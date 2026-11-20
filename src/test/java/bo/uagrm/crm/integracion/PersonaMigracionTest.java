package bo.uagrm.crm.integracion;

import static bo.uagrm.crm.persistencia.DatabaseService.*;
import static org.junit.jupiter.api.Assertions.*;

import bo.uagrm.crm.persistencia.DatabaseService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Adversarios del contrato de migración, sólo en QA local y siempre con ROLLBACK. */
@EnabledIfEnvironmentVariable(named = "P1_TEST_DB_URL", matches = ".+")
class PersonaMigracionTest {
  static DatabaseService db;
  static String migracion;

  @BeforeAll
  static void init() throws Exception {
    String url = System.getenv("P1_TEST_DB_URL");
    assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:"), "Sólo PostgreSQL local desechable");
    db = new DatabaseService(url, System.getenv().getOrDefault("P1_TEST_DB_USER", "crm_user"),
        System.getenv().getOrDefault("P1_TEST_DB_PASSWORD", "crm_password"));
    migracion = Files.readString(Path.of("sql/migraciones/2026-11-19_persona.sql"), StandardCharsets.UTF_8);
  }

  @Test
  void contratoCompatiblePuedeReaplicarseSinCambiarDatos() throws Exception {
    try (Connection c = db.getConnection()) {
      c.setAutoCommit(false);
      try {
        Long antes = uno(c, r -> r.getLong(1), "SELECT count(*) FROM persona");
        String metadatos = uno(c, r -> r.getString(1),
            "SELECT row_to_json(c)::text FROM configuraciones c WHERE clave='schema.persona.version'");
        try (var sentencia = c.createStatement()) {
          sentencia.execute(migracion);
          sentencia.execute(migracion); // También dos veces dentro de la misma transacción.
        }
        assertEquals(antes, uno(c, r -> r.getLong(1), "SELECT count(*) FROM persona"));
        assertEquals(metadatos, uno(c, r -> r.getString(1),
            "SELECT row_to_json(c)::text FROM configuraciones c WHERE clave='schema.persona.version'"));
      } finally { c.rollback(); }
    }
  }

  @Test
  void nombreCorrectoConCheckVerdaderoNoCumpleContrato() throws Exception {
    rechaza("ALTER TABLE public.persona DROP CONSTRAINT persona_documento_check;"
        + " ALTER TABLE public.persona ADD CONSTRAINT persona_documento_check CHECK(true)");
  }

  @Test
  void indiceUnicoParcialNoEquivaleAUnicidadGlobal() throws Exception {
    rechaza("DROP INDEX public.persona_correo_lower_unique;"
        + " CREATE UNIQUE INDEX persona_correo_lower_unique ON public.persona(lower(correo)) WHERE correo<>''");
  }

  @Test
  void indiceDeDosColumnasNoGarantizaCorreoUnico() throws Exception {
    rechaza("DROP INDEX public.persona_correo_lower_unique;"
        + " CREATE UNIQUE INDEX persona_correo_lower_unique ON public.persona(lower(correo),documento)");
  }

  @Test
  void indiceSinLowerNoGarantizaUnicidadSinDistinguirMayusculas() throws Exception {
    rechaza("DROP INDEX public.persona_correo_lower_unique;"
        + " CREATE UNIQUE INDEX persona_correo_lower_unique ON public.persona(correo)");
  }

  @Test
  void versionPosteriorNoSeSobrescribe() throws Exception {
    try (Connection c = db.getConnection()) {
      c.setAutoCommit(false);
      try {
        actualizar(c, "UPDATE configuraciones SET valor='2' WHERE clave='schema.persona.version'");
        try (var sentencia = c.createStatement()) {
          SQLException error = assertThrows(SQLException.class, () -> sentencia.execute(migracion));
          assertEquals("P0001", error.getSQLState());
          assertTrue(error.getMessage().contains("Versión persona desconocida"));
        }
      } finally { c.rollback(); }
    }
  }

  private static void rechaza(String alteracionQa) throws Exception {
    try (Connection c = db.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (var sentencia = c.createStatement()) {
          sentencia.execute(alteracionQa);
          SQLException error = assertThrows(SQLException.class, () -> sentencia.execute(migracion));
          assertEquals("P0001", error.getSQLState());
          assertTrue(error.getMessage().contains("no coincide con la estructura aprobada"));
        }
      } finally { c.rollback(); }
    }
  }
}
