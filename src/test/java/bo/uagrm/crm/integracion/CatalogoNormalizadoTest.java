package bo.uagrm.crm.integracion;

import static bo.uagrm.crm.persistencia.DatabaseService.*;
import static org.junit.jupiter.api.Assertions.*;

import bo.uagrm.crm.dominio.captacion.Oferta;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Catálogo normalizado, sin perder las instantáneas comerciales. Solo PostgreSQL QA local. */
@EnabledIfEnvironmentVariable(named = "P1_TEST_DB_URL", matches = ".+")
class CatalogoNormalizadoTest {
  static DatabaseService db;

  @BeforeAll
  static void init() {
    String url = System.getenv("P1_TEST_DB_URL");
    assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:"), "Solo QA local desechable");
    db = new DatabaseService(url, System.getenv().getOrDefault("P1_TEST_DB_USER", "crm_user"),
        System.getenv().getOrDefault("P1_TEST_DB_PASSWORD", "crm_password"));
  }

  @Test
  void programaSeGuardaUnaVezYLosModulosSeRelacionanPorSuCodigo() throws Exception {
    try (Connection c = db.getConnection()) {
      assertEquals(Long.valueOf(5), uno(c, r -> r.getLong(1), "SELECT count(*) FROM programas"));
      assertEquals(Long.valueOf(25), uno(c, r -> r.getLong(1), "SELECT count(*) FROM catalogo_cache"));
      assertEquals(Long.valueOf(0), uno(c, r -> r.getLong(1), "SELECT count(*) FROM information_schema.columns"
          + " WHERE table_schema=current_schema() AND table_name='catalogo_cache' AND column_name='programa_nombre'"));
      assertEquals(Long.valueOf(2), uno(c, r -> r.getLong(1), "SELECT count(*) FROM pg_constraint"
          + " WHERE confrelid='programas'::regclass AND contype='f'"));
    }
  }

  @Test
  void noSeAdmiteUnModuloQueApunteAUnProgramaInexistente() throws Exception {
    try (Connection c = db.getConnection()) {
      c.setAutoCommit(false);
      try {
        SQLException ex = assertThrows(SQLException.class, () -> actualizar(c,
            "UPDATE catalogo_cache SET programa_codigo=? WHERE codigo=?",
            "programa-que-no-existe", "desarrollo-web-m1"));
        assertEquals("23503", ex.getSQLState());
      } finally { c.rollback(); }
    }
  }

  @Test
  void renombrarProgramaActualizaLaOfertaPeroConservaElNombreDeUnaOperacionAnterior() throws Exception {
    try (Connection c = db.getConnection()) {
      c.setAutoCommit(false);
      try {
        Oferta antes = uno(c, Oferta::de, Oferta.SQL + " WHERE o.codigo=?", "desarrollo-web-m1");
        String documento = "norm-" + UUID.randomUUID();
        actualizar(c, "INSERT INTO prospectos(documento,nombre,apellido,telefono,correo,origen,created_at,updated_at)"
            + " VALUES(?,?,?,'70000000',?,'Correo',now(),now())", documento, "Prueba", "Normalización", documento + "@example.test");
        Long tarjeta = uno(c, r -> r.getLong(1),
            "INSERT INTO intereses(documento,modulo_codigo,programa_nombre,modulo_nombre,precio,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,now(),now()) RETURNING nro_tarjeta",
            documento, antes.codigo(), antes.programa(), antes.nombre(), antes.precio());
        String nombreNuevo = "Programa renombrado en QA";
        actualizar(c, "UPDATE programas SET nombre=? WHERE codigo=?", nombreNuevo, antes.programaCodigo());
        Oferta despues = uno(c, Oferta::de, Oferta.SQL + " WHERE o.codigo=?", antes.codigo());
        assertEquals(nombreNuevo, despues.programa());
        assertEquals(antes.codigo(), despues.codigo());
        assertEquals(antes.precio(), despues.precio());
        assertEquals(antes.programa(), uno(c, r -> r.getString(1),
            "SELECT programa_nombre FROM intereses WHERE nro_tarjeta=?", tarjeta));
      } finally { c.rollback(); }
    }
  }
}
