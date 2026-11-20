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
  public record Rebote(String destinatario, String motivo, String originalMessageId) {
    public Rebote(String destinatario, String motivo) { this(destinatario, motivo, null); }
  }

  @FunctionalInterface
  public interface Confirmacion {
    /** Solo retorna cuando el aviso quedó registrado de forma recuperable. */
    void confirmar(Rebote rebote) throws Exception;
  }

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

  /** Lectura compatible sin confirmar: no marca avisos como leídos ni modifica la bandeja. */
  public List<Rebote> nuevos() throws MessagingException {
    List<Rebote> out = new ArrayList<>();
    recibir(out::add, false);
    return out;
  }

  /** Marca cada aviso leído únicamente después de que el consumidor confirmó su persistencia. */
  public int procesar(Confirmacion confirmacion) throws MessagingException {
    return recibir(confirmacion, true);
  }

  private int recibir(Confirmacion confirmacion, boolean confirmarLectura) throws MessagingException {
    Properties p = new Properties();
    p.put("mail.imaps.connectiontimeout", "15000");
    p.put("mail.imaps.timeout", "30000");
    p.put("mail.imaps.ssl.checkserveridentity", "true");
    p.put("mail.imaps.peek", "true"); // leer el cuerpo no debe adelantar el flag SEEN
    int procesados = 0;
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
          Rebote rebote;
          try {
            m.writeTo(crudo);
            rebote = leer(crudo.toByteArray());
          } catch (Exception e) { // un aviso dañado no impide leer los demás
            rebote = new Rebote("(desconocido)", "aviso de rebote ilegible");
          }
          confirmar(m, rebote, confirmacion, confirmarLectura);
          procesados++;
        }
      } finally {
        inbox.close(false);
      }
    } finally {
      store.close();
    }
    return procesados;
  }

  static void confirmar(Message mensaje, Rebote rebote, Confirmacion consumidor, boolean marcarLeido)
      throws MessagingException {
    try { consumidor.confirmar(rebote); }
    catch (Exception e) {
      throw new MessagingException("El rebote no se confirmó; permanece sin leer para recuperación", e);
    }
    if (marcarLeido) mensaje.setFlag(Flags.Flag.SEEN, true);
  }

  /** Solo se autentica (para el diagnóstico). */
  public void verificar() throws MessagingException {
    Properties p = new Properties();
    p.put("mail.imaps.connectiontimeout", "15000");
    p.put("mail.imaps.timeout", "30000");
    p.put("mail.imaps.ssl.checkserveridentity", "true");
    Store store = Session.getInstance(p).getStore("imaps");
    try { store.connect(host, 993, usuario, password); }
    finally { store.close(); }
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
        motivo == null ? "sin motivo en el aviso" : motivo.replaceAll("\\s+", " ").trim(),
        originalId(t));
  }

  private static String originalId(String t) {
    String id = primero(Pattern.compile("(?im)^Original-Message-ID:\\s*(<[^<>\\s]{3,250}>)"), t);
    if (id != null) return id;
    // Las respuestas propias tienen un dominio y prefijo estables; no usar el ID exterior del DSN.
    Matcher m = Pattern.compile("(?im)^Message-ID:\\s*(<crm\\.[^<>\\s]+@grupo05sa\\.tecnoweb\\.org\\.bo>)").matcher(t);
    return m.find() ? m.group(1) : null;
  }

  private static String primero(Pattern p, String t) {
    Matcher m = p.matcher(t);
    return m.find() ? m.group(1) : null;
  }
}
