package bo.uagrm.crm.aplicacion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee el asunto: NOMBRE["param1","param2",...]. Acepta [] y ["*"] sin parámetros (como LISPER["*"]
 * del enunciado), números sin comillas y las comillas tipográficas que ponen los celulares.
 */
public final class CommandParser {

  private static final Pattern FORMA =
      Pattern.compile("^\\s*([A-Za-z]+)\\s*\\[(.*)\\]\\s*$", Pattern.DOTALL);
  private static final Pattern INICIO = Pattern.compile("^\\s*([A-Za-z]+)\\s*\\[");
  private static final String AYUDA = " Envíe HELP[] para ver los comandos de su rol.";

  public Command parse(String asunto, String remitente, String adjunto) {
    String a = asunto == null ? "" : asunto.replaceAll("[“”„‟«»]", "\"");
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
    return asunto != null && (FORMA.matcher(asunto).matches() || INICIO.matcher(asunto).find());
  }

  static List<String> parametros(String texto) {
    List<String> out = new ArrayList<>();
    String t = texto.trim();
    if (t.isEmpty()) return out;
    int i = 0, n = t.length();
    while (true) {
      while (i < n && Character.isWhitespace(t.charAt(i))) i++;
      StringBuilder sb = new StringBuilder();
      if (i < n && t.charAt(i) == '"') {
        i++;
        while (true) {
          if (i >= n)
            throw new IllegalArgumentException("Falta cerrar una comilla en los parámetros.");
          char ch = t.charAt(i++);
          if (ch != '"') sb.append(ch);
          else if (i < n && t.charAt(i) == '"') {
            sb.append('"');
            i++;
          } else break;
        }
        while (i < n && Character.isWhitespace(t.charAt(i))) i++;
      } else {
        while (i < n && t.charAt(i) != ',') sb.append(t.charAt(i++));
      }
      out.add(sb.toString().trim());
      if (i >= n) return out;
      if (t.charAt(i) != ',')
        throw new IllegalArgumentException(
            "Los parámetros van entre comillas y separados por comas.");
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
