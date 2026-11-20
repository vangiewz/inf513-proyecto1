package bo.uagrm.crm.aplicacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommandParserTest {

  private final CommandParser parser = new CommandParser();

  private Command parse(String asunto) {
    return parser.parse(asunto, "a@b.com", null);
  }

  @Test
  void comandoConParametrosEntreComillas() {
    Command c =
        parse(
            "insertarusuario[\"Ana\",\"Rojas,"
                + " Vaca\",\"ana@example.com\",\"71234567\",\"vendedor\"]");
    assertTrue(c.isValido());
    assertEquals(Comando.INSERTARUSUARIO, c.getTipo());
    assertEquals("Rojas, Vaca", c.getParam(1)); // la coma dentro de comillas no separa
    assertEquals(5, c.cantidad());
  }

  @Test
  void espaciosUtf8YComillasEscapadasDentroDelParametro() {
    Command c = parse("  REGISTRARINTERACCION  [ \"7\" , \"Nota\" , \"Pidió el curso \"\"Web\"\"\" ]  ");
    assertTrue(c.isValido());
    assertEquals("Pidió el curso \"Web\"", c.getParam(2));
    assertEquals(3, c.cantidad());
  }

  @Test
  void sinParametrosYAsterisco() {
    assertEquals(0, parse("VERTABLERO[]").cantidad());
    assertEquals(0, parse("LISTAROFERTA[\"*\"]").cantidad());
  }

  @Test
  void comillasTipograficasYNumerosSinComillas() {
    Command c = parse("MOVERTARJETA[7, “En Contacto”]");
    assertTrue(c.isValido());
    assertEquals("7", c.getParam(0));
    assertEquals("En Contacto", c.getParam(1));
  }

  @Test
  void prefijosDeRespuestaDeCualquierCliente() {
    assertEquals(Comando.VERTABLERO, parse("Re: VERTABLERO[]").getTipo());
    assertEquals(Comando.VERTABLERO, parse("RV: RE: VERTABLERO[]").getTipo());
    assertEquals(Comando.VERTABLERO, parse("Fwd:VERTABLERO[]").getTipo());
    assertTrue(CommandParser.pareceComando("RE: HELP[]"));
    assertFalse(CommandParser.pareceComando("Re: hola"));
  }

  @Test
  void opcionalAusenteEsNull() {
    Command c = parse("MARCARPERDIDO[\"7\",\"3\"]");
    assertTrue(c.isValido());
    assertNull(c.getParam(2));
  }

  @Test
  void errores() {
    assertFalse(parse("hola, quiero información").isValido());
    assertFalse(parse("NOEXISTE[]").isValido());
    assertFalse(parse("VERORDEN[\"1\",\"2\"]").isValido()); // de más
    assertFalse(parse("INSERTARUSUARIO[\"Ana\"]").isValido()); // de menos
    assertFalse(parse("VERORDEN[\"1]").isValido()); // comilla sin cerrar
    assertFalse(CommandParser.pareceComando("Re: consulta"));
    Command cortado = parse("MOVERTARJETA[\"1"); // sin ] final
    assertEquals("MOVERTARJETA", cortado.getNombre());
    assertTrue(cortado.getMensajeError().contains("MOVERTARJETA[\"nro_tarjeta\",\"estado\"]"));
  }

  @Test
  void contratosDocentesExactosYUtf8() {
    Command lisper = parse("  Re: LISPER [ \"*\" ] ");
    assertTrue(lisper.isValido());
    assertEquals(1, lisper.cantidad());
    assertEquals("*", lisper.getParam(0));
    Command insper = parse("INSPER[\"4715292\",\"José Carlos\",\"Pérez Seras\",\"Estudiante\","
        + "\"33554433\",\"71055123\",\"juanperez@uagrm.edu.bo\"]");
    assertTrue(insper.isValido());
    assertEquals(7, insper.cantidad());
    assertEquals("José Carlos", insper.getParam(1));
    assertTrue(CommandParser.pareceComando("INSPER"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "LISPER[]", "LISPER[*]", "LISPER[“*”]", "LISPER[\"*\",]", "LISPER[\"*\",\"*\"]",
      "LISPER[\"*\"]]", "LISPER[[\"*\"]", "LISPER[\"*\" extra]", "LISPER[\"*]",
      "INSPER[4715292,\"A\",\"B\",\"C\",\"33554433\",\"71055123\",\"a@b.com\"]",
      "INSPER[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]",
      "INSPER[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\"]",
      "VERORDEN[\"1\",]", "GENERARORDEN[\"1\",,]", "HELP[[]]"
  })
  void rechazaGramaticasMalformadasSinExcepcion(String asunto) {
    assertFalse(parse(asunto).isValido(), asunto);
  }

  @Test
  void escapesDocumentadosYCompatibilidadCrm() {
    Command backslash = parse("INSPER[\"4715292\",\"José \\\"Pepe\\\"\",\"Pérez\",\"Estudiante\","
        + "\"33554433\",\"71055123\",\"a@b.com\"]");
    Command duplicada = parse("INSPER[\"4715292\",\"José \"\"Pepe\"\"\",\"Pérez\",\"Estudiante\","
        + "\"33554433\",\"71055123\",\"a@b.com\"]");
    assertTrue(backslash.isValido());
    assertTrue(duplicada.isValido());
    assertEquals("José \"Pepe\"", backslash.getParam(1));
    assertEquals(backslash.getParam(1), duplicada.getParam(1));
    assertTrue(parse("MOVERTARJETA[7, En Contacto]").isValido(), "Compatibilidad CRM explícita");
    assertTrue(parse("GENERARORDEN[\"7\",\"\"]").isValido());
  }
}
