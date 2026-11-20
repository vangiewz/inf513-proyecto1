package bo.uagrm.crm.presentacion;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Timeout(10)
class Pop3ClientTest {
  interface Check {
    void run(Pop3Client c) throws Exception;
  }

  void session(List<String[]> pasos, Check check) throws Exception {
    try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      server.setSoTimeout(3000);
      var pool = Executors.newSingleThreadExecutor();
      var future =
          pool.submit(
              () -> {
                try (var socket = server.accept()) {
                  socket.setSoTimeout(3000);
                  var input =
                      new BufferedReader(
                          new InputStreamReader(
                              socket.getInputStream(), StandardCharsets.ISO_8859_1));
                  var output = socket.getOutputStream();
                  output.write("+OK servidor QA\r\n".getBytes(StandardCharsets.ISO_8859_1));
                  output.flush();
                  for (var paso : pasos) {
                    assertEquals(paso[0], input.readLine());
                    output.write((paso[1] + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    output.flush();
                  }
                }
                return null;
              });
      try {
        try (var client = new Pop3Client("127.0.0.1", server.getLocalPort(), "qa", "ficticia")) {
          check.run(client);
        }
        future.get(5, TimeUnit.SECONDS);
      } finally {
        pool.shutdownNow();
      }
    }
  }

  List<String[]> login() {
    var l = new ArrayList<String[]>();
    l.add(new String[] {"USER qa", "+OK"});
    l.add(new String[] {"PASS ficticia", "+OK"});
    return l;
  }

  @Test
  void protocoloCompletoYDesescapeDePuntos() throws Exception {
    var pasos = login();
    pasos.add(new String[] {"STAT", "+OK   1   100"});
    pasos.add(new String[] {"LIST 1", "+OK  1  100"});
    pasos.add(
        new String[] {"RETR 1", "+OK\r\nFrom: qa@example.test\r\n\r\n..punto\r\nEspañol\r\n."});
    pasos.add(new String[] {"DELE 1", "+OK"});
    pasos.add(new String[] {"QUIT", "+OK"});
    session(
        pasos,
        c -> {
          assertEquals(1, c.stat());
          assertEquals(100, c.tamano(1));
          String m = new String(c.retr(1), StandardCharsets.ISO_8859_1);
          assertTrue(m.contains("\r\n.punto\r\n"));
          assertTrue(m.contains("Español"));
          c.dele(1);
          c.quit();
        });
  }

  @Test
  void topLeeSoloCabeceras() throws Exception {
    var p = login();
    p.add(new String[] {"TOP 1 0", "+OK\r\nSubject: HELP[]\r\n."});
    session(
        p,
        c -> assertTrue(new String(c.top(1, 0), StandardCharsets.ISO_8859_1).contains("HELP[]")));
  }

  @Test
  void statMalformadoEsErrorDeProtocolo() throws Exception {
    var p = login();
    p.add(new String[] {"STAT", "+OK basura"});
    session(p, c -> assertThrows(IOException.class, c::stat));
  }

  @Test
  void listMalformadoEsErrorDeProtocolo() throws Exception {
    var p = login();
    p.add(new String[] {"LIST 1", "+OK 1 no-numero"});
    session(p, c -> assertThrows(IOException.class, () -> c.tamano(1)));
  }

  @Test
  void servidorQueCortaMensajeNoEntregaDatosTruncados() throws Exception {
    var p = login();
    p.add(new String[] {"RETR 1", "+OK\r\nCuerpo sin terminador"});
    session(p, c -> assertThrows(IOException.class, () -> c.retr(1)));
  }

  @Test
  void errorDeleNoSeConfirmaConQuit() throws Exception {
    var p = login();
    p.add(new String[] {"DELE 1", "-ERR no existe"});
    session(p, c -> assertThrows(IOException.class, () -> c.dele(1)));
  }
}
