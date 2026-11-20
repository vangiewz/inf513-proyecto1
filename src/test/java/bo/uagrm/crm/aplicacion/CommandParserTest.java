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
  void sinParametrosYAsterisco() {
    assertEquals(0, parse("VERTABLERO[]").cantidad());
    assertEquals(0, parse("LISTAROFERTA[\"*\"]").cantidad());
  }

  @Test
  void comillasTipograficasYComillasObligatorias() {
    Command c = parse("MOVERTARJETA[“7”, “En Contacto”]");
    assertTrue(c.isValido());
    assertEquals("7", c.getParam(0));
    assertEquals("En Contacto", c.getParam(1));
    // Enunciado: si faltan comillas, Error, nombrando el parámetro.
    Command sin = parse("MOVERTARJETA[7,\"En Contacto\"]");
    assertFalse(sin.isValido());
    assertTrue(sin.getMensajeError().contains("parámetro 1"), sin.getMensajeError());
    assertFalse(parse("INSERTARUSUARIO[Ana,Rojas,ana@x.com,71234567,vendedor]").isValido());
  }

  @Test
  void comandoSinCorchetesYCorcheteDeMas() {
    Command sin = parse("listaroferta");
    assertFalse(sin.isValido());
    assertTrue(sin.getMensajeError().contains("Faltan los corchetes"), sin.getMensajeError());
    assertTrue(CommandParser.pareceComando("LISTAROFERTA"));
    assertFalse(CommandParser.pareceComando("hola, quiero información"));
    Command extra = parse("VERTABLERO[]]");
    assertFalse(extra.isValido());
    assertTrue(extra.getMensajeError().contains("corchete"), extra.getMensajeError());
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
  void espaciosUtf8YComillasEscapadasDentroDelParametro() {
    Command c =
        parse("  REGISTRARINTERACCION [ \"7\" ,  \"Llamada\" , \"Dijo \\\"sí\\\", llamar en ñ\\\\ \"\"mañana\"\"\" ]  ");
    assertTrue(c.isValido());
    assertEquals("7", c.getParam(0));
    assertEquals("Dijo \"sí\", llamar en ñ\\ \"mañana\"", c.getParam(2));
    assertFalse(parse("VERHISTORIAL[\"7\",]").isValido()); // coma sin parámetro
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
}
