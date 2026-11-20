package bo.uagrm.crm.presentacion;

import bo.uagrm.crm.aplicacion.Comando;
import bo.uagrm.crm.aplicacion.Command;
import bo.uagrm.crm.aplicacion.CommandParser;
import java.util.ArrayList;
import java.util.List;

/** Ajusta únicamente el texto visible de la ayuda en la respuesta HTML enviada por correo. */
final class AyudaHtml {
  private AyudaHtml() {}

  static String ajustar(String asunto, String html) {
    if (asunto == null || html == null || !asunto.startsWith("Ayuda: ")) return html;

    if (asunto.startsWith("Ayuda: comandos del rol ")) {
      return ajustarListado(html);
    }

    try {
      return ajustarComando(html, Comando.valueOf(asunto.substring("Ayuda: ".length())));
    } catch (IllegalArgumentException e) {
      return html;
    }
  }

  private static String ajustarListado(String html) {
    String comienzoTabla = "<div style=\"overflow-x:auto;\"><table";
    String cierreTabla = "</table></div>";
    int inicio = html.indexOf(comienzoTabla);
    if (inicio < 0) return html;
    int fin = html.indexOf(cierreTabla, inicio);
    if (fin < 0) return html;
    fin += cierreTabla.length();
    String tablaOriginal = html.substring(inicio, fin);

    StringBuilder tabla = new StringBuilder("<div style=\"overflow-x:auto;\"><table cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;font-size:13px;line-height:18px;font-variant-numeric:tabular-nums;\"><thead><tr>");
    encabezado(tabla, "Comando y ejemplo");
    encabezado(tabla, "Descripción");
    tabla.append("</tr></thead><tbody>");
    String grupo = "";
    int fila = 0;
    for (Comando comando : Comando.values()) {
      if (!tablaOriginal.contains(">" + escapar(comando.firma()) + "</td>")) continue;
      if (!grupo.equals(comando.cu)) {
        grupo = comando.cu;
        tabla.append("<tr><td colspan=\"2\" style=\"padding:9px 10px;background:#E6EEF8;color:#1D3F73;font-weight:700;border-bottom:1px solid #D5DBE3;\">")
            .append(escapar(grupo.equals("General") ? "General" : "Caso de uso " + grupo))
            .append("</td></tr>");
      }
      List<String> variantes = variantes(comando);
      tabla.append("<tr style=\"background:").append(fila++ % 2 == 1 ? "#F7F9FB" : "#FFFFFF").append(";\">");
      tabla.append("<td valign=\"top\" style=\"padding:8px 10px;word-break:break-word;overflow-wrap:anywhere;border-bottom:1px solid #D5DBE3;\"><strong style=\"color:#1B2430;font-weight:700;\">")
          .append(escapar(variantes.get(0))).append("</strong>");
      for (int i = 1; i < variantes.size(); i++)
        tabla.append("<div style=\"margin-top:4px;color:#5F6B7A;\">")
            .append(escapar(variantes.get(i))).append("</div>");
      tabla.append("</td><td valign=\"top\" style=\"padding:8px 10px;border-bottom:1px solid #D5DBE3;\">")
          .append(escapar(comando.descripcion + detalleParametros(comando)))
          .append("</td></tr>");
    }
    tabla.append("</tbody></table></div>");
    return html.substring(0, inicio) + tabla + html.substring(fin);
  }

  private static void encabezado(StringBuilder tabla, String texto) {
    tabla.append("<th align=\"left\" style=\"padding:8px 10px;background:#EEF2F7;color:#1B2430;font-weight:600;border-bottom:1px solid #D5DBE3;\">")
        .append(escapar(texto)).append("</th>");
  }

  private static List<String> variantes(Comando comando) {
    String ejemplo = comando.ejemplo.substring(0, comando.ejemplo.indexOf(']') + 1);
    Command muestra = new CommandParser().parse(ejemplo, "prueba@example.test", null);
    if (!muestra.isValido()) throw new IllegalStateException("Ejemplo inválido: " + comando.name());
    List<String> valores = new ArrayList<>();
    for (int i = 0; i < muestra.cantidad(); i++) valores.add(muestra.getParam(i));
    if (comando == Comando.HELP) valores.set(0, "LISTARUSUARIOS");
    String sencillo = asunto(comando, valores.subList(0, comando.minimos()));
    if (comando.minimos() == comando.parametros.length) return List.of(sencillo);
    for (int i = valores.size(); i < comando.parametros.length; i++)
      valores.add(valorOpcional(comando.parametros[i]));
    String completo = asunto(comando, valores);
    return sencillo.equals(completo) ? List.of(sencillo) : List.of(sencillo, completo);
  }

  private static String asunto(Comando comando, List<String> valores) {
    List<String> citados = new ArrayList<>();
    for (String valor : valores)
      citados.add("\"" + valor.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    return comando.name() + "[" + String.join(",", citados) + "]";
  }

  private static String valorOpcional(String parametro) {
    return switch (parametro) {
      case "correo_nuevo?" -> "ana.nueva@example.com";
      case "profesion?" -> "Estudiante";
      case "ubicacion?" -> "Santa Cruz";
      case "codigos_descuentos?" -> "3_MAS_MODULOS";
      case "estado_pago?" -> "En revision";
      case "observacion?" -> "Comprobante recibido";
      case "nro_comprobante?", "nro_solicitud?" -> "1";
      default -> throw new IllegalStateException("Falta ejemplo opcional: " + parametro);
    };
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
