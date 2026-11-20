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
    return new AsignacionService(db).asignar(admin, t.nro(), v1.correo()).tarjeta();
  }

  static PagoService.Orden revisar(Interes t) throws Exception {
    var o = pagos().generar(v1, t.nro(), null, null);
    return pagos().registrarComprobante(v1, o.nro(), comprobante(), "Comprobante QA");
  }


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
    admin = user("administrador");
    c1 = user("coordinador");
    c2 = user("coordinador");
    v1 = user("vendedor");
    v2 = user("vendedor");
    cp = new CommandProcessor(db);
    for (String codigo :
        List.of("desarrollo-web", "datos-bi", "ciberseguridad", "redes-servidores"))
      new ProgramaService(db).asignar(admin, codigo, c1.correo());
    new ProgramaService(db).asignar(admin, "ia-aplicada", c2.correo());
  }

  @Test
  void ochoCasosDeUsoCompletosPorDespachadorSinAbrirWeb() throws Exception {
    // CU1: alta y modificacion de usuario por comandos.
    String correo = doc() + "@example.test";
    ok(admin, "INSERTARUSUARIO", "Nombre", "QA", correo, "70000001", "vendedor");
    ok(
        admin,
        "MODIFICARUSUARIO",
        correo,
        "Nombre editado",
        "QA",
        "70000002",
        "vendedor",
        "activo",
        "2026-01-01",
        "2099-12-31");
    assertEquals("Nombre editado", str("SELECT nombre FROM usuarios WHERE correo=?", correo));
    assertEquals("2099-12-31", str("SELECT fecha_fin::text FROM usuarios WHERE correo=?", correo));
    ok(admin, "ASIGNARCOORDINADOR", "desarrollo-web", "" + c1.correo());
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
    String prospecto = documento;
    List<Integer> tarjetas;
    try (Connection c = db.getConnection()) {
      tarjetas =
          lista(
              c,
              x -> x.getInt(1),
              "SELECT nro_tarjeta FROM intereses WHERE documento=? ORDER BY modulo_codigo",
              prospecto);
    }
    assertEquals(5, tarjetas.size());
    // CU3: coordinador asigna cada interes; vendedor no se autoasigna.
    for (int nro : tarjetas) ok(c1, "ASIGNARPROSPECTO", "" + nro, "" + v1.correo());
    int nro = tarjetas.get(0);
    assertEquals("Denegado", ejecutar(v1, "ASIGNARPROSPECTO", "" + nro, "" + v1.correo()).resultado());
    // CU4, CU7: etapas aprobadas, contacto, recordatorio y cumplimiento.
    var tablero = ok(v1, "VERTABLERO");
    assertEquals(6, tablero.bloques().size());
    ok(v1, "MOVERTARJETA", "" + nro, "En Contacto");
    ok(v1, "MOVERTARJETA", "" + nro, "Seguimiento");
    ok(v1, "REGISTRARINTERACCION", "" + nro, "WhatsApp", "Se informo el programa completo");
    ok(v1, "PROGRAMARRECORDATORIO", "" + nro, "2090-01-10 10:00", "Confirmar deposito");
    int rec = (int) count("SELECT nro_recordatorio FROM recordatorios WHERE nro_tarjeta=?", nro);
    ok(v1, "COMPLETARRECORDATORIO", "" + rec, "Se recibio el deposito");
    ok(v1, "VERHISTORIAL", "" + nro);
    // CU5: una perdida clasificada sobre otra tarjeta del mismo programa.
    Interes perdida = nueva("desarrollo-web-m1", true);
    String motivo = "Horario " + doc();
    ok(c1, "INSERTARMOTIVO", motivo, "Horario");
    ok(v1, "MARCARPERDIDO", "" + perdida.nro(), motivo.toUpperCase(), "Cruce de horarios");
    // CU6: orden, adjunto real, revision, aprobacion e inscripcion, todo por correo.
    String modulos =
        "desarrollo-web-m1;desarrollo-web-m2;desarrollo-web-m3;desarrollo-web-m4;desarrollo-web-m5";
    ok(v1, "GENERARORDEN", "" + nro, modulos, "PROGRAMA_COMPLETO");
    int pago =
        (int)
            count(
                "SELECT pg.nro_orden FROM pagos pg JOIN ordenes_pago op ON op.nro_orden=pg.nro_orden WHERE"
                    + " op.nro_tarjeta=?",
                nro);
    assertEquals("2873.00", str("SELECT total::text FROM ordenes_pago WHERE nro_orden=?", pago));
    // El precio acordado de cada módulo ya lleva el descuento: su suma es el total cobrado.
    assertEquals(
        "2873.00",
        str("SELECT sum(precio_acordado)::text FROM solicitudes_compra WHERE nro_orden=?", pago));
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
    // RF1.3: el pago existe pero es de un programa ajeno al coordinador → Denegado (no Error).
    assertEquals("Denegado", ejecutar(c2, "APROBARPAGO", "" + pago).resultado());
    ok(c1, "APROBARPAGO", "" + pago, "Verificacion de prueba");
    ok(v1, "CONVERTIRPROSPECTO", "" + nro);
    ok(c1, "LISTARMATRICULAS");
    assertEquals(
        5,
        count(
            "SELECT count(*) FROM intereses WHERE documento=? AND estado='Convertido'",
            prospecto));
    for (int tarjeta : tarjetas) ok(v1, "CONVERTIRPROSPECTO", "" + tarjeta);
    int orden = pago;
    assertEquals(
        5,
        count(
            "SELECT count(*) FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON"
                + " sc.nro_solicitud=ic.nro_solicitud WHERE sc.nro_orden=?",
            orden));
    assertEquals(
        5,
        count(
            "SELECT count(*) FROM comisiones co JOIN solicitudes_compra sc ON"
                + " sc.nro_solicitud=co.nro_solicitud WHERE sc.nro_orden=?",
            orden));
    assertEquals("Error", ejecutar(c1, "APROBARPAGO", "" + pago).resultado());
    // Preparacion para el SIS sin fingir una API que el Grupo 06 aun no entrego.
    int matricula =
        (int)
            count(
                "SELECT min(ic.nro_solicitud) FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON"
                    + " sc.nro_solicitud=ic.nro_solicitud WHERE sc.nro_orden=?",
                orden);
    r = ok(c1, "EXPORTARMATRICULAS", "" + matricula);
    assertEquals(1, r.archivos().size());
    assertTrue(
        new String(r.archivos().get(0).contenido(), StandardCharsets.UTF_8).contains(documento));
    int exp =
        (int) count("SELECT nro_exportacion FROM exportaciones WHERE nro_solicitud=?", matricula);
    ok(c1, "VEREXPORTACION", "" + exp);
    ok(
        c1,
        "REGISTRARRESULTADOSIS",
        "" + exp,
        "recibida",
        "Constancia ficticia exclusiva del entorno QA");
    assertEquals(
        1, count("SELECT count(*) FROM exportaciones WHERE nro_solicitud=?", matricula));
    // CU8 y bitacora.
    for (String comando :
        List.of("REPORTEEMBUDO", "REPORTEVENDEDORES", "REPORTEPERDIDAS", "REPORTEOFERTA"))
      ok(c1, comando);
    ok(admin, "VERBITACORA");
    assertTrue(count("SELECT count(*) FROM log_comandos WHERE usuario_correo=?", c1.correo()) > 5);
  }

  @Test
  void correccionesDeLaAuditoriaDel20Nov() throws Exception {
    // GENERARORDEN no duplica una orden abierta de la misma tarjeta.
    Interes t = nueva("datos-bi-m1", true);
    ok(v1, "GENERARORDEN", "" + t.nro());
    var dup = ejecutar(v1, "GENERARORDEN", "" + t.nro());
    assertEquals("Error", dup.resultado());
    assertTrue(dup.mensaje().contains("ya tiene la orden"), dup.mensaje());
    // MARCARPERDIDO con la orden sin pagar la cierra (no queda pendiente para siempre).
    String motivo = "Precio " + doc();
    ok(c1, "INSERTARMOTIVO", motivo, "Económico");
    ok(v1, "MARCARPERDIDO", "" + t.nro(), motivo, "No pagó");
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM pagos pg JOIN ordenes_pago op ON op.nro_orden=pg.nro_orden WHERE"
                + " op.nro_tarjeta=? AND pg.estado='Pendiente de pago'",
            t.nro()));
    // RF3.2: el usuario ve las etapas aprobadas, nunca los nombres internos.
    Interes m = nueva("datos-bi-m2", true);
    var mov = ok(v1, "MOVERTARJETA", "" + m.nro(), "Seguimiento");
    assertTrue(mov.mensaje().contains("Seguimiento"), mov.mensaje());
    assertFalse(mov.mensaje().contains("Interesado"), mov.mensaje());
    var mala = ejecutar(v1, "MOVERTARJETA", "" + m.nro(), "Interesado");
    assertEquals("Error", mala.resultado());
    assertTrue(mala.mensaje().contains("Parámetro 2"), mala.mensaje());
    // RF1.2: tarjeta de otro vendedor → Denegado.
    assertEquals("Denegado", ejecutar(v2, "VERHISTORIAL", "" + m.nro()).resultado());
    // ELIMINARPROSPECTO quita un registro sin actividad comercial.
    String ci = doc();
    ok(admin, "REGISTRARPROSPECTO", ci, "Borrar", "QA", "70000002", ci + "@example.test", "Correo", "datos-bi-m3");
    ok(admin, "ELIMINARPROSPECTO", ci);
    assertEquals(0, count("SELECT count(*) FROM prospectos WHERE documento=?", ci));
    // CONFIGURARCOMISION (administración) y su uso al aprobar.
    ok(admin, "CONFIGURARCOMISION", "3");
    assertEquals("Denegado", ejecutar(c1, "CONFIGURARCOMISION", "4").resultado());
    assertEquals("3", str("SELECT valor FROM configuraciones WHERE clave='comision_porcentaje'"));
    ok(admin, "CONFIGURARCOMISION", "2");
    // Round-Robin: dos prospectos nuevos en la bolsa van a vendedores distintos y consecutivos.
    for (int k = 0; k < 2; k++) {
      String d = doc();
      ok(admin, "REGISTRARPROSPECTO", d, "Turno", "QA", "70000003", d + "@example.test", "Correo", "datos-bi-m4");
    }
    ok(admin, "ASIGNARAUTOMATICO");
    assertEquals(
        2,
        count(
            "SELECT count(DISTINCT vendedor_correo) FROM intereses i JOIN prospectos p ON p.documento=i.documento"
                + " WHERE p.nombre='Turno' AND i.created_at > now() - interval '1 minute'"));
  }

  @Test
  void credencialFueraDeVigenciaQuedaDenegada() throws Exception {
    String correo = doc() + "@example.test";
    // Una credencial no puede nacer vencida; se crea vigente y luego se acorta su vigencia.
    assertEquals(
        "Error",
        ejecutar(admin, "INSERTARUSUARIO", "Vigencia", "QA", correo, "70000001", "vendedor", "2026-01-01", "2026-01-31")
            .resultado());
    ok(admin, "INSERTARUSUARIO", "Vigencia", "QA", correo, "70000001", "vendedor");
    ok(admin, "MODIFICARUSUARIO", correo, "Vigencia", "QA", "70000001", "vendedor", "activo", "2026-01-01", "2026-01-31");
    var r = cp.procesarEmail(correo, "HELP[]", null);
    assertEquals("Denegado", r.resultado());
    assertTrue(r.mensaje().contains("venció el 31/01/2026"), r.mensaje());
    String futuro = doc() + "@example.test";
    ok(admin, "INSERTARUSUARIO", "Futuro", "QA", futuro, "70000001", "vendedor", "2090-01-01", "2090-12-31");
    assertTrue(cp.procesarEmail(futuro, "HELP[]", null).mensaje().contains("vigente desde el 01/01/2090"));
    ok(admin, "MODIFICARUSUARIO", correo, "Vigencia", "QA", "70000001", "vendedor", "activo", "2026-01-01", "2099-12-31");
    assertEquals("Éxito", cp.procesarEmail(correo, "HELP[]", null).resultado());
    assertEquals(
        "Error",
        ejecutar(admin, "INSERTARUSUARIO", "Al", "Reves", doc() + "@example.test", "70000001", "vendedor", "2026-05-01", "2026-04-01")
            .resultado());
    assertEquals(
        "Error",
        ejecutar(admin, "INSERTARUSUARIO", "Mala", "Fecha", doc() + "@example.test", "70000001", "vendedor", "2026-02-30")
            .resultado());
  }

  @Test
  void coordinadorNoPuedeOperarFueraDeSuPrograma() throws Exception {
    var t = nueva("ia-aplicada-m1", true);
    var o = revisar(t);
    assertThrows(CrmException.class, () -> pagos().ver(c1, o.nro()));
    assertThrows(CrmException.class, () -> pagos().archivo(c1, o.nro(), null));
    assertThrows(CrmException.class, () -> pagos().aprobar(c1, o.nro(), "No corresponde"));
    assertThrows(CrmException.class, () -> new AsignacionService(db).asignar(c1, t.nro(), v2.correo()));
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
    assertEquals("Pago aprobado", pagos().aprobar(c2, o.nro(), "QA responsable").estadoPago());
  }

  @Test
  void fichaIncompletaSeCompletaPorCorreoAntesDeAprobar() throws Exception {
    var t = nueva("desarrollo-web-m1", false);
    var o = revisar(t);
    assertThrows(CrmException.class, () -> pagos().aprobar(c1, o.nro(), null));
    assertEquals("En revision", pagos().ver(c1, o.nro()).estadoPago());
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
    ok(c1, "APROBARPAGO", "" + o.nro());
  }

  @Test
  void rechazoCorreccionYReenvioConservanHistorial() throws Exception {
    var t = nueva("desarrollo-web-m1", true);
    var o = revisar(t);
    pagos().rechazar(c1, o.nro(), "Imagen poco clara");
    assertEquals("Pago rechazado", pagos().ver(v1, o.nro()).estadoPago());
    pagos().registrarComprobante(v1, o.nro(), comprobante(), "Archivo corregido");
    pagos().aprobar(c1, o.nro(), "Corregido");
    assertEquals(2, pagos().comprobantes(c1, o.nro()).size());
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM comprobantes_pago WHERE nro_orden=? AND estado_revision='Pago"
                + " rechazado'",
            o.nro()));
    assertThrows(CrmException.class, () -> pagos().rechazar(c1, o.nro(), "No permitido"));
  }

  @Test
  void duplicadoEnOtraOrdenRevierteTodasLasInscripcionesDeLaTransaccion() throws Exception {
    var t = nueva("desarrollo-web-m1", true);
    var a = revisar(t);
    pagos().aprobar(c1, a.nro(), null);
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
    new AsignacionService(db).asignar(admin, nuevo.nro(), v1.correo());
    var o = pagos().generar(v1, nuevo.nro(), "desarrollo-web-m2;desarrollo-web-m1", null);
    pagos().registrarComprobante(v1, o.nro(), comprobante(), null);
    assertThrows(CrmException.class, () -> pagos().aprobar(c1, o.nro(), null));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON"
                + " sc.nro_solicitud=ic.nro_solicitud WHERE sc.nro_orden=?",
            o.nro()));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM comisiones co JOIN solicitudes_compra sc ON"
                + " sc.nro_solicitud=co.nro_solicitud WHERE sc.nro_orden=?",
            o.nro()));
    assertEquals("En revision", pagos().ver(c1, o.nro()).estadoPago());
  }


  @ParameterizedTest
  @ValueSource(
      strings = {"no-existe", "ia-aplicada-m1", "desarrollo-web-m1;no-existe", "desarrollo-web-m2"})
  void ordenInvalidaNoDejaFilasParciales(String codigos) throws Exception {
    var t = nueva("desarrollo-web-m1", true);
    assertThrows(CrmException.class, () -> pagos().generar(v1, t.nro(), codigos, null));
    assertEquals(0, count("SELECT count(*) FROM ordenes_pago WHERE nro_tarjeta=?", t.nro()));
  }

  @Test
  void identidadYPermisosDelComprobanteNoDependanDelIdAdivinado() throws Exception {
    var t = nueva("desarrollo-web-m1", true);
    var o = revisar(t);
    var otro = revisar(nueva("desarrollo-web-m1", true));
    int nro = (int) count("SELECT nro_comprobante FROM comprobantes_pago WHERE nro_orden=?", otro.nro());
    assertThrows(CrmException.class, () -> pagos().archivo(c1, o.nro(), nro));
    assertThrows(CrmException.class, () -> pagos().archivo(v2, o.nro(), null));
    assertThrows(CrmException.class, () -> pagos().aprobar(v1, o.nro(), "No permitido"));
  }

  @Test
  void bytesInvalidosYAdjuntoAusenteNoAlteranElPago() throws Exception {
    var t = nueva("desarrollo-web-m1", true);
    var o = pagos().generar(v1, t.nro(), null, null);
    assertThrows(
        CrmException.class,
        () ->
            pagos()
                .registrarComprobante(
                    v1,
                    o.nro(),
                    new Adjunto(
                        "falso.pdf",
                        "application/pdf",
                        "<script>fake</script>".getBytes(StandardCharsets.UTF_8)),
                    null));
    assertThrows(
        CrmException.class, () -> pagos().registrarComprobante(v1, o.nro(), null, null));
    assertEquals(0, count("SELECT count(*) FROM comprobantes_pago WHERE nro_orden=?", o.nro()));
    assertEquals("Pendiente de pago", pagos().ver(c1, o.nro()).estadoPago());
  }

  @Test
  void cambioDeResponsableRevocaElAccesoDelAnterior() throws Exception {
    var t = nueva("ciberseguridad-m1", true);
    var o = revisar(t);
    try {
      new ProgramaService(db).asignar(admin, "ciberseguridad", c2.correo());
      assertThrows(CrmException.class, () -> pagos().ver(c1, o.nro()));
      assertEquals(o.nro(), pagos().ver(c2, o.nro()).nro());
    } finally {
      new ProgramaService(db).asignar(admin, "ciberseguridad", c1.correo());
    }
  }
}
