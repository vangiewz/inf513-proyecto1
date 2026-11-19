package bo.uagrm.crm.dominio.captacion;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

import bo.uagrm.crm.dominio.Alcance;
import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.dominio.embudo.EmbudoService;
import bo.uagrm.crm.dominio.embudo.Interes;
import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * CU2. Gestión de Captar, Consumir Oferta: catálogo del SIS y registro de prospectos (RF2.1,
 * RF2.2).
 */
public final class ProspectoService {

  public static final List<String> ORIGENES =
      List.of(
          "Facebook",
          "Instagram",
          "TikTok",
          "WhatsApp",
          "Referido",
          "Web",
          "Correo",
          "Feria",
          "Otro");
  public static final String[] COLUMNAS_IMPORTACION = {"Fila", "CI", "Resultado"};

  /** Resultado de registrar un interés: la tarjeta creada y si el prospecto era nuevo. */
  public record Alta(Interes tarjeta, boolean prospectoNuevo, int interesesCreados) {}

  private final DatabaseService db;

  public ProspectoService(DatabaseService db) {
    this.db = db;
  }

  /** Oferta del SIS: el público solo ve lo abierto para inscripción; el personal ve todo. */
  public List<Oferta> listarOferta(boolean todas) throws SQLException {
    try (Connection c = db.getConnection()) {
      return lista(
          c,
          Oferta::de,
          Oferta.SQL
              + " WHERE (? OR o.estado = 'abierto') "
              + "ORDER BY o.programa_codigo, o.codigo",
          todas);
    }
  }

  /**
   * Registro por el personal. actor = null cuando la persona se registra sola por correo (comando
   * REGISTRARINTERES).
   */
  public Alta registrar(
      Usuario actor,
      String ci,
      String nombre,
      String apellido,
      String telefono,
      String correo,
      String origen,
      String codigoOferta,
      String... ficha)
      throws SQLException, CrmException {
    return db.enTransaccion(
        c -> {
          Alta a =
              alta(c, actor, ci, nombre, apellido, telefono, correo, origen, codigoOferta, ficha);
          return a;
        });
  }

  /**
   * Importación masiva (RF2.1). Columnas: ci,nombre,apellido,telefono,correo,origen,codigo_oferta.
   */
  public List<String[]> importar(Usuario actor, String csv) throws SQLException, CrmException {
    if (csv == null || csv.isBlank())
      throw new CrmException(
          "Adjunte un archivo .csv con las columnas"
              + " documento,nombre,apellido,telefono,correo,origen,codigo_oferta.");
    List<String> lineas = csv.lines().filter(l -> !l.isBlank()).toList();
    char sep =
        lineas.get(0).chars().filter(ch -> ch == ';').count()
                > lineas.get(0).chars().filter(ch -> ch == ',').count()
            ? ';'
            : ',';
    List<String[]> resultado = new ArrayList<>();
    db.enTransaccion(
        c -> {
          for (int i = 0; i < lineas.size(); i++) {
            String ci = "(fila " + (i + 1) + ")";
            Savepoint sp = c.setSavepoint();
            try {
              List<String> f = separar(lineas.get(i), sep);
              if (i == 0 && List.of("ci", "documento").contains(Valida.normal(f.get(0)))) {
                c.releaseSavepoint(sp);
                continue;
              }
              ci = f.get(0);
              if (f.size() != 7)
                throw new CrmException("Se esperaban 7 columnas y hay " + f.size() + ".");
              Alta a =
                  alta(
                      c, actor, f.get(0), f.get(1), f.get(2), f.get(3), f.get(4), f.get(5),
                      f.get(6));
              c.releaseSavepoint(sp);
              resultado.add(
                  new String[] {
                    String.valueOf(i + 1),
                    ci,
                    "Tarjeta #"
                        + a.tarjeta().nro()
                        + " en "
                        + a.tarjeta().ofertaCodigo()
                        + (a.prospectoNuevo() ? "" : " (prospecto ya registrado)")
                  });
            } catch (CrmException | SQLException | IllegalArgumentException e) {
              c.rollback(sp);
              resultado.add(new String[] {String.valueOf(i + 1), ci, "Error: " + mensaje(e)});
            }
          }
          return null;
        });
    return resultado;
  }

  public List<Interes> listar(Usuario u, String texto) throws SQLException {
    String t = texto == null ? "" : texto.trim();
    try (Connection c = db.getConnection()) {
      return lista(
          c,
          Interes::de,
          Interes.SQL
              + " WHERE "
              + Alcance.visible(u)
              + " AND (? = '' OR p.documento = upper(?) OR p.nombre || ' ' || p.apellido ILIKE '%'"
              + " || ? || '%') ORDER BY i.nro_tarjeta",
          Alcance.params(u, t, t, t));
    }
  }

  public Prospecto modificar(
      Usuario u,
      String ci,
      String nombre,
      String apellido,
      String telefono,
      String correo,
      String origen,
      String... ficha)
      throws SQLException, CrmException {
    String c1 = Valida.ci(ci),
        n = Valida.texto(nombre, "nombre", 60),
        a = Valida.texto(apellido, "apellido", 60);
    String t = Valida.telefono(telefono),
        co = Valida.correo(correo),
        o = Valida.elegir(origen, ORIGENES, "origen");
    return db.enTransaccion(
        c -> {
          Prospecto p = porCi(c, c1);
          if (u.rol() != Rol.ADMINISTRADOR
              && uno(
                      c,
                      rs -> 1,
                      "SELECT 1 FROM intereses i WHERE i.documento = ? AND " + Alcance.propio(u),
                      Alcance.despues(u, p.ci()))
                  == null)
            throw new CrmException("El prospecto " + c1 + " no tiene tarjetas a su cargo.");
          actualizar(
              c,
              "UPDATE prospectos SET nombre = ?, apellido = ?, telefono = ?, correo = ?, origen ="
                  + " ?, profesion=COALESCE(?,profesion),ubicacion=COALESCE(?,ubicacion),updated_at"
                  + " = now() WHERE documento = ?",
              n,
              a,
              t,
              co,
              o,
              Valida.opcional(ficha.length > 0 ? ficha[0] : null, "profesion", 120),
              Valida.opcional(ficha.length > 1 ? ficha[1] : null, "ubicacion", 160),
              p.ci());
          return porCi(c, c1);
        });
  }

  public Prospecto eliminar(String ci) throws SQLException, CrmException {
    return db.enTransaccion(
        c -> {
          Prospecto p = porCi(c, Valida.ci(ci));
          if (uno(c, rs -> 1, "SELECT 1 FROM intereses WHERE documento = ?", p.ci()) != null)
            throw new CrmException(
                "El prospecto tiene intereses o historial comercial: se conserva y no se puede"
                    + " eliminar.");
          actualizar(c, "DELETE FROM prospectos WHERE documento = ?", p.ci());
          return p;
        });
  }

  public int asignarCoordinador(Usuario actor, String programa, String coordinador)
      throws SQLException, CrmException {
    return new ProgramaService(db).asignar(actor, programa, coordinador);
  }

  private static Alta alta(
      Connection c,
      Usuario actor,
      String ci,
      String nombre,
      String apellido,
      String telefono,
      String correo,
      String origen,
      String codigoOferta,
      String... ficha)
      throws SQLException, CrmException {
    String doc = Valida.ci(ci),
        n = Valida.texto(nombre, "nombre", 120),
        a = Valida.texto(apellido, "apellido", 120);
    String tel = Valida.telefono(telefono),
        email = Valida.correo(correo),
        fuente = Valida.elegir(origen, ORIGENES, "origen");
    String codigo = Valida.texto(codigoOferta, "codigo de oferta", 255);
    List<Oferta> ofertas =
        lista(
            c,
            Oferta::de,
            Oferta.SQL + " WHERE o.codigo = ? OR o.programa_codigo = ? ORDER BY o.codigo",
            codigo,
            codigo);
    if (ofertas.isEmpty())
      throw new CrmException(
          "No existe la oferta. Vea LISTAROFERTA[] o consulte a administración para actualizar el catálogo.");
    if (ofertas.stream().anyMatch(o -> !o.abierta()))
      throw new CrmException("La oferta esta cerrada y no recibe inscripciones.");
    if (actor != null
        && actor.rol() == Rol.COORDINADOR
        && ofertas.stream().anyMatch(of -> !actor.correo().equalsIgnoreCase(Objects.toString(of.coordinadorCorreo(), ""))))
      throw new CrmException("El programa no está a su cargo.");
    Prospecto p =
        uno(
            c,
            Prospecto::de,
            Prospecto.SQL + " WHERE documento = ? OR lower(correo) = ? FOR UPDATE",
            doc,
            email);
    boolean nuevo = p == null;
    if (!nuevo && (!p.ci().equals(doc) || !p.correo().equalsIgnoreCase(email)))
      throw new CrmException(
          "No se pudo registrar el interés con esos datos. Contacte a coordinación.");
    if (nuevo)
      actualizar(
                c,
                "INSERT INTO"
                    + " prospectos(documento,nombre,apellido,telefono,correo,origen,profesion,ubicacion,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,now(),now())",
                doc,
                n,
                a,
                tel,
                email,
                fuente,
                Valida.opcional(ficha.length > 0 ? ficha[0] : null, "profesion", 120),
                Valida.opcional(ficha.length > 1 ? ficha[1] : null, "ubicacion", 160));
    int primero = 0, creados = 0;
    for (Oferta of : ofertas) {
      if (uno(
              c,
              rs -> 1,
              "SELECT 1 FROM intereses WHERE documento=? AND modulo_codigo=?",
              doc,
              of.codigo())
          != null) continue;
      int nro =
          DatabaseService.insertar(
              c,
              "nro_tarjeta",
              """
INSERT INTO intereses(documento,modulo_codigo,anuncio_codigo,programa_nombre,modulo_nombre,
    modulo_descripcion,precio,estado,created_at,updated_at,ultima_actividad_en)
VALUES(?,?,?,?,?,?,?,'Nuevo',now(),now(),now())
""",
              doc,
              of.codigo(),
              of.anuncioId(),
              of.programa(),
              of.nombre(),
              of.descripcion(),
              of.precio());
      EmbudoService.registrarMovimiento(
          c,
          nro,
          actor == null ? null : actor.correo(),
          null,
          "Nuevo",
          "Registro por correo. Origen: " + fuente);
      if (primero == 0) primero = nro;
      creados++;
    }
    if (creados == 0) throw new CrmException("El prospecto ya tiene registrado ese interes.");
    return new Alta(uno(c, Interes::de, Interes.SQL + " WHERE i.nro_tarjeta=?", primero), nuevo, creados);
  }

  private static Prospecto porCi(Connection c, String ci) throws SQLException, CrmException {
    Prospecto p = uno(c, Prospecto::de, Prospecto.SQL + " WHERE documento=?", ci);
    if (p == null) throw new CrmException("No existe un prospecto con CI " + ci + ".");
    return p;
  }

  static List<String> separar(String linea, char sep) {
    List<String> out = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    boolean comillas = false;
    for (int i = 0; i < linea.length(); i++) {
      char ch = linea.charAt(i);
      if (ch == '"') {
        if (comillas && i + 1 < linea.length() && linea.charAt(i + 1) == '"') {
          sb.append('"');
          i++;
        } else comillas = !comillas;
      } else if (ch == sep && !comillas) {
        out.add(sb.toString().trim());
        sb.setLength(0);
      } else sb.append(ch);
    }
    if (comillas) throw new IllegalArgumentException("La fila CSV tiene comillas sin cerrar.");
    out.add(sb.toString().trim());
    return out;
  }

  private static String mensaje(Exception e) {
    if (e instanceof SQLException s && "23505".equals(s.getSQLState())) return "dato duplicado.";
    return e.getMessage();
  }
}
