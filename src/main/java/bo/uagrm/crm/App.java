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
import java.util.LinkedHashSet;
import java.util.Set;
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
 * <p>Log: consola, logs/access.log (cada ciclo y cada correo atendido) y logs/error.log (fallas con
 * la etapa, el destino y la causa probable, ver {@link Diagnostico}).
 */
public final class App {

  private static final int MAX_POR_CICLO = 20;
  private static final long MAX_BYTES =
      8L * 1024 * 1024; // correos más grandes se rechazan sin descargarlos
  private static final Logger LOG = Logger.getLogger("crm");

  private final Config cfg;
  private final CommandProcessor procesador;
  private final SeguimientoService seguimiento;
  private final SmtpSender smtp;
  private final ResponseFormatter fmt;
  private final DatabaseService db;

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
      System.exit(Diagnostico.revisar(app.cfg, app.smtp, app.db) ? 0 : 1);
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
    Diagnostico.revisar(cfg, app.smtp, app.db); // informa; el servicio arranca igual y reintenta
    app.ejecutar();
  }

  /**
   * Consola y, en modo servicio, dos archivos UTF-8 (enunciado §5): logs/access.log con cada correo
   * procesado y cada ciclo (INFO) y logs/error.log con fallas y excepciones (WARNING o más).
   */
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
      FileHandler acceso = archivoLog("logs/access.log");
      acceso.setFilter(r -> r.getLevel().intValue() < Level.WARNING.intValue());
      LOG.addHandler(acceso);
      FileHandler errores = archivoLog("logs/error.log");
      // Fallas y excepciones; también el RECUPERADO que cierra cada incidente abierto aquí.
      errores.setFilter(
          r -> r.getLevel().intValue() >= Level.WARNING.intValue()
              || (r.getMessage() != null && r.getMessage().startsWith("RECUPERADO")));
      LOG.addHandler(errores);
    }
  }

  private static FileHandler archivoLog(String ruta) throws IOException {
    FileHandler f = new FileHandler(ruta, true); // se agrega al final, no se pisa entre ejecuciones
    f.setFormatter(new SimpleFormatter());
    f.setEncoding("UTF-8");
    return f;
  }

  private void ejecutar() throws InterruptedException {
    java.security.Security.setProperty("networkaddress.cache.ttl", "10"); // ver cambios de DNS pronto
    while (true) {
      ciclo++;
      Instant inicio = Instant.now();
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
        registrarFalla(e.etapa + " " + e.destino + " → " + e.causa, inicio);
      } catch (RuntimeException e) {
        registrarFalla("inesperada → " + Diagnostico.unaLinea(String.valueOf(e)), inicio);
      }
      Thread.sleep(cfg.intervaloSegundos() * 1000L); // la espera se cuenta desde el fin del ciclo
    }
  }

  /**
   * La primera vez se informa completa; mientras siga, una línea corta por ciclo. El incidente
   * (inicio y ciclos) dura hasta el RECUPERADO aunque cambie la causa.
   */
  private void registrarFalla(String detalle, Instant inicioCiclo) {
    if (falla == null) {
      fallaDesde = inicioCiclo;
      fallaCiclos = 0;
    }
    fallaCiclos++;
    if (!detalle.equals(falla)) {
      falla = detalle;
      LOG.severe("ciclo " + ciclo + " FALLA " + detalle);
    } else {
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

  /**
   * Huellas (SHA-256) de los últimos correos ya atendidos. Si el QUIT falla y el servidor no aplicó
   * los DELE, esos correos vuelven en el siguiente ciclo: se borran sin ejecutarlos otra vez.
   */
  // ponytail: en memoria (se pierde al reiniciar el lector); persistirla si hace falta entre reinicios.
  private final Set<String> atendidos = new LinkedHashSet<>();

  private static final int MAX_HUELLAS = 1000;

  /** Falla de envío SMTP en curso; mientras siga, no se retiran más correos del buzón. */
  private Exception smtpCaido;

  /** Respuesta que no salió por una falla del SMTP (no del destinatario): se reenvía antes de leer más correo. */
  private record EnEspera(String para, Respuesta r, String asuntoRecibido) {}

  // ponytail: en memoria; si el lector se reinicia con respuestas en espera, se pierden (quedan en la bitácora).
  private final java.util.ArrayDeque<EnEspera> enEspera = new java.util.ArrayDeque<>();

  /**
   * Un ciclo por etapas: BD → (SMTP, si estaba caído) → POP3: cada correo se atiende y se responde
   * antes de su DELE → QUIT → recordatorios. Devuelve el resumen para access.log.
   */
  private String ciclo() throws EtapaFallida {
    long inicio = System.nanoTime();
    try {
      db.verificarModelo(); // No retirar mensajes si la BD o el modelo no están disponibles.
    } catch (Exception e) {
      throw new EtapaFallida("BD", cfg.dbHost(), cfg.dbPuerto(), e);
    }
    long tBd = ms(inicio);
    if (smtpCaido != null) { // sin poder responder no se toma ningún correo: esperan en el buzón
      try {
        // Con respuestas en espera, la prueba es reenviarlas de verdad (un login correcto no basta:
        // el servidor puede fallar recién en DATA). Sin ellas, alcanza con el login.
        if (enEspera.isEmpty()) smtp.verificar();
        while (!enEspera.isEmpty()) {
          EnEspera p = enEspera.peek();
          try {
            smtp.enviar(p.para(), p.r().asunto(), fmt.html(p.r(), p.asuntoRecibido()),
                fmt.texto(p.r(), p.asuntoRecibido()), p.r().archivos());
            log("Respuesta en espera enviada a " + p.para() + " · " + p.asuntoRecibido());
          } catch (Exception e) {
            if (!rechazoDestinatario(e)) throw e; // el SMTP sigue fallando: queda en espera
            LOG.warning("SMTP: " + p.para() + " rechazado por el servidor de destino → "
                + Diagnostico.explicar(cfg.smtpHost(), cfg.smtpPuerto(), e));
          }
          enEspera.poll();
        }
        smtpCaido = null;
      } catch (Exception e) {
        smtpCaido = e;
        throw new EtapaFallida("SMTP", cfg.smtpHost(), cfg.smtpPuerto(), e);
      }
    }
    long t0 = System.nanoTime();
    int enBuzon, leidos = 0, repetidos = 0;
    try (Pop3Client pop =
        new Pop3Client(cfg.pop3Host(), cfg.pop3Puerto(), cfg.pop3Usuario(), cfg.pop3Password())) {
      enBuzon = pop.stat();
      int n = Math.min(enBuzon, MAX_POR_CICLO);
      for (int i = 1; i <= n && smtpCaido == null; i++) {
        long bytes = pop.tamano(i);
        boolean excedido = bytes > MAX_BYTES;
        byte[] contenido = excedido ? pop.top(i, 0) : pop.retr(i);
        String huella = huella(contenido);
        if (atendidos.contains(huella)) {
          repetidos++;
          Correo ya = Correo.leer(contenido);
          log("Ya atendido en un ciclo anterior (el servidor lo devolvió): se borra sin repetirlo · "
              + ya.remitente() + " · " + ya.asunto());
        } else {
          atender(new Recibido(contenido, bytes, excedido));
          recordar(huella);
          leidos++;
        }
        pop.dele(i);
      }
      pop.quit(); // confirma los DELE: recién aquí los mensajes salen del buzón
    } catch (Exception e) {
      throw new EtapaFallida("POP3", cfg.pop3Host(), cfg.pop3Puerto(), e);
    }
    String resumen = "BD " + tBd + " ms · POP3 " + leidos + " msg " + ms(t0) + " ms";
    if (repetidos > 0) resumen += " · " + repetidos + " ya atendido(s) borrado(s)";
    if (enBuzon > leidos + repetidos)
      resumen += " (quedan " + (enBuzon - leidos - repetidos) + " en el buzón para el próximo ciclo)";
    if (smtpCaido != null)
      throw new EtapaFallida("SMTP", cfg.smtpHost(), cfg.smtpPuerto(), smtpCaido);
    try {
      int avisos = avisarRecordatorios();
      if (avisos > 0) resumen += " · " + avisos + " recordatorio(s) avisado(s)";
    } catch (Exception e) {
      throw new EtapaFallida("Recordatorios (BD)", cfg.dbHost(), cfg.dbPuerto(), e);
    }
    if (smtpCaido != null)
      throw new EtapaFallida("SMTP", cfg.smtpHost(), cfg.smtpPuerto(), smtpCaido);
    return resumen + " · total " + ms(inicio) + " ms";
  }

  private static String huella(byte[] contenido) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(contenido));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void recordar(String huella) {
    atendidos.add(huella);
    if (atendidos.size() > MAX_HUELLAS) atendidos.remove(atendidos.iterator().next());
  }

  /**
   * Atiende un correo. Nada de lo que traiga detiene el servicio: todo termina en respuesta,
   * bitácora o ambos.
   */
  private void atender(Recibido m) {
    Correo c = null;
    try {
      c = Correo.leer(m.contenido());
      String asunto = c.asunto() == null ? "(sin asunto)" : c.asunto();
      if (c.remitente().isEmpty()) {
        procesador.bitacora(
            null, null, c.asunto(), "Error", "Correo ilegible: sin una dirección de remitente válida");
        log("Ilegible: sin remitente · " + asunto);
        return;
      }
      if (c.automatico()
          || c.remitente().equalsIgnoreCase(smtp.getRemitente())
          || c.remitente().equalsIgnoreCase(cfg.buzonSistema())) {
        procesador.bitacora(
            c.remitente(), null, c.asunto(), "Ignorado", "Respuesta automática o correo del propio sistema");
        log("Ignorado (automático o propio): " + c.remitente() + " · " + asunto);
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
          log("Ignorado (remitente desconocido, asunto sin comando): " + c.remitente() + " · " + asunto);
          return;
        }
      }
      boolean enviado = enviar(c.remitente(), r, c.asunto());
      log(r.resultado()
          + (enviado ? "" : smtpCaido != null ? " (respuesta en espera: SMTP caído)" : " (respuesta NO entregada: destinatario rechazado)")
          + " · " + c.remitente() + " · " + asunto);
      for (Aviso a : r.avisos()) enviar(a.para(), a.contenido(), null);
    } catch (Exception e) {
      String remitente = c == null ? null : c.remitente();
      procesador.bitacora(
          remitente, null, c == null ? null : c.asunto(), "Error",
          "Fallo al atender el correo: " + Diagnostico.unaLinea(String.valueOf(e.getMessage())));
      LOG.log(Level.SEVERE, "No se pudo atender un correo de " + remitente + ": "
          + Diagnostico.explicar(cfg.dbHost(), cfg.dbPuerto(), e), e);
    }
  }

  /** ¿El servidor rechazó la dirección del destinatario (no el SMTP entero, ni la cuota del remitente)? */
  private static boolean rechazoDestinatario(Exception e) {
    return e instanceof jakarta.mail.SendFailedException s
        && s.getInvalidAddresses() != null
        && s.getInvalidAddresses().length > 0;
  }

  /**
   * Envía una respuesta; devuelve false si no salió. Un rechazo del destinatario (dirección
   * inexistente) solo afecta a ese correo. Cualquier otra falla (SMTP caído, login, cuota de Gmail,
   * corte en DATA) deja la respuesta en espera, marca el SMTP como caído y el ciclo deja de retirar
   * correos hasta reenviarla.
   */
  private boolean enviar(String para, Respuesta r, String asuntoRecibido) {
    if (smtpCaido != null) {
      enEspera.add(new EnEspera(para, r, asuntoRecibido));
      return false;
    }
    try {
      smtp.enviar(
          para,
          r.asunto(),
          fmt.html(r, asuntoRecibido),
          fmt.texto(r, asuntoRecibido),
          r.archivos());
      return true;
    } catch (Exception e) {
      String causa = Diagnostico.explicar(cfg.smtpHost(), cfg.smtpPuerto(), e);
      if (rechazoDestinatario(e)) {
        procesador.bitacora(
            para, null, asuntoRecibido == null ? r.asunto() : asuntoRecibido, "Error",
            "No se pudo entregar la respuesta: " + causa);
        LOG.warning("SMTP: " + para + " rechazado por el servidor de destino → " + causa);
      } else {
        smtpCaido = e; // el ciclo lo informa una vez como FALLA SMTP (y luego RECUPERADO)
        enEspera.add(new EnEspera(para, r, asuntoRecibido));
      }
      return false;
    }
  }

  /** RF4.3: cuando vence un recordatorio, su dueño recibe un correo. Devuelve cuántos se avisaron. */
  private int avisarRecordatorios() throws Exception {
    int enviados = 0;
    for (Recordatorio r : seguimiento.vencidos()) {
      if (smtpCaido != null) break; // se reintenta en el próximo ciclo
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
                  + "\",\"resultado\"].");
      try {
        smtp.enviar(r.correoUsuario(), aviso.asunto(), fmt.html(aviso, null), fmt.texto(aviso, null));
      } catch (Exception e) {
        if (!rechazoDestinatario(e)) {
          smtpCaido = e; // se reintenta cuando vuelva el SMTP
          continue;
        }
        // Dirección rechazada: reintentar no sirve. Se marca una sola vez y queda en la bitácora.
        String causa = Diagnostico.explicar(cfg.smtpHost(), cfg.smtpPuerto(), e);
        seguimiento.marcarNotificado(r.nro());
        procesador.bitacora(r.correoUsuario(), null, "Recordatorio " + r.nro(), "Error",
            "Aviso no entregado: " + causa);
        LOG.warning("Recordatorio " + r.nro() + ": " + r.correoUsuario() + " rechazado → " + causa);
        continue;
      }
      seguimiento.marcarNotificado(r.nro());
      enviados++;
      log("Recordatorio " + r.nro() + " enviado a " + r.correoUsuario());
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
