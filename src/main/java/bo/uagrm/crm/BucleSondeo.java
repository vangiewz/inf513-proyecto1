package bo.uagrm.crm;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Ejecutor serial con fixed delay variable. Un cierre interrumpe la espera sin lanzar otro ciclo. */
public final class BucleSondeo implements AutoCloseable {
  @FunctionalInterface
  interface Espera { void dormir(long milisegundos) throws InterruptedException; }

  private final AtomicBoolean ejecutando = new AtomicBoolean();
  private volatile boolean detener;
  private volatile Thread hilo;
  private volatile boolean esperando;
  private final Espera espera;

  public BucleSondeo() { this(Thread::sleep); }
  BucleSondeo(Espera espera) { this.espera = espera; }

  public void ejecutar(Runnable ciclo, LongSupplier demora) throws InterruptedException {
    if (!ejecutando.compareAndSet(false, true))
      throw new IllegalStateException("Ya existe un sondeo en este proceso");
    hilo = Thread.currentThread();
    try {
      while (!detener && !hilo.isInterrupted()) {
        ciclo.run();
        esperando = true;
        try {
          if (!detener && !hilo.isInterrupted()) espera.dormir(demora.getAsLong());
        } finally { esperando = false; }
      }
    } finally {
      hilo = null;
      ejecutando.set(false);
    }
  }

  public void close() {
    detener = true;
    Thread actual = hilo;
    // No interrumpir JDBC/SMTP a mitad de una transacción: terminar el ciclo en curso.
    if (actual != null && actual != Thread.currentThread() && esperando) actual.interrupt();
  }

  /** El hook permite terminar la operación en curso antes de que la JVM cierre sus sockets. */
  public void cerrarYEsperar(long milisegundos) {
    Thread actual = hilo;
    close();
    if (actual != null && actual != Thread.currentThread()) {
      try { actual.join(milisegundos); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
  }
}
