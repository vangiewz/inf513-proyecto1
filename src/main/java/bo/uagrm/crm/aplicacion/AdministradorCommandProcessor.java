package bo.uagrm.crm.aplicacion;

import bo.uagrm.crm.persistencia.DatabaseService;

import java.util.EnumSet;
import java.util.Set;

import static bo.uagrm.crm.aplicacion.Comando.*;

/** Rol administrador: gestión completa. No usa REGISTRARINTERES (del público) ni TOMARPROSPECTO (del vendedor). */
public final class AdministradorCommandProcessor extends RolCommandProcessor {

    private static final Set<Comando> COMANDOS = EnumSet.of(HELP,
            LISTARUSUARIOS, INSERTARUSUARIO, MODIFICARUSUARIO, ELIMINARUSUARIO,
            LISTAROFERTA, REGISTRARPROSPECTO, IMPORTARPROSPECTOS, LISTARPROSPECTOS, MODIFICARPROSPECTO, ELIMINARPROSPECTO,
            ASIGNARCOORDINADOR, LISTARBOLSA, ASIGNARPROSPECTO, ASIGNARAUTOMATICO, VERTABLERO, MOVERTARJETA,
            LISTARMOTIVOS, INSERTARMOTIVO, MODIFICARMOTIVO, ELIMINARMOTIVO, MARCARPERDIDO,
            VALIDARCONVERSION, CONVERTIRPROSPECTO, LISTARMATRICULAS,
            REGISTRARINTERACCION, PROGRAMARRECORDATORIO, LISTARRECORDATORIOS, COMPLETARRECORDATORIO, VERHISTORIAL, VERBITACORA,
            REPORTEEMBUDO, REPORTEVENDEDORES, REPORTEPERDIDAS, REPORTEOFERTA);

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
