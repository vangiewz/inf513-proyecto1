package bo.uagrm.crm.dominio.usuarios;

/** Roles del CRM: los mismos de crm-web (Usuario::ROLES) y de RF1.1. */
public enum Rol {
    ADMINISTRADOR, COORDINADOR, VENDEDOR;

    /** Valor guardado en usuario.rol. */
    public String nombre() {
        return name().toLowerCase();
    }

    public static Rol deNombre(String rol) {
        return valueOf(rol.trim().toUpperCase());
    }
}
