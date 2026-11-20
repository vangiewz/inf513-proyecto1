package bo.uagrm.crm;

import static org.junit.jupiter.api.Assertions.*;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class LogSeguroTest {
  @Test void errorSmtpYCredencialesEnCausasNoSePublican() {
    LogRecord registro = new LogRecord(Level.SEVERE,
        "550 <persona@example.test> unknown; password=valorPrivado token=tokenPrivado"
            + " clave='dato privado con espacios' Authorization: Bearer secretoBearer");
    registro.setThrown(new IllegalStateException("causa smtp@example.test secret=otroPrivado"));
    String salida = App.formatoLogSeguro().format(registro);
    for (String privado : new String[] {"persona@example.test", "smtp@example.test", "valorPrivado", "tokenPrivado", "otroPrivado", "dato privado", "con espacios", "secretoBearer"})
      assertFalse(salida.contains(privado));
    assertTrue(salida.contains("550"));
    assertTrue(salida.contains("IllegalStateException"));
  }

  @Test void detalleSqlYUrlSeRedactanConservandoReferenciaYEstado() {
    LogRecord registro = new LogRecord(Level.WARNING,
        "registro #17 SQLSTATE=23505 Key (documento)=(4715292) https://cuenta:clavePrivada@ejemplo.test/ruta");
    String salida = App.formatoLogSeguro().format(registro);
    assertFalse(salida.contains("4715292"));
    assertFalse(salida.contains("clavePrivada"));
    assertTrue(salida.contains("registro #17 SQLSTATE=23505"));
    assertTrue(salida.contains("[dato]"));
  }
}
