package bo.uagrm.crm;

import bo.uagrm.crm.aplicacion.Command;
import bo.uagrm.crm.aplicacion.Comando;
import bo.uagrm.crm.aplicacion.CommandProcessor;
import bo.uagrm.crm.aplicacion.ResponseFormatter;
import bo.uagrm.crm.aplicacion.Respuesta;
import bo.uagrm.crm.aplicacion.Respuesta.Aviso;
import bo.uagrm.crm.aplicacion.RolCommandProcessor;
import bo.uagrm.crm.dominio.seguimiento.Recordatorio;
import bo.uagrm.crm.dominio.seguimiento.SeguimientoService;
import bo.uagrm.crm.persistencia.DatabaseService;
import bo.uagrm.crm.presentacion.Bandeja;
import bo.uagrm.crm.presentacion.Correo;
import bo.uagrm.crm.presentacion.Pop3Client;
import bo.uagrm.crm.presentacion.Rebotes;
import bo.uagrm.crm.presentacion.SmtpSender;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * CRM vía e-mail (Proyecto 1, INF513 Grupo 05 SA). Cada intervalo: lee el buzón
 * grupo05sa@tecnoweb.org.bo por POP3, guarda cada correo en la BD antes de borrarlo, lo procesa
 * desde la BD y envía las respuestas en cola por SMTP, reintentando las que fallen.
 *
 * <p>Uso: java -jar crm-mail.jar crm-mail.properties · java -jar crm-mail.jar --diagnostico
 * crm-mail.properties · java -jar crm-mail.jar --probar crm-mail.properties remitente@correo
 * "COMANDO[...]" [archivo] · java -jar crm-mail.jar --anexo
 *
 * <p>Log: consola y logs/crm-mail.N.log (5 MB x 5, rotativo). Por correo: RECIBIDO (guardado en la
 * BD), PROCESADO/CONFIRMADO (operación y respuesta en cola, un solo COMMIT), ENVIADO, PENDIENTE
 * (reintento programado) o FALLIDO. Por servidor: FALLA, "sigue la falla" y RECUPERADO.
 */
public final class App {

  private static final int MAX_POR_CICLO = 20;
  private static final int MAX_PROCESO_POR_CICLO = 50;
  // ponytail: Gmail limita los login IMAP frecuentes; los rebotes tardan minutos en llegar.
  private static final int CICLOS_ENTRE_REBOTES = 6;
  private static final long MAX_BYTES =
      8L * 1024 * 1024; // correos más grandes se rechazan sin descargarlos
  private static final int ESPERA_MAXIMA_SEGUNDOS = 60;
  private static final Logger LOG = Logger.getLogger("crm");

  private final Config cfg;
  private final CommandProcessor procesador;
  private final SeguimientoService seguimiento;
  private final SmtpSender smtp;
  private final ResponseFormatter fmt;
  private final DatabaseService db;
  private final Bandeja bandeja;
  private final Rebotes rebotes;

  private long ciclo;
  /** Fallas en curso por servidor (BD, POP3, SMTP, IMAP), para informar cambios y no repetir. */
  private final Map<String, Falla> fallas = new LinkedHashMap<>();

  private final Map<String, String> ipVista = new HashMap<>(); // host vigilado -> IP(s) resueltas

  private record Falla(String detalle, Instant desde, int ciclos) {}

  App(Config cfg) {
    this.cfg = cfg;
    this.db = new DatabaseService(cfg.dbUrl(), cfg.dbUsuario(), cfg.dbPassword());
    this.procesador = new CommandProcessor(db);
    this.seguimiento = new SeguimientoService(db);
    this.bandeja = new Bandeja(db);
    this.fmt = procesador.formatter();
    this.smtp =
        new SmtpSender(
            cfg.smtpHost(),
            cfg.smtpPuerto(),
            cfg.smtpUsuario(),
            cfg.smtpPassword(),
            cfg.buzonSistema());
    this.rebotes = new Rebotes(cfg.smtpHost(), cfg.smtpUsuario(), cfg.smtpPassword());
  }

  public static void main(String[] args) throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone("America/La_Paz"));
    if (args.length == 1 && args[0].equals("--anexo")) {
      anexo();
      return;
    }
    if (args.length == 2 && args[0].equals("--diagnostico")) {
      configurarLog(false);
      App app = new App(Config.cargar(Path.of(args[1])));
      boolean ok = Diagnostico.revisar(app.cfg, app.smtp, app.rebotes, app.db);
      app.informarBandeja();
      System.exit(ok ? 0 : 1);
    }
    if (args.length >= 4 && args[0].equals("--probar")) {
      App app = new App(Config.cargar(Path.of(args[1])));
      String csv =
          args.length > 4 && args[4].toLowerCase(java.util.Locale.ROOT).endsWith(".csv")
              ? Files.readString(Path.of(args[4]), StandardCharsets.UTF_8)
              : null;
      bo.uagrm.crm.presentacion.Adjunto archivo = null;
      if (args.length > 4 && !args[4].toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
        csv = null;
        archivo =
            new bo.uagrm.crm.presentacion.Adjunto(
                Path.of(args[4]).getFileName().toString(),
                "application/octet-stream",
                Files.readAllBytes(Path.of(args[4])));
      }
      Respuesta r =
          app.procesador.procesarEmail(args[2].toLowerCase(), args[3], csv, archivo, null);
      System.out.println(r == null ? "(ignorado: no se responde)" : app.fmt.texto(r, args[3]));
      if (r != null)
        Files.writeString(
            Path.of("respuesta.html"), app.fmt.html(r, args[3]), StandardCharsets.UTF_8);
      return;
    }
    configurarLog(true);
    Config cfg = Config.cargar(Path.of(args.length > 0 ? args[0] : "crm-mail.properties"));
    log("CRM vía e-mail iniciado. " + cfg);
    App app = new App(cfg);
    Diagnostico.revisar(cfg, app.smtp, app.rebotes, app.db); // informa; el servicio arranca igual
    app.informarBandeja();
    app.ejecutar();
  }

  /** Consola y, en modo servicio, archivo rotativo logs/crm-mail.N.log, ambos en UTF-8. */
  private static void configurarLog(boolean archivo) throws IOException {
    System.setProperty(
        "java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s %5$s%6$s%n");
    Logger raiz = Logger.getLogger("");
    raiz.setLevel(Level.WARNING); // librerías: solo advertencias
    for (Handler h : raiz.getHandlers()) raiz.removeHandler(h);
    LOG.setLevel(Level.INFO);
    LOG.setUseParentHandlers(false);
    ConsoleHandler consola = new ConsoleHandler();
    consola.setFormatter(new SimpleFormatter());
    consola.setEncoding("UTF-8");
    LOG.addHandler(consola);
    if (archivo) {
      Files.createDirectories(Path.of("logs"));
      FileHandler f = new FileHandler("logs/crm-mail.%g.log", 5_000_000, 5, true);
      f.setFormatter(new SimpleFormatter());
      f.setEncoding("UTF-8");
      LOG.addHandler(f);
    }
  }

  /** Al arrancar: lo que quedó pendiente de una ejecución anterior (caída, corte de red). */
  private void informarBandeja() {
    try {
      log("Bandeja: " + bandeja.estado());
    } catch (SQLException e) {
      LOG.warning("Bandeja: no se pudo leer (" + Diagnostico.explicar(cfg.dbHost(), cfg.dbPuerto(), e) + ")");
    }
  }

  private void ejecutar() throws InterruptedException {
    java.security.Security.setProperty("networkaddress.cache.ttl", "10"); // ver cambios de DNS pronto
    while (true) {
      ciclo++;
      vigilarDns(cfg.pop3Host()); // smtp.gmail.com rota sus IP: no se vigila
      vigilarDns(cfg.dbHost());
      try {
        String resumen = ciclo();
        if (resumen != null)
          log(
              "ciclo "
                  + ciclo
                  + (fallas.isEmpty() ? " OK" : " CON FALLAS (" + String.join(", ", fallas.keySet()) + ")")
                  + " · "
                  + resumen);
      } catch (RuntimeException e) {
        LOG.log(Level.SEVERE, "ciclo " + ciclo + " falla inesperada", e);
      }
      Thread.sleep(espera() * 1000L);
    }
  }

  /**
   * Espera entre ciclos: el intervalo configurado; si un servidor sigue fallando se duplica hasta
   * 60 s, para no insistir contra un servidor caído o saturado.
   */
  int espera() {
    int peor = fallas.values().stream().mapToInt(Falla::ciclos).max().orElse(0);
    int s = cfg.intervaloSegundos() << Math.min(Math.max(peor - 1, 0), 3);
    return Math.max(cfg.intervaloSegundos(), Math.min(s, ESPERA_MAXIMA_SEGUNDOS));
  }

  /** La primera vez se informa completa; mientras siga igual, una línea corta por ciclo. */
  private void falla(String etapa, String detalle) {
    Falla f = fallas.get(etapa);
    if (f == null || !f.detalle().equals(detalle)) {
      fallas.put(etapa, new Falla(detalle, f == null ? Instant.now() : f.desde(), 1));
      LOG.severe("ciclo " + ciclo + " FALLA " + etapa + " " + detalle);
    } else {
      fallas.put(etapa, new Falla(detalle, f.desde(), f.ciclos() + 1));
      LOG.warning(
          "ciclo "
              + ciclo
              + " sigue la falla "
              + etapa
              + " ("
              + (f.ciclos() + 1)
              + " ciclos, "
              + duracion(f.desde())
              + "; próximo intento en "
              + espera()
              + " s)");
    }
  }

  private void sinFalla(String etapa) {
    Falla f = fallas.remove(etapa);
    if (f != null)
      log(
          "RECUPERADO "
              + etapa
              + " tras "
              + f.ciclos()
              + " ciclo(s) con falla ("
              + duracion(f.desde())
              + "): "
              + f.detalle());
  }

  /** Si el DNS de un host de la facultad cambia o deja de resolver, queda en el log. */
  private void vigilarDns(String host) {
    String ahora;
    try {
      ahora = Diagnostico.ips(host);
    } catch (UnknownHostException e) {
      ahora = "sin resolución";
    }
    String antes = ipVista.put(host, ahora);
    if (antes != null && !antes.equals(ahora))
      LOG.warning("DNS " + host + " cambió: " + antes + " → " + ahora);
  }

  private static String duracion(Instant desde) {
    long s = Duration.between(desde, Instant.now()).toSeconds();
    return String.format("%d:%02d", s / 60, s % 60);
  }

  private static long ms(long t0) {
    return (System.nanoTime() - t0) / 1_000_000;
  }

  private String bd(Exception e) {
    return cfg.dbHost() + ":" + cfg.dbPuerto() + " → " + Diagnostico.explicar(cfg.dbHost(), cfg.dbPuerto(), e);
  }

  /**
   * Un ciclo: BD → POP3 (guardar y borrar) → procesar → recordatorios → enviar la cola → rebotes.
   * Devuelve el resumen, o null si la BD no está disponible (entonces no se toca el buzón).
   */
  String ciclo() {
    long t0 = System.nanoTime();
    try {
      db.verificarModelo(); // sin BD no se retira ningún mensaje del buzón
    } catch (Exception e) {
      falla("BD", bd(e));
      return null;
    }
    long tBd = ms(t0);
    t0 = System.nanoTime();
    String pop3 = descargar();
    if (pop3 == null) return null; // la BD falló al guardar
    pop3 += " " + ms(t0) + " ms";
    int procesados;
    int avisos;
    try {
      procesados = procesarPendientes();
      avisos = encolarRecordatorios();
    } catch (SQLException e) {
      falla("BD", bd(e));
      return null;
    }
    sinFalla("BD");
    int enviados = enviarCola();
    if (ciclo % CICLOS_ENTRE_REBOTES == 1) revisarRebotes();
    String resumen =
        "BD " + tBd + " ms · POP3 " + pop3 + " · procesados " + procesados + " · enviados " + enviados;
    if (avisos > 0) resumen += " · " + avisos + " recordatorio(s) en cola";
    try {
      Bandeja.Estado e = bandeja.estado();
      if (e.recibidos() + e.pendientes() + e.fallidos() > 0) resumen += " · " + e;
    } catch (SQLException e) {
      // el resumen es informativo; la próxima vuelta se vuelve a intentar
    }
    return resumen;
  }

  /**
   * Descarga hasta MAX_POR_CICLO correos. Cada uno se guarda en la BD (confirmado) y recién después
   * recibe su DELE; QUIT al final. Si la BD falla al guardar, la sesión se corta sin QUIT y el
   * servidor conserva todos los mensajes (los ya guardados no se duplican: message_id es único).
   * Devuelve el texto para el resumen, o null si la BD falló.
   */
  private String descargar() {
    int guardados = 0, yaGuardados = 0, enBuzon;
    try (Pop3Client pop =
        new Pop3Client(cfg.pop3Host(), cfg.pop3Puerto(), cfg.pop3Usuario(), cfg.pop3Password())) {
      enBuzon = pop.stat();
      int n = Math.min(enBuzon, MAX_POR_CICLO);
      for (int i = 1; i <= n; i++) {
        long bytes = pop.tamano(i);
        byte[] crudo = bytes > MAX_BYTES ? pop.top(i, 0) : pop.retr(i);
        String id = Correo.idMensaje(crudo);
        Correo c = Correo.leer(crudo);
        Long nro;
        try {
          nro = bandeja.guardar(id, c.remitente(), c.asunto(), crudo, bytes);
        } catch (SQLException e) {
          falla("BD", bd(e) + " (al guardar un correo; el buzón lo conserva)");
          return null; // sin QUIT: no se confirma ningún DELE
        }
        pop.dele(i);
        if (nro == null) {
          yaGuardados++;
          log("YA GUARDADO " + id + " · " + c.remitente() + ": se borra del buzón sin repetirlo");
        } else {
          guardados++;
          log(
              "RECIBIDO registro #"
                  + nro
                  + " · "
                  + (c.remitente().isEmpty() ? "(sin remitente)" : c.remitente())
                  + " · "
                  + c.asunto()
                  + " ("
                  + bytes / 1024
                  + " KB, guardado antes de borrarlo del buzón)");
        }
      }
      pop.quit(); // confirma los DELE; si falla, los correos ya están guardados en la BD
    } catch (Exception e) {
      falla(
          "POP3",
          cfg.pop3Host()
              + ":"
              + cfg.pop3Puerto()
              + " → "
              + Diagnostico.explicar(cfg.pop3Host(), cfg.pop3Puerto(), e)
              + (guardados + yaGuardados > 0
                  ? " (" + (guardados + yaGuardados) + " correo(s) ya guardados en la BD)"
                  : ""));
      return guardados + " msg (falla POP3)";
    }
    sinFalla("POP3");
    String r = guardados + " msg";
    if (yaGuardados > 0) r += " + " + yaGuardados + " ya guardado(s)";
    if (enBuzon > guardados + yaGuardados)
      r += " (quedan " + (enBuzon - guardados - yaGuardados) + " en el buzón)";
    return r;
  }

  /**
   * Procesa los correos guardados (también los que quedaron de una caída). Cada uno en una
   * transacción: operación, cierre del registro y respuesta en cola se confirman juntos o no se
   * confirma nada y el correo sigue 'Recibido'. Una falla de conexión corta el proceso (se reintenta
   * en el próximo ciclo); otra falla deja el correo con resultado 'Error' para no repetirla sin fin.
   */
  private int procesarPendientes() throws SQLException {
    int n = 0;
    for (long nro : bandeja.porProcesar(MAX_PROCESO_POR_CICLO)) {
      try {
        List<String> lineas = db.enTransaccion(c -> procesarUno(c, nro));
        lineas.forEach(App::log);
        if (!lineas.isEmpty()) n++;
      } catch (Exception e) {
        if (DatabaseService.esDeConexion(e))
          throw e instanceof SQLException s ? s : new SQLException("conexión perdida", "08006", e);
        LOG.log(Level.SEVERE, "No se pudo procesar el registro #" + nro + "; queda con Error", e);
        try {
          db.enTransaccion(
              c -> {
                if (bandeja.tomar(c, nro) != null)
                  bandeja.cerrar(
                      c, nro, null, "Error", "Fallo interno al procesar: " + e.getMessage());
                return null;
              });
        } catch (SQLException grave) {
          if (DatabaseService.esDeConexion(grave)) throw grave;
          LOG.severe("Tampoco se pudo marcar el registro #" + nro + ": " + grave.getMessage());
        }
      }
    }
    return n;
  }

  /** Un correo guardado → resultado en la bitácora y respuesta(s) en cola. Devuelve las líneas del log. */
  private List<String> procesarUno(Connection c, long nro) throws SQLException, MessagingException {
    Bandeja.Entrante e = bandeja.tomar(c, nro);
    if (e == null) return List.of(); // ya procesado o tomado por otra instancia
    Correo m = Correo.leer(e.contenido());
    String rem = m.remitente(), asunto = m.asunto();
    List<String> log = new ArrayList<>();
    if (rem.isEmpty()) {
      bandeja.cerrar(c, nro, null, "Error", "Correo ilegible: sin una dirección de remitente válida");
      log.add("ILEGIBLE registro #" + nro + " sin remitente · " + asunto + " (no se responde)");
      return log;
    }
    if (m.automatico() || rem.equalsIgnoreCase(smtp.getRemitente())) {
      bandeja.cerrar(c, nro, null, "Ignorado", "Respuesta automática o correo del propio sistema");
      log.add("IGNORADO registro #" + nro + " (automático o propio) · " + rem + " · " + asunto);
      return log;
    }
    if (e.bytes() > MAX_BYTES) {
      Respuesta r =
          fmt.error(
              "Correo",
              "El correo pesa "
                  + (e.bytes() / 1024)
                  + " KB y el límite es "
                  + (MAX_BYTES / 1024)
                  + " KB. Vuelva a enviar el comando con un adjunto más liviano.");
      bandeja.cerrar(c, nro, null, "Error", r.mensaje());
      long k = encolar(c, nro, rem, r, asunto, e.messageId());
      log.add("PROCESADO registro #" + nro + " Error (correo demasiado grande) · " + rem + " → respuesta en cola (correo #" + k + ")");
      return log;
    }
    Command cmd = procesador.interpretar(asunto, rem, null);
    if (cmd.isValido() && cmd.getTipo().escritura()) {
      Long previo = bandeja.repetido(c, nro, rem, asunto);
      if (previo != null) {
        Long k = bandeja.reenviar(c, previo, nro, rem);
        bandeja.cerrar(
            c,
            nro,
            null,
            "Repetido",
            "Reintento del registro #"
                + previo
                + ": no se volvió a ejecutar"
                + (k == null ? "; su respuesta sigue en cola" : "; se reenvía su respuesta"));
        log.add(
            "REPETIDO registro #"
                + nro
                + " = registro #"
                + previo
                + " · "
                + rem
                + " · "
                + asunto
                + " (no se ejecuta otra vez; "
                + (k == null ? "la respuesta original sigue en cola)" : "respuesta original reenviada como correo #" + k + ")"));
        return log;
      }
    }
    CommandProcessor.Atendido a =
        db.aislado(
            () -> procesador.atender(rem, asunto, m.adjuntoCsv(), m.adjunto(), m.errorAdjunto()));
    if (a.respuesta() == null) {
      bandeja.cerrar(c, nro, null, a.resultado(), a.detalle());
      log.add("IGNORADO registro #" + nro + " · " + rem + " · " + asunto + " (" + a.detalle() + ")");
      return log;
    }
    Respuesta r = a.respuesta();
    bandeja.cerrar(c, nro, a.usuarioCorreo(), a.resultado(), a.detalle());
    long k = encolar(c, nro, rem, r, asunto, e.messageId());
    log.add(
        (r.resultado().equals("Éxito") ? "CONFIRMADO en BD" : "PROCESADO")
            + " registro #"
            + nro
            + " "
            + r.resultado()
            + " · "
            + rem
            + " · "
            + asunto
            + " → respuesta en cola (correo #"
            + k
            + ")");
    for (Aviso av : r.avisos()) {
      long ka = encolar(c, nro, av.para(), av.contenido(), null, null);
      log.add("  aviso a " + av.para() + " en cola (correo #" + ka + ")");
    }
    return log;
  }

  private long encolar(
      Connection c, long nro, String para, Respuesta r, String asuntoRecibido, String enRespuestaA)
      throws SQLException, MessagingException {
    byte[] mime =
        smtp.preparar(
            para,
            r.asunto(),
            fmt.html(r, asuntoRecibido),
            fmt.texto(r, asuntoRecibido),
            r.archivos(),
            enRespuestaA);
    return bandeja.encolar(c, nro, null, para, r.asunto(), mime);
  }

  /**
   * RF4.3: cada recordatorio vencido pasa a la cola y queda marcado como notificado en la misma
   * transacción (sin pérdida ni duplicado, aunque haya dos instancias). Devuelve cuántos.
   */
  private int encolarRecordatorios() throws SQLException {
    List<String> lineas;
    try {
      lineas = encolarVencidos();
    } catch (MessagingException e) {
      throw new IllegalStateException("No se pudo armar el aviso de un recordatorio", e);
    }
    lineas.forEach(App::log);
    return lineas.size();
  }

  private List<String> encolarVencidos() throws SQLException, MessagingException {
    return db.enTransaccion(
            c -> {
              List<String> out = new ArrayList<>();
              for (Recordatorio r : seguimiento.vencidos()) {
                Respuesta aviso =
                    fmt.aviso(
                        "Recordatorio: " + r.descripcion(),
                        "Hola, "
                            + r.usuario()
                            + ". Tiene pendiente: "
                            + r.descripcion()
                            + ". Prospecto "
                            + r.prospecto()
                            + (r.telefono() == null ? "" : ", teléfono " + r.telefono())
                            + " (tarjeta #"
                            + r.nroTarjeta()
                            + "). Al terminar envíe COMPLETARRECORDATORIO[\""
                            + r.nro()
                            + "\"].");
                byte[] mime =
                    smtp.preparar(
                        r.correoUsuario(),
                        aviso.asunto(),
                        fmt.html(aviso, null),
                        fmt.texto(aviso, null),
                        List.of(),
                        null);
                long k =
                    bandeja.encolar(
                        c, null, (long) r.nro(), r.correoUsuario(), aviso.asunto(), mime);
                seguimiento.marcarNotificado(r.nro());
                out.add("Recordatorio " + r.nro() + " para " + r.correoUsuario() + " en cola (correo #" + k + ")");
              }
              return out;
            });
  }

  /** Resultado de intentar un correo de la cola. */
  private record Envio(boolean enviado, boolean cortar) {}

  /**
   * Envía los correos en cola cuyo turno llegó. Una falla de conexión o de login con el SMTP
   * corta la vuelta (no se insiste con los demás); el correo espera 1, 2, 4… hasta 60 min.
   */
  private int enviarCola() {
    int enviados = 0;
    try {
      for (long k : bandeja.porEnviar(MAX_POR_CICLO)) {
        Envio r = db.enTransaccion(c -> enviarUno(c, k));
        if (r.enviado()) enviados++;
        if (r.cortar()) return enviados;
      }
      if (enviados > 0) sinFalla("SMTP");
    } catch (SQLException e) {
      // Si el envío salió pero la BD no lo pudo marcar, el correo se volverá a enviar (mismo
      // Message-ID): se prefiere un duplicado a una respuesta perdida.
      falla("BD", bd(e) + " (al marcar la cola de salida)");
    }
    return enviados;
  }

  private Envio enviarUno(Connection c, long k) throws SQLException {
    Bandeja.Saliente s = bandeja.tomarSaliente(c, k);
    if (s == null) return new Envio(false, false);
    String origen = s.nroRegistro() == null ? "recordatorio" : "registro #" + s.nroRegistro();
    try {
      smtp.enviarPreparado(s.mensaje());
      bandeja.enviado(c, k);
      log("ENVIADO correo #" + k + " a " + s.destinatario() + " (" + origen + ", intento " + (s.intentos() + 1) + ")");
      return new Envio(true, false);
    } catch (MessagingException e) {
      String causa = Diagnostico.explicar(cfg.smtpHost(), cfg.smtpPuerto(), e);
      boolean definitivo = definitivo(e);
      Integer minutos = bandeja.fallo(c, k, s.intentos(), causa, definitivo);
      if (minutos == null)
        LOG.severe(
            "FALLIDO correo #" + k + " a " + s.destinatario() + " (" + origen + ") tras "
                + (s.intentos() + 1) + " intento(s) → " + causa + ". La operación ya estaba confirmada; no se repite.");
      else
        LOG.warning(
            "PENDIENTE correo #" + k + " a " + s.destinatario() + " (" + origen + "), intento "
                + (s.intentos() + 1) + " falló → " + causa + "; próximo intento en " + minutos + " min");
      if (!definitivo) falla("SMTP", cfg.smtpHost() + ":" + cfg.smtpPuerto() + " → " + causa);
      return new Envio(false, !definitivo);
    }
  }

  /** Rechazo que no se arregla reintentando: la dirección del destinatario no existe o es inválida. */
  static boolean definitivo(MessagingException e) {
    if (e instanceof SendFailedException s
        && s.getInvalidAddresses() != null
        && s.getInvalidAddresses().length > 0) return true;
    for (Throwable t = e; t != null; t = t.getCause())
      if (t instanceof jakarta.mail.internet.AddressException) return true;
    return false;
  }

  /**
   * Respuestas que Gmail aceptó pero el servidor del destinatario rechazó después: el correo de
   * la cola pasa a 'Rebotado'. Si IMAP no responde se avisa una vez y el ciclo sigue.
   */
  private void revisarRebotes() {
    try {
      for (Rebotes.Rebote r : rebotes.nuevos()) {
        int marcados = bandeja.rebote(r.destinatario(), r.motivo());
        LOG.warning(
            "REBOTE respuesta a " + r.destinatario() + " no entregada → " + r.motivo()
                + (marcados > 0 ? " (correo de la cola marcado Rebotado)" : ""));
        procesador.bitacora(
            r.destinatario(), null, "(rebote)", "Error", "La respuesta no se entregó: " + r.motivo());
      }
      sinFalla("IMAP");
    } catch (Exception e) {
      falla("IMAP", rebotes.host() + ":993 → " + Diagnostico.explicar(rebotes.host(), 993, e) + " (lectura de rebotes)");
    }
  }

  /** Matriz de acceso (Anexo: comandos por rol), generada del mismo código que la aplica. */
  private static void anexo() {
    CommandProcessor p = new CommandProcessor(new DatabaseService("", "", ""));
    String[] roles = {"administrador", "coordinador", "vendedor", "público"};
    Map<String, RolCommandProcessor> m = p.procesadores();
    System.out.println("CU\tComando\tAdministrador\tCoordinador\tVendedor\tPúblico\tEjemplo");
    for (Comando k : Comando.values()) {
      StringBuilder sb = new StringBuilder(k.cu).append('\t').append(k.firma());
      for (String r : roles) sb.append('\t').append(m.get(r).comandos().contains(k) ? "Sí" : "—");
      System.out.println(sb.append('\t').append(k.ejemplo));
    }
  }

  private static void log(String s) {
    LOG.info(s);
  }
}
