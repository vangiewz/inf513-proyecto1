package bo.uagrm.crm.dominio.seguimiento;

import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.dominio.embudo.EmbudoService;
import bo.uagrm.crm.dominio.embudo.Interes;
import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.filas;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

/** CU7. Gestión de Bitácora, Interacciones y Recordatorios (RF4.1, RF4.2, RF4.3). */
public final class SeguimientoService {

    public static final List<String> TIPOS = List.of("Llamada", "WhatsApp", "Correo", "Reunión", "Nota");
    public static final String[] COLUMNAS_HISTORIAL = {"Fecha", "Tipo", "Detalle", "Usuario"};
    public static final String[] COLUMNAS_BITACORA = {"Fecha", "Remitente", "Rol", "Comando", "Resultado", "Detalle"};
    private static final List<DateTimeFormatter> FORMATOS =
            List.of(DateTimeFormatter.ofPattern("uuuu-MM-dd H:mm"), DateTimeFormatter.ofPattern("d/M/uuuu H:mm"));

    /** Bitácora de una tarjeta: la tarjeta y sus eventos en orden cronológico. */
    public record Historial(Interes tarjeta, List<String[]> eventos) {
    }

    private final DatabaseService db;

    public SeguimientoService(DatabaseService db) {
        this.db = db;
    }

    public Interaccion registrarInteraccion(Usuario u, int tarjetaId, String tipo, String descripcion) throws SQLException, CrmException {
        String t = Valida.elegir(tipo, TIPOS, "tipo"), d = Valida.texto(descripcion, "descripción", 500);
        return db.enTransaccion(c -> {
            EmbudoService.tarjeta(c, u, tarjetaId, true);
            int id = DatabaseService.insertar(c, "INSERT INTO interaccion (interes_id, usuario_id, tipo, descripcion) VALUES (?, ?, ?, ?)",
                    tarjetaId, u.id(), t, d);
            return uno(c, Interaccion::de, Interaccion.SQL + " WHERE id = ?", id);
        });
    }

    public Recordatorio programarRecordatorio(Usuario u, int tarjetaId, String fechaHora, String descripcion) throws SQLException, CrmException {
        LocalDateTime cuando = fecha(fechaHora);
        if (!cuando.isAfter(LocalDateTime.now())) throw new CrmException("La fecha " + fechaHora + " ya pasó: indique una fecha futura.");
        String d = Valida.texto(descripcion, "descripción", 300);
        return db.enTransaccion(c -> {
            EmbudoService.tarjeta(c, u, tarjetaId, true);
            int id = DatabaseService.insertar(c, "INSERT INTO recordatorio (interes_id, usuario_id, fecha_hora, descripcion) VALUES (?, ?, ?, ?)",
                    tarjetaId, u.id(), cuando, d);
            return uno(c, Recordatorio::de, Recordatorio.SQL + " WHERE r.id = ?", id);
        });
    }

    /** Pendientes del usuario; el administrador ve los de todos. */
    public List<Recordatorio> listarRecordatorios(Usuario u) throws SQLException {
        try (Connection c = db.getConnection()) {
            return lista(c, Recordatorio::de, Recordatorio.SQL + " WHERE r.estado = 'Pendiente' AND (? OR r.usuario_id = ?) ORDER BY r.fecha_hora",
                    u.rol() == Rol.ADMINISTRADOR, u.id());
        }
    }

    public Recordatorio completarRecordatorio(Usuario u, int id) throws SQLException, CrmException {
        return db.enTransaccion(c -> {
            Recordatorio r = uno(c, Recordatorio::de, Recordatorio.SQL + " WHERE r.id = ?", id);
            if (r == null || (r.usuarioId() != u.id() && u.rol() != Rol.ADMINISTRADOR))
                throw new CrmException("No tiene un recordatorio con ID " + id + ". Vea LISTARRECORDATORIOS[].");
            if (!r.estado().equals("Pendiente")) throw new CrmException("El recordatorio " + id + " ya está " + r.estado() + ".");
            actualizar(c, "UPDATE recordatorio SET estado = 'Realizado' WHERE id = ?", id);
            return uno(c, Recordatorio::de, Recordatorio.SQL + " WHERE r.id = ?", id);
        });
    }

    public Historial historial(Usuario u, int tarjetaId) throws SQLException, CrmException {
        try (Connection c = db.getConnection()) {
            Interes t = EmbudoService.tarjeta(c, u, tarjetaId, false);
            List<String[]> eventos = filas(c, """
                    SELECT h.fecha, h.tipo, h.detalle, COALESCE(u.nombre || ' ' || u.apellido, 'El interesado (correo)')
                    FROM (
                        SELECT m.fecha, CASE WHEN m.estado_anterior IS DISTINCT FROM m.estado_nuevo THEN 'Movimiento' ELSE 'Asignación' END AS tipo,
                               CASE WHEN m.estado_anterior IS DISTINCT FROM m.estado_nuevo
                                    THEN COALESCE(m.estado_anterior || ' → ', '') || m.estado_nuevo || COALESCE('. ' || m.detalle, '')
                                    ELSE m.detalle END AS detalle,
                               m.usuario_id
                        FROM movimiento m WHERE m.interes_id = ?
                        UNION ALL
                        SELECT x.fecha, x.tipo, x.descripcion, x.usuario_id FROM interaccion x WHERE x.interes_id = ?
                        UNION ALL
                        SELECT r.fecha_hora, 'Recordatorio (' || r.estado || ')', r.descripcion, r.usuario_id FROM recordatorio r WHERE r.interes_id = ?
                    ) h LEFT JOIN usuario u ON u.id = h.usuario_id
                    ORDER BY h.fecha""", tarjetaId, tarjetaId, tarjetaId);
            return new Historial(t, eventos);
        }
    }

    /** Bitácora del sistema: cada correo procesado, con su resultado (Éxito, Error o Denegado). */
    public void registrarComando(String remitente, Integer usuarioId, String asunto, String resultado, String detalle) throws SQLException {
        try (Connection c = db.getConnection()) {
            actualizar(c, "INSERT INTO log_comando (remitente, usuario_id, asunto, resultado, detalle) VALUES (?, ?, ?, ?, ?)",
                    corto(remitente, 120), usuarioId, corto(asunto == null ? "(sin asunto)" : asunto, 300), resultado, corto(detalle, 300));
        }
    }

    public List<String[]> bitacora(int cantidad) throws SQLException {
        try (Connection c = db.getConnection()) {
            return filas(c, """
                    SELECT l.fecha, l.remitente, COALESCE(u.rol, 'público'), l.asunto, l.resultado, COALESCE(l.detalle, '')
                    FROM log_comando l LEFT JOIN usuario u ON u.id = l.usuario_id
                    ORDER BY l.id DESC LIMIT ?""", Math.min(cantidad, 200));
        }
    }

    /** Recordatorios que ya vencieron y aún no se avisaron por correo. */
    public List<Recordatorio> vencidos() throws SQLException {
        try (Connection c = db.getConnection()) {
            return lista(c, Recordatorio::de, Recordatorio.SQL
                    + " WHERE r.estado = 'Pendiente' AND NOT r.notificado AND r.fecha_hora <= now() ORDER BY r.fecha_hora");
        }
    }

    public void marcarNotificado(int id) throws SQLException {
        try (Connection c = db.getConnection()) {
            actualizar(c, "UPDATE recordatorio SET notificado = true WHERE id = ?", id);
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
        throw new CrmException("La fecha \"" + texto + "\" no es válida. Use AAAA-MM-DD HH:MM, por ejemplo 2026-10-15 10:00.");
    }
}
