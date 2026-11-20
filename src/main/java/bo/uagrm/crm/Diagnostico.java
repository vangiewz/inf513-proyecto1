package bo.uagrm.crm;

import bo.uagrm.crm.persistencia.DatabaseService;
import bo.uagrm.crm.presentacion.Pop3Client;
import bo.uagrm.crm.presentacion.SmtpSender;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Traduce una falla de red, de POP3, de SMTP o de PostgreSQL a su causa probable, para el log del
 * servicio. Con --diagnostico (y al arrancar) revisa paso a paso DNS, TCP, POP3, SMTP y la BD.
 */
public final class Diagnostico {

  private static final Logger LOG = Logger.getLogger("crm");
  private static final Pattern IP_PG_HBA = Pattern.compile("host \"([^\"]+)\"");

  private Diagnostico() {}

  /** Causa probable de la falla de una etapa contra host:puerto, en una línea. */
  public static String explicar(String host, int puerto, Throwable e) {
    return unaLinea(causa(host, puerto, e));
  }

  /** Une en una línea los mensajes de varias líneas (servidores SMTP, PostgreSQL) para el log. */
  public static String unaLinea(String texto) {
    return texto == null ? "" : texto.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
  }

  private static String causa(String host, int puerto, Throwable e) {
    String destino = host + ":" + puerto;
    // Primero la causa de red más profunda: suele venir envuelta (PSQLException, MessagingException).
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof UnknownHostException)
        return "DNS: no se resuelve " + host + " (sin Internet o DNS caído; pruebe nslookup " + host + ")";
      if (t instanceof ConnectException)
        return "conexión rechazada en " + destino + ": servicio detenido o puerto cerrado";
      if (t instanceof NoRouteToHostException)
        return "sin ruta hacia " + destino + ": red caída o firewall";
      if (t instanceof SocketTimeoutException)
        return "sin respuesta de " + destino + " a tiempo: firewall, IP bloqueada o servidor saturado";
      if (t instanceof java.net.SocketException) // después de sus subclases Connect/NoRouteToHost
        return destino
            + " cortó la conexión ("
            + t.getMessage()
            + "): servicio reiniciándose, firewall o límite de conexiones";
      if (t instanceof SSLException)
        return "falla TLS con " + destino + ": " + t.getMessage();
      if (t instanceof AuthenticationFailedException)
        return "rechazó el login: contraseña de aplicación de Gmail revocada o cuenta bloqueada";
      if (t instanceof SendFailedException f) {
        // El detalle útil (550 5.1.1, 550 5.4.5 cuota diaria...) viene en la excepción anidada.
        String detalle = f.getNextException() != null ? f.getNextException().getMessage() : f.getMessage();
        return f.getInvalidAddresses() != null && f.getInvalidAddresses().length > 0
            ? "SMTP rechazó el destinatario: " + detalle
            : "SMTP rechazó el envío (¿cuota diaria de Gmail o remitente bloqueado?): " + detalle;
      }
      if (String.valueOf(t.getMessage()).contains(" 421"))
        return "SMTP " + destino + " no disponible temporalmente (421); se reintenta en el próximo ciclo";
    }
    for (Throwable t = e; t != null; t = t.getCause())
      if (t instanceof SQLException s && s.getSQLState() != null) return postgres(s);
    String m = String.valueOf(e.getMessage());
    if (m.contains("POP3")) return pop3(m);
    return e.getClass().getSimpleName() + ": " + m;
  }

  private static String postgres(SQLException s) {
    String m = String.valueOf(s.getMessage());
    return switch (s.getSQLState()) {
      case "28000" -> {
        Matcher ip = IP_PG_HBA.matcher(m);
        yield m.contains("pg_hba")
            ? "PostgreSQL: la IP "
                + (ip.find() ? ip.group(1) : "de este equipo")
                + " no está autorizada en pg_hba.conf"
            : "PostgreSQL rechazó la autenticación: " + m;
      }
      case "28P01" -> "PostgreSQL: usuario o contraseña incorrectos (¿cambiaron la clave?)";
      case "3D000" -> "PostgreSQL: la base de datos no existe: " + m;
      case "3F000" -> "PostgreSQL: el esquema no existe (revise currentSchema en db.url)";
      case "42P01" -> "PostgreSQL: " + m;
      case "42703" -> "PostgreSQL: falta una columna del modelo: " + m;
      case "42501" -> "PostgreSQL: permiso denegado (¿REVOKE sobre la tabla?): " + m;
      case "53300" -> "PostgreSQL: conexiones agotadas en el servidor";
      case "57P01", "57P02", "57P03" -> "PostgreSQL se está reiniciando o apagando";
      case "08004" -> "PostgreSQL rechazó la conexión: " + m;
      default ->
          s.getSQLState().startsWith("08")
              ? "PostgreSQL: falla de conexión: " + m
              : "PostgreSQL " + s.getSQLState() + ": " + m;
    };
  }

  private static String pop3(String m) {
    // Dovecot responde [IN-USE] en el PASS: se revisa antes que el login.
    if (m.contains("[IN-USE]") || m.contains("[SYS/TEMP]") || m.contains("[LOGIN-DELAY]"))
      return "POP3: buzón bloqueado por otra sesión (¿otro lector activo, p. ej. el del VPS?) (" + m + ")";
    if (m.contains("[AUTH]") || m.contains("PASS") || m.contains("USER"))
      return "POP3 rechazó el login: usuario o contraseña del buzón, o cuenta deshabilitada (" + m + ")";
    if (m.contains("[SYS/PERM]")) return "POP3: error permanente del servidor de correo (" + m + ")";
    if (m.endsWith("null")) return "POP3: el servidor cerró la conexión sin responder";
    return m;
  }

  /** Revisión completa paso a paso; devuelve true si todo está bien. */
  public static boolean revisar(Config cfg, SmtpSender smtp, DatabaseService db) {
    boolean ok = red(cfg.pop3Host(), cfg.pop3Puerto());
    ok &= paso("POP3", cfg.pop3Host(), cfg.pop3Puerto(), () -> {
      try (Pop3Client pop =
          new Pop3Client(cfg.pop3Host(), cfg.pop3Puerto(), cfg.pop3Usuario(), cfg.pop3Password())) {
        String r = "login " + cfg.pop3Usuario() + " OK, " + pop.stat() + " mensaje(s) en el buzón";
        pop.quit();
        return r;
      }
    });
    ok &= red(cfg.smtpHost(), cfg.smtpPuerto());
    ok &= paso("SMTP", cfg.smtpHost(), cfg.smtpPuerto(), () -> {
      smtp.verificar();
      return "login STARTTLS de " + Config.oculto(cfg.smtpUsuario()) + " OK";
    });
    ok &= red(cfg.dbHost(), cfg.dbPuerto());
    ok &= paso("BD", cfg.dbHost(), cfg.dbPuerto(), () -> String.join(", ", db.diagnostico()));
    if (ok) LOG.info("Diagnóstico: todo en orden.");
    else LOG.warning("Diagnóstico: hay fallas, ver las líneas FALLA anteriores.");
    return ok;
  }

  private static boolean red(String host, int puerto) {
    return paso("DNS", host, puerto, () -> host + " → " + ips(host))
        && paso("TCP", host, puerto, () -> {
          try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, puerto), 10_000);
            return host + ":" + puerto + " abierto";
          }
        });
  }

  /** IP(s) del host, ordenadas, para comparar entre ciclos. */
  static String ips(String host) throws UnknownHostException {
    return String.join(
        " ",
        Arrays.stream(InetAddress.getAllByName(host)).map(InetAddress::getHostAddress).sorted().toList());
  }

  @FunctionalInterface
  private interface Paso {
    String hacer() throws Exception;
  }

  private static boolean paso(String etapa, String host, int puerto, Paso p) {
    long t0 = System.nanoTime();
    try {
      String r = p.hacer();
      LOG.info(String.format("%-4s OK    %s (%d ms)", etapa, r, (System.nanoTime() - t0) / 1_000_000));
      return true;
    } catch (Exception e) {
      LOG.severe(String.format("%-4s FALLA %s", etapa, explicar(host, puerto, e)));
      return false;
    }
  }
}
