package bo.uagrm.crm.dominio.usuarios;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** CU1. Gestión de Usuarios. */
public final class UsuarioService {

  private static final List<String> ROLES = List.of("administrador", "coordinador", "vendedor");
  private static final List<String> ESTADOS = List.of("activo", "inactivo");

  private final DatabaseService db;

  public UsuarioService(DatabaseService db) {
    this.db = db;
  }

  /** Usuario con ese correo (activo o no), o null. */
  public Usuario buscarPorCorreo(String correo) throws SQLException {
    try (Connection c = db.getConnection()) {
      return uno(c, Usuario::de, Usuario.SQL + " WHERE lower(correo) = ?", correo.toLowerCase());
    }
  }

  public Usuario buscarPorId(int id) throws SQLException, CrmException {
    try (Connection c = db.getConnection()) {
      return porId(c, id);
    }
  }

  public List<Usuario> listar(String rol) throws SQLException, CrmException {
    String filtro = rol == null ? "" : Valida.elegir(rol, ROLES, "rol");
    try (Connection c = db.getConnection()) {
      return lista(
          c, Usuario::de, Usuario.SQL + " WHERE (? = '' OR rol = ?) ORDER BY id", filtro, filtro);
    }
  }

  public Usuario insertar(
      String nombre, String apellido, String correo, String telefono, String rol)
      throws SQLException, CrmException {
    String n = Valida.texto(nombre, "nombre", 60), a = Valida.texto(apellido, "apellido", 60);
    String co = Valida.correo(correo),
        t = Valida.telefono(telefono),
        r = Valida.elegir(rol, ROLES, "rol");
    return db.enTransaccion(
        c -> {
          int id =
              DatabaseService.insertar(
                  c,
                  "INSERT INTO usuarios (nombre, apellido, correo, telefono, rol, password,"
                      + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                  n,
                  a,
                  co,
                  t,
                  r,
                  org.mindrot.jbcrypt.BCrypt.hashpw(
                          java.util.UUID.randomUUID().toString(),
                          org.mindrot.jbcrypt.BCrypt.gensalt(12))
                      .replace("$2a$", "$2y$"));
          return porId(c, id);
        });
  }

  public Usuario modificar(
      Usuario actor,
      int id,
      String nombre,
      String apellido,
      String correo,
      String telefono,
      String rol,
      String estado)
      throws SQLException, CrmException {
    String n = Valida.texto(nombre, "nombre", 60), a = Valida.texto(apellido, "apellido", 60);
    String co = Valida.correo(correo), t = Valida.telefono(telefono);
    String r = Valida.elegir(rol, ROLES, "rol"), e = Valida.elegir(estado, ESTADOS, "estado");
    if (id == actor.id() && (!r.equals(actor.rol().nombre()) || e.equals("inactivo")))
      throw new CrmException(
          "No puede cambiar su propio rol ni darse de baja: pídaselo a otro administrador.");
    return db.enTransaccion(
        c -> {
          validarCambioAcceso(c, actor, id, r, e);
          Usuario anterior = porId(c, id);
          actualizar(
              c,
              "UPDATE usuarios SET nombre = ?, apellido = ?, correo = ?, telefono = ?, rol ="
                  + " ?, estado = ?, updated_at = now() WHERE id = ?",
              n,
              a,
              co,
              t,
              r,
              e,
              id);
          if (e.equals("inactivo") || !r.equals(anterior.rol().nombre()))
            actualizar(c, "DELETE FROM api_tokens WHERE usuario_id=?", id);
          if (e.equals("inactivo") || !r.equals("vendedor")) liberarCartera(c, actor, id);
          return porId(c, id);
        });
  }

  /**
   * Baja lógica: el usuario queda inactivo (su historial se conserva). Devuelve las tarjetas
   * liberadas.
   */
  public int eliminar(Usuario actor, int id) throws SQLException, CrmException {
    if (id == actor.id()) throw new CrmException("No puede darse de baja a sí mismo.");

    return db.enTransaccion(
        c -> {
          validarCambioAcceso(c, actor, id, null, "inactivo");
          Usuario u = porId(c, id);
          if (!u.activo()) throw new CrmException("El usuario " + id + " ya está inactivo.");
          actualizar(
              c, "UPDATE usuarios SET estado = 'inactivo', updated_at = now() WHERE id = ?", id);
          actualizar(c, "DELETE FROM api_tokens WHERE usuario_id=?", id);
          int liberadas = liberarCartera(c, actor, id);
          return liberadas;
        });
  }

  private static void validarCambioAcceso(
      Connection c, Usuario actor, int id, String rol, String estado)
      throws SQLException, CrmException {
    uno(c, rs -> 1, "SELECT pg_advisory_xact_lock(51305)");
    lista(
        c,
        rs -> rs.getInt(1),
        "SELECT id FROM usuarios WHERE rol='administrador' ORDER BY id FOR UPDATE");
    Usuario actual = porId(c, actor.id());
    if (!actual.activo() || actual.rol() != bo.uagrm.crm.dominio.usuarios.Rol.ADMINISTRADOR)
      throw new CrmException("Solo un administrador activo puede cambiar el acceso de usuarios.");
    Usuario objetivo = porId(c, id);
    boolean retira = "inactivo".equals(estado) || (rol != null && !"administrador".equals(rol));
    if (objetivo.rol() == bo.uagrm.crm.dominio.usuarios.Rol.ADMINISTRADOR
        && objetivo.activo()
        && retira
        && uno(
                c,
                rs -> 1,
                "SELECT 1 FROM usuarios WHERE rol='administrador' AND estado='activo' AND id<>?"
                    + " LIMIT 1",
                id)
            == null) throw new CrmException("Debe conservar al menos un administrador activo.");
  }

  private static Usuario porId(Connection c, int id) throws SQLException, CrmException {
    Usuario u = uno(c, Usuario::de, Usuario.SQL + " WHERE id = ?", id);
    if (u == null)
      throw new CrmException("No existe el usuario con ID " + id + ". Vea LISTARUSUARIOS[].");
    return u;
  }

  /**
   * Las tarjetas abiertas del vendedor vuelven a la bolsa común (RF1.2), con su movimiento en el
   * historial.
   */
  private static int liberarCartera(Connection c, Usuario actor, int vendedorId)
      throws SQLException {
    return actualizar(
        c,
        """
WITH lib AS (
    UPDATE intereses SET vendedor_id = NULL, fecha_asignacion = NULL, updated_at=now()
    WHERE vendedor_id = ? AND estado NOT IN ('Convertido', 'Perdido')
    RETURNING id, estado)
INSERT INTO movimientos_interes (interes_id, usuario_id, estado_anterior, estado_nuevo, detalle)
SELECT id, ?, estado, estado, 'Devuelta a la bolsa común (el vendedor dejó de estar activo)' FROM lib""",
        vendedorId,
        actor.id());
  }
}
