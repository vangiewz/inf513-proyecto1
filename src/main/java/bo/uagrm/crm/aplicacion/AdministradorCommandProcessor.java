package bo.uagrm.crm.aplicacion;

import static bo.uagrm.crm.aplicacion.Comando.*;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.util.EnumSet;
import java.util.Set;

/**
 * Rol administrador: gestión completa. No usa REGISTRARINTERES (del público) ni TOMARPROSPECTO (del
 * vendedor).
 */
public final class AdministradorCommandProcessor extends RolCommandProcessor {

  private static final Set<Comando> COMANDOS =
      EnumSet.of(
          HELP,
          LISTARUSUARIOS,
          INSERTARUSUARIO,
          MODIFICARUSUARIO,
          ELIMINARUSUARIO,
          LISTAROFERTA,
          REGISTRARPROSPECTO,
          IMPORTARPROSPECTOS,
          LISTARPROSPECTOS,
          MODIFICARPROSPECTO,
          ELIMINARPROSPECTO,
          ASIGNARCOORDINADOR,
          LISTARBOLSA,
          ASIGNARPROSPECTO,
          ASIGNARAUTOMATICO,
          VERTABLERO,
          MOVERTARJETA,
          LISTARMOTIVOS,
          INSERTARMOTIVO,
          MODIFICARMOTIVO,
          ELIMINARMOTIVO,
          MARCARPERDIDO,
          GENERARORDEN,
          LISTARORDENES,
          VERORDEN,
          LISTARDESCUENTOS,
          LISTARPAGOS,
          REGISTRARCOMPROBANTE,
          VERCOMPROBANTE,
          APROBARPAGO,
          RECHAZARPAGO,
          EXPORTARMATRICULAS,
          LISTAREXPORTACIONES,
          VEREXPORTACION,
          REGISTRARRESULTADOSIS,
          VALIDARCONVERSION,
          CONVERTIRPROSPECTO,
          LISTARMATRICULAS,
          REGISTRARINTERACCION,
          PROGRAMARRECORDATORIO,
          LISTARRECORDATORIOS,
          COMPLETARRECORDATORIO,
          VERHISTORIAL,
          VERBITACORA,
          REPORTEEMBUDO,
          REPORTEVENDEDORES,
          REPORTEPERDIDAS,
          REPORTEOFERTA,
          NUEVOPRUEBA);

  public AdministradorCommandProcessor(DatabaseService db, ResponseFormatter fmt) {
    super(db, fmt);
  }

  @Override
  public Set<Comando> comandos() {
    return COMANDOS;
  }

  @Override
  public String rol() {
    return "administrador";
  }
}
