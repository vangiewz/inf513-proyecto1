package bo.uagrm.crm.presentacion;

import bo.uagrm.crm.aplicacion.Comando;
import java.util.ArrayList;
import java.util.List;

/** Ajusta únicamente el texto visible de la ayuda en la respuesta HTML enviada por correo. */
final class AyudaHtml {
  private AyudaHtml() {}

  static String ajustar(String asunto, String html) {
    if (asunto == null || html == null || !asunto.startsWith("Ayuda: ")) return html;

    if (asunto.startsWith("Ayuda: comandos del rol ")) {
      for (Comando comando : Comando.values()) html = ajustarComando(html, comando);
      return html;
    }

    try {
      return ajustarComando(html, Comando.valueOf(asunto.substring("Ayuda: ".length())));
    } catch (IllegalArgumentException e) {
      return html;
    }
  }

  private static String ajustarComando(String html, Comando comando) {
    String firmaOriginal = escapar(comando.firma());
    if (!html.contains(firmaOriginal)) return html;
    html = html.replace(firmaOriginal, escapar(firmaVisible(comando)));
    String detalle = detalleParametros(comando);
    return detalle.isEmpty()
        ? html
        : html.replace(escapar(comando.descripcion), escapar(comando.descripcion + detalle));
  }

  private static String firmaVisible(Comando comando) {
    StringBuilder firma = new StringBuilder(comando.name()).append('[');
    for (int i = 0; i < comando.parametros.length; i++) {
      if (i > 0) firma.append(',');
      firma.append('"').append(comando.parametros[i].replace("?", "")).append('"');
    }
    return firma.append(']').toString();
  }

  private static String detalleParametros(Comando comando) {
    List<String> obligatorios = new ArrayList<>();
    List<String> opcionales = new ArrayList<>();
    for (String parametro : comando.parametros) {
      if (parametro.endsWith("?")) opcionales.add(parametro.substring(0, parametro.length() - 1));
      else obligatorios.add(parametro);
    }
    StringBuilder detalle = new StringBuilder();
    if (!obligatorios.isEmpty()) detalle.append(" Obligatorios: ").append(String.join(", ", obligatorios)).append('.');
    if (!opcionales.isEmpty()) detalle.append(" Opcionales: ").append(String.join(", ", opcionales)).append('.');
    if (comando == Comando.LISTARUSUARIOS)
      detalle.append(" Sin filtro, enviar LISTARUSUARIOS[].");
    return detalle.toString();
  }

  private static String escapar(String texto) {
    return texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;");
  }
}
