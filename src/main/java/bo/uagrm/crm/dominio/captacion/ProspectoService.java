package bo.uagrm.crm.dominio.captacion;

import bo.uagrm.crm.dominio.Alcance;
import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.dominio.embudo.EmbudoService;
import bo.uagrm.crm.dominio.embudo.Interes;
import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

/** CU2. Gestión de Captar, Consumir Oferta: catálogo del SIS y registro de prospectos (RF2.1, RF2.2). */
public final class ProspectoService {

    public static final List<String> ORIGENES =
            List.of("Facebook", "Instagram", "TikTok", "WhatsApp", "Referido", "Web", "Correo", "Feria", "Otro");
    public static final String[] COLUMNAS_IMPORTACION = {"Fila", "CI", "Resultado"};

    /** Resultado de registrar un interés: la tarjeta creada y si el prospecto era nuevo. */
    public record Alta(Interes tarjeta, boolean prospectoNuevo) {
    }

    private final DatabaseService db;

    public ProspectoService(DatabaseService db) {
        this.db = db;
    }

    /** Oferta del SIS: el público solo ve lo abierto para inscripción; el personal ve todo. */
    public List<Oferta> listarOferta(boolean todas) throws SQLException {
        try (Connection c = db.getConnection()) {
            return lista(c, Oferta::de, Oferta.SQL + " WHERE (? OR o.estado = 'abierto') "
                    + "ORDER BY COALESCE(o.programa_id, o.id), o.programa_id NULLS FIRST, o.codigo", todas);
        }
    }

    /** Registro por el personal. actor = null cuando la persona se registra sola por correo (comando REGISTRARINTERES). */
    public Alta registrar(Usuario actor, String ci, String nombre, String apellido, String telefono, String correo,
                          String origen, String codigoOferta) throws SQLException, CrmException {
        return db.enTransaccion(c -> {
            Alta a = alta(c, actor, ci, nombre, apellido, telefono, correo, origen, codigoOferta);
            return a;
        });
    }

    /** Importación masiva (RF2.1). Columnas: ci,nombre,apellido,telefono,correo,origen,codigo_oferta. */
    public List<String[]> importar(Usuario actor, String csv) throws SQLException, CrmException {
        if (csv == null || csv.isBlank())
            throw new CrmException("Adjunte un archivo .csv con las columnas ci,nombre,apellido,telefono,correo,origen,codigo_oferta.");
        List<String> lineas = csv.lines().filter(l -> !l.isBlank()).toList();
        char sep = lineas.get(0).chars().filter(ch -> ch == ';').count() > lineas.get(0).chars().filter(ch -> ch == ',').count() ? ';' : ',';
        List<String[]> resultado = new ArrayList<>();
        db.enTransaccion(c -> {
            for (int i = 0; i < lineas.size(); i++) {
                List<String> f = separar(lineas.get(i), sep);
                if (i == 0 && Valida.normal(f.get(0)).equals("ci")) continue;   // encabezado
                String ci = f.get(0);
                Savepoint sp = c.setSavepoint();
                try {
                    if (f.size() != 7) throw new CrmException("Se esperaban 7 columnas y hay " + f.size() + ".");
                    Alta a = alta(c, actor, f.get(0), f.get(1), f.get(2), f.get(3), f.get(4), f.get(5), f.get(6));
                    c.releaseSavepoint(sp);
                    resultado.add(new String[]{String.valueOf(i + 1), ci, "Tarjeta #" + a.tarjeta().id() + " en "
                            + a.tarjeta().ofertaCodigo() + (a.prospectoNuevo() ? "" : " (prospecto ya registrado)")});
                } catch (CrmException | SQLException e) {
                    c.rollback(sp);
                    resultado.add(new String[]{String.valueOf(i + 1), ci, "Error: " + mensaje(e)});
                }
            }
            return null;
        });
        return resultado;
    }

    public List<Interes> listar(Usuario u, String texto) throws SQLException {
        String t = texto == null ? "" : texto.trim();
        try (Connection c = db.getConnection()) {
            return lista(c, Interes::de, Interes.SQL + " WHERE " + Alcance.visible(u)
                    + " AND (? = '' OR p.ci = upper(?) OR p.nombre || ' ' || p.apellido ILIKE '%' || ? || '%') ORDER BY i.id",
                    Alcance.params(u, t, t, t));
        }
    }

    public Prospecto modificar(Usuario u, String ci, String nombre, String apellido, String telefono, String correo,
                               String origen) throws SQLException, CrmException {
        String c1 = Valida.ci(ci), n = Valida.texto(nombre, "nombre", 60), a = Valida.texto(apellido, "apellido", 60);
        String t = Valida.telefono(telefono), co = Valida.correoOpcional(correo), o = Valida.elegir(origen, ORIGENES, "origen");
        return db.enTransaccion(c -> {
            Prospecto p = porCi(c, c1);
            if (u.rol() != Rol.ADMINISTRADOR && uno(c, rs -> 1, "SELECT 1 FROM interes i JOIN oferta o ON o.id = i.oferta_id WHERE i.prospecto_id = ? AND "
                    + Alcance.propio(u), Alcance.despues(u, p.id())) == null)
                throw new CrmException("El prospecto " + c1 + " no tiene tarjetas a su cargo.");
            actualizar(c, "UPDATE prospecto SET nombre = ?, apellido = ?, telefono = ?, correo = ?, origen = ? WHERE id = ?",
                    n, a, t, co, o, p.id());
            return porCi(c, c1);
        });
    }

    public Prospecto eliminar(String ci) throws SQLException, CrmException {
        return db.enTransaccion(c -> {
            Prospecto p = porCi(c, Valida.ci(ci));
            if (uno(c, rs -> 1, "SELECT 1 FROM matricula m JOIN interes i ON i.id = m.interes_id WHERE i.prospecto_id = ?", p.id()) != null)
                throw new CrmException("El prospecto " + p.ci() + " ya tiene una matrícula: no se puede eliminar.");
            actualizar(c, "DELETE FROM prospecto WHERE id = ?", p.id());
            return p;
        });
    }

    /** Pone un programa y sus módulos a cargo de un coordinador (RF1.3). Devuelve cuántas ofertas cambiaron. */
    public int asignarCoordinador(String codigoPrograma, int coordinadorId) throws SQLException, CrmException {
        return db.enTransaccion(c -> {
            Oferta p = uno(c, Oferta::de, Oferta.SQL + " WHERE o.codigo = ? AND o.tipo = 'programa'", codigoPrograma.trim());
            if (p == null) throw new CrmException("No existe el programa \"" + codigoPrograma + "\". Vea LISTAROFERTA[].");
            if (uno(c, rs -> 1, "SELECT 1 FROM usuario WHERE id = ? AND rol = 'coordinador' AND estado = 'activo'", coordinadorId) == null)
                throw new CrmException("El usuario " + coordinadorId + " no es un coordinador activo.");
            return actualizar(c, "UPDATE oferta SET coordinador_id = ? WHERE id = ? OR programa_id = ?", coordinadorId, p.id(), p.id());
        });
    }

    private static Alta alta(Connection c, Usuario actor, String ci, String nombre, String apellido, String telefono,
                             String correo, String origen, String codigoOferta) throws SQLException, CrmException {
        String c1 = Valida.ci(ci), n = Valida.texto(nombre, "nombre", 60), a = Valida.texto(apellido, "apellido", 60);
        String t = Valida.telefono(telefono), co = Valida.correoOpcional(correo), o = Valida.elegir(origen, ORIGENES, "origen");
        Oferta of = uno(c, Oferta::de, Oferta.SQL + " WHERE o.codigo = ?", Valida.texto(codigoOferta, "código de oferta", 40));
        if (of == null) throw new CrmException("No existe la oferta \"" + codigoOferta + "\". Vea LISTAROFERTA[].");
        if (!of.abierta()) throw new CrmException("La oferta " + of.codigo() + " está " + of.estado() + " en el SIS: no recibe inscripciones.");
        if (actor != null && actor.rol() == Rol.COORDINADOR && !Objects.equals(of.coordinadorId(), actor.id()))
            throw new CrmException("La oferta " + of.codigo() + " no está a su cargo.");

        Prospecto p = uno(c, Prospecto::de, Prospecto.SQL + " WHERE ci = ?", c1);
        boolean nuevo = p == null;
        int prospectoId = nuevo
                ? DatabaseService.insertar(c, "INSERT INTO prospecto (ci, nombre, apellido, telefono, correo, origen, registrado_por) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        c1, n, a, t, co, o, actor == null ? null : actor.id())
                : p.id();
        if (uno(c, rs -> 1, "SELECT 1 FROM interes WHERE prospecto_id = ? AND oferta_id = ?", prospectoId, of.id()) != null)
            throw new CrmException("El prospecto " + c1 + " ya tiene registrado su interés en " + of.codigo() + ".");

        Integer vendedor = actor != null && actor.rol() == Rol.VENDEDOR ? actor.id() : null;   // RF1.2: entra a su cartera
        int id = DatabaseService.insertar(c, "INSERT INTO interes (prospecto_id, oferta_id, vendedor_id, asignado_en) "
                + "VALUES (?, ?, ?, CASE WHEN ?::int IS NULL THEN NULL ELSE clock_timestamp() END)", prospectoId, of.id(), vendedor, vendedor);
        EmbudoService.registrarMovimiento(c, id, actor == null ? null : actor.id(), null, "Nuevo",
                actor == null ? "Registrado por el propio interesado vía correo" : "Origen: " + o);
        return new Alta(uno(c, Interes::de, Interes.SQL + " WHERE i.id = ?", id), nuevo);
    }

    private static Prospecto porCi(Connection c, String ci) throws SQLException, CrmException {
        Prospecto p = uno(c, Prospecto::de, Prospecto.SQL + " WHERE ci = ?", ci);
        if (p == null) throw new CrmException("No existe un prospecto con CI " + ci + ".");
        return p;
    }

    /** Separa una línea CSV respetando comillas ("a, b" es un solo campo). */
    static List<String> separar(String linea, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean comillas = false;
        for (int i = 0; i < linea.length(); i++) {
            char ch = linea.charAt(i);
            if (ch == '"') {
                if (comillas && i + 1 < linea.length() && linea.charAt(i + 1) == '"') { sb.append('"'); i++; }
                else comillas = !comillas;
            } else if (ch == sep && !comillas) {
                out.add(sb.toString().trim());
                sb.setLength(0);
            } else sb.append(ch);
        }
        out.add(sb.toString().trim());
        return out;
    }

    private static String mensaje(Exception e) {
        if (e instanceof SQLException s && "23505".equals(s.getSQLState())) return "dato duplicado.";
        return e.getMessage();
    }
}
