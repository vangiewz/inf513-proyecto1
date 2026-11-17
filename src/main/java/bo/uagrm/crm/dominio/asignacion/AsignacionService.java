package bo.uagrm.crm.dominio.asignacion;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

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

/**
 * CU3. Gestión de Asignar Prospectos (Leads): manual, automática Round-Robin (RF2.3) y bolsa común
 * (RF1.2).
 */
public final class AsignacionService {

  /** Una tarjeta y el vendedor que la recibió (para avisarle por correo). */
  public record Asignacion(Interes tarjeta, Usuario vendedor) {}

  private final DatabaseService db;

  public AsignacionService(DatabaseService db) {
    this.db = db;
  }

  public List<Interes> bolsa(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return lista(
          c,
          Interes::de,
          Interes.SQL
              + " WHERE i.vendedor_id IS NULL AND i.estado NOT IN ('Convertido', 'Perdido') AND "
              + Alcance.visible(u)
              + " ORDER BY i.created_at, i.id",
          Alcance.params(u));
    }
  }

  public Asignacion asignar(Usuario u, int tarjetaId, int vendedorId)
      throws SQLException, CrmException {
    return db.enTransaccion(
        c -> {
          if (u.rol() == bo.uagrm.crm.dominio.usuarios.Rol.VENDEDOR)
            throw new CrmException("Solo coordinacion o administracion pueden asignar.");
          uno(c, rs -> 1, "SELECT pg_advisory_xact_lock(51305)");
          Interes t = EmbudoService.tarjeta(c, u, tarjetaId, true);
          if (t.cerrada())
            throw new CrmException(
                "La tarjeta #" + tarjetaId + " está en " + t.estado() + ": ya no se asigna.");
          Usuario v =
              uno(
                  c,
                  Usuario::de,
                  Usuario.SQL + " WHERE id = ? AND rol = 'vendedor' AND estado = 'activo'",
                  vendedorId);
          if (v == null)
            throw new CrmException(
                "El usuario "
                    + vendedorId
                    + " no es un vendedor activo. Vea LISTARUSUARIOS[\"vendedor\"].");
          if (v.id() == (t.vendedorId() == null ? -1 : t.vendedorId()))
            throw new CrmException(
                "La tarjeta #" + tarjetaId + " ya es de " + v.getNombreCompleto() + ".");
          Asignacion a = asignarA(c, u, t, v, "Asignada a " + v.getNombreCompleto());
          return a;
        });
  }

  /**
   * Reparte la bolsa común en turno rotativo, empezando después del último vendedor que recibió una
   * tarjeta.
   */
  public List<Asignacion> asignarAutomatico(Usuario u) throws SQLException, CrmException {
    return db.enTransaccion(
        c -> {
          if (u.rol() == bo.uagrm.crm.dominio.usuarios.Rol.VENDEDOR)
            throw new CrmException("Solo coordinacion o administracion pueden asignar.");
          uno(c, rs -> 1, "SELECT pg_advisory_xact_lock(51305)");
          List<Usuario> vendedores =
              lista(
                  c,
                  Usuario::de,
                  Usuario.SQL + " WHERE rol = 'vendedor' AND estado = 'activo' ORDER BY id");
          if (vendedores.isEmpty())
            throw new CrmException("No hay vendedores activos para repartir.");
          List<Interes> pendientes =
              lista(
                  c,
                  Interes::de,
                  Interes.SQL
                      + " WHERE i.vendedor_id IS NULL AND i.estado NOT IN ('Convertido', 'Perdido')"
                      + " AND "
                      + Alcance.visible(u)
                      + " ORDER BY i.created_at, i.id FOR UPDATE OF i",
                  Alcance.params(u));
          if (pendientes.isEmpty())
            throw new CrmException("La bolsa común está vacía: no hay prospectos sin vendedor.");
          java.util.Map<Integer, Long> carga = new java.util.LinkedHashMap<>();
          for (Usuario v : vendedores)
            carga.put(
                v.id(),
                uno(
                    c,
                    rs -> rs.getLong(1),
                    "SELECT count(*) FROM intereses WHERE vendedor_id=? AND estado NOT IN"
                        + " ('Convertido','Perdido')",
                    v.id()));
          java.util.Map<Integer, Usuario> porProspecto = new java.util.HashMap<>();
          List<Asignacion> hechas = new ArrayList<>();
          for (Interes t : pendientes) {
            Usuario v = porProspecto.get(t.prospecto().id());
            if (v == null) {
              v =
                  uno(
                      c,
                      Usuario::de,
                      Usuario.SQL
                          + " WHERE id=(SELECT vendedor_id FROM intereses WHERE prospecto_id=? AND"
                          + " vendedor_id IS NOT NULL AND estado NOT IN ('Convertido','Perdido')"
                          + " ORDER BY id LIMIT 1) AND estado='activo' AND rol='vendedor'",
                      t.prospecto().id());
              if (v == null) {
                int elegido =
                    carga.entrySet().stream()
                        .min(java.util.Map.Entry.comparingByValue())
                        .orElseThrow()
                        .getKey();
                v = vendedores.stream().filter(x -> x.id() == elegido).findFirst().orElseThrow();
              }
              porProspecto.put(t.prospecto().id(), v);
            }
            hechas.add(asignarA(c, u, t, v, "Distribucion equitativa a " + v.getNombreCompleto()));
            carga.put(v.id(), carga.get(v.id()) + 1);
          }
          return hechas;
        });
  }

  private static Asignacion asignarA(
      Connection c, Usuario actor, Interes t, Usuario v, String detalle)
      throws SQLException, CrmException {
    actualizar(
        c,
        "UPDATE intereses SET vendedor_id = ?, fecha_asignacion = COALESCE(fecha_asignacion,"
            + " clock_timestamp()), updated_at=now() WHERE id = ?",
        v.id(),
        t.id());
    actualizar(
        c,
        "UPDATE recordatorios SET usuario_id=?, updated_at=now() WHERE interes_id=? AND estado IN"
            + " ('Pendiente','Atrasada')",
        v.id(),
        t.id());
    EmbudoService.registrarMovimiento(c, t.id(), actor.id(), t.estado(), t.estado(), detalle);
    return new Asignacion(uno(c, Interes::de, Interes.SQL + " WHERE i.id = ?", t.id()), v);
  }
}
