package bo.uagrm.crm.dominio;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validación de los parámetros que llegan en el asunto. Los mensajes van en español al remitente. */
public final class Valida {

    private static final Pattern CORREO = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern TELEFONO = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final Pattern CI = Pattern.compile("^[0-9]{5,10}(-?[0-9A-Z]{1,3})?$");

    private Valida() {
    }

    /** Sin tildes, en minúsculas y sin espacios extremos: para comparar lo que escribe el usuario. */
    public static String normal(String s) {
        return Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Devuelve la opción canónica que coincide (sin importar mayúsculas ni tildes). */
    public static String elegir(String valor, List<String> opciones, String campo) throws CrmException {
        String v = normal(valor == null ? "" : valor);
        for (String o : opciones) if (normal(o).equals(v)) return o;
        throw new CrmException("El " + campo + " \"" + valor + "\" no es válido. Use uno de: " + String.join(", ", opciones) + ".");
    }

    public static String texto(String valor, String campo, int max) throws CrmException {
        String v = valor == null ? "" : valor.trim();
        if (v.isEmpty()) throw new CrmException("El campo " + campo + " es obligatorio.");
        if (v.length() > max) throw new CrmException("El campo " + campo + " admite hasta " + max + " caracteres.");
        return v;
    }

    public static String opcional(String valor, String campo, int max) throws CrmException {
        return valor == null || valor.isBlank() ? null : texto(valor, campo, max);
    }

    public static String correo(String valor) throws CrmException {
        String v = texto(valor, "correo", 120).toLowerCase(Locale.ROOT);
        if (!esCorreo(v)) throw new CrmException("El correo \"" + valor + "\" no tiene un formato válido.");
        return v;
    }

    public static String correoOpcional(String valor) throws CrmException {
        return valor == null || valor.isBlank() ? null : correo(valor);
    }

    public static String telefono(String valor) throws CrmException {
        String v = texto(valor, "teléfono", 20).replaceAll("[\\s-]", "");
        if (!esTelefono(v)) throw new CrmException("El teléfono \"" + valor + "\" debe tener de 7 a 15 dígitos.");
        return v;
    }

    public static String ci(String valor) throws CrmException {
        String v = texto(valor, "CI", 15).toUpperCase(Locale.ROOT);
        if (!esCi(v)) throw new CrmException("El CI \"" + valor + "\" no es válido (ejemplo: 4715292 o 4715292-1B).");
        return v;
    }

    public static int entero(String valor, String campo) throws CrmException {
        try {
            int n = Integer.parseInt(valor.trim().replaceFirst("^#", ""));
            if (n > 0) return n;
        } catch (NumberFormatException | NullPointerException ignored) {
            // se informa abajo
        }
        throw new CrmException("El campo " + campo + " debe ser un número entero positivo (se recibió \"" + valor + "\").");
    }

    public static boolean esCorreo(String v) {
        return v != null && CORREO.matcher(v).matches();
    }

    public static boolean esTelefono(String v) {
        return v != null && TELEFONO.matcher(v).matches();
    }

    public static boolean esCi(String v) {
        return v != null && CI.matcher(v).matches();
    }
}
