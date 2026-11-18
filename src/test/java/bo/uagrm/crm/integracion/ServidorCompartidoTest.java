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
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Mismo PostgreSQL del docente; todas las escrituras quedan en el esquema QA reservado. */
@EnabledIfEnvironmentVariable(named = "P1_REMOTE_DB_PASSWORD", matches = ".+")
class ServidorCompartidoTest {
  static DatabaseService db;
  static Usuario admin, coord, v1, v2;
  static long sec = 1000000000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode());

  static synchronized String doc() {
    return "" + (sec++);
  }

  static long count(String s, Object... args) throws Exception {
    try (Connection c = db.getConnection()) {
      return uno(c, r -> r.getLong(1), s, args);
    }
  }

  static Usuario user(String rol) throws Exception {
    return new UsuarioService(db)
        .insertar(rol, "QA Remoto", doc() + "@example.test", "70000001", rol);
  }

  static Interes alta() throws Exception {
    String d = doc();
    return new ProspectoService(db)
        .registrar(
            admin,
            d,
            "Prueba",
            "Remota",
            "70000001",
            d + "@example.test",
            "Correo",
            "desarrollo-web-m1")
        .tarjeta();
  }

  @BeforeAll
  static void init() throws Exception {
    db =
        new DatabaseService(
            System.getenv().getOrDefault("P1_REMOTE_DB_URL", "jdbc:postgresql://db.tecnoweb.org.bo:5432/db_grupo05sa?currentSchema=crm_qa_correo&ApplicationName=crm-mail-qa"),
            System.getenv().getOrDefault("P1_REMOTE_DB_USER", "grupo05sa"),
            System.getenv("P1_REMOTE_DB_PASSWORD"));
    try (Connection c = db.getConnection()) {
      assertEquals("crm_qa_correo", uno(c, r -> r.getString(1), "SELECT current_schema()"));
    }
    db.verificarModelo();
    admin = user("administrador");
    coord = user("coordinador");
    v1 = user("vendedor");
    v2 = user("vendedor");
    try (Connection c = db.getConnection()) {
      for (String codigo :
          lista(c, r -> r.getString(1), "SELECT DISTINCT programa_codigo FROM catalogo_cache"))
        new ProgramaService(db).asignar(admin, codigo, coord.correo());
    }
  }

  @Test
  void cu1IdentidadYBajaRemotas() throws Exception {
    var u = user("vendedor");
    var service = new UsuarioService(db);
    assertEquals(u.correo(), service.buscarPorCorreo(u.correo().toUpperCase()).correo());
    service.eliminar(admin, u.correo());
    assertFalse(service.buscarPorCorreo(u.correo()).activo());
  }

  @Test
  void cu2ProgramaCompletoYRegistroPublico() throws Exception {
    String d = doc();
    var a =
        new ProspectoService(db)
            .registrar(
                null, d, "Publico", "QA", "70000001", d + "@example.test", "Correo", "ia-aplicada");
    assertEquals(5, a.interesesCreados());
    assertEquals(
        5,
        count("SELECT count(*) FROM intereses WHERE documento=?", a.tarjeta().prospecto().ci()));
  }

  @Test
  void cu3AsignacionYReasignacionRemotas() throws Exception {
    var t = alta();
    var service = new AsignacionService(db);
    assertEquals(v1.correo(), service.asignar(coord, t.nro(), v1.correo()).tarjeta().vendedorCorreo());
    assertEquals(v2.correo(), service.asignar(admin, t.nro(), v2.correo()).tarjeta().vendedorCorreo());
    assertThrows(CrmException.class, () -> service.asignar(v1, t.nro(), v1.correo()));
  }

  @Test
  void cu4AliasYProteccionDeCartera() throws Exception {
    var t = alta();
    new AsignacionService(db).asignar(admin, t.nro(), v1.correo());
    var s = new EmbudoService(db);
    assertEquals("Contactado", s.mover(v1, t.nro(), "En Contacto").estado());
    assertThrows(CrmException.class, () -> s.mover(v2, t.nro(), "Interesado"));
  }

  @Test
  void cu5PerdidaClasificadaConHistorial() throws Exception {
    var t = alta();
    new AsignacionService(db).asignar(admin, t.nro(), v1.correo());
    var p = new PerdidaService(db);
    var m = p.insertarMotivo("Motivo remoto " + doc(), "Horario");
    assertEquals("Perdido", p.marcarPerdido(v1, t.nro(), m.nombre(), "QA").estado());
    assertThrows(CrmException.class, () -> p.eliminarMotivo(m.nombre()));
    assertEquals("Interesado", new EmbudoService(db).mover(v1, t.nro(), "Seguimiento").estado());
  }

  @Test
  void cu6NoConvierteSinPagoEnServidorReal() throws Exception {
    long antes = count("SELECT count(*) FROM inscripciones_comerciales");
    var t = alta();
    new AsignacionService(db).asignar(admin, t.nro(), v1.correo());
    assertThrows(CrmException.class, () -> new ConversionService(db).convertir(v1, t.nro()));
    assertEquals(antes, count("SELECT count(*) FROM inscripciones_comerciales"));
  }

  @Test
  void cu7InteraccionRecordatorioYFinalizacion() throws Exception {
    var t = alta();
    new AsignacionService(db).asignar(admin, t.nro(), v1.correo());
    var s = new SeguimientoService(db);
    s.registrarInteraccion(v1, t.nro(), "Nota", "Prueba JDBC real");
    var r = s.programarRecordatorio(v1, t.nro(), "2090-01-10 10:00", "Prueba remota");
    assertTrue(r.fechaHora().contains("10:00"));
    assertEquals("Realizada", s.completarRecordatorio(v1, r.nro(), "Verificado").estado());
    assertFalse(s.historial(v1, t.nro()).eventos().isEmpty());
  }

  @Test
  void cu8TodosLosReportesConPostgreSqlDelDocente() throws Exception {
    var s = new ReporteService(db);
    assertEquals(6, s.embudo(admin).size());
    s.vendedores(admin);
    s.oferta(admin);
    s.perdidasPorCategoria(admin);
    s.perdidasPorMotivo(admin);
  }

  @Test
  void despachadorYBitacoraDeComandosReales() throws Exception {
    var cp = new CommandProcessor(db);
    for (String cmd :
        List.of(
            "HELP[]",
            "LISTAROFERTA[]",
            "VERTABLERO[]",
            "LISTARMATRICULAS[]",
            "VERBITACORA[]",
            "LISTARRECORDATORIOS[]"))
      assertEquals("Éxito", cp.procesarEmail(admin.correo(), cmd, null).resultado(), cmd);
    assertEquals(
        "Denegado",
        cp.procesarEmail(
                v1.correo(),
                "INSERTARUSUARIO[\"A\",\"B\",\"ab@example.test\",\"70000001\",\"vendedor\"]",
                null)
            .resultado());
    assertTrue(count("SELECT count(*) FROM log_comandos") >= 7);
  }

  @Test
  void esquemaAntiguoSeRechazaAntesDeLeerElBuzon() throws Exception {
    var old =
        new DatabaseService(
            System.getenv()
                .getOrDefault("P1_REMOTE_DB_URL", "jdbc:postgresql://db.tecnoweb.org.bo:5432/db_grupo05sa?currentSchema=crm_qa_correo")
                .replaceFirst("currentSchema=[^&]*", "currentSchema=crm"),
            System.getenv().getOrDefault("P1_REMOTE_DB_USER", "grupo05sa"),
            System.getenv("P1_REMOTE_DB_PASSWORD"));
    assertThrows(SQLException.class, old::verificarModelo);
  }
}
