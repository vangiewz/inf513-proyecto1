package bo.uagrm.crm.presentacion;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import java.util.Properties;

/**
 * Emisor SMTP con Jakarta Mail por smtp.gmail.com:587 con STARTTLS y contraseña de aplicación.
 * mail.tecnoweb.org.bo no reenvía a dominios externos (550 Relaying denied), por eso la respuesta
 * sale por Gmail. Reply-To apunta al buzón del sistema para que las respuestas del usuario vuelvan
 * al CRM.
 */
public final class SmtpSender {

  private final Session session;
  private final String remitente;
  private final String buzonSistema;

  public SmtpSender(String host, int puerto, String usuario, String password, String buzonSistema) {
    Properties p = new Properties();
    p.put("mail.smtp.host", host);
    p.put("mail.smtp.port", String.valueOf(puerto));
    p.put("mail.smtp.auth", "true");
    // Solo las pruebas locales (servidor SMTP de prueba en 127.0.0.1) pueden omitir STARTTLS.
    boolean tls =
        !(host.equals("127.0.0.1") && "false".equals(System.getProperty("crm.smtp.starttls")));
    p.put("mail.smtp.starttls.enable", String.valueOf(tls));
    p.put("mail.smtp.starttls.required", String.valueOf(tls));
    p.put("mail.smtp.connectiontimeout", "15000");
    p.put("mail.smtp.timeout", "30000");
    p.put("mail.smtp.writetimeout", "30000");
    p.put("mail.smtp.ssl.checkserveridentity", "true");
    this.session =
        Session.getInstance(
            p,
            new Authenticator() {
              @Override
              protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(usuario, password);
              }
            });
    this.remitente = usuario;
    this.buzonSistema = buzonSistema;
  }

  /** Correo multipart/alternative (texto plano + HTML) y, si hay, los adjuntos. */
  MimeMessage crearMensaje(String para, String asunto, String html, String texto)
      throws MessagingException {
    return crearMensaje(para, asunto, html, texto, java.util.List.of());
  }

  MimeMessage crearMensaje(
      String para, String asunto, String html, String texto, java.util.List<Adjunto> archivos)
      throws MessagingException {
    MimeMessage m = new MimeMessage(session);
    try {
      m.setFrom(new InternetAddress(remitente, "CRM Grupo 05 SA", "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      throw new MessagingException("Codificación no soportada", e);
    }
    m.setReplyTo(new InternetAddress[] {new InternetAddress(buzonSistema)});
    m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(para));
    m.setSubject(
        asunto.replaceAll("[\\r\\n]+", " "),
        "UTF-8"); // sin saltos de línea: no se inyectan cabeceras
    m.setHeader("Auto-Submitted", "auto-replied"); // RFC 3834: evita bucles con otros contestadores
    MimeBodyPart parteTexto = new MimeBodyPart();
    parteTexto.setText(texto, "UTF-8");
    MimeBodyPart parteHtml = new MimeBodyPart();
    parteHtml.setContent(html, "text/html; charset=UTF-8");
    MimeMultipart alternativas = new MimeMultipart("alternative", parteTexto, parteHtml);
    if (archivos.isEmpty()) m.setContent(alternativas);
    else {
      MimeBodyPart cuerpo = new MimeBodyPart();
      cuerpo.setContent(alternativas);
      MimeMultipart mixto = new MimeMultipart("mixed", cuerpo);
      for (Adjunto a : archivos) {
        MimeBodyPart archivo = new MimeBodyPart();
        archivo.setDataHandler(
            new jakarta.activation.DataHandler(
                new jakarta.mail.util.ByteArrayDataSource(a.contenido(), a.mime())));
        archivo.setFileName(a.nombre());
        archivo.setDisposition(jakarta.mail.Part.ATTACHMENT);
        mixto.addBodyPart(archivo);
      }
      m.setContent(mixto);
    }
    return m;
  }

  /**
   * Arma el correo completo una sola vez, con un Message-ID propio que no cambia al reenviarlo, y
   * lo devuelve en bytes para guardarlo en la cola (correos_salientes). enRespuestaA es el
   * Message-ID del correo recibido (hilo de la conversación); puede ser null.
   */
  public byte[] preparar(
      String para,
      String asunto,
      String html,
      String texto,
      java.util.List<Adjunto> archivos,
      String enRespuestaA)
      throws MessagingException {
    MimeMessage m = crearMensaje(para, asunto, html, texto, archivos);
    if (enRespuestaA != null && enRespuestaA.startsWith("<")) {
      m.setHeader("In-Reply-To", enRespuestaA);
      m.setHeader("References", enRespuestaA);
    }
    m.saveChanges();
    m.setHeader(
        "Message-ID",
        "<crm." + java.util.UUID.randomUUID() + "@grupo05sa.tecnoweb.org.bo>");
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    try {
      fijo(m).writeTo(out);
    } catch (java.io.IOException e) {
      throw new MessagingException("No se pudo armar el correo", e);
    }
    return out.toByteArray();
  }

  /** Envía un correo armado con preparar(), sin cambiar su Message-ID. */
  public void enviarPreparado(byte[] crudo) throws MessagingException {
    Transport.send(fijo(new MimeMessage(session, new java.io.ByteArrayInputStream(crudo))));
  }

  /** Copia que conserva el Message-ID al guardarse o enviarse (saveChanges lo regeneraría). */
  static MimeMessage fijo(MimeMessage m) throws MessagingException {
    return new MimeMessage(m) {
      @Override
      protected void updateMessageID() {}
    };
  }

  /** Abre la conexión con STARTTLS y se autentica, sin enviar nada (para el diagnóstico). */
  public void verificar() throws MessagingException {
    try (jakarta.mail.Transport t = session.getTransport("smtp")) {
      t.connect();
    }
  }

  public String getRemitente() {
    return remitente;
  }
}
