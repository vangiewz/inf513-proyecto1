package bo.uagrm.crm;

import static bo.uagrm.crm.persistencia.DatabaseService.*;
import static org.junit.jupiter.api.Assertions.*;

import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.dominio.usuarios.UsuarioService;
import bo.uagrm.crm.persistencia.DatabaseService;
import bo.uagrm.crm.presentacion.AutenticidadCorreo;
import jakarta.mail.internet.MimeUtility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Circuito real App.ciclo -> sockets POP3/SMTP locales -> PostgreSQL, con firma de autorización. */
@EnabledIfEnvironmentVariable(named = "P1_TEST_DB_URL", matches = ".+")
@Timeout(90)
class PersonaCorreoTest {
  @TempDir Path temporal;

  @Test
  void defensaDocenteConSmtpCaidoReinicioRelecturaYErrores() throws Exception {
    String url = System.getenv("P1_TEST_DB_URL");
    assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:"));
    DatabaseService db = new DatabaseService(url, System.getenv().getOrDefault("P1_TEST_DB_USER", "crm_user"),
        System.getenv().getOrDefault("P1_TEST_DB_PASSWORD", "crm_password"));
    Usuario admin = new UsuarioService(db).insertar("Admin", "CorreoPersona", "correo.persona." + UUID.randomUUID()
        + "@example.test", "70000001", "administrador");
    byte[] clave = new byte[32];
    new java.security.SecureRandom().nextBytes(clave);
    Path claves = temporal.resolve("claves.properties");
    Files.writeString(claves, admin.correo() + "=" + Base64.getEncoder().encodeToString(clave));
    var pop = new BandejaCorreoTest.Pop3Falso();
    var smtp = new BandejaCorreoTest.SmtpFalso();
    pop.iniciar(); smtp.iniciar();
    System.setProperty("crm.smtp.starttls", "false");
    try {
      Config cfg = new Config("127.0.0.1", pop.puerto(), "qa", "ficticia", "127.0.0.1", smtp.puerto(),
          "sistema@example.test", "ficticia", "buzon@example.test", url,
          System.getenv().getOrDefault("P1_TEST_DB_USER", "crm_user"),
          System.getenv().getOrDefault("P1_TEST_DB_PASSWORD", "crm_password"), 3,
          3000, 30000, 60000, temporal.resolve("logs").toString(), temporal.resolve("run").toString(),
          claves.toString(), "plain");
      String doc = String.valueOf(10_000_000 + new java.security.SecureRandom().nextInt(80_000_000));
      String alta = "INSPER[\"" + doc + "\",\"José Carlos\",\"Pérez Seras\",\"Estudiante\",\"33554433\","
          + "\"71055123\",\"" + doc + "@example.test\"]";
      String recibido = correo(admin.correo(), alta, clave);
      pop.buzon.add(recibido);
      smtp.abierto = false;
      new App(cfg).ciclo();
      try (Connection c = db.getConnection()) {
        assertEquals(Long.valueOf(1), uno(c, r -> r.getLong(1), "SELECT count(*) FROM persona WHERE documento=?", doc));
        assertEquals(Long.valueOf(1), uno(c, r -> r.getLong(1), "SELECT count(*) FROM correos_salientes s"
            + " JOIN log_comandos l USING(nro_registro) WHERE l.remitente=? AND s.estado='Pendiente'", admin.correo()));
        actualizar(c, "UPDATE correos_salientes SET proximo_intento=now() WHERE destinatario=?", admin.correo());
      }
      assertTrue(pop.buzon.isEmpty());
      smtp.abierto = true;
      new App(cfg).ciclo(); // Reconstrucción real de App: estado pendiente proviene de PostgreSQL.
      assertFalse(smtp.recibidos.isEmpty());
      int respuestas = smtp.recibidos.size();
      pop.buzon.add(recibido); // mismo correo y firma: no repite SQL ni respuesta.
      new App(cfg).ciclo();
      assertEquals(respuestas, smtp.recibidos.size());
      pop.buzon.add(correo(admin.correo(), "LISPER[\"*\"]", clave));
      pop.buzon.add(correo(admin.correo(), "INSPER[\"incompleto\"]", clave));
      new App(cfg).ciclo();
      try (Connection c = db.getConnection()) {
        assertEquals(Long.valueOf(1), uno(c, r -> r.getLong(1), "SELECT count(*) FROM persona WHERE documento=?", doc));
        assertEquals(Long.valueOf(2), uno(c, r -> r.getLong(1), "SELECT count(*) FROM log_comandos"
            + " WHERE remitente=? AND resultado='Éxito'", admin.correo()));
        assertEquals(Long.valueOf(1), uno(c, r -> r.getLong(1), "SELECT count(*) FROM log_comandos"
            + " WHERE remitente=? AND resultado='Error'", admin.correo()));
      }
      assertEquals(respuestas + 2, smtp.recibidos.size());
    } finally {
      pop.detener(); smtp.detener();
    }
  }

  private static String correo(String remitente, String asunto, byte[] clave) throws Exception {
    String raw = "From: " + remitente + "\r\nTo: buzon@example.test\r\nSubject: "
        + MimeUtility.encodeText(asunto, "UTF-8", "B") + "\r\nMessage-ID: <" + UUID.randomUUID()
        + "@persona.qa>\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nQA\r\n";
    return new String(AutenticidadCorreo.firmar(raw.getBytes(StandardCharsets.ISO_8859_1), clave, Instant.now()),
        StandardCharsets.ISO_8859_1);
  }
}
