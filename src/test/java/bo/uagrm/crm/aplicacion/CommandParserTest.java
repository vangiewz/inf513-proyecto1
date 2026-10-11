package bo.uagrm.crm.aplicacion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandParserTest {

    private final CommandParser parser = new CommandParser();

    private Command parse(String asunto) {
        return parser.parse(asunto, "a@b.com", null);
    }

    @Test
    void comandoConParametrosEntreComillas() {
        Command c = parse("insertarusuario[\"Ana\",\"Rojas, Vaca\",\"ana@example.com\",\"71234567\",\"vendedor\"]");
        assertTrue(c.isValido());
        assertEquals(Comando.INSERTARUSUARIO, c.getTipo());
        assertEquals("Rojas, Vaca", c.getParam(1));   // la coma dentro de comillas no separa
        assertEquals(5, c.cantidad());
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
    void opcionalAusenteEsNull() {
        Command c = parse("MARCARPERDIDO[\"7\",\"3\"]");
        assertTrue(c.isValido());
        assertNull(c.getParam(2));
    }

    @Test
    void errores() {
        assertFalse(parse("hola, quiero información").isValido());
        assertFalse(parse("NOEXISTE[]").isValido());
        assertFalse(parse("TOMARPROSPECTO[\"1\",\"2\"]").isValido());            // de más
        assertFalse(parse("INSERTARUSUARIO[\"Ana\"]").isValido());     // de menos
        assertFalse(parse("TOMARPROSPECTO[\"1]").isValido());                    // comilla sin cerrar
        assertFalse(CommandParser.pareceComando("Re: consulta"));
        Command cortado = parse("MOVERTARJETA[\"1");                                // sin ] final
        assertEquals("MOVERTARJETA", cortado.getNombre());
        assertTrue(cortado.getMensajeError().contains("MOVERTARJETA[\"id_tarjeta\",\"estado\"]"));
    }
}
