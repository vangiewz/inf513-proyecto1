package bo.uagrm.crm.integracion;

import static org.junit.jupiter.api.Assertions.*;

import bo.uagrm.crm.aplicacion.*;
import bo.uagrm.crm.dominio.captacion.ProgramaService;
import bo.uagrm.crm.dominio.usuarios.*;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Cada comando, con cada rol y con entradas malas (vacías, espacios, letras donde va un número,
 * negativos, inyección SQL, textos enormes, emojis, de más o de menos parámetros, corchetes sin
 * cerrar) debe responder con un mensaje claro: nunca un error interno ni un error SQL sin traducir.
 * Corre contra un PostgreSQL desechable con sql/esquema.sql y sql/referencias.sql:
 * P1_ROBUSTEZ_DB_URL=jdbc:postgresql://127.0.0.1:55442/crm_qa_robustez (usuario crm_user).
 */
@EnabledIfEnvironmentVariable(named = "P1_ROBUSTEZ_DB_URL", matches = ".+")
@Timeout(900)
class RobustezCorreoTest {
  static final List<String> BASURA =
      List.of(
          "",
          "   ",
          "abc",
          "-1",
          "0",
          "99999999",
          "1.5",
          "99999999999999999999",
          "'; DROP TABLE usuarios; --",
          "x".repeat(5000),
          "😀🔥",
          "a\tb",
          "<script>alert(1)</script>",
          "2026-13-45 99:99",
          "%_\\");

  static DatabaseService db;
  static CommandProcessor cp;
  static Map<String, String> remitentes = new LinkedHashMap<>();

  @BeforeAll
  static void init() throws Exception {
    String url = System.getenv("P1_ROBUSTEZ_DB_URL");
    assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:"), "solo contra una base local desechable");
    db = new DatabaseService(url, "crm_user", "crm_password");
    db.verificarModelo();
    var us = new UsuarioService(db);
    var admin = us.insertar("Admin", "QA", "admin@qa.test", "70000001", "administrador");
    var coord = us.insertar("Coord", "QA", "coord@qa.test", "70000002", "coordinador");
    us.insertar("Vend", "QA", "vend@qa.test", "70000003", "vendedor");
    new ProgramaService(db).asignar(admin, "desarrollo-web", coord.id());
    remitentes.put("administrador", "admin@qa.test");
    remitentes.put("coordinador", "coord@qa.test");
    remitentes.put("vendedor", "vend@qa.test");
    remitentes.put("visitante", "visitante@outlook.com");
    cp = new CommandProcessor(db);
  }

  static String lista(String... p) {
    return Arrays.stream(p)
        .map(x -> "\"" + x.replace("\"", "\"\"") + "\"")
        .collect(Collectors.joining(","));
  }

  @Test
  void ningunComandoFallaPorDentroConEntradasMalas() throws Exception {
    List<String> fallas = new ArrayList<>();
    List<String> exitosConBasura = new ArrayList<>();
    int casos = 0;
    for (Comando k : Comando.values()) {
      int n = k.parametros.length;
      String nombre = k.name();
      Map<String, String> asuntos = new LinkedHashMap<>();
      asuntos.put("sin corchetes", nombre);
      asuntos.put("corchete abierto", nombre + "[");
      asuntos.put("comilla abierta", nombre + "[\"1");
      asuntos.put("minúsculas y espacios", "  " + nombre.toLowerCase() + "  [  ]  ");
      asuntos.put("asterisco", nombre + "[\"*\"]");
      asuntos.put("Re: y espacios", "RE:  " + nombre + " [ " + lista(rellenar(n, "99999999")) + " ]");
      asuntos.put("de más", nombre + "[" + lista(rellenar(n + 1, "99999999")) + "]");
      asuntos.put("comas sueltas", nombre + "[,,,]");
      for (String b : BASURA) asuntos.put("todos = «" + corto(b) + "»", nombre + "[" + lista(rellenar(n, b)) + "]");
      for (var rol : remitentes.entrySet()) {
        for (var a : asuntos.entrySet()) {
          casos++;
          String caso = rol.getKey() + " · " + nombre + " · " + a.getKey();
          Respuesta r;
          try {
            r = cp.procesarEmail(rol.getValue(), a.getValue(), null);
          } catch (Throwable t) {
            fallas.add(caso + " → EXCEPCIÓN " + t);
            continue;
          }
          if (r == null) {
            if (!rol.getKey().equals("visitante")) fallas.add(caso + " → sin respuesta");
            continue;
          }
          String msg = Objects.toString(r.mensaje(), "");
          if (msg.contains("error interno") || msg.contains("(código "))
            fallas.add(caso + " → " + r.resultado() + ": " + msg);
          boolean vacio = a.getKey().contains("«»") || a.getKey().contains("«   »");
          boolean malFormado =
              a.getKey().equals("de más") || a.getKey().startsWith("co") || a.getKey().startsWith("corchete");
          if (r.resultado().equals("Éxito")
              && ((vacio && k.minimos() > 0) || (malFormado && n > 0)))
            fallas.add(caso + " → aceptó una entrada inválida");
          else if (r.resultado().equals("Éxito") && n > 0 && a.getKey().startsWith("todos"))
            exitosConBasura.add(caso);
        }
      }
    }
    // CSV de IMPORTARPROSPECTOS dañados.
    for (String csv :
        List.of("", "\uFEFF", "ci;nombre\n", "a,b,c\n\"sin cerrar", ";;;;\n;;;;", "\0\1\2\3", "x".repeat(100000))) {
      casos++;
      try {
        var r = cp.procesarEmail("admin@qa.test", "IMPORTARPROSPECTOS[]", csv);
        String msg = Objects.toString(r.mensaje(), "");
        if (msg.contains("error interno") || msg.contains("(código "))
          fallas.add("CSV «" + corto(csv) + "» → " + msg);
      } catch (Throwable t) {
        fallas.add("CSV «" + corto(csv) + "» → EXCEPCIÓN " + t);
      }
    }
    db.verificarModelo(); // la inyección no borró nada
    System.out.println("Casos probados: " + casos);
    System.out.println("Éxito con valores raros (revisar que sea legítimo): " + exitosConBasura.size());
    exitosConBasura.forEach(x -> System.out.println("  " + x));
    assertTrue(fallas.isEmpty(), fallas.size() + " fallas:\n" + String.join("\n", fallas));
  }

  static String[] rellenar(int n, String v) {
    String[] a = new String[n];
    Arrays.fill(a, v);
    return a;
  }

  static String corto(String s) {
    String t = s.replace("\n", "\\n").replace("\t", "\\t");
    return t.length() > 12 ? t.substring(0, 12) + "…" : t;
  }
}
