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
    "Exportación", "Inscripción", "Alumno", "Módulo", "Estado"
  };
  private static final String FROM =
      """
      FROM inscripciones_comerciales m JOIN solicitudes_compra sc ON sc.nro_solicitud=m.nro_solicitud
      JOIN ordenes_pago op ON op.nro_orden=sc.nro_orden JOIN intereses i ON i.nro_tarjeta=op.nro_tarjeta
      JOIN prospectos p ON p.documento=i.documento JOIN pagos pg ON pg.nro_orden=op.nro_orden
      """;
  private final DatabaseService db;

  public ExportacionService(DatabaseService db) {
    this.db = db;
  }

  private static void rol(Usuario u) throws CrmException {
    if (u.rol() == Rol.VENDEDOR)
      throw new CrmException(
          "Solo administración o coordinación preparan el intercambio académico.");
  }

  private static String alcance(Usuario u) {
    if (u.rol() == Rol.ADMINISTRADOR) return "TRUE";
    return "EXISTS (SELECT 1 FROM catalogo_cache ac JOIN programas_responsables pr ON"
        + " pr.programa_codigo=ac.programa_codigo WHERE ac.codigo=sc.modulo_codigo"
        + " AND pr.coordinador_correo=?)";
  }

  private static Object[] args(Usuario u, Object... previos) {
    var l = new ArrayList<Object>(Arrays.asList(previos));
    if (u.rol() == Rol.COORDINADOR) l.add(u.correo());
    return l.toArray();
  }

  public List<String[]> listar(Usuario u) throws SQLException, CrmException {
    rol(u);
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          "SELECT e.nro_exportacion,m.nro_solicitud,p.nombre || ' ' || p.apellido,sc.modulo_codigo,e.estado "
              + FROM
              + " JOIN exportaciones e ON e.nro_solicitud=m.nro_solicitud WHERE "
              + alcance(u)
              + " ORDER BY e.nro_exportacion DESC",
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
              "SELECT nro_solicitud FROM inscripciones_comerciales ORDER BY nro_solicitud FOR UPDATE");
          var ids =
              lista(
                  c,
                  r -> r.getInt(1),
                  "SELECT m.nro_solicitud "
                      + FROM
                      + " WHERE (?::int IS NULL OR m.nro_solicitud=?) AND "
                      + " pg.estado='Pago aprobado' AND NOT EXISTS(SELECT 1 FROM exportaciones e"
                      + " WHERE e.nro_solicitud=m.nro_solicitud AND e.estado IN"
                      + " ('preparada','exportada','recibida')) AND "
                      + alcance(u)
                      + " ORDER BY m.nro_solicitud LIMIT 1000",
                  args(u, matricula, matricula));
          if (matricula != null && ids.isEmpty())
            throw new CrmException(
                "La inscripción no está disponible, ya se preparó o no pertenece a su alcance. Use"
                    + " LISTAREXPORTACIONES y VEREXPORTACION para recuperarla.");
          var rows = new ArrayList<String[]>();
          var csv = new StringBuilder(encabezado());
          for (int nro : ids) {
            Object[] data =
                uno(
                    c,
                    r -> new Object[] {r.getString(1), r.getInt(2), r.getString(3), r.getString(4)},
                    "SELECT p.documento,m.nro_solicitud,p.nombre || ' ' || p.apellido,sc.modulo_codigo "
                        + FROM
                        + " WHERE m.nro_solicitud=?",
                    nro);
            int exportacion =
                insertar(
                    c,
                    "nro_exportacion",
                    """
INSERT INTO exportaciones(usuario_correo,nro_solicitud,fecha_exportacion,estado,respuesta,created_at,updated_at)
VALUES(?,?,now(),'preparada',json_build_object('inscripcion',json_build_object('nro_solicitud',?::int),'origen','correo','envio_SIS','pendiente de API')::text,now(),now())
""",
                    u.correo(),
                    nro,
                    nro);
            rows.add(
                new String[] {
                  "" + exportacion, "" + nro, (String) data[2], (String) data[3], "preparada"
                });
            csv.append(linea(c, nro));
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

  /** Error si la exportación no existe; Denegado si existe pero es de un programa ajeno (RF1.3). */
  private static void fueraDeAlcance(Connection c, int nro) throws SQLException, CrmException {
    if (uno(c, r -> 1, "SELECT 1 FROM exportaciones WHERE nro_exportacion=?", nro) == null)
      throw new CrmException("No existe la exportación #" + nro + ".");
    throw new bo.uagrm.crm.dominio.AccesoDenegado("La exportación #" + nro + " es de un programa que no está a su cargo.");
  }

  public Preparacion recuperar(Usuario u, int nro) throws SQLException, CrmException {
    rol(u);
    try (Connection c = db.getConnection()) {
      var rows =
          filas(
              c,
              "SELECT e.nro_exportacion,m.nro_solicitud,p.nombre || ' ' || p.apellido,sc.modulo_codigo,e.estado "
                  + FROM
                  + " JOIN exportaciones e ON e.nro_solicitud=m.nro_solicitud WHERE e.nro_exportacion=? AND "
                  + alcance(u),
              args(u, nro));
      if (rows.isEmpty()) fueraDeAlcance(c, nro);
      int inscripcion = Integer.parseInt(rows.get(0)[1]);
      return new Preparacion(
          rows,
          new Adjunto(
              "matricula_exportacion_" + nro + ".csv",
              "text/csv; charset=UTF-8",
              (encabezado() + linea(c, inscripcion)).getBytes(StandardCharsets.UTF_8)));
    }
  }

  public void resultado(Usuario u, int nro, String estado, String respuesta)
      throws SQLException, CrmException {
    rol(u);
    String e =
        Valida.elegir(
            estado,
            List.of("preparada", "exportada", "recibida", "error"),
            "estado de exportación");
    String nota = Valida.texto(respuesta, "respuesta o constancia del SIS", 2000);
    db.enTransaccion(
        c -> {
          String anterior =
              uno(
                  c,
                  r -> r.getString(1),
                  "SELECT e.estado "
                      + FROM
                      + " JOIN exportaciones e ON e.nro_solicitud=m.nro_solicitud WHERE e.nro_exportacion=? AND "
                      + alcance(u)
                      + " FOR UPDATE OF e",
                  args(u, nro));
          if (anterior == null) fueraDeAlcance(c, nro);
          if (anterior.equals("recibida") && !e.equals("recibida"))
            throw new CrmException("Una recepción confirmada no se revierte manualmente.");
          actualizar(
              c,
              "UPDATE exportaciones SET"
                  + " estado=?,respuesta=json_build_object('inscripcion',json_build_object('nro_solicitud',nro_solicitud),'origen','correo','constancia_manual',?::text)::text,updated_at=now()"
                  + " WHERE nro_exportacion=?",
              e,
              nota,
              nro);
          return null;
        });
  }

  private static String encabezado() {
    return "\ufeffnombres,apellidos,ci,telefono,profesion,ubicacion,correo,programa,modulo,external_modulo_id,precio_acordado,estado_pago,fecha_inscripcion\r\n";
  }

  private static String linea(Connection c, int nro) throws SQLException {
    List<String[]> fields =
        filas(
            c,
            "SELECT"
                + " p.nombre,p.apellido,p.documento,p.telefono,p.profesion,p.ubicacion,p.correo,sc.programa_nombre,sc.modulo_nombre,sc.modulo_codigo,sc.precio_acordado,pg.estado,m.fecha_confirmacion"
                + " "
                + FROM
                + " WHERE m.nro_solicitud=?",
            nro);
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
