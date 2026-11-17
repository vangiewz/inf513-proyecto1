package bo.uagrm.crm.aplicacion;

import java.sql.SQLException;
import java.util.Map;
import org.postgresql.util.PSQLException;

/** Traduce los errores de PostgreSQL a mensajes en español para el remitente. */
final class Errores {

  private static final Map<String, String> DUPLICADOS =
      Map.of(
          "usuario_correo_key", "Ya existe un usuario con ese correo.",
          "prospecto_ci_key", "Ya existe un prospecto con ese CI.",
          "motivo_perdida_nombre_key", "Ya existe un motivo con ese nombre.",
          "interes_prospecto_id_oferta_id_key",
              "El prospecto ya tiene registrado su interés en esa oferta.",
          "matricula_interes_id_key", "La tarjeta ya tiene una matrícula.");

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
    if (estado.equals("23514") || estado.equals("22001"))
      return "Uno de los valores no está permitido o es demasiado largo.";
    if (estado.startsWith("08"))
      return "No hay conexión con la base de datos. Intente de nuevo en unos minutos.";
    java.util.logging.Logger.getLogger("crm").warning("Error SQL no previsto: " + estado + " " + e.getMessage());
    return "No se pudo completar la operación (código " + estado + "). Intente de nuevo.";
  }
}
