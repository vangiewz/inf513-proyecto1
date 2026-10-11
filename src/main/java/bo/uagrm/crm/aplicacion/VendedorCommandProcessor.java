package bo.uagrm.crm.aplicacion;

import bo.uagrm.crm.persistencia.DatabaseService;

import java.util.EnumSet;
import java.util.Set;

import static bo.uagrm.crm.aplicacion.Comando.*;

/** Rol vendedor: trabaja su cartera y la bolsa común (RF1.2). */
public final class VendedorCommandProcessor extends RolCommandProcessor {

    private static final Set<Comando> COMANDOS = EnumSet.of(HELP, LISTAROFERTA,
            REGISTRARPROSPECTO, LISTARPROSPECTOS, MODIFICARPROSPECTO,
            LISTARBOLSA, TOMARPROSPECTO, VERTABLERO, MOVERTARJETA,
            LISTARMOTIVOS, MARCARPERDIDO, VALIDARCONVERSION, CONVERTIRPROSPECTO,
            REGISTRARINTERACCION, PROGRAMARRECORDATORIO, LISTARRECORDATORIOS, COMPLETARRECORDATORIO, VERHISTORIAL,
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
