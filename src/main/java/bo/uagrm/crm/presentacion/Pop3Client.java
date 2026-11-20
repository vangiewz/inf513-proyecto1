package bo.uagrm.crm.presentacion;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Lector de correos POP3 con sockets propios (RFC 1939), contra mail.tecnoweb.org.bo:110 (Dovecot).
 * Diálogo: USER, PASS, STAT, RETR n, DELE n, QUIT. Los DELE solo se confirman con QUIT: si la
 * sesión se corta antes, el servidor conserva los mensajes y se vuelven a leer en el siguiente
 * ciclo.
 */
public final class Pop3Client implements Closeable {

  private Socket socket;
  private BufferedReader in;
  private Writer out;

  public Pop3Client(String host, int puerto, String usuario, String password) throws IOException {
    this(host, puerto, usuario, password, "plain");
  }

  /** plain conserva compatibilidad; starttls exige STLS y tls exige TLS desde el primer byte. */
  public Pop3Client(String host, int puerto, String usuario, String password, String seguridad) throws IOException {
    String modo = seguridad == null ? "plain" : seguridad.toLowerCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("plain", "starttls", "tls").contains(modo))
      throw new IllegalArgumentException("pop3.seguridad debe ser plain, starttls o tls");
    if (usuario == null || password == null || usuario.matches("(?s).*[\\r\\n].*") || password.matches("(?s).*[\\r\\n].*"))
      throw new IllegalArgumentException("Credenciales POP3 inválidas");
    socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(host, puerto), 15_000);
      socket.setSoTimeout(60_000);
      if (modo.equals("tls")) asegurarTls(host, puerto);
      flujos();
      ok(in.readLine(), "saludo");
      if (modo.equals("starttls")) {
        comando("STLS"); // el rechazo termina la sesión: nunca se envían credenciales en claro
        asegurarTls(host, puerto);
        flujos();
      }
      comando("USER " + usuario);
      comando("PASS " + password);
    } catch (IOException | RuntimeException e) {
      try {
        socket.close();
      } catch (IOException closeError) {
        e.addSuppressed(closeError);
      }
      throw e;
    }
  }

  private void flujos() throws IOException {
    // ISO-8859-1 preserva bytes; MIME decodifica después su charset.
    in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
    out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1));
  }

  private void asegurarTls(String host, int puerto) throws IOException {
    var ssl = (javax.net.ssl.SSLSocket) ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
        .createSocket(socket, host, puerto, true);
    socket = ssl;
    var parametros = ssl.getSSLParameters();
    parametros.setEndpointIdentificationAlgorithm("HTTPS");
    ssl.setSSLParameters(parametros);
    ssl.startHandshake();
  }

  /** Cantidad de mensajes en el buzón. */
  public int stat() throws IOException {
    try {
      return Integer.parseInt(comando("STAT").trim().split("\\s+")[1]);
    } catch (NumberFormatException | IndexOutOfBoundsException e) {
      throw new IOException("Respuesta STAT invalida", e);
    }
  }

  /** Tamaño en bytes del mensaje n (LIST n). */
  public long tamano(int n) throws IOException {
    try {
      return Long.parseLong(comando("LIST " + n).trim().split("\\s+")[2]);
    } catch (NumberFormatException | IndexOutOfBoundsException e) {
      throw new IOException("Respuesta LIST invalida", e);
    }
  }

  /** Mensaje completo (cabeceras y cuerpo) tal como llegó. */
  public byte[] retr(int n) throws IOException {
    comando("RETR " + n);
    return multilinea();
  }

  /**
   * Solo las cabeceras y las primeras líneas del cuerpo (TOP n lineas): para correos demasiado
   * grandes.
   */
  public byte[] top(int n, int lineas) throws IOException {
    comando("TOP " + n + " " + lineas);
    return multilinea();
  }

  private byte[] multilinea() throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    for (String linea = in.readLine(); ; linea = in.readLine()) {
      if (linea == null)
        throw new IOException("El servidor cerró la conexión a mitad de un mensaje");
      if (linea.equals(".")) break;
      if (linea.startsWith("..")) linea = linea.substring(1); // byte-stuffing del protocolo
      buf.writeBytes((linea + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
      if (buf.size() > 8 * 1024 * 1024 + 65536)
        throw new IOException("El mensaje excede el límite de recepción; no se confirma DELE");
    }
    return buf.toByteArray();
  }

  public void dele(int n) throws IOException {
    comando("DELE " + n);
  }

  /** Confirma los DELE y cierra la sesión. */
  public void quit() throws IOException {
    comando("QUIT");
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  private String comando(String linea) throws IOException {
    out.write(linea + "\r\n");
    out.flush();
    return ok(in.readLine(), linea.startsWith("PASS") ? "PASS" : linea.startsWith("USER") ? "USER" : linea);
  }

  private static String ok(String respuesta, String paso) throws IOException {
    if (respuesta == null || !respuesta.startsWith("+OK")) {
      String codigo = "";
      if (respuesta != null) {
        var m = java.util.regex.Pattern.compile("\\[(AUTH|IN-USE|SYS/TEMP|LOGIN-DELAY|SYS/PERM)\\]").matcher(respuesta);
        if (m.find()) codigo = " " + m.group();
      }
      throw new IOException("POP3 rechazó " + paso + (respuesta == null ? ": conexión cerrada" : ": respuesta negativa" + codigo));
    }
    return respuesta;
  }
}
