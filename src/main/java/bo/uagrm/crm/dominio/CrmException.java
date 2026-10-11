package bo.uagrm.crm.dominio;

/** Error de negocio o de formato: su mensaje se responde tal cual al remitente. */
public class CrmException extends Exception {
    public CrmException(String mensaje) {
        super(mensaje);
    }
}
