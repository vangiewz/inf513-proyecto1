package bo.uagrm.crm.aplicacion;

import bo.uagrm.crm.aplicacion.Respuesta.Bloque;
import bo.uagrm.crm.aplicacion.Respuesta.Carril;
import bo.uagrm.crm.aplicacion.Respuesta.Tabla;
import java.util.List;
import java.util.Map;

/**
 * Arma las respuestas (success, successTable, error, denegado) y las convierte en correo: HTML con
 * estilos en línea (lo que admite Gmail) y una versión de texto plano.
 */
public final class ResponseFormatter {

  // Tokens: neutros fríos, azul UAGRM como único acento y un color por resultado.
  private static final String FUENTE = "-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
  private static final String MONO = "Consolas,'SFMono-Regular',Menlo,monospace";
  private static final String TINTA = "#1B2430";
  private static final String SECUNDARIO = "#4A5565";
  private static final String PIE = "#5F6B7A";
  private static final String LINEA = "#D5DBE3";
  private static final String ENCABEZADO = "#EEF2F7";
  private static final String CEBRA = "#F7F9FB";
  private static final String ACENTO = "#1D3F73";
  private static final String PISTA = "#E6EAF0";

  private static final Map<String, String> RESULTADO =
      Map.of("Éxito", "#1E6B3A", "Error", "#A12A1F", "Denegado", "#8A5300", "Aviso", ACENTO);

  /** Etapa del embudo → {fondo, texto}. */
  private static final Map<String, String[]> ETAPA =
      Map.of(
          "En Contacto", new String[] {"#E4F1F6", "#0E5A73"},
          "Seguimiento", new String[] {"#EEE9F7", "#4B3A85"},
          "Promesa de Pago", new String[] {"#FDF3E1", "#7A4A00"},
          "Nuevo", new String[] {"#E6EEF8", "#1D3F73"},
          "Contactado", new String[] {"#E4F1F6", "#0E5A73"},
          "Interesado", new String[] {"#EEE9F7", "#4B3A85"},
          "Pago enviado", new String[] {"#FDF3E1", "#7A4A00"},
          "En revision", new String[] {"#FDF3E1", "#7A4A00"},
          "Convertido", new String[] {"#E8F3EC", "#1E6B3A"},
          "Perdido", new String[] {"#F3EAEA", "#8C2D24"});

  // ---- Construcción de respuestas ----

  public Respuesta success(String titulo, String mensaje) {
    return new Respuesta("Éxito", titulo, mensaje, List.of(), List.of());
  }

  public Respuesta successTable(
      String titulo, String mensaje, String[] columnas, List<String[]> filas) {
    return successTable(titulo, mensaje, new Tabla(null, columnas, filas));
  }

  public Respuesta successTable(String titulo, String mensaje, Bloque... bloques) {
    return new Respuesta("Éxito", titulo, mensaje, List.of(bloques), List.of());
  }

  public Respuesta error(String comando, String mensaje) {
    return new Respuesta("Error", "Error: " + comando, mensaje, List.of(), List.of());
  }

  public Respuesta denegado(String comando, String mensaje) {
    return new Respuesta("Denegado", "Denegado: " + comando, mensaje, List.of(), List.of());
  }

  public Respuesta aviso(String titulo, String mensaje, Bloque... bloques) {
    return new Respuesta("Aviso", titulo, mensaje, List.of(bloques), List.of());
  }

  // ---- Correo HTML ----

  public String html(Respuesta r, String asuntoRecibido) {
    String color = RESULTADO.getOrDefault(r.resultado(), ACENTO);
    StringBuilder b = new StringBuilder(4096);
    b.append("<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"UTF-8\">")
        .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        .append("<meta name=\"color-scheme\" content=\"light dark\"><title>")
        .append(esc(r.titulo()))
        .append("</title></head>")
        .append("<body style=\"margin:0;padding:0;background:#FFFFFF;\">")
        .append(
            "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
                + " style=\"border-collapse:collapse;table-layout:fixed;\"><tr><td align=\"center\""
                + " style=\"padding:16px 12px;\">")
        .append(
            "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
                + " style=\"max-width:760px;border-collapse:collapse;table-layout:fixed;font-family:")
        .append(FUENTE)
        .append(";color:")
        .append(TINTA)
        .append(";\">")
        .append("<tr><td style=\"height:4px;line-height:4px;font-size:0;background:")
        .append(color)
        .append(";\">&nbsp;</td></tr>")
        .append("<tr><td style=\"padding:20px 4px 4px 4px;\">")
        .append("<h1 style=\"margin:0;font-size:20px;line-height:26px;font-weight:600;color:")
        .append(color)
        .append(";\">")
        .append(esc(r.titulo()))
        .append("</h1></td></tr>");
    if (r.mensaje() != null && !r.mensaje().isBlank())
      b.append("<tr><td style=\"padding:8px 4px 4px 4px;font-size:15px;line-height:22px;color:")
          .append(TINTA)
          .append(";\">")
          .append(esc(r.mensaje()).replace("\n", "<br>"))
          .append("</td></tr>");
    for (Bloque bloque : r.bloques()) {
      b.append("<tr><td style=\"padding:20px 4px 0 4px;\">");
      if (bloque instanceof Tabla t) tabla(b, t);
      else if (bloque instanceof Carril c) carril(b, c);
      b.append("</td></tr>");
    }
    b.append("<tr><td style=\"padding:28px 4px 0 4px;\"><div style=\"border-top:1px solid ")
        .append(LINEA)
        .append(";padding-top:12px;font-size:12px;line-height:18px;color:")
        .append(PIE)
        .append(";\">");
    if (asuntoRecibido != null)
      b.append("Comando recibido: <span style=\"font-family:")
          .append(MONO)
          .append(";color:")
          .append(SECUNDARIO)
          .append(";\">")
          .append(esc(asuntoRecibido))
          .append("</span><br>");
    b.append("CRM vía e-mail · Grupo 05 SA · INF513 Tecnología Web · UAGRM FICCT<br>")
        .append("Envíe HELP[] para ver sus comandos.</div></td></tr>")
        .append("</table></td></tr></table></body></html>");
    return b.toString();
  }

  /** Insignia de una etapa del embudo; cualquier otro texto se devuelve escapado, sin insignia. */
  public String estadoBadge(String estado) {
    String[] c = ETAPA.get(estado);
    if (c == null) return esc(estado);
    return "<span style=\"display:inline-block;padding:2px 8px;border-radius:10px;background:"
        + c[0]
        + ";color:"
        + c[1]
        + ";font-size:12px;line-height:16px;font-weight:600;white-space:nowrap;\">"
        + esc(estado)
        + "</span>";
  }

  private void tabla(StringBuilder b, Tabla t) {
    if (t.titulo() != null)
      b.append(
              "<h2 style=\"margin:0 0 8px 0;font-size:15px;line-height:20px;font-weight:600;color:")
          .append(ACENTO)
          .append(";\">")
          .append(esc(t.titulo()))
          .append("</h2>");
    if (t.filas().isEmpty()) {
      b.append("<p style=\"margin:0;font-size:14px;line-height:20px;color:")
          .append(SECUNDARIO)
          .append(";\">Sin registros.</p>");
      return;
    }
    int barra = -1;
    for (int i = 0; i < t.columnas().length; i++) if (t.columnas()[i].endsWith("%")) barra = i;
    b.append(
            "<div style=\"overflow-x:auto;\"><table cellpadding=\"0\" cellspacing=\"0\""
                + " style=\"width:100%;border-collapse:collapse;font-size:13px;line-height:18px;")
        .append("font-variant-numeric:tabular-nums;\"><thead><tr>");
    for (String col : t.columnas())
      b.append("<th align=\"left\" style=\"padding:8px 10px;background:")
          .append(ENCABEZADO)
          .append(";color:")
          .append(TINTA)
          .append(";font-weight:600;border-bottom:1px solid ")
          .append(LINEA)
          .append(";\">")
          .append(esc(col))
          .append("</th>");
    b.append("</tr></thead><tbody>");
    for (int f = 0; f < t.filas().size(); f++) {
      String[] fila = t.filas().get(f);
      b.append("<tr style=\"background:").append(f % 2 == 1 ? CEBRA : "#FFFFFF").append(";\">");
      for (int i = 0; i < fila.length; i++) {
        // Las firmas de comando (primera columna) no tienen espacios: sin cortarlas la tabla se desborda y Gmail
        // recorta columnas. Las demás columnas no se cortan a mitad de palabra.
        b.append("<td valign=\"top\" style=\"padding:8px 10px;")
            .append(i == 0 ? "word-break:break-word;overflow-wrap:anywhere;" : "")
            .append("border-bottom:1px solid ")
            .append(LINEA)
            .append(";\">");
        if (i == barra) barra(b, fila[i]);
        else b.append(estadoBadge(fila[i]).replace("\n", "<br>"));
        b.append("</td>");
      }
      b.append("</tr>");
    }
    b.append("</tbody></table></div>");
  }

  private void barra(StringBuilder b, String valor) {
    double pct;
    try {
      pct = Math.max(0, Math.min(100, Double.parseDouble(valor)));
    } catch (NumberFormatException e) {
      b.append(esc(valor));
      return;
    }
    int ancho = (int) Math.round(pct * 0.64); // 100 % = 64 px
    b.append("<table cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;\"><tr>")
        .append(
            "<td style=\"width:64px;padding:0;\"><div style=\"width:64px;height:8px;background:")
        .append(PISTA)
        .append(";border-radius:4px;\"><div style=\"width:")
        .append(ancho)
        .append("px;height:8px;background:")
        .append(ACENTO)
        .append(";border-radius:4px;\"></div></div></td>")
        .append("<td style=\"padding:0 0 0 8px;white-space:nowrap;\">")
        .append(esc(valor))
        .append(" %</td></tr></table>");
  }

  private void carril(StringBuilder b, Carril c) {
    b.append("<div style=\"margin:0 0 8px 0;\">")
        .append(estadoBadge(c.estado()))
        .append("<span style=\"font-size:13px;color:")
        .append(SECUNDARIO)
        .append(";padding-left:8px;\">")
        .append(c.tarjetas().size())
        .append(c.tarjetas().size() == 1 ? " tarjeta" : " tarjetas")
        .append("</span></div>");
    if (c.tarjetas().isEmpty()) {
      b.append("<p style=\"margin:0;font-size:13px;line-height:18px;color:")
          .append(PIE)
          .append(";\">Sin tarjetas en esta etapa.</p>");
      return;
    }
    for (String[] tarjeta : c.tarjetas()) {
      b.append("<div style=\"border:1px solid ")
          .append(LINEA)
          .append(";border-radius:6px;padding:8px 10px;margin:0 0 6px 0;")
          .append("font-size:13px;line-height:18px;\">");
      for (int i = 0; i < tarjeta.length; i++) {
        if (i == 0)
          b.append("<strong style=\"color:")
              .append(TINTA)
              .append(";\">")
              .append(esc(tarjeta[i]))
              .append("</strong>");
        else
          b.append("<br><span style=\"color:")
              .append(SECUNDARIO)
              .append(";\">")
              .append(esc(tarjeta[i]))
              .append("</span>");
      }
      b.append("</div>");
    }
  }

  // ---- Texto plano (parte alternativa del correo) ----

  public String texto(Respuesta r, String asuntoRecibido) {
    StringBuilder b = new StringBuilder();
    b.append(r.titulo()).append("\n\n");
    if (r.mensaje() != null && !r.mensaje().isBlank()) b.append(r.mensaje()).append("\n\n");
    for (Bloque bloque : r.bloques()) {
      if (bloque instanceof Tabla t) {
        if (t.titulo() != null) b.append(t.titulo()).append('\n');
        b.append(String.join(" | ", t.columnas())).append('\n');
        for (String[] f : t.filas())
          b.append(String.join(" | ", f).replace("\n", " / ")).append('\n');
        if (t.filas().isEmpty()) b.append("Sin registros.\n");
      } else if (bloque instanceof Carril c) {
        b.append("== ")
            .append(c.estado())
            .append(" (")
            .append(c.tarjetas().size())
            .append(") ==\n");
        for (String[] t : c.tarjetas()) b.append("- ").append(String.join(" / ", t)).append('\n');
      }
      b.append('\n');
    }
    if (asuntoRecibido != null) b.append("Comando recibido: ").append(asuntoRecibido).append('\n');
    return b.append(
            "CRM vía e-mail · Grupo 05 SA · INF513 Tecnología Web · UAGRM FICCT\n")
        .toString();
  }

  static String esc(String s) {
    if (s == null) return "";
    StringBuilder b = new StringBuilder(s.length());
    for (char ch : s.toCharArray()) {
      switch (ch) {
        case '<' -> b.append("&lt;");
        case '>' -> b.append("&gt;");
        case '&' -> b.append("&amp;");
        case '"' -> b.append("&quot;");
        case '\'' -> b.append("&#39;");
        default -> b.append(ch);
      }
    }
    return b.toString();
  }
}
