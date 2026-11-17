package bo.uagrm.crm.dominio.conversion;

import static bo.uagrm.crm.persistencia.DatabaseService.*;

import bo.uagrm.crm.dominio.*;
import bo.uagrm.crm.dominio.usuarios.*;
import bo.uagrm.crm.persistencia.DatabaseService;
import bo.uagrm.crm.presentacion.Adjunto;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Prepara el intercambio academico. Sin API SIS no afirma una recepcion automatica. */
public final class ExportacionService {
  public record Preparacion(List<String[]> filas, Adjunto archivo) {}

  public static final String[] COLUMNAS = {
    "Exportacion", "Inscripcion", "Alumno", "Modulo", "Estado"
  };
  private static final String FROM =
      """
      FROM inscripciones_comerciales m JOIN solicitudes_compra sc ON sc.id=m.solicitud_compra_id
      JOIN ordenes_pago op ON op.id=sc.orden_pago_id JOIN intereses i ON i.id=op.interes_id
      JOIN prospectos p ON p.id=i.prospecto_id JOIN pagos pg ON pg.orden_pago_id=op.id
      """;
  private final DatabaseService db;

  public ExportacionService(DatabaseService db) {
    this.db = db;
  }

  private static void rol(Usuario u) throws CrmException {
    if (u.rol() == Rol.VENDEDOR)
      throw new CrmException(
          "Solo administracion o coordinacion preparan el intercambio academico.");
  }

  private static String alcance(Usuario u) {
    if (u.rol() == Rol.ADMINISTRADOR) return "TRUE";
    return "EXISTS (SELECT 1 FROM catalogo_cache ac JOIN programas_responsables pr ON"
        + " pr.external_programa_id=ac.programa_codigo WHERE ac.codigo=sc.external_modulo_id"
        + " AND pr.coordinador_id=?)";
  }

  private static Object[] args(Usuario u, Object... previos) {
    var l = new ArrayList<Object>(Arrays.asList(previos));
    if (u.rol() == Rol.COORDINADOR) l.add(u.id());
    return l.toArray();
  }

  public List<String[]> listar(Usuario u) throws SQLException, CrmException {
    rol(u);
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          "SELECT e.id,m.id,p.nombre || ' ' || p.apellido,sc.external_modulo_id,e.estado "
              + FROM
              + " JOIN exportaciones e ON e.inscripcion_comercial_id=m.id WHERE "
              + alcance(u)
              + " ORDER BY e.id DESC",
          args(u));
    }
  }

  public Preparacion preparar(Usuario u, Integer matricula) throws SQLException, CrmException {
    rol(u);
    return db.enTransaccion(
        c -> {
          // Mismo orden de bloqueo que la exportacion web: inscripciones por ID.
          lista(
              c,
              r -> r.getInt(1),
              "SELECT id FROM inscripciones_comerciales ORDER BY id FOR UPDATE");
          var ids =
              lista(
                  c,
                  r -> r.getInt(1),
                  "SELECT m.id "
                      + FROM
                      + " WHERE (?::int IS NULL OR m.id=?) AND m.estado='Confirmada' AND"
                      + " pg.estado='Pago aprobado' AND NOT EXISTS(SELECT 1 FROM exportaciones e"
                      + " WHERE e.inscripcion_comercial_id=m.id AND e.estado IN"
                      + " ('preparada','exportada','recibida')) AND "
                      + alcance(u)
                      + " ORDER BY m.id LIMIT 1000",
                  args(u, matricula, matricula));
          if (matricula != null && ids.isEmpty())
            throw new CrmException(
                "La inscripcion no esta disponible, ya se preparo o no pertenece a su alcance. Use"
                    + " LISTAREXPORTACIONES y VEREXPORTACION para recuperarla.");
          var rows = new ArrayList<String[]>();
          var csv = new StringBuilder(encabezado());
          for (int id : ids) {
            Object[] data =
                uno(
                    c,
                    r -> new Object[] {r.getInt(1), r.getInt(2), r.getString(3), r.getString(4)},
                    "SELECT p.id,m.id,p.nombre || ' ' || p.apellido,sc.external_modulo_id "
                        + FROM
                        + " WHERE m.id=?",
                    id);
            int exportacion =
                insertar(
                    c,
                    """
INSERT INTO exportaciones(usuario_id,prospecto_id,inscripcion_comercial_id,fecha_exportacion,estado,respuesta,created_at,updated_at)
VALUES(?,?,?,now(),'preparada',json_build_object('inscripcion',json_build_object('id',?::int),'origen','correo','envio_SIS','pendiente de API')::text,now(),now())
""",
                    u.id(),
                    data[0],
                    id,
                    id);
            rows.add(
                new String[] {
                  "" + exportacion, "" + id, (String) data[2], (String) data[3], "preparada"
                });
            csv.append(linea(c, id));
          }
          return new Preparacion(
              List.copyOf(rows),
              ids.isEmpty()
                  ? null
                  : new Adjunto(
                      "matriculas_preparadas.csv",
                      "text/csv; charset=UTF-8",
                      csv.toString().getBytes(StandardCharsets.UTF_8)));
        });
  }

  public Preparacion recuperar(Usuario u, int id) throws SQLException, CrmException {
    rol(u);
    try (Connection c = db.getConnection()) {
      var rows =
          filas(
              c,
              "SELECT e.id,m.id,p.nombre || ' ' || p.apellido,sc.external_modulo_id,e.estado "
                  + FROM
                  + " JOIN exportaciones e ON e.inscripcion_comercial_id=m.id WHERE e.id=? AND "
                  + alcance(u),
              args(u, id));
      if (rows.isEmpty()) throw new CrmException("No existe esa exportacion en su alcance.");
      int inscripcion = Integer.parseInt(rows.get(0)[1]);
      return new Preparacion(
          rows,
          new Adjunto(
              "matricula_exportacion_" + id + ".csv",
              "text/csv; charset=UTF-8",
              (encabezado() + linea(c, inscripcion)).getBytes(StandardCharsets.UTF_8)));
    }
  }

  public void resultado(Usuario u, int id, String estado, String respuesta)
      throws SQLException, CrmException {
    rol(u);
    String e =
        Valida.elegir(
            estado,
            List.of("preparada", "exportada", "recibida", "error"),
            "estado de exportacion");
    String nota = Valida.texto(respuesta, "respuesta o constancia del SIS", 2000);
    db.enTransaccion(
        c -> {
          String anterior =
              uno(
                  c,
                  r -> r.getString(1),
                  "SELECT e.estado "
                      + FROM
                      + " JOIN exportaciones e ON e.inscripcion_comercial_id=m.id WHERE e.id=? AND "
                      + alcance(u)
                      + " FOR UPDATE OF e",
                  args(u, id));
          if (anterior == null) throw new CrmException("No existe esa exportacion en su alcance.");
          if (anterior.equals("recibida") && !e.equals("recibida"))
            throw new CrmException("Una recepcion confirmada no se revierte manualmente.");
          actualizar(
              c,
              "UPDATE exportaciones SET"
                  + " estado=?,respuesta=json_build_object('inscripcion',json_build_object('id',inscripcion_comercial_id),'origen','correo','constancia_manual',?::text)::text,updated_at=now()"
                  + " WHERE id=?",
              e,
              nota,
              id);
          return null;
        });
  }

  private static String encabezado() {
    return "\ufeffnombres,apellidos,ci,telefono,profesion,ubicacion,correo,programa,modulo,external_modulo_id,precio_acordado,estado_pago,fecha_inscripcion\r\n";
  }

  private static String linea(Connection c, int id) throws SQLException {
    List<String[]> fields =
        filas(
            c,
            "SELECT"
                + " p.nombre,p.apellido,p.documento,p.telefono,p.profesion,p.ubicacion,p.correo,sc.programa_nombre,sc.modulo_nombre,sc.external_modulo_id,sc.precio_acordado,pg.estado,m.fecha_confirmacion"
                + " "
                + FROM
                + " WHERE m.id=?",
            id);
    return Arrays.stream(fields.get(0))
            .map(ExportacionService::celda)
            .collect(java.util.stream.Collectors.joining(","))
        + "\r\n";
  }

  private static String celda(String s) {
    String value = s == null ? "" : s;
    String trim = value.stripLeading();
    if (!trim.isEmpty() && "=+-@".indexOf(trim.charAt(0)) >= 0) value = "'" + value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
