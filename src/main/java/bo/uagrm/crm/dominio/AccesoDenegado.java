package bo.uagrm.crm.dominio;

/**
 * El dato existe pero está fuera del alcance del rol (RF1.2: cartera del vendedor; RF1.3: programas
 * del coordinador). Se responde "Denegado", igual que un comando no permitido al rol.
 */
public class AccesoDenegado extends CrmException {
  public AccesoDenegado(String mensaje) {
    super(mensaje);
  }
}
