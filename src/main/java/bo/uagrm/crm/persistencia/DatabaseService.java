package bo.uagrm.crm.persistencia;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Capa de persistencia: conexión JDBC a PostgreSQL (db.tecnoweb.org.bo:5432, db_grupo05sa, esquema crm)
 * y consultas parametrizadas. Todo valor del usuario viaja como parámetro (PreparedStatement), nunca concatenado.
 */
public final class DatabaseService {

    @FunctionalInterface
    public interface Mapeo<T> {
        T de(ResultSet rs) throws SQLException;
    }

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final String url;
    private final String usuario;
    private final String password;

    public DatabaseService(String url, String usuario, String password) {
        this.url = url;
        this.usuario = usuario;
        this.password = password;
    }

    /** Trabajo de varias sentencias que debe ser atómico. E es la excepción de negocio que puede lanzar. */
    @FunctionalInterface
    public interface Trabajo<T, E extends Exception> {
        T en(Connection c) throws SQLException, E;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, usuario, password);
    }

    /** Ejecuta el trabajo en una transacción: COMMIT si termina bien, ROLLBACK ante cualquier excepción. */
    public <T, E extends Exception> T enTransaccion(Trabajo<T, E> trabajo) throws SQLException, E {
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                T resultado = trabajo.en(c);
                c.commit();
                return resultado;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public static <T> List<T> lista(Connection c, Mapeo<T> mapeo, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = preparar(c, sql, params); ResultSet rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) out.add(mapeo.de(rs));
            return out;
        }
    }

    /** Primera fila o null. */
    public static <T> T uno(Connection c, Mapeo<T> mapeo, String sql, Object... params) throws SQLException {
        List<T> l = lista(c, mapeo, sql, params);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Filas como texto, listas para una tabla de respuesta (reportes y listados). */
    public static List<String[]> filas(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = preparar(c, sql, params); ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            List<String[]> out = new ArrayList<>();
            while (rs.next()) {
                String[] f = new String[md.getColumnCount()];
                for (int i = 0; i < f.length; i++) f[i] = texto(rs.getObject(i + 1));
                out.add(f);
            }
            return out;
        }
    }

    public static int actualizar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = preparar(c, sql, params)) {
            return ps.executeUpdate();
        }
    }

    /** INSERT ... RETURNING id. */
    public static int insertar(Connection c, String sql, Object... params) throws SQLException {
        return uno(c, rs -> rs.getInt(1), sql + " RETURNING id", params);
    }

    public static String texto(Object v) {
        if (v == null) return "";
        if (v instanceof Timestamp t) return t.toLocalDateTime().format(FECHA);
        if (v instanceof BigDecimal b) return b.setScale(b.scale() > 0 ? Math.min(b.scale(), 2) : 0, RoundingMode.HALF_UP).toPlainString();
        return v.toString();
    }

    private static PreparedStatement preparar(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        return ps;
    }
}
