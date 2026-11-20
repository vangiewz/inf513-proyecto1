package bo.uagrm.crm.dominio.personas;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Registro del ejercicio docente; no representa ni crea una cuenta de acceso al CRM. */
public record Persona(
    String documento,
    String nombres,
    String apellidos,
    String ocupacion,
    String telefonoFijo,
    String telefonoCelular,
    String correo) {

  public static final String SQL =
      "SELECT documento,nombres,apellidos,ocupacion,telefono_fijo,telefono_celular,correo FROM persona";
  public static final String[] COLUMNAS = {
    "Documento", "Nombres", "Apellidos", "Ocupación", "Teléfono fijo", "Celular", "Correo"
  };

  public static Persona de(ResultSet rs) throws SQLException {
    return new Persona(rs.getString("documento"), rs.getString("nombres"),
        rs.getString("apellidos"), rs.getString("ocupacion"), rs.getString("telefono_fijo"),
        rs.getString("telefono_celular"), rs.getString("correo"));
  }

  public String[] fila() {
    return new String[] {documento, nombres, apellidos, ocupacion, telefonoFijo, telefonoCelular, correo};
  }
}
