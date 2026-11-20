package bo.uagrm.crm;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

/** Qué rechazos del SMTP no se reintentan. */
class AppTest {

  private static App conReloj(java.util.concurrent.atomic.AtomicLong reloj) {
    Config config = new Config("127.0.0.1",110,"qa","ficticia","127.0.0.1",587,
        "qa@example.test","ficticia","buzon@example.test",
        "jdbc:postgresql://127.0.0.1:1/qa","qa","ficticia",3);
    return new App(config, reloj::get);
  }

  @Test void rebotesEsperanUnMinutoDesdeFinInclusoConSondeoPop3CadaTresSegundos() {
    var reloj = new java.util.concurrent.atomic.AtomicLong();
    var intentos = new java.util.concurrent.atomic.AtomicInteger();
    App app = conReloj(reloj);
    Runnable lento = () -> { intentos.incrementAndGet(); reloj.addAndGet(5_000_000_000L); };
    assertTrue(app.revisarRebotesSiCorresponde(lento)); // termina en t=5s
    for (long s = 8; s < 65; s += 3) {
      reloj.set(s * 1_000_000_000L);
      assertFalse(app.revisarRebotesSiCorresponde(lento));
    }
    assertEquals(1,intentos.get());
    reloj.set(65_000_000_000L);
    assertTrue(app.revisarRebotesSiCorresponde(lento));
    assertEquals(2,intentos.get());
  }

  @Test void falloImapTampocoProduceReintentoInmediato() {
    var reloj = new java.util.concurrent.atomic.AtomicLong();
    App app = conReloj(reloj);
    assertThrows(IllegalStateException.class, () -> app.revisarRebotesSiCorresponde(() -> {
      reloj.set(7_000_000_000L);
      throw new IllegalStateException("caída IMAP simulada");
    }));
    reloj.set(66_999_000_000L);
    assertFalse(app.revisarRebotesSiCorresponde(() -> fail("antes del minuto")));
    reloj.set(67_000_000_000L);
    assertTrue(app.revisarRebotesSiCorresponde(() -> {}));
  }

  @Test
  void soloLaDireccionInvalidaEsDefinitiva() throws Exception {
    Address[] mala = {new InternetAddress("noexiste@example.test")};
    assertTrue(App.definitivo(new SendFailedException("550 5.1.1", null, null, null, mala)));
    assertFalse(App.definitivo(new SendFailedException("421 4.7.0 intente luego")), "temporal");
    assertFalse(App.definitivo(new MessagingException("Couldn't connect to host")), "red");
    assertFalse(App.definitivo(new jakarta.mail.AuthenticationFailedException("535")), "clave: se reintenta");
  }
}
