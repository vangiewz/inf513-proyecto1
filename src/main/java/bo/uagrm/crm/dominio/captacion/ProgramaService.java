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

  public int asignar(Usuario actor, String codigo, int coordinador)
      throws SQLException, CrmException {
    if (actor.rol() != Rol.ADMINISTRADOR)
      throw new CrmException("Solo administracion puede asignar responsables de programa.");
    String programa = Valida.texto(codigo, "codigo de programa", 255);
    return db.enTransaccion(
        c -> {
          uno(c, r -> 1, "SELECT pg_advisory_xact_lock(51305)");
          if (uno(
                  c,
                  r -> 1,
                  "SELECT 1 FROM usuarios WHERE id=? AND rol='administrador' AND estado='activo'",
                  actor.id())
              == null) throw new CrmException("Se requiere un administrador activo.");
          if (uno(
                  c,
                  r -> 1,
                  "SELECT 1 FROM usuarios WHERE id=? AND rol='coordinador' AND estado='activo'",
                  coordinador)
              == null) throw new CrmException("El responsable debe ser un coordinador activo.");
          Integer cuantos =
              uno(
                  c,
                  r -> r.getInt(1),
                  "SELECT count(*) FROM catalogo_cache WHERE programa_codigo=?",
                  programa);
          if (cuantos == 0)
            throw new CrmException("No existe ese programa en el catalogo. Vea LISTAROFERTA[].");
          actualizar(
              c,
              """
INSERT INTO programas_responsables(external_programa_id,coordinador_id,created_at,updated_at) VALUES(?,?,now(),now())
ON CONFLICT(external_programa_id) DO UPDATE SET coordinador_id=excluded.coordinador_id,updated_at=now()
""",
              programa,
              coordinador);
          return cuantos;
        });
  }
}
