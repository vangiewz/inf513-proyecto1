package bo.uagrm.crm;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

/** Qué rechazos del SMTP no se reintentan. */
class AppTest {

  @Test
  void soloLaDireccionInvalidaEsDefinitiva() throws Exception {
    Address[] mala = {new InternetAddress("noexiste@example.test")};
    assertTrue(App.definitivo(new SendFailedException("550 5.1.1", null, null, null, mala)));
    assertFalse(App.definitivo(new SendFailedException("421 4.7.0 intente luego")), "temporal");
    assertFalse(App.definitivo(new MessagingException("Couldn't connect to host")), "red");
    assertFalse(App.definitivo(new jakarta.mail.AuthenticationFailedException("535")), "clave: se reintenta");
  }
}
