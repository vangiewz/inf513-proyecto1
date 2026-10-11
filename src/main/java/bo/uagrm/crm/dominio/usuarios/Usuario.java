package bo.uagrm.crm.dominio.usuarios;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Entidad usuario: su correo es la identidad con la que envía comandos. */
public record Usuario(int id, String nombre, String apellido, String correo, String telefono, Rol rol, String estado) {

    public static final String SQL = "SELECT id, nombre, apellido, correo, telefono, rol, estado FROM usuario";
    public static final String[] COLUMNAS = {"ID", "Nombre", "Correo", "Teléfono", "Rol", "Estado"};

    public static Usuario de(ResultSet rs) throws SQLException {
        return new Usuario(rs.getInt("id"), rs.getString("nombre"), rs.getString("apellido"), rs.getString("correo"),
                rs.getString("telefono"), Rol.deNombre(rs.getString("rol")), rs.getString("estado"));
    }

    public String getNombreCompleto() {
        return nombre + " " + apellido;
    }

    public boolean activo() {
        return "activo".equals(estado);
    }

    public String[] fila() {
        return new String[]{String.valueOf(id), getNombreCompleto(), correo, telefono == null ? "" : telefono, rol.nombre(), estado};
    }
}
