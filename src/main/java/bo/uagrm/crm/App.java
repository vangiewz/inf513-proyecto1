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
import bo.uagrm.crm.presentacion.AutenticidadCorreo;
import bo.uagrm.crm.presentacion.Correo;
import bo.uagrm.crm.presentacion.Pop3Client;
import bo.uagrm.crm.presentacion.Rebotes;
import bo.uagrm.crm.presentacion.SmtpSender;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
  private static final long MAX_BYTES =
      8L * 1024 * 1024; // correos más grandes se rechazan sin descargarlos
  private static final Logger LOG = Logger.getLogger("crm");

  private final Config cfg;
  private final CommandProcessor procesador;
  private final SeguimientoService seguimiento;
  private final SmtpSender smtp;
  private final ResponseFormatter fmt;
  private final DatabaseService db;
  private final Bandeja bandeja;
  private final Rebotes rebotes;
  private final AutenticidadCorreo autenticidad;
  private final java.util.function.LongSupplier relojMonotono;
  private boolean falloBdPermanente;
  private long proximaRevisionRebotes;

  private long ciclo;
  /** Fallas en curso por servidor (BD, POP3, SMTP, IMAP), para informar cambios y no repetir. */
  private final Map<String, Falla> fallas = new LinkedHashMap<>();

  private record Falla(String detalle, Instant desde, int ciclos) {}

  App(Config cfg) {
    this(cfg, System::nanoTime);
  }

  App(Config cfg, java.util.function.LongSupplier relojMonotono) {
    this.cfg = cfg;
    this.relojMonotono = relojMonotono;
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
    try {
      this.autenticidad = AutenticidadCorreo.cargar(cfg.authKeysFile() == null ? null : Path.of(cfg.authKeysFile()));
    } catch (IOException e) {
      throw new IllegalArgumentException("No se pudo cargar el archivo privado de autenticidad", e);
    }
  }

  public static void main(String[] args) throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone("America/La_Paz"));
    if (args.length == 1 && args[0].equals("--anexo")) {
      anexo();
      return;
    }
    if (args.length == 2 && args[0].equals("--diagnostico")) {
      configurarLog(false, "logs");
      App app = new App(Config.cargar(Path.of(args[1])));
      try (InstanciaCorreo instancia = InstanciaCorreo.adquirir(Path.of(app.cfg.lockDirectory()), app.cfg.pop3Host(), app.cfg.pop3Usuario())) {
        boolean ok = Diagnostico.revisar(app.cfg, app.smtp, app.rebotes, app.db);
        if (!ok) throw new IOException("Diagnóstico no satisfactorio; revise los registros");
      }
      return;
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
    Config cfg = Config.cargar(Path.of(args.length > 0 ? args[0] : "crm-mail.properties"));
    try (InstanciaCorreo instancia = InstanciaCorreo.adquirir(Path.of(cfg.lockDirectory()), cfg.pop3Host(), cfg.pop3Usuario())) {
      configurarLog(true, cfg.logDirectory());
      log("CRM vía e-mail iniciado. " + cfg);
      // El primer ciclo comprueba el modelo; no se duplican conexiones con diagnósticos de arranque.
      new App(cfg).ejecutar();
    } catch (InterruptedException cierre) {
      Thread.currentThread().interrupt();
      log("Servicio detenido; solicitudes y respuestas pendientes permanecen recuperables");
    }
  }

  /** Consola y, en modo servicio, archivo rotativo logs/crm-mail.N.log, ambos en UTF-8. */
  private static void configurarLog(boolean archivo, String directorio) throws IOException {
    System.setProperty(
        "java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s %5$s%6$s%n");
    Logger raiz = Logger.getLogger("");
    raiz.setLevel(Level.WARNING); // librerías: solo advertencias
    for (Handler h : raiz.getHandlers()) raiz.removeHandler(h);
    LOG.setLevel(Level.INFO);
    LOG.setUseParentHandlers(false);
    ConsoleHandler consola = new ConsoleHandler();
    consola.setFormatter(formatoLogSeguro());
    consola.setEncoding("UTF-8");
    LOG.addHandler(consola);
    if (archivo) {
      Files.createDirectories(Path.of(directorio));
      FileHandler f = new FileHandler(Path.of(directorio, "crm-mail.%g.log").toString(), 5_000_000, 5, true);
      f.setFormatter(formatoLogSeguro());
      f.setEncoding("UTF-8");
      LOG.addHandler(f);
    }
  }

  /** Redacta también mensajes de excepciones y causas, no únicamente eventos principales. */
  static java.util.logging.Formatter formatoLogSeguro() {
    return new SimpleFormatter() {
      @Override public synchronized String format(java.util.logging.LogRecord registro) {
        return super.format(registro)
            .replaceAll("(?i)(\\b(?:https?|postgresql)://)[^\\s/@]+:[^\\s/@]+@", "$1[privado]@")
            .replaceAll("(?i)\\b(password|passwd|contraseña|clave|token|secret|authorization|X-CRM-Signature)(\\s*[:=]\\s*)(Bearer\\s+[^\\s,;]+|\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;]+)", "$1$2[privado]")
            .replaceAll("(?i)(Key\\s*\\([^)]*\\)\\s*=\\s*)\\([^)]*\\)", "$1([dato])")
            .replaceAll("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}", "[correo]");
      }
    };
  }

  private void ejecutar() throws InterruptedException {
    java.security.Security.setProperty("networkaddress.cache.ttl", "10");
    try (BucleSondeo bucle = new BucleSondeo()) {
      Thread cierre = new Thread(() -> bucle.cerrarYEsperar(75000), "crm-cierre");
      Runtime.getRuntime().addShutdownHook(cierre);
      try {
        bucle.ejecutar(() -> {
          ciclo++;
          try {
            String resumen = ciclo();
            sinFalla("INTERNO");
            if (resumen != null) log("ciclo " + ciclo
                + (fallas.isEmpty() ? " OK" : " CON FALLAS (" + String.join(", ", fallas.keySet()) + ")")
                + " · " + resumen);
          } catch (RuntimeException e) {
            falla("INTERNO", "Fallo inesperado " + e.getClass().getSimpleName());
          }
        }, this::esperaMs);
      } finally {
        try { Runtime.getRuntime().removeShutdownHook(cierre); }
        catch (IllegalStateException apagando) { /* JVM está finalizando */ }
      }
    }
  }

  /** Fixed delay desde el fin: 3, 6, 12, 24, 30 s por defecto; errores permanentes 60 s. */
  long esperaMs() {
    int peor = fallas.values().stream().mapToInt(Falla::ciclos).max().orElse(0);
    return new ControlSondeo(cfg.pollIntervalMs(), cfg.backoffMaxMs(), cfg.permanentRetryMs())
        .demora(peor, falloBdPermanente);
  }

  int espera() { return Math.toIntExact((esperaMs() + 999) / 1000); }

  /** La primera vez se informa completa; mientras siga igual, una línea corta por ciclo. */
  private void falla(String etapa, String detalle) {
    Falla f = fallas.get(etapa);
    if (f == null || !f.detalle().equals(detalle)) {
      fallas.put(etapa, new Falla(detalle, f == null ? Instant.now() : f.desde(), f == null ? 1 : f.ciclos() + 1));
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
  synchronized String ciclo() {
    long t0 = System.nanoTime();
    try {
      db.verificarModelo(); // sin BD no se retira ningún mensaje del buzón
    } catch (Exception e) {
      DatabaseService.TipoFallo tipo = DatabaseService.tipoFallo(e);
      falloBdPermanente = tipo != DatabaseService.TipoFallo.TEMPORAL;
      falla("BD", tipo + " · " + bd(e));
      return null;
    }
    falloBdPermanente = false;
    long tBd = ms(t0);
    t0 = System.nanoTime();
    String pop3 = descargar();
    if (pop3 == null) return null; // la BD falló al guardar
    if (Thread.currentThread().isInterrupted()) return "cierre solicitado; pendientes conservados";
    pop3 += " " + ms(t0) + " ms";
    int procesados;
    int avisos;
    try {
      procesados = procesarPendientes();
      if (Thread.currentThread().isInterrupted()) return "cierre solicitado; pendientes conservados";
      avisos = encolarRecordatorios();
    } catch (SQLException e) {
      falla("BD", bd(e));
      return null;
    }
    sinFalla("BD");
    int enviados = enviarCola();
    revisarRebotesSiCorresponde(this::revisarRebotes);
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

  /** Un minuto desde el fin del intento IMAP, incluso si falla, independiente del sondeo POP3. */
  synchronized boolean revisarRebotesSiCorresponde(Runnable revision) {
    if (Thread.currentThread().isInterrupted()) return false;
    long ahora = relojMonotono.getAsLong();
    if (proximaRevisionRebotes != 0 && ahora < proximaRevisionRebotes) return false;
    try {
      revision.run();
      return true;
    } finally {
      proximaRevisionRebotes = relojMonotono.getAsLong() + 60_000_000_000L;
    }
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
        new Pop3Client(cfg.pop3Host(), cfg.pop3Puerto(), cfg.pop3Usuario(), cfg.pop3Password(), cfg.pop3Seguridad())) {
      enBuzon = pop.stat();
      int n = Math.min(enBuzon, MAX_POR_CICLO);
      for (int i = 1; i <= n; i++) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Lectura detenida para cierre del servicio");
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
          log("YA GUARDADO correlación " + correlacion(id) + ": se borra del buzón sin repetirlo");
        } else {
          guardados++;
          log(
              "RECIBIDO registro #"
                  + nro
                  + " · correlación " + correlacion(id)
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
      if (Thread.currentThread().isInterrupted()) break;
      try {
        List<String> lineas = db.enTransaccion(c -> procesarUno(c, nro));
        lineas.forEach(App::log);
        if (!lineas.isEmpty()) n++;
      } catch (Exception e) {
        if (DatabaseService.esDeConexion(e))
          throw e instanceof SQLException s ? s : new SQLException("conexión perdida", "08006", e);
        LOG.severe("Fallo interno registro #" + nro + " · " + e.getClass().getSimpleName()
            + "; se prepara respuesta de contingencia tras ROLLBACK");
        try {
          db.enTransaccion(c -> {
            Bandeja.Entrante entrante = bandeja.tomar(c, nro);
            if (entrante == null) return null;
            Correo m = Correo.leer(entrante.contenido());
            Respuesta respuesta = fmt.error("Solicitud", "No se pudo completar la solicitud. No se confirmó la operación. Referencia #" + nro + ".");
            if (!m.remitente().isEmpty() && !m.automatico()
                && !m.remitente().equalsIgnoreCase(smtp.getRemitente())) {
              encolar(c, nro, m.remitente(), respuesta, null,
                  Correo.messageIdOriginal(entrante.contenido()));
            }
            bandeja.cerrar(c, nro, null, "Error", "Fallo interno " + e.getClass().getSimpleName()
                + (m.remitente().isEmpty() ? "; sin dirección válida para responder" : "; respuesta de contingencia en cola"));
            return null;
          });
        } catch (Exception grave) {
          if (DatabaseService.esDeConexion(grave))
            throw grave instanceof SQLException s ? s : new SQLException("conexión perdida", "08006", grave);
          // La transacción de contingencia se revierte: el correo sigue Recibido y recuperable.
          LOG.severe("PENDIENTE DE RECUPERACIÓN registro #" + nro + " · " + grave.getClass().getSimpleName());
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
      log.add("ILEGIBLE registro #" + nro + " sin remitente (no se responde)");
      return log;
    }
    if (m.automatico() || rem.equalsIgnoreCase(smtp.getRemitente())) {
      bandeja.cerrar(c, nro, null, "Ignorado", "Respuesta automática o correo del propio sistema");
      log.add("IGNORADO registro #" + nro + " (automático o propio)");
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
      long k = encolar(c, nro, rem, r, asunto, Correo.messageIdOriginal(e.contenido()));
      log.add("PROCESADO registro #" + nro + " Error (correo demasiado grande) → respuesta en cola (correo #" + k + ")");
      return log;
    }
    Boolean registrado = DatabaseService.uno(c, rs -> rs.getBoolean(1),
        "SELECT EXISTS(SELECT 1 FROM usuarios WHERE lower(correo)=lower(?))", rem);
    if (Boolean.TRUE.equals(registrado)) {
      AutenticidadCorreo.Resultado identidad = autenticidad.verificar(e.contenido(), Instant.now());
      if (!identidad.autentico()) {
        Respuesta denegado = fmt.denegado("Autenticidad", "La solicitud necesita una firma válida del remitente registrado. " + identidad.motivo());
        bandeja.cerrar(c, nro, null, "Denegado", "Autenticidad del remitente no acreditada");
        long k = encolar(c, nro, rem, denegado, null, Correo.messageIdOriginal(e.contenido()));
        log.add("DENEGADO registro #" + nro + " autenticidad no acreditada; respuesta #" + k);
        return log;
      }
    }
    Command cmd = procesador.interpretar(asunto, rem, null);
    String rolVigente = Boolean.TRUE.equals(registrado) ? DatabaseService.uno(c, rs -> rs.getString(1),
        "SELECT rol FROM usuarios WHERE lower(correo)=lower(?) AND estado='activo'"
            + " AND CURRENT_DATE BETWEEN fecha_inicio AND fecha_fin", rem) : null;
    boolean permisoActual = !Boolean.TRUE.equals(registrado)
        || (cmd.isValido() && rolVigente != null && procesador.procesadores().get(rolVigente) != null
            && procesador.procesadores().get(rolVigente).comandos().contains(cmd.getTipo()));
    if (cmd.isValido() && cmd.getTipo().escritura() && permisoActual) {
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
      log.add("IGNORADO registro #" + nro);
      return log;
    }
    Respuesta r = a.respuesta();
    bandeja.cerrar(c, nro, a.usuarioCorreo(), a.resultado(), a.detalle());
    long k = encolar(c, nro, rem, r, asunto, Correo.messageIdOriginal(e.contenido()));
    log.add(
        (r.resultado().equals("Éxito") ? "CONFIRMADO en BD" : "PROCESADO")
            + " registro #"
            + nro
            + " "
            + r.resultado()
            + " → respuesta en cola (correo #"
            + k
            + ")");
    for (Aviso av : r.avisos()) {
      long ka = encolar(c, nro, av.para(), av.contenido(), null, null);
      log.add("  aviso en cola (correo #" + ka + ")");
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
                            + "\",\"resultado\"].");
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
                out.add("Recordatorio " + r.nro() + " en cola (correo #" + k + ")");
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
        if (Thread.currentThread().isInterrupted()) return enviados;
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
      log("ENVIADO correo #" + k + " (" + origen + ", intento " + (s.intentos() + 1) + ")");
      return new Envio(true, false);
    } catch (MessagingException e) {
      String causa = Diagnostico.explicar(cfg.smtpHost(), cfg.smtpPuerto(), e);
      boolean definitivo = definitivo(e);
      Integer minutos = bandeja.fallo(c, k, s.intentos(), causa, definitivo);
      if (minutos == null)
        LOG.severe(
            "FALLIDO correo #" + k + " (" + origen + ") tras "
                + (s.intentos() + 1) + " intento(s) → " + causa + ". La operación ya estaba confirmada; no se repite.");
      else
        LOG.warning(
            "PENDIENTE correo #" + k + " (" + origen + "), intento "
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
      rebotes.procesar(r -> {
        int marcados = registrarRebote(r);
        LOG.warning("REBOTE registrado · respuestas correlacionadas " + marcados);
      });
      sinFalla("IMAP");
    } catch (Exception e) {
      falla("IMAP", rebotes.host() + ":993 → " + Diagnostico.explicar(rebotes.host(), 993, e) + " (lectura de rebotes)");
    }
  }

  /** Confirma también los DSN sin correlación; un fallo de BD impide adelantar SEEN en IMAP. */
  int registrarRebote(Rebotes.Rebote rebote) throws SQLException {
    return db.enTransaccion(c -> {
      int marcados = bandeja.rebote(rebote.destinatario(), rebote.motivo(), rebote.originalMessageId());
      seguimiento.registrarComando("(rebote)", null, "(rebote)", "Error",
          marcados == 0 ? "DSN recibido sin respuesta correlacionada; revisar el aviso original"
              : "DSN registrado; respuestas correlacionadas: " + marcados);
      return marcados;
    });
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

  private static String correlacion(String id) {
    return Integer.toHexString(id == null ? 0 : id.hashCode());
  }

  private static void log(String s) {
    LOG.info(s);
  }
}
