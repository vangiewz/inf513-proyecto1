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
 * Capa de persistencia: conexión JDBC a PostgreSQL (db.tecnoweb.org.bo:5432, db_grupo05sa, esquema
 * public) y consultas parametrizadas. Todo valor del usuario viaja como parámetro (PreparedStatement),
 * nunca concatenado.
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

  /**
   * Trabajo de varias sentencias que debe ser atómico. E es la excepción de negocio que puede
   * lanzar.
   */
  @FunctionalInterface
  public interface Trabajo<T, E extends Exception> {
    T en(Connection c) throws SQLException, E;
  }

  public Connection getConnection() throws SQLException {
    java.util.Properties props = new java.util.Properties();
    props.setProperty("user", usuario);
    props.setProperty("password", password);
    props.setProperty("connectTimeout", "10");
    props.setProperty("socketTimeout", "30");
    Connection c = DriverManager.getConnection(url, props);
    try {
      try (var ps = c.prepareStatement("SET TIME ZONE 'UTC'")) {
        ps.execute();
      }
      return c;
    } catch (SQLException e) {
      c.close();
      throw e;
    }
  }

  /** Las tablas del modelo compartido con la web (sql/esquema.sql). */
  public static final List<String> TABLAS =
      List.of(
          "api_tokens", "catalogo_cache", "comisiones", "comprobantes_pago", "configuraciones",
          "descuentos", "exportaciones", "inscripciones_comerciales", "interacciones", "intereses",
          "log_comandos", "motivos_perdida", "movimientos_interes", "notificaciones",
          "orden_pago_descuentos", "ordenes_pago", "pagos", "programas_responsables", "prospectos",
          "recordatorios", "roles", "solicitudes_compra", "usuarios");

  private static final String VALORES_TABLAS =
      "(VALUES " + String.join(",", TABLAS.stream().map(t -> "('" + t + "')").toList()) + ") v(t)";

  /**
   * Se comprueba antes de leer el buzón: si falta una tabla (borrada, otro esquema) no se retiran
   * mensajes, y el error nombra las tablas que faltan.
   */
  public void verificarModelo() throws SQLException {
    try (Connection c = getConnection()) {
      List<String> faltan =
          lista(c, rs -> rs.getString(1), "SELECT t FROM " + VALORES_TABLAS + " WHERE to_regclass(t) IS NULL");
      if (!faltan.isEmpty()) {
        String esquema = uno(c, rs -> rs.getString(1), "SELECT current_schema()");
        throw new SQLException(
            faltan.size() == TABLAS.size()
                ? "el esquema "
                    + (esquema == null ? "de currentSchema no existe" : "'" + esquema + "' no tiene ninguna tabla del CRM")
                    + ": revise currentSchema en db.url (debe ser public)"
                : "faltan tablas en el esquema '" + esquema + "' (¿las borraron?): " + String.join(", ", faltan),
            "42P01");
      }
    }
  }

  /** Para --diagnostico: versión, IP con la que nos ve el servidor, tablas y permisos. */
  public List<String> diagnostico() throws SQLException {
    verificarModelo();
    try (Connection c = getConnection()) {
      List<String> out = new ArrayList<>();
      out.add(uno(c, rs -> "PostgreSQL " + rs.getString(1), "SHOW server_version"));
      out.add(
          uno(
              c,
              rs -> "base " + rs.getString(1) + ", esquema " + rs.getString(2) + ", nos ve como " + rs.getString(3),
              "SELECT current_database(), current_schema(), host(inet_client_addr())"));
      List<String> sinPermiso =
          lista(
              c,
              rs -> rs.getString(1),
              "SELECT t FROM "
                  + VALORES_TABLAS
                  + " WHERE NOT has_table_privilege(t, 'SELECT,INSERT,UPDATE,DELETE')");
      out.add(
          sinPermiso.isEmpty()
              ? TABLAS.size() + " tablas con permisos completos"
              : "SIN PERMISOS en: " + String.join(", ", sinPermiso));
      if (!sinPermiso.isEmpty()) throw new SQLException(String.join(", ", out), "42501");
      return out;
    }
  }

  /**
   * Ejecuta el trabajo en una transacción: COMMIT si termina bien, ROLLBACK ante cualquier
   * excepción.
   */
  public <T, E extends Exception> T enTransaccion(Trabajo<T, E> trabajo) throws SQLException, E {
    try (Connection c = getConnection()) {
      c.setAutoCommit(false);
      uno(c, rs -> rs.getString(1), "SELECT set_config('crm.canal','correo',true)");
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

  public static <T> List<T> lista(Connection c, Mapeo<T> mapeo, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement ps = preparar(c, sql, params);
        ResultSet rs = ps.executeQuery()) {
      List<T> out = new ArrayList<>();
      while (rs.next()) out.add(mapeo.de(rs));
      return out;
    }
  }

  /** Primera fila o null. */
  public static <T> T uno(Connection c, Mapeo<T> mapeo, String sql, Object... params)
      throws SQLException {
    List<T> l = lista(c, mapeo, sql, params);
    return l.isEmpty() ? null : l.get(0);
  }

  /** Filas como texto, listas para una tabla de respuesta (reportes y listados). */
  public static List<String[]> filas(Connection c, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement ps = preparar(c, sql, params);
        ResultSet rs = ps.executeQuery()) {
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

  /** INSERT que devuelve el correlativo generado (nro_tarjeta, nro_orden...) de la columna clave. */
  public static int insertar(Connection c, String clave, String sql, Object... params)
      throws SQLException {
    return uno(c, rs -> rs.getInt(1), sql + " RETURNING " + clave, params);
  }

  public static String texto(Object v) {
    if (v == null) return "";
    if (v instanceof Timestamp t)
      return t.toLocalDateTime()
          .atZone(java.time.ZoneOffset.UTC)
          .withZoneSameInstant(java.time.ZoneId.of("America/La_Paz"))
          .format(FECHA);
    if (v instanceof BigDecimal b)
      return b.setScale(b.scale() > 0 ? Math.min(b.scale(), 2) : 0, RoundingMode.HALF_UP)
          .toPlainString();
    return v.toString();
  }

  private static PreparedStatement preparar(Connection c, String sql, Object... params)
      throws SQLException {
    PreparedStatement ps = c.prepareStatement(sql);
    for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    return ps;
  }
}
