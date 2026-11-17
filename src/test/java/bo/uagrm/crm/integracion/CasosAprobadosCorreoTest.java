package bo.uagrm.crm.integracion;

import static bo.uagrm.crm.persistencia.DatabaseService.*;
import static org.junit.jupiter.api.Assertions.*;

import bo.uagrm.crm.aplicacion.*;
import bo.uagrm.crm.dominio.*;
import bo.uagrm.crm.dominio.asignacion.*;
import bo.uagrm.crm.dominio.captacion.*;
import bo.uagrm.crm.dominio.conversion.*;
import bo.uagrm.crm.dominio.embudo.*;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfEnvironmentVariable(named = "P1_TEST_DB_URL", matches = ".+")
@Timeout(60)
class CasosAprobadosCorreoTest {
  static DatabaseService db;
  static Usuario admin, c1, c2, v1, v2;
  static CommandProcessor cp;
  static long sequence = 1000000000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode());
  static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a01cAAAAASUVORK5CYII=");

  static synchronized String doc() {
    return "" + (sequence++);
  }

  static long count(String s, Object... args) throws Exception {
    try (Connection c = db.getConnection()) {
      return uno(c, r -> r.getLong(1), s, args);
    }
  }

  static String str(String s, Object... args) throws Exception {
    try (Connection c = db.getConnection()) {
      return uno(c, r -> r.getString(1), s, args);
    }
  }

  static void sql(String s, Object... args) throws Exception {
    try (Connection c = db.getConnection()) {
      actualizar(c, s, args);
    }
  }

  static Usuario user(String rol) throws Exception {
    return new UsuarioService(db)
        .insertar(rol, "Correo QA", doc() + "@example.test", "70000001", rol);
  }

  static String asunto(String cmd, String... params) {
    return cmd
        + "["
        + Arrays.stream(params)
            .map(x -> "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(","))
        + "]";
  }

  static Respuesta ejecutar(Usuario u, String cmd, String... params) {
    return cp.procesarEmail(u.correo(), asunto(cmd, params), null);
  }

  static Respuesta ok(Usuario u, String cmd, String... params) {
    var r = ejecutar(u, cmd, params);
    assertEquals("Éxito", r.resultado(), cmd + ": " + r.mensaje());
    return r;
  }

  static PagoService pagos() {
    return new PagoService(db);
  }

  static Adjunto comprobante() {
    return new Adjunto("recibo.png", "application/octet-stream", PNG);
  }

  static Interes nueva(String modulo, boolean ficha) throws Exception {
    String d = doc();
    var t =
        new ProspectoService(db)
            .registrar(
                admin,
                d,
                "Persona",
                "QA",
                "70000001",
                d + "@example.test",
                "Correo",
                modulo,
                ficha ? "Ingenieria" : null,
                ficha ? "Santa Cruz" : null)
            .tarjeta();
    return new AsignacionService(db).asignar(admin, t.id(), v1.id()).tarjeta();
  }

  static PagoService.Orden revisar(Interes t) throws Exception {
    var o = pagos().generar(v1, t.id(), null, null);
    return pagos().registrarComprobante(v1, o.pagoId(), comprobante(), "Comprobante QA");
  }

  static String token(Usuario u) throws Exception {
    String plain = UUID.randomUUID().toString() + UUID.randomUUID();
    String hash =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(plain.getBytes(StandardCharsets.UTF_8)));
    sql(
        "INSERT INTO api_tokens(usuario_id,token_hash,expira_en,created_at,updated_at)"
            + " VALUES(?,?,now()+interval '1 hour',now(),now())",
        u.id(),
        hash);
    return plain;
  }

  @BeforeAll
  static void init() throws Exception {
    assertEquals("jdbc:postgresql://127.0.0.1:55432/crm_qa_audit", System.getenv("P1_TEST_DB_URL"));
    db = new DatabaseService(System.getenv("P1_TEST_DB_URL"), "crm_user", "crm_password");
    db.verificarModelo();
    admin = user("administrador");
    c1 = user("coordinador");
    c2 = user("coordinador");
    v1 = user("vendedor");
    v2 = user("vendedor");
    cp = new CommandProcessor(db);
    for (String codigo :
        List.of("desarrollo-web", "datos-bi", "ciberseguridad", "redes-servidores"))
      new ProgramaService(db).asignar(admin, codigo, c1.id());
    new ProgramaService(db).asignar(admin, "ia-aplicada", c2.id());
  }

  @Test
  void ochoCasosDeUsoCompletosPorDespachadorSinAbrirWeb() throws Exception {
    // CU1: alta y modificacion de usuario por comandos.
    String correo = doc() + "@example.test";
    ok(admin, "INSERTARUSUARIO", "Nombre", "QA", correo, "70000001", "vendedor");
    int uid = (int) count("SELECT id FROM usuarios WHERE correo=?", correo);
    ok(
        admin,
        "MODIFICARUSUARIO",
        "" + uid,
        "Nombre editado",
        "QA",
        correo,
        "70000002",
        "vendedor",
        "activo");
    assertEquals("Nombre editado", str("SELECT nombre FROM usuarios WHERE id=?", uid));
    ok(admin, "ASIGNARCOORDINADOR", "desarrollo-web", "" + c1.id());
    // CU2: visitante registra programa completo con ficha.
    String documento = doc();
    String email = documento + "@example.test";
    var r =
        cp.procesarEmail(
            email,
            asunto(
                "REGISTRARINTERES",
                "desarrollo-web",
                documento,
                "Persona",
                "Programa",
                "70000001",
                "Ingenieria",
                "Santa Cruz"),
            null);
    assertEquals("Éxito", r.resultado(), r.mensaje());
    int prospecto = (int) count("SELECT id FROM prospectos WHERE documento=?", documento);
    List<Integer> tarjetas;
    try (Connection c = db.getConnection()) {
      tarjetas =
          lista(
              c,
              x -> x.getInt(1),
              "SELECT id FROM intereses WHERE prospecto_id=? ORDER BY external_modulo_id",
              prospecto);
    }
    assertEquals(5, tarjetas.size());
    // CU3: coordinador asigna cada interes; vendedor no se autoasigna.
    for (int id : tarjetas) ok(c1, "ASIGNARPROSPECTO", "" + id, "" + v1.id());
    int id = tarjetas.get(0);
    assertEquals("Denegado", ejecutar(v1, "ASIGNARPROSPECTO", "" + id, "" + v1.id()).resultado());
    // CU4, CU7: etapas aprobadas, contacto, recordatorio y cumplimiento.
    var tablero = ok(v1, "VERTABLERO");
    assertEquals(6, tablero.bloques().size());
    ok(v1, "MOVERTARJETA", "" + id, "En Contacto");
    ok(v1, "MOVERTARJETA", "" + id, "Seguimiento");
    ok(v1, "REGISTRARINTERACCION", "" + id, "WhatsApp", "Se informo el programa completo");
    ok(v1, "PROGRAMARRECORDATORIO", "" + id, "2090-01-10 10:00", "Confirmar deposito");
    int rec = (int) count("SELECT id FROM recordatorios WHERE interes_id=?", id);
    ok(v1, "COMPLETARRECORDATORIO", "" + rec, "Se recibio el deposito");
    ok(v1, "VERHISTORIAL", "" + id);
    // CU5: una perdida clasificada sobre otra tarjeta del mismo programa.
    Interes perdida = nueva("desarrollo-web-m1", true);
    String motivo = "Horario " + doc();
    ok(c1, "INSERTARMOTIVO", motivo, "Horario");
    int mid = (int) count("SELECT id FROM motivos_perdida WHERE nombre=?", motivo);
    ok(v1, "MARCARPERDIDO", "" + perdida.id(), "" + mid, "Cruce de horarios");
    // CU6: orden, adjunto real, revision, aprobacion e inscripcion, todo por correo.
    String modulos =
        "desarrollo-web-m1;desarrollo-web-m2;desarrollo-web-m3;desarrollo-web-m4;desarrollo-web-m5";
    ok(v1, "GENERARORDEN", "" + id, modulos, "PROGRAMA_COMPLETO");
    int pago =
        (int)
            count(
                "SELECT pg.id FROM pagos pg JOIN ordenes_pago op ON op.id=pg.orden_pago_id WHERE"
                    + " op.interes_id=?",
                id);
    assertEquals("2873.00", str("SELECT monto::text FROM pagos WHERE id=?", pago));
    r =
        cp.procesarEmail(
            v1.correo(),
            asunto("REGISTRARCOMPROBANTE", "" + pago, "Deposito QA"),
            null,
            comprobante(),
            null);
    assertEquals("Éxito", r.resultado(), r.mensaje());
    r = ok(c1, "VERCOMPROBANTE", "" + pago);
    assertEquals(1, r.archivos().size());
    assertArrayEquals(PNG, r.archivos().get(0).contenido());
    assertEquals("Denegado", ejecutar(v1, "APROBARPAGO", "" + pago).resultado());
    assertEquals("Error", ejecutar(c2, "APROBARPAGO", "" + pago).resultado());
    ok(c1, "APROBARPAGO", "" + pago, "Verificacion de prueba");
    ok(v1, "CONVERTIRPROSPECTO", "" + id);
    ok(c1, "LISTARMATRICULAS");
    assertEquals(
        5,
        count(
            "SELECT count(*) FROM intereses WHERE prospecto_id=? AND estado='Convertido'",
            prospecto));
    for (int tarjeta : tarjetas) ok(v1, "CONVERTIRPROSPECTO", "" + tarjeta);
    int orden = (int) count("SELECT orden_pago_id FROM pagos WHERE id=?", pago);
    assertEquals(
        5,
        count(
            "SELECT count(*) FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON"
                + " sc.id=ic.solicitud_compra_id WHERE sc.orden_pago_id=?",
            orden));
    assertEquals(
        5,
        count(
            "SELECT count(*) FROM comisiones co JOIN solicitudes_compra sc ON"
                + " sc.id=co.solicitud_compra_id WHERE sc.orden_pago_id=?",
            orden));
    assertEquals("Error", ejecutar(c1, "APROBARPAGO", "" + pago).resultado());
    // Preparacion para el SIS sin fingir una API que el Grupo 06 aun no entrego.
    int matricula =
        (int)
            count(
                "SELECT min(ic.id) FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON"
                    + " sc.id=ic.solicitud_compra_id WHERE sc.orden_pago_id=?",
                orden);
    r = ok(c1, "EXPORTARMATRICULAS", "" + matricula);
    assertEquals(1, r.archivos().size());
    assertTrue(
        new String(r.archivos().get(0).contenido(), StandardCharsets.UTF_8).contains(documento));
    int exp =
        (int) count("SELECT id FROM exportaciones WHERE inscripcion_comercial_id=?", matricula);
    ok(c1, "VEREXPORTACION", "" + exp);
    ok(
        c1,
        "REGISTRARRESULTADOSIS",
        "" + exp,
        "recibida",
        "Constancia ficticia exclusiva del entorno QA");
    assertEquals(
        1, count("SELECT count(*) FROM exportaciones WHERE inscripcion_comercial_id=?", matricula));
    // CU8 y bitacora.
    for (String comando :
        List.of("REPORTEEMBUDO", "REPORTEVENDEDORES", "REPORTEPERDIDAS", "REPORTEOFERTA"))
      ok(c1, comando);
    ok(admin, "VERBITACORA");
    assertTrue(count("SELECT count(*) FROM log_comandos WHERE usuario_id=?", c1.id()) > 5);
    // Interoperabilidad comprobada por HTTP sobre las mismas filas.
    var web = BaseCompartidaTest.http("GET", "/pagos/" + pago, null, token(admin));
    assertEquals(200, web.statusCode(), web.body());
    assertTrue(web.body().contains("Pago aprobado"));
  }

  @Test
  void coordinadorNoPuedeOperarFueraDeSuPrograma() throws Exception {
    var t = nueva("ia-aplicada-m1", true);
    var o = revisar(t);
    assertThrows(CrmException.class, () -> pagos().ver(c1, o.id()));
    assertThrows(CrmException.class, () -> pagos().archivo(c1, o.pagoId(), null));
    assertThrows(CrmException.class, () -> pagos().aprobar(c1, o.pagoId(), "No corresponde"));
    assertThrows(CrmException.class, () -> new AsignacionService(db).asignar(c1, t.id(), v2.id()));
    assertTrue(new ProspectoService(db).listar(c1, t.prospecto().ci()).isEmpty());
    assertThrows(
        CrmException.class,
        () ->
            new ProspectoService(db)
                .registrar(
                    c1,
                    doc(),
                    "Otra",
                    "QA",
                    "70000001",
                    doc() + "@example.test",
                    "Correo",
                    "ia-aplicada-m2"));
    assertEquals("Pago aprobado", pagos().aprobar(c2, o.pagoId(), "QA responsable").estadoPago());
  }

  @Test
  void fichaIncompletaSeCompletaPorCorreoAntesDeAprobar() throws Exception {
    var t = nueva("desarrollo-web-m1", false);
    var o = revisar(t);
    assertThrows(CrmException.class, () -> pagos().aprobar(c1, o.pagoId(), null));
    assertEquals("En revision", pagos().ver(c1, o.id()).estadoPago());
    ok(
        v1,
        "MODIFICARPROSPECTO",
        t.prospecto().ci(),
        "Persona",
        "QA",
        "70000001",
        t.prospecto().correo(),
        "Correo",
        "Ingenieria",
        "Santa Cruz");
    ok(c1, "APROBARPAGO", "" + o.pagoId());
  }

  @Test
  void rechazoCorreccionYReenvioConservanHistorial() throws Exception {
    var t = nueva("desarrollo-web-m1", true);
    var o = revisar(t);
    pagos().rechazar(c1, o.pagoId(), "Imagen poco clara");
    assertEquals("Pago rechazado", pagos().ver(v1, o.id()).estadoPago());
    pagos().registrarComprobante(v1, o.pagoId(), comprobante(), "Archivo corregido");
    pagos().aprobar(c1, o.pagoId(), "Corregido");
    assertEquals(2, pagos().comprobantes(c1, o.pagoId()).size());
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM comprobantes_pago WHERE pago_id=? AND estado_revision='Pago"
                + " rechazado'",
            o.pagoId()));
    assertThrows(CrmException.class, () -> pagos().rechazar(c1, o.pagoId(), "No permitido"));
  }

  @Test
  void duplicadoEnOtraOrdenRevierteTodasLasInscripcionesDeLaTransaccion() throws Exception {
    var t = nueva("desarrollo-web-m1", true);
    var a = revisar(t);
    pagos().aprobar(c1, a.pagoId(), null);
    var nuevo =
        new ProspectoService(db)
            .registrar(
                admin,
                t.prospecto().ci(),
                "Persona",
                "QA",
                "70000001",
                t.prospecto().correo(),
                "Correo",
                "desarrollo-web-m2")
            .tarjeta();
    new AsignacionService(db).asignar(admin, nuevo.id(), v1.id());
    var o = pagos().generar(v1, nuevo.id(), "desarrollo-web-m2;desarrollo-web-m1", null);
    pagos().registrarComprobante(v1, o.pagoId(), comprobante(), null);
    assertThrows(CrmException.class, () -> pagos().aprobar(c1, o.pagoId(), null));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON"
                + " sc.id=ic.solicitud_compra_id WHERE sc.orden_pago_id=?",
            o.id()));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM comisiones co JOIN solicitudes_compra sc ON"
                + " sc.id=co.solicitud_compra_id WHERE sc.orden_pago_id=?",
            o.id()));
    assertEquals("En revision", pagos().ver(c1, o.id()).estadoPago());
  }

  @Test
  void aprobacionConcurrenteCorreoYWebNoDuplicaMatricula() throws Exception {
    var t = nueva("desarrollo-web-m1", true);
    var o = revisar(t);
    String auth = token(admin);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var resultadoJava =
          pool.submit(
              () -> {
                try {
                  pagos().aprobar(c1, o.pagoId(), "QA");
                  return 200;
                } catch (CrmException e) {
                  return 422;
                }
              });
      var web =
          pool.submit(
              () ->
                  BaseCompartidaTest.http("PATCH", "/pagos/" + o.pagoId() + "/aprobar", "{}", auth)
                      .statusCode());
      assertEquals(
          List.of(200, 422),
          java.util.stream.Stream.of(resultadoJava.get(), web.get()).sorted().toList());
      assertEquals(
          1,
          count(
              "SELECT count(*) FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON"
                  + " sc.id=ic.solicitud_compra_id WHERE sc.orden_pago_id=?",
              o.id()));
    } finally {
      pool.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"no-existe", "ia-aplicada-m1", "desarrollo-web-m1;no-existe", "desarrollo-web-m2"})
  void ordenInvalidaNoDejaFilasParciales(String codigos) throws Exception {
    var t = nueva("desarrollo-web-m1", true);
    assertThrows(CrmException.class, () -> pagos().generar(v1, t.id(), codigos, null));
    assertEquals(0, count("SELECT count(*) FROM ordenes_pago WHERE interes_id=?", t.id()));
  }

  @Test
  void identidadYPermisosDelComprobanteNoDependanDelIdAdivinado() throws Exception {
    var t = nueva("desarrollo-web-m1", true);
    var o = revisar(t);
    var otro = revisar(nueva("desarrollo-web-m1", true));
    int id = (int) count("SELECT id FROM comprobantes_pago WHERE pago_id=?", otro.pagoId());
    assertThrows(CrmException.class, () -> pagos().archivo(c1, o.pagoId(), id));
    assertThrows(CrmException.class, () -> pagos().archivo(v2, o.pagoId(), null));
    assertThrows(CrmException.class, () -> pagos().aprobar(v1, o.pagoId(), "No permitido"));
  }

  @Test
  void bytesInvalidosYAdjuntoAusenteNoAlteranElPago() throws Exception {
    var t = nueva("desarrollo-web-m1", true);
    var o = pagos().generar(v1, t.id(), null, null);
    assertThrows(
        CrmException.class,
        () ->
            pagos()
                .registrarComprobante(
                    v1,
                    o.pagoId(),
                    new Adjunto(
                        "falso.pdf",
                        "application/pdf",
                        "<script>fake</script>".getBytes(StandardCharsets.UTF_8)),
                    null));
    assertThrows(
        CrmException.class, () -> pagos().registrarComprobante(v1, o.pagoId(), null, null));
    assertEquals(0, count("SELECT count(*) FROM comprobantes_pago WHERE pago_id=?", o.pagoId()));
    assertEquals("Pendiente de pago", pagos().ver(c1, o.id()).estadoPago());
  }

  @Test
  void cambioDeResponsableRevocaElAccesoDelAnterior() throws Exception {
    var t = nueva("ciberseguridad-m1", true);
    var o = revisar(t);
    try {
      new ProgramaService(db).asignar(admin, "ciberseguridad", c2.id());
      assertThrows(CrmException.class, () -> pagos().ver(c1, o.id()));
      assertEquals(o.id(), pagos().ver(c2, o.id()).id());
    } finally {
      new ProgramaService(db).asignar(admin, "ciberseguridad", c1.id());
    }
  }
}
