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
 * mail.tecnoweb.org.bo no reenvía a dominios externos (550 Relaying denied), por eso la respuesta sale por Gmail.
 * Reply-To apunta al buzón del sistema para que las respuestas del usuario vuelvan al CRM.
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
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.starttls.required", "true");
        p.put("mail.smtp.connectiontimeout", "15000");
        p.put("mail.smtp.timeout", "30000");
        this.session = Session.getInstance(p, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(usuario, password);
            }
        });
        this.remitente = usuario;
        this.buzonSistema = buzonSistema;
    }

    /** Envía un correo multipart/alternative: texto plano + HTML. */
    public void enviar(String para, String asunto, String html, String texto) throws MessagingException {
        MimeMessage m = new MimeMessage(session);
        try {
            m.setFrom(new InternetAddress(remitente, "CRM Grupo 05 SA", "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new MessagingException("Codificación no soportada", e);
        }
        m.setReplyTo(new InternetAddress[]{new InternetAddress(buzonSistema)});
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(para));
        m.setSubject(asunto.replaceAll("[\\r\\n]+", " "), "UTF-8");   // sin saltos de línea: no se inyectan cabeceras
        m.setHeader("Auto-Submitted", "auto-replied");   // RFC 3834: evita bucles con otros contestadores
        MimeBodyPart parteTexto = new MimeBodyPart();
        parteTexto.setText(texto, "UTF-8");
        MimeBodyPart parteHtml = new MimeBodyPart();
        parteHtml.setContent(html, "text/html; charset=UTF-8");
        m.setContent(new MimeMultipart("alternative", parteTexto, parteHtml));
        Transport.send(m);
    }

    public String getRemitente() {
        return remitente;
    }
}
