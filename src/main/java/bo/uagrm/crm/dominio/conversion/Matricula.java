package bo.uagrm.crm.dominio.conversion;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.math.BigDecimal;
import java.sql.*;

/** Inscripcion comercial compartida con web. El estado de exportacion al SIS es independiente. */
public record Matricula(
    int nro,
    int nroTarjeta,
    String ci,
    String prospecto,
    String oferta,
    BigDecimal monto,
    String estado,
    String registradoPor,
    String fecha) {
  public static final String SQL =
      """
SELECT m.nro_solicitud,i.nro_tarjeta,p.documento,p.nombre || ' ' || p.apellido AS prospecto,
sc.modulo_codigo AS codigo,sc.precio_acordado AS monto,'Confirmada' AS estado,
u.nombre || ' ' || u.apellido AS registrado_por,m.fecha_confirmacion AS fecha
FROM inscripciones_comerciales m JOIN solicitudes_compra sc ON sc.nro_solicitud=m.nro_solicitud
JOIN ordenes_pago op ON op.nro_orden=sc.nro_orden JOIN intereses origen ON origen.nro_tarjeta=op.nro_tarjeta
LEFT JOIN intereses modulo ON modulo.documento=origen.documento AND modulo.modulo_codigo=sc.modulo_codigo
JOIN intereses i ON i.nro_tarjeta=COALESCE(modulo.nro_tarjeta,origen.nro_tarjeta)
JOIN prospectos p ON p.documento=i.documento LEFT JOIN usuarios u ON u.correo=op.vendedor_correo
""";
  public static final String[] COLUMNAS = {
    "Nro.",
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
        r.getInt("nro_solicitud"),
        r.getInt("nro_tarjeta"),
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
      "" + nro,
      fecha,
      "#" + nroTarjeta,
      ci,
      prospecto,
      oferta,
      DatabaseService.texto(monto),
      estado,
      registradoPor
    };
  }
}
