package bo.uagrm.crm.dominio.seguimiento;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Entidad recordatorio de contacto (RF4.3). Llega por correo a su dueño cuando vence. */
public record Recordatorio(
    int id,
    int tarjetaId,
    String prospecto,
    String telefono,
    String fechaHora,
    String descripcion,
    String estado,
    int usuarioId,
    String usuario,
    String correoUsuario) {

  public static final String SQL =
      """
SELECT r.id, r.interes_id, p.nombre || ' ' || p.apellido AS prospectos, p.telefono, r.fecha_programada, r.descripcion,
       r.estado, r.usuario_id, u.nombre || ' ' || u.apellido AS usuarios, u.correo
FROM recordatorios r
JOIN intereses i ON i.id = r.interes_id
JOIN prospectos p ON p.id = i.prospecto_id
JOIN usuarios u ON u.id = r.usuario_id""";
  public static final String[] COLUMNAS = {
    "ID", "Fecha y hora", "Tarjeta", "Prospecto", "Teléfono", "Descripción", "Responsable"
  };

  public static Recordatorio de(ResultSet rs) throws SQLException {
    return new Recordatorio(
        rs.getInt("id"),
        rs.getInt("interes_id"),
        rs.getString("prospectos"),
        rs.getString("telefono"),
        DatabaseService.texto(rs.getTimestamp("fecha_programada")),
        rs.getString("descripcion"),
        rs.getString("estado"),
        rs.getInt("usuario_id"),
        rs.getString("usuarios"),
        rs.getString("correo"));
  }

  public String[] fila() {
    return new String[] {
      String.valueOf(id),
      fechaHora,
      "#" + tarjetaId,
      prospecto,
      telefono == null ? "" : telefono,
      descripcion,
      usuario
    };
  }
}
