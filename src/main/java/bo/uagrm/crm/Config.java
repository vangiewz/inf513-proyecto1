package bo.uagrm.crm;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Configuración leída de crm-mail.properties (fuera del repositorio: lleva contraseñas). */
public record Config(
    String pop3Host,
    int pop3Puerto,
    String pop3Usuario,
    String pop3Password,
    String smtpHost,
    int smtpPuerto,
    String smtpUsuario,
    String smtpPassword,
    String buzonSistema,
    String dbUrl,
    String dbUsuario,
    String dbPassword,
    int intervaloSegundos,
    long pollIntervalMs,
    long backoffMaxMs,
    long permanentRetryMs,
    String logDirectory,
    String lockDirectory,
    String authKeysFile,
    String pop3Seguridad) {

  public static final long MIN_INSTITUCIONAL_MS = 3000;

  /** Conserva el constructor usado por integraciones locales anteriores. */
  public Config(String pop3Host, int pop3Puerto, String pop3Usuario, String pop3Password,
      String smtpHost, int smtpPuerto, String smtpUsuario, String smtpPassword,
      String buzonSistema, String dbUrl, String dbUsuario, String dbPassword, int intervaloSegundos) {
    this(pop3Host, pop3Puerto, pop3Usuario, pop3Password, smtpHost, smtpPuerto, smtpUsuario,
        smtpPassword, buzonSistema, dbUrl, dbUsuario, dbPassword, intervaloSegundos,
        Math.multiplyExact((long) intervaloSegundos, 1000), 30_000, 60_000,
        System.getProperty("crm.log.dir", "logs"), directorioBloqueo(), null, "plain");
  }

  public Config {
    boolean institucional = institucional(pop3Host) || institucional(dbUrl);
    if (pollIntervalMs <= 0 || (institucional && pollIntervalMs < MIN_INSTITUCIONAL_MS))
      throw new IllegalArgumentException("mail.poll.interval.ms debe ser al menos 3000 en Tecnoweb");
    if (backoffMaxMs < Math.max(pollIntervalMs, MIN_INSTITUCIONAL_MS))
      throw new IllegalArgumentException("mail.poll.backoff.max.ms no puede ser menor al intervalo");
    if (permanentRetryMs < backoffMaxMs)
      throw new IllegalArgumentException("mail.poll.permanent.retry.ms no puede ser menor al backoff máximo");
    if (logDirectory == null || logDirectory.isBlank() || lockDirectory == null || lockDirectory.isBlank())
      throw new IllegalArgumentException("Los directorios de logs y bloqueo son obligatorios");
    if (!java.util.Set.of("plain", "starttls", "tls").contains(pop3Seguridad))
      throw new IllegalArgumentException("pop3.seguridad debe ser plain, starttls o tls");
  }

  public static boolean institucional(String destino) {
    return destino != null && destino.toLowerCase(java.util.Locale.ROOT).contains("tecnoweb.org.bo");
  }

  private static String directorioBloqueo() {
    return System.getProperty("crm.lock.dir", Path.of(System.getProperty("user.home"), ".crm-mail", "run").toString());
  }

  public static Config cargar(Path archivo) throws IOException {
    Properties p = new Properties();
    try (Reader r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
      p.load(r);
    }
    long intervalo = p.containsKey("mail.poll.interval.ms")
        ? Long.parseLong(req(p, "mail.poll.interval.ms"))
        : Math.multiplyExact(Long.parseLong(p.getProperty("intervalo.segundos", "3")), 1000);
    return new Config(
        req(p, "pop3.host"),
        Integer.parseInt(p.getProperty("pop3.puerto", "110")),
        req(p, "pop3.usuario"),
        req(p, "pop3.password"),
        req(p, "smtp.host"),
        Integer.parseInt(p.getProperty("smtp.puerto", "587")),
        req(p, "smtp.usuario"),
        req(p, "smtp.password"),
        req(p, "buzon.sistema"),
        req(p, "db.url"),
        req(p, "db.usuario"),
        req(p, "db.password"),
        Math.toIntExact((intervalo + 999) / 1000), intervalo,
        Long.parseLong(p.getProperty("mail.poll.backoff.max.ms", Long.toString(Math.max(intervalo, 30_000)))),
        Long.parseLong(p.getProperty("mail.poll.permanent.retry.ms", Long.toString(Math.max(intervalo, 60_000)))),
        p.getProperty("log.directory", System.getProperty("crm.log.dir", "logs")),
        p.getProperty("lock.directory", directorioBloqueo()),
        p.getProperty("auth.keys.file"),
        p.getProperty("pop3.seguridad", "plain").trim().toLowerCase(java.util.Locale.ROOT));
  }

  /** Host de la BD tomado de db.url (jdbc:postgresql://host:puerto/base?...), para el diagnóstico. */
  public String dbHost() {
    return dbUrl.replaceFirst("^jdbc:postgresql://([^:/?]+).*$", "$1");
  }

  public int dbPuerto() {
    String p = dbUrl.replaceFirst("^jdbc:postgresql://[^:/?]+:(\\d+).*$", "$1");
    return p.equals(dbUrl) ? 5432 : Integer.parseInt(p);
  }

  private static String req(Properties p, String clave) {
    String v = p.getProperty(clave);
    if (v == null || v.isBlank())
      throw new IllegalArgumentException("Falta " + clave + " en la configuración");
    return v.trim();
  }

  /** Sin contraseñas: el record se puede imprimir en el log sin filtrarlas. */
  @Override
  public String toString() {
    return "POP3 "
        + pop3Usuario
        + "@"
        + pop3Host
        + ":"
        + pop3Puerto
        + " · SMTP "
        + smtpUsuario
        + "@"
        + smtpHost
        + ":"
        + smtpPuerto
        + " · BD "
        + dbUsuario
        + " "
        + dbHost() + ":" + dbPuerto()
        + " · cada "
        + pollIntervalMs
        + " ms";
  }
}
