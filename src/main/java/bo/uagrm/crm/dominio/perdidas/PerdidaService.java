package bo.uagrm.crm.dominio.perdidas;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;

import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.dominio.embudo.EmbudoService;
import bo.uagrm.crm.dominio.embudo.Interes;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * CU5. Gestión de Pérdida de Prospectos y Clasificación de Motivos (RF3.3). La clave del motivo es
 * su nombre; al escribirlo en un comando no importan mayúsculas ni tildes.
 */
public final class PerdidaService {

  public static final List<String> CATEGORIAS =
      List.of("Económico", "Horario", "Incompatibilidad", "Contacto", "Otro");

  private final DatabaseService db;

  public PerdidaService(DatabaseService db) {
    this.db = db;
  }

  public List<MotivoPerdida> listarMotivos() throws SQLException {
    try (Connection c = db.getConnection()) {
      return todos(c);
    }
  }

  public MotivoPerdida insertarMotivo(String nombre, String categoria)
      throws SQLException, CrmException {
    String n = Valida.texto(nombre, "nombre del motivo", 80),
        cat = Valida.elegir(categoria, CATEGORIAS, "categoría");
    return db.enTransaccion(
        c -> {
          if (buscar(c, n) != null) throw new CrmException("Ya existe el motivo \"" + n + "\".");
          actualizar(c, "INSERT INTO motivos_perdida (nombre, categoria) VALUES (?, ?)", n, cat);
          return porNombre(c, n);
        });
  }

  /** Cambia el nombre (se propaga a las tarjetas: ON UPDATE CASCADE) o la categoría. */
  public MotivoPerdida modificarMotivo(String motivo, String nombreNuevo, String categoria)
      throws SQLException, CrmException {
    String n = Valida.texto(nombreNuevo, "nombre nuevo del motivo", 80),
        cat = Valida.elegir(categoria, CATEGORIAS, "categoría");
    return db.enTransaccion(
        c -> {
          MotivoPerdida m = porNombre(c, motivo);
          MotivoPerdida otro = buscar(c, n);
          if (otro != null && !otro.nombre().equals(m.nombre()))
            throw new CrmException("Ya existe el motivo \"" + otro.nombre() + "\".");
          actualizar(
              c, "UPDATE motivos_perdida SET nombre = ?, categoria = ? WHERE nombre = ?", n, cat, m.nombre());
          return porNombre(c, n);
        });
  }

  public MotivoPerdida eliminarMotivo(String motivo) throws SQLException, CrmException {
    return db.enTransaccion(
        c -> {
          MotivoPerdida m = porNombre(c, motivo);
          if (m.usos() > 0)
            throw new CrmException(
                "El motivo \""
                    + m.nombre()
                    + "\" clasifica "
                    + m.usos()
                    + " tarjeta(s): no se puede eliminar, solo modificar.");
          actualizar(c, "DELETE FROM motivos_perdida WHERE nombre = ?", m.nombre());
          return m;
        });
  }

  /** Pasa la tarjeta a Perdido con su motivo obligatorio. */
  public Interes marcarPerdido(Usuario u, int nroTarjeta, String motivo, String detalle)
      throws SQLException, CrmException {
    String d = Valida.opcional(detalle, "detalle", 200);
    return db.enTransaccion(
        c -> {
          Interes t = EmbudoService.tarjeta(c, u, nroTarjeta, true);
          if (t.cerrada())
            throw new CrmException("La tarjeta #" + nroTarjeta + " ya está en " + t.estado() + ".");
          if (t.estado().equals("En revision"))
            throw new CrmException(
                "La tarjeta #" + nroTarjeta + " tiene un comprobante en revisión: primero APROBARPAGO o"
                    + " RECHAZARPAGO; después se puede marcar como perdida.");
          MotivoPerdida m = porNombre(c, motivo);
          // Una orden emitida y nunca pagada se cierra junto con la pérdida (no queda pendiente).
          actualizar(
              c,
              "UPDATE pagos SET estado='Pago rechazado', fecha_validacion=now(), observacion_validacion="
                  + "'Anulada: prospecto perdido', updated_at=now() WHERE estado='Pendiente de pago' AND"
                  + " nro_orden IN (SELECT nro_orden FROM ordenes_pago WHERE nro_tarjeta=?)",
              nroTarjeta);
          actualizar(
              c,
              "UPDATE intereses SET estado = 'Perdido', motivo_perdida = ?, observacion = ?"
                  + " WHERE nro_tarjeta = ?",
              m.nombre(),
              d,
              nroTarjeta);
          EmbudoService.registrarMovimiento(
              c,
              nroTarjeta,
              u.correo(),
              t.estado(),
              "Perdido",
              "Motivo: " + m.nombre() + " (" + m.categoria() + ")" + (d == null ? "" : ". " + d));
          return EmbudoService.tarjeta(c, u, nroTarjeta, false);
        });
  }

  private static List<MotivoPerdida> todos(Connection c) throws SQLException {
    return lista(
        c, MotivoPerdida::de, MotivoPerdida.SQL + MotivoPerdida.AGRUPAR + " ORDER BY mp.categoria, mp.nombre");
  }

  /** El motivo con ese nombre (sin importar mayúsculas ni tildes), o null. */
  private static MotivoPerdida buscar(Connection c, String nombre) throws SQLException {
    String buscado = Valida.normal(nombre == null ? "" : nombre);
    for (MotivoPerdida m : todos(c)) if (Valida.normal(m.nombre()).equals(buscado)) return m;
    return null;
  }

  private static MotivoPerdida porNombre(Connection c, String nombre)
      throws SQLException, CrmException {
    Valida.texto(nombre, "motivo", 80);
    MotivoPerdida m = buscar(c, nombre);
    if (m == null)
      throw new CrmException("No existe el motivo \"" + nombre.trim() + "\". Vea LISTARMOTIVOS[].");
    return m;
  }
}
