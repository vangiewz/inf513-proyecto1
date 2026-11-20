package bo.uagrm.crm.aplicacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * La matriz de acceso se aplica antes de llegar al dominio: un comando denegado nunca abre una
 * conexión a la BD.
 */
class MatrizAccesoTest {

  // URL inválida: cualquier intento de usar la BD fallaría con "Error", no con "Denegado".
  private final DatabaseService sinBd =
      new DatabaseService("jdbc:postgresql://0.0.0.0:1/ninguna", "x", "x");
  private final ResponseFormatter fmt = new ResponseFormatter();
  private final CommandParser parser = new CommandParser();
  private static final java.time.LocalDate HOY = java.time.LocalDate.now();
  private final Usuario vendedor =
      new Usuario(
          "v@example.com", "Vendedor", "Uno", "70000003", Rol.VENDEDOR, "activo", HOY, HOY.plusYears(1));
  private final Usuario coordinador =
      new Usuario(
          "c@example.com", "Coord", "Uno", "70000002", Rol.COORDINADOR, "activo", HOY, HOY.plusYears(1));

  private String resultado(RolCommandProcessor p, String asunto, Usuario u) {
    return p.procesar(parser.parse(asunto, u == null ? "x@example.com" : u.correo(), null), u)
        .resultado();
  }

  @Test
  void comandoDenegadoNoTocaLaBaseDeDatos() {
    VendedorCommandProcessor v = new VendedorCommandProcessor(sinBd, fmt);
    assertEquals(
        "Denegado",
        resultado(
            v, "INSERTARUSUARIO[\"A\",\"B\",\"a@b.com\",\"70000000\",\"vendedor\"]", vendedor));
    assertEquals("Denegado", resultado(v, "VERBITACORA[]", vendedor));
    assertEquals("Denegado", resultado(v, "LISPER[\"*\"]", vendedor));
    assertEquals("Denegado", resultado(new PublicoCommandProcessor(sinBd, fmt), "LISPER[\"*\"]", null));
    assertEquals("Denegado", resultado(new CoordinadorCommandProcessor(sinBd, fmt),
        "INSPER[\"4715292\",\"A\",\"B\",\"Estudiante\",\"33554433\",\"71055123\",\"a@b.com\"]", coordinador));
    assertEquals(
        "Denegado",
        resultado(
            new CoordinadorCommandProcessor(sinBd, fmt), "ELIMINARUSUARIO[\"1\"]", coordinador));
    assertEquals(
        "Denegado", resultado(new PublicoCommandProcessor(sinBd, fmt), "VERTABLERO[]", null));
    // Un comando permitido sí llega al dominio (y aquí falla porque no hay BD): prueba que el
    // filtro es el rol.
    assertEquals("Error", resultado(v, "VERTABLERO[]", vendedor));
  }

  @Test
  void filasDeLaMatriz() {
    assertEquals(
        EnumSet.of(Comando.HELP, Comando.LISTAROFERTA, Comando.REGISTRARINTERES),
        new PublicoCommandProcessor(sinBd, fmt).comandos());
    var admin = new AdministradorCommandProcessor(sinBd, fmt).comandos();
    var vend = new VendedorCommandProcessor(sinBd, fmt).comandos();
    assertTrue(admin.contains(Comando.LISPER));
    assertTrue(admin.contains(Comando.INSPER));
    assertFalse(admin.contains(Comando.REGISTRARINTERES));
    assertFalse(vend.contains(Comando.LISTARBOLSA));
    for (Comando k :
        EnumSet.of(
            Comando.INSERTARUSUARIO,
            Comando.MODIFICARUSUARIO,
            Comando.ELIMINARUSUARIO,
            Comando.ASIGNARPROSPECTO,
            Comando.ASIGNARAUTOMATICO,
            Comando.VERBITACORA,
            Comando.REPORTEVENDEDORES))
      assertFalse(vend.contains(k), k + " no debe estar al alcance del vendedor");
    // Todo comando del catálogo pertenece al menos a un rol.
    for (Comando k : Comando.values()) {
      assertTrue(admin.contains(k) || vend.contains(k) || k == Comando.REGISTRARINTERES, k.name());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(Comando.class)
  void cadaPermisoDenegadoSeRechazaSinConectar(Comando comando) {
    var procesadores =
        java.util.List.of(
            new AdministradorCommandProcessor(sinBd, fmt),
            new CoordinadorCommandProcessor(sinBd, fmt),
            new VendedorCommandProcessor(sinBd, fmt),
            new PublicoCommandProcessor(sinBd, fmt));
    String[] parametros = new String[comando.parametros.length];
    java.util.Arrays.fill(parametros, "1");
    String asunto =
        comando.name()
            + "["
            + java.util.Arrays.stream(parametros)
                .map(x -> "\"" + x + "\"")
                .collect(java.util.stream.Collectors.joining(","))
            + "]";
    for (var p : procesadores)
      if (!p.comandos().contains(comando))
        assertEquals(
            "Denegado", resultado(p, asunto, vendedor), p.rol() + " no debe ejecutar " + comando);
  }
}
