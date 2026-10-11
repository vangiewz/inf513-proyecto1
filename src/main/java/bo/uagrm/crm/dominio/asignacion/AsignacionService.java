package bo.uagrm.crm.dominio.asignacion;

import bo.uagrm.crm.dominio.Alcance;
import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.embudo.EmbudoService;
import bo.uagrm.crm.dominio.embudo.Interes;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

/** CU3. Gestión de Asignar Prospectos (Leads): manual, automática Round-Robin (RF2.3) y bolsa común (RF1.2). */
public final class AsignacionService {

    /** Una tarjeta y el vendedor que la recibió (para avisarle por correo). */
    public record Asignacion(Interes tarjeta, Usuario vendedor) {
    }

    private final DatabaseService db;

    public AsignacionService(DatabaseService db) {
        this.db = db;
    }

    public List<Interes> bolsa(Usuario u) throws SQLException {
        try (Connection c = db.getConnection()) {
            return lista(c, Interes::de, Interes.SQL + " WHERE i.vendedor_id IS NULL AND i.estado NOT IN ('Convertido', 'Perdido') AND "
                    + Alcance.visible(u) + " ORDER BY i.creado_en, i.id", Alcance.params(u));
        }
    }

    public Asignacion asignar(Usuario u, int tarjetaId, int vendedorId) throws SQLException, CrmException {
        return db.enTransaccion(c -> {
            Interes t = EmbudoService.tarjeta(c, u, tarjetaId, true);
            if (t.cerrada()) throw new CrmException("La tarjeta #" + tarjetaId + " está en " + t.estado() + ": ya no se asigna.");
            Usuario v = uno(c, Usuario::de, Usuario.SQL + " WHERE id = ? AND rol = 'vendedor' AND estado = 'activo'", vendedorId);
            if (v == null) throw new CrmException("El usuario " + vendedorId + " no es un vendedor activo. Vea LISTARUSUARIOS[\"vendedor\"].");
            if (v.id() == (t.vendedorId() == null ? -1 : t.vendedorId()))
                throw new CrmException("La tarjeta #" + tarjetaId + " ya es de " + v.getNombreCompleto() + ".");
            Asignacion a = asignarA(c, u, t, v, "Asignada a " + v.getNombreCompleto());
            return a;
        });
    }

    /** Reparte la bolsa común en turno rotativo, empezando después del último vendedor que recibió una tarjeta. */
    public List<Asignacion> asignarAutomatico(Usuario u) throws SQLException, CrmException {
        return db.enTransaccion(c -> {
            List<Usuario> vendedores = lista(c, Usuario::de, Usuario.SQL + " WHERE rol = 'vendedor' AND estado = 'activo' ORDER BY id");
            if (vendedores.isEmpty()) throw new CrmException("No hay vendedores activos para repartir.");
            List<Interes> pendientes = lista(c, Interes::de, Interes.SQL + " WHERE i.vendedor_id IS NULL AND i.estado NOT IN ('Convertido', 'Perdido') AND "
                    + Alcance.visible(u) + " ORDER BY i.creado_en, i.id FOR UPDATE OF i", Alcance.params(u));
            if (pendientes.isEmpty()) throw new CrmException("La bolsa común está vacía: no hay prospectos sin vendedor.");
            Integer ultimo = uno(c, rs -> rs.getInt(1),
                    "SELECT vendedor_id FROM interes WHERE vendedor_id IS NOT NULL AND asignado_en IS NOT NULL ORDER BY asignado_en DESC, id DESC LIMIT 1");
            int turno = 0;
            if (ultimo != null)
                while (turno < vendedores.size() && vendedores.get(turno).id() <= ultimo) turno++;
            List<Asignacion> hechas = new ArrayList<>();
            for (Interes t : pendientes) {
                Usuario v = vendedores.get(turno++ % vendedores.size());
                hechas.add(asignarA(c, u, t, v, "Asignada por Round-Robin a " + v.getNombreCompleto()));
            }
            return hechas;
        });
    }

    /** El vendedor toma una tarjeta de la bolsa común para su cartera. */
    public Interes tomar(Usuario u, int tarjetaId) throws SQLException, CrmException {
        return db.enTransaccion(c -> {
            Interes t = EmbudoService.tarjeta(c, u, tarjetaId, false);
            if (t.vendedorId() != null) throw new CrmException("La tarjeta #" + tarjetaId + " ya está en su cartera.");
            if (t.cerrada()) throw new CrmException("La tarjeta #" + tarjetaId + " está en " + t.estado() + ".");
            // La condición vendedor_id IS NULL evita que dos vendedores tomen la misma tarjeta a la vez.
            if (actualizar(c, "UPDATE interes SET vendedor_id = ?, asignado_en = clock_timestamp() WHERE id = ? AND vendedor_id IS NULL",
                    u.id(), tarjetaId) == 0)
                throw new CrmException("Otro vendedor acaba de tomar la tarjeta #" + tarjetaId + ".");
            EmbudoService.registrarMovimiento(c, tarjetaId, u.id(), t.estado(), t.estado(), "Tomada de la bolsa común");
            return EmbudoService.tarjeta(c, u, tarjetaId, false);
        });
    }

    private static Asignacion asignarA(Connection c, Usuario actor, Interes t, Usuario v, String detalle) throws SQLException, CrmException {
        actualizar(c, "UPDATE interes SET vendedor_id = ?, asignado_en = clock_timestamp() WHERE id = ?", v.id(), t.id());
        EmbudoService.registrarMovimiento(c, t.id(), actor.id(), t.estado(), t.estado(), detalle);
        return new Asignacion(uno(c, Interes::de, Interes.SQL + " WHERE i.id = ?", t.id()), v);
    }
}
