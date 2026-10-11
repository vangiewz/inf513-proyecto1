package bo.uagrm.crm.dominio.perdidas;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Entidad motivo de pérdida con su categoría (RF3.3). usos = tarjetas perdidas por este motivo. */
public record MotivoPerdida(int id, String nombre, String categoria, int usos) {

    public static final String SQL = """
            SELECT mp.id, mp.nombre, mp.categoria, COUNT(i.id) AS usos
            FROM motivo_perdida mp LEFT JOIN interes i ON i.motivo_perdida_id = mp.id""";
    public static final String AGRUPAR = " GROUP BY mp.id";
    public static final String[] COLUMNAS = {"ID", "Motivo", "Categoría", "Tarjetas"};

    public static MotivoPerdida de(ResultSet rs) throws SQLException {
        return new MotivoPerdida(rs.getInt("id"), rs.getString("nombre"), rs.getString("categoria"), rs.getInt("usos"));
    }

    public String[] fila() {
        return new String[]{String.valueOf(id), nombre, categoria, String.valueOf(usos)};
    }
}
