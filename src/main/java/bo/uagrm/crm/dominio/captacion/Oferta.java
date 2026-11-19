package bo.uagrm.crm.dominio.captacion;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.math.BigDecimal;
import java.sql.*;

/**
 * Instantánea del catalogo externo del CRM. No contiene prospectos ni ventas.
 */
public record Oferta(
    String codigo,
    String nombre,
    String tipo,
    String programa,
    int numModulos,
    BigDecimal precio,
    String estado,
    String coordinadorCorreo,
    String coordinador,
    String programaCodigo,
    String anuncioId,
    String descripcion) {
  public static final String SQL =
      "SELECT o.*,pg.nombre AS programa_nombre,pr.coordinador_correo,u.nombre || ' ' || u.apellido AS coordinador FROM"
          + " catalogo_cache o JOIN programas pg ON pg.codigo=o.programa_codigo LEFT JOIN programas_responsables pr ON"
          + " pr.programa_codigo=o.programa_codigo LEFT JOIN usuarios u ON"
          + " u.correo=pr.coordinador_correo";
  public static final String[] COLUMNAS = {
    "Código",
    "Oferta",
    "Tipo",
    "Precio (Bs)",
    "Estado",
    "Programa",
    "Código programa",
    "Coordinador"
  };

  public static Oferta de(ResultSet r) throws SQLException {
    return new Oferta(
        r.getString("codigo"),
        r.getString("nombre"),
        "modulo",
        r.getString("programa_nombre"),
        1,
        r.getBigDecimal("precio"),
        r.getString("estado"),
        r.getString("coordinador_correo"),
        r.getString("coordinador"),
        r.getString("programa_codigo"),
        r.getString("anuncio_codigo"),
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
