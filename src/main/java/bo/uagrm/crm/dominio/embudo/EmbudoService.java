package bo.uagrm.crm.dominio.embudo;

import bo.uagrm.crm.dominio.Alcance;
import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

/** CU4. Gestión del Embudo de Ventas en Tablero Kanban. */
public final class EmbudoService {

    /** Columnas del tablero (RF3.2). */
    public static final List<String> ESTADOS =
            List.of("Nuevo", "En Contacto", "Seguimiento", "Promesa de Pago", "Convertido", "Perdido");

    private final DatabaseService db;

    public EmbudoService(DatabaseService db) {
        this.db = db;
    }

    /** Tarjetas del tablero: el vendedor ve su cartera; coordinador y administrador, su alcance. */
    public List<Interes> tablero(Usuario u) throws SQLException {
        try (Connection c = db.getConnection()) {
            return lista(c, Interes::de, Interes.SQL + " WHERE " + Alcance.propio(u) + " ORDER BY i.id", Alcance.params(u));
        }
    }

    /** Mueve la tarjeta entre las columnas abiertas. Perdido y Convertido tienen su propio comando. */
    public Interes mover(Usuario u, int id, String estado) throws SQLException, CrmException {
        String nuevo = Valida.elegir(estado, ESTADOS, "estado");
        if (nuevo.equals("Perdido"))
            throw new CrmException("Para marcar una tarjeta como Perdido use MARCARPERDIDO[\"" + id + "\",\"id_motivo\",\"detalle\"]: el motivo es obligatorio. Vea LISTARMOTIVOS[].");
        if (nuevo.equals("Convertido"))
            throw new CrmException("Para convertir use CONVERTIRPROSPECTO[\"" + id + "\"]: antes se validan los datos para la matrícula.");
        return db.enTransaccion(c -> {
            Interes t = tarjeta(c, u, id, true);
            if (t.estado().equals("Convertido")) throw new CrmException("La tarjeta #" + id + " ya fue convertida en matrícula.");
            if (t.estado().equals(nuevo)) throw new CrmException("La tarjeta #" + id + " ya está en " + nuevo + ".");
            actualizar(c, "UPDATE interes SET estado = ?, motivo_perdida_id = NULL, detalle_perdida = NULL WHERE id = ?", nuevo, id);
            registrarMovimiento(c, id, u.id(), t.estado(), nuevo,
                    t.estado().equals("Perdido") ? "Reactivada" : null);
            return tarjeta(c, u, id, false);
        });
    }

    /**
     * Carga una tarjeta y comprueba que el usuario puede verla (modificar = false) o trabajarla (modificar = true).
     * La usan CU3, CU5, CU6 y CU7.
     */
    public static Interes tarjeta(Connection c, Usuario u, int id, boolean modificar) throws SQLException, CrmException {
        // Al modificar se bloquea la fila (FOR UPDATE) hasta el fin de la transacción: dos usuarios no la cambian a la vez.
        Interes t = uno(c, Interes::de, Interes.SQL + " WHERE i.id = ?" + (modificar ? " FOR UPDATE OF i" : ""), id);
        if (t == null) throw new CrmException("No existe la tarjeta #" + id + ".");
        boolean puede = switch (u.rol()) {
            case ADMINISTRADOR -> true;
            case COORDINADOR -> Objects.equals(t.coordinadorId(), u.id());
            case VENDEDOR -> Objects.equals(t.vendedorId(), u.id()) || (!modificar && t.vendedorId() == null);
        };
        if (!puede) throw new CrmException(u.rol() == Rol.VENDEDOR
                ? "La tarjeta #" + id + " no está en su cartera." + (t.vendedorId() == null ? " Tómela con TOMARPROSPECTO[\"" + id + "\"]." : "")
                : "La tarjeta #" + id + " es de una oferta que no está a su cargo.");
        return t;
    }

    public static void registrarMovimiento(Connection c, int interesId, Integer usuarioId, String anterior, String nuevo,
                                           String detalle) throws SQLException {
        actualizar(c, "INSERT INTO movimiento (interes_id, usuario_id, estado_anterior, estado_nuevo, detalle) VALUES (?, ?, ?, ?, ?)",
                interesId, usuarioId, anterior, nuevo, detalle);
    }
}
