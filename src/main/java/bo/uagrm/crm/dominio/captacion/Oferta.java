package bo.uagrm.crm.dominio.captacion;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.math.BigDecimal;
import java.sql.*;

/**
 * Instantánea sincronizada de la oferta externa usada por web. No contiene prospectos ni ventas.
 */
public record Oferta(
    int id,
    String codigo,
    String nombre,
    String tipo,
    String programa,
    int numModulos,
    BigDecimal precio,
    String estado,
    Integer coordinadorId,
    String coordinador,
    String programaCodigo,
    String anuncioId,
    String descripcion) {
  public static final String SQL =
      "SELECT o.*,pr.coordinador_id,u.nombre || ' ' || u.apellido AS coordinador FROM"
          + " catalogo_cache o LEFT JOIN programas_responsables pr ON"
          + " pr.external_programa_id=o.programa_codigo LEFT JOIN usuarios u ON"
          + " u.id=pr.coordinador_id";
  public static final String[] COLUMNAS = {
    "Código",
    "Oferta",
    "Tipo",
    "Precio (Bs)",
    "Estado",
    "Programa",
    "Codigo programa",
    "Coordinador"
  };

  public static Oferta de(ResultSet r) throws SQLException {
    return new Oferta(
        r.getInt("id"),
        r.getString("codigo"),
        r.getString("nombre"),
        "modulo",
        r.getString("programa_nombre"),
        1,
        r.getBigDecimal("precio"),
        r.getString("estado"),
        r.getObject("coordinador_id") == null
            ? null
            : Math.toIntExact(((Number) r.getObject("coordinador_id")).longValue()),
        r.getString("coordinador"),
        r.getString("programa_codigo"),
        r.getString("external_anuncio_id"),
        r.getString("descripcion"));
  }

  public boolean abierta() {
    return "abierto".equals(estado);
  }

  public String descripcionTipo() {
    return "Módulo de " + programa;
  }

  public String[] fila() {
    return new String[] {
      codigo,
      nombre,
      descripcionTipo(),
      DatabaseService.texto(precio),
      estado,
      programa,
      programaCodigo,
      coordinador == null ? "Sin responsable" : coordinador
    };
  }
}
