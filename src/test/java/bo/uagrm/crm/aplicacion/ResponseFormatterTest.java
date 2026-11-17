package bo.uagrm.crm.aplicacion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ResponseFormatterTest {
  @Test
  void htmlEscapaDatosYAsuntoDelRemitente() {
    var f = new ResponseFormatter();
    String x = "<script>alert('x')</script>";
    var r = f.successTable(x, x, new String[] {x}, List.<String[]>of(new String[] {x}));
    String html = f.html(r, x);
    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("&lt;script&gt;"));
    assertTrue(f.texto(r, x).contains(x));
  }

  @Test
  void todosLosEstadosDelModeloWebTienenInsignia() {
    var f = new ResponseFormatter();
    for (String e : bo.uagrm.crm.dominio.embudo.EmbudoService.ESTADOS)
      assertTrue(f.estadoBadge(e).startsWith("<span"), e);
  }
}
