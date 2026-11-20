package bo.uagrm.crm.presentacion;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutenticidadCorreoTest {
  @TempDir Path temporal;
  private static final Instant AHORA = Instant.ofEpochSecond(1795104000L);
  private static final byte[] CLAVE = java.util.stream.IntStream.range(0,32)
      .collect(java.io.ByteArrayOutputStream::new, java.io.ByteArrayOutputStream::write,
          (a,b) -> a.writeBytes(b.toByteArray())).toByteArray();
  private static final String RAW = "From: ana@example.test\r\nTo: grupo05sa@tecnoweb.org.bo\r\n"
      + "Subject: LISPER[\"*\"]\r\nMessage-ID: <qa-1@example.test>\r\nMIME-Version: 1.0\r\n"
      + "Content-Type: text/plain; charset=UTF-8\r\n\r\nqa\r\n";

  private AutenticidadCorreo auth() throws Exception {
    Path p = temporal.resolve("keys.properties");
    Files.writeString(p, "ana@example.test=" + Base64.getEncoder().encodeToString(CLAVE));
    return AutenticidadCorreo.cargar(p);
  }

  private byte[] firmado() throws Exception {
    return AutenticidadCorreo.firmar(RAW.getBytes(StandardCharsets.UTF_8), CLAVE, AHORA);
  }

  @Test void firmaRealYCompatibilidadVectorPython() throws Exception {
    byte[] firmado = firmado();
    assertTrue(auth().verificar(firmado, AHORA).autentico());
    assertTrue(new String(firmado,StandardCharsets.UTF_8).contains(
        "X-CRM-Signature: 92a7bca16a4f6b87b67a0e2ad6b9e5596243fa799e3714be782c3638f62abe91"),
        "vector producido también por scripts/firmar_correo.py");
  }

  @Test void fromConocidoSinFirmaONoConfiguradoNoEsAutenticacion() throws Exception {
    assertFalse(auth().verificar(RAW.getBytes(StandardCharsets.UTF_8), AHORA).autentico());
    assertFalse(AutenticidadCorreo.cargar(null).verificar(firmado(), AHORA).autentico());
    assertNotEquals(Correo.idMensaje(RAW.getBytes(StandardCharsets.UTF_8)), Correo.idMensaje(firmado()),
        "una copia sin firma no puede suprimir la solicitud auténtica");
  }

  @Test void alteracionAsuntoRemitenteCuerpoOMimeInvalidaFirma() throws Exception {
    String f = new String(firmado(),StandardCharsets.UTF_8);
    for (String alterado : new String[] {f.replace("LISPER", "INSPER"),
        f.replace("ana@example.test", "admin@example.test"), f.replace("\r\nqa\r\n", "\r\nmalicioso\r\n"),
        f.replace("text/plain", "text/html")})
      assertFalse(auth().verificar(alterado.getBytes(StandardCharsets.UTF_8), AHORA).autentico());
  }

  @Test void firmaProtegeAdjuntosBinariosCodificados() throws Exception {
    String mime = RAW.substring(0,RAW.indexOf("Content-Type:"))
        + "Content-Type: multipart/mixed; boundary=qa\r\n\r\n--qa\r\nContent-Type: application/pdf\r\n"
        + "Content-Transfer-Encoding: base64\r\n\r\nJVBERi0x\r\n--qa--\r\n";
    byte[] firmado = AutenticidadCorreo.firmar(mime.getBytes(StandardCharsets.UTF_8),CLAVE,AHORA);
    assertTrue(auth().verificar(firmado, AHORA).autentico());
    assertFalse(auth().verificar(new String(firmado,StandardCharsets.UTF_8).replace("JVBERi0x","JVBERi0y")
        .getBytes(StandardCharsets.UTF_8), AHORA).autentico());
  }

  @Test void fechasFueraVentanaYHeadersDuplicadosSeRechazan() throws Exception {
    assertFalse(auth().verificar(firmado(),AHORA.plusSeconds(86401)).autentico());
    assertFalse(auth().verificar(firmado(),AHORA.minusSeconds(301)).autentico());
    byte[] repetido = ("From: otro@example.test\r\n" + new String(firmado(),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    assertFalse(auth().verificar(repetido,AHORA).autentico());
    assertFalse(auth().verificar(new byte[] {0,1,2},AHORA).autentico());
  }

  @Test void cabecerasTransporteNoRompenFirmaNiDeduplicacion() throws Exception {
    byte[] f = firmado();
    byte[] relay = ("Received: from mx.example.test by qa\r\nReturn-Path: <ana@example.test>\r\n"
        + new String(f,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    assertTrue(auth().verificar(relay,AHORA).autentico());
    assertEquals(Correo.idMensaje(f),Correo.idMensaje(relay));
    assertEquals("<qa-1@example.test>",Correo.messageIdOriginal(f));
  }

  @Test void caseHexadecimalDeFirmaNoPermiteRepetirSolicitud() throws Exception {
    byte[] original = firmado();
    String texto = new String(original, StandardCharsets.UTF_8);
    var m = java.util.regex.Pattern.compile("(?m)^(X-CRM-Signature: )([0-9a-f]+)").matcher(texto);
    assertTrue(m.find());
    byte[] mayusculas = (texto.substring(0,m.start(2)) + m.group(2).toUpperCase(java.util.Locale.ROOT)
        + texto.substring(m.end(2))).getBytes(StandardCharsets.UTF_8);
    assertTrue(auth().verificar(mayusculas,AHORA).autentico());
    assertEquals(Correo.idMensaje(original),Correo.idMensaje(mayusculas),
        "formas hexadecimales equivalentes conservan una misma identidad de solicitud");
  }

  @Test void claveDebilNoSeCargaNiSeFirma() throws Exception {
    Path p=temporal.resolve("debil.properties"); Files.writeString(p,"ana@example.test=YQ==");
    assertThrows(java.io.IOException.class,()->AutenticidadCorreo.cargar(p));
    assertThrows(IllegalArgumentException.class,()->AutenticidadCorreo.firmar(RAW.getBytes(),new byte[4],AHORA));
  }
}
