package bo.uagrm.crm;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.mail.MessagingException;
import bo.uagrm.crm.presentacion.SmtpSender;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class EntregaCorreoTest {
  @TempDir Path dir;
  String id = "a".repeat(64);
  @Test void pendienteNoEjecutaYSeRecuperaSinDuplicarTrasReinicioOQuitFallido() throws Exception {
    var operaciones = new AtomicInteger(); var envios = new AtomicInteger();
    EntregaCorreo.Preparar preparar = () -> { operaciones.incrementAndGet(); return List.of(new byte[]{1}); };
    EntregaCorreo.Enviar enviar = m -> envios.incrementAndGet();
    assertFalse(new EntregaCorreo(dir).atender(id, false, preparar, enviar));
    assertEquals(0, operaciones.get());
    assertTrue(new EntregaCorreo(dir).atender(id, true, preparar, enviar));
    assertTrue(new EntregaCorreo(dir).atender(id, false, preparar, enviar));
    assertEquals(1, operaciones.get()); assertEquals(1, envios.get());
  }
  @Test void smtpSinDataRecuperaRespuestaDurableSinReejecutarOperacion() throws Exception {
    var ops = new AtomicInteger(); var enviados = new ArrayList<Byte>();
    EntregaCorreo.Preparar p = () -> { ops.incrementAndGet(); return List.of(new byte[]{1}, new byte[]{2}); };
    assertThrows(SmtpSender.NoEnviado.class, () -> new EntregaCorreo(dir).atender(id, true, p, m -> {
      if (m[0] == 2) throw new SmtpSender.NoEnviado(new MessagingException("conexión rechazada"));
      enviados.add(m[0]);
    }));
    assertTrue(new EntregaCorreo(dir).atender(id, false, p, m -> enviados.add(m[0])));
    assertEquals(List.of((byte)1, (byte)2), enviados); assertEquals(1, ops.get());
  }
  @Test void noRepiteDataDeAceptacionIncierta() throws Exception {
    var n = new AtomicInteger();
    assertThrows(MessagingException.class, () -> new EntregaCorreo(dir).atender(id, true,
        () -> List.of(new byte[]{1}), m -> { n.incrementAndGet(); throw new MessagingException("corte en DATA"); }));
    assertThrows(IllegalStateException.class, () -> new EntregaCorreo(dir).atender(id, true,
        () -> fail("no reejecutar"), m -> fail("no reenviar")));
    assertEquals(1, n.get());
  }
  @Test void noRepiteOperacionDeCommitIncierto() {
    assertThrows(Exception.class, () -> new EntregaCorreo(dir).atender(id, true,
        () -> { throw new Exception("corte después de commit"); }, m -> fail()));
    assertThrows(IllegalStateException.class, () -> new EntregaCorreo(dir).atender(id, true,
        () -> fail("no repetir operación"), m -> fail()));
  }
  @Test void falloSinEfectosPuedeReintentarse() throws Exception {
    assertThrows(EntregaCorreo.SinEfectos.class, () -> new EntregaCorreo(dir).atender(id, true,
        () -> { throw new EntregaCorreo.SinEfectos(new Exception("BD caída antes del dominio")); }, m -> fail()));
    assertTrue(new EntregaCorreo(dir).atender(id, true, List::of, m -> fail()));
  }
  @Test void diarioDanadoNoAutorizaEjecucionNiBorrado() throws Exception {
    Files.write(dir.resolve(id + ".estado"), new byte[]{1});
    assertThrows(java.io.IOException.class, () -> new EntregaCorreo(dir).atender(id, true,
        () -> fail(), m -> fail()));
  }
}
