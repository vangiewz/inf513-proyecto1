package bo.uagrm.crm.dominio.captacion;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Entidad prospecto (lead): persona interesada en la oferta. */
public record Prospecto(
    int id,
    String ci,
    String nombre,
    String apellido,
    String telefono,
    String correo,
    String origen) {

  public static final String SQL =
      "SELECT id, documento, nombre, apellido, telefono, correo, origen FROM prospectos";

  public static Prospecto de(ResultSet rs) throws SQLException {
    return new Prospecto(
        rs.getInt("id"),
        rs.getString("documento"),
        rs.getString("nombre"),
        rs.getString("apellido"),
        rs.getString("telefono"),
        rs.getString("correo"),
        rs.getString("origen"));
  }

  public String getNombreCompleto() {
    return nombre + " " + apellido;
  }
}
