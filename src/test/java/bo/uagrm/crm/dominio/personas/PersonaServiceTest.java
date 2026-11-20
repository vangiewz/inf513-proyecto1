package bo.uagrm.crm.dominio.personas;

import static org.junit.jupiter.api.Assertions.*;

import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PersonaServiceTest {
  private final PersonaService personas = new PersonaService(
      new DatabaseService("jdbc:postgresql://127.0.0.1:1/no_conectar", "qa", "ficticia"));
  private final LocalDate hoy = LocalDate.now();
  private final Usuario admin = new Usuario("a@example.test", "Admin", "QA", "70000000",
      Rol.ADMINISTRADOR, "activo", hoy, hoy.plusYears(1));

  @Test
  void validaAntesDeAbrirConexion() {
    String[] validos = {"4715292", "José Carlos", "Pérez Seras", "Estudiante", "33554433", "71055123", "a@example.test"};
    String[][] invalidos = {
        {"0", "abc", "1234567890123456"}, {"", " ", "A".repeat(121), "A\nB"},
        {"", "A".repeat(121)}, {"", "A".repeat(81)}, {"123", "telefono", "1".repeat(16)},
        {"123", "abc", "-71055123", "71-055123", "710 55123"}, {"sin-arroba", "a@", "a".repeat(121)}
    };
    for (int i = 0; i < validos.length; i++) {
      for (String invalido : invalidos[i]) {
        String[] datos = validos.clone();
        datos[i] = invalido;
        assertThrows(CrmException.class, () -> insertar(admin, datos),
            "Campo " + i + " debe rechazar el valor inválido antes de abrir JDBC");
      }
    }
  }

  @Test
  void soloAdministradorVigentePuedeOperar() {
    Usuario vendedor = new Usuario("v@example.test", "Vendedor", "QA", "70000000", Rol.VENDEDOR,
        "activo", hoy, hoy.plusYears(1));
    Usuario vencido = new Usuario("a@example.test", "Admin", "QA", "70000000", Rol.ADMINISTRADOR,
        "activo", hoy.minusYears(1), hoy.minusDays(1));
    for (Usuario u : new Usuario[] {null, vendedor, vencido}) {
      assertThrows(CrmException.class, () -> personas.listar(u, "*"));
      assertThrows(CrmException.class, () -> insertar(u,
          new String[] {"4715292", "A", "B", "C", "33554433", "71055123", "a@example.test"}));
    }
    assertThrows(CrmException.class, () -> personas.listar(admin, "1"));
  }

  private void insertar(Usuario u, String[] datos) throws Exception {
    personas.insertar(u, datos[0], datos[1], datos[2], datos[3], datos[4], datos[5], datos[6]);
  }
}
