package bo.uagrm.crm.presentacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RebotesTest {

  private static Rebotes.Rebote leer(String s) {
    return Rebotes.leer(s.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void avisoDeGmailConDestinatarioYMotivo() {
    var r =
        leer(
            """
            From: Mail Delivery Subsystem <mailer-daemon@googlemail.com>
            X-Failed-Recipients: Lobo.Carlos@ficct.uagrm.edu.bo
            Subject: Delivery Status Notification (Failure)
            Content-Type: multipart/report; report-type=delivery-status; boundary="b"

            --b
            Content-Type: message/delivery-status

            Reporting-MTA: dns; googlemail.com

            Final-Recipient: rfc822; lobo.carlos@ficct.uagrm.edu.bo
            Action: failed
            Status: 5.7.1
            Diagnostic-Code: smtp; 550 5.7.1 Service unavailable, Client host
             blocked using Spamhaus
            --b--
            """);
    assertEquals("lobo.carlos@ficct.uagrm.edu.bo", r.destinatario());
    assertEquals("550 5.7.1 Service unavailable, Client host blocked using Spamhaus", r.motivo());
  }

  @Test
  void sinCabeceraXFailedNiDiagnosticoUsaFinalRecipientYStatus() {
    var r =
        leer(
            """
            From: postmaster@outlook.com
            Subject: Undeliverable

            Final-Recipient: rfc822;<ana@outlook.com>
            Status: 5.1.1
            """);
    assertEquals("ana@outlook.com", r.destinatario());
    assertEquals("5.1.1", r.motivo());
  }

  @Test
  void avisoSinDatosNoFalla() {
    var r = leer("From: mailer-daemon@x.org\nSubject: ?\n\nalgo\n");
    assertEquals("(desconocido)", r.destinatario());
    assertEquals("sin motivo en el aviso", r.motivo());
  }

  @Test void correlacionUsaIdOriginalYNuncaIdExteriorDelDsn() {
    var r = leer("From: mailer-daemon@example.test\nMessage-ID: <dsn@example.test>\n\n"
        + "Original-Message-ID: <crm.123@grupo05sa.tecnoweb.org.bo>\nFinal-Recipient: rfc822; ana@example.test\nStatus: 5.1.1\n");
    assertEquals("<crm.123@grupo05sa.tecnoweb.org.bo>",r.originalMessageId());
    assertNull(leer("Message-ID: <dsn@example.test>\n\nStatus: 5.1.1\n").originalMessageId());
  }

  @Test void noSeMarcaLeidoAntesDePersistirYErrorConservaAviso() throws Exception {
    var mensaje = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()));
    var r = new Rebotes.Rebote("ana@example.test","5.1.1","<crm.qa@grupo05sa.tecnoweb.org.bo>");
    assertThrows(jakarta.mail.MessagingException.class,()->Rebotes.confirmar(mensaje,r,x->{
      assertFalse(mensaje.isSet(jakarta.mail.Flags.Flag.SEEN)); throw new java.sql.SQLException("QA desconectado");
    },true));
    assertFalse(mensaje.isSet(jakarta.mail.Flags.Flag.SEEN));
    Rebotes.confirmar(mensaje,r,x->assertFalse(mensaje.isSet(jakarta.mail.Flags.Flag.SEEN)),true);
    assertTrue(mensaje.isSet(jakarta.mail.Flags.Flag.SEEN));
  }
}
