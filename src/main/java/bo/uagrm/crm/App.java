package bo.uagrm.crm;

import bo.uagrm.crm.aplicacion.Comando;
import bo.uagrm.crm.aplicacion.CommandProcessor;
import bo.uagrm.crm.aplicacion.ResponseFormatter;
import bo.uagrm.crm.aplicacion.Respuesta;
import bo.uagrm.crm.aplicacion.Respuesta.Aviso;
import bo.uagrm.crm.aplicacion.RolCommandProcessor;
import bo.uagrm.crm.dominio.seguimiento.Recordatorio;
import bo.uagrm.crm.dominio.seguimiento.SeguimientoService;
import bo.uagrm.crm.persistencia.DatabaseService;
import bo.uagrm.crm.presentacion.Correo;
import bo.uagrm.crm.presentacion.Pop3Client;
import bo.uagrm.crm.presentacion.SmtpSender;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * CRM vía e-mail (Proyecto 1, INF513 Grupo 05 SA). Cada intervalo: lee el buzón grupo05sa@tecnoweb.org.bo por POP3,
 * procesa el comando del asunto, responde al remitente por SMTP y avisa los recordatorios vencidos.
 *
 * Uso: java -jar crm-mail.jar crm-mail.properties
 *      java -jar crm-mail.jar --probar crm-mail.properties remitente@correo "COMANDO[...]" [archivo.csv]
 *      java -jar crm-mail.jar --anexo
 */
public final class App {

    private static final int MAX_POR_CICLO = 20;
    private static final long MAX_BYTES = 5L * 1024 * 1024;   // correos más grandes se rechazan sin descargarlos
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Config cfg;
    private final CommandProcessor procesador;
    private final SeguimientoService seguimiento;
    private final SmtpSender smtp;
    private final ResponseFormatter fmt;

    App(Config cfg) {
        this.cfg = cfg;
        DatabaseService db = new DatabaseService(cfg.dbUrl(), cfg.dbUsuario(), cfg.dbPassword());
        this.procesador = new CommandProcessor(db);
        this.seguimiento = new SeguimientoService(db);
        this.fmt = procesador.formatter();
        this.smtp = new SmtpSender(cfg.smtpHost(), cfg.smtpPuerto(), cfg.smtpUsuario(), cfg.smtpPassword(), cfg.buzonSistema());
    }

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("America/La_Paz"));
        if (args.length == 1 && args[0].equals("--anexo")) {
            anexo();
            return;
        }
        if (args.length >= 4 && args[0].equals("--probar")) {
            App app = new App(Config.cargar(Path.of(args[1])));
            String csv = args.length > 4 ? Files.readString(Path.of(args[4]), StandardCharsets.UTF_8) : null;
            Respuesta r = app.procesador.procesarEmail(args[2].toLowerCase(), args[3], csv);
            System.out.println(r == null ? "(ignorado: no se responde)" : app.fmt.texto(r, args[3]));
            if (r != null) Files.writeString(Path.of("respuesta.html"), app.fmt.html(r, args[3]), StandardCharsets.UTF_8);
            return;
        }
        Config cfg = Config.cargar(Path.of(args.length > 0 ? args[0] : "crm-mail.properties"));
        log("CRM vía e-mail iniciado. " + cfg);
        new App(cfg).ejecutar();
    }

    private void ejecutar() throws InterruptedException {
        while (true) {
            try {
                ciclo();
            } catch (Exception e) {
                log("Ciclo fallido: " + e.getMessage());
            }
            Thread.sleep(cfg.intervaloSegundos() * 1000L);
        }
    }

    /** Un mensaje bajado del buzón; si superó el límite solo trae sus cabeceras. */
    private record Recibido(byte[] contenido, long bytes, boolean excedido) {
    }

    private void ciclo() throws Exception {
        List<Recibido> mensajes = new ArrayList<>();
        try (Pop3Client pop = new Pop3Client(cfg.pop3Host(), cfg.pop3Puerto(), cfg.pop3Usuario(), cfg.pop3Password())) {
            int n = Math.min(pop.stat(), MAX_POR_CICLO);
            for (int i = 1; i <= n; i++) {
                long bytes = pop.tamano(i);
                boolean excedido = bytes > MAX_BYTES;
                mensajes.add(new Recibido(excedido ? pop.top(i, 0) : pop.retr(i), bytes, excedido));
                pop.dele(i);
            }
            pop.quit();   // confirma los DELE: recién aquí los mensajes salen del buzón
        }
        for (Recibido m : mensajes) atender(m);
        avisarRecordatorios();
    }

    /** Atiende un correo. Nada de lo que traiga detiene el servicio: todo termina en respuesta, bitácora o ambos. */
    private void atender(Recibido m) {
        Correo c = Correo.leer(m.contenido());
        try {
            if (c.remitente().isEmpty()) {
                procesador.bitacora(null, null, c.asunto(), "Error", "Correo ilegible: sin una dirección de remitente válida");
                log("Ilegible: sin remitente · " + c.asunto());
                return;
            }
            if (c.automatico() || c.remitente().equalsIgnoreCase(smtp.getRemitente())) {
                procesador.bitacora(c.remitente(), null, c.asunto(), "Ignorado", "Respuesta automática o correo del propio sistema");
                log("Ignorado (automático o propio): " + c.remitente() + " · " + c.asunto());
                return;
            }
            Respuesta r;
            if (m.excedido()) {
                r = fmt.error("Correo", "El correo pesa " + (m.bytes() / 1024) + " KB y el límite es " + (MAX_BYTES / 1024)
                        + " KB. Vuelva a enviar el comando con un adjunto más liviano.");
                procesador.bitacora(c.remitente(), null, c.asunto(), "Error", r.mensaje());
            } else {
                r = procesador.procesarEmail(c.remitente(), c.asunto(), c.adjuntoCsv());
                if (r == null) {
                    log("Ignorado (remitente desconocido, asunto sin comando): " + c.remitente() + " · " + c.asunto());
                    return;
                }
            }
            enviar(c.remitente(), r, c.asunto());
            log(r.resultado() + " · " + c.remitente() + " · " + c.asunto());
            for (Aviso a : r.avisos()) enviar(a.para(), a.contenido(), null);
        } catch (Exception e) {
            procesador.bitacora(c.remitente(), null, c.asunto(), "Error", "Fallo al atender el correo: " + e.getMessage());
            log("No se pudo atender un correo de " + c.remitente() + ": " + e);
        }
    }

    /** Un envío fallido (destinatario inexistente, Gmail caído) se registra y no corta los siguientes. */
    private void enviar(String para, Respuesta r, String asuntoRecibido) {
        try {
            smtp.enviar(para, r.asunto(), fmt.html(r, asuntoRecibido), fmt.texto(r, asuntoRecibido));
        } catch (Exception e) {
            procesador.bitacora(para, null, asuntoRecibido == null ? r.asunto() : asuntoRecibido, "Error",
                    "No se pudo enviar el correo de respuesta: " + e.getMessage());
            log("Envío fallido a " + para + ": " + e.getMessage());
        }
    }

    /** RF4.3: cuando vence un recordatorio, su dueño recibe un correo. */
    private void avisarRecordatorios() throws Exception {
        for (Recordatorio r : seguimiento.vencidos()) {
            try {
                Respuesta aviso = fmt.aviso("Recordatorio: " + r.descripcion(), "Hola, " + r.usuario() + ". Tiene pendiente: " + r.descripcion()
                        + ". Prospecto " + r.prospecto() + (r.telefono() == null ? "" : ", teléfono " + r.telefono())
                        + " (tarjeta #" + r.tarjetaId() + "). Al terminar envíe COMPLETARRECORDATORIO[\"" + r.id() + "\"].");
                smtp.enviar(r.correoUsuario(), aviso.asunto(), fmt.html(aviso, null), fmt.texto(aviso, null));
                seguimiento.marcarNotificado(r.id());
                log("Recordatorio " + r.id() + " enviado a " + r.correoUsuario());
            } catch (Exception e) {
                log("Recordatorio " + r.id() + " no enviado (se reintenta en el próximo ciclo): " + e.getMessage());
            }
        }
    }

    /** Matriz de acceso (Anexo: comandos por rol), generada del mismo código que la aplica. */
    private static void anexo() {
        CommandProcessor p = new CommandProcessor(new DatabaseService("", "", ""));
        String[] roles = {"administrador", "coordinador", "vendedor", "público"};
        Map<String, RolCommandProcessor> m = p.procesadores();
        System.out.println("CU\tComando\tAdministrador\tCoordinador\tVendedor\tPúblico\tEjemplo");
        for (Comando k : Comando.values()) {
            StringBuilder sb = new StringBuilder(k.cu).append('\t').append(k.firma());
            for (String r : roles) sb.append('\t').append(m.get(r).comandos().contains(k) ? "Sí" : "—");
            System.out.println(sb.append('\t').append(k.ejemplo));
        }
    }

    private static void log(String s) {
        System.out.println(LocalDateTime.now().format(HORA) + " " + s);
    }
}
