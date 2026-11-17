package bo.uagrm.crm.dominio.embudo;

import bo.uagrm.crm.dominio.captacion.Prospecto;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.math.BigDecimal;
import java.sql.*;

public record Interes(
    int id,
    String estado,
    Prospecto prospecto,
    String ofertaCodigo,
    String ofertaNombre,
    String ofertaEstado,
    BigDecimal precio,
    Integer coordinadorId,
    Integer vendedorId,
    String vendedor,
    String motivo,
    String creadoEn) {
  public static final String SQL =
      """
SELECT i.id,i.estado,i.vendedor_id,i.created_at,p.id AS prospecto_id,p.documento,p.nombre,p.apellido,p.telefono,p.correo,p.origen,
i.external_modulo_id AS codigo,i.modulo_nombre AS oferta,COALESCE(o.estado,'cerrado') AS oferta_estado,i.precio,
v.nombre || ' ' || v.apellido AS vendedor,mp.nombre AS motivo,pr.coordinador_id
FROM intereses i JOIN prospectos p ON p.id=i.prospecto_id
LEFT JOIN catalogo_cache o ON o.codigo=i.external_modulo_id
LEFT JOIN programas_responsables pr ON pr.external_programa_id=o.programa_codigo
LEFT JOIN usuarios v ON v.id=i.vendedor_id LEFT JOIN motivos_perdida mp ON mp.id=i.motivo_perdida_id
""";
  public static final String[] COLUMNAS = {
    "Tarjeta", "CI", "Prospecto", "Teléfono", "Correo", "Origen", "Oferta", "Estado", "Vendedor"
  };

  public static Interes de(ResultSet r) throws SQLException {
    var p =
        new Prospecto(
            r.getInt("prospecto_id"),
            r.getString("documento"),
            r.getString("nombre"),
            r.getString("apellido"),
            r.getString("telefono"),
            r.getString("correo"),
            r.getString("origen"));
    Number vendedor = (Number) r.getObject("vendedor_id");
    Number coordinador = (Number) r.getObject("coordinador_id");
    return new Interes(
        r.getInt("id"),
        r.getString("estado"),
        p,
        r.getString("codigo"),
        r.getString("oferta"),
        r.getString("oferta_estado"),
        r.getBigDecimal("precio"),
        coordinador == null ? null : Math.toIntExact(coordinador.longValue()),
        vendedor == null ? null : Math.toIntExact(vendedor.longValue()),
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
      "#" + id,
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
