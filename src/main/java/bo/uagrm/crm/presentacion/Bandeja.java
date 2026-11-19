package bo.uagrm.crm.presentacion;

import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Bandeja persistente del canal de correo, en la misma BD.
 *
 * <p>Entrada (log_comandos): cada correo se guarda con resultado 'Recibido' y su contenido ANTES de
 * borrarlo del buzón POP3; message_id es único, así que una relectura no lo duplica. Se procesa
 * desde aquí, y la operación, el cierre del registro y la respuesta en cola se confirman juntos.
 *
 * <p>Salida (correos_salientes): la respuesta ya armada (con su Message-ID fijo) se envía después y,
 * si falla, se reintenta con espera progresiva sin volver a ejecutar la operación.
 */
public final class Bandeja {

  /** Intentos de envío antes de dar una respuesta por fallida (≈ 5 h con la espera progresiva). */
  public static final int MAX_INTENTOS = 10;

  /** Un reintento del mismo comando de escritura dentro de este plazo no se vuelve a ejecutar. */
  public static final int MINUTOS_REINTENTO = 30;

  public record Entrante(long nro, String messageId, byte[] contenido, long bytes) {}

  public record Saliente(
      long nro, Long nroRegistro, String destinatario, String asunto, byte[] mensaje, int intentos) {}

  /** Correos guardados sin procesar, respuestas en cola y respuestas fallidas. */
  public record Estado(int recibidos, int pendientes, int fallidos) {
    @Override
    public String toString() {
      return recibidos
          + " correo(s) recibido(s) sin procesar, "
          + pendientes
          + " respuesta(s) en cola, "
          + fallidos
          + " fallida(s)";
    }
  }

  private final DatabaseService db;

  public Bandeja(DatabaseService db) {
    this.db = db;
  }

  /**
   * Guarda un correo recién descargado (se confirma de inmediato, antes del DELE). Devuelve su
   * número de registro, o null si ese message_id ya estaba guardado.
   */
  public Long guardar(String messageId, String remitente, String asunto, byte[] contenido, long bytes)
      throws SQLException {
    try (Connection c = db.getConnection()) {
      return uno(
          c,
          rs -> rs.getLong(1),
          "INSERT INTO log_comandos(remitente,asunto,resultado,detalle,message_id,contenido,bytes)"
              + " VALUES(?,?,'Recibido','Guardado; en espera de proceso',?,?,?)"
              + " ON CONFLICT (message_id) DO NOTHING RETURNING nro_registro",
          corto(remitente == null || remitente.isBlank() ? "(ilegible)" : remitente, 160),
          corto(asunto == null ? "(sin asunto)" : asunto, 300),
          messageId,
          contenido,
          (int) Math.min(bytes, Integer.MAX_VALUE));
    }
  }

  /** Registros 'Recibido' en orden de llegada. */
  public List<Long> porProcesar(int max) throws SQLException {
    try (Connection c = db.getConnection()) {
      return lista(
          c,
          rs -> rs.getLong(1),
          "SELECT nro_registro FROM log_comandos WHERE resultado='Recibido' ORDER BY nro_registro"
              + " LIMIT ?",
          max);
    }
  }

  /** Bloquea el registro para procesarlo; null si ya se procesó o lo tiene otra instancia. */
  public Entrante tomar(Connection c, long nro) throws SQLException {
    return uno(
        c,
        rs -> new Entrante(rs.getLong(1), rs.getString(2), rs.getBytes(3), rs.getLong(4)),
        "SELECT nro_registro,message_id,contenido,COALESCE(bytes,0) FROM log_comandos"
            + " WHERE nro_registro=? AND resultado='Recibido' FOR UPDATE SKIP LOCKED",
        nro);
  }

  /** Resultado final del correo; el contenido crudo deja de guardarse. */
  public void cerrar(Connection c, long nro, String usuarioCorreo, String resultado, String detalle)
      throws SQLException {
    DatabaseService.actualizar(
        c,
        "UPDATE log_comandos SET resultado=?,detalle=?,usuario_correo=?,"
            + "rol_registrado=(SELECT rol FROM usuarios WHERE correo=?),contenido=NULL,"
            + "procesado_en=now() WHERE nro_registro=?",
        resultado,
        corto(detalle, 300),
        usuarioCorreo,
        usuarioCorreo,
        nro);
  }

  /** Pone un correo armado en la cola de salida. Devuelve su número. */
  public long encolar(
      Connection c,
      Long nroRegistro,
      Long nroRecordatorio,
      String destinatario,
      String asunto,
      byte[] mensaje)
      throws SQLException {
    return uno(
        c,
        rs -> rs.getLong(1),
        "INSERT INTO correos_salientes(nro_registro,nro_recordatorio,destinatario,asunto,mensaje)"
            + " VALUES(?,?,?,?,?) RETURNING nro_correo",
        nroRegistro,
        nroRecordatorio,
        corto(destinatario, 160),
        corto(asunto == null ? "(sin asunto)" : asunto, 300),
        mensaje);
  }

  /**
   * Comando de escritura ya ejecutado con éxito, del mismo remitente y con el mismo asunto, que el
   * usuario repite porque no vio la respuesta: dentro de {@link #MINUTOS_REINTENTO} minutos, o en
   * cualquier momento de la última semana si su respuesta todavía no salió.
   */
  public Long repetido(Connection c, long nro, String remitente, String asunto)
      throws SQLException {
    return uno(
        c,
        rs -> rs.getLong(1),
        "SELECT l.nro_registro FROM log_comandos l WHERE l.nro_registro<? AND"
            + " lower(l.remitente)=lower(?) AND btrim(l.asunto)=btrim(?) AND l.resultado='Éxito'"
            + " AND l.fecha > now() - interval '7 days' AND (l.fecha > now() - make_interval(mins"
            + " => ?) OR NOT EXISTS (SELECT 1 FROM correos_salientes s WHERE"
            + " s.nro_registro=l.nro_registro AND s.estado='Enviado')) ORDER BY l.nro_registro DESC"
            + " LIMIT 1",
        nro,
        remitente,
        corto(asunto, 300),
        MINUTOS_REINTENTO);
  }

  /**
   * Vuelve a poner en cola la respuesta original de un comando repetido (mismo Message-ID), salvo
   * que aún esté pendiente. Devuelve el número del correo encolado o null.
   */
  public Long reenviar(Connection c, long previo, long nuevo, String destinatario)
      throws SQLException {
    return uno(
        c,
        rs -> rs.getLong(1),
        "INSERT INTO correos_salientes(nro_registro,destinatario,asunto,mensaje) SELECT"
            + " ?,s.destinatario,s.asunto,s.mensaje FROM correos_salientes s WHERE"
            + " s.nro_registro=? AND lower(s.destinatario)=lower(?) AND NOT EXISTS (SELECT 1 FROM"
            + " correos_salientes p WHERE p.nro_registro=? AND p.estado='Pendiente') ORDER BY"
            + " s.nro_correo LIMIT 1 RETURNING nro_correo",
        nuevo,
        previo,
        destinatario,
        previo);
  }

  /** Correos de la cola cuyo próximo intento ya llegó. */
  public List<Long> porEnviar(int max) throws SQLException {
    try (Connection c = db.getConnection()) {
      return lista(
          c,
          rs -> rs.getLong(1),
          // timestamp(0) redondea al segundo: un correo recién encolado puede quedar 0,5 s "en el
          // futuro"; el segundo de margen evita que espere un ciclo más.
          "SELECT nro_correo FROM correos_salientes WHERE estado='Pendiente' AND"
              + " proximo_intento<=now()+interval '1 second' ORDER BY nro_correo LIMIT ?",
          max);
    }
  }

  /** Bloquea un correo pendiente mientras se envía (otra instancia lo salta). */
  public Saliente tomarSaliente(Connection c, long nro) throws SQLException {
    return uno(
        c,
        rs ->
            new Saliente(
                rs.getLong(1),
                (Long) rs.getObject(2),
                rs.getString(3),
                rs.getString(4),
                rs.getBytes(5),
                rs.getInt(6)),
        "SELECT nro_correo,nro_registro,destinatario,asunto,mensaje,intentos FROM"
            + " correos_salientes WHERE nro_correo=? AND estado='Pendiente' FOR UPDATE SKIP LOCKED",
        nro);
  }

  public void enviado(Connection c, long nro) throws SQLException {
    DatabaseService.actualizar(
        c,
        "UPDATE correos_salientes SET estado='Enviado',intentos=intentos+1,enviado_en=now(),"
            + "ultimo_error=NULL WHERE nro_correo=?",
        nro);
  }

  /**
   * Registra un intento fallido. Devuelve los minutos hasta el próximo intento (1, 2, 4… hasta
   * 60), o null si el correo quedó 'Fallido' (rechazo definitivo o demasiados intentos).
   */
  public Integer fallo(Connection c, long nro, int intentos, String error, boolean definitivo)
      throws SQLException {
    int n = intentos + 1;
    if (definitivo || n >= MAX_INTENTOS) {
      DatabaseService.actualizar(
          c,
          "UPDATE correos_salientes SET estado='Fallido',intentos=?,ultimo_error=? WHERE"
              + " nro_correo=?",
          n,
          corto(error, 500),
          nro);
      return null;
    }
    int minutos = espera(n);
    DatabaseService.actualizar(
        c,
        "UPDATE correos_salientes SET intentos=?,ultimo_error=?,proximo_intento=now()+"
            + "make_interval(mins => ?) WHERE nro_correo=?",
        n,
        corto(error, 500),
        minutos,
        nro);
    return minutos;
  }

  /** Espera antes del intento n+1: 1, 2, 4, 8, 16, 32 y luego 60 minutos. */
  static int espera(int n) {
    return Math.min(60, 1 << Math.min(n - 1, 6));
  }

  /** Gmail aceptó la respuesta pero el servidor del destinatario la rechazó después (DSN). */
  public int rebote(String destinatario, String motivo) throws SQLException {
    try (Connection c = db.getConnection()) {
      return DatabaseService.actualizar(
          c,
          "UPDATE correos_salientes SET estado='Rebotado',ultimo_error=? WHERE nro_correo=(SELECT"
              + " max(nro_correo) FROM correos_salientes WHERE lower(destinatario)=lower(?) AND"
              + " estado='Enviado')",
          corto(motivo, 500),
          destinatario);
    }
  }

  public Estado estado() throws SQLException {
    try (Connection c = db.getConnection()) {
      return uno(
          c,
          rs -> new Estado(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
          "SELECT (SELECT count(*) FROM log_comandos WHERE resultado='Recibido'),"
              + "(SELECT count(*) FROM correos_salientes WHERE estado='Pendiente'),"
              + "(SELECT count(*) FROM correos_salientes WHERE estado='Fallido')");
    }
  }

  private static String corto(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
