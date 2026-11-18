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
SELECT e.estado, COUNT(i.nro_tarjeta), COALESCE(ROUND(100.0 * COUNT(i.nro_tarjeta) / NULLIF(SUM(COUNT(i.nro_tarjeta)) OVER (), 0), 1), 0.0)
FROM (VALUES (1, 'Nuevo'), (2, 'En Contacto'), (3, 'Seguimiento'), (4, 'Promesa de Pago'),
             (5, 'Convertido'), (6, 'Perdido')) AS e (orden, estado)
LEFT JOIN (intereses i LEFT JOIN catalogo_cache o ON o.codigo = i.modulo_codigo) ON (CASE i.estado WHEN 'Contactado' THEN 'En Contacto' WHEN 'Interesado' THEN 'Seguimiento' WHEN 'Pago enviado' THEN 'Promesa de Pago' WHEN 'En revision' THEN 'Promesa de Pago' ELSE i.estado END) = e.estado AND\s"""
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
       COUNT(i.nro_tarjeta),
       COUNT(i.nro_tarjeta) FILTER (WHERE i.estado NOT IN ('Convertido', 'Perdido')),
       COUNT(i.nro_tarjeta) FILTER (WHERE i.estado = 'Convertido'),
       COUNT(i.nro_tarjeta) FILTER (WHERE i.estado = 'Perdido'),
       COALESCE(ROUND(100.0 * COUNT(i.nro_tarjeta) FILTER (WHERE i.estado = 'Convertido') / NULLIF(COUNT(i.nro_tarjeta), 0), 1), 0.0),
       COALESCE(SUM(m.monto), 0.00)
FROM usuarios v
LEFT JOIN (intereses i LEFT JOIN catalogo_cache o ON o.codigo = i.modulo_codigo) ON i.vendedor_correo = v.correo AND\s"""
              + Alcance.propio(u)
              + """

LEFT JOIN (SELECT origen.documento,sc.modulo_codigo,SUM(sc.precio_acordado) AS monto
FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON sc.nro_solicitud=ic.nro_solicitud
JOIN ordenes_pago op ON op.nro_orden=sc.nro_orden JOIN intereses origen ON origen.nro_tarjeta=op.nro_tarjeta
GROUP BY origen.documento,sc.modulo_codigo) m ON m.documento=i.documento AND m.modulo_codigo=i.modulo_codigo
WHERE v.rol='vendedor' AND (?::text='' OR v.correo=?)
GROUP BY v.correo
ORDER BY COUNT(i.nro_tarjeta) FILTER (WHERE i.estado = 'Convertido') DESC, v.correo""",
          Alcance.params(
              u,
              u.rol() == bo.uagrm.crm.dominio.usuarios.Rol.VENDEDOR ? u.correo() : "",
              u.rol() == bo.uagrm.crm.dominio.usuarios.Rol.VENDEDOR ? u.correo() : ""));
    }
  }

  public List<String[]> perdidasPorMotivo(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          """
SELECT mp.categoria, mp.nombre, COUNT(i.nro_tarjeta),
       COALESCE(ROUND(100.0 * COUNT(i.nro_tarjeta) / NULLIF(SUM(COUNT(i.nro_tarjeta)) OVER (), 0), 1), 0.0)
FROM motivos_perdida mp
LEFT JOIN (intereses i LEFT JOIN catalogo_cache o ON o.codigo = i.modulo_codigo) ON i.motivo_perdida = mp.nombre AND\s"""
              + Alcance.propio(u)
              + """

GROUP BY mp.nombre
ORDER BY COUNT(i.nro_tarjeta) DESC, mp.categoria, mp.nombre""",
          Alcance.params(u));
    }
  }

  public List<String[]> perdidasPorCategoria(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          """
SELECT mp.categoria, COUNT(i.nro_tarjeta),
       COALESCE(ROUND(100.0 * COUNT(i.nro_tarjeta) / NULLIF(SUM(COUNT(i.nro_tarjeta)) OVER (), 0), 1), 0.0)
FROM motivos_perdida mp
LEFT JOIN (intereses i LEFT JOIN catalogo_cache o ON o.codigo = i.modulo_codigo) ON i.motivo_perdida = mp.nombre AND\s"""
              + Alcance.propio(u)
              + """

GROUP BY mp.categoria
ORDER BY COUNT(i.nro_tarjeta) DESC, mp.categoria""",
          Alcance.params(u));
    }
  }

  public List<String[]> oferta(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          """
SELECT i.modulo_codigo, i.modulo_nombre, COUNT(i.nro_tarjeta),
       COUNT(i.nro_tarjeta) FILTER (WHERE i.estado = 'Convertido'),
       COUNT(i.nro_tarjeta) FILTER (WHERE i.estado = 'Perdido'),
       COALESCE(SUM(m.monto), 0.00)
FROM intereses i
LEFT JOIN catalogo_cache o ON o.codigo = i.modulo_codigo
LEFT JOIN (SELECT origen.documento,sc.modulo_codigo,SUM(sc.precio_acordado) AS monto
FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON sc.nro_solicitud=ic.nro_solicitud
JOIN ordenes_pago op ON op.nro_orden=sc.nro_orden JOIN intereses origen ON origen.nro_tarjeta=op.nro_tarjeta
GROUP BY origen.documento,sc.modulo_codigo) m ON m.documento=i.documento AND m.modulo_codigo=i.modulo_codigo
WHERE\s"""
              + Alcance.propio(u)
              + """

GROUP BY i.modulo_codigo, i.modulo_nombre
ORDER BY COUNT(i.nro_tarjeta) DESC, i.modulo_codigo""",
          Alcance.params(u));
    }
  }
}
