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
  private final java.util.function.LongSupplier reloj;
  private volatile long reconectarDespues;
  private volatile SQLException ultimaConexionFallida;

  public DatabaseService(String url, String usuario, String password) {
    this(url, usuario, password, System::nanoTime);
  }

  DatabaseService(String url, String usuario, String password, java.util.function.LongSupplier reloj) {
    this.url = url;
    this.usuario = usuario;
    this.password = password;
    this.reloj = reloj;
  }

  /**
   * Trabajo de varias sentencias que debe ser atómico. E es la excepción de negocio que puede
   * lanzar.
   */
  @FunctionalInterface
  public interface Trabajo<T, E extends Exception> {
    T en(Connection c) throws SQLException, E;
  }

  /**
   * Transacción en curso en este hilo. Mientras existe, los servicios trabajan dentro de ella: así
   * la operación de negocio, su bitácora y su respuesta en cola se confirman en un solo COMMIT.
   */
  private final ThreadLocal<Connection> enCurso = new ThreadLocal<>();

  /**
   * Conexión nueva o, dentro de una transacción en curso, esa misma conexión (su close() no la
   * cierra: la cierra quien abrió la transacción).
   */
  public Connection getConnection() throws SQLException {
    Connection actual = enCurso.get();
    return actual != null ? sinCerrar(actual) : abrir();
  }

  private static Connection sinCerrar(Connection c) {
    return (Connection)
        java.lang.reflect.Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, m, args) -> {
              if (m.getName().equals("close")) return null;
              try {
                return m.invoke(c, args);
              } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private synchronized Connection abrir() throws SQLException {
    if (ultimaConexionFallida != null && bo.uagrm.crm.Config.institucional(url) && reloj.getAsLong() < reconectarDespues) {
      throw new SQLException("Reconexión diferida para respetar el intervalo institucional",
          ultimaConexionFallida.getSQLState(), ultimaConexionFallida);
    }
    java.util.Properties props = new java.util.Properties();
    props.setProperty("user", usuario);
    props.setProperty("password", password);
    props.setProperty("connectTimeout", "10");
    props.setProperty("socketTimeout", "30");
    Connection c;
    try {
      c = DriverManager.getConnection(url, props);
      ultimaConexionFallida = null;
      reconectarDespues = 0;
    } catch (SQLException e) {
      if (bo.uagrm.crm.Config.institucional(url)) {
        ultimaConexionFallida = e;
        reconectarDespues = reloj.getAsLong() + (tipoFallo(e) == TipoFallo.TEMPORAL ? 3000L : 60000L) * 1_000_000L;
      }
      throw e;
    }
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
          "correos_salientes",
          "descuentos", "exportaciones", "inscripciones_comerciales", "interacciones", "intereses",
          "log_comandos", "motivos_perdida", "movimientos_interes", "notificaciones",
          "orden_pago_descuentos", "ordenes_pago", "pagos", "persona", "programas", "programas_responsables", "prospectos",
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
                : "faltan tablas en el esquema '" + esquema + "' (¿las borraron?): " + String.join(", ", faltan)
                    + (faltan.equals(List.of("correos_salientes"))
                        ? " (falta aplicar sql/migraciones/2026-11-19_bandeja_correo.sql)"
                        : "")
                    + (faltan.contains("programas")
                        ? " (falta aplicar sql/migraciones/2026-11-19_normalizar_catalogo.sql)"
                        : "")
                    + (faltan.contains("persona")
                        ? " (falta aplicar sql/migraciones/2026-11-19_persona.sql)"
                        : ""),
            "42P01");
      }
      if (uno(
              c,
              rs -> 1,
              "SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()"
                  + " AND table_name='log_comandos' AND column_name='message_id'")
          == null)
        throw new SQLException(
            "falta la columna log_comandos.message_id: aplique"
                + " sql/migraciones/2026-11-19_bandeja_correo.sql",
            "42703");
      boolean nombreRepetido = uno(c, rs -> 1,
          "SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()"
              + " AND table_name='catalogo_cache' AND column_name='programa_nombre'") != null;
      Long relacionesPrograma = uno(c, rs -> rs.getLong(1),
          "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace"
              + " WHERE n.nspname=current_schema() AND c.contype='f' AND c.conname IN"
              + " ('catalogo_cache_programa_codigo_fk','programas_responsables_programa_codigo_fk')");
      if (nombreRepetido || relacionesPrograma != 2L)
        throw new SQLException("el catálogo no está normalizado: aplique"
            + " sql/migraciones/2026-11-19_normalizar_catalogo.sql antes de leer el buzón", "55000");
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
   * excepción. Si ya hay una transacción en curso en este hilo, el trabajo se une a ella con un
   * SAVEPOINT: si falla, solo se deshace su parte y la excepción sigue hacia arriba.
   */
  public <T, E extends Exception> T enTransaccion(Trabajo<T, E> trabajo) throws SQLException, E {
    Connection actual = enCurso.get();
    if (actual != null) {
      java.sql.Savepoint sp = actual.setSavepoint();
      try {
        T resultado = trabajo.en(sinCerrar(actual));
        actual.releaseSavepoint(sp);
        return resultado;
      } catch (Exception e) {
        try {
          actual.rollback(sp);
        } catch (SQLException r) {
          e.addSuppressed(r);
        }
        throw e;
      }
    }
    try (Connection c = abrir()) {
      c.setAutoCommit(false);
      uno(c, rs -> rs.getString(1), "SELECT set_config('crm.canal','correo',true)");
      enCurso.set(c);
      try {
        T resultado = trabajo.en(sinCerrar(c));
        c.commit();
        return resultado;
      } catch (Exception e) {
        try {
          c.rollback();
        } catch (SQLException r) {
          e.addSuppressed(r);
        }
        throw e;
      } finally {
        enCurso.remove();
      }
    }
  }

  /**
   * Para el trabajo que debe sobrevivir a una falla parcial dentro de la transacción en curso:
   * corre en un SAVEPOINT y, si dejó la transacción abortada (sentencia fallida fuera de un
   * enTransaccion), vuelve a ese punto para que el resto (bitácora, cola) pueda confirmarse.
   */
  public <T> T aislado(java.util.function.Supplier<T> trabajo) throws SQLException {
    Connection c = enCurso.get();
    if (c == null) return trabajo.get();
    java.sql.Savepoint sp = c.setSavepoint();
    T resultado = trabajo.get();
    try (var ps = c.prepareStatement("SELECT 1")) {
      ps.execute();
      c.releaseSavepoint(sp);
    } catch (SQLException abortada) {
      c.rollback(sp);
    }
    return resultado;
  }

  /** ¿La falla es de conexión (BD caída, red cortada) y conviene reintentar más tarde? */
  public static boolean esDeConexion(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof java.io.IOException) return true;
      if (t instanceof SQLException s && s.getSQLState() != null && s.getSQLState().startsWith("08"))
        return true;
      if (t instanceof SQLException s
          && s.getSQLState() != null
          && (s.getSQLState().startsWith("57P") || s.getSQLState().equals("53300"))) return true;
    }
    return false;
  }

  /** Distingue recuperación temporal de errores que necesitan intervención de configuración/esquema. */
  public enum TipoFallo { TEMPORAL, CREDENCIALES, ESQUEMA, SQL_PERMANENTE }

  public static TipoFallo tipoFallo(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException s && s.getSQLState() != null) {
        String estado = s.getSQLState();
        if (estado.startsWith("28") || estado.equals("42501")) return TipoFallo.CREDENCIALES;
        if (estado.startsWith("42") || estado.startsWith("3D") || estado.startsWith("3F")
            || estado.equals("55000")) return TipoFallo.ESQUEMA;
      }
    }
    return esDeConexion(e) ? TipoFallo.TEMPORAL : TipoFallo.SQL_PERMANENTE;
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
