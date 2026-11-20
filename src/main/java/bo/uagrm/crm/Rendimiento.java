package bo.uagrm.crm;

import java.time.Instant;
import java.util.logging.Logger;

/** Mediciones locales monotónicas; no registra asuntos, cuerpos ni credenciales. */
public final class Rendimiento {
  private Rendimiento() {}
  private static final ThreadLocal<Medicion> ACTUAL = new ThreadLocal<>();
  private static final class Medicion {
    String id;
    long inicio = System.nanoTime();
    long bd, conexion, parser, html, smtp, smtpConexion;
  }
  public static void iniciar(String id) {
    Medicion m = new Medicion(); m.id = id; ACTUAL.set(m);
    Logger.getLogger("crm").info("PERF inicio id=" + id + " utc=" + Instant.now());
  }
  public static void sumar(String etapa, long inicio) {
    Medicion m = ACTUAL.get(); if (m == null) return;
    long ns = System.nanoTime() - inicio;
    switch (etapa) {
      case "bd" -> m.bd += ns;
      case "conexion" -> m.conexion += ns;
      case "parser" -> m.parser += ns;
      case "html" -> m.html += ns;
      case "smtp" -> m.smtp += ns;
      case "smtpConexion" -> m.smtpConexion += ns;
      default -> { }
    }
  }
  public static void terminar() {
    // Los proveedores tienen relojes propios; estas duraciones usan solo nanoTime.
    Medicion m = ACTUAL.get(); if (m == null) return;
    Logger.getLogger("crm").info(String.format(java.util.Locale.ROOT,
        "PERF fin id=%s utc=%s total_ms=%.3f bd_sql_ms=%.3f bd_conexion_ms=%.3f parser_ms=%.3f formato_ms=%.3f smtp_ms=%.3f smtp_conexion_ms=%.3f",
        m.id, Instant.now(), (System.nanoTime()-m.inicio)/1e6, m.bd/1e6,
        m.conexion/1e6, m.parser/1e6, m.html/1e6, m.smtp/1e6, m.smtpConexion/1e6));
    ACTUAL.remove();
  }
  public static String id() { Medicion m = ACTUAL.get(); return m == null ? null : m.id; }
}
