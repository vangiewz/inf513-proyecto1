package bo.uagrm.crm.dominio;

import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;

/**
 * Qué tarjetas (intereses i) ve cada rol, como condición SQL con parámetros enlazados (?). RF1.2:
 * el vendedor ve solo su cartera (la bolsa común la reparten coordinación y administración). RF1.3:
 * el coordinador, solo los programas a su cargo. El valor del ? lo dan params()/despues(), en el mismo orden en que aparece la condición
 * en la consulta.
 */
public final class Alcance {

  private Alcance() {}

  /** Condición SQL del alcance del rol sobre la tarjeta i (para consultar, modificar y medir). */
  public static String propio(Usuario u) {
    return switch (u.rol()) {
      case ADMINISTRADOR -> "TRUE";
      case COORDINADOR ->
          "EXISTS (SELECT 1 FROM catalogo_cache ac JOIN programas_responsables pr ON"
              + " pr.programa_codigo=ac.programa_codigo WHERE ac.codigo=i.modulo_codigo"
              + " AND pr.coordinador_correo=?)";
      case VENDEDOR -> "i.vendedor_correo = ?";
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
    Object[] propios = u.rol() == Rol.ADMINISTRADOR ? new Object[0] : new Object[] {u.correo()};
    Object[] out = new Object[antes.length + propios.length + despues.length];
    System.arraycopy(antes, 0, out, 0, antes.length);
    System.arraycopy(propios, 0, out, antes.length, propios.length);
    System.arraycopy(despues, 0, out, antes.length + propios.length, despues.length);
    return out;
  }
}
