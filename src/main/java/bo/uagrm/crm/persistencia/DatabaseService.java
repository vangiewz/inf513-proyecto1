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
  private volatile com.zaxxer.hikari.HikariDataSource pool;
  private static final ThreadLocal<SQLException> FALLO = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> CORREO = ThreadLocal.withInitial(() -> false);
  private static final ThreadLocal<Boolean> ESCRITURA = ThreadLocal.withInitial(() -> false);
  public static void comenzarCorreo() { FALLO.remove(); ESCRITURA.remove(); CORREO.set(true); }
  public static void terminarCorreo() { FALLO.remove(); ESCRITURA.remove(); CORREO.remove(); }
  public static SQLException falloCorreo() { return FALLO.get(); }
  public static boolean sinEscrituras() { return !ESCRITURA.get(); }
  public static void registrarFallo(SQLException e) {
    if (!CORREO.get()) return;
    String estado = e.getSQLState();
    if (estado == null || estado.startsWith("08") || estado.matches("57P0[123]") || e instanceof java.sql.SQLTimeoutException
        || e instanceof java.sql.SQLTransientConnectionException) FALLO.set(e);
  }

  /** Sin secretos; conservar wrapper Hikari, causas JDBC, SQLState y espera del pool. */
  public String estadoPool() {
    var p = pool;
    if (p == null) return "pool=no-iniciado";
    var b = p.getHikariPoolMXBean();
    return "pool[activas=" + b.getActiveConnections() + ", libres=" + b.getIdleConnections()
        + ", total=" + b.getTotalConnections() + ", esperando=" + b.getThreadsAwaitingConnection()
        + ", maxLifetime_ms=" + p.getMaxLifetime() + "]";
  }

  private synchronized com.zaxxer.hikari.HikariDataSource pool() {
    if (pool == null) {
      var p = new com.zaxxer.hikari.HikariConfig();
      p.setJdbcUrl(url); p.setUsername(usuario); p.setPassword(password);
      p.setMaximumPoolSize(1); p.setMinimumIdle(0);
      p.setConnectionTimeout(15000); p.setValidationTimeout(5000);
      p.setIdleTimeout(120000); p.setMaxLifetime(600000);
      p.setInitializationFailTimeout(-1);
      p.setConnectionInitSql("SET TIME ZONE 'UTC'");
      p.addDataSourceProperty("connectTimeout", "10");
      p.addDataSourceProperty("socketTimeout", "30");
      pool = new com.zaxxer.hikari.HikariDataSource(p);
      var recurso = pool;
      Runtime.getRuntime().addShutdownHook(new Thread(recurso::close, "crm-jdbc-cierre"));
    }
    return pool;
  }

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
    long conexionInicio = System.nanoTime();
    if (!Boolean.getBoolean("crm.jdbc.sinPool")) {
      try { return pool().getConnection(); }
      catch (SQLException e) {
        registrarFallo(e);
        java.util.logging.Logger.getLogger("crm").log(java.util.logging.Level.WARNING,
            "JDBC SQLState=" + e.getSQLState() + " codigo=" + e.getErrorCode() + " "
                + estadoPool() + " espera_ms=" + (System.nanoTime() - conexionInicio) / 1_000_000, e);
        throw e;
      }
      finally { bo.uagrm.crm.Rendimiento.sumar("conexion", conexionInicio); }
    }
    java.util.Properties props = new java.util.Properties();
    props.setProperty("user", usuario);
    props.setProperty("password", password);
    props.setProperty("connectTimeout", "10");
    props.setProperty("socketTimeout", "30");
    Connection c;
    try { c = DriverManager.getConnection(url, props); }
    catch (SQLException e) { registrarFallo(e); throw e; }
    try {
      try (var ps = c.prepareStatement("SET TIME ZONE 'UTC'")) {
        ps.execute();
      }
      bo.uagrm.crm.Rendimiento.sumar("conexion", conexionInicio);
      return c;
    } catch (SQLException e) {
      registrarFallo(e);
      c.close();
      throw e;
    }
  }

  /**
   * Fecha de hoy en Bolivia, para la vigencia de las credenciales. La sesión está en UTC: CURRENT_DATE
   * sería la fecha UTC y entre las 20:00 y las 24:00 discreparía con la que usa Java (America/La_Paz).
   */
  public static final String HOY = "(now() AT TIME ZONE 'America/La_Paz')::date";

  /** Las tablas del modelo compartido con la web (sql/esquema.sql). */
  public static final List<String> TABLAS =
      List.of(
          "api_tokens", "catalogo_cache", "comisiones", "comprobantes_pago", "configuraciones",
          "descuentos", "exportaciones", "inscripciones_comerciales", "interacciones", "intereses",
          "log_comandos", "motivos_perdida", "movimientos_interes", "notificaciones",
          "orden_pago_descuentos", "ordenes_pago", "pagos", "programas", "programas_responsables", "prospectos",
          "recordatorios", "roles", "solicitudes_compra", "usuarios");

  private static final String VALORES_TABLAS =
      "(VALUES " + String.join(",", TABLAS.stream().map(t -> "('" + t + "')").toList()) + ") v(t)";

  /**
   * Comprueba modelo y permisos, sin mutaciones. El monitor lo ejecuta fuera del hilo POP3.
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
                : "faltan tablas en el esquema '" + esquema + "' (¿las borraron?): " + String.join(", ", faltan)
                    + (faltan.contains("programas")
                        ? ". Aplique sql/migraciones/2026-11-19_normalizar_catalogo.sql"
                        : ""),
            "42P01");
      }
      // Un REVOKE haría fallar cada comando después de retirar el correo: se detecta antes.
      List<String> sinPermiso =
          lista(
              c,
              rs -> rs.getString(1),
              SIN_PERMISO);
      if (!sinPermiso.isEmpty())
        throw new SQLException("permiso denegado sobre: " + String.join(", ", sinPermiso), "42501");
    }
  }

  /**
   * Tablas o secuencias del modelo sin TODOS los permisos que usa el lector. has_table_privilege con
   * una lista separada por comas es verdadero si se tiene CUALQUIERA: por eso se pregunta uno por uno.
   */
  private static final String SIN_PERMISO =
      "SELECT t FROM "
          + VALORES_TABLAS
          + " WHERE NOT (has_table_privilege(t,'SELECT') AND has_table_privilege(t,'INSERT')"
          + " AND has_table_privilege(t,'UPDATE') AND has_table_privilege(t,'DELETE'))"
          + " UNION ALL SELECT s.schemaname || '.' || s.sequencename FROM pg_sequences s"
          + " WHERE s.schemaname = current_schema()"
          + " AND NOT has_sequence_privilege(s.schemaname || '.' || s.sequencename, 'USAGE')";

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
              SIN_PERMISO);
      out.add(
          sinPermiso.isEmpty()
              ? TABLAS.size() + " tablas y sus secuencias con permisos completos"
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
        if (e instanceof SQLException s) registrarFallo(s);
        try {
          c.rollback();
        } catch (SQLException r) { // conexión ya muerta: no tapar la causa real con el 08003 del rollback
          e.addSuppressed(r);
        }
        throw e;
      }
    }
  }

  public static <T> List<T> lista(Connection c, Mapeo<T> mapeo, String sql, Object... params)
      throws SQLException {
    long inicio = System.nanoTime();
    try (PreparedStatement ps = preparar(c, sql, params);
        ResultSet rs = ps.executeQuery()) {
      List<T> out = new ArrayList<>();
      while (rs.next()) out.add(mapeo.de(rs));
      return out;
    } catch (SQLException e) {
      registrarFallo(e); throw e;
    } finally {
      bo.uagrm.crm.Rendimiento.sumar("bd", inicio);
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
    long inicio = System.nanoTime();
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
    } catch (SQLException e) {
      registrarFallo(e); throw e;
    } finally {
      bo.uagrm.crm.Rendimiento.sumar("bd", inicio);
    }
  }

  public static int actualizar(Connection c, String sql, Object... params) throws SQLException {
    long inicio = System.nanoTime();
    ESCRITURA.set(true); // antes de ejecutar: una desconexión puede dejar el resultado incierto
    try (PreparedStatement ps = preparar(c, sql, params)) {
      return ps.executeUpdate();
    } catch (SQLException e) {
      registrarFallo(e); throw e;
    } finally {
      bo.uagrm.crm.Rendimiento.sumar("bd", inicio);
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
    if (!sql.stripLeading().matches("(?is)^(SELECT|SHOW)\\b.*")) ESCRITURA.set(true);
    PreparedStatement ps = c.prepareStatement(sql);
    for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    return ps;
  }
}
