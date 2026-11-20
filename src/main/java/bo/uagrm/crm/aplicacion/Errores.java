package bo.uagrm.crm.aplicacion;

import java.sql.SQLException;
import java.util.Map;
import org.postgresql.util.PSQLException;

/** Traduce los errores de PostgreSQL a mensajes en español para el remitente. */
final class Errores {

  private static final Map<String, String> DUPLICADOS =
      Map.of(
          "usuarios_pkey", "Ya existe un usuario con ese correo.",
          "usuarios_correo_lower_unique", "Ya existe un usuario con ese correo.",
          "prospectos_pkey", "Ya existe un prospecto con ese CI.",
          "prospectos_correo_lower_unique", "Ya existe un prospecto con ese correo.",
          "prospectos_correo_unique", "Ya existe un prospecto con ese correo.",
          "motivos_perdida_pkey", "Ya existe un motivo con ese nombre.",
          "descuentos_pkey", "Ya existe un descuento con ese código.",
          "intereses_documento_modulo_codigo_unique",
              "El prospecto ya tiene registrado su interés en esa oferta.",
          "inscripciones_comerciales_pkey", "La solicitud ya tiene una inscripción confirmada.");

  private Errores() {}

  static String traducir(SQLException e) {
    bo.uagrm.crm.persistencia.DatabaseService.registrarFallo(e);
    String estado = e.getSQLState() == null ? "" : e.getSQLState();
    // Toda excepción SQL capturada queda en error.log (enunciado §5), aunque al usuario se le traduzca.
    java.util.logging.Logger.getLogger("crm")
        .log(java.util.logging.Level.WARNING, "SQLState=" + estado + " codigo=" + e.getErrorCode(), e);
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
    for (Throwable t = e; t != null; t = t.getCause())
      if (t instanceof java.net.SocketTimeoutException) // la BD vive pero la consulta quedó bloqueada
        return "La base de datos está ocupada y no respondió a tiempo. Intente de nuevo en unos minutos.";
    if (estado.startsWith("08") || estado.startsWith("57P"))
      return "No hay conexión con la base de datos. Intente de nuevo en unos minutos.";
    if (estado.equals("42501"))
      return "La base de datos no permite esta operación (permiso denegado). Avise al administrador.";
    if (estado.equals("40P01") || estado.equals("55P03") || estado.equals("57014"))
      return "La base de datos está ocupada y no respondió a tiempo. Intente de nuevo en unos minutos.";
    return "No se pudo completar la operación (código " + estado + "). Intente de nuevo.";
  }
}
