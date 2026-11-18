package bo.uagrm.crm.presentacion;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebotes de las respuestas. Gmail acepta el envío y recién después el servidor del destinatario
 * puede rechazarlo; el aviso (DSN, RFC 3464) vuelve a la cuenta emisora, no al buzón del sistema.
 * Se leen por IMAP los avisos no leídos de mailer-daemon/postmaster y se marcan como leídos.
 */
public final class Rebotes {

  /** Destinatario que no recibió la respuesta y el motivo que dio su servidor. */
  public record Rebote(String destinatario, String motivo) {}

  private static final Pattern FINAL =
      Pattern.compile("(?im)^(?:Final|Original)-Recipient:\\s*rfc822;\\s*<?([^\\s>]+)");
  private static final Pattern FALLIDO = Pattern.compile("(?im)^X-Failed-Recipients:\\s*([^\\s,]+)");
  private static final Pattern DIAGNOSTICO =
      Pattern.compile("(?im)^Diagnostic-Code:\\s*(?:smtp;\\s*)?(.+(?:\\r?\\n[ \\t]+.+)*)");
  private static final Pattern ESTADO = Pattern.compile("(?im)^Status:\\s*(\\S+)");

  private final String host;
  private final String usuario;
  private final String password;

  /** El host IMAP sale del SMTP: smtp.gmail.com → imap.gmail.com. */
  public Rebotes(String smtpHost, String usuario, String password) {
    this.host = smtpHost.replaceFirst("^smtp\\.", "imap.");
    this.usuario = usuario;
    this.password = password;
  }

  public String host() {
    return host;
  }

  /** Rebotes nuevos desde la última revisión; los deja marcados como leídos. */
  public List<Rebote> nuevos() throws MessagingException {
    Properties p = new Properties();
    p.put("mail.imaps.connectiontimeout", "15000");
    p.put("mail.imaps.timeout", "30000");
    p.put("mail.imaps.ssl.checkserveridentity", "true");
    List<Rebote> out = new ArrayList<>();
    Store store = Session.getInstance(p).getStore("imaps");
    store.connect(host, 993, usuario, password);
    try {
      Folder inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_WRITE);
      try {
        Message[] avisos =
            inbox.search(
                new AndTerm(
                    new FlagTerm(new Flags(Flags.Flag.SEEN), false),
                    new OrTerm(new FromStringTerm("mailer-daemon"), new FromStringTerm("postmaster"))));
        for (Message m : avisos) {
          ByteArrayOutputStream crudo = new ByteArrayOutputStream();
          try {
            m.writeTo(crudo);
            out.add(leer(crudo.toByteArray()));
          } catch (Exception e) { // un aviso dañado no impide leer los demás
            out.add(new Rebote("(desconocido)", "aviso de rebote ilegible: " + e.getMessage()));
          }
          m.setFlag(Flags.Flag.SEEN, true);
        }
      } finally {
        inbox.close(false);
      }
    } finally {
      store.close();
    }
    return out;
  }

  /** Solo se autentica (para el diagnóstico). */
  public void verificar() throws MessagingException {
    Store store = Session.getInstance(new Properties()).getStore("imaps");
    store.connect(host, 993, usuario, password);
    store.close();
  }

  /** Destinatario y motivo de un aviso de rebote, leídos del texto crudo. */
  static Rebote leer(byte[] crudo) {
    String t = new String(crudo, StandardCharsets.UTF_8);
    String destino = primero(FALLIDO, t);
    if (destino == null) destino = primero(FINAL, t);
    String motivo = primero(DIAGNOSTICO, t);
    if (motivo == null) motivo = primero(ESTADO, t);
    return new Rebote(
        destino == null ? "(desconocido)" : destino.toLowerCase(java.util.Locale.ROOT),
        motivo == null ? "sin motivo en el aviso" : motivo.replaceAll("\\s+", " ").trim());
  }

  private static String primero(Pattern p, String t) {
    Matcher m = p.matcher(t);
    return m.find() ? m.group(1) : null;
  }
}
