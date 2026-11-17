package bo.uagrm.crm.presentacion;

import static org.junit.jupiter.api.Assertions.*;

import bo.uagrm.crm.dominio.CrmException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdjuntoTest {
  @Test
  void pdfValidoYDataUrlSeRecuperanSinCambiarBytes() throws Exception {
    byte[] bytes =
        "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF"
            .replace("\\n", "\n")
            .getBytes(StandardCharsets.US_ASCII);
    var a = new Adjunto("C:\\fakepath\\recibo.pdf", "text/plain", bytes).comoComprobante();
    assertEquals("application/pdf", a.mime());
    assertEquals("recibo.pdf", a.nombre());
    assertArrayEquals(bytes, Adjunto.desdeDataUrl(a.nombre(), a.dataUrl()).contenido());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<svg><script>alert(1)</script></svg>",
        "MZ ejecutable",
        "%PDF-1.4 sin terminador",
        "<html>falso</html>",
        ""
      })
  void extensionPdfNoHaceValidoUnArchivo(String contenido) {
    assertThrows(
        CrmException.class,
        () ->
            new Adjunto(
                    "comprobante.pdf",
                    "application/pdf",
                    contenido.getBytes(StandardCharsets.UTF_8))
                .comoComprobante());
  }

  @Test
  void limiteDeCincoMegabytesSeAplicaAlContenidoDecodificado() {
    assertThrows(
        CrmException.class,
        () ->
            new Adjunto("grande.pdf", "application/pdf", new byte[Adjunto.MAX_BYTES + 1])
                .comoComprobante());
  }

  @Test
  void copiasDefensivasNoPermitenCambiarArchivoValidado() throws Exception {
    byte[] bytes = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    var a = new Adjunto("x.pdf", "application/pdf", bytes);
    bytes[0] = 0;
    assertEquals('%', a.contenido()[0]);
    byte[] copia = a.contenido();
    copia[0] = 0;
    assertEquals('%', a.contenido()[0]);
  }
}
