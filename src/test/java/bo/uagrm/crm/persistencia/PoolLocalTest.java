package bo.uagrm.crm.persistencia;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Solo SELECT y estado de sesión sobre PostgreSQL local; no modifica tablas. */
@EnabledIfEnvironmentVariable(named="P1_POOL_TEST_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:.*")
class PoolLocalTest {
  @Test void reutilizaConexionYConservaUtc() throws Exception {
    var db = nuevo();
    int pid;
    try(var c=db.getConnection()) {
      pid=DatabaseService.uno(c,r->r.getInt(1),"SELECT pg_backend_pid()");
      assertEquals("UTC", DatabaseService.uno(c,r->r.getString(1),"SHOW TIME ZONE"));
    }
    try(var c=db.getConnection()) {
      assertEquals(pid, (int)DatabaseService.uno(c,r->r.getInt(1),"SELECT pg_backend_pid()"));
      assertTrue(c.getAutoCommit());
    }
  }
  @Test void rollbackNoFiltraEstadoTransaccionalAlSiguientePrestamo() throws Exception {
    var db=nuevo();
    assertThrows(IllegalStateException.class, () -> db.enTransaccion(c -> {
      DatabaseService.uno(c,r->r.getString(1),"SELECT set_config('crm.prueba_pool','temporal',true)");
      throw new IllegalStateException("fallo simulado");
    }));
    try(var c=db.getConnection()) {
      assertTrue(c.getAutoCommit());
      String v=DatabaseService.uno(c,r->r.getString(1),"SELECT current_setting('crm.prueba_pool',true)");
      assertTrue(v==null || v.isEmpty());
      assertEquals(1,(int)DatabaseService.uno(c,r->r.getInt(1),"SELECT 1"));
    }
  }
  private DatabaseService nuevo() {
    return new DatabaseService(System.getenv("P1_POOL_TEST_URL"),
        System.getenv("P1_POOL_TEST_USER"),System.getenv("P1_POOL_TEST_PASSWORD"));
  }

  @Test void recuperaConexionCortadaSinRecrearElPool() throws Exception {
    var db = nuevo();
    try (var c = db.getConnection()) {
      int pid = DatabaseService.uno(c, r -> r.getInt(1), "SELECT pg_backend_pid()");
      // Sólo backend local de esta prueba, nunca una sesión institucional.
      try (var control = java.sql.DriverManager.getConnection(System.getenv("P1_POOL_TEST_URL"),
          System.getenv("P1_POOL_TEST_USER"), System.getenv("P1_POOL_TEST_PASSWORD"))) {
        assertTrue((boolean)DatabaseService.uno(control, r -> r.getBoolean(1), "SELECT pg_terminate_backend(?)", pid));
      }
      DatabaseService.comenzarCorreo();
      try {
        assertThrows(java.sql.SQLException.class, () -> DatabaseService.uno(c, r -> r.getInt(1), "SELECT 1"));
        assertNotNull(DatabaseService.falloCorreo());
        assertTrue(DatabaseService.sinEscrituras());
      } finally { DatabaseService.terminarCorreo(); }
    }
    try (var recuperada = db.getConnection()) {
      assertEquals(1, (int)DatabaseService.uno(recuperada, r -> r.getInt(1), "SELECT 1"));
    }
  }
}
