package bo.uagrm.crm.dominio.embudo;

import bo.uagrm.crm.dominio.captacion.Prospecto;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.math.BigDecimal;
import java.sql.*;

public record Interes(
    int nro,
    String estado,
    Prospecto prospecto,
    String ofertaCodigo,
    String ofertaNombre,
    String ofertaEstado,
    BigDecimal precio,
    String coordinadorCorreo,
    String vendedorCorreo,
    String vendedor,
    String motivo,
    String creadoEn) {
  public static final String SQL =
      """
SELECT i.nro_tarjeta,i.estado,i.vendedor_correo,i.created_at,p.documento,p.nombre,p.apellido,p.telefono,p.correo,p.origen,
i.modulo_codigo AS codigo,i.modulo_nombre AS oferta,COALESCE(o.estado,'cerrado') AS oferta_estado,i.precio,
v.nombre || ' ' || v.apellido AS vendedor,i.motivo_perdida AS motivo,pr.coordinador_correo
FROM intereses i JOIN prospectos p ON p.documento=i.documento
LEFT JOIN catalogo_cache o ON o.codigo=i.modulo_codigo
LEFT JOIN programas_responsables pr ON pr.programa_codigo=o.programa_codigo
LEFT JOIN usuarios v ON v.correo=i.vendedor_correo
""";
  public static final String[] COLUMNAS = {
    "Tarjeta", "CI", "Prospecto", "Teléfono", "Correo", "Origen", "Oferta", "Estado", "Vendedor"
  };

  public static Interes de(ResultSet r) throws SQLException {
    var p =
        new Prospecto(
            r.getString("documento"),
            r.getString("nombre"),
            r.getString("apellido"),
            r.getString("telefono"),
            r.getString("correo"),
            r.getString("origen"));
    return new Interes(
        r.getInt("nro_tarjeta"),
        r.getString("estado"),
        p,
        r.getString("codigo"),
        r.getString("oferta"),
        r.getString("oferta_estado"),
        r.getBigDecimal("precio"),
        r.getString("coordinador_correo"),
        r.getString("vendedor_correo"),
        r.getString("vendedor"),
        r.getString("motivo"),
        DatabaseService.texto(r.getTimestamp("created_at")));
  }

  public boolean cerrada() {
    return "Convertido".equals(estado) || "Perdido".equals(estado);
  }

  public String nombreVendedor() {
    return vendedor == null ? "Bolsa común" : vendedor;
  }

  public String[] fila() {
    return new String[] {
      "#" + nro,
      prospecto.ci(),
      prospecto.getNombreCompleto(),
      nv(prospecto.telefono()),
      nv(prospecto.correo()),
      prospecto.origen(),
      ofertaCodigo,
      EmbudoService.etapaCorreo(estado),
      nombreVendedor()
    };
  }

  private static String nv(String x) {
    return x == null ? "" : x;
  }
}
