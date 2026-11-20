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
public final class SmtpSender implements AutoCloseable {

  private final Session session;
  private final String remitente;
  private final String buzonSistema;
  private Transport conexion;
  private long ultimoUso;
  /** Falló antes de llamar sendMessage: se sabe que DATA no se intentó. */
  public static final class NoEnviado extends MessagingException {
    public NoEnviado(MessagingException causa) { super("SMTP no conectado; DATA no iniciado", causa); }
  }
  @FunctionalInterface interface Fabrica { Transport crear() throws MessagingException; }
  private Fabrica fabrica;

  SmtpSender(Fabrica fabrica) {
    this("127.0.0.1", 1, "prueba@example.test", "ficticia", "buzon@example.test");
    this.fabrica = fabrica;
  }

  private void cerrarConexion() {
    if (conexion != null) try { conexion.close(); } catch (MessagingException ignored) { }
    conexion = null;
  }

  private Transport conexion() throws MessagingException {
    if (conexion != null && System.nanoTime() - ultimoUso > 60_000_000_000L) cerrarConexion();
    if (conexion == null || !conexion.isConnected()) {
      cerrarConexion();
      conexion = fabrica.crear();
      conexion.connect();
    }
    ultimoUso = System.nanoTime();
    return conexion;
  }

  public SmtpSender(String host, int puerto, String usuario, String password, String buzonSistema) {
    Properties p = new Properties();
    p.put("mail.smtp.host", host);
    p.put("mail.smtp.port", String.valueOf(puerto));
    p.put("mail.smtp.auth", "true");
    p.put("mail.smtp.starttls.enable", "true");
    p.put("mail.smtp.starttls.required", "true");
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
    this.fabrica = () -> session.getTransport("smtp");
  }

  /** Envía un correo multipart/alternative: texto plano + HTML. */
  public void enviar(String para, String asunto, String html, String texto)
      throws MessagingException {
    enviar(para, asunto, html, texto, java.util.List.of());
  }

  public synchronized void enviar(
      String para, String asunto, String html, String texto, java.util.List<Adjunto> archivos)
      throws MessagingException {
    long inicio = System.nanoTime();
    try {
      enviarPreparado(preparar(para, asunto, html, texto, archivos));
    } finally { bo.uagrm.crm.Rendimiento.sumar("smtpPreparar", inicio); }
  }

  /** MIME durable, incluido Message-ID: no se reconstruye al recuperar una respuesta. */
  public byte[] preparar(String para, String asunto, String html, String texto,
      java.util.List<Adjunto> archivos) throws MessagingException {
    MimeMessage m = crearMensaje(para, asunto, html, texto, archivos);
    m.saveChanges();
    var bytes = new java.io.ByteArrayOutputStream();
    try { m.writeTo(bytes); }
    catch (java.io.IOException e) { throw new MessagingException("Preparando MIME", e); }
    return bytes.toByteArray();
  }

  public synchronized void enviarPreparado(byte[] contenido) throws MessagingException {
    long inicio = System.nanoTime();
    try {
      MimeMessage m = new MimeMessage(session, new java.io.ByteArrayInputStream(contenido));
      var log = java.util.logging.Logger.getLogger("crm");
      String referencia = " id=" + bo.uagrm.crm.Rendimiento.id() + " message_id="
          + String.valueOf(m.getMessageID()).replaceAll("[\\r\\n]", " ");
      log.info("SMTP inicio" + referencia + " utc=" + java.time.Instant.now());
      if (Boolean.getBoolean("crm.smtp.sinReuso")) {
        try (Transport t = session.getTransport("smtp")) {
          try { t.connect(); } catch (MessagingException e) { throw new NoEnviado(e); }
          t.sendMessage(m, m.getAllRecipients());
        }
      }
      else {
        long conexionInicio = System.nanoTime();
        Transport t;
        try { t = conexion(); }
        catch (MessagingException e) { throw new NoEnviado(e); }
        bo.uagrm.crm.Rendimiento.sumar("smtpConexion", conexionInicio);
        t.sendMessage(m, m.getAllRecipients());
      }
      log.info("SMTP aceptado" + referencia + " utc=" + java.time.Instant.now()
          + " duracion_ms=" + (System.nanoTime() - inicio) / 1_000_000);
    } catch (MessagingException e) {
      cerrarConexion();
      throw e; // no repetir DATA automáticamente: el servidor pudo aceptar el mensaje
    }
    finally { bo.uagrm.crm.Rendimiento.sumar("smtp", inicio); }
  }

  MimeMessage crearMensaje(String para, String asunto, String html, String texto)
      throws MessagingException {
    return crearMensaje(para, asunto, html, texto, java.util.List.of());
  }

  MimeMessage crearMensaje(
      String para, String asunto, String html, String texto, java.util.List<Adjunto> archivos)
      throws MessagingException {
    MimeMessage m = new MimeMessage(session);
    String correlacion = bo.uagrm.crm.Rendimiento.id();
    if (correlacion != null) m.setHeader("X-CRM-Correlation-ID", correlacion);
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
    parteHtml.setContent(AyudaHtml.ajustar(asunto, html), "text/html; charset=UTF-8");
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

  /** Abre la conexión con STARTTLS y se autentica, sin enviar nada (para el diagnóstico). */
  public synchronized void verificar() throws MessagingException {
    if (Boolean.getBoolean("crm.smtp.sinReuso")) {
      try (Transport t = session.getTransport("smtp")) { t.connect(); }
    } else conexion();
  }

  public String getRemitente() {
    return remitente;
  }
  @Override public synchronized void close() { cerrarConexion(); }
}
