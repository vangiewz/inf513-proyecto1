package bo.uagrm.crm.dominio.personas;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/** LISPER e INSPER: siete campos docentes, SQL preparado y autorización de administrador. */
public final class PersonaService {
  private final DatabaseService db;

  public PersonaService(DatabaseService db) {
    this.db = db;
  }

  private static void autorizar(Usuario actor) throws CrmException {
    if (actor == null || actor.rol() != Rol.ADMINISTRADOR || !actor.activo()
        || !actor.vigente(LocalDate.now()))
      throw new CrmException("LISPER e INSPER requieren un administrador activo y vigente.");
  }

  public List<Persona> listar(Usuario actor, String selector) throws SQLException, CrmException {
    autorizar(actor);
    if (!"*".equals(selector))
      throw new CrmException("LISPER admite exactamente el selector \"*\": LISPER[\"*\"].");
    try (Connection c = db.getConnection()) {
      return lista(c, Persona::de, Persona.SQL + " ORDER BY documento");
    }
  }

  public Persona insertar(Usuario actor, String documento, String nombres, String apellidos,
      String ocupacion, String telefonoFijo, String telefonoCelular, String correo)
      throws SQLException, CrmException {
    autorizar(actor);
    String doc = Valida.ci(documento);
    String nom = texto(nombres, "nombres", 120);
    String ape = texto(apellidos, "apellidos", 120);
    String ocu = texto(ocupacion, "ocupación", 80);
    String fijo = telefono(telefonoFijo, "teléfono fijo");
    String celular = telefono(telefonoCelular, "teléfono celular");
    String email = Valida.correo(correo);
    return db.enTransaccion(c -> {
      actualizar(c, "INSERT INTO persona(documento,nombres,apellidos,ocupacion,telefono_fijo,"
          + "telefono_celular,correo) VALUES(?,?,?,?,?,?,?)", doc, nom, ape, ocu, fijo, celular, email);
      return uno(c, Persona::de, Persona.SQL + " WHERE documento=?", doc);
    });
  }

  private static String texto(String valor, String campo, int max) throws CrmException {
    String texto = Valida.texto(valor, campo, max);
    if (texto.chars().anyMatch(Character::isISOControl))
      throw new CrmException("El campo " + campo + " no admite caracteres de control.");
    return texto;
  }

  /** Contrato docente: dígitos y un '+' inicial opcional; nunca normalizar un signo negativo. */
  private static String telefono(String valor, String campo) throws CrmException {
    String telefono = Valida.texto(valor, campo, 16);
    if (!telefono.matches("^\\+?[0-9]{7,15}$"))
      throw new CrmException("El campo " + campo + " debe contener de 7 a 15 dígitos, con '+' inicial opcional.");
    return telefono;
  }
}
