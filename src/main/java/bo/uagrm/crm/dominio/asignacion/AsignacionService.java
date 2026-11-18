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
              + " WHERE i.vendedor_correo IS NULL AND i.estado NOT IN ('Convertido', 'Perdido') AND "
              + Alcance.visible(u)
              + " ORDER BY i.created_at, i.nro_tarjeta",
          Alcance.params(u));
    }
  }

  public Asignacion asignar(Usuario u, int nroTarjeta, String vendedor)
      throws SQLException, CrmException {
    return db.enTransaccion(
        c -> {
          if (u.rol() == bo.uagrm.crm.dominio.usuarios.Rol.VENDEDOR)
            throw new CrmException("Solo coordinacion o administracion pueden asignar.");
          uno(c, rs -> 1, "SELECT pg_advisory_xact_lock(51305)");
          Interes t = EmbudoService.tarjeta(c, u, nroTarjeta, true);
          if (t.cerrada())
            throw new CrmException(
                "La tarjeta #" + nroTarjeta + " está en " + t.estado() + ": ya no se asigna.");
          Usuario v =
              uno(
                  c,
                  Usuario::de,
                  Usuario.SQL
                      + " WHERE lower(correo) = lower(?) AND rol = 'vendedor' AND estado = 'activo'"
                      + " AND CURRENT_DATE BETWEEN fecha_inicio AND fecha_fin",
                  vendedor);
          if (v == null)
            throw new CrmException(
                "El usuario "
                    + vendedor
                    + " no es un vendedor activo y vigente. Vea LISTARUSUARIOS[\"vendedor\"].");
          if (v.correo().equals(t.vendedorCorreo()))
            throw new CrmException(
                "La tarjeta #" + nroTarjeta + " ya es de " + v.getNombreCompleto() + ".");
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
                  Usuario.SQL
                      + " WHERE rol = 'vendedor' AND estado = 'activo' AND CURRENT_DATE BETWEEN fecha_inicio AND fecha_fin ORDER BY correo");
          if (vendedores.isEmpty())
            throw new CrmException("No hay vendedores activos para repartir.");
          List<Interes> pendientes =
              lista(
                  c,
                  Interes::de,
                  Interes.SQL
                      + " WHERE i.vendedor_correo IS NULL AND i.estado NOT IN ('Convertido', 'Perdido')"
                      + " AND "
                      + Alcance.visible(u)
                      + " ORDER BY i.created_at, i.nro_tarjeta FOR UPDATE OF i",
                  Alcance.params(u));
          if (pendientes.isEmpty())
            throw new CrmException("La bolsa común está vacía: no hay prospectos sin vendedor.");
          java.util.Map<String, Long> carga = new java.util.LinkedHashMap<>();
          for (Usuario v : vendedores)
            carga.put(
                v.correo(),
                uno(
                    c,
                    rs -> rs.getLong(1),
                    "SELECT count(*) FROM intereses WHERE vendedor_correo=? AND estado NOT IN"
                        + " ('Convertido','Perdido')",
                    v.correo()));
          java.util.Map<String, Usuario> porProspecto = new java.util.HashMap<>();
          List<Asignacion> hechas = new ArrayList<>();
          for (Interes t : pendientes) {
            Usuario v = porProspecto.get(t.prospecto().ci());
            if (v == null) {
              v =
                  uno(
                      c,
                      Usuario::de,
                      Usuario.SQL
                          + " WHERE correo=(SELECT vendedor_correo FROM intereses WHERE documento=? AND"
                          + " vendedor_correo IS NOT NULL AND estado NOT IN ('Convertido','Perdido')"
                          + " ORDER BY nro_tarjeta LIMIT 1) AND estado='activo' AND rol='vendedor'"
                          + " AND CURRENT_DATE BETWEEN fecha_inicio AND fecha_fin",
                      t.prospecto().ci());
              if (v == null) {
                String elegido =
                    carga.entrySet().stream()
                        .min(java.util.Map.Entry.comparingByValue())
                        .orElseThrow()
                        .getKey();
                v = vendedores.stream().filter(x -> x.correo().equals(elegido)).findFirst().orElseThrow();
              }
              porProspecto.put(t.prospecto().ci(), v);
            }
            hechas.add(asignarA(c, u, t, v, "Distribucion equitativa a " + v.getNombreCompleto()));
            carga.put(v.correo(), carga.get(v.correo()) + 1);
          }
          return hechas;
        });
  }

  private static Asignacion asignarA(
      Connection c, Usuario actor, Interes t, Usuario v, String detalle)
      throws SQLException, CrmException {
    actualizar(
        c,
        "UPDATE intereses SET vendedor_correo = ?, fecha_asignacion = COALESCE(fecha_asignacion,"
            + " clock_timestamp()), updated_at=now() WHERE nro_tarjeta = ?",
        v.correo(),
        t.nro());
    actualizar(
        c,
        "UPDATE recordatorios SET usuario_correo=?, updated_at=now() WHERE nro_tarjeta=? AND estado IN"
            + " ('Pendiente','Atrasada')",
        v.correo(),
        t.nro());
    EmbudoService.registrarMovimiento(c, t.nro(), actor.correo(), t.estado(), t.estado(), detalle);
    return new Asignacion(uno(c, Interes::de, Interes.SQL + " WHERE i.nro_tarjeta = ?", t.nro()), v);
  }
}
