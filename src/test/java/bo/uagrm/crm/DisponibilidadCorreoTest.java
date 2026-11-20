package bo.uagrm.crm;

import static org.junit.jupiter.api.Assertions.*;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** POP3 real sobre loopback; captura MIME sin enviar correo a ningún proveedor. */
class DisponibilidadCorreoTest {
  @TempDir Path dir;
  static class Buzon implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
    final List<String> mensajes = new ArrayList<>();
    final Thread hilo;
    volatile boolean perderQuit;
    Buzon(String... asuntos) throws IOException {
      for (String a : asuntos) mensajes.add("From: desconocido@example.test\r\nTo: buzon@example.test\r\nSubject: "
          + a + "\r\nMessage-ID: <" + UUID.randomUUID() + "@example.test>\r\n\r\nPrueba local\r\n");
      hilo = Thread.startVirtualThread(() -> {
        while (!server.isClosed()) try (Socket s = server.accept()) {
          var in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
          var out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.ISO_8859_1), true);
          Set<Integer> borrar = new HashSet<>();
          out.print("+OK local\r\n"); out.flush();
          String l;
          while ((l = in.readLine()) != null) {
            String[] p = l.split(" ");
            switch (p[0]) {
              case "USER", "PASS" -> out.print("+OK\r\n");
              case "STAT" -> out.print("+OK " + mensajes.size() + " 100\r\n");
              case "LIST" -> out.print("+OK " + p[1] + " " + mensajes.get(Integer.parseInt(p[1])-1).length() + "\r\n");
              case "RETR" -> out.print("+OK\r\n" + mensajes.get(Integer.parseInt(p[1])-1) + ".\r\n");
              case "DELE" -> { borrar.add(Integer.parseInt(p[1])-1); out.print("+OK\r\n"); }
              case "QUIT" -> {
                if (perderQuit) { perderQuit = false; s.close(); break; }
                borrar.stream().sorted(Comparator.reverseOrder()).forEach(i -> mensajes.remove((int)i));
                out.print("+OK\r\n"); out.flush(); s.close();
              }
              default -> out.print("-ERR\r\n");
            }
            out.flush();
            if (s.isClosed()) break;
          }
        } catch (IOException e) { if (!server.isClosed()) throw new UncheckedIOException(e); }
      });
    }
    public void close() throws Exception { server.close(); hilo.join(2000); }
  }
  Config config(Buzon b, String url) {
    return new Config("127.0.0.1", b.server.getLocalPort(), "local", "local", "127.0.0.1", 1,
        "smtp@example.test", "ficticia", "buzon@example.test", url, "crm_user", "crm_password", 3);
  }
  @Test void ayudaRespondeSinJDBCPrivadoQuedaEnPopYQuitFallidoNoDuplica() throws Exception {
    List<byte[]> respuestas = new ArrayList<>();
    try (var b = new Buzon("LISTARUSUARIOS[]", "HELP[]")) {
      Config cfg = config(b, "jdbc:postgresql://127.0.0.1:1/no");
      var db = new DatabaseService(cfg.dbUrl(), cfg.dbUsuario(), cfg.dbPassword());
      b.perderQuit = true;
      var app = new App(cfg, db, dir, respuestas::add);
      assertThrows(Exception.class, app::ciclo);
      assertEquals(2, b.mensajes.size()); assertEquals(1, respuestas.size());
      assertNull(DatabaseService.falloCorreo());
      // Nueva instancia: el diario persiste aunque el servidor haya perdido el QUIT anterior.
      new App(cfg, db, dir, respuestas::add).ciclo();
      assertEquals(1, b.mensajes.size()); assertTrue(b.mensajes.getFirst().contains("LISTARUSUARIOS[]"));
      assertEquals(1, respuestas.size());
    }
  }
  @Test void privadoPendienteSeRecuperaYRevisaPermisosAlVolverBD() throws Exception {
    String url = System.getenv("P1_TEST_DB_URL");
    Assumptions.assumeTrue(url != null && url.startsWith("jdbc:postgresql://127.0.0.1:"));
    List<byte[]> respuestas = new ArrayList<>();
    try (var b = new Buzon("LISTARUSUARIOS[]")) {
      var cfg = config(b, url);
      var app = new App(cfg, new DatabaseService(url, "crm_user", "crm_password"), dir, respuestas::add);
      app.ciclo(); assertEquals(1, b.mensajes.size()); assertTrue(respuestas.isEmpty());
      app.comprobarBD(); app.ciclo();
      assertTrue(b.mensajes.isEmpty()); assertEquals(1, respuestas.size());
      var mime = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()),
          new ByteArrayInputStream(respuestas.getFirst()));
      assertTrue(mime.getSubject().toLowerCase().contains("denegado"), mime.getSubject());
    }
  }

  /** Corta TCP hacia el PostgreSQL LOCAL; el pool y App permanecen vivos. */
  static class CorteBD implements AutoCloseable {
    final ServerSocket entrada = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
    final List<Socket> conexiones = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean habilitado = true;
    CorteBD(int destino) throws IOException {
      Thread.startVirtualThread(() -> {
        while (!entrada.isClosed()) try {
          Socket cliente = entrada.accept(); conexiones.add(cliente);
          if (!habilitado) { cliente.close(); continue; }
          Socket bd = new Socket("127.0.0.1", destino); conexiones.add(bd);
          Thread.startVirtualThread(() -> copiar(cliente, bd));
          Thread.startVirtualThread(() -> copiar(bd, cliente));
        } catch (IOException e) { if (!entrada.isClosed()) throw new UncheckedIOException(e); }
      });
    }
    void copiar(Socket desde, Socket hasta) {
      try { desde.getInputStream().transferTo(hasta.getOutputStream()); }
      catch (IOException ignored) { }
      finally { try { desde.close(); hasta.close(); } catch (IOException ignored) { } }
    }
    void caer() throws IOException { habilitado = false; for (Socket s : conexiones) s.close(); }
    public void close() throws IOException { entrada.close(); caer(); }
  }

  @Test void caidaTcpRealNoBloqueaHelpYElMismoPoolRecuperaPrivados() throws Exception {
    String url = System.getenv("P1_TEST_DB_URL");
    Assumptions.assumeTrue(url != null && url.startsWith("jdbc:postgresql://127.0.0.1:"));
    int puerto = Integer.parseInt(url.replaceFirst("^jdbc:postgresql://127.0.0.1:(\\d+)/.*", "$1"));
    try (var corte = new CorteBD(puerto); var b = new Buzon("LISTARUSUARIOS[]", "HELP[]")) {
      String proxyUrl = url.replaceFirst(":" + puerto + "/", ":" + corte.entrada.getLocalPort() + "/");
      List<byte[]> respuestas = new ArrayList<>();
      var cfg = config(b, proxyUrl);
      var app = new App(cfg, new DatabaseService(proxyUrl, "crm_user", "crm_password"), dir, respuestas::add);
      app.comprobarBD(); assertTrue(app.bdDisponible());
      corte.caer();
      Thread comprobando = Thread.startVirtualThread(app::comprobarBD);
      long limite = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      while (app.bdDisponible() && System.nanoTime() < limite) Thread.onSpinWait();
      assertFalse(app.bdDisponible());
      long inicio = System.nanoTime();
      app.ciclo();
      long ms = (System.nanoTime() - inicio) / 1_000_000;
      assertTrue(ms < 2000, "HELP local bloqueado por JDBC: " + ms + " ms");
      assertEquals(1, respuestas.size()); assertEquals(1, b.mensajes.size());
      comprobando.join(20000); assertFalse(comprobando.isAlive()); assertFalse(app.bdDisponible());
      corte.habilitado = true;
      app.comprobarBD(); assertTrue(app.bdDisponible());
      app.ciclo();
      assertTrue(b.mensajes.isEmpty()); assertEquals(2, respuestas.size());
      System.out.println("PRUEBA corte TCP local: HELP ciclo_ms=" + ms
          + "; privado recuperado con mismo pool y permisos verificados");
    }
  }
}
