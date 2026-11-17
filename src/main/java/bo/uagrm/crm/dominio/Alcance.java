package bo.uagrm.crm.dominio;

import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;

/**
 * Qué tarjetas (interes i JOIN oferta o) ve cada rol, como condición SQL con parámetros enlazados
 * (?). RF1.2: el vendedor ve su cartera y la bolsa común. RF1.3: el coordinador, solo las ofertas a
 * su cargo. El valor del ? lo dan params()/despues(), en el mismo orden en que aparece la condición
 * en la consulta.
 */
public final class Alcance {

  private Alcance() {}

  /** Para consultar (listados, bolsa común). */
  public static String visible(Usuario u) {
    return switch (u.rol()) {
      case ADMINISTRADOR -> "TRUE";
      case COORDINADOR ->
          "EXISTS (SELECT 1 FROM catalogo_cache ac JOIN programas_responsables pr ON"
              + " pr.external_programa_id=ac.programa_codigo WHERE ac.codigo=i.external_modulo_id"
              + " AND pr.coordinador_id=?)";
      case VENDEDOR -> "i.vendedor_id = ?";
    };
  }

  /** Para modificar y para métricas: sin la bolsa común en el caso del vendedor. */
  public static String propio(Usuario u) {
    return switch (u.rol()) {
      case ADMINISTRADOR -> "TRUE";
      case COORDINADOR ->
          "EXISTS (SELECT 1 FROM catalogo_cache ac JOIN programas_responsables pr ON"
              + " pr.external_programa_id=ac.programa_codigo WHERE ac.codigo=i.external_modulo_id"
              + " AND pr.coordinador_id=?)";
      case VENDEDOR -> "i.vendedor_id = ?";
    };
  }

  /** Parámetros cuando la condición de alcance va antes que los demás ? de la consulta. */
  public static Object[] params(Usuario u, Object... despues) {
    return unir(new Object[0], u, despues);
  }

  /** Parámetros cuando la condición de alcance va después de los demás ? de la consulta. */
  public static Object[] despues(Usuario u, Object... antes) {
    return unir(antes, u, new Object[0]);
  }

  private static Object[] unir(Object[] antes, Usuario u, Object[] despues) {
    Object[] propios = u.rol() == Rol.ADMINISTRADOR ? new Object[0] : new Object[] {u.id()};
    Object[] out = new Object[antes.length + propios.length + despues.length];
    System.arraycopy(antes, 0, out, 0, antes.length);
    System.arraycopy(propios, 0, out, antes.length, propios.length);
    System.arraycopy(despues, 0, out, antes.length + propios.length, despues.length);
    return out;
  }
}
