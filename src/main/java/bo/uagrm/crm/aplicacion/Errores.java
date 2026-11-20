package bo.uagrm.crm.aplicacion;

import java.sql.SQLException;
import java.util.Map;
import org.postgresql.util.PSQLException;

/** Traduce los errores de PostgreSQL a mensajes en español para el remitente. */
final class Errores {

  private static final Map<String, String> DUPLICADOS =
      Map.ofEntries(
          Map.entry("persona_pkey", "Ya existe una persona con ese documento."),
          Map.entry("persona_correo_lower_unique", "Ya existe una persona con ese correo."),
          Map.entry("usuarios_pkey", "Ya existe un usuario con ese correo."),
          Map.entry("usuarios_correo_lower_unique", "Ya existe un usuario con ese correo."),
          Map.entry("prospectos_pkey", "Ya existe un prospecto con ese CI."),
          Map.entry("prospectos_correo_lower_unique", "Ya existe un prospecto con ese correo."),
          Map.entry("prospectos_correo_unique", "Ya existe un prospecto con ese correo."),
          Map.entry("motivos_perdida_pkey", "Ya existe un motivo con ese nombre."),
          Map.entry("descuentos_pkey", "Ya existe un descuento con ese código."),
          Map.entry("intereses_documento_modulo_codigo_unique",
              "El prospecto ya tiene registrado su interés en esa oferta."),
          Map.entry("inscripciones_comerciales_pkey", "La solicitud ya tiene una inscripción confirmada."));

  private Errores() {}

  static String traducir(SQLException e) {
    String estado = e.getSQLState() == null ? "" : e.getSQLState();
    String restriccion =
        e instanceof PSQLException p && p.getServerErrorMessage() != null
            ? p.getServerErrorMessage().getConstraint()
            : null;
    if (estado.equals("23505"))
      return DUPLICADOS.getOrDefault(restriccion, "Ya existe un registro con esos datos.");
    if (estado.equals("23503"))
      return "El dato está en uso o hace referencia a un registro que no existe.";
    if (estado.equals("23514") || estado.equals("22001") || estado.equals("23502"))
      return "Uno de los valores no está permitido o es demasiado largo.";
    if (bo.uagrm.crm.persistencia.DatabaseService.esDeConexion(e))
      return "No hay conexión con la base de datos. Intente de nuevo en unos minutos.";
    java.util.logging.Logger.getLogger("crm").warning("Error SQL no previsto: SQLSTATE=" + estado);
    return "No se pudo completar la operación (código " + estado + "). Intente de nuevo.";
  }
}
