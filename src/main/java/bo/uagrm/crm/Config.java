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
    int intervaloSegundos) {

  public static Config cargar(Path archivo) throws IOException {
    Properties p = new Properties();
    try (Reader r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
      p.load(r);
    }
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
        Integer.parseInt(p.getProperty("intervalo.segundos", "10")));
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
        + dbUrl
        + " · cada "
        + intervaloSegundos
        + " s";
  }
}
