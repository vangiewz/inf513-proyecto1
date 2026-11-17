package bo.uagrm.crm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTest {
  @TempDir Path temp;

  static Path ejemplo() {
    return Path.of("crm-mail.properties.example");
  }

  @Test
  void ejemploEntregadoUsaLasMismasClavesQueElServicio() throws Exception {
    Config c = Config.cargar(ejemplo());
    assertEquals("grupo05sa", c.pop3Usuario());
    assertEquals("grupo05sa", c.dbUsuario());
    assertEquals(110, c.pop3Puerto());
    assertEquals(587, c.smtpPuerto());
    assertTrue(c.dbUrl().contains("currentSchema=public&"));
  }

  @Test
  void registroNoExponePasswords() throws Exception {
    Config c = Config.cargar(ejemplo());
    String texto = c.toString();
    assertFalse(texto.contains(c.pop3Password()));
    assertFalse(texto.contains(c.smtpPassword()));
    assertFalse(texto.contains(c.dbPassword()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "pop3.host",
        "pop3.usuario",
        "pop3.password",
        "smtp.host",
        "smtp.usuario",
        "smtp.password",
        "buzon.sistema",
        "db.url",
        "db.usuario",
        "db.password"
      })
  void clavesFaltantesSeDetectanAntesDeIniciar(String key) throws Exception {
    var props = new Properties();
    try (var in = Files.newBufferedReader(ejemplo(), StandardCharsets.UTF_8)) {
      props.load(in);
    }
    props.remove(key);
    Path f = temp.resolve("ficticio.properties");
    try (var out = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
      props.store(out, "Solo placeholders de prueba");
    }
    var e = assertThrows(IllegalArgumentException.class, () -> Config.cargar(f));
    assertTrue(e.getMessage().contains(key));
  }

  @Test
  void valoresPredeterminadosSonCompatiblesConConfiguracionAnterior() throws Exception {
    var props = new Properties();
    try (var in = Files.newBufferedReader(ejemplo(), StandardCharsets.UTF_8)) {
      props.load(in);
    }
    for (String k : java.util.List.of("pop3.puerto", "smtp.puerto", "intervalo.segundos"))
      props.remove(k);
    Path f = temp.resolve("ficticio.properties");
    try (var out = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
      props.store(out, "Solo placeholders");
    }
    Config c = Config.cargar(f);
    assertEquals(110, c.pop3Puerto());
    assertEquals(587, c.smtpPuerto());
    assertEquals(10, c.intervaloSegundos());
  }
}
