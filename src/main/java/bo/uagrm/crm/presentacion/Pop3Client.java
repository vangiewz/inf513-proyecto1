package bo.uagrm.crm.presentacion;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Lector de correos POP3 con sockets propios (RFC 1939), contra mail.tecnoweb.org.bo:110 (Dovecot).
 * Diálogo: USER, PASS, STAT, RETR n, DELE n, QUIT. Los DELE solo se confirman con QUIT: si la sesión se corta
 * antes, el servidor conserva los mensajes y se vuelven a leer en el siguiente ciclo.
 */
public final class Pop3Client implements Closeable {

    private final Socket socket;
    private final BufferedReader in;
    private final Writer out;

    public Pop3Client(String host, int puerto, String usuario, String password) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, puerto), 15_000);
        socket.setSoTimeout(60_000);
        // ISO-8859-1 pasa cada byte tal cual: el MIME se decodifica después con su propio charset.
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1));
        ok(in.readLine(), "saludo");
        comando("USER " + usuario);
        comando("PASS " + password);
    }

    /** Cantidad de mensajes en el buzón. */
    public int stat() throws IOException {
        return Integer.parseInt(comando("STAT").split(" ")[1]);
    }

    /** Tamaño en bytes del mensaje n (LIST n). */
    public long tamano(int n) throws IOException {
        return Long.parseLong(comando("LIST " + n).split(" ")[2].trim());
    }

    /** Mensaje completo (cabeceras y cuerpo) tal como llegó. */
    public byte[] retr(int n) throws IOException {
        comando("RETR " + n);
        return multilinea();
    }

    /** Solo las cabeceras y las primeras líneas del cuerpo (TOP n lineas): para correos demasiado grandes. */
    public byte[] top(int n, int lineas) throws IOException {
        comando("TOP " + n + " " + lineas);
        return multilinea();
    }

    private byte[] multilinea() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (String linea = in.readLine(); ; linea = in.readLine()) {
            if (linea == null) throw new IOException("El servidor cerró la conexión a mitad de un mensaje");
            if (linea.equals(".")) break;
            if (linea.startsWith("..")) linea = linea.substring(1);   // byte-stuffing del protocolo
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
        socket.close();
    }

    private String comando(String linea) throws IOException {
        out.write(linea + "\r\n");
        out.flush();
        return ok(in.readLine(), linea.startsWith("PASS") ? "PASS" : linea);
    }

    private static String ok(String respuesta, String paso) throws IOException {
        if (respuesta == null || !respuesta.startsWith("+OK"))
            throw new IOException("POP3 rechazó " + paso + ": " + respuesta);
        return respuesta;
    }
}
