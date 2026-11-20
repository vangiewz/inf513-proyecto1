package bo.uagrm.crm.presentacion;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Autorización del canal mediante HMAC SHA-256, independiente de cabeceras del relay.
 * Cada cuenta registrada tiene su propia clave aleatoria privada de al menos 32 bytes.
 * La firma protege la identidad, el destinatario, el asunto, el Message-ID, el tipo MIME
 * y el cuerpo completo (incluidos adjuntos). Las cabeceras de transporte no se firman.
 */
public final class AutenticidadCorreo {
  public static final String FIRMA = "X-CRM-Signature";
  public static final String TIEMPO = "X-CRM-Timestamp";
  private static final Duration ANTIGUEDAD = Duration.ofHours(24);
  private final Map<String, byte[]> claves;

  public record Resultado(boolean autentico, String motivo) {}

  private AutenticidadCorreo(Map<String, byte[]> claves) {
    this.claves = Map.copyOf(claves);
  }

  /** Sin archivo se mantiene una política cerrada: ninguna identidad queda autenticada. */
  public static AutenticidadCorreo cargar(Path archivo) throws java.io.IOException {
    Map<String, byte[]> out = new HashMap<>();
    if (archivo != null) {
      Properties p = new Properties();
      try (var r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) { p.load(r); }
      for (String correo : p.stringPropertyNames()) {
        try {
          byte[] clave = Base64.getDecoder().decode(p.getProperty(correo).trim());
          if (clave.length < 32 || !correo.matches("[^\\s@]+@[^\\s@]+"))
            throw new IllegalArgumentException();
          out.put(correo.toLowerCase(Locale.ROOT), clave);
        } catch (IllegalArgumentException e) {
          throw new java.io.IOException("Configuración de claves HMAC inválida; se requieren claves Base64 de al menos 32 bytes");
        }
      }
    }
    return new AutenticidadCorreo(out);
  }

  public Resultado verificar(byte[] crudo, Instant ahora) {
    try {
      MimeMessage m = mensaje(crudo);
      String remitente = identidad(m);
      byte[] clave = claves.get(remitente);
      if (clave == null) return new Resultado(false, "La cuenta no dispone de una clave de autorización del canal");
      String tiempo = unico(m, TIEMPO), firma = unico(m, FIRMA);
      if (!firma.matches("[0-9a-fA-F]{64}")) return new Resultado(false, "Falta una firma HMAC válida");
      Instant emitido = Instant.ofEpochSecond(Long.parseLong(tiempo));
      if (emitido.isAfter(ahora.plusSeconds(300)) || emitido.isBefore(ahora.minus(ANTIGUEDAD)))
        return new Resultado(false, "La firma está vencida o la fecha del mensaje es incorrecta");
      boolean ok = MessageDigest.isEqual(HexFormat.of().parseHex(firma), mac(clave, material(m, tiempo)));
      return new Resultado(ok, ok ? "Firma HMAC verificada" : "La firma no corresponde al contenido del mensaje");
    } catch (Exception e) {
      return new Resultado(false, "No se pudo verificar la autorización criptográfica del mensaje");
    }
  }

  /** Firma un .eml ya construido, sin enviarlo ni modificar los comandos del Subject. */
  public static byte[] firmar(byte[] crudo, byte[] clave, Instant instante) throws Exception {
    if (clave.length < 32) throw new IllegalArgumentException("La clave HMAC requiere al menos 32 bytes");
    if (crudo.length == 0 || crudo[crudo.length - 1] != '\n') {
      byte[] terminado = java.util.Arrays.copyOf(crudo, crudo.length + 2);
      terminado[crudo.length] = '\r'; terminado[crudo.length + 1] = '\n'; crudo = terminado;
    }
    MimeMessage m = mensaje(crudo);
    identidad(m);
    String ts = Long.toString(instante.getEpochSecond());
    String sig = HexFormat.of().formatHex(mac(clave, material(m, ts)));
    // Prependemos solo las cabeceras propias: no se reserializa ni recodifica el cuerpo firmado.
    String texto = new String(crudo, StandardCharsets.ISO_8859_1);
    if (m.getHeader(FIRMA) != null || m.getHeader(TIEMPO) != null)
      throw new IllegalArgumentException("El mensaje ya contiene cabeceras de firma");
    return ((TIEMPO + ": " + ts + "\r\n" + FIRMA + ": " + sig + "\r\n") + texto)
        .getBytes(StandardCharsets.ISO_8859_1);
  }

  /** Material estable frente al plegado de cabeceras y a Received/Return-Path agregados por el relay. */
  static byte[] material(MimeMessage m, String timestamp) throws Exception {
    StringBuilder b = new StringBuilder("CRM-HMAC-SHA256-v1\n").append(timestamp).append('\n');
    for (String nombre : new String[] {"From", "To", "Subject", "Message-ID", "MIME-Version", "Content-Type", "Content-Transfer-Encoding", "Content-Disposition", "Content-ID", "Auto-Submitted", "Precedence"}) {
      String valor = unico(m, nombre);
      if (nombre.equals("Subject")) valor = m.getSubject() == null ? "" : m.getSubject();
      b.append(nombre.toLowerCase(Locale.ROOT)).append(':').append(valor.replaceAll("\\r?\\n[ \\t]+", " ").trim()).append('\n');
    }
    byte[] cuerpo;
    try (var in = m.getRawInputStream()) { cuerpo = in.readAllBytes(); }
    // POP3 y SMTP transportan CRLF. Canonicalizar saltos permite firmar un .eml local con LF.
    String cuerpoCrLf = new String(cuerpo, StandardCharsets.ISO_8859_1).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
    b.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cuerpoCrLf.getBytes(StandardCharsets.ISO_8859_1))));
    return b.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] mac(byte[] clave, byte[] material) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(clave, "HmacSHA256"));
    return mac.doFinal(material);
  }

  private static String unico(MimeMessage m, String nombre) throws Exception {
    String[] valores = m.getHeader(nombre);
    if (valores == null) return "";
    if (valores.length != 1) throw new IllegalArgumentException("Cabecera repetida");
    return valores[0];
  }

  private static String identidad(MimeMessage m) throws Exception {
    unico(m, "From");
    var direcciones = m.getFrom();
    if (direcciones == null || direcciones.length != 1 || !(direcciones[0] instanceof InternetAddress a))
      throw new IllegalArgumentException("Se requiere un único From válido");
    a.validate();
    if (unico(m, "Message-ID").isBlank()) throw new IllegalArgumentException("Se requiere Message-ID");
    return a.getAddress().toLowerCase(Locale.ROOT);
  }

  private static MimeMessage mensaje(byte[] bytes) throws Exception {
    return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes));
  }

  /** Herramienta local: entrada.eml claves.properties salida.eml. Nunca envía correo. */
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("Uso: AutenticidadCorreo entrada.eml claves.properties salida.eml");
    Path salida = Path.of(args[2]);
    byte[] original = Files.readAllBytes(Path.of(args[0]));
    AutenticidadCorreo auth = cargar(Path.of(args[1]));
    byte[] clave = auth.claves.get(identidad(mensaje(original)));
    if (clave == null) throw new IllegalArgumentException("No hay clave para el remitente del archivo");
    Files.write(salida, firmar(original, clave, Instant.now()), java.nio.file.StandardOpenOption.CREATE_NEW);
    System.out.println("Archivo firmado; no se envió ningún correo.");
  }
}
