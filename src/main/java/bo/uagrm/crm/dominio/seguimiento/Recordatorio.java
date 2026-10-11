package bo.uagrm.crm.dominio.seguimiento;

import java.sql.ResultSet;
import java.sql.SQLException;

import bo.uagrm.crm.persistencia.DatabaseService;

/** Entidad recordatorio de contacto (RF4.3). Llega por correo a su dueño cuando vence. */
public record Recordatorio(int id, int tarjetaId, String prospecto, String telefono, String fechaHora, String descripcion,
                           String estado, int usuarioId, String usuario, String correoUsuario) {

    public static final String SQL = """
            SELECT r.id, r.interes_id, p.nombre || ' ' || p.apellido AS prospecto, p.telefono, r.fecha_hora, r.descripcion,
                   r.estado, r.usuario_id, u.nombre || ' ' || u.apellido AS usuario, u.correo
            FROM recordatorio r
            JOIN interes i ON i.id = r.interes_id
            JOIN prospecto p ON p.id = i.prospecto_id
            JOIN usuario u ON u.id = r.usuario_id""";
    public static final String[] COLUMNAS = {"ID", "Fecha y hora", "Tarjeta", "Prospecto", "Teléfono", "Descripción", "Responsable"};

    public static Recordatorio de(ResultSet rs) throws SQLException {
        return new Recordatorio(rs.getInt("id"), rs.getInt("interes_id"), rs.getString("prospecto"), rs.getString("telefono"),
                DatabaseService.texto(rs.getTimestamp("fecha_hora")), rs.getString("descripcion"), rs.getString("estado"),
                rs.getInt("usuario_id"), rs.getString("usuario"), rs.getString("correo"));
    }

    public String[] fila() {
        return new String[]{String.valueOf(id), fechaHora, "#" + tarjetaId, prospecto, telefono == null ? "" : telefono, descripcion, usuario};
    }
}
