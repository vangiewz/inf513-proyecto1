package bo.uagrm.crm.integracion;

import static bo.uagrm.crm.persistencia.DatabaseService.*;
import static org.junit.jupiter.api.Assertions.*;

import bo.uagrm.crm.aplicacion.*;
import bo.uagrm.crm.dominio.*;
import bo.uagrm.crm.dominio.captacion.*;
import bo.uagrm.crm.dominio.pagos.*;
import bo.uagrm.crm.dominio.usuarios.*;
import bo.uagrm.crm.persistencia.DatabaseService;
import bo.uagrm.crm.presentacion.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "P1_REMOTE_DB_PASSWORD", matches = ".+")
@Timeout(600)
class CorreoServidorFinancieroTest {
  static DatabaseService db;
  static CommandProcessor cp;
  static Usuario admin, coor, otro, vendedor;
  static long sec = 1000000000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode());

  static synchronized String documento() {
    return "" + (sec++);
  }

  static Usuario user(String rol) throws Exception {
    return new UsuarioService(db)
        .insertar(rol, "QA Servidor", documento() + "@example.test", "70000001", rol);
  }

  static long numero(String s, Object... a) throws Exception {
    try (Connection c = db.getConnection()) {
      return uno(c, r -> r.getLong(1), s, a);
    }
  }

  static Respuesta ejecutar(Usuario u, String cmd, String... p) {
    return cp.procesarEmail(u.correo(), CasosAprobadosCorreoTest.asunto(cmd, p), null);
  }

  static Respuesta ok(Usuario u, String cmd, String... p) {
    var r = ejecutar(u, cmd, p);
    assertEquals("Éxito", r.resultado(), cmd + ": " + r.mensaje());
    return r;
  }

  static Adjunto pdf() {
    return new Adjunto(
        "recibo.pdf",
        "application/pdf",
        "%PDF-1.4\n1 0 obj <<>> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII));
  }

  @BeforeAll
  static void init() throws Exception {
    db =
        new DatabaseService(
            System.getenv().getOrDefault("P1_REMOTE_DB_URL", "jdbc:postgresql://db.tecnoweb.org.bo:5432/db_grupo05sa?currentSchema=crm_qa_correo&ApplicationName=crm-mail-qa-finanzas"),
            System.getenv().getOrDefault("P1_REMOTE_DB_USER", "grupo05sa"),
            System.getenv("P1_REMOTE_DB_PASSWORD"));
    try (Connection c = db.getConnection()) {
      assertEquals("crm_qa_correo", uno(c, r -> r.getString(1), "SELECT current_schema()"));
    }
    db.verificarModelo();
    admin = user("administrador");
    coor = user("coordinador");
    otro = user("coordinador");
    vendedor = user("vendedor");
    cp = new CommandProcessor(db);
    new ProgramaService(db).asignar(admin, "desarrollo-web", coor.correo());
    new ProgramaService(db).asignar(admin, "ia-aplicada", otro.correo());
  }

  @Test
  void programaCompletoSeInscribePorCorreoEnPostgreSqlDelIngeniero() throws Exception {
    String doc = documento(), correo = doc + "@example.test";
    var r =
        cp.procesarEmail(
            correo,
            CasosAprobadosCorreoTest.asunto(
                "REGISTRARINTERES",
                "desarrollo-web",
                doc,
                "Alumno",
                "QA",
                "70000001",
                "Ingenieria",
                "SCZ"),
            null);
    assertEquals("Éxito", r.resultado(), r.mensaje());
    String p = doc;
    List<Integer> ids;
    try (Connection c = db.getConnection()) {
      ids =
          lista(
              c,
              x -> x.getInt(1),
              "SELECT nro_tarjeta FROM intereses WHERE documento=? ORDER BY modulo_codigo",
              p);
    }
    for (int nro : ids) ok(coor, "ASIGNARPROSPECTO", "" + nro, "" + vendedor.correo());
    int tarjeta = ids.get(0);
    ok(
        vendedor,
        "GENERARORDEN",
        "" + tarjeta,
        "desarrollo-web-m1;desarrollo-web-m2;desarrollo-web-m3;desarrollo-web-m4;desarrollo-web-m5",
        "PROGRAMA_COMPLETO");
    int pago =
        (int)
            numero(
                "SELECT pg.nro_orden FROM pagos pg JOIN ordenes_pago op ON op.nro_orden=pg.nro_orden WHERE"
                    + " op.nro_tarjeta=?",
                tarjeta);
    r =
        cp.procesarEmail(
            vendedor.correo(),
            CasosAprobadosCorreoTest.asunto("REGISTRARCOMPROBANTE", "" + pago),
            null,
            pdf(),
            null);
    assertEquals("Éxito", r.resultado(), r.mensaje());
    r = ok(coor, "VERCOMPROBANTE", "" + pago);
    assertArrayEquals(pdf().contenido(), r.archivos().get(0).contenido());
    assertEquals("Error", ejecutar(otro, "APROBARPAGO", "" + pago).resultado());
    ok(coor, "RECHAZARPAGO", "" + pago, "Correccion QA");
    r =
        cp.procesarEmail(
            vendedor.correo(),
            CasosAprobadosCorreoTest.asunto("REGISTRARCOMPROBANTE", "" + pago, "Corregido"),
            null,
            pdf(),
            null);
    assertEquals("Éxito", r.resultado(), r.mensaje());
    ok(coor, "APROBARPAGO", "" + pago, "Prueba aislada");
    assertEquals(
        5,
        numero("SELECT count(*) FROM intereses WHERE documento=? AND estado='Convertido'", p));
    for (int nro : ids) ok(vendedor, "CONVERTIRPROSPECTO", "" + nro);
    int ic =
        (int)
            numero(
                "SELECT min(ic.nro_solicitud) FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON"
                    + " sc.nro_solicitud=ic.nro_solicitud JOIN ordenes_pago op ON op.nro_orden=sc.nro_orden"
                    + " WHERE op.nro_tarjeta=?",
                tarjeta);
    assertEquals(1, ok(coor, "EXPORTARMATRICULAS", "" + ic).archivos().size());
    ok(coor, "LISTAREXPORTACIONES");
    assertEquals("Error", ejecutar(coor, "APROBARPAGO", "" + pago).resultado());
    for (String cmd :
        List.of("REPORTEEMBUDO", "REPORTEVENDEDORES", "REPORTEPERDIDAS", "REPORTEOFERTA"))
      ok(coor, cmd);
  }

  @Test
  void dosAprobacionesSimultaneasEnServidorSoloCreanUnaInscripcion() throws Exception {
    String doc = documento();
    var t =
        new ProspectoService(db)
            .registrar(
                admin,
                doc,
                "Alumno",
                "QA",
                "70000001",
                doc + "@example.test",
                "Correo",
                "desarrollo-web-m1",
                "Ingenieria",
                "SCZ")
            .tarjeta();
    ok(coor, "ASIGNARPROSPECTO", "" + t.nro(), "" + vendedor.correo());
    PagoService pagos = new PagoService(db);
    var o = pagos.generar(vendedor, t.nro(), null, null);
    pagos.registrarComprobante(vendedor, o.nro(), pdf(), null);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<String> llamada = () -> ejecutar(coor, "APROBARPAGO", "" + o.nro()).resultado();
      var respuestas = pool.invokeAll(List.of(llamada, llamada));
      int exitos = 0;
      for (var f : respuestas) if (f.get().equals("Éxito")) exitos++;
      assertEquals(1, exitos);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(
        1,
        numero(
            "SELECT count(*) FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON"
                + " sc.nro_solicitud=ic.nro_solicitud WHERE sc.nro_orden=?",
            o.nro()));
    assertEquals(
        1,
        numero(
            "SELECT count(*) FROM comisiones co JOIN solicitudes_compra sc ON"
                + " sc.nro_solicitud=co.nro_solicitud WHERE sc.nro_orden=?",
            o.nro()));
  }
}
