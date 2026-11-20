package bo.uagrm.crm;

import java.util.concurrent.*;
import java.util.logging.*;

/** Verificación independiente: una espera JDBC nunca detiene el sondeo POP3. */
final class DisponibilidadBD implements AutoCloseable {
  @FunctionalInterface interface Verificar { void ejecutar() throws Exception; }
  private final Verificar verificar;
  private final ScheduledExecutorService ejecutor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "crm-salud-bd"); t.setDaemon(true); return t;
  });
  private volatile boolean disponible;
  private volatile long generacion;
  private int fallos;
  DisponibilidadBD(Verificar verificar) { this.verificar = verificar; }
  void iniciar() { ejecutor.execute(this::ciclo); }
  boolean disponible() { return disponible; }
  synchronized void invalidar() { disponible = false; generacion++; }
  private void ciclo() {
    comprobar();
    long espera = fallos == 0 ? 30 : Math.min(60, 5L << Math.min(4, fallos - 1));
    if (!ejecutor.isShutdown()) ejecutor.schedule(this::ciclo, espera, TimeUnit.SECONDS);
  }
  void comprobar() {
    long inicio = System.nanoTime(), version = generacion;
    boolean antes = disponible;
    disponible = false; // durante una comprobación bloqueada no prestar una salud antigua
    try {
      verificar.ejecutar();
      synchronized (this) { if (version == generacion) disponible = true; }
      fallos = 0;
      if (!antes && disponible) Logger.getLogger("crm").info("RECUPERADO BD: modelo y permisos verificados");
    } catch (Exception e) {
      invalidar(); fallos++;
      Logger.getLogger("crm").log(Level.WARNING, "BD no disponible; POP3 continúa; verificación_ms="
          + (System.nanoTime() - inicio) / 1_000_000, e);
    }
  }
  public void close() { ejecutor.shutdownNow(); }
}
