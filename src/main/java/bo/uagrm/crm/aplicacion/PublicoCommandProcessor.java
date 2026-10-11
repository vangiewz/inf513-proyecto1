package bo.uagrm.crm.aplicacion;

import bo.uagrm.crm.persistencia.DatabaseService;

import java.util.EnumSet;
import java.util.Set;

import static bo.uagrm.crm.aplicacion.Comando.*;

/** Remitente no registrado (posible prospecto): consulta la oferta y registra su interés. */
public final class PublicoCommandProcessor extends RolCommandProcessor {

    private static final Set<Comando> COMANDOS = EnumSet.of(HELP, LISTAROFERTA, REGISTRARINTERES);

    public PublicoCommandProcessor(DatabaseService db, ResponseFormatter fmt) {
        super(db, fmt);
    }

    @Override
    public Set<Comando> comandos() {
        return COMANDOS;
    }

    @Override
    public String rol() {
        return "público";
    }

    @Override
    protected String mensajeDenegado(Command cmd) {
        return "El correo " + cmd.getRemitente() + " no está registrado como usuario del CRM. Como visitante puede usar "
                + "HELP[], LISTAROFERTA[] e REGISTRARINTERES[\"codigo_oferta\",\"ci\",\"nombre\",\"apellido\",\"telefono\"].";
    }
}
