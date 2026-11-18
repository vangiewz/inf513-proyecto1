package bo.uagrm.crm.dominio.usuarios;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/** CU1. Gestión de Usuarios. La clave del usuario es su correo; la credencial tiene vigencia. */
public final class UsuarioService {

  private static final List<String> ROLES = List.of("administrador", "coordinador", "vendedor");
  private static final List<String> ESTADOS = List.of("activo", "inactivo");

  private final DatabaseService db;

  public UsuarioService(DatabaseService db) {
    this.db = db;
  }

  /** Usuario con ese correo (activo o no, vigente o no), o null. */
  public Usuario buscarPorCorreo(String correo) throws SQLException {
    try (Connection c = db.getConnection()) {
      return uno(c, Usuario::de, Usuario.SQL + " WHERE lower(correo) = ?", correo.toLowerCase());
    }
  }

  public List<Usuario> listar(String rol) throws SQLException, CrmException {
    String filtro = rol == null ? "" : Valida.elegir(rol, ROLES, "rol");
    try (Connection c = db.getConnection()) {
      return lista(
          c, Usuario::de, Usuario.SQL + " WHERE (? = '' OR rol = ?) ORDER BY rol, correo", filtro, filtro);
    }
  }

  /** Alta con vigencia de un año desde hoy. */
  public Usuario insertar(String nombre, String apellido, String correo, String telefono, String rol)
      throws SQLException, CrmException {
    return insertar(nombre, apellido, correo, telefono, rol, null, null);
  }

  /** fechaInicio por omisión hoy; fechaFin por omisión un año después del inicio. */
  public Usuario insertar(
      String nombre,
      String apellido,
      String correo,
      String telefono,
      String rol,
      String fechaInicio,
      String fechaFin)
      throws SQLException, CrmException {
    String n = Valida.texto(nombre, "nombre", 60), a = Valida.texto(apellido, "apellido", 60);
    String co = Valida.correo(correo),
        t = Valida.telefono(telefono),
        r = Valida.elegir(rol, ROLES, "rol");
    LocalDate ini = opcionalFecha(fechaInicio, "fecha_inicio", LocalDate.now());
    LocalDate fin = opcionalFecha(fechaFin, "fecha_fin", ini.plusYears(1));
    vigenciaValida(ini, fin);
    return db.enTransaccion(
        c -> {
          actualizar(
              c,
              "INSERT INTO usuarios (correo, nombre, apellido, telefono, rol, password, fecha_inicio,"
                  + " fecha_fin, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
              co,
              n,
              a,
              t,
              r,
              org.mindrot.jbcrypt.BCrypt.hashpw(
                      java.util.UUID.randomUUID().toString(), org.mindrot.jbcrypt.BCrypt.gensalt(12))
                  .replace("$2a$", "$2y$"),
              ini,
              fin);
          return porCorreo(c, co);
        });
  }

  /**
   * Modifica al usuario identificado por su correo. Las fechas y el correo nuevo son opcionales: si
   * no llegan se conservan; un correo nuevo se propaga a sus tarjetas, órdenes e historial (ON
   * UPDATE CASCADE).
   */
  public Usuario modificar(
      Usuario actor,
      String correo,
      String nombre,
      String apellido,
      String telefono,
      String rol,
      String estado,
      String fechaInicio,
      String fechaFin,
      String correoNuevo)
      throws SQLException, CrmException {
    String co = Valida.correo(correo);
    String n = Valida.texto(nombre, "nombre", 60), a = Valida.texto(apellido, "apellido", 60);
    String t = Valida.telefono(telefono);
    String r = Valida.elegir(rol, ROLES, "rol"), e = Valida.elegir(estado, ESTADOS, "estado");
    String nuevo = correoNuevo == null || correoNuevo.isBlank() ? co : Valida.correo(correoNuevo);
    boolean propio = co.equalsIgnoreCase(actor.correo());
    if (propio && (!r.equals(actor.rol().nombre()) || e.equals("inactivo")))
      throw new CrmException(
          "No puede cambiar su propio rol ni darse de baja: pídaselo a otro administrador.");
    return db.enTransaccion(
        c -> {
          validarCambioAcceso(c, actor, co, r, e);
          Usuario anterior = porCorreo(c, co);
          LocalDate ini = opcionalFecha(fechaInicio, "fecha_inicio", anterior.fechaInicio());
          LocalDate fin = opcionalFecha(fechaFin, "fecha_fin", anterior.fechaFin());
          vigenciaValida(ini, fin);
          if (propio && !new Usuario(co, n, a, t, actor.rol(), e, ini, fin).vigente(LocalDate.now()))
            throw new CrmException("No puede dejar su propia credencial fuera de vigencia.");
          actualizar(
              c,
              "UPDATE usuarios SET correo = ?, nombre = ?, apellido = ?, telefono = ?, rol = ?,"
                  + " estado = ?, fecha_inicio = ?, fecha_fin = ?, updated_at = now() WHERE correo = ?",
              nuevo,
              n,
              a,
              t,
              r,
              e,
              ini,
              fin,
              anterior.correo());
          if (e.equals("inactivo") || !r.equals(anterior.rol().nombre()))
            actualizar(c, "DELETE FROM api_tokens WHERE usuario_correo=?", nuevo);
          if (e.equals("inactivo") || !r.equals("vendedor")) liberarCartera(c, actor, nuevo);
          return porCorreo(c, nuevo);
        });
  }

  /**
   * Baja lógica: el usuario queda inactivo (su historial se conserva). Devuelve las tarjetas
   * liberadas.
   */
  public int eliminar(Usuario actor, String correo) throws SQLException, CrmException {
    String co = Valida.correo(correo);
    if (co.equalsIgnoreCase(actor.correo())) throw new CrmException("No puede darse de baja a sí mismo.");
    return db.enTransaccion(
        c -> {
          validarCambioAcceso(c, actor, co, null, "inactivo");
          Usuario u = porCorreo(c, co);
          if (!u.activo()) throw new CrmException("El usuario " + co + " ya está inactivo.");
          actualizar(
              c, "UPDATE usuarios SET estado = 'inactivo', updated_at = now() WHERE correo = ?", u.correo());
          actualizar(c, "DELETE FROM api_tokens WHERE usuario_correo=?", u.correo());
          return liberarCartera(c, actor, u.correo());
        });
  }

  private static LocalDate opcionalFecha(String valor, String campo, LocalDate omision)
      throws CrmException {
    return valor == null || valor.isBlank() ? omision : Valida.fecha(valor, campo);
  }

  private static void vigenciaValida(LocalDate ini, LocalDate fin) throws CrmException {
    if (fin.isBefore(ini))
      throw new CrmException(
          "La fecha_fin ("
              + fin.format(Usuario.DIA)
              + ") no puede ser anterior a la fecha_inicio ("
              + ini.format(Usuario.DIA)
              + ").");
  }

  private static void validarCambioAcceso(
      Connection c, Usuario actor, String correo, String rol, String estado)
      throws SQLException, CrmException {
    uno(c, rs -> 1, "SELECT pg_advisory_xact_lock(51305)");
    lista(
        c,
        rs -> rs.getString(1),
        "SELECT correo FROM usuarios WHERE rol='administrador' ORDER BY correo FOR UPDATE");
    Usuario actual = porCorreo(c, actor.correo());
    if (!actual.activo()
        || !actual.vigente(LocalDate.now())
        || actual.rol() != Rol.ADMINISTRADOR)
      throw new CrmException("Solo un administrador activo puede cambiar el acceso de usuarios.");
    Usuario objetivo = porCorreo(c, correo);
    boolean retira = "inactivo".equals(estado) || (rol != null && !"administrador".equals(rol));
    if (objetivo.rol() == Rol.ADMINISTRADOR
        && objetivo.activo()
        && retira
        && uno(
                c,
                rs -> 1,
                "SELECT 1 FROM usuarios WHERE rol='administrador' AND estado='activo'"
                    + " AND CURRENT_DATE BETWEEN fecha_inicio AND fecha_fin AND correo<>? LIMIT 1",
                objetivo.correo())
            == null) throw new CrmException("Debe conservar al menos un administrador activo.");
  }

  private static Usuario porCorreo(Connection c, String correo) throws SQLException, CrmException {
    Usuario u = uno(c, Usuario::de, Usuario.SQL + " WHERE lower(correo) = ?", correo.toLowerCase());
    if (u == null)
      throw new CrmException("No existe el usuario " + correo + ". Vea LISTARUSUARIOS[].");
    return u;
  }

  /**
   * Las tarjetas abiertas del vendedor vuelven a la bolsa común (RF1.2), con su movimiento en el
   * historial.
   */
  private static int liberarCartera(Connection c, Usuario actor, String vendedor)
      throws SQLException {
    return actualizar(
        c,
        """
WITH lib AS (
    UPDATE intereses SET vendedor_correo = NULL, fecha_asignacion = NULL, updated_at=now()
    WHERE vendedor_correo = ? AND estado NOT IN ('Convertido', 'Perdido')
    RETURNING nro_tarjeta, estado)
INSERT INTO movimientos_interes (nro_tarjeta, usuario_correo, estado_anterior, estado_nuevo, detalle)
SELECT nro_tarjeta, ?, estado, estado, 'Devuelta a la bolsa común (el vendedor dejó de estar activo)' FROM lib""",
        vendedor,
        actor.correo());
  }
}
