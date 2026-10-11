package bo.uagrm.crm.dominio.reportes;

import bo.uagrm.crm.dominio.Alcance;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static bo.uagrm.crm.persistencia.DatabaseService.filas;

/** CU8. Reportes y Estadísticas. Cada rol ve las cifras de su alcance (RF1.2, RF1.3). */
public final class ReporteService {

    public static final String[] COLUMNAS_EMBUDO = {"Etapa", "Tarjetas", "%"};
    public static final String[] COLUMNAS_VENDEDORES = {"Vendedor", "Asignadas", "Abiertas", "Convertidas", "Perdidas", "Conversión %", "Matriculado (Bs)"};
    public static final String[] COLUMNAS_MOTIVOS = {"Categoría", "Motivo", "Tarjetas", "%"};
    public static final String[] COLUMNAS_CATEGORIAS = {"Categoría", "Tarjetas", "%"};
    public static final String[] COLUMNAS_OFERTA = {"Oferta", "Nombre", "Intereses", "Convertidos", "Perdidos", "Matriculado (Bs)"};

    private final DatabaseService db;

    public ReporteService(DatabaseService db) {
        this.db = db;
    }

    /** Tarjetas por etapa (incluye las vacías) en el orden del tablero. */
    public List<String[]> embudo(Usuario u) throws SQLException {
        try (Connection c = db.getConnection()) {
            return filas(c, """
                    SELECT e.estado, COUNT(i.id), COALESCE(ROUND(100.0 * COUNT(i.id) / NULLIF(SUM(COUNT(i.id)) OVER (), 0), 1), 0.0)
                    FROM (VALUES (1, 'Nuevo'), (2, 'En Contacto'), (3, 'Seguimiento'), (4, 'Promesa de Pago'),
                                 (5, 'Convertido'), (6, 'Perdido')) AS e (orden, estado)
                    LEFT JOIN (interes i JOIN oferta o ON o.id = i.oferta_id) ON i.estado = e.estado AND\s""" + Alcance.propio(u) + """

                    GROUP BY e.orden, e.estado ORDER BY e.orden""", Alcance.params(u));
        }
    }

    public List<String[]> vendedores(Usuario u) throws SQLException {
        try (Connection c = db.getConnection()) {
            return filas(c, """
                    SELECT v.nombre || ' ' || v.apellido || CASE WHEN v.estado = 'inactivo' THEN ' (inactivo)' ELSE '' END,
                           COUNT(i.id),
                           COUNT(i.id) FILTER (WHERE i.estado NOT IN ('Convertido', 'Perdido')),
                           COUNT(i.id) FILTER (WHERE i.estado = 'Convertido'),
                           COUNT(i.id) FILTER (WHERE i.estado = 'Perdido'),
                           COALESCE(ROUND(100.0 * COUNT(i.id) FILTER (WHERE i.estado = 'Convertido') / NULLIF(COUNT(i.id), 0), 1), 0.0),
                           COALESCE(SUM(m.monto), 0.00)
                    FROM usuario v
                    LEFT JOIN (interes i JOIN oferta o ON o.id = i.oferta_id) ON i.vendedor_id = v.id AND\s""" + Alcance.propio(u) + """

                    LEFT JOIN matricula m ON m.interes_id = i.id
                    WHERE v.rol = 'vendedor'
                    GROUP BY v.id
                    ORDER BY COUNT(i.id) FILTER (WHERE i.estado = 'Convertido') DESC, v.id""", Alcance.params(u));
        }
    }

    public List<String[]> perdidasPorMotivo(Usuario u) throws SQLException {
        try (Connection c = db.getConnection()) {
            return filas(c, """
                    SELECT mp.categoria, mp.nombre, COUNT(i.id),
                           COALESCE(ROUND(100.0 * COUNT(i.id) / NULLIF(SUM(COUNT(i.id)) OVER (), 0), 1), 0.0)
                    FROM motivo_perdida mp
                    LEFT JOIN (interes i JOIN oferta o ON o.id = i.oferta_id) ON i.motivo_perdida_id = mp.id AND\s""" + Alcance.propio(u) + """

                    GROUP BY mp.id
                    ORDER BY COUNT(i.id) DESC, mp.categoria, mp.nombre""", Alcance.params(u));
        }
    }

    public List<String[]> perdidasPorCategoria(Usuario u) throws SQLException {
        try (Connection c = db.getConnection()) {
            return filas(c, """
                    SELECT mp.categoria, COUNT(i.id),
                           COALESCE(ROUND(100.0 * COUNT(i.id) / NULLIF(SUM(COUNT(i.id)) OVER (), 0), 1), 0.0)
                    FROM motivo_perdida mp
                    LEFT JOIN (interes i JOIN oferta o ON o.id = i.oferta_id) ON i.motivo_perdida_id = mp.id AND\s""" + Alcance.propio(u) + """

                    GROUP BY mp.categoria
                    ORDER BY COUNT(i.id) DESC, mp.categoria""", Alcance.params(u));
        }
    }

    public List<String[]> oferta(Usuario u) throws SQLException {
        try (Connection c = db.getConnection()) {
            return filas(c, """
                    SELECT o.codigo, o.nombre, COUNT(i.id),
                           COUNT(i.id) FILTER (WHERE i.estado = 'Convertido'),
                           COUNT(i.id) FILTER (WHERE i.estado = 'Perdido'),
                           COALESCE(SUM(m.monto), 0.00)
                    FROM interes i
                    JOIN oferta o ON o.id = i.oferta_id
                    LEFT JOIN matricula m ON m.interes_id = i.id
                    WHERE\s""" + Alcance.propio(u) + """

                    GROUP BY o.id
                    ORDER BY COUNT(i.id) DESC, o.codigo""", Alcance.params(u));
        }
    }
}
