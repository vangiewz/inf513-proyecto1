package bo.uagrm.crm.dominio.embudo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

import bo.uagrm.crm.dominio.captacion.Prospecto;
import bo.uagrm.crm.persistencia.DatabaseService;

/** Entidad interes: un prospecto interesado en una oferta. Es la tarjeta del tablero Kanban. */
public record Interes(int id, String estado, Prospecto prospecto, String ofertaCodigo, String ofertaNombre,
                      String ofertaEstado, BigDecimal precio, Integer coordinadorId, Integer vendedorId,
                      String vendedor, String motivo, String creadoEn) {

    public static final String SQL = """
            SELECT i.id, i.estado, i.vendedor_id, i.creado_en,
                   p.id AS prospecto_id, p.ci, p.nombre, p.apellido, p.telefono, p.correo, p.origen,
                   o.codigo, o.nombre AS oferta, o.estado AS oferta_estado, o.precio, o.coordinador_id,
                   v.nombre || ' ' || v.apellido AS vendedor, mp.nombre AS motivo
            FROM interes i
            JOIN prospecto p ON p.id = i.prospecto_id
            JOIN oferta o ON o.id = i.oferta_id
            LEFT JOIN usuario v ON v.id = i.vendedor_id
            LEFT JOIN motivo_perdida mp ON mp.id = i.motivo_perdida_id""";
    public static final String[] COLUMNAS = {"Tarjeta", "CI", "Prospecto", "Teléfono", "Correo", "Origen", "Oferta", "Estado", "Vendedor"};

    public static Interes de(ResultSet rs) throws SQLException {
        Prospecto p = new Prospecto(rs.getInt("prospecto_id"), rs.getString("ci"), rs.getString("nombre"),
                rs.getString("apellido"), rs.getString("telefono"), rs.getString("correo"), rs.getString("origen"));
        return new Interes(rs.getInt("id"), rs.getString("estado"), p, rs.getString("codigo"), rs.getString("oferta"),
                rs.getString("oferta_estado"), rs.getBigDecimal("precio"), (Integer) rs.getObject("coordinador_id"),
                (Integer) rs.getObject("vendedor_id"), rs.getString("vendedor"), rs.getString("motivo"),
                DatabaseService.texto(rs.getTimestamp("creado_en")));
    }

    public boolean cerrada() {
        return "Convertido".equals(estado) || "Perdido".equals(estado);
    }

    public String nombreVendedor() {
        return vendedor == null ? "Bolsa común" : vendedor;
    }

    public String[] fila() {
        return new String[]{"#" + id, prospecto.ci(), prospecto.getNombreCompleto(), nv(prospecto.telefono()),
                nv(prospecto.correo()), prospecto.origen(), ofertaCodigo, estado, nombreVendedor()};
    }

    private static String nv(String s) {
        return s == null ? "" : s;
    }
}
