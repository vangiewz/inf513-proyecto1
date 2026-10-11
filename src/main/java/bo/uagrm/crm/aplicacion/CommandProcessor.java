package bo.uagrm.crm.aplicacion;

import bo.uagrm.crm.dominio.seguimiento.SeguimientoService;
import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.dominio.usuarios.UsuarioService;
import bo.uagrm.crm.persistencia.DatabaseService;

import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Control central: recibe el correo, lo pasa a CommandParser, identifica al remitente contra la tabla usuario
 * y delega en el procesador de su rol. Todo correo atendido queda en la bitácora (log_comando).
 */
public final class CommandProcessor {

    private final CommandParser parser = new CommandParser();
    private final ResponseFormatter fmt = new ResponseFormatter();
    private final UsuarioService usuarios;
    private final SeguimientoService seguimiento;
    private final Map<Rol, RolCommandProcessor> porRol = new EnumMap<>(Rol.class);
    private final PublicoCommandProcessor publico;

    public CommandProcessor(DatabaseService db) {
        this.usuarios = new UsuarioService(db);
        this.seguimiento = new SeguimientoService(db);
        porRol.put(Rol.ADMINISTRADOR, new AdministradorCommandProcessor(db, fmt));
        porRol.put(Rol.COORDINADOR, new CoordinadorCommandProcessor(db, fmt));
        porRol.put(Rol.VENDEDOR, new VendedorCommandProcessor(db, fmt));
        this.publico = new PublicoCommandProcessor(db, fmt);
    }

    /**
     * Procesa un correo. Devuelve null cuando no corresponde responder: remitente desconocido con un asunto
     * que ni siquiera tiene forma de comando (publicidad, spam).
     */
    public Respuesta procesarEmail(String remitente, String asunto, String adjunto) {
        Command cmd = parser.parse(asunto, remitente, adjunto);
        Usuario u = null;
        Respuesta r;
        try {
            u = usuarios.buscarPorCorreo(remitente);
            if (u == null && !CommandParser.pareceComando(asunto)) {
                bitacora(remitente, null, asunto, "Ignorado", "Remitente no registrado y asunto sin forma de comando");
                return null;
            }
            // Orden de control: formato del comando → identidad del remitente → permiso del rol → dominio.
            if (!cmd.isValido()) r = fmt.error(cmd.getNombre(), cmd.getMensajeError());
            else if (u == null) r = publico.procesar(cmd, null);
            else if (!u.activo()) r = fmt.denegado(cmd.getNombre(), "Su usuario está inactivo. Pida a un administrador que lo reactive.");
            else r = porRol.get(u.rol()).procesar(cmd, u);
        } catch (SQLException e) {
            System.err.println("Sin acceso a la BD al identificar a " + remitente + ": " + e.getMessage());
            r = fmt.error(cmd.getNombre(), Errores.traducir(e));
        } catch (RuntimeException e) {
            e.printStackTrace();
            r = fmt.error(cmd.getNombre(), "Ocurrió un error interno al procesar el comando. Quedó registrado; intente de nuevo.");
        }
        bitacora(remitente, u == null ? null : u.id(), asunto, r.resultado(), r.resultado().equals("Éxito") ? r.titulo() : r.mensaje());
        return r;
    }

    /** Registra en la bitácora (CU7) un correo que no llegó a procesarse (automático, ilegible, demasiado grande…). */
    public void bitacora(String remitente, Integer usuarioId, String asunto, String resultado, String detalle) {
        try {
            seguimiento.registrarComando(remitente == null || remitente.isBlank() ? "(ilegible)" : remitente, usuarioId, asunto, resultado, detalle);
        } catch (SQLException | RuntimeException e) {
            System.err.println("No se pudo escribir la bitácora: " + e.getMessage());
        }
    }

    public ResponseFormatter formatter() {
        return fmt;
    }

    /** Fila de la matriz de acceso de cada rol (para el anexo de comandos por rol). */
    public Map<String, RolCommandProcessor> procesadores() {
        return Map.of("administrador", porRol.get(Rol.ADMINISTRADOR), "coordinador", porRol.get(Rol.COORDINADOR),
                "vendedor", porRol.get(Rol.VENDEDOR), "público", publico);
    }
}
