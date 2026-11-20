package bo.uagrm.crm;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import bo.uagrm.crm.aplicacion.CommandProcessor;
import bo.uagrm.crm.persistencia.DatabaseService;

class DisponibilidadBDTest {
  @Test void consultaBloqueadaNoBloqueaAyudaPublica() throws Exception {
    CountDownLatch dentro = new CountDownLatch(1), salir = new CountDownLatch(1);
    try (var salud = new DisponibilidadBD(() -> { dentro.countDown(); salir.await(); })) {
      salud.iniciar(); assertTrue(dentro.await(2, TimeUnit.SECONDS));
      var cp = new CommandProcessor(new DatabaseService("jdbc:postgresql://127.0.0.1:1/no", "x", "x"));
      try {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
          assertFalse(salud.disponible());
          assertEquals("Éxito", cp.ayudaSinBD("HELP[]").resultado());
          assertThrows(IllegalArgumentException.class, () -> cp.ayudaSinBD("LISTARUSUARIOS[]"));
          assertThrows(IllegalArgumentException.class, () -> cp.ayudaSinBD("HELP[\"LISTARUSUARIOS\"]"));
        });
      } finally { salir.countDown(); }
    }
  }
}
