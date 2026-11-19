package bo.uagrm.crm;

import static bo.uagrm.crm.persistencia.DatabaseService.uno;
import static org.junit.jupiter.api.Assertions.*;

import bo.uagrm.crm.aplicacion.CommandProcessor;
import bo.uagrm.crm.dominio.captacion.ProspectoService;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.dominio.usuarios.UsuarioService;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Bandeja persistente de entrada y salida, de punta a punta con el ciclo real de App: POP3 y SMTP
 * de prueba en 127.0.0.1 y PostgreSQL desechable (P1_TEST_DB_URL, solo 127.0.0.1).
 *
 * <p>Comprueba: el correo se guarda antes del DELE y no se duplica si se vuelve a leer; un QUIT
 * cuya respuesta se pierde no pierde solicitudes; con el SMTP caído la operación queda confirmada y
 * la respuesta se reintenta sin repetirla; un reintento del usuario no repite la operación; una
 * falla antes del COMMIT no deja nada; un destinatario inexistente queda Fallido; un recordatorio
 * se avisa una vez; dos instancias a la vez procesan cada correo una sola vez.
 */
@EnabledIfEnvironmentVariable(named = "P1_TEST_DB_URL", matches = ".+")
@Timeout(120)
class BandejaCorreoTest {

  static DatabaseService db;
  static Usuario admin;
  static long tarjeta;
  static final Pop3Falso pop = new Pop3Falso();
  static final SmtpFalso smtp = new SmtpFalso();

  @BeforeAll
  static void init() throws Exception {
    String url = System.getenv("P1_TEST_DB_URL");
    assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:"), "solo contra una base local desechable");
    db =
        new DatabaseService(
            url,
            System.getenv().getOrDefault("P1_TEST_DB_USER", "crm_user"),
            System.getenv().getOrDefault("P1_TEST_DB_PASSWORD", "crm_password"));
    db.verificarModelo();
    admin =
        new UsuarioService(db)
            .insertar("Admin", "Bandeja", "bandeja." + UUID.randomUUID() + "@example.test", "70000001", "administrador");
    String doc = String.valueOf(10_000_000 + new Random().nextInt(80_000_000));
    tarjeta =
        new ProspectoService(db)
            .registrar(admin, doc, "Prospecto", "Bandeja", "70000002", doc + "@example.test", "Correo",
                "desarrollo-web-m1", null, null)
            .tarjeta()
            .nro();
    pop.iniciar();
    smtp.iniciar();
    System.setProperty("crm.smtp.starttls", "false");
  }

  @AfterAll
  static void fin() {
    pop.detener();
    smtp.detener();
  }

  @BeforeEach
  void limpiar() throws Exception {
    pop.buzon.clear();
    pop.quitPerdido = false;
    smtp.recibidos.clear();
    smtp.abierto = true;
    // el ciclo de cada prueba solo debe ver lo suyo
    try (Connection c = db.getConnection()) {
      DatabaseService.actualizar(c, "UPDATE log_comandos SET resultado='Error',contenido=NULL WHERE resultado='Recibido'");
      DatabaseService.actualizar(c, "UPDATE correos_salientes SET estado='Fallido' WHERE estado='Pendiente'");
      DatabaseService.actualizar(c, "UPDATE recordatorios SET notificado=true");
    }
  }

  static App app() {
    return new App(
        new Config("127.0.0.1", pop.puerto(), "qa", "ficticia", "127.0.0.1", smtp.puerto(),
            "sistema@example.test", "ficticia", "buzon@example.test",
            System.getenv("P1_TEST_DB_URL"),
            System.getenv().getOrDefault("P1_TEST_DB_USER", "crm_user"),
            System.getenv().getOrDefault("P1_TEST_DB_PASSWORD", "crm_password"), 1));
  }

  static String correo(String from, String asunto) {
    return "From: " + from + "\r\nTo: buzon@example.test\r\nSubject: " + asunto
        + "\r\nMessage-ID: <" + UUID.randomUUID() + "@qa.test>\r\nDate: Thu, 19 Nov 2026 10:00:00 -0400"
        + "\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nqa\r\n";
  }

  static long cuenta(String sql, Object... p) throws SQLException {
    try (Connection c = db.getConnection()) {
      return uno(c, rs -> rs.getLong(1), sql, p);
    }
  }

  static long interacciones(String texto) throws SQLException {
    return cuenta("SELECT count(*) FROM interacciones WHERE descripcion=?", texto);
  }

  static String nota(String texto) {
    return "REGISTRARINTERACCION[\"" + tarjeta + "\",\"Nota\",\"" + texto + "\"]";
  }

  @Test
  void seGuardaAntesDelDeleYUnaRelecturaNoDuplica() throws Exception {
    String txt = "relectura " + UUID.randomUUID();
    String m = correo(admin.correo(), nota(txt));
    pop.buzon.add(m);
    app().ciclo();
    assertEquals(1, interacciones(txt));
    assertEquals(0, pop.buzon.size());
    assertEquals(1, smtp.recibidos.size(), "la respuesta salió");
    pop.buzon.add(m); // el servidor vuelve a entregar el mismo correo (mismo Message-ID)
    app().ciclo();
    assertEquals(1, interacciones(txt), "no se ejecuta dos veces");
    assertEquals(0, pop.buzon.size(), "igual se borra del buzón");
    assertEquals(1, smtp.recibidos.size(), "no se responde dos veces");
  }

  @Test
  void quitSinRespuestaNoPierdeSolicitudes() throws Exception {
    String a = "quit-a " + UUID.randomUUID(), b = "quit-b " + UUID.randomUUID();
    pop.buzon.add(correo(admin.correo(), nota(a)));
    pop.buzon.add(correo(admin.correo(), nota(b)));
    pop.quitPerdido = true; // el servidor aplica los DELE pero su +OK no llega
    app().ciclo();
    assertEquals(0, pop.buzon.size());
    assertEquals(1, interacciones(a));
    assertEquals(1, interacciones(b));
    assertEquals(2, smtp.recibidos.size());
  }

  @Test
  void smtpCaidoSeReintentaSinRepetirLaOperacion() throws Exception {
    String txt = "smtp " + UUID.randomUUID();
    smtp.abierto = false;
    pop.buzon.add(correo(admin.correo(), nota(txt)));
    App app = app();
    app.ciclo();
    assertEquals(1, interacciones(txt), "la operación quedó confirmada");
    long k = cuenta("SELECT max(nro_correo) FROM correos_salientes");
    assertEquals("Pendiente", estado(k));
    assertEquals(1, cuenta("SELECT intentos FROM correos_salientes WHERE nro_correo=?", k));
    assertTrue(cuenta("SELECT count(*) FROM correos_salientes WHERE nro_correo=? AND proximo_intento>now()", k) == 1,
        "espera antes de reintentar");
    smtp.abierto = true;
    app.ciclo();
    assertEquals(0, smtp.recibidos.size(), "no reintenta antes de su turno");
    try (Connection c = db.getConnection()) {
      DatabaseService.actualizar(c, "UPDATE correos_salientes SET proximo_intento=now() WHERE nro_correo=?", k);
    }
    app.ciclo();
    assertEquals("Enviado", estado(k));
    assertEquals(1, smtp.recibidos.size());
    assertEquals(1, interacciones(txt), "reenviar la respuesta no repitió la operación");
  }

  @Test
  void reintentoDelUsuarioNoRepiteLaOperacion() throws Exception {
    String txt = "reintento " + UUID.randomUUID();
    pop.buzon.add(correo(admin.correo(), nota(txt)));
    app().ciclo();
    pop.buzon.add(correo(admin.correo(), nota(txt))); // otro correo, mismo asunto
    app().ciclo();
    assertEquals(1, interacciones(txt));
    assertEquals(1, cuenta("SELECT count(*) FROM log_comandos WHERE asunto=? AND resultado='Repetido'", nota(txt)));
    assertEquals(2, smtp.recibidos.size(), "se reenvía la respuesta original");
    assertEquals(messageId(smtp.recibidos.get(0)), messageId(smtp.recibidos.get(1)), "con el mismo Message-ID");
  }

  @Test
  void fallaAntesDelCommitNoDejaNadaYSeProcesaDespues() throws Exception {
    String txt = "commit " + UUID.randomUUID();
    var cp = new CommandProcessor(db);
    assertThrows(
        SQLException.class,
        () ->
            db.enTransaccion(
                c -> {
                  var a = cp.atender(admin.correo(), nota(txt), null, null, null);
                  assertEquals("Éxito", a.resultado()); // los servicios se unieron a esta transacción
                  throw new SQLException("caída simulada antes del COMMIT", "08006");
                }));
    assertEquals(0, interacciones(txt), "nada quedó confirmado");
    pop.buzon.add(correo(admin.correo(), nota(txt)));
    app().ciclo();
    assertEquals(1, interacciones(txt));
  }

  @Test
  void destinatarioInexistenteQuedaFallidoSinInsistir() throws Exception {
    pop.buzon.add(correo("noexiste@example.test", "HELP[]"));
    app().ciclo();
    long k = cuenta("SELECT max(nro_correo) FROM correos_salientes");
    assertEquals("Fallido", estado(k));
    assertEquals(1, cuenta("SELECT intentos FROM correos_salientes WHERE nro_correo=?", k));
  }

  @Test
  void recordatorioVencidoSeAvisaUnaVez() throws Exception {
    long r;
    try (Connection c = db.getConnection()) {
      r = uno(c, rs -> rs.getLong(1),
          "INSERT INTO recordatorios(nro_tarjeta,usuario_correo,fecha_programada,descripcion,created_at,updated_at)"
              + " VALUES(?,?,now()-interval '1 hour','Llamar QA',now(),now()) RETURNING nro_recordatorio",
          tarjeta, admin.correo());
    }
    App app = app();
    app.ciclo();
    app.ciclo();
    assertEquals(1, cuenta("SELECT count(*) FROM correos_salientes WHERE nro_recordatorio=?", r));
    assertEquals(1, smtp.recibidos.size());
    assertTrue(smtp.recibidos.get(0).contains("Recordatorio"));
  }

  @Test
  void dosInstanciasProcesanCadaCorreoUnaVez() throws Exception {
    List<String> textos = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      String t = "paralelo " + i + " " + UUID.randomUUID();
      textos.add(t);
      pop.buzon.add(correo(admin.correo(), nota(t)));
    }
    var pool = Executors.newFixedThreadPool(2);
    App a1 = app(), a2 = app();
    for (int vuelta = 0; vuelta < 3; vuelta++) {
      var f1 = pool.submit(a1::ciclo);
      var f2 = pool.submit(a2::ciclo);
      f1.get(60, TimeUnit.SECONDS);
      f2.get(60, TimeUnit.SECONDS);
    }
    pool.shutdown();
    for (String t : textos) assertEquals(1, interacciones(t), t);
    assertEquals(12, smtp.recibidos.size());
  }

  static String estado(long k) throws SQLException {
    try (Connection c = db.getConnection()) {
      return uno(c, rs -> rs.getString(1), "SELECT estado FROM correos_salientes WHERE nro_correo=?", k);
    }
  }

  static String messageId(String crudo) {
    Matcher m = Pattern.compile("(?im)^Message-ID:\\s*(\\S+)").matcher(crudo);
    return m.find() ? m.group(1) : null;
  }

  /** POP3 con estado: los DELE se aplican con QUIT; una sesión abierta bloquea el buzón. */
  static final class Pop3Falso extends Servidor {
    final List<String> buzon = new CopyOnWriteArrayList<>();
    volatile boolean quitPerdido;
    private final Object cerrojo = new Object();
    private boolean ocupado;

    @Override
    void atender(Socket s) throws IOException {
      var in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
      var out = s.getOutputStream();
      escribir(out, "+OK QA");
      List<String> vista = List.of();
      Set<Integer> borrar = new HashSet<>();
      boolean mio = false;
      try {
        for (String l; (l = in.readLine()) != null; ) {
          String[] p = l.split(" ");
          switch (p[0]) {
            case "USER" -> escribir(out, "+OK");
            case "PASS" -> {
              synchronized (cerrojo) {
                if (ocupado) {
                  escribir(out, "-ERR [IN-USE] Mailbox is locked");
                  continue;
                }
                ocupado = mio = true;
              }
              vista = List.copyOf(buzon);
              escribir(out, "+OK");
            }
            case "STAT" -> escribir(out, "+OK " + vista.size() + " 0");
            case "LIST" -> escribir(out, "+OK " + p[1] + " " + vista.get(Integer.parseInt(p[1]) - 1).length());
            case "RETR", "TOP" -> {
              escribir(out, "+OK");
              for (String x : vista.get(Integer.parseInt(p[1]) - 1).split("\r\n"))
                escribir(out, x.startsWith(".") ? "." + x : x);
              escribir(out, ".");
            }
            case "DELE" -> {
              borrar.add(Integer.parseInt(p[1]));
              escribir(out, "+OK");
            }
            case "QUIT" -> {
              for (int n : borrar) buzon.remove(vista.get(n - 1));
              if (!quitPerdido) escribir(out, "+OK");
              return;
            }
            default -> escribir(out, "-ERR");
          }
        }
      } finally {
        if (mio) synchronized (cerrojo) { ocupado = false; }
      }
    }
  }

  /** SMTP sin TLS que guarda lo recibido; rechaza con 550 a noexiste@; cerrado = conexión rechazada. */
  static final class SmtpFalso extends Servidor {
    final List<String> recibidos = new CopyOnWriteArrayList<>();
    volatile boolean abierto = true;

    @Override
    void atender(Socket s) throws IOException {
      if (!abierto) return; // se cierra sin saludar
      var in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
      var out = s.getOutputStream();
      escribir(out, "220 qa ESMTP");
      for (String l; (l = in.readLine()) != null; ) {
        String u = l.toUpperCase(Locale.ROOT);
        if (u.startsWith("EHLO")) escribir(out, "250-qa\r\n250 AUTH PLAIN LOGIN");
        else if (u.startsWith("AUTH")) escribir(out, "235 ok");
        else if (u.startsWith("RCPT") && u.contains("NOEXISTE")) escribir(out, "550 5.1.1 no existe");
        else if (u.startsWith("DATA")) {
          escribir(out, "354 datos");
          StringBuilder sb = new StringBuilder();
          for (String d; (d = in.readLine()) != null && !d.equals("."); ) sb.append(d).append("\r\n");
          recibidos.add(sb.toString());
          escribir(out, "250 ok");
        } else if (u.startsWith("QUIT")) {
          escribir(out, "221 adios");
          return;
        } else escribir(out, "250 ok");
      }
    }
  }

  abstract static class Servidor {
    private ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    abstract void atender(Socket s) throws IOException;

    void iniciar() throws IOException {
      server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
      pool.submit(() -> {
        while (!server.isClosed()) {
          try {
            Socket s = server.accept();
            pool.submit(() -> {
              try (s) {
                s.setSoTimeout(20_000);
                atender(s);
              } catch (IOException ignored) {
                // el cliente cortó
              }
              return null;
            });
          } catch (IOException e) {
            return null;
          }
        }
        return null;
      });
    }

    int puerto() {
      return server.getLocalPort();
    }

    void detener() {
      try {
        server.close();
      } catch (IOException ignored) {
        // ya cerrado
      }
      pool.shutdownNow();
    }

    static void escribir(OutputStream out, String s) throws IOException {
      out.write((s + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
    }
  }
}
