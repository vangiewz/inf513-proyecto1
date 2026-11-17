package bo.uagrm.crm.dominio.reportes;

import static bo.uagrm.crm.persistencia.DatabaseService.filas;

import bo.uagrm.crm.dominio.Alcance;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** CU8. Reportes y Estadísticas. Cada rol ve las cifras de su alcance (RF1.2, RF1.3). */
public final class ReporteService {

  public static final String[] COLUMNAS_EMBUDO = {"Etapa", "Tarjetas", "%"};
  public static final String[] COLUMNAS_VENDEDORES = {
    "Vendedor",
    "Asignadas",
    "Abiertas",
    "Convertidas",
    "Perdidas",
    "Conversión %",
    "Matriculado (Bs)"
  };
  public static final String[] COLUMNAS_MOTIVOS = {"Categoría", "Motivo", "Tarjetas", "%"};
  public static final String[] COLUMNAS_CATEGORIAS = {"Categoría", "Tarjetas", "%"};
  public static final String[] COLUMNAS_OFERTA = {
    "Oferta", "Nombre", "Intereses", "Convertidos", "Perdidos", "Matriculado (Bs)"
  };

  private final DatabaseService db;

  public ReporteService(DatabaseService db) {
    this.db = db;
  }

  /** Tarjetas por etapa (incluye las vacías) en el orden del tablero. */
  public List<String[]> embudo(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          """
SELECT e.estado, COUNT(i.id), COALESCE(ROUND(100.0 * COUNT(i.id) / NULLIF(SUM(COUNT(i.id)) OVER (), 0), 1), 0.0)
FROM (VALUES (1, 'Nuevo'), (2, 'En Contacto'), (3, 'Seguimiento'), (4, 'Promesa de Pago'),
             (5, 'Convertido'), (6, 'Perdido')) AS e (orden, estado)
LEFT JOIN (intereses i LEFT JOIN catalogo_cache o ON o.codigo = i.external_modulo_id) ON (CASE i.estado WHEN 'Contactado' THEN 'En Contacto' WHEN 'Interesado' THEN 'Seguimiento' WHEN 'Pago enviado' THEN 'Promesa de Pago' WHEN 'En revision' THEN 'Promesa de Pago' ELSE i.estado END) = e.estado AND\s"""
              + Alcance.propio(u)
              + """

GROUP BY e.orden, e.estado ORDER BY e.orden""",
          Alcance.params(u));
    }
  }

  public List<String[]> vendedores(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          """
SELECT v.nombre || ' ' || v.apellido || CASE WHEN v.estado = 'inactivo' THEN ' (inactivo)' ELSE '' END,
       COUNT(i.id),
       COUNT(i.id) FILTER (WHERE i.estado NOT IN ('Convertido', 'Perdido')),
       COUNT(i.id) FILTER (WHERE i.estado = 'Convertido'),
       COUNT(i.id) FILTER (WHERE i.estado = 'Perdido'),
       COALESCE(ROUND(100.0 * COUNT(i.id) FILTER (WHERE i.estado = 'Convertido') / NULLIF(COUNT(i.id), 0), 1), 0.0),
       COALESCE(SUM(m.monto), 0.00)
FROM usuarios v
LEFT JOIN (intereses i LEFT JOIN catalogo_cache o ON o.codigo = i.external_modulo_id) ON i.vendedor_id = v.id AND\s"""
              + Alcance.propio(u)
              + """

LEFT JOIN (SELECT origen.prospecto_id,sc.external_modulo_id,SUM(sc.precio_acordado) AS monto
FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON sc.id=ic.solicitud_compra_id
JOIN ordenes_pago op ON op.id=sc.orden_pago_id JOIN intereses origen ON origen.id=op.interes_id
GROUP BY origen.prospecto_id,sc.external_modulo_id) m ON m.prospecto_id=i.prospecto_id AND m.external_modulo_id=i.external_modulo_id
WHERE v.rol='vendedor' AND (?::int=0 OR v.id=?)
GROUP BY v.id
ORDER BY COUNT(i.id) FILTER (WHERE i.estado = 'Convertido') DESC, v.id""",
          Alcance.params(
              u,
              u.rol() == bo.uagrm.crm.dominio.usuarios.Rol.VENDEDOR ? u.id() : 0,
              u.rol() == bo.uagrm.crm.dominio.usuarios.Rol.VENDEDOR ? u.id() : 0));
    }
  }

  public List<String[]> perdidasPorMotivo(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          """
SELECT mp.categoria, mp.nombre, COUNT(i.id),
       COALESCE(ROUND(100.0 * COUNT(i.id) / NULLIF(SUM(COUNT(i.id)) OVER (), 0), 1), 0.0)
FROM motivos_perdida mp
LEFT JOIN (intereses i LEFT JOIN catalogo_cache o ON o.codigo = i.external_modulo_id) ON i.motivo_perdida_id = mp.id AND\s"""
              + Alcance.propio(u)
              + """

GROUP BY mp.id
ORDER BY COUNT(i.id) DESC, mp.categoria, mp.nombre""",
          Alcance.params(u));
    }
  }

  public List<String[]> perdidasPorCategoria(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          """
SELECT mp.categoria, COUNT(i.id),
       COALESCE(ROUND(100.0 * COUNT(i.id) / NULLIF(SUM(COUNT(i.id)) OVER (), 0), 1), 0.0)
FROM motivos_perdida mp
LEFT JOIN (intereses i LEFT JOIN catalogo_cache o ON o.codigo = i.external_modulo_id) ON i.motivo_perdida_id = mp.id AND\s"""
              + Alcance.propio(u)
              + """

GROUP BY mp.categoria
ORDER BY COUNT(i.id) DESC, mp.categoria""",
          Alcance.params(u));
    }
  }

  public List<String[]> oferta(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          """
SELECT i.external_modulo_id, i.modulo_nombre, COUNT(i.id),
       COUNT(i.id) FILTER (WHERE i.estado = 'Convertido'),
       COUNT(i.id) FILTER (WHERE i.estado = 'Perdido'),
       COALESCE(SUM(m.monto), 0.00)
FROM intereses i
LEFT JOIN catalogo_cache o ON o.codigo = i.external_modulo_id
LEFT JOIN (SELECT origen.prospecto_id,sc.external_modulo_id,SUM(sc.precio_acordado) AS monto
FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON sc.id=ic.solicitud_compra_id
JOIN ordenes_pago op ON op.id=sc.orden_pago_id JOIN intereses origen ON origen.id=op.interes_id
GROUP BY origen.prospecto_id,sc.external_modulo_id) m ON m.prospecto_id=i.prospecto_id AND m.external_modulo_id=i.external_modulo_id
WHERE\s"""
              + Alcance.propio(u)
              + """

GROUP BY i.external_modulo_id, i.modulo_nombre
ORDER BY COUNT(i.id) DESC, i.external_modulo_id""",
          Alcance.params(u));
    }
  }
}
