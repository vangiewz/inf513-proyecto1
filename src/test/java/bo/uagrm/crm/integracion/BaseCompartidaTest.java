package bo.uagrm.crm.integracion;

import static bo.uagrm.crm.persistencia.DatabaseService.*;
import static org.junit.jupiter.api.Assertions.*;

import bo.uagrm.crm.aplicacion.*;
import bo.uagrm.crm.dominio.*;
import bo.uagrm.crm.dominio.asignacion.*;
import bo.uagrm.crm.dominio.captacion.*;
import bo.uagrm.crm.dominio.conversion.*;
import bo.uagrm.crm.dominio.embudo.*;
import bo.uagrm.crm.dominio.perdidas.*;
import bo.uagrm.crm.dominio.reportes.*;
import bo.uagrm.crm.dominio.seguimiento.*;
import bo.uagrm.crm.dominio.usuarios.*;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * PostgreSQL real + HTTP real de Laravel. Solo admite la base desechable crm_qa_audit en localhost.
 */
@EnabledIfEnvironmentVariable(named = "P1_TEST_DB_URL", matches = ".+")
class BaseCompartidaTest {
  static DatabaseService db;
  static Usuario admin, coord, v1, v2;
  static final String tag = UUID.randomUUID().toString().substring(0, 8);
  static String token;
  static int seq;

  static synchronized String doc() {
    return Long.toString(1000000000L + Integer.toUnsignedLong(tag.hashCode()) + (++seq));
  }

  static String email(String d) {
    return d.toLowerCase() + "@example.test";
  }

  static long count(String sql, Object... args) throws SQLException {
    try (Connection c = db.getConnection()) {
      return uno(c, r -> r.getLong(1), sql, args);
    }
  }

  static void sql(String sql, Object... args) throws SQLException {
    try (Connection c = db.getConnection()) {
      actualizar(c, sql, args);
    }
  }

  static ProspectoService ps() {
    return new ProspectoService(db);
  }

  static EmbudoService es() {
    return new EmbudoService(db);
  }

  static AsignacionService as() {
    return new AsignacionService(db);
  }

  static SeguimientoService ss() {
    return new SeguimientoService(db);
  }

  static Usuario user(String rol) throws Exception {
    return new UsuarioService(db).insertar(rol, "QA", email(doc()), "70000001", rol);
  }

  static Interes alta(String oferta) throws Exception {
    String d = doc();
    return ps().registrar(
            admin,
            d,
            "Persona",
            "QA",
            "70000001",
            email(d),
            "Correo",
            oferta,
            "Ingenieria",
            "Santa Cruz")
        .tarjeta();
  }

  static Interes propia() throws Exception {
    var t = alta("desarrollo-web-m1");
    return as().asignar(admin, t.id(), v1.id()).tarjeta();
  }

  static HttpResponse<String> http(String method, String path, String body, String bearer)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:18002/api" + path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json");
    if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
    return HttpClient.newHttpClient()
        .send(
            builder
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  static String bearer(Usuario u) throws Exception {
    String plain = UUID.randomUUID().toString() + UUID.randomUUID();
    String hash =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    sql(
        "INSERT INTO api_tokens(usuario_id,token_hash,expira_en,created_at,updated_at)"
            + " VALUES(?,?,now()+interval '1 hour',now(),now())",
        u.id(),
        hash);
    return plain;
  }

  @BeforeAll
  static void setup() throws Exception {
    String url = System.getenv("P1_TEST_DB_URL");
    if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:55432/crm_qa_audit(?:\\?.*)?"))
      throw new IllegalStateException("Solo base QA local");
    db = new DatabaseService(url, "crm_user", "crm_password");
    assertEquals("crm_qa_audit", query("SELECT current_database()"));
    admin = user("administrador");
    coord = user("coordinador");
    v1 = user("vendedor");
    v2 = user("vendedor");
    try (Connection c = db.getConnection()) {
      for (String codigo :
          lista(c, r -> r.getString(1), "SELECT DISTINCT programa_codigo FROM catalogo_cache"))
        new ProgramaService(db).asignar(admin, codigo, coord.id());
    }
    token = bearer(admin);
    assertTrue(count("SELECT count(*) FROM catalogo_cache") > 0);
  }

  static String query(String sql, Object... args) throws SQLException {
    try (Connection c = db.getConnection()) {
      return uno(c, r -> r.getString(1), sql, args);
    }
  }

  @Test
  void usuariosCorreoSonVisiblesEnWebYPasswordNoEsComun() throws Exception {
    var u = user("vendedor");
    var r = http("GET", "/usuarios?buscar=" + u.correo(), null, token);
    assertEquals(200, r.statusCode(), r.body());
    assertTrue(r.body().contains(u.correo()));
    assertFalse(r.body().contains("password"));
    r =
        http(
            "POST",
            "/login",
            "{\"correo\":\"" + u.correo() + "\",\"password\":\"Admin12345\"}",
            null);
    assertEquals(422, r.statusCode(), r.body());
  }

  @Test
  void usuarioWebPuedeEnviarComandos() throws Exception {
    String correo = email(doc());
    var r =
        http(
            "POST",
            "/usuarios",
            "{\"nombre\":\"Web\",\"apellido\":\"QA\",\"correo\":\""
                + correo
                + "\",\"password\":\"Clave12345\",\"rol\":\"vendedor\",\"estado\":\"activo\"}",
            token);
    assertEquals(201, r.statusCode(), r.body());
    assertEquals(
        "Éxito", new CommandProcessor(db).procesarEmail(correo, "VERTABLERO[]", null).resultado());
  }

  @Test
  void moduloCorreoVisibleEnLaravelYMismaFilaActualizada() throws Exception {
    var t = propia();
    var r = http("GET", "/intereses/" + t.id(), null, token);
    assertEquals(200, r.statusCode(), r.body());
    assertTrue(r.body().contains(t.prospecto().correo()));
    r = http("PATCH", "/intereses/" + t.id() + "/estado", "{\"estado\":\"Interesado\"}", token);
    assertEquals(200, r.statusCode(), r.body());
    assertEquals(
        "Interesado",
        es().tablero(v1).stream().filter(x -> x.id() == t.id()).findFirst().orElseThrow().estado());
    es().mover(v1, t.id(), "En Contacto");
    assertEquals("Contactado", query("SELECT estado FROM intereses WHERE id=?", t.id()));
  }

  @Test
  void registroWebLeidoPorCorreo() throws Exception {
    String d = doc();
    var r =
        http(
            "POST",
            "/registro-interes",
            "{\"nombre\":\"Web\",\"apellido\":\"QA\",\"documento\":\""
                + d
                + "\",\"correo\":\""
                + email(d)
                + "\",\"telefono\":\"70000001\",\"profesion\":\"Ingenieria\",\"ubicacion\":\"SCZ\",\"modulo_id\":\"ia-aplicada-m1\"}",
            null);
    assertEquals(201, r.statusCode(), r.body());
    assertEquals(1, ps().listar(admin, d).size());
  }

  @Test
  void programaCompletoCreaCincoInteresesSinDuplicarPersona() throws Exception {
    String d = doc();
    var a = ps().registrar(null, d, "Publico", "QA", "70000001", email(d), "Correo", "datos-bi");
    assertEquals(5, a.interesesCreados());
    assertEquals(1, count("SELECT count(*) FROM prospectos WHERE documento=?", d));
    assertEquals(
        5,
        count("SELECT count(*) FROM intereses WHERE prospecto_id=?", a.tarjeta().prospecto().id()));
    assertThrows(
        CrmException.class,
        () -> ps().registrar(null, d, "Publico", "QA", "70000001", email(d), "Correo", "datos-bi"));
  }

  @Test
  void identidadAjenaNoAgregaInteresesNiSobrescribeDatos() throws Exception {
    var t = alta("desarrollo-web-m1");
    assertThrows(
        CrmException.class,
        () ->
            ps().registrar(
                    null,
                    t.prospecto().ci(),
                    "Intruso",
                    "QA",
                    "70000002",
                    "otro@example.test",
                    "Correo",
                    "ia-aplicada-m1"));
    assertEquals(
        1, count("SELECT count(*) FROM intereses WHERE prospecto_id=?", t.prospecto().id()));
  }

  @Test
  void ofertaCerradaYDesconocidaNoCreanProspectos() throws Exception {
    String d = doc();
    assertThrows(
        CrmException.class,
        () -> ps().registrar(null, d, "QA", "QA", "70000001", email(d), "Correo", "no-existe"));
    assertEquals(0, count("SELECT count(*) FROM prospectos WHERE documento=?", d));
  }

  @Test
  void vendedorNoVeBolsaNiCarteraAjenaNiSeAutoasigna() throws Exception {
    var t = alta("desarrollo-web-m1");
    assertTrue(ps().listar(v1, t.prospecto().ci()).isEmpty());
    assertThrows(CrmException.class, () -> as().asignar(v1, t.id(), v1.id()));
    as().asignar(admin, t.id(), v2.id());
    assertThrows(CrmException.class, () -> es().mover(v1, t.id(), "Contactado"));
    assertThrows(CrmException.class, () -> ss().historial(v1, t.id()));
  }

  @Test
  void reasignacionConservaContactoYCambiaResponsablePendiente() throws Exception {
    var t = propia();
    var x = ss().registrarInteraccion(v1, t.id(), "Nota", "Contacto inicial");
    var r = ss().programarRecordatorio(v1, t.id(), "2090-01-10 10:00", "Seguimiento");
    as().asignar(coord, t.id(), v2.id());
    assertEquals(v1.id(), count("SELECT usuario_id FROM interacciones WHERE id=?", x.id()));
    assertEquals(v2.id(), count("SELECT usuario_id FROM recordatorios WHERE id=?", r.id()));
    assertThrows(CrmException.class, () -> ss().completarRecordatorio(v1, r.id(), "No autorizado"));
    assertEquals(
        "Realizada", ss().completarRecordatorio(v2, r.id(), "Contacto realizado").estado());
  }

  @Test
  void recordatorioJavaVisibleEnWebYHoraBolivianaCorrecta() throws Exception {
    var t = propia();
    var r = ss().programarRecordatorio(v1, t.id(), "2090-01-10 10:00", "Hora local QA");
    assertTrue(
        query("SELECT fecha_programada::text FROM recordatorios WHERE id=?", r.id())
            .contains("14:00"));
    var h = http("GET", "/recordatorios?fecha=2090-01-10", null, token);
    assertEquals(200, h.statusCode(), h.body());
    assertTrue(h.body().contains("Hora local QA"));
    assertTrue(r.fechaHora().contains("10:00"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-02-30 10:00",
        "2090-13-01 10:00",
        "2090-01-01 25:00",
        "ayer",
        "2000-01-01 10:00"
      })
  void fechasInvalidasNoGeneranActividad(String fecha) throws Exception {
    var t = propia();
    assertThrows(CrmException.class, () -> ss().programarRecordatorio(v1, t.id(), fecha, "QA"));
    assertEquals(0, count("SELECT count(*) FROM recordatorios WHERE interes_id=?", t.id()));
  }

  @Test
  void completarEsAtomicoYNoSeRepite() throws Exception {
    var t = propia();
    var r = ss().programarRecordatorio(v1, t.id(), "2090-01-10 10:00", "QA completar");
    assertThrows(CrmException.class, () -> ss().completarRecordatorio(v1, r.id(), ""));
    ss().completarRecordatorio(v1, r.id(), "Se llamo");
    assertThrows(CrmException.class, () -> ss().completarRecordatorio(v1, r.id(), "Otra llamada"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM interacciones WHERE interes_id=? AND resultado='Se llamo'",
            t.id()));
  }

  @Test
  void perdidaClasificadaYReactivacionConHistorial() throws Exception {
    var t = propia();
    var p = new PerdidaService(db);
    var m = p.insertarMotivo("Horario " + doc(), "Horario");
    assertEquals("Perdido", p.marcarPerdido(v1, t.id(), m.id(), "Trabaja de noche").estado());
    assertThrows(CrmException.class, () -> p.eliminarMotivo(m.id()));
    assertEquals("Interesado", es().mover(v1, t.id(), "Seguimiento").estado());
    assertNull(query("SELECT motivo_perdida_id::text FROM intereses WHERE id=?", t.id()));
    assertTrue(ss().historial(v1, t.id()).eventos().size() >= 4);
  }

  @Test
  void conversionNoEvitaPagosNiCreaInscripciones() throws Exception {
    var t = propia();
    long antes = count("SELECT count(*) FROM inscripciones_comerciales");
    assertThrows(CrmException.class, () -> new ConversionService(db).convertir(v1, t.id()));
    for (String etapa :
        List.of("Convertido", "Perdido", "Pago enviado", "En revision", "Promesa de Pago"))
      assertThrows(CrmException.class, () -> es().mover(v1, t.id(), etapa));
    assertEquals(antes, count("SELECT count(*) FROM inscripciones_comerciales"));
  }

  @Test
  void reportesNoPierdenInteresesConCatalogoDesactualizado() throws Exception {
    var t = propia();
    sql("UPDATE intereses SET external_modulo_id=? WHERE id=?", "retirado-" + doc(), t.id());
    var rs = new ReporteService(db);
    assertFalse(rs.oferta(admin).isEmpty());
    assertEquals(6, rs.embudo(admin).size());
    rs.vendedores(admin);
    rs.perdidasPorCategoria(admin);
    rs.perdidasPorMotivo(admin);
    assertEquals(
        es().tablero(admin).size(),
        rs.embudo(admin).stream().mapToInt(a -> Integer.parseInt(a[1])).sum());
  }

  @Test
  void bajaUsuarioLiberaCarteraYRevocaAcceso() throws Exception {
    Usuario v = user("vendedor");
    var t = alta("desarrollo-web-m1");
    as().asignar(admin, t.id(), v.id());
    assertEquals(1, new UsuarioService(db).eliminar(admin, v.id()));
    assertNull(query("SELECT vendedor_id::text FROM intereses WHERE id=?", t.id()));
    assertEquals(
        "Denegado",
        new CommandProcessor(db).procesarEmail(v.correo(), "VERTABLERO[]", null).resultado());
  }

  @Test
  void importacionMixtaHaceRollbackPorFila() throws Exception {
    String d = doc();
    var rows =
        ps().importar(
                admin,
                "ci,nombre,apellido,telefono,correo,origen,codigo_oferta\n"
                    + d
                    + ",Ana,QA,70000001,"
                    + email(d)
                    + ",Correo,desarrollo-web-m1\n"
                    + "MAL,Ana,QA,abc,no-es-email,Correo,desarrollo-web-m1");
    assertEquals(2, rows.size());
    assertTrue(rows.get(0)[2].startsWith("Tarjeta"));
    assertTrue(rows.get(1)[2].startsWith("Error"));
  }

  @Test
  void comandosRechazadosEInvalidosQuedanEnBitacora() throws Exception {
    var cp = new CommandProcessor(db);
    assertEquals(
        "Denegado", cp.procesarEmail(v1.correo(), "ASIGNARPROSPECTO[\"1\",\"1\"]", null).resultado());
    assertEquals("Error", cp.procesarEmail(v1.correo(), "NOEXISTE[]", null).resultado());
    assertTrue(count("SELECT count(*) FROM log_comandos WHERE usuario_id=?", v1.id()) >= 2);
  }

  @Test
  void inyeccionSqlPermaneceComoDato() throws Exception {
    String d = doc();
    ps().registrar(
            admin,
            d,
            "O'Connor; DROP TABLE usuarios; --",
            "QA",
            "70000001",
            email(d),
            "Correo",
            "desarrollo-web-m1");
    assertTrue(count("SELECT count(*) FROM usuarios") > 0);
    assertEquals(1, count("SELECT count(*) FROM prospectos WHERE documento=?", d));
  }

  @Test
  void altaConcurrenteNoDuplicaPersonaNiInteres() throws Exception {
    String d = doc();
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> task =
          () -> {
            try {
              ps().registrar(
                      null,
                      d,
                      "Carrera",
                      "QA",
                      "70000001",
                      email(d),
                      "Correo",
                      "desarrollo-web-m1");
              return true;
            } catch (CrmException | SQLException e) {
              return false;
            }
          };
      var runs = pool.invokeAll(List.of(task, task));
      assertEquals(
          1,
          runs.stream()
              .filter(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .count());
      assertEquals(1, count("SELECT count(*) FROM prospectos WHERE documento=?", d));
      assertEquals(
          1,
          count(
              "SELECT count(*) FROM intereses i JOIN prospectos p ON p.id=i.prospecto_id WHERE"
                  + " p.documento=?",
              d));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void lecturaDeTodosLosComandosSinErroresDeEsquema() throws Exception {
    var cp = new CommandProcessor(db);
    for (String command :
        List.of(
            "HELP[]",
            "LISTARUSUARIOS[]",
            "LISTAROFERTA[]",
            "LISTARPROSPECTOS[]",
            "LISTARBOLSA[]",
            "VERTABLERO[]",
            "LISTARMOTIVOS[]",
            "LISTARMATRICULAS[]",
            "LISTARRECORDATORIOS[]",
            "VERBITACORA[]",
            "REPORTEEMBUDO[]",
            "REPORTEVENDEDORES[]",
            "REPORTEPERDIDAS[]",
            "REPORTEOFERTA[]")) {
      var r = cp.procesarEmail(admin.correo(), command, null);
      assertEquals("Éxito", r.resultado(), command + ": " + r.mensaje());
    }
  }

  @Test
  void pagoRealPorApiVisibleComoInscripcionEnCorreoYNoSeDuplica() throws Exception {
    var t = propia();
    String vendedorToken = bearer(v1);
    var r =
        http(
            "POST",
            "/intereses/" + t.id() + "/ordenes-pago",
            "{\"modulos\":[\"desarrollo-web-m1\"],\"descuentos\":[]}",
            vendedorToken);
    assertEquals(201, r.statusCode(), r.body());
    int pago =
        (int)
            count(
                "SELECT pg.id FROM pagos pg JOIN ordenes_pago op ON op.id=pg.orden_pago_id WHERE"
                    + " op.interes_id=?",
                t.id());
    assertThrows(CrmException.class, () -> new ConversionService(db).convertir(v1, t.id()));
    String png =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a01cAAAAASUVORK5CYII=";
    r =
        http(
            "POST",
            "/comprobantes",
            "{\"pago_id\":" + pago + ",\"archivo\":\"" + png + "\",\"nombre_archivo\":\"qa.png\"}",
            vendedorToken);
    assertEquals(201, r.statusCode(), r.body());
    r = http("PATCH", "/pagos/" + pago + "/aprobar", "{}", bearer(coord));
    assertEquals(200, r.statusCode(), r.body());
    var cs = new ConversionService(db);
    var m = cs.convertir(v1, t.id());
    assertEquals("Confirmada", m.estado());
    assertEquals(m.id(), cs.convertir(v1, t.id()).id());
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON"
                + " sc.id=ic.solicitud_compra_id JOIN ordenes_pago op ON op.id=sc.orden_pago_id"
                + " WHERE op.interes_id=?",
            t.id()));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM comisiones co JOIN solicitudes_compra sc ON"
                + " sc.id=co.solicitud_compra_id JOIN ordenes_pago op ON op.id=sc.orden_pago_id"
                + " WHERE op.interes_id=?",
            t.id()));
    assertThrows(CrmException.class, () -> es().mover(v1, t.id(), "Nuevo"));
  }

  @Test
  void perdidaHechaEnWebSeClasificaYSeVeEnHistorialDeCorreo() throws Exception {
    var t = propia();
    var r =
        http(
            "PATCH",
            "/intereses/" + t.id() + "/estado",
            "{\"estado\":\"Perdido\",\"observacion\":\"Sin disponibilidad\"}",
            token);
    assertEquals(200, r.statusCode(), r.body());
    assertNotNull(query("SELECT motivo_perdida_id::text FROM intereses WHERE id=?", t.id()));
    assertTrue(
        ss().historial(v1, t.id()).eventos().stream()
            .anyMatch(a -> String.join(" ", a).contains("Cambio desde web")));
  }

  @Test
  void asignacionAutomaticaAgrupaInteresesDelMismoProspecto() throws Exception {
    String d = doc();
    var a = ps().registrar(admin, d, "Programa", "QA", "70000001", email(d), "Correo", "datos-bi");
    as().asignarAutomatico(coord);
    assertEquals(
        1,
        count(
            "SELECT count(DISTINCT vendedor_id) FROM intereses WHERE prospecto_id=?",
            a.tarjeta().prospecto().id()));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM intereses WHERE vendedor_id IS NULL AND estado NOT IN"
                + " ('Convertido','Perdido')"));
  }

  @Test
  void dosFinalizacionesConcurrentesDejanUnaSolaNota() throws Exception {
    var t = propia();
    var r = ss().programarRecordatorio(v1, t.id(), "2090-01-10 10:00", "Concurrencia");
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> task =
          () -> {
            try {
              ss().completarRecordatorio(v1, r.id(), "Resultado unico");
              return true;
            } catch (CrmException e) {
              return false;
            }
          };
      int exitos = 0;
      for (var f : pool.invokeAll(List.of(task, task))) if (f.get()) exitos++;
      assertEquals(1, exitos);
      assertEquals(
          1,
          count(
              "SELECT count(*) FROM interacciones WHERE interes_id=? AND resultado='Resultado"
                  + " unico'",
              t.id()));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aliasDeTiposYCoordinadorRespetaListadoDeVendedores() throws Exception {
    var t = propia();
    for (String tipo : List.of("Correo", "Reunión", "Nota"))
      ss().registrarInteraccion(v1, t.id(), tipo, "Alias QA");
    var cp = new CommandProcessor(db);
    assertEquals(
        "Error",
        cp.procesarEmail(coord.correo(), "LISTARUSUARIOS[\"administrador\"]", null).resultado());
    assertEquals("Éxito", cp.procesarEmail(coord.correo(), "LISTARUSUARIOS[]", null).resultado());
  }

  @Test
  void modificarUsuarioActualizaDatosYRevocaTokens() throws Exception {
    var u = user("vendedor");
    String auth = bearer(u);
    var us = new UsuarioService(db);
    var mod =
        us.modificar(
            admin, u.id(), "Nombre", "Nuevo", u.correo(), "70000002", "coordinador", "activo");
    assertEquals(Rol.COORDINADOR, mod.rol());
    assertEquals("Nombre", mod.nombre());
    assertEquals(401, http("GET", "/me", null, auth).statusCode());
    assertThrows(
        CrmException.class,
        () ->
            us.modificar(
                v1, u.id(), "Intruso", "QA", u.correo(), "70000002", "vendedor", "activo"));
  }

  @Test
  void motivoCrudValidaDuplicadosYCategoria() throws Exception {
    var p = new PerdidaService(db);
    var m = p.insertarMotivo("Motivo " + doc(), "Otro");
    assertEquals("Horario", p.modificarMotivo(m.id(), m.nombre(), "Horario").categoria());
    assertThrows(java.sql.SQLException.class, () -> p.insertarMotivo(m.nombre(), "Otro"));
    assertThrows(CrmException.class, () -> p.insertarMotivo("QA", "NoExiste"));
    assertEquals(m.id(), p.eliminarMotivo(m.id()).id());
  }

  @Test
  void csvConComillasRotasRechazaSoloEsaFila() throws Exception {
    String d = doc();
    var filas =
        ps().importar(
                admin,
                "ci,nombre,apellido,telefono,correo,origen,codigo_oferta\n"
                    + "\"sin-cerrar,Ana,QA,70000001,mail@example.test,Correo,desarrollo-web-m1\n"
                    + d
                    + ",Ana,QA,70000001,"
                    + email(d)
                    + ",Correo,desarrollo-web-m1");
    assertEquals(2, filas.size());
    assertTrue(filas.get(0)[2].startsWith("Error"));
    assertTrue(filas.get(1)[2].startsWith("Tarjeta"));
  }

  @Test
  void modificarProspectoSeVeEnWebYConservaIntereses() throws Exception {
    var t = propia();
    ps().modificar(
            v1,
            t.prospecto().ci(),
            "Nuevo nombre",
            "QA",
            "70000002",
            t.prospecto().correo(),
            "Referido");
    var r = http("GET", "/intereses/" + t.id(), null, token);
    assertEquals(200, r.statusCode());
    assertTrue(r.body().contains("Nuevo nombre"));
    assertThrows(CrmException.class, () -> ps().eliminar(t.prospecto().ci()));
  }
}
