package bo.uagrm.crm.dominio.seguimiento;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Entidad recordatorio de contacto (RF4.3). Llega por correo a su dueño cuando vence. */
public record Recordatorio(
    int nro,
    int nroTarjeta,
    String prospecto,
    String telefono,
    String fechaHora,
    String descripcion,
    String estado,
    String usuarioCorreo,
    String usuario,
    String correoUsuario) {

  public static final String SQL =
      """
SELECT r.nro_recordatorio, r.nro_tarjeta, p.nombre || ' ' || p.apellido AS prospectos, p.telefono, r.fecha_programada, r.descripcion,
       r.estado, r.usuario_correo, u.nombre || ' ' || u.apellido AS usuarios, u.correo
FROM recordatorios r
JOIN intereses i ON i.nro_tarjeta = r.nro_tarjeta
JOIN prospectos p ON p.documento = i.documento
JOIN usuarios u ON u.correo = r.usuario_correo""";
  public static final String[] COLUMNAS = {
    "Nro.", "Fecha y hora", "Tarjeta", "Prospecto", "Teléfono", "Descripción", "Responsable"
  };

  public static Recordatorio de(ResultSet rs) throws SQLException {
    return new Recordatorio(
        rs.getInt("nro_recordatorio"),
        rs.getInt("nro_tarjeta"),
        rs.getString("prospectos"),
        rs.getString("telefono"),
        DatabaseService.texto(rs.getTimestamp("fecha_programada")),
        rs.getString("descripcion"),
        rs.getString("estado"),
        rs.getString("usuario_correo"),
        rs.getString("usuarios"),
        rs.getString("correo"));
  }

  public String[] fila() {
    return new String[] {
      String.valueOf(nro),
      fechaHora,
      "#" + nroTarjeta,
      prospecto,
      telefono == null ? "" : telefono,
      descripcion,
      usuario
    };
  }
}
