package bo.uagrm.crm.dominio.seguimiento;

import java.sql.ResultSet;
import java.sql.SQLException;

import bo.uagrm.crm.persistencia.DatabaseService;

/** Entidad interacción o nota manual sobre una tarjeta (RF4.1, RF4.2). */
public record Interaccion(int id, int tarjetaId, String tipo, String descripcion, String fecha) {

    public static final String SQL = "SELECT id, interes_id, tipo, descripcion, fecha FROM interaccion";

    public static Interaccion de(ResultSet rs) throws SQLException {
        return new Interaccion(rs.getInt("id"), rs.getInt("interes_id"), rs.getString("tipo"), rs.getString("descripcion"),
                DatabaseService.texto(rs.getTimestamp("fecha")));
    }
}
