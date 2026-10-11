package bo.uagrm.crm.dominio.usuarios;

import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.persistencia.DatabaseService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

/** CU1. Gestión de Usuarios. */
public final class UsuarioService {

    private static final List<String> ROLES = List.of("administrador", "coordinador", "vendedor");
    private static final List<String> ESTADOS = List.of("activo", "inactivo");

    private final DatabaseService db;

    public UsuarioService(DatabaseService db) {
        this.db = db;
    }

    /** Usuario con ese correo (activo o no), o null. */
    public Usuario buscarPorCorreo(String correo) throws SQLException {
        try (Connection c = db.getConnection()) {
            return uno(c, Usuario::de, Usuario.SQL + " WHERE correo = ?", correo.toLowerCase());
        }
    }

    public Usuario buscarPorId(int id) throws SQLException, CrmException {
        try (Connection c = db.getConnection()) {
            return porId(c, id);
        }
    }

    public List<Usuario> listar(String rol) throws SQLException, CrmException {
        String filtro = rol == null ? "" : Valida.elegir(rol, ROLES, "rol");
        try (Connection c = db.getConnection()) {
            return lista(c, Usuario::de, Usuario.SQL + " WHERE (? = '' OR rol = ?) ORDER BY id", filtro, filtro);
        }
    }

    public Usuario insertar(String nombre, String apellido, String correo, String telefono, String rol)
            throws SQLException, CrmException {
        String n = Valida.texto(nombre, "nombre", 60), a = Valida.texto(apellido, "apellido", 60);
        String co = Valida.correo(correo), t = Valida.telefono(telefono), r = Valida.elegir(rol, ROLES, "rol");
        return db.enTransaccion(c -> {
            int id = DatabaseService.insertar(c,
                    "INSERT INTO usuario (nombre, apellido, correo, telefono, rol) VALUES (?, ?, ?, ?, ?)", n, a, co, t, r);
            return porId(c, id);
        });
    }

    public Usuario modificar(Usuario actor, int id, String nombre, String apellido, String correo, String telefono,
                             String rol, String estado) throws SQLException, CrmException {
        String n = Valida.texto(nombre, "nombre", 60), a = Valida.texto(apellido, "apellido", 60);
        String co = Valida.correo(correo), t = Valida.telefono(telefono);
        String r = Valida.elegir(rol, ROLES, "rol"), e = Valida.elegir(estado, ESTADOS, "estado");
        if (id == actor.id() && (!r.equals(actor.rol().nombre()) || e.equals("inactivo")))
            throw new CrmException("No puede cambiar su propio rol ni darse de baja: pídaselo a otro administrador.");
        return db.enTransaccion(c -> {
            porId(c, id);
            actualizar(c, "UPDATE usuario SET nombre = ?, apellido = ?, correo = ?, telefono = ?, rol = ?, estado = ? WHERE id = ?",
                    n, a, co, t, r, e, id);
            if (e.equals("inactivo") || !r.equals("vendedor")) liberarCartera(c, actor, id);
            return porId(c, id);
        });
    }

    /** Baja lógica: el usuario queda inactivo (su historial se conserva). Devuelve las tarjetas liberadas. */
    public int eliminar(Usuario actor, int id) throws SQLException, CrmException {
        if (id == actor.id()) throw new CrmException("No puede darse de baja a sí mismo.");
        return db.enTransaccion(c -> {
            Usuario u = porId(c, id);
            if (!u.activo()) throw new CrmException("El usuario " + id + " ya está inactivo.");
            actualizar(c, "UPDATE usuario SET estado = 'inactivo' WHERE id = ?", id);
            int liberadas = liberarCartera(c, actor, id);
            return liberadas;
        });
    }

    private static Usuario porId(Connection c, int id) throws SQLException, CrmException {
        Usuario u = uno(c, Usuario::de, Usuario.SQL + " WHERE id = ?", id);
        if (u == null) throw new CrmException("No existe el usuario con ID " + id + ". Vea LISTARUSUARIOS[].");
        return u;
    }

    /** Las tarjetas abiertas del vendedor vuelven a la bolsa común (RF1.2), con su movimiento en el historial. */
    private static int liberarCartera(Connection c, Usuario actor, int vendedorId) throws SQLException {
        return actualizar(c, """
                WITH lib AS (
                    UPDATE interes SET vendedor_id = NULL, asignado_en = NULL
                    WHERE vendedor_id = ? AND estado NOT IN ('Convertido', 'Perdido')
                    RETURNING id, estado)
                INSERT INTO movimiento (interes_id, usuario_id, estado_anterior, estado_nuevo, detalle)
                SELECT id, ?, estado, estado, 'Devuelta a la bolsa común (el vendedor dejó de estar activo)' FROM lib""",
                vendedorId, actor.id());
    }
}
