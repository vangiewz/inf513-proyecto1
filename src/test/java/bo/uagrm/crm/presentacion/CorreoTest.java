package bo.uagrm.crm.presentacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Un correo mal formado nunca lanza excepción: se lee lo que se pueda. */
class CorreoTest {

  private static Correo leer(String crudo) {
    return Correo.leer(crudo.getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  void asuntoCodificadoYAdjuntoCsv() {
    Correo c =
        leer(
            """
            From: "Ana" <Ana@Example.com>\r
            Subject: =?UTF-8?Q?REGISTRARINTERACCION["7","Nota","Se_envi=C3=B3_el_PDF"]?=\r
            MIME-Version: 1.0\r
            Content-Type: multipart/mixed; boundary="b"\r
            \r
            --b\r
            Content-Type: text/plain\r
            \r
            hola\r
            --b\r
            Content-Type: application/octet-stream; name="p.csv"\r
            Content-Disposition: attachment; filename="p.csv"\r
            \r
            ci,nombre\r
            --b--\r
            """);
    assertEquals("ana@example.com", c.remitente());
    assertEquals("REGISTRARINTERACCION[\"7\",\"Nota\",\"Se envió el PDF\"]", c.asunto());
    assertTrue(c.adjuntoCsv().startsWith("ci,nombre"));
  }

  @Test
  void cabecerasRotasNoLanzanExcepcion() {
    Correo c =
        leer(
            "From: <<<sin direccion\r\n"
                + "Subject: VERTABLERO[]\r\n"
                + "Content-Type: multipart/mixed; boundary=\"x\"\r\n\r\n"
                + "basura sin fin");
    assertEquals("", c.remitente());
    assertNull(c.adjuntoCsv());
    assertEquals("", leer("").remitente());
    assertEquals(
        "juan@example.com", leer("From: Juan juan@example.com (texto suelto\r\n\r\n").remitente());
  }

  @Test
  void rebotesSonAutomaticos() {
    assertTrue(
        leer("From: MAILER-DAEMON@mail.tecnoweb.org.bo\r\nSubject: Undelivered\r\n\r\n")
            .automatico());
    assertTrue(
        leer("From: a@example.com\r\nAuto-Submitted: auto-replied\r\nSubject: x\r\n\r\n")
            .automatico());
  }

  @Test
  void comprobanteEntranteBinarioLlegaAlDespachador() throws Exception {
    byte[] bytes =
        java.util.Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a01cAAAAASUVORK5CYII=");
    var m =
        new jakarta.mail.internet.MimeMessage(
            jakarta.mail.Session.getInstance(new java.util.Properties()));
    m.setFrom(new jakarta.mail.internet.InternetAddress("vendedor@example.test"));
    m.setSubject("REGISTRARCOMPROBANTE[\"1\"]", "UTF-8");
    var a = new jakarta.mail.internet.MimeBodyPart();
    a.setDataHandler(
        new jakarta.activation.DataHandler(
            new jakarta.mail.util.ByteArrayDataSource(bytes, "image/png")));
    a.setFileName("recibo.png");
    a.setDisposition(jakarta.mail.Part.ATTACHMENT);
    m.setContent(new jakarta.mail.internet.MimeMultipart("mixed", a));
    var out = new java.io.ByteArrayOutputStream();
    m.writeTo(out);
    Correo c = Correo.leer(out.toByteArray());
    org.junit.jupiter.api.Assertions.assertFalse(c.automatico());
    assertEquals("vendedor@example.test", c.remitente());
    org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, c.adjunto().contenido());
    assertNull(c.errorAdjunto());
  }

  @Test
  void multiplesComprobantesSeRechazanSinElegirUnoArbitrariamente() throws Exception {
    var m =
        new jakarta.mail.internet.MimeMessage(
            jakarta.mail.Session.getInstance(new java.util.Properties()));
    m.setFrom("vendedor@example.test");
    m.setSubject("REGISTRARCOMPROBANTE[\"1\"]");
    var mixed = new jakarta.mail.internet.MimeMultipart("mixed");
    for (int i = 0; i < 2; i++) {
      var a = new jakarta.mail.internet.MimeBodyPart();
      a.setText("%PDF-1.4\n%%EOF");
      a.setFileName("recibo" + i + ".pdf");
      a.setDisposition(jakarta.mail.Part.ATTACHMENT);
      mixed.addBodyPart(a);
    }
    m.setContent(mixed);
    var out = new java.io.ByteArrayOutputStream();
    m.writeTo(out);
    Correo c = Correo.leer(out.toByteArray());
    assertTrue(c.errorAdjunto().contains("un solo"));
    assertNull(c.adjunto());
  }
}
