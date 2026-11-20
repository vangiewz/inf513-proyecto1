package bo.uagrm.crm.integracion;

import static bo.uagrm.crm.persistencia.DatabaseService.*;
import static org.junit.jupiter.api.Assertions.*;

import bo.uagrm.crm.aplicacion.CommandProcessor;
import bo.uagrm.crm.aplicacion.Respuesta;
import bo.uagrm.crm.dominio.personas.PersonaService;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.dominio.usuarios.UsuarioService;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Contratos docentes contra PostgreSQL real desechable; nunca acepta el servidor institucional. */
@EnabledIfEnvironmentVariable(named = "P1_TEST_DB_URL", matches = ".+")
class PersonaIntegracionTest {
  static DatabaseService db;
  static Usuario admin;
  static PersonaService personas;
  static CommandProcessor cp;

  @BeforeAll
  static void init() throws Exception {
    String url = System.getenv("P1_TEST_DB_URL");
    assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:"), "Sólo PostgreSQL local desechable");
    db = new DatabaseService(url, System.getenv().getOrDefault("P1_TEST_DB_USER", "crm_user"),
        System.getenv().getOrDefault("P1_TEST_DB_PASSWORD", "crm_password"));
    db.verificarModelo();
    admin = new UsuarioService(db).insertar("Admin", "Personas", "persona." + UUID.randomUUID()
        + "@example.test", "70000001", "administrador");
    personas = new PersonaService(db);
    cp = new CommandProcessor(db);
  }

  private static String doc() {
    return String.valueOf(10_000_000 + new java.security.SecureRandom().nextInt(80_000_000));
  }

  @Test
  void insertaSieteCamposUtf8YConsultaPosterior() throws Exception {
    String doc = doc();
    String asunto = "INSPER[\"" + doc + "\",\"José \\\"Pepe\\\"\",\"Pérez Seras\",\"Estudiante\","
        + "\"33554433\",\"71055123\",\"" + doc + "@example.test\"]";
    Respuesta alta = cp.procesarEmail(admin.correo(), asunto, null);
    assertEquals("Éxito", alta.resultado());
    var encontrada = personas.listar(admin, "*").stream().filter(p -> p.documento().equals(doc)).findFirst().orElseThrow();
    assertEquals("José \"Pepe\"", encontrada.nombres());
    assertEquals("Pérez Seras", encontrada.apellidos());
    assertEquals("Estudiante", encontrada.ocupacion());
    assertEquals("33554433", encontrada.telefonoFijo());
    assertEquals("71055123", encontrada.telefonoCelular());
    Respuesta listado = cp.procesarEmail(admin.correo(), "LISPER[\"*\"]", null);
    assertEquals("Éxito", listado.resultado());
    assertTrue(((Respuesta.Tabla) listado.bloques().get(0)).filas().stream().anyMatch(f -> f[0].equals(doc)));
  }

  @Test
  void duplicadosDocumentoYCorreoSeTraducenSinReinsertar() throws Exception {
    String doc = doc(), otro = doc(), correo = doc + "@example.test";
    personas.insertar(admin, doc, "Ana", "QA", "Estudiante", "33554433", "71055123", correo);
    Respuesta duplicado = cp.procesarEmail(admin.correo(), "INSPER[\"" + doc
        + "\",\"Ana\",\"QA\",\"Estudiante\",\"33554433\",\"71055123\",\"otro" + correo + "\"]", null);
    assertEquals("Error", duplicado.resultado());
    assertTrue(duplicado.mensaje().contains("documento"));
    Respuesta correoDuplicado = cp.procesarEmail(admin.correo(), "INSPER[\"" + otro
        + "\",\"Ana\",\"QA\",\"Estudiante\",\"33554433\",\"71055123\",\"" + correo.toUpperCase() + "\"]", null);
    assertEquals("Error", correoDuplicado.resultado());
    assertTrue(correoDuplicado.mensaje().contains("correo"));
    try (Connection c = db.getConnection()) {
      assertEquals(Long.valueOf(1), uno(c, r -> r.getLong(1), "SELECT count(*) FROM persona WHERE documento IN (?,?)", doc, otro));
    }
  }

  @Test
  void transaccionExternaRevierteInsercionConfirmadaSoloDentroDeElla() throws Exception {
    String doc = doc();
    assertThrows(SQLException.class, () -> db.enTransaccion(c -> {
      personas.insertar(admin, doc, "Prueba", "Rollback", "Estudiante", "33554433", "71055123", doc + "@example.test");
      throw new SQLException("Interrupción local antes de COMMIT", "08006");
    }));
    assertTrue(personas.listar(admin, "*").stream().noneMatch(p -> p.documento().equals(doc)));
  }

  @Test
  void restriccionesSqlRechazanNullFormatoYLongitud() throws Exception {
    for (String[] caso : new String[][] {
        {null, "Ana", "23502"}, {"correo-invalido", "Ana", "23514"},
        {doc() + "@example.test", "A".repeat(121), "22001"}}) {
      try (Connection c = db.getConnection()) {
        c.setAutoCommit(false);
        SQLException ex = assertThrows(SQLException.class, () -> actualizar(c,
            "INSERT INTO persona(documento,nombres,apellidos,ocupacion,telefono_fijo,telefono_celular,correo)"
                + " VALUES(?,?,'QA','Estudiante','33554433','71055123',?)", doc(), caso[1], caso[0]));
        assertEquals(caso[2], ex.getSQLState());
        c.rollback();
      }
    }
  }

  @Test
  void sqlInjectionEsUnValorYNoUnaSentencia() throws Exception {
    String doc = doc();
    String nombre = "Ana'; DROP TABLE usuarios; --";
    personas.insertar(admin, doc, nombre, "QA", "Estudiante", "33554433", "71055123", doc + "@example.test");
    try (Connection c = db.getConnection()) {
      assertEquals(nombre, uno(c, r -> r.getString(1), "SELECT nombres FROM persona WHERE documento=?", doc));
    }
    db.verificarModelo();
  }

  @Test
  void lisperVacioRespondeSinRegistrosYConservaDatosAlRevertirQa() throws Exception {
    Long originales;
    try (Connection c = db.getConnection()) {
      originales = uno(c, r -> r.getLong(1), "SELECT count(*) FROM persona");
    }
    db.enTransaccion(c -> {
      try {
        String documento = doc();
        personas.insertar(admin, documento, "Prueba", "Vacío", "Estudiante", "33554433", "71055123",
            "vacio." + UUID.randomUUID() + "@example.test");
        Long antes = uno(c, r -> r.getLong(1), "SELECT count(*) FROM persona");
        assertTrue(antes > 0, "La conservación se demuestra con al menos una fila real de QA");
        java.sql.Savepoint restaurar = c.setSavepoint();
        try {
          // Sólo QA 127.0.0.1: el DELETE jamás se confirma, incluso si una aserción falla.
          actualizar(c, "DELETE FROM persona");
          Respuesta vacia = cp.procesarEmail(admin.correo(), "LISPER[\"*\"]", null);
          assertEquals("Éxito", vacia.resultado());
          assertEquals("No hay personas registradas.", vacia.mensaje());
          assertTrue(((Respuesta.Tabla) vacia.bloques().get(0)).filas().isEmpty());
        } finally { c.rollback(restaurar); }
        assertEquals(antes, uno(c, r -> r.getLong(1), "SELECT count(*) FROM persona"));
        assertEquals(Long.valueOf(1), uno(c, r -> r.getLong(1),
            "SELECT count(*) FROM persona WHERE documento=?", documento),
            "ROLLBACK al savepoint recupera la fila que estaba presente antes del vaciado");
        return null;
      } finally { c.rollback(); } // También retira el fixture, sin dejar datos de este caso.
    });
    try (Connection c = db.getConnection()) {
      assertEquals(originales, uno(c, r -> r.getLong(1), "SELECT count(*) FROM persona"),
          "La prueba de vacío no elimina registros previamente existentes en QA");
    }
  }
}
