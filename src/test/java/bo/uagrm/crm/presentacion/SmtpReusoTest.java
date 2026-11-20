package bo.uagrm.crm.presentacion;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.mail.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SmtpReusoTest {
  static class Falso extends Transport {
    int conexiones, envios; boolean falla;
    Falso() { super(Session.getInstance(new Properties()), null); }
    @Override public void connect() { conexiones++; setConnected(true); }
    @Override public boolean isConnected() { return super.isConnected(); }
    @Override public void sendMessage(Message m, Address[] destinos) throws MessagingException {
      envios++; if (falla) throw new MessagingException("corte durante DATA");
    }
    @Override public void close() { setConnected(false); }
  }
  @Test void reutilizaYCierra() throws Exception {
    Falso t = new Falso();
    try (var sender = new SmtpSender(() -> t)) {
      sender.enviar("persona@example.test", "uno", "x", "x");
      sender.enviar("persona@example.test", "dos", "x", "x");
      assertEquals(1, t.conexiones); assertEquals(2, t.envios);
    }
    assertFalse(t.isConnected());
  }
  @Test void fallaNoReenviaElMensajeYProximaLlamadaReconecta() throws Exception {
    Falso primero = new Falso(), segundo = new Falso(); primero.falla=true;
    Queue<Falso> cola = new ArrayDeque<>(List.of(primero, segundo));
    try (var sender = new SmtpSender(cola::remove)) {
      assertThrows(MessagingException.class,
          () -> sender.enviar("persona@example.test", "uno", "x", "x"));
      assertEquals(1, primero.envios); assertFalse(primero.isConnected());
      assertEquals(0, segundo.envios);
      sender.enviar("persona@example.test", "dos", "x", "x");
      assertEquals(1, segundo.conexiones); assertEquals(1, segundo.envios);
    }
  }
}
