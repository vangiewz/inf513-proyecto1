package bo.uagrm.crm.dominio.seguimiento;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.filas;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.dominio.embudo.EmbudoService;
import bo.uagrm.crm.dominio.embudo.Interes;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/** CU7. Gestión de Bitácora, Interacciones y Recordatorios (RF4.1, RF4.2, RF4.3). */
public final class SeguimientoService {

  public static final List<String> TIPOS =
      List.of("Llamada", "WhatsApp", "Correo electronico", "Reunion", "Nota comercial");
  public static final String[] COLUMNAS_HISTORIAL = {"Fecha", "Tipo", "Detalle", "Usuario"};
  public static final String[] COLUMNAS_BITACORA = {
    "Fecha", "Remitente", "Rol", "Comando", "Resultado", "Detalle"
  };
  private static final List<DateTimeFormatter> FORMATOS =
      List.of(
          DateTimeFormatter.ofPattern("uuuu-MM-dd H:mm")
              .withResolverStyle(java.time.format.ResolverStyle.STRICT),
          DateTimeFormatter.ofPattern("d/M/uuuu H:mm")
              .withResolverStyle(java.time.format.ResolverStyle.STRICT));

  /** Bitácora de una tarjeta: la tarjeta y sus eventos en orden cronológico. */
  public record Historial(Interes tarjeta, List<String[]> eventos) {}

  private final DatabaseService db;

  public SeguimientoService(DatabaseService db) {
    this.db = db;
  }

  public Interaccion registrarInteraccion(Usuario u, int nroTarjeta, String tipo, String descripcion)
      throws SQLException, CrmException {
    String entrada =
        switch (Valida.normal(tipo == null ? "" : tipo)) {
          case "correo" -> "Correo electronico";
          case "reunion" -> "Reunion";
          case "nota" -> "Nota comercial";
          default -> tipo;
        };
    String t = Valida.elegir(entrada, TIPOS, "tipo"),
        d = Valida.texto(descripcion, "descripción", 500);
    return db.enTransaccion(
        c -> {
          EmbudoService.tarjeta(c, u, nroTarjeta, true);
          int nro =
              DatabaseService.insertar(
                  c,
                  "nro_interaccion",
                  "INSERT INTO interacciones (nro_tarjeta, usuario_correo, tipo, descripcion,"
                      + " created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                  nroTarjeta,
                  u.correo(),
                  t,
                  d);
          actualizar(
              c,
              "UPDATE intereses SET estado=CASE WHEN estado='Nuevo' THEN 'Contactado' ELSE estado"
                  + " END, ultima_actividad_en=now(),updated_at=now() WHERE nro_tarjeta=?",
              nroTarjeta);
          return uno(c, Interaccion::de, Interaccion.SQL + " WHERE nro_interaccion = ?", nro);
        });
  }

  public Recordatorio programarRecordatorio(
      Usuario u, int nroTarjeta, String fechaHora, String descripcion)
      throws SQLException, CrmException {
    LocalDateTime cuando = fecha(fechaHora);
    if (!cuando.isAfter(LocalDateTime.now(java.time.ZoneId.of("America/La_Paz"))))
      throw new CrmException("La fecha " + fechaHora + " ya pasó: indique una fecha futura.");
    String d = Valida.texto(descripcion, "descripción", 300);
    return db.enTransaccion(
        c -> {
          Interes tarjeta = EmbudoService.tarjeta(c, u, nroTarjeta, true);
          int nro =
              DatabaseService.insertar(
                  c,
                  "nro_recordatorio",
                  "INSERT INTO recordatorios (nro_tarjeta, usuario_correo, fecha_programada,"
                      + " descripcion, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                  nroTarjeta,
                  tarjeta.vendedorCorreo() == null ? u.correo() : tarjeta.vendedorCorreo(),
                  cuando
                      .atZone(java.time.ZoneId.of("America/La_Paz"))
                      .withZoneSameInstant(java.time.ZoneOffset.UTC)
                      .toLocalDateTime(),
                  d);
          actualizar(
              c,
              "UPDATE intereses SET proximo_contacto=(SELECT fecha_programada FROM recordatorios"
                  + " WHERE nro_recordatorio=?),updated_at=now() WHERE nro_tarjeta=?",
              nro,
              nroTarjeta);
          return uno(c, Recordatorio::de, Recordatorio.SQL + " WHERE r.nro_recordatorio = ?", nro);
        });
  }

  /** Pendientes del usuario; el administrador ve los de todos. */
  public List<Recordatorio> listarRecordatorios(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return lista(
          c,
          Recordatorio::de,
          Recordatorio.SQL
              + " WHERE r.estado IN ('Pendiente','Atrasada') AND "
              + bo.uagrm.crm.dominio.Alcance.propio(u)
              + " ORDER BY r.fecha_programada",
          bo.uagrm.crm.dominio.Alcance.params(u));
    }
  }

  public Recordatorio completarRecordatorio(Usuario u, int nro, String resultado)
      throws SQLException, CrmException {
    String nota = Valida.texto(resultado, "resultado", 2000);
    return db.enTransaccion(
        c -> {
          Recordatorio r = uno(c, Recordatorio::de, Recordatorio.SQL + " WHERE r.nro_recordatorio=?", nro);
          if (r == null) throw new CrmException("No existe el recordatorio.");
          EmbudoService.tarjeta(c, u, r.nroTarjeta(), true);
          r = uno(c, Recordatorio::de, Recordatorio.SQL + " WHERE r.nro_recordatorio=? FOR UPDATE OF r", nro);
          if (!List.of("Pendiente", "Atrasada").contains(r.estado()))
            throw new CrmException("La actividad ya fue realizada o cancelada.");
          actualizar(
              c,
              "UPDATE recordatorios SET estado='Realizada',fecha_realizada=now(),updated_at=now()"
                  + " WHERE nro_recordatorio=?",
              nro);
          actualizar(
              c,
              """
INSERT INTO interacciones(nro_tarjeta,usuario_correo,tipo,descripcion,resultado,fecha,created_at,updated_at)
VALUES(?,?,'Nota comercial',?,?,now(),now(),now())
""",
              r.nroTarjeta(),
              u.correo(),
              "Actividad agendada: " + r.descripcion(),
              nota);
          actualizar(
              c,
              "UPDATE intereses SET ultima_actividad_en=now(),updated_at=now() WHERE nro_tarjeta=?",
              r.nroTarjeta());
          return uno(c, Recordatorio::de, Recordatorio.SQL + " WHERE r.nro_recordatorio=?", nro);
        });
  }

  public Historial historial(Usuario u, int nroTarjeta) throws SQLException, CrmException {
    try (Connection c = db.getConnection()) {
      Interes t = EmbudoService.tarjeta(c, u, nroTarjeta, false);
      List<String[]> eventos =
          filas(
              c,
              """
SELECT h.fecha, h.tipo, h.detalle, COALESCE(u.nombre || ' ' || u.apellido, 'Sistema / interesado')
FROM (
    SELECT m.fecha, CASE WHEN m.estado_anterior IS DISTINCT FROM m.estado_nuevo THEN 'Movimiento' ELSE 'Asignación' END AS tipo,
           CASE WHEN m.estado_anterior IS DISTINCT FROM m.estado_nuevo
                THEN COALESCE(m.estado_anterior || ' → ', '') || m.estado_nuevo || COALESCE('. ' || m.detalle, '')
                ELSE m.detalle END AS detalle,
           m.usuario_correo
    FROM movimientos_interes m WHERE m.nro_tarjeta = ?
    UNION ALL
    SELECT x.fecha, x.tipo, x.descripcion || COALESCE('. Resultado: ' || x.resultado,''), x.usuario_correo FROM interacciones x WHERE x.nro_tarjeta = ?
    UNION ALL
    SELECT r.fecha_programada, 'Recordatorio (' || r.estado || ')', r.descripcion, r.usuario_correo FROM recordatorios r WHERE r.nro_tarjeta = ?
) h LEFT JOIN usuarios u ON u.correo = h.usuario_correo
ORDER BY h.fecha""",
              nroTarjeta,
              nroTarjeta,
              nroTarjeta);
      return new Historial(t, eventos);
    }
  }

  /** Bitácora del sistema: cada correo procesado, con su resultado (Éxito, Error o Denegado). */
  public void registrarComando(
      String remitente, String usuarioCorreo, String asunto, String resultado, String detalle)
      throws SQLException {
    try (Connection c = db.getConnection()) {
      actualizar(
          c,
          "INSERT INTO log_comandos(remitente,usuario_correo,asunto,resultado,detalle,rol_registrado)"
              + " VALUES(?,?,?,?,?,(SELECT rol FROM usuarios WHERE correo=?))",
          corto(remitente, 160),
          usuarioCorreo,
          corto(asunto == null ? "(sin asunto)" : asunto, 300),
          resultado,
          corto(detalle, 300),
          usuarioCorreo);
    }
  }

  public List<String[]> bitacora(int cantidad) throws SQLException {
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          """
SELECT l.fecha, l.remitente, COALESCE(l.rol_registrado,u.rol, 'público'), l.asunto, l.resultado, COALESCE(l.detalle, '')
FROM log_comandos l LEFT JOIN usuarios u ON u.correo = l.usuario_correo
ORDER BY l.nro_registro DESC LIMIT ?""",
          Math.min(cantidad, 200));
    }
  }

  /** Recordatorios que ya vencieron y aún no se avisaron por correo. */
  public List<Recordatorio> vencidos() throws SQLException {
    try (Connection c = db.getConnection()) {
      return lista(
          c,
          Recordatorio::de,
          Recordatorio.SQL
              + " WHERE r.estado IN ('Pendiente','Atrasada') AND u.estado='activo' AND (u.rol <>"
              + " 'vendedor' OR i.vendedor_correo=u.correo) AND NOT r.notificado AND r.fecha_programada <="
              + " now() AND (u.rol <> 'coordinador' OR EXISTS (SELECT 1 FROM catalogo_cache ac JOIN"
              + " programas_responsables pr ON pr.programa_codigo=ac.programa_codigo WHERE"
              + " ac.codigo=i.modulo_codigo AND pr.coordinador_correo=u.correo)) ORDER BY"
              + " r.fecha_programada");
    }
  }

  public void marcarNotificado(int nro) throws SQLException {
    try (Connection c = db.getConnection()) {
      actualizar(c, "UPDATE recordatorios SET notificado = true WHERE nro_recordatorio = ?", nro);
    }
  }

  private static String corto(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  private static LocalDateTime fecha(String texto) throws CrmException {
    String t = texto == null ? "" : texto.trim();
    for (DateTimeFormatter f : FORMATOS) {
      try {
        return LocalDateTime.parse(t, f);
      } catch (DateTimeParseException ignored) {
        // se prueba el siguiente formato
      }
    }
    throw new CrmException(
        "La fecha \""
            + texto
            + "\" no es válida. Use AAAA-MM-DD HH:MM, por ejemplo 2026-10-15 10:00.");
  }
}
