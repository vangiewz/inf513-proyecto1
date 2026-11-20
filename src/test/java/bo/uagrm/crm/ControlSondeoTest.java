package bo.uagrm.crm;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import bo.uagrm.crm.persistencia.DatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlSondeoTest {
  @TempDir Path temp;

  @Test void reintentosProgresivosYRecuperacion() {
    ControlSondeo p = new ControlSondeo(3000, 30000, 60000);
    assertEquals(List.of(3000L,6000L,12000L,24000L,30000L,30000L),
        java.util.stream.IntStream.rangeClosed(1,6).mapToObj(n -> p.demora(n,false)).toList());
    assertEquals(60000, p.demora(3,true));
    assertEquals(3000, p.demora(0,false), "la recuperación vuelve al intervalo normal");
  }

  @Test void demoraSeCuentaDesdeElFinalInclusoSiElCicloTardaMasDeTresSegundos() throws Exception {
    AtomicLong reloj = new AtomicLong();
    List<Long> inicios = new ArrayList<>();
    AtomicInteger ciclos = new AtomicInteger();
    BucleSondeo[] bucle = new BucleSondeo[1];
    bucle[0] = new BucleSondeo(ms -> {
      assertEquals(3000,ms);
      reloj.addAndGet(ms);
      if (ciclos.get() == 3) bucle[0].close();
    });
    bucle[0].ejecutar(() -> {
      inicios.add(reloj.get());
      reloj.addAndGet(7000); // reloj simulado: sondeo lento
      ciclos.incrementAndGet();
    }, () -> 3000);
    assertEquals(List.of(0L,10000L,20000L), inicios);
  }

  @Test void noHaySuperposicionNiSegundaEjecucionDelMismoBucle() throws Exception {
    CountDownLatch dentro = new CountDownLatch(1), salir = new CountDownLatch(1);
    AtomicInteger activos = new AtomicInteger(), maximo = new AtomicInteger();
    BucleSondeo bucle = new BucleSondeo();
    Thread t = new Thread(() -> {
      try { bucle.ejecutar(() -> {
        maximo.accumulateAndGet(activos.incrementAndGet(), Math::max);
        dentro.countDown();
        try { salir.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        activos.decrementAndGet();
      }, () -> 3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    });
    t.start();
    try {
      assertTrue(dentro.await(2,TimeUnit.SECONDS));
      assertThrows(IllegalStateException.class, () -> bucle.ejecutar(() -> fail("no debe ejecutarse"), () -> 3000));
      bucle.close(); salir.countDown(); t.join(2000);
      assertFalse(t.isAlive()); assertEquals(1,maximo.get());
    } finally { bucle.close(); salir.countDown(); t.join(2000); }
  }

  @Test void bloqueoPorBuzonSeLiberaAlCerrarYNoDependeDelArchivoDeConfiguracion() throws Exception {
    try (InstanciaCorreo primero = InstanciaCorreo.adquirir(temp,"mail.tecnoweb.org.bo","grupo05sa")) {
      assertThrows(IOException.class, () -> InstanciaCorreo.adquirir(temp,"MAIL.TECNOWEB.ORG.BO","GRUPO05SA"));
      try (InstanciaCorreo otro = InstanciaCorreo.adquirir(temp,"mail.tecnoweb.org.bo","otro")) {
        assertNotNull(otro);
      }
    }
    try (InstanciaCorreo reiniciado = InstanciaCorreo.adquirir(temp,"mail.tecnoweb.org.bo","grupo05sa")) {
      assertNotNull(reiniciado);
    }
  }

  @Test void cierreDuranteOperacionPermiteTerminarSinInterrumpirTransaccion() throws Exception {
    CountDownLatch dentro = new CountDownLatch(1), terminar = new CountDownLatch(1);
    AtomicInteger completados = new AtomicInteger();
    BucleSondeo bucle = new BucleSondeo();
    Thread t = new Thread(() -> {
      try {
        bucle.ejecutar(() -> {
          dentro.countDown();
          try { terminar.await(); } catch (InterruptedException e) { throw new AssertionError("No cancelar transacción",e); }
          assertFalse(Thread.currentThread().isInterrupted());
          completados.incrementAndGet();
        }, () -> 3000);
      } catch (InterruptedException e) { throw new AssertionError(e); }
    });
    t.start();
    try {
      assertTrue(dentro.await(2,TimeUnit.SECONDS));
      bucle.close(); terminar.countDown(); t.join(2000);
      assertFalse(t.isAlive()); assertEquals(1,completados.get());
    } finally { bucle.close(); terminar.countDown(); t.join(2000); }
  }

  @Test void caidaTemporalCredencialesYEsquemaSeDistinguen() {
    assertEquals(DatabaseService.TipoFallo.TEMPORAL, DatabaseService.tipoFallo(new SQLException("qa","08006")));
    assertEquals(DatabaseService.TipoFallo.TEMPORAL, DatabaseService.tipoFallo(new SQLException("qa","57P03")));
    assertEquals(DatabaseService.TipoFallo.CREDENCIALES, DatabaseService.tipoFallo(new SQLException("qa","28P01")));
    assertEquals(DatabaseService.TipoFallo.ESQUEMA, DatabaseService.tipoFallo(new SQLException("qa","42P01")));
    assertEquals(DatabaseService.TipoFallo.SQL_PERMANENTE, DatabaseService.tipoFallo(new SQLException("qa","23505")));
  }

  @Test void esperaNoPermiteBucleAgresivoTrasCaidas() throws Exception {
    AtomicLong reloj = new AtomicLong();
    AtomicInteger intentos = new AtomicInteger();
    List<Long> inicios = new ArrayList<>();
    ControlSondeo politica = new ControlSondeo(3000,30000,60000);
    BucleSondeo[] bucle = new BucleSondeo[1];
    bucle[0] = new BucleSondeo(ms -> {
      reloj.addAndGet(ms);
      if (intentos.get() == 6) bucle[0].close();
    });
    bucle[0].ejecutar(() -> { inicios.add(reloj.get()); intentos.incrementAndGet(); },
        () -> politica.demora(intentos.get() <= 4 ? intentos.get() : 0,false));
    assertEquals(List.of(0L,3000L,9000L,21000L,45000L,48000L),inicios);
  }
}
