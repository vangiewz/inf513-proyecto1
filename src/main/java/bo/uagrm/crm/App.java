package bo.uagrm.crm;

import bo.uagrm.crm.aplicacion.Comando;
import bo.uagrm.crm.aplicacion.CommandProcessor;
import bo.uagrm.crm.aplicacion.ResponseFormatter;
import bo.uagrm.crm.aplicacion.Respuesta;
import bo.uagrm.crm.aplicacion.Respuesta.Aviso;
import bo.uagrm.crm.aplicacion.RolCommandProcessor;
import bo.uagrm.crm.dominio.seguimiento.Recordatorio;
import bo.uagrm.crm.dominio.seguimiento.SeguimientoService;
import bo.uagrm.crm.persistencia.DatabaseService;
import bo.uagrm.crm.presentacion.Correo;
import bo.uagrm.crm.presentacion.Pop3Client;
import bo.uagrm.crm.presentacion.Rebotes;
import bo.uagrm.crm.presentacion.SmtpSender;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 * grupo05sa@tecnoweb.org.bo por POP3, procesa el comando del asunto, responde al remitente por SMTP
 * y avisa los recordatorios vencidos.
 *
 * <p>Uso: java -jar crm-mail.jar crm-mail.properties · java -jar crm-mail.jar --diagnostico
 * crm-mail.properties · java -jar crm-mail.jar --probar crm-mail.properties remitente@correo
 * "COMANDO[...]" [archivo] · java -jar crm-mail.jar --anexo
 *
 * <p>Log: consola y logs/crm-mail.N.log (5 MB x 5, rotativo). Una línea por ciclo con la latencia de
 * cada etapa; ante una falla, la etapa, el destino y la causa probable (ver {@link Diagnostico}).
 */
public final class App {

  private static final int MAX_POR_CICLO = 20;
  // ponytail: Gmail limita los login IMAP frecuentes; los rebotes tardan minutos en llegar.
  private static final int CICLOS_ENTRE_REBOTES = 6;
  private static final long MAX_BYTES =
      8L * 1024 * 1024; // correos más grandes se rechazan sin descargarlos
  private static final Logger LOG = Logger.getLogger("crm");

  private final Config cfg;
  private final CommandProcessor procesador;
  private final SeguimientoService seguimiento;
  private final SmtpSender smtp;
  private final ResponseFormatter fmt;
  private final DatabaseService db;
  private final Rebotes rebotes;
  private String fallaRebotes; // última falla al leer rebotes, para no repetirla cada vez

  // Estado entre ciclos, para informar cambios y no solo errores sueltos.
  private long ciclo;
  private String falla; // "etapa destino → causa" de la falla en curso; null si el último ciclo fue bien
  private Instant fallaDesde;
  private int fallaCiclos;
  private final Map<String, String> ipVista = new HashMap<>(); // host vigilado -> IP(s) resueltas

  App(Config cfg) {
    this.cfg = cfg;
    this.db = new DatabaseService(cfg.dbUrl(), cfg.dbUsuario(), cfg.dbPassword());
    this.procesador = new CommandProcessor(db);
    this.seguimiento = new SeguimientoService(db);
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
      System.exit(Diagnostico.revisar(app.cfg, app.smtp, app.rebotes, app.db) ? 0 : 1);
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
    Diagnostico.revisar(cfg, app.smtp, app.rebotes, app.db); // informa; el servicio arranca igual y reintenta
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

  private void ejecutar() throws InterruptedException {
    java.security.Security.setProperty("networkaddress.cache.ttl", "10"); // ver cambios de DNS pronto
    while (true) {
      ciclo++;
      vigilarDns(cfg.pop3Host()); // smtp.gmail.com rota sus IP: no se vigila
      vigilarDns(cfg.dbHost());
      try {
        String resumen = ciclo();
        if (falla != null)
          log(
              "RECUPERADO tras "
                  + fallaCiclos
                  + " ciclo(s) con falla ("
                  + duracion(fallaDesde)
                  + "): "
                  + falla);
        falla = null;
        log("ciclo " + ciclo + " OK · " + resumen);
      } catch (EtapaFallida e) {
        registrarFalla(e.etapa + " " + e.destino + " → " + e.causa);
      } catch (RuntimeException e) {
        registrarFalla("inesperada → " + e);
      }
      Thread.sleep(cfg.intervaloSegundos() * 1000L);
    }
  }

  /** La primera vez se informa completa; mientras siga igual, una línea corta por ciclo. */
  private void registrarFalla(String detalle) {
    if (!detalle.equals(falla)) {
      if (falla == null) fallaDesde = Instant.now();
      falla = detalle;
      fallaCiclos = 1;
      LOG.severe("ciclo " + ciclo + " FALLA " + detalle);
    } else {
      fallaCiclos++;
      LOG.warning(
          "ciclo "
              + ciclo
              + " sigue la falla ("
              + fallaCiclos
              + " ciclos, "
              + duracion(fallaDesde)
              + "): "
              + falla.substring(0, Math.max(0, falla.indexOf(" → "))));
    }
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

  /** Falla de una etapa del ciclo, con su destino y la causa ya diagnosticada. */
  private static final class EtapaFallida extends Exception {
    final String etapa;
    final String destino;
    final String causa;

    EtapaFallida(String etapa, String host, int puerto, Exception e) {
      super(e);
      this.etapa = etapa;
      this.destino = host + ":" + puerto;
      this.causa = Diagnostico.explicar(host, puerto, e);
    }
  }

  /** Un mensaje bajado del buzón; si superó el límite solo trae sus cabeceras. */
  private record Recibido(byte[] contenido, long bytes, boolean excedido) {}

  /** Un ciclo por etapas: BD → POP3 → atender (SMTP) → recordatorios. Devuelve el resumen. */
  private String ciclo() throws EtapaFallida {
    long t0 = System.nanoTime();
    try {
      db.verificarModelo(); // No retirar mensajes si la BD o el modelo no están disponibles.
    } catch (Exception e) {
      throw new EtapaFallida("BD", cfg.dbHost(), cfg.dbPuerto(), e);
    }
    long tBd = ms(t0);
    t0 = System.nanoTime();
    List<Recibido> mensajes = new ArrayList<>();
    int enBuzon;
    try (Pop3Client pop =
        new Pop3Client(cfg.pop3Host(), cfg.pop3Puerto(), cfg.pop3Usuario(), cfg.pop3Password())) {
      enBuzon = pop.stat();
      int n = Math.min(enBuzon, MAX_POR_CICLO);
      for (int i = 1; i <= n; i++) {
        long bytes = pop.tamano(i);
        boolean excedido = bytes > MAX_BYTES;
        mensajes.add(new Recibido(excedido ? pop.top(i, 0) : pop.retr(i), bytes, excedido));
        pop.dele(i);
      }
      pop.quit(); // confirma los DELE: recién aquí los mensajes salen del buzón
    } catch (Exception e) {
      throw new EtapaFallida("POP3", cfg.pop3Host(), cfg.pop3Puerto(), e);
    }
    String resumen = "BD " + tBd + " ms · POP3 " + mensajes.size() + " msg " + ms(t0) + " ms";
    if (enBuzon > mensajes.size())
      resumen += " (quedan " + (enBuzon - mensajes.size()) + " en el buzón para el próximo ciclo)";
    for (Recibido m : mensajes) atender(m);
    try {
      int avisos = avisarRecordatorios();
      if (avisos > 0) resumen += " · " + avisos + " recordatorio(s) avisado(s)";
    } catch (Exception e) {
      throw new EtapaFallida("Recordatorios (BD)", cfg.dbHost(), cfg.dbPuerto(), e);
    }
    if (ciclo % CICLOS_ENTRE_REBOTES == 1) revisarRebotes();
    return resumen;
  }

  /**
   * Respuestas que Gmail aceptó pero el servidor del destinatario rechazó después. Si Gmail no
   * responde se avisa una vez y el ciclo sigue: los rebotes quedan sin leer para la próxima.
   */
  private void revisarRebotes() {
    try {
      for (Rebotes.Rebote r : rebotes.nuevos()) {
        LOG.warning("REBOTE respuesta a " + r.destinatario() + " no entregada → " + r.motivo());
        procesador.bitacora(
            r.destinatario(), null, "(rebote)", "Error", "La respuesta no se entregó: " + r.motivo());
      }
      if (fallaRebotes != null) log("Rebotes: lectura IMAP recuperada");
      fallaRebotes = null;
    } catch (Exception e) {
      String causa = Diagnostico.explicar(rebotes.host(), 993, e);
      if (!causa.equals(fallaRebotes))
        LOG.warning("Rebotes: no se pudo leer IMAP " + rebotes.host() + ":993 → " + causa);
      fallaRebotes = causa;
    }
  }

  /**
   * Atiende un correo. Nada de lo que traiga detiene el servicio: todo termina en respuesta,
   * bitácora o ambos.
   */
  private void atender(Recibido m) {
    Correo c = Correo.leer(m.contenido());
    try {
      if (c.remitente().isEmpty()) {
        procesador.bitacora(
            null,
            null,
            c.asunto(),
            "Error",
            "Correo ilegible: sin una dirección de remitente válida");
        log("Ilegible: sin remitente · " + c.asunto());
        return;
      }
      if (c.automatico() || c.remitente().equalsIgnoreCase(smtp.getRemitente())) {
        procesador.bitacora(
            c.remitente(),
            null,
            c.asunto(),
            "Ignorado",
            "Respuesta automática o correo del propio sistema");
        log("Ignorado (automático o propio): " + c.remitente() + " · " + c.asunto());
        return;
      }
      Respuesta r;
      if (m.excedido()) {
        r =
            fmt.error(
                "Correo",
                "El correo pesa "
                    + (m.bytes() / 1024)
                    + " KB y el límite es "
                    + (MAX_BYTES / 1024)
                    + " KB. Vuelva a enviar el comando con un adjunto más liviano.");
        procesador.bitacora(c.remitente(), null, c.asunto(), "Error", r.mensaje());
      } else {
        r =
            procesador.procesarEmail(
                c.remitente(), c.asunto(), c.adjuntoCsv(), c.adjunto(), c.errorAdjunto());
        if (r == null) {
          log(
              "Ignorado (remitente desconocido, asunto sin comando): "
                  + c.remitente()
                  + " · "
                  + c.asunto());
          return;
        }
      }
      enviar(c.remitente(), r, c.asunto());
      log(r.resultado() + " · " + c.remitente() + " · " + c.asunto());
      for (Aviso a : r.avisos()) enviar(a.para(), a.contenido(), null);
    } catch (Exception e) {
      procesador.bitacora(
          c.remitente(),
          null,
          c.asunto(),
          "Error",
          "Fallo al atender el correo: " + e.getMessage());
      LOG.severe(
          "No se pudo atender un correo de "
              + c.remitente()
              + ": "
              + Diagnostico.explicar(cfg.dbHost(), cfg.dbPuerto(), e));
    }
  }

  /**
   * Un envío fallido (destinatario inexistente, Gmail caído) se registra y no corta los siguientes.
   */
  private void enviar(String para, Respuesta r, String asuntoRecibido) {
    try {
      smtp.enviar(
          para,
          r.asunto(),
          fmt.html(r, asuntoRecibido),
          fmt.texto(r, asuntoRecibido),
          r.archivos());
    } catch (Exception e) {
      procesador.bitacora(
          para,
          null,
          asuntoRecibido == null ? r.asunto() : asuntoRecibido,
          "Error",
          "No se pudo enviar el correo de respuesta: " + e.getMessage());
      LOG.severe(
          "SMTP "
              + cfg.smtpHost()
              + ":"
              + cfg.smtpPuerto()
              + " → "
              + Diagnostico.explicar(cfg.smtpHost(), cfg.smtpPuerto(), e)
              + " (respuesta a "
              + para
              + " no enviada)");
    }
  }

  /** RF4.3: cuando vence un recordatorio, su dueño recibe un correo. Devuelve cuántos se avisaron. */
  private int avisarRecordatorios() throws Exception {
    int enviados = 0;
    for (Recordatorio r : seguimiento.vencidos()) {
      try {
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
                    + r.tarjetaId()
                    + "). Al terminar envíe COMPLETARRECORDATORIO[\""
                    + r.id()
                    + "\"].");
        smtp.enviar(
            r.correoUsuario(), aviso.asunto(), fmt.html(aviso, null), fmt.texto(aviso, null));
        seguimiento.marcarNotificado(r.id());
        enviados++;
        log("Recordatorio " + r.id() + " enviado a " + r.correoUsuario());
      } catch (Exception e) {
        LOG.warning(
            "Recordatorio "
                + r.id()
                + " no enviado (se reintenta en el próximo ciclo): "
                + Diagnostico.explicar(cfg.smtpHost(), cfg.smtpPuerto(), e));
      }
    }
    return enviados;
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
