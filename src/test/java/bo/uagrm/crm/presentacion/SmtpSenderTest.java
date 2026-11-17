package bo.uagrm.crm.presentacion;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

class SmtpSenderTest {
  @Test
  void respuestaMimeIncluyeTextoHtmlReplyToYAntibucle() throws Exception {
    var smtp =
        new SmtpSender("127.0.0.1", 1, "sistema@example.test", "ficticia", "buzon@example.test");
    MimeMessage m =
        smtp.crearMensaje("persona@example.test", "Prueba ñ", "<p>Español</p>", "Español");
    m.saveChanges();
    assertEquals("Prueba ñ", m.getSubject());
    assertEquals("auto-replied", m.getHeader("Auto-Submitted")[0]);
    assertEquals("buzon@example.test", m.getReplyTo()[0].toString());
    assertTrue(m.isMimeType("multipart/alternative"));
    var body = (MimeMultipart) m.getContent();
    assertEquals(2, body.getCount());
    assertTrue(body.getBodyPart(0).isMimeType("text/plain"));
    assertTrue(body.getBodyPart(1).isMimeType("text/html"));
    assertEquals("Español", body.getBodyPart(0).getContent());
  }

  @Test
  void asuntoNoPermiteInyectarCabeceras() throws Exception {
    var m =
        new SmtpSender("127.0.0.1", 1, "sistema@example.test", "ficticia", "buzon@example.test")
            .crearMensaje(
                "persona@example.test", "Prueba\r\nBcc: intruso@example.test", "<p>x</p>", "x");
    m.saveChanges();
    assertNull(m.getHeader("Bcc"));
    assertFalse(m.getSubject().contains("\n"));
  }

  @Test
  void comprobanteDevueltoConservaBytesYNoReemplazaElCuerpo() throws Exception {
    byte[] bytes =
        java.util.Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a01cAAAAASUVORK5CYII=");
    var a = new Adjunto("../../recibo.png", "image/png", bytes).comoComprobante();
    var sender =
        new SmtpSender("127.0.0.1", 1, "sistema@example.test", "ficticia", "buzon@example.test");
    var m =
        sender.crearMensaje(
            "persona@example.test",
            "Comprobante",
            "<p>Revision</p>",
            "Revision",
            java.util.List.of(a));
    m.saveChanges();
    assertTrue(m.isMimeType("multipart/mixed"));
    var mixed = (MimeMultipart) m.getContent();
    assertEquals(2, mixed.getCount());
    assertTrue(mixed.getBodyPart(0).isMimeType("multipart/alternative"));
    assertEquals("recibo.png", mixed.getBodyPart(1).getFileName());
    assertArrayEquals(bytes, mixed.getBodyPart(1).getInputStream().readAllBytes());
    var output = new java.io.ByteArrayOutputStream();
    m.writeTo(output);
    var parsed = Correo.leer(output.toByteArray());
    assertTrue(parsed.automatico());
    assertArrayEquals(bytes, parsed.adjunto().contenido());
  }
}
