package bo.uagrm.crm.dominio.usuarios;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Entidad usuario. Su clave es el correo, que es también la identidad con la que envía comandos. La
 * credencial vale entre fecha_inicio y fecha_fin (inclusive).
 */
public record Usuario(
    String correo,
    String nombre,
    String apellido,
    String telefono,
    Rol rol,
    String estado,
    LocalDate fechaInicio,
    LocalDate fechaFin) {

  public static final String SQL =
      "SELECT correo, nombre, apellido, telefono, rol, estado, fecha_inicio, fecha_fin FROM usuarios";
  public static final String[] COLUMNAS = {"Correo", "Nombre", "Teléfono", "Rol", "Estado", "Vigencia"};
  public static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  public static Usuario de(ResultSet rs) throws SQLException {
    return new Usuario(
        rs.getString("correo"),
        rs.getString("nombre"),
        rs.getString("apellido"),
        rs.getString("telefono"),
        Rol.deNombre(rs.getString("rol")),
        rs.getString("estado"),
        rs.getObject("fecha_inicio", LocalDate.class),
        rs.getObject("fecha_fin", LocalDate.class));
  }

  public String getNombreCompleto() {
    return nombre + " " + apellido;
  }

  public boolean activo() {
    return "activo".equals(estado);
  }

  /** La credencial vale ese día: entre fecha_inicio y fecha_fin, ambas incluidas. */
  public boolean vigente(LocalDate dia) {
    return !dia.isBefore(fechaInicio) && !dia.isAfter(fechaFin);
  }

  public String vigencia() {
    return fechaInicio.format(DIA) + " a " + fechaFin.format(DIA);
  }

  public String[] fila() {
    return new String[] {
      correo, getNombreCompleto(), telefono == null ? "" : telefono, rol.nombre(), estado, vigencia()
    };
  }
}
