package bo.uagrm.crm.dominio.perdidas;

import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.dominio.embudo.EmbudoService;
import bo.uagrm.crm.dominio.embudo.Interes;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

/** CU5. Gestión de Pérdida de Prospectos y Clasificación de Motivos (RF3.3). */
public final class PerdidaService {

    public static final List<String> CATEGORIAS = List.of("Económico", "Horario", "Incompatibilidad", "Contacto", "Otro");

    private final DatabaseService db;

    public PerdidaService(DatabaseService db) {
        this.db = db;
    }

    public List<MotivoPerdida> listarMotivos() throws SQLException {
        try (Connection c = db.getConnection()) {
            return lista(c, MotivoPerdida::de, MotivoPerdida.SQL + MotivoPerdida.AGRUPAR + " ORDER BY mp.categoria, mp.id");
        }
    }

    public MotivoPerdida insertarMotivo(String nombre, String categoria) throws SQLException, CrmException {
        String n = Valida.texto(nombre, "nombre del motivo", 80), cat = Valida.elegir(categoria, CATEGORIAS, "categoría");
        return db.enTransaccion(c -> {
            return porId(c, DatabaseService.insertar(c, "INSERT INTO motivo_perdida (nombre, categoria) VALUES (?, ?)", n, cat));
        });
    }

    public MotivoPerdida modificarMotivo(int id, String nombre, String categoria) throws SQLException, CrmException {
        String n = Valida.texto(nombre, "nombre del motivo", 80), cat = Valida.elegir(categoria, CATEGORIAS, "categoría");
        return db.enTransaccion(c -> {
            porId(c, id);
            actualizar(c, "UPDATE motivo_perdida SET nombre = ?, categoria = ? WHERE id = ?", n, cat, id);
            return porId(c, id);
        });
    }

    public MotivoPerdida eliminarMotivo(int id) throws SQLException, CrmException {
        return db.enTransaccion(c -> {
            MotivoPerdida m = porId(c, id);
            if (m.usos() > 0)
                throw new CrmException("El motivo \"" + m.nombre() + "\" clasifica " + m.usos() + " tarjeta(s): no se puede eliminar, solo modificar.");
            actualizar(c, "DELETE FROM motivo_perdida WHERE id = ?", id);
            return m;
        });
    }

    /** Pasa la tarjeta a Perdido con su motivo obligatorio. */
    public Interes marcarPerdido(Usuario u, int tarjetaId, int motivoId, String detalle) throws SQLException, CrmException {
        String d = Valida.opcional(detalle, "detalle", 200);
        return db.enTransaccion(c -> {
            Interes t = EmbudoService.tarjeta(c, u, tarjetaId, true);
            if (t.cerrada()) throw new CrmException("La tarjeta #" + tarjetaId + " ya está en " + t.estado() + ".");
            MotivoPerdida m = porId(c, motivoId);
            actualizar(c, "UPDATE interes SET estado = 'Perdido', motivo_perdida_id = ?, detalle_perdida = ? WHERE id = ?", m.id(), d, tarjetaId);
            EmbudoService.registrarMovimiento(c, tarjetaId, u.id(), t.estado(), "Perdido",
                    "Motivo: " + m.nombre() + " (" + m.categoria() + ")" + (d == null ? "" : ". " + d));
            return EmbudoService.tarjeta(c, u, tarjetaId, false);
        });
    }

    private static MotivoPerdida porId(Connection c, int id) throws SQLException, CrmException {
        MotivoPerdida m = uno(c, MotivoPerdida::de, MotivoPerdida.SQL + " WHERE mp.id = ?" + MotivoPerdida.AGRUPAR, id);
        if (m == null) throw new CrmException("No existe el motivo " + id + ". Vea LISTARMOTIVOS[].");
        return m;
    }
}
