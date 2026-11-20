package bo.uagrm.crm;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Bloqueo del sistema operativo por buzón. Se libera incluso si el proceso termina abruptamente. */
public final class InstanciaCorreo implements AutoCloseable {
  private final FileChannel canal;
  private final FileLock bloqueo;

  private InstanciaCorreo(FileChannel canal, FileLock bloqueo) {
    this.canal = canal;
    this.bloqueo = bloqueo;
  }

  public static InstanciaCorreo adquirir(Path directorio, String host, String usuario) throws IOException {
    Files.createDirectories(directorio);
    String clave = host.toLowerCase(java.util.Locale.ROOT) + "\n" + usuario.toLowerCase(java.util.Locale.ROOT);
    String nombre;
    try {
      nombre = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(clave.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException imposible) { throw new IllegalStateException(imposible); }
    FileChannel canal = FileChannel.open(directorio.resolve("buzon-" + nombre + ".lock"),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      FileLock bloqueo = canal.tryLock();
      if (bloqueo == null) throw new IOException("Ya existe otro lector para este buzón en este equipo");
      return new InstanciaCorreo(canal, bloqueo);
    } catch (OverlappingFileLockException e) {
      canal.close();
      throw new IOException("Ya existe otro lector para este buzón en este equipo", e);
    } catch (IOException | RuntimeException e) {
      canal.close();
      throw e;
    }
  }

  public void close() throws IOException {
    try { bloqueo.release(); } finally { canal.close(); }
  }
}
