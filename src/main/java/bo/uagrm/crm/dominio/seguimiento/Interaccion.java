package bo.uagrm.crm.dominio.seguimiento;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Entidad interacción o nota manual sobre una tarjeta (RF4.1, RF4.2). */
public record Interaccion(int nro, int nroTarjeta, String tipo, String descripcion, String fecha) {

  public static final String SQL =
      "SELECT nro_interaccion, nro_tarjeta, tipo, descripcion, fecha FROM interacciones";

  public static Interaccion de(ResultSet rs) throws SQLException {
    return new Interaccion(
        rs.getInt("nro_interaccion"),
        rs.getInt("nro_tarjeta"),
        rs.getString("tipo"),
        rs.getString("descripcion"),
        DatabaseService.texto(rs.getTimestamp("fecha")));
  }
}
