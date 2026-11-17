package bo.uagrm.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Cada falla que el docente puede provocar se traduce a su causa (sin red ni BD). */
class DiagnosticoTest {

  private static String bd(Throwable e) {
    return Diagnostico.explicar("db.tecnoweb.org.bo", 5432, e);
  }

  @Test
  void redYDns() {
    // El driver de PostgreSQL envuelve la causa de red: debe salir la causa, no "connection failed".
    assertTrue(
        bd(new SQLException("The connection attempt failed.", "08001", new UnknownHostException("x")))
            .startsWith("DNS: no se resuelve db.tecnoweb.org.bo"));
    assertTrue(bd(new ConnectException("Connection refused")).contains("puerto cerrado"));
    assertTrue(bd(new SocketTimeoutException("connect timed out")).contains("IP bloqueada"));
    assertTrue(bd(new java.net.SocketException("Connection reset")).contains("cortó la conexión"));
  }

  @Test
  void postgres() {
    assertEquals(
        "PostgreSQL: la IP 181.115.1.2 no está autorizada en pg_hba.conf",
        bd(
            new SQLException(
                "FATAL: no pg_hba.conf entry for host \"181.115.1.2\", user \"grupo05sa\","
                    + " database \"db_grupo05sa\", no encryption",
                "28000")));
    assertTrue(bd(new SQLException("x", "28P01")).contains("contraseña incorrectos"));
    assertTrue(bd(new SQLException("relation \"intereses\" does not exist", "42P01")).contains("PostgreSQL: relation"));
    assertTrue(bd(new SQLException("permission denied for table usuarios", "42501")).contains("REVOKE"));
  }

  @Test
  void pop3() {
    String pop = "mail.tecnoweb.org.bo";
    assertTrue(
        Diagnostico.explicar(pop, 110, new IOException("POP3 rechazó PASS: -ERR [AUTH] Authentication failed."))
            .startsWith("POP3 rechazó el login"));
    assertTrue(
        Diagnostico.explicar(pop, 110, new IOException("POP3 rechazó PASS: -ERR [IN-USE] Mailbox is locked"))
            .contains("otro lector activo"));
  }

  @Test
  void smtp() {
    MessagingException e =
        new MessagingException("envío", new AuthenticationFailedException("535-5.7.8 Username and Password not accepted"));
    assertTrue(Diagnostico.explicar("smtp.gmail.com", 587, e).contains("contraseña de aplicación"));
  }

  @Test
  void hostYPuertoDeLaUrl() {
    Config c =
        new Config("p", 110, "u", "x", "s", 587, "u", "x", "b",
            "jdbc:postgresql://db.tecnoweb.org.bo:5433/db_grupo05sa?currentSchema=public", "u", "x", 10);
    assertEquals("db.tecnoweb.org.bo", c.dbHost());
    assertEquals(5433, c.dbPuerto());
  }
}
