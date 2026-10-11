package bo.uagrm.crm.presentacion;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Correo leído del buzón: remitente, asunto ya decodificado (RFC 2047) y el .csv adjunto si lo hay.
 * leer() nunca lanza excepción: un correo mal formado devuelve remitente vacío o adjunto nulo, y el
 * servicio lo registra en la bitácora en lugar de caerse.
 */
public record Correo(String remitente, String asunto, String adjuntoCsv, boolean automatico) {

    private static final Session SESION = Session.getInstance(new Properties());
    private static final Pattern DIRECCION = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    public static Correo leer(byte[] crudo) {
        MimeMessage m;
        try {
            m = new MimeMessage(SESION, new ByteArrayInputStream(crudo));
        } catch (MessagingException e) {
            return new Correo("", null, null, false);
        }
        String remitente = remitente(m);
        String asunto;
        try {
            asunto = m.getSubject();
        } catch (MessagingException e) {
            asunto = cabecera(m, "Subject");
        }
        String auto = cabecera(m, "Auto-Submitted"), prec = cabecera(m, "Precedence");
        boolean automatico = (auto != null && !auto.trim().equalsIgnoreCase("no"))
                || (prec != null && prec.trim().toLowerCase(Locale.ROOT).matches("bulk|junk|list"))
                || remitente.matches("^(mailer-daemon|postmaster|no-?reply)@.*");
        String csv;
        try {
            csv = csv(m);
        } catch (Exception e) {   // adjunto dañado: se procesa el comando como si no lo trajera
            csv = null;
        }
        return new Correo(remitente, asunto, csv, automatico);
    }

    /** Dirección del From; si la cabecera está mal formada se intenta una lectura tolerante. Vacía si no hay dirección válida. */
    private static String remitente(MimeMessage m) {
        try {
            Address[] from = m.getFrom();
            if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia && !valida(ia.getAddress()).isEmpty())
                return valida(ia.getAddress());
        } catch (MessagingException ignored) {
            // se intenta abajo con la cabecera cruda
        }
        String h = cabecera(m, "From");
        if (h == null) return "";
        try {
            InternetAddress[] a = InternetAddress.parseHeader(h, false);
            if (a.length > 0 && !valida(a[0].getAddress()).isEmpty()) return valida(a[0].getAddress());
        } catch (AddressException ignored) {
            // última opción: buscar una dirección dentro del texto
        }
        Matcher x = DIRECCION.matcher(h);
        return x.find() ? valida(x.group()) : "";
    }

    private static String valida(String d) {
        return d != null && DIRECCION.matcher(d).matches() ? d.toLowerCase(Locale.ROOT) : "";
    }

    private static String cabecera(MimeMessage m, String nombre) {
        try {
            return m.getHeader(nombre, null);
        } catch (MessagingException e) {
            return null;
        }
    }

    private static String csv(Part p) throws Exception {
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String c = csv(mp.getBodyPart(i));
                if (c != null) return c;
            }
            return null;
        }
        String nombre = p.getFileName();
        if (!p.isMimeType("text/csv") && (nombre == null || !nombre.toLowerCase(Locale.ROOT).endsWith(".csv"))) return null;
        try (InputStream in = p.getInputStream()) {
            return texto(in.readAllBytes());
        }
    }

    /** UTF-8 (sin BOM); si no lo es, Windows-1252, que es como guarda Excel en español. */
    private static String texto(byte[] b) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }
}
