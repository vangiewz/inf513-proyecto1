package bo.uagrm.crm.dominio.conversion;

import static bo.uagrm.crm.persistencia.DatabaseService.*;

import bo.uagrm.crm.dominio.*;
import bo.uagrm.crm.dominio.embudo.*;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.*;
import java.util.*;

/**
 * Consulta las inscripciones confirmadas por la aprobacion del pago en el CRM, sin duplicarlas.
 */
public final class ConversionService {
  public record Requisito(String nombre, String valor, boolean cumple) {
    public static final String[] COLUMNAS = {"Requisito", "Valor", "Cumple"};

    public String[] fila() {
      return new String[] {nombre, valor, cumple ? "Sí" : "No"};
    }
  }

  private final DatabaseService db;

  public ConversionService(DatabaseService db) {
    this.db = db;
  }

  public List<Requisito> validar(Usuario u, int id) throws SQLException, CrmException {
    try (Connection c = db.getConnection()) {
      return requisitos(c, EmbudoService.tarjeta(c, u, id, false));
    }
  }

  public Matricula convertir(Usuario u, int id) throws SQLException, CrmException {
    return db.enTransaccion(
        c -> {
          Interes t = EmbudoService.tarjeta(c, u, id, true);
          List<String> faltan =
              requisitos(c, t).stream().filter(r -> !r.cumple()).map(Requisito::nombre).toList();
          if (!faltan.isEmpty())
            throw new CrmException(
                "Falta: "
                    + String.join("; ", faltan)
                    + ". La conversion se confirma con APROBARPAGO; correo no omite ese"
                    + " control.");
          Matricula m =
              uno(c, Matricula::de, Matricula.SQL + " WHERE i.id=? ORDER BY m.id LIMIT 1", id);
          if (m == null)
            throw new CrmException(
                "No existe una inscripcion comercial confirmada para esta tarjeta.");
          return m; // Idempotente: no duplica inscripciones ni comisiones.
        });
  }

  public List<Matricula> listarMatriculas(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return lista(
          c,
          Matricula::de,
          Matricula.SQL + " WHERE " + Alcance.propio(u) + " ORDER BY m.id DESC",
          Alcance.params(u));
    }
  }

  private static List<Requisito> requisitos(Connection c, Interes t) throws SQLException {
    var p = t.prospecto();
    boolean pagado =
        uno(
                c,
                r -> 1,
                """
SELECT 1 FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON sc.id=ic.solicitud_compra_id
JOIN ordenes_pago op ON op.id=sc.orden_pago_id JOIN pagos pg ON pg.orden_pago_id=op.id
JOIN intereses origen ON origen.id=op.interes_id
WHERE origen.prospecto_id=? AND sc.external_modulo_id=? AND pg.estado='Pago aprobado' AND op.estado='Pago aprobado' AND sc.estado='confirmada' AND ic.estado='Confirmada' LIMIT 1
""",
                p.id(),
                t.ofertaCodigo())
            != null;
    java.util.List<Requisito> requisitos =
        new java.util.ArrayList<>(
            List.of(
                new Requisito(
                    "Documento de identidad", p.ci(), p.ci() != null && !p.ci().isBlank()),
                new Requisito(
                    "Nombre y apellido",
                    p.getNombreCompleto(),
                    !p.nombre().isBlank() && !p.apellido().isBlank()),
                new Requisito("Telefono valido", p.telefono(), Valida.esTelefono(p.telefono())),
                new Requisito("Correo valido", p.correo(), Valida.esCorreo(p.correo())),
                new Requisito("Vendedor asignado", t.nombreVendedor(), t.vendedorId() != null),
                new Requisito(
                    "Pago aprobado e inscripcion confirmada",
                    pagado ? "Confirmada" : "Pendiente",
                    pagado),
                new Requisito(
                    "Inscripcion comercial confirmada",
                    t.estado(),
                    "Convertido".equals(t.estado()))));
    var faltan = bo.uagrm.crm.dominio.pagos.PagoService.faltantes(c, t);
    requisitos.add(
        new Requisito(
            "Profesion",
            faltan.contains("profesion") ? "Pendiente" : "Completa",
            !faltan.contains("profesion")));
    requisitos.add(
        new Requisito(
            "Ubicacion",
            faltan.contains("ubicacion") ? "Pendiente" : "Completa",
            !faltan.contains("ubicacion")));
    return List.copyOf(requisitos);
  }
}
