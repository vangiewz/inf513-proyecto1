package bo.uagrm.crm.aplicacion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee el asunto: NOMBRE["param1","param2",...]. Todo parámetro va entre comillas dobles (enunciado:
 * si faltan comillas, Error). Acepta [] y ["*"] sin parámetros, espacios de más, minúsculas, los
 * prefijos Re:/RV:/Fwd:, las comillas tipográficas que ponen los celulares y, dentro de un
 * parámetro, comillas escapadas como "" o \" (y la barra como \\).
 */
public final class CommandParser {

  private static final Pattern FORMA =
      Pattern.compile("^\\s*([A-Za-z]+)\\s*\\[(.*)\\]\\s*$", Pattern.DOTALL);
  private static final Pattern INICIO = Pattern.compile("^\\s*([A-Za-z]+)\\s*\\[");
  private static final Pattern SOLO = Pattern.compile("^\\s*([A-Za-z]+)\\s*$");
  private static final String AYUDA = " Envíe HELP[] para ver los comandos de su rol.";
  /** Prefijos que agregan Gmail, Outlook y Thunderbird al responder o reenviar (Re:, RV:, Fwd:...). */
  private static final Pattern PREFIJO =
      Pattern.compile("^\\s*((re|rv|res|fw|fwd|enc|tr|aw|wg)\\s*:\\s*)+", Pattern.CASE_INSENSITIVE);

  /** Asunto sin prefijos de respuesta y con comillas tipográficas normalizadas. */
  static String limpiar(String asunto) {
    return asunto == null
        ? ""
        : PREFIJO.matcher(asunto).replaceFirst("").replaceAll("[“”„‟«»]", "\"");
  }

  public Command parse(String asunto, String remitente, String adjunto) {
    String a = limpiar(asunto);
    Matcher m = FORMA.matcher(a);
    if (!m.matches()) {
      Matcher inicio = INICIO.matcher(a);
      if (inicio.find()) { // empieza como comando pero está incompleto: se indica su forma correcta
        String nombre = inicio.group(1).toUpperCase(Locale.ROOT);
        for (Comando k : Comando.values())
          if (k.name().equals(nombre))
            return Command.invalido(
                nombre,
                "El comando está incompleto: falta cerrar el corchete ] o una comilla. Forma"
                    + " correcta: "
                    + k.firma()
                    + ". Ejemplo: "
                    + k.ejemplo,
                remitente);
      }
      Matcher solo = SOLO.matcher(a);
      Comando k = solo.matches() ? conocido(solo.group(1)) : null;
      if (k != null)
        return Command.invalido(
            k.name(),
            "Faltan los corchetes [ ]. Forma correcta: " + k.firma() + ". Ejemplo: " + k.ejemplo,
            remitente);
      return Command.invalido(
          "Asunto",
          "El asunto debe tener la forma COMANDO[\"parámetro\",...], por ejemplo LISTAROFERTA[]."
              + AYUDA,
          remitente);
    }
    String nombre = m.group(1).toUpperCase(Locale.ROOT);
    Comando tipo;
    try {
      tipo = Comando.valueOf(nombre);
    } catch (IllegalArgumentException e) {
      return Command.invalido(nombre, "El comando " + nombre + " no existe." + AYUDA, remitente);
    }
    List<String> params;
    try {
      params = parametros(m.group(2));
    } catch (IllegalArgumentException e) {
      return Command.invalido(
          nombre, e.getMessage() + " Forma correcta: " + tipo.firma(), remitente);
    }
    if (params.size() == 1 && params.get(0).equals("*")) params = List.of();
    if (params.size() < tipo.minimos() || params.size() > tipo.parametros.length)
      return Command.invalido(
          nombre,
          nombre
              + " recibe "
              + cuantos(tipo)
              + " y llegaron "
              + params.size()
              + ". Forma correcta: "
              + tipo.firma()
              + ". Ejemplo: "
              + tipo.ejemplo,
          remitente);
    return Command.valido(tipo, params, remitente, adjunto);
  }

  /**
   * ¿El asunto tiene forma de comando? Lo que no la tiene y viene de un desconocido no se responde
   * (spam).
   */
  public static boolean pareceComando(String asunto) {
    String a = limpiar(asunto);
    if (FORMA.matcher(a).matches() || INICIO.matcher(a).find()) return true;
    Matcher solo = SOLO.matcher(a); // un comando conocido escrito sin corchetes también se responde
    return solo.matches() && conocido(solo.group(1)) != null;
  }

  private static Comando conocido(String nombre) {
    try {
      return Comando.valueOf(nombre.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  static List<String> parametros(String texto) {
    List<String> out = new ArrayList<>();
    String t = texto.trim();
    if (t.isEmpty()) return out;
    int i = 0, n = t.length();
    while (true) {
      int numero = out.size() + 1;
      while (i < n && Character.isWhitespace(t.charAt(i))) i++;
      if (i >= n)
        throw new IllegalArgumentException("Falta el parámetro " + numero + " después de la coma.");
      if (t.charAt(i) == '[' || t.charAt(i) == ']')
        throw new IllegalArgumentException("Sobra un corchete " + t.charAt(i) + " en los parámetros.");
      StringBuilder sb = new StringBuilder();
      if (t.charAt(i) == '"') {
        i++;
        while (true) {
          if (i >= n)
            throw new IllegalArgumentException("Falta cerrar la comilla del parámetro " + numero + ".");
          char ch = t.charAt(i++);
          if (ch == '\\' && i < n && (t.charAt(i) == '"' || t.charAt(i) == '\\'))
            sb.append(t.charAt(i++)); // \" y \\ escapados dentro del parámetro
          else if (ch != '"') sb.append(ch);
          else if (i < n && t.charAt(i) == '"') {
            sb.append('"');
            i++;
          } else break;
        }
        while (i < n && Character.isWhitespace(t.charAt(i))) i++;
      } else {
        while (i < n && t.charAt(i) != ',') sb.append(t.charAt(i++));
        throw new IllegalArgumentException(
            "El parámetro " + numero + " (" + sb.toString().trim() + ") debe ir entre comillas dobles.");
      }
      out.add(sb.toString().trim());
      if (i >= n) return out;
      if (t.charAt(i) != ',')
        throw new IllegalArgumentException(
            "Después del parámetro " + numero + " falta una coma: los parámetros van entre comillas"
                + " y separados por comas.");
      i++;
    }
  }

  private static String cuantos(Comando c) {
    int min = c.minimos(), max = c.parametros.length;
    if (max == 0) return "ningún parámetro";
    return (min == max ? String.valueOf(max) : min + " a " + max)
        + (max == 1 ? " parámetro" : " parámetros");
  }
}
