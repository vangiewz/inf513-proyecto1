package bo.uagrm.crm.aplicacion;

import static bo.uagrm.crm.aplicacion.Comando.*;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.util.EnumSet;
import java.util.Set;

/**
 * Rol coordinador: gestiona las ofertas a su cargo (RF1.3), reparte prospectos, motivos y reportes.
 */
public final class CoordinadorCommandProcessor extends RolCommandProcessor {

  private static final Set<Comando> COMANDOS =
      EnumSet.of(
          HELP,
          LISTARUSUARIOS,
          LISTAROFERTA,
          REGISTRARPROSPECTO,
          IMPORTARPROSPECTOS,
          LISTARPROSPECTOS,
          MODIFICARPROSPECTO,
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
          REPORTEEMBUDO,
          REPORTEVENDEDORES,
          REPORTEPERDIDAS,
          REPORTEOFERTA);

  public CoordinadorCommandProcessor(DatabaseService db, ResponseFormatter fmt) {
    super(db, fmt);
  }

  @Override
  public Set<Comando> comandos() {
    return COMANDOS;
  }

  @Override
  public String rol() {
    return "coordinador";
  }
}
