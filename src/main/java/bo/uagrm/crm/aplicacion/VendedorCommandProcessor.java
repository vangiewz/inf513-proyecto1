package bo.uagrm.crm.aplicacion;

import static bo.uagrm.crm.aplicacion.Comando.*;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.util.EnumSet;
import java.util.Set;

/** Rol vendedor: trabaja su cartera y la bolsa común (RF1.2). */
public final class VendedorCommandProcessor extends RolCommandProcessor {

  private static final Set<Comando> COMANDOS =
      EnumSet.of(
          HELP,
          LISTAROFERTA,
          REGISTRARPROSPECTO,
          LISTARPROSPECTOS,
          MODIFICARPROSPECTO,
          VERTABLERO,
          MOVERTARJETA,
          LISTARMOTIVOS,
          MARCARPERDIDO,
          GENERARORDEN,
          LISTARORDENES,
          VERORDEN,
          LISTARDESCUENTOS,
          LISTARPAGOS,
          REGISTRARCOMPROBANTE,
          VERCOMPROBANTE,
          VALIDARCONVERSION,
          CONVERTIRPROSPECTO,
          REGISTRARINTERACCION,
          PROGRAMARRECORDATORIO,
          LISTARRECORDATORIOS,
          COMPLETARRECORDATORIO,
          VERHISTORIAL,
          REPORTEEMBUDO);

  public VendedorCommandProcessor(DatabaseService db, ResponseFormatter fmt) {
    super(db, fmt);
  }

  @Override
  public Set<Comando> comandos() {
    return COMANDOS;
  }

  @Override
  public String rol() {
    return "vendedor";
  }
}
