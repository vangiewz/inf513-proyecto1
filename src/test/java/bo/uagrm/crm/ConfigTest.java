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
    for (String k : java.util.List.of("pop3.puerto", "smtp.puerto", "intervalo.segundos", "mail.poll.interval.ms"))
      props.remove(k);
    Path f = temp.resolve("ficticio.properties");
    try (var out = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
      props.store(out, "Solo placeholders");
    }
    Config c = Config.cargar(f);
    assertEquals(110, c.pop3Puerto());
    assertEquals(587, c.smtpPuerto());
    assertEquals(3, c.intervaloSegundos());
    assertEquals(3000, c.pollIntervalMs());
  }

  private Config conPropiedades(String clave, String valor, boolean retirarNueva) throws Exception {
    Properties props = new Properties();
    try (var in = Files.newBufferedReader(ejemplo(), StandardCharsets.UTF_8)) { props.load(in); }
    if (retirarNueva) props.remove("mail.poll.interval.ms");
    props.setProperty(clave, valor);
    Path f = temp.resolve("intervalo.properties");
    try (var out = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) { props.store(out,"QA ficticia"); }
    return Config.cargar(f);
  }

  @ParameterizedTest
  @ValueSource(strings={"0","-1","1","2999"})
  void intervaloInstitucionalInferiorATresSegundosSeRechaza(String valor) {
    assertThrows(IllegalArgumentException.class, () -> conPropiedades("mail.poll.interval.ms",valor,false));
  }

  @Test void propiedadNuevaTienePrecedenciaYConservaMilisegundos() throws Exception {
    Config c = conPropiedades("mail.poll.interval.ms","3500",false);
    assertEquals(3500,c.pollIntervalMs());
    assertEquals(4,c.intervaloSegundos());
  }

  @Test void propiedadHistoricaSigueSiendoCompatible() throws Exception {
    Config c = conPropiedades("intervalo.segundos","10",true);
    assertEquals(10000,c.pollIntervalMs());
    assertThrows(IllegalArgumentException.class, () -> conPropiedades("intervalo.segundos","2",true));
  }

  @Test void urlJdbcConSecretosNoSeImprime() throws Exception {
    Config c = conPropiedades("db.url","jdbc:postgresql://db.tecnoweb.org.bo:5432/db_grupo05sa?password=NO_IMPRIMIR",false);
    assertFalse(c.toString().contains("NO_IMPRIMIR"));
    assertFalse(c.toString().contains("password="));
  }
}
