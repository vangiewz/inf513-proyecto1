package bo.uagrm.crm.dominio.perdidas;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Entidad motivo de pérdida con su categoría (RF3.3). usos = tarjetas perdidas por este motivo. */
public record MotivoPerdida(String nombre, String categoria, int usos) {

  public static final String SQL =
      """
      SELECT mp.nombre, mp.categoria, COUNT(i.nro_tarjeta) AS usos
      FROM motivos_perdida mp LEFT JOIN intereses i ON i.motivo_perdida = mp.nombre""";
  public static final String AGRUPAR = " GROUP BY mp.nombre";
  public static final String[] COLUMNAS = {"Motivo", "Categoría", "Tarjetas"};

  public static MotivoPerdida de(ResultSet rs) throws SQLException {
    return new MotivoPerdida(
        rs.getString("nombre"), rs.getString("categoria"), rs.getInt("usos"));
  }

  public String[] fila() {
    return new String[] {nombre, categoria, String.valueOf(usos)};
  }
}
