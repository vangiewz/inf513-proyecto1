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
 * ciclo. Además del timeout por lectura (60 s) la sesión tiene un plazo total.
 */
public final class Pop3Client implements Closeable {

  /**
   * Plazo total de la sesión: un servidor que manda un byte cada pocos segundos nunca dispara el
   * timeout por lectura; al vencer este plazo se cierra el socket y la lectura en curso falla.
   */
  static final long PLAZO_SESION_MS = 10 * 60_000;

  private static final java.util.Timer VIGIA = new java.util.Timer("pop3-plazo", true);
  private final Socket socket;
  private final BufferedReader in;
  private final Writer out;
  private final java.util.TimerTask cierre;
  private volatile boolean vencida;

  public Pop3Client(String host, int puerto, String usuario, String password) throws IOException {
    this(host, puerto, usuario, password, PLAZO_SESION_MS);
  }

  Pop3Client(String host, int puerto, String usuario, String password, long plazoMs) throws IOException {
    socket = new Socket();
    cierre =
        new java.util.TimerTask() {
          @Override
          public void run() {
            vencida = true;
            try {
              socket.close();
            } catch (IOException ignorado) {
              // el ciclo ya informa la lectura interrumpida
            }
          }
        };
    VIGIA.schedule(cierre, plazoMs);
    try {
      socket.connect(new InetSocketAddress(host, puerto), 15_000);
      socket.setSoTimeout(60_000);
      // ISO-8859-1 pasa cada byte tal cual: el MIME se decodifica después con su propio charset.
      in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
      out =
          new BufferedWriter(
              new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1));
      ok(in.readLine(), "saludo");
      comando("USER " + usuario);
      comando("PASS " + password);
    } catch (IOException | RuntimeException e) {
      cierre.cancel();
      try {
        socket.close();
      } catch (IOException closeError) {
        e.addSuppressed(closeError);
      }
      throw e;
    }
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

  /** Si el plazo total cerró el socket, la causa es la lentitud del servidor, no un corte suyo. */
  private IOException causa(IOException e) {
    return vencida
        ? new IOException("POP3: la sesión superó el plazo de " + PLAZO_SESION_MS / 60_000
            + " min (el servidor responde demasiado lento)", e)
        : e;
  }

  private byte[] multilinea() throws IOException {
    try {
      return leerMultilinea();
    } catch (IOException e) {
      throw causa(e);
    }
  }

  private byte[] leerMultilinea() throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    for (String linea = in.readLine(); ; linea = in.readLine()) {
      if (linea == null)
        throw new IOException("El servidor cerró la conexión a mitad de un mensaje");
      if (linea.equals(".")) break;
      if (linea.startsWith("..")) linea = linea.substring(1); // byte-stuffing del protocolo
      buf.writeBytes((linea + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
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
    cierre.cancel();
    socket.close();
  }

  private String comando(String linea) throws IOException {
    try {
      out.write(linea + "\r\n");
      out.flush();
      return ok(in.readLine(), linea.startsWith("PASS") ? "PASS" : linea);
    } catch (IOException e) {
      throw causa(e);
    }
  }

  private static String ok(String respuesta, String paso) throws IOException {
    if (respuesta == null || !respuesta.startsWith("+OK"))
      throw new IOException("POP3 rechazó " + paso + ": " + respuesta);
    return respuesta;
  }
}
