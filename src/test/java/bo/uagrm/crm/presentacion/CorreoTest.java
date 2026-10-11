package bo.uagrm.crm.presentacion;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Un correo mal formado nunca lanza excepción: se lee lo que se pueda. */
class CorreoTest {

    private static Correo leer(String crudo) {
        return Correo.leer(crudo.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void asuntoCodificadoYAdjuntoCsv() {
        Correo c = leer("""
                From: "Ana" <Ana@Example.com>\r
                Subject: =?UTF-8?Q?REGISTRARINTERACCION["7","Nota","Se_envi=C3=B3_el_PDF"]?=\r
                MIME-Version: 1.0\r
                Content-Type: multipart/mixed; boundary="b"\r
                \r
                --b\r
                Content-Type: text/plain\r
                \r
                hola\r
                --b\r
                Content-Type: application/octet-stream; name="p.csv"\r
                Content-Disposition: attachment; filename="p.csv"\r
                \r
                ci,nombre\r
                --b--\r
                """);
        assertEquals("ana@example.com", c.remitente());
        assertEquals("REGISTRARINTERACCION[\"7\",\"Nota\",\"Se envió el PDF\"]", c.asunto());
        assertTrue(c.adjuntoCsv().startsWith("ci,nombre"));
    }

    @Test
    void cabecerasRotasNoLanzanExcepcion() {
        Correo c = leer("From: <<<sin direccion\r\nSubject: VERTABLERO[]\r\nContent-Type: multipart/mixed; boundary=\"x\"\r\n\r\nbasura sin fin");
        assertEquals("", c.remitente());
        assertNull(c.adjuntoCsv());
        assertEquals("", leer("").remitente());
        assertEquals("juan@example.com", leer("From: Juan juan@example.com (texto suelto\r\n\r\n").remitente());
    }

    @Test
    void rebotesSonAutomaticos() {
        assertTrue(leer("From: MAILER-DAEMON@mail.tecnoweb.org.bo\r\nSubject: Undelivered\r\n\r\n").automatico());
        assertTrue(leer("From: a@example.com\r\nAuto-Submitted: auto-replied\r\nSubject: x\r\n\r\n").automatico());
    }
}
