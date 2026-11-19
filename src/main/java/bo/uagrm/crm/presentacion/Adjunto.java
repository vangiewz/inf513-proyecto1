package bo.uagrm.crm.presentacion;

import bo.uagrm.crm.dominio.CrmException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** Archivo binario: el tipo de un comprobante se comprueba por contenido, no por su extension. */
public record Adjunto(String nombre, String mime, byte[] contenido) {
  public static final int MAX_BYTES = 5 * 1024 * 1024;

  public Adjunto {
    contenido = contenido.clone();
    nombre = nombre == null ? "archivo" : nombre.replace('\\', '/');
    nombre = nombre.substring(nombre.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    if (nombre.isBlank()) nombre = "archivo";
    if (nombre.length() > 120) nombre = nombre.substring(nombre.length() - 120);
  }

  @Override
  public byte[] contenido() {
    return contenido.clone();
  }

  public Adjunto comoComprobante() throws CrmException {
    if (contenido.length == 0 || contenido.length > MAX_BYTES)
      throw new CrmException("El comprobante debe tener entre 1 byte y 5 MB.");
    String real = null;
    if (empieza(0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10) && contenido.length >= 24)
      real = "image/png";
    else if (empieza(0xff, 0xd8, 0xff)
        && contenido.length > 4
        && (contenido[contenido.length - 2] & 255) == 0xff
        && (contenido[contenido.length - 1] & 255) == 0xd9) real = "image/jpeg";
    else if (contenido.length >= 14
        && (ascii(0, 6).equals("GIF87a") || ascii(0, 6).equals("GIF89a"))
        && contenido[contenido.length - 1] == 0x3b) real = "image/gif";
    else if (contenido.length >= 20 && ascii(0, 4).equals("RIFF") && ascii(8, 4).equals("WEBP"))
      real = "image/webp";
    else if (contenido.length >= 12
        && ascii(0, 5).equals("%PDF-")
        && new String(
                contenido,
                Math.max(0, contenido.length - 1024),
                Math.min(1024, contenido.length),
                StandardCharsets.ISO_8859_1)
            .contains("%%EOF")) real = "application/pdf";
    if (real == null)
      throw new CrmException(
          "Adjunte un comprobante PNG, JPEG, GIF, WebP o PDF válido; no se admiten SVG ni"
              + " ejecutables.");
    String extension =
        switch (real) {
          case "image/png" -> ".png";
          case "image/jpeg" -> ".jpg";
          case "image/gif" -> ".gif";
          case "image/webp" -> ".webp";
          default -> ".pdf";
        };
    String seguro = nombre.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
    if (!seguro.toLowerCase(Locale.ROOT).endsWith(extension)) seguro = "comprobante" + extension;
    return new Adjunto(seguro, real, contenido);
  }

  private boolean empieza(int... prefijo) {
    if (contenido.length < prefijo.length) return false;
    for (int i = 0; i < prefijo.length; i++) if ((contenido[i] & 255) != prefijo[i]) return false;
    return true;
  }

  private String ascii(int desde, int n) {
    return new String(contenido, desde, n, StandardCharsets.US_ASCII);
  }

  public String dataUrl() {
    return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(contenido);
  }

  public static Adjunto desdeDataUrl(String nombre, String data) throws CrmException {
    try {
      int coma = data.indexOf(',');
      if (!data.startsWith("data:") || coma < 0 || !data.substring(0, coma).endsWith(";base64"))
        throw new IllegalArgumentException();
      return new Adjunto(
              nombre,
              "application/octet-stream",
              Base64.getDecoder().decode(data.substring(coma + 1)))
          .comoComprobante();
    } catch (RuntimeException e) {
      throw new CrmException("El comprobante almacenado no tiene un formato válido.");
    }
  }
}
