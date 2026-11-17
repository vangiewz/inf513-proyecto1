package bo.uagrm.crm.dominio.conversion;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.math.BigDecimal;
import java.sql.*;

/** Inscripcion comercial compartida con web. El estado de exportacion al SIS es independiente. */
public record Matricula(
    int id,
    int tarjetaId,
    String ci,
    String prospecto,
    String oferta,
    BigDecimal monto,
    String estado,
    String registradoPor,
    String fecha) {
  public static final String SQL =
      """
SELECT m.id,i.id AS interes_id,p.documento,p.nombre || ' ' || p.apellido AS prospecto,
sc.external_modulo_id AS codigo,sc.precio_acordado AS monto,m.estado,
u.nombre || ' ' || u.apellido AS registrado_por,m.fecha_confirmacion AS fecha
FROM inscripciones_comerciales m JOIN solicitudes_compra sc ON sc.id=m.solicitud_compra_id
JOIN ordenes_pago op ON op.id=sc.orden_pago_id JOIN intereses origen ON origen.id=op.interes_id
LEFT JOIN intereses modulo ON modulo.prospecto_id=origen.prospecto_id AND modulo.external_modulo_id=sc.external_modulo_id
JOIN intereses i ON i.id=COALESCE(modulo.id,origen.id)
JOIN prospectos p ON p.id=i.prospecto_id LEFT JOIN usuarios u ON u.id=op.vendedor_id
""";
  public static final String[] COLUMNAS = {
    "ID",
    "Fecha",
    "Tarjeta",
    "CI",
    "Prospecto",
    "Oferta",
    "Monto (Bs)",
    "Estado",
    "Vendedor de la venta"
  };

  public static Matricula de(ResultSet r) throws SQLException {
    return new Matricula(
        r.getInt("id"),
        r.getInt("interes_id"),
        r.getString("documento"),
        r.getString("prospecto"),
        r.getString("codigo"),
        r.getBigDecimal("monto"),
        r.getString("estado"),
        r.getString("registrado_por"),
        DatabaseService.texto(r.getTimestamp("fecha")));
  }

  public String[] fila() {
    return new String[] {
      "" + id,
      fecha,
      "#" + tarjetaId,
      ci,
      prospecto,
      oferta,
      DatabaseService.texto(monto),
      estado,
      registradoPor
    };
  }
}
