package bo.uagrm.crm.aplicacion;

import java.util.List;

/** Comando recibido en el asunto de un correo, ya separado en nombre y parámetros. */
public final class Command {

    private final String nombre;
    private final Comando tipo;
    private final List<String> params;
    private final boolean valido;
    private final String mensajeError;
    private final String remitente;
    private final String adjunto;

    private Command(String nombre, Comando tipo, List<String> params, String mensajeError, String remitente, String adjunto) {
        this.nombre = nombre;
        this.tipo = tipo;
        this.params = params;
        this.valido = mensajeError == null;
        this.mensajeError = mensajeError;
        this.remitente = remitente;
        this.adjunto = adjunto;
    }

    static Command valido(Comando tipo, List<String> params, String remitente, String adjunto) {
        return new Command(tipo.name(), tipo, List.copyOf(params), null, remitente, adjunto);
    }

    static Command invalido(String nombre, String mensajeError, String remitente) {
        return new Command(nombre, null, List.of(), mensajeError, remitente, null);
    }

    public String getNombre() {
        return nombre;
    }

    public Comando getTipo() {
        return tipo;
    }

    /** Parámetro i, o null si no vino (los opcionales). */
    public String getParam(int i) {
        return i < params.size() && !params.get(i).isBlank() ? params.get(i) : null;
    }

    public int cantidad() {
        return params.size();
    }

    public boolean isValido() {
        return valido;
    }

    public String getMensajeError() {
        return mensajeError;
    }

    /** Correo del remitente (identidad de quien envía el comando). */
    public String getRemitente() {
        return remitente;
    }

    /** Contenido del .csv adjunto, o null. */
    public String getAdjunto() {
        return adjunto;
    }
}
