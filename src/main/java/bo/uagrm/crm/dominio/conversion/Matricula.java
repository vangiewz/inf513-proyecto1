package bo.uagrm.crm.dominio.conversion;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

import bo.uagrm.crm.persistencia.DatabaseService;

/** Entidad matrícula: conversión validada que espera ser matriculada por el SIS (Grupo 06 SA). */
public record Matricula(int id, int tarjetaId, String ci, String prospecto, String oferta, BigDecimal monto,
                        String estado, String registradoPor, String fecha) {

    public static final String SQL = """
            SELECT m.id, m.interes_id, p.ci, p.nombre || ' ' || p.apellido AS prospecto, o.codigo, m.monto, m.estado,
                   u.nombre || ' ' || u.apellido AS registrado_por, m.fecha
            FROM matricula m
            JOIN interes i ON i.id = m.interes_id
            JOIN prospecto p ON p.id = i.prospecto_id
            JOIN oferta o ON o.id = i.oferta_id
            JOIN usuario u ON u.id = m.usuario_id""";
    public static final String[] COLUMNAS = {"ID", "Fecha", "Tarjeta", "CI", "Prospecto", "Oferta", "Monto (Bs)", "Estado", "Registró"};

    public static Matricula de(ResultSet rs) throws SQLException {
        return new Matricula(rs.getInt("id"), rs.getInt("interes_id"), rs.getString("ci"), rs.getString("prospecto"),
                rs.getString("codigo"), rs.getBigDecimal("monto"), rs.getString("estado"), rs.getString("registrado_por"),
                DatabaseService.texto(rs.getTimestamp("fecha")));
    }

    public String[] fila() {
        return new String[]{String.valueOf(id), fecha, "#" + tarjetaId, ci, prospecto, oferta, DatabaseService.texto(monto), estado, registradoPor};
    }
}
