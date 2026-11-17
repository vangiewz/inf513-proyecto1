package bo.uagrm.crm.aplicacion;

import java.util.List;

/**
 * Contenido de la respuesta (aún sin HTML): resultado, título, mensaje y bloques de datos.
 * ResponseFormatter la convierte en el correo. resultado: Éxito, Error, Denegado o Aviso.
 */
public record Respuesta(
    String resultado,
    String titulo,
    String mensaje,
    List<Bloque> bloques,
    List<Aviso> avisos,
    List<bo.uagrm.crm.presentacion.Adjunto> archivos) {

  public Respuesta(
      String resultado, String titulo, String mensaje, List<Bloque> bloques, List<Aviso> avisos) {
    this(resultado, titulo, mensaje, bloques, avisos, List.of());
  }

  public Respuesta {
    bloques = List.copyOf(bloques);
    avisos = List.copyOf(avisos);
    archivos = List.copyOf(archivos);
  }

  public Respuesta conArchivos(List<bo.uagrm.crm.presentacion.Adjunto> nuevos) {
    return new Respuesta(resultado, titulo, mensaje, bloques, avisos, nuevos);
  }

  public sealed interface Bloque permits Tabla, Carril {}

  /** Tabla de datos. Una columna cuyo encabezado termina en "%" se dibuja con barra. */
  public record Tabla(String titulo, String[] columnas, List<String[]> filas) implements Bloque {}

  /** Carril del tablero Kanban: una etapa y sus tarjetas (cada tarjeta, varias líneas). */
  public record Carril(String estado, List<String[]> tarjetas) implements Bloque {}

  /** Correo adicional a otro usuario (p. ej., al vendedor que recibe un prospecto). */
  public record Aviso(String para, Respuesta contenido) {}

  public Respuesta conAvisos(List<Aviso> nuevos) {
    return new Respuesta(resultado, titulo, mensaje, bloques, nuevos, archivos);
  }

  /** Asunto del correo de respuesta. */
  public String asunto() {
    return titulo;
  }
}
