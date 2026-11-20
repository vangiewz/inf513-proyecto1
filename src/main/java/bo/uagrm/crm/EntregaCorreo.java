package bo.uagrm.crm;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Diario local conservador. POP3 conserva la solicitud hasta confirmar todos sus envíos.
 * Una ejecución o DATA de resultado incierto exige conciliación, nunca repetición automática.
 * Debe usarse bajo el bloqueo de instancia de App; no borrar este directorio al reiniciar. */
final class EntregaCorreo {
  @FunctionalInterface interface Preparar { List<byte[]> obtener() throws Exception; }
  @FunctionalInterface interface Enviar { void enviar(byte[] mime) throws Exception; }
  static final class SinEfectos extends Exception { SinEfectos(Exception e) { super(e); } }
  private enum Fase { EJECUTANDO, PREPARADO, ENVIANDO, TERMINADO }
  private record Estado(Fase fase, int siguiente, List<byte[]> mensajes) {}
  private final Path directorio;
  EntregaCorreo(Path directorio) { this.directorio = directorio; }

  List<String> pendientes() throws IOException {
    if (!Files.exists(directorio)) return List.of();
    List<String> salida = new ArrayList<>();
    try (var rutas = Files.newDirectoryStream(directorio, "*.estado")) {
      for (Path ruta : rutas) {
        Estado e = leer(ruta);
        if (e.fase != Fase.TERMINADO) salida.add(ruta.getFileName() + " " + e.fase
            + " aceptados=" + e.siguiente + "/" + e.mensajes.size());
      }
    }
    return salida.stream().sorted().toList();
  }

  boolean atender(String id, boolean habilitado, Preparar preparar, Enviar enviar) throws Exception {
    if (!id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Huella inválida");
    Files.createDirectories(directorio);
    Path ruta = directorio.resolve(id + ".estado");
    Estado e = Files.exists(ruta) ? leer(ruta) : null;
    if (e != null && e.fase == Fase.TERMINADO) return true;
    if (e != null && (e.fase == Fase.EJECUTANDO || e.fase == Fase.ENVIANDO)) {
      throw new IllegalStateException("Entrega " + id + " en " + e.fase
          + ": resultado incierto; conservar POP3 y conciliar antes de reintentar");
    }
    if (e == null) {
      if (!habilitado) return false; // ninguna consulta ni efecto privado durante la caída
      guardar(ruta, new Estado(Fase.EJECUTANDO, 0, List.of()));
      List<byte[]> mensajes;
      try { mensajes = List.copyOf(preparar.obtener()); }
      catch (SinEfectos ex) { Files.delete(ruta); throw ex; }
      e = new Estado(Fase.PREPARADO, 0, mensajes);
      guardar(ruta, e); // respuesta y avisos durables ANTES del primer SMTP
    }
    for (int i = e.siguiente; i < e.mensajes.size(); i++) {
      guardar(ruta, new Estado(Fase.ENVIANDO, i, e.mensajes));
      try { enviar.enviar(e.mensajes.get(i)); }
      catch (bo.uagrm.crm.presentacion.SmtpSender.NoEnviado ex) {
        guardar(ruta, new Estado(Fase.PREPARADO, i, e.mensajes));
        throw ex;
      }
      guardar(ruta, new Estado(Fase.PREPARADO, i + 1, e.mensajes));
    }
    guardar(ruta, new Estado(Fase.TERMINADO, 0, List.of()));
    return true;
  }

  private static void guardar(Path ruta, Estado e) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(1); out.writeUTF(e.fase.name()); out.writeInt(e.siguiente);
      out.writeInt(e.mensajes.size());
      for (byte[] m : e.mensajes) { out.writeInt(m.length); out.write(m); }
    }
    Path tmp = ruta.resolveSibling(ruta.getFileName() + ".tmp");
    try (var c = FileChannel.open(tmp, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
      ByteBuffer b = ByteBuffer.wrap(bytes.toByteArray());
      while (b.hasRemaining()) c.write(b);
      c.force(true);
    }
    Files.move(tmp, ruta, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  private static Estado leer(Path ruta) throws IOException {
    try (var in = new DataInputStream(Files.newInputStream(ruta))) {
      if (in.readInt() != 1) throw new IOException("Versión de diario desconocida");
      Fase fase = Fase.valueOf(in.readUTF());
      int siguiente = in.readInt(), n = in.readInt();
      if (n < 0 || n > 1000 || siguiente < 0 || siguiente > n)
        throw new IOException("Diario inválido");
      List<byte[]> mensajes = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        int size = in.readInt();
        if (size < 0 || size > 32 * 1024 * 1024) throw new IOException("Tamaño inválido");
        byte[] m = in.readNBytes(size);
        if (m.length != size) throw new EOFException("Diario incompleto");
        mensajes.add(m);
      }
      if (in.read() != -1) throw new IOException("Datos inesperados en diario");
      return new Estado(fase, siguiente, mensajes);
    }
  }
}
