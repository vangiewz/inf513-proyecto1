package bo.uagrm.crm.presentacion;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Identidad de los correos recibidos, Message-ID fijo de las respuestas y espera entre intentos. */
class ColaCorreoTest {

  static byte[] b(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  @Test
  void idMensajeUsaLaCabeceraOElResumen() {
    assertEquals("<abc@gmail.com>", Correo.idMensaje(b("Message-ID: <abc@gmail.com>\r\nSubject: x\r\n\r\ny\r\n")));
    assertEquals("<abc@gmail.com>", Correo.idMensaje(b("Message-Id:\r\n <abc@gmail.com>\r\nSubject: x\r\n\r\ny\r\n")), "cabecera plegada");
    String sin = Correo.idMensaje(b("Subject: HELP[]\r\n\r\ntelnet\r\n"));
    assertTrue(sin.startsWith("sha256:") && sin.length() == 71);
    assertEquals(sin, Correo.idMensaje(b("Subject: HELP[]\r\n\r\ntelnet\r\n")), "estable al releer");
    assertNotEquals(sin, Correo.idMensaje(b("Subject: HELP[]\r\n\r\notro\r\n")));
    assertTrue(Correo.idMensaje(b("Message-ID: basura sin corchetes\r\n\r\n")).startsWith("sha256:"));
  }

  @Test
  void laRespuestaConservaSuMessageIdAlReenviarse() throws Exception {
    var s = new SmtpSender("smtp.example.test", 587, "sistema@example.test", "x", "buzon@example.test");
    byte[] crudo = s.preparar("ana@example.test", "Éxito: listo", "<p>ok</p>", "ok", List.of(), "<orig@gmail.com>");
    MimeMessage m = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(crudo));
    String id = m.getMessageID();
    assertTrue(id.matches("<crm\\.[0-9a-f-]{36}@grupo05sa\\.tecnoweb\\.org\\.bo>"), id);
    assertEquals("<orig@gmail.com>", m.getHeader("In-Reply-To", null));
    MimeMessage copia = SmtpSender.fijo(m);
    copia.saveChanges(); // Transport.send() lo llama antes de enviar
    assertEquals(id, copia.getMessageID(), "un reintento entrega el mismo mensaje");
    byte[] otra = s.preparar("ana@example.test", "Éxito: listo", "<p>ok</p>", "ok", List.of(), null);
    MimeMessage m2 = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(otra));
    assertNotEquals(id, m2.getMessageID(), "cada respuesta nueva tiene su propio Message-ID");
    assertNull(m2.getHeader("In-Reply-To"));
  }

  @Test
  void esperaProgresivaHastaUnaHora() {
    assertEquals(List.of(1, 2, 4, 8, 16, 32, 60, 60, 60),
        java.util.stream.IntStream.rangeClosed(1, 9).map(Bandeja::espera).boxed().toList());
  }
}
