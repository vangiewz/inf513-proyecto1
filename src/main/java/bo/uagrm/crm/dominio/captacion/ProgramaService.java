package bo.uagrm.crm.dominio.captacion;

import static bo.uagrm.crm.persistencia.DatabaseService.*;

import bo.uagrm.crm.dominio.*;
import bo.uagrm.crm.dominio.usuarios.*;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.*;

/** RF1.3: la responsabilidad comercial se asigna al identificador real de la oferta externa. */
public final class ProgramaService {
  private final DatabaseService db;

  public ProgramaService(DatabaseService db) {
    this.db = db;
  }

  public int asignar(Usuario actor, String codigo, String coordinador)
      throws SQLException, CrmException {
    if (actor.rol() != Rol.ADMINISTRADOR)
      throw new CrmException("Solo administración puede asignar responsables de programa.");
    String programa = Valida.texto(codigo, "codigo de programa", 255);
    String responsable = Valida.correo(coordinador);
    return db.enTransaccion(
        c -> {
          uno(c, r -> 1, "SELECT pg_advisory_xact_lock(51305)");
          if (uno(
                  c,
                  r -> 1,
                  "SELECT 1 FROM usuarios WHERE correo=? AND rol='administrador' AND estado='activo'",
                  actor.correo())
              == null) throw new CrmException("Se requiere un administrador activo.");
          if (uno(
                  c,
                  r -> 1,
                  "SELECT 1 FROM usuarios WHERE correo=? AND rol='coordinador' AND estado='activo'"
                      + " AND CURRENT_DATE BETWEEN fecha_inicio AND fecha_fin",
                  responsable)
              == null) throw new CrmException("El responsable debe ser un coordinador activo.");
          Integer cuantos =
              uno(
                  c,
                  r -> r.getInt(1),
                  "SELECT count(*) FROM catalogo_cache WHERE programa_codigo=?",
                  programa);
          if (cuantos == 0)
            throw new CrmException("No existe ese programa en el catálogo. Vea LISTAROFERTA[].");
          actualizar(
              c,
              """
INSERT INTO programas_responsables(programa_codigo,coordinador_correo,created_at,updated_at) VALUES(?,?,now(),now())
ON CONFLICT(programa_codigo) DO UPDATE SET coordinador_correo=excluded.coordinador_correo,updated_at=now()
""",
              programa,
              responsable);
          return cuantos;
        });
  }
}
