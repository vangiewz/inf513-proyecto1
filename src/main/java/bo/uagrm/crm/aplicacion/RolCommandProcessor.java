package bo.uagrm.crm.aplicacion;

import bo.uagrm.crm.aplicacion.Respuesta.Aviso;
import bo.uagrm.crm.aplicacion.Respuesta.Bloque;
import bo.uagrm.crm.aplicacion.Respuesta.Carril;
import bo.uagrm.crm.aplicacion.Respuesta.Tabla;
import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.dominio.asignacion.AsignacionService;
import bo.uagrm.crm.dominio.asignacion.AsignacionService.Asignacion;
import bo.uagrm.crm.dominio.captacion.Oferta;
import bo.uagrm.crm.dominio.captacion.Prospecto;
import bo.uagrm.crm.dominio.captacion.ProspectoService;
import bo.uagrm.crm.dominio.captacion.ProspectoService.Alta;
import bo.uagrm.crm.dominio.conversion.ConversionService;
import bo.uagrm.crm.dominio.conversion.ConversionService.Requisito;
import bo.uagrm.crm.dominio.conversion.Matricula;
import bo.uagrm.crm.dominio.embudo.EmbudoService;
import bo.uagrm.crm.dominio.embudo.Interes;
import bo.uagrm.crm.dominio.perdidas.MotivoPerdida;
import bo.uagrm.crm.dominio.perdidas.PerdidaService;
import bo.uagrm.crm.dominio.reportes.ReporteService;
import bo.uagrm.crm.dominio.seguimiento.Interaccion;
import bo.uagrm.crm.dominio.seguimiento.Recordatorio;
import bo.uagrm.crm.dominio.seguimiento.SeguimientoService;
import bo.uagrm.crm.dominio.seguimiento.SeguimientoService.Historial;
import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.dominio.usuarios.UsuarioService;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Procesador de un rol. Cada subclase declara su fila de la matriz de acceso (comandos()); aquí se
 * valida el permiso, se llama al servicio del caso de uso y se arma la respuesta con
 * ResponseFormatter.
 */
public abstract class RolCommandProcessor {

  protected final ResponseFormatter fmt;
  protected final UsuarioService usuarios;
  protected final ProspectoService prospectos;
  protected final AsignacionService asignacion;
  protected final EmbudoService embudo;
  protected final PerdidaService perdidas;
  protected final ConversionService conversion;
  protected final SeguimientoService seguimiento;
  protected final ReporteService reportes;
  protected final bo.uagrm.crm.dominio.pagos.PagoService pagos;
  protected final bo.uagrm.crm.dominio.conversion.ExportacionService exportaciones;

  protected RolCommandProcessor(DatabaseService db, ResponseFormatter fmt) {
    this.fmt = fmt;
    this.usuarios = new UsuarioService(db);
    this.prospectos = new ProspectoService(db);
    this.asignacion = new AsignacionService(db);
    this.embudo = new EmbudoService(db);
    this.perdidas = new PerdidaService(db);
    this.conversion = new ConversionService(db);
    this.seguimiento = new SeguimientoService(db);
    this.reportes = new ReporteService(db);
    this.pagos = new bo.uagrm.crm.dominio.pagos.PagoService(db);
    this.exportaciones = new bo.uagrm.crm.dominio.conversion.ExportacionService(db);
  }

  /** Comandos que este rol puede ejecutar (su fila de la matriz de acceso). */
  public abstract Set<Comando> comandos();

  /** Nombre del rol para los mensajes. */
  public abstract String rol();

  /** Mensaje cuando el comando no está entre los de este rol. */
  protected String mensajeDenegado(Command cmd) {
    return "Su rol ("
        + rol()
        + ") no tiene permiso para usar "
        + cmd.getNombre()
        + ". Envíe HELP[] para ver sus comandos.";
  }

  /**
   * Primero el permiso (matriz de acceso); solo si el rol lo tiene se llega al dominio y a la base
   * de datos.
   */
  public Respuesta procesar(Command cmd, Usuario u) {
    if (!comandos().contains(cmd.getTipo()))
      return fmt.denegado(cmd.getNombre(), mensajeDenegado(cmd));
    try {
      return ejecutar(cmd, u);
    } catch (CrmException e) {
      return fmt.error(cmd.getNombre(), e.getMessage());
    } catch (SQLException e) {
      return fmt.error(cmd.getNombre(), Errores.traducir(e));
    } catch (RuntimeException e) {
      // Un fallo imprevisto no detiene el servicio: se responde un error limpio y el
      // detalle queda en el log.
      java.util.logging.Logger.getLogger("crm")
          .log(java.util.logging.Level.SEVERE, "Fallo interno en " + cmd.getNombre(), e);
      return fmt.error(
          cmd.getNombre(),
          "Ocurrió un error interno al procesar el comando. Quedó registrado; intente de nuevo.");
    }
  }

  private Respuesta ejecutar(Command c, Usuario u) throws SQLException, CrmException {
    return switch (c.getTipo()) {
      case HELP -> ayuda(c.getParam(0), u);

      // CU1. Gestión de Usuarios
      case LISTARUSUARIOS -> {
        if (u.rol() == Rol.COORDINADOR
            && c.getParam(0) != null
            && !"vendedor".equalsIgnoreCase(c.getParam(0)))
          throw new CrmException("Coordinacion solo puede listar vendedores.");
        List<Usuario> l = usuarios.listar(u.rol() == Rol.COORDINADOR ? "vendedor" : c.getParam(0));
        yield fmt.successTable(
            "Listando usuarios " + u.getNombreCompleto(),
            l.size() + " usuario(s).",
            Usuario.COLUMNAS,
            filas(l, Usuario::fila));
      }
      case INSERTARUSUARIO -> {
        Usuario n =
            usuarios.insertar(
                c.getParam(0),
                c.getParam(1),
                c.getParam(2),
                c.getParam(3),
                c.getParam(4),
                c.getParam(5),
                c.getParam(6));
        yield fmt.successTable(
            "Éxito: usuario registrado",
            n.getNombreCompleto()
                + " quedó registrado como "
                + n.rol().nombre()
                + ". El administrador debe asignarle una clave desde web para habilitar su acceso"
                + " web. Ya puede enviar comandos desde "
                + n.correo()
                + " (credencial vigente del "
                + n.vigencia()
                + ").",
            Usuario.COLUMNAS,
            List.<String[]>of(n.fila()));
      }
      case MODIFICARUSUARIO -> {
        Usuario m =
            usuarios.modificar(
                u,
                c.getParam(0),
                c.getParam(1),
                c.getParam(2),
                c.getParam(3),
                c.getParam(4),
                c.getParam(5),
                c.getParam(6),
                c.getParam(7),
                c.getParam(8));
        yield fmt.successTable(
            "Éxito: usuario modificado",
            "Datos actualizados.",
            Usuario.COLUMNAS,
            List.<String[]>of(m.fila()));
      }
      case ELIMINARUSUARIO -> {
        int liberadas = usuarios.eliminar(u, c.getParam(0));
        yield fmt.success(
            "Éxito: usuario dado de baja",
            "El usuario "
                + c.getParam(0).trim()
                + " quedó inactivo y ya no puede enviar comandos."
                + (liberadas > 0
                    ? " " + liberadas + " tarjeta(s) abiertas volvieron a la bolsa común."
                    : ""));
      }

      // CU2. Gestión de Captar, Consumir Oferta
      case LISTAROFERTA -> {
        List<Oferta> l = prospectos.listarOferta(u != null);
        List<String[]> f = filas(l, Oferta::fila);
        String[] cols = Oferta.COLUMNAS;
        if (u == null) { // el público no ve el estado interno ni el coordinador
          cols = Arrays.copyOf(cols, 4);
          f = f.stream().map(r -> Arrays.copyOf(r, 4)).toList();
        }
        yield fmt.successTable(
            "Listando la oferta académica",
            "Catálogo simulado compartido con web (API SIS pendiente). "
                + (u == null
                    ? "Para inscribirse envíe"
                          + " REGISTRARINTERES[\"codigo_oferta\",\"documento\",\"nombre\",\"apellido\",\"telefono\"]."
                    : l.size() + " oferta(s)."),
            cols,
            f);
      }
      case REGISTRARINTERES -> {
        Alta a =
            prospectos.registrar(
                null,
                c.getParam(1),
                c.getParam(2),
                c.getParam(3),
                c.getParam(4),
                c.getRemitente(),
                "Correo",
                c.getParam(0),
                c.getParam(5),
                c.getParam(6));
        yield fmt.success(
            "Éxito: interés registrado",
            "Gracias, "
                + a.tarjeta().prospecto().nombre()
                + ". Registramos "
                + a.interesesCreados()
                + " interés(es). Primera oferta: "
                + a.tarjeta().ofertaNombre()
                + ". Un asesor le contactará al "
                + a.tarjeta().prospecto().telefono()
                + ".");
      }
      case REGISTRARPROSPECTO -> {
        Alta a =
            prospectos.registrar(
                u,
                c.getParam(0),
                c.getParam(1),
                c.getParam(2),
                c.getParam(3),
                c.getParam(4),
                c.getParam(5),
                c.getParam(6),
                c.getParam(7),
                c.getParam(8));
        yield fmt.successTable(
            "Éxito: prospecto registrado",
            (a.prospectoNuevo()
                    ? "Prospecto registrado."
                    : "El prospecto ya existía: se agregó su nuevo interés.")
                + " "
                + a.interesesCreados()
                + " interés(es) creados. Tarjeta #"
                + a.tarjeta().nro()
                + " en Nuevo, "
                + (a.tarjeta().vendedorCorreo() == null
                    ? "en la bolsa común."
                    : "en la cartera de " + a.tarjeta().vendedor() + "."),
            Interes.COLUMNAS,
            List.<String[]>of(a.tarjeta().fila()));
      }
      case IMPORTARPROSPECTOS -> {
        List<String[]> r = prospectos.importar(u, c.getAdjunto());
        long ok = r.stream().filter(f -> f[2].startsWith("Tarjeta")).count();
        yield fmt.successTable(
            "Importación de prospectos",
            ok + " de " + r.size() + " fila(s) importadas a la bolsa común.",
            ProspectoService.COLUMNAS_IMPORTACION,
            r);
      }
      case LISTARPROSPECTOS -> {
        List<Interes> l = prospectos.listar(u, c.getParam(0));
        yield fmt.successTable(
            "Listando prospectos",
            l.size() + " tarjeta(s)" + (u.rol() == Rol.VENDEDOR ? " de su cartera." : "."),
            Interes.COLUMNAS,
            filas(l, Interes::fila));
      }
      case MODIFICARPROSPECTO -> {
        Prospecto p =
            prospectos.modificar(
                u,
                c.getParam(0),
                c.getParam(1),
                c.getParam(2),
                c.getParam(3),
                c.getParam(4),
                c.getParam(5),
                c.getParam(6),
                c.getParam(7));
        yield fmt.success(
            "Éxito: prospecto modificado",
            p.getNombreCompleto()
                + " (CI "
                + p.ci()
                + "): teléfono "
                + p.telefono()
                + ", correo "
                + (p.correo() == null ? "(sin correo)" : p.correo())
                + ", origen "
                + p.origen()
                + ".");
      }
      case ELIMINARPROSPECTO -> {
        Prospecto p = prospectos.eliminar(c.getParam(0));
        yield fmt.success(
            "Éxito: prospecto eliminado",
            "Se eliminó a "
                + p.getNombreCompleto()
                + " (CI "
                + p.ci()
                + ") con sus tarjetas e historial.");
      }
      case ASIGNARCOORDINADOR -> {
        int n = prospectos.asignarCoordinador(u, c.getParam(0), c.getParam(1));
        yield fmt.success(
            "Éxito: coordinador asignado",
            "El programa "
                + c.getParam(0)
                + " y sus módulos ("
                + n
                + " ofertas) quedaron a cargo del usuario "
                + c.getParam(1)
                + ".");
      }

      // CU3. Gestión de Asignar Prospectos
      case LISTARBOLSA -> {
        List<Interes> l = asignacion.bolsa(u);
        yield fmt.successTable(
            "Bolsa común",
            l.size()
                + " prospecto(s) sin vendedor. Repártalos con ASIGNARAUTOMATICO[] o"
                + " ASIGNARPROSPECTO[\"nro_tarjeta\",\"correo_vendedor\"].",
            Interes.COLUMNAS,
            filas(l, Interes::fila));
      }
      case ASIGNARPROSPECTO -> {
        Asignacion a = asignacion.asignar(u, nro(c, 0), c.getParam(1));
        yield fmt.successTable(
                "Éxito: prospecto asignado",
                "La tarjeta #"
                    + a.tarjeta().nro()
                    + " pasó a la cartera de "
                    + a.vendedor().getNombreCompleto()
                    + ". Se le avisó por correo.",
                Interes.COLUMNAS,
                List.<String[]>of(a.tarjeta().fila()))
            .conAvisos(avisos(List.of(a)));
      }
      case ASIGNARAUTOMATICO -> {
        List<Asignacion> l = asignacion.asignarAutomatico(u);
        List<String[]> f =
            l.stream()
                .map(
                    a ->
                        new String[] {
                          "#" + a.tarjeta().nro(),
                          a.tarjeta().prospecto().getNombreCompleto(),
                          a.tarjeta().ofertaCodigo(),
                          a.vendedor().getNombreCompleto()
                        })
                .toList();
        yield fmt.successTable(
                "Éxito: distribución equitativa",
                l.size()
                    + " prospecto(s) repartidos en turno rotativo. Cada vendedor recibió un"
                    + " aviso.",
                new String[] {"Tarjeta", "Prospecto", "Oferta", "Vendedor"},
                f)
            .conAvisos(avisos(l));
      }

      // CU4. Gestión de Embudo de Ventas en Tablero Kanban
      case VERTABLERO -> tablero(u);
      case MOVERTARJETA -> {
        Interes t = embudo.mover(u, nro(c, 0), c.getParam(1));
        yield fmt.successTable(
            "Éxito: tarjeta movida",
            "La tarjeta #" + t.nro() + " pasó a la etapa " + EmbudoService.etapaCorreo(t.estado()) + ".",
            Interes.COLUMNAS,
            List.<String[]>of(t.fila()));
      }

      // CU5. Gestión de Pérdida de Prospectos y Clasificación de Motivos
      case LISTARMOTIVOS -> {
        List<MotivoPerdida> l = perdidas.listarMotivos();
        yield fmt.successTable(
            "Listando motivos de pérdida",
            l.size()
                + " motivo(s). Para marcar una pérdida:"
                + " MARCARPERDIDO[\"nro_tarjeta\",\"motivo\",\"detalle\"].",
            MotivoPerdida.COLUMNAS,
            filas(l, MotivoPerdida::fila));
      }
      case INSERTARMOTIVO -> {
        MotivoPerdida m = perdidas.insertarMotivo(c.getParam(0), c.getParam(1));
        yield fmt.successTable(
            "Éxito: motivo registrado",
            "Motivo \"" + m.nombre() + "\" registrado en " + m.categoria() + ".",
            MotivoPerdida.COLUMNAS,
            List.<String[]>of(m.fila()));
      }
      case MODIFICARMOTIVO -> {
        MotivoPerdida m = perdidas.modificarMotivo(c.getParam(0), c.getParam(1), c.getParam(2));
        yield fmt.successTable(
            "Éxito: motivo modificado",
            "Datos actualizados.",
            MotivoPerdida.COLUMNAS,
            List.<String[]>of(m.fila()));
      }
      case ELIMINARMOTIVO -> {
        MotivoPerdida m = perdidas.eliminarMotivo(c.getParam(0));
        yield fmt.success(
            "Éxito: motivo eliminado", "Se eliminó el motivo \"" + m.nombre() + "\".");
      }
      case MARCARPERDIDO -> {
        Interes t = perdidas.marcarPerdido(u, nro(c, 0), c.getParam(1), c.getParam(2));
        yield fmt.successTable(
            "Éxito: tarjeta marcada como Perdido",
            "Motivo: "
                + t.motivo()
                + ". Puede reactivarla con MOVERTARJETA[\""
                + t.nro()
                + "\",\"Seguimiento\"].",
            Interes.COLUMNAS,
            List.<String[]>of(t.fila()));
      }

      // CU6. Gestión de Validar Datos y Procesar Conversión a Matrícula
      case GENERARORDEN -> {
        var o = pagos.generar(u, nro(c, 0), c.getParam(1), c.getParam(2));
        yield fmt.successTable(
            "Orden de pago generada",
            "Referencia demostrativa. Registre el comprobante con REGISTRARCOMPROBANTE[\""
                + o.nro()
                + "\"]. No se ejecutó un cobro bancario.",
            bo.uagrm.crm.dominio.pagos.PagoService.Orden.COLUMNAS,
            List.<String[]>of(o.fila()));
      }
      case LISTARORDENES, LISTARPAGOS -> {
        var l = pagos.listar(u, c.getParam(0));
        yield fmt.successTable(
            "Órdenes y pagos",
            l.size() + " registro(s) dentro de su alcance.",
            bo.uagrm.crm.dominio.pagos.PagoService.Orden.COLUMNAS,
            filas(l, bo.uagrm.crm.dominio.pagos.PagoService.Orden::fila));
      }
      case VERORDEN -> {
        var o = pagos.ver(u, nro(c, 0));
        yield fmt.successTable(
            "Orden #" + o.nro(),
            "Estado del pago: " + o.estadoPago(),
            new Tabla(
                "Resumen",
                bo.uagrm.crm.dominio.pagos.PagoService.Orden.COLUMNAS,
                List.<String[]>of(o.fila())),
            new Tabla(
                "Modulos",
                new String[] {"Código", "Programa", "Módulo", "Precio (Bs)", "Estado"},
                pagos.items(u, o.nro())));
      }
      case LISTARDESCUENTOS ->
          fmt.successTable(
              "Descuentos activos",
              "Use sus codigos al generar una orden.",
              new String[] {"Código", "Descuento", "Porcentaje"},
              pagos.descuentos());
      case REGISTRARCOMPROBANTE -> {
        if (c.getErrorArchivo() != null) throw new CrmException(c.getErrorArchivo());
        var o = pagos.registrarComprobante(u, nro(c, 0), c.getArchivo(), c.getParam(1));
        yield fmt.successTable(
            "Comprobante en revisión",
            "El coordinador responsable debe revisar el adjunto con VERCOMPROBANTE y aprobarlo o"
                + " rechazarlo.",
            bo.uagrm.crm.dominio.pagos.PagoService.Orden.COLUMNAS,
            List.<String[]>of(o.fila()));
      }
      case VERCOMPROBANTE -> {
        int pago = nro(c, 0);
        var archivo = pagos.archivo(u, pago, c.getParam(1) == null ? null : nro(c, 1));
        yield fmt.successTable(
                "Comprobante del pago de la orden #" + pago,
                "Revise el archivo adjunto antes de tomar una decision.",
                bo.uagrm.crm.dominio.pagos.PagoService.Comprobante.COLUMNAS,
                filas(
                    pagos.comprobantes(u, pago),
                    bo.uagrm.crm.dominio.pagos.PagoService.Comprobante::fila))
            .conArchivos(List.of(archivo));
      }
      case APROBARPAGO -> {
        var o = pagos.aprobar(u, nro(c, 0), c.getParam(1));
        yield fmt.successTable(
            "Pago aprobado e inscripción confirmada",
            "Se validaron los datos y registraron las inscripciones y comisiones sin abrir web. El"
                + " envío al SIS es un paso independiente.",
            bo.uagrm.crm.dominio.pagos.PagoService.Orden.COLUMNAS,
            List.<String[]>of(o.fila()));
      }
      case RECHAZARPAGO -> {
        var o = pagos.rechazar(u, nro(c, 0), c.getParam(1));
        yield fmt.successTable(
            "Comprobante rechazado",
            "Motivo: " + c.getParam(1) + ". Puede adjuntarse un comprobante corregido.",
            bo.uagrm.crm.dominio.pagos.PagoService.Orden.COLUMNAS,
            List.<String[]>of(o.fila()));
      }
      case EXPORTARMATRICULAS -> {
        var e = exportaciones.preparar(u, c.getParam(0) == null ? null : nro(c, 0));
        yield fmt.successTable(
                "Intercambio académico preparado",
                e.filas().isEmpty()
                    ? "No hay inscripciones pendientes."
                    : "CSV adjunto preparado. La API del Grupo 06 aún no está disponible; no se"
                        + " afirma una recepcion automatica.",
                bo.uagrm.crm.dominio.conversion.ExportacionService.COLUMNAS,
                e.filas())
            .conArchivos(e.archivo() == null ? List.of() : List.of(e.archivo()));
      }
      case LISTAREXPORTACIONES ->
          fmt.successTable(
              "Intercambio académico",
              "Estados y constancias de las preparaciones.",
              bo.uagrm.crm.dominio.conversion.ExportacionService.COLUMNAS,
              exportaciones.listar(u));
      case VEREXPORTACION -> {
        var e = exportaciones.recuperar(u, nro(c, 0));
        yield fmt.successTable(
                "Preparacion academica",
                "CSV recuperado sin crear otro registro.",
                bo.uagrm.crm.dominio.conversion.ExportacionService.COLUMNAS,
                e.filas())
            .conArchivos(List.of(e.archivo()));
      }
      case REGISTRARRESULTADOSIS -> {
        exportaciones.resultado(u, nro(c, 0), c.getParam(1), c.getParam(2));
        yield fmt.success(
            "Constancia del SIS registrada",
            "Estado: " + c.getParam(1) + ". Constancia manual registrada por el responsable.");
      }

      case VALIDARCONVERSION -> {
        int nro = nro(c, 0);
        List<Requisito> l = conversion.validar(u, nro);
        long faltan = l.stream().filter(r -> !r.cumple()).count();
        yield fmt.successTable(
            "Validación de la tarjeta #" + nro,
            faltan == 0
                ? "Requisitos completos: consulte la inscripción con CONVERTIRPROSPECTO[\""
                    + nro
                    + "\"]."
                : "Faltan " + faltan + " requisito(s) para la matrícula.",
            Requisito.COLUMNAS,
            filas(l, Requisito::fila));
      }
      case CONVERTIRPROSPECTO -> {
        Matricula m = conversion.convertir(u, nro(c, 0));
        yield fmt.successTable(
            "Inscripción comercial confirmada",
            "Inscripción comercial #"
                + m.nro()
                + " por Bs "
                + DatabaseService.texto(m.monto())
                + ", estado "
                + m.estado()
                + ". La exportación al SIS se prepara aparte con EXPORTARMATRICULAS (administración o"
                + " coordinación).",
            Matricula.COLUMNAS,
            List.<String[]>of(m.fila()));
      }
      case LISTARMATRICULAS -> {
        List<Matricula> l = conversion.listarMatriculas(u);
        yield fmt.successTable(
            "Listando matrículas",
            l.size() + " conversión(es) para el SIS académico.",
            Matricula.COLUMNAS,
            filas(l, Matricula::fila));
      }

      // CU7. Gestión de Bitácora, Interacciones y Recordatorios
      case REGISTRARINTERACCION -> {
        Interaccion i = seguimiento.registrarInteraccion(u, nro(c, 0), c.getParam(1), c.getParam(2));
        yield fmt.success(
            "Éxito: interacción registrada",
            i.tipo() + " registrada en la tarjeta #" + i.nroTarjeta() + " el " + i.fecha() + ".");
      }
      case PROGRAMARRECORDATORIO -> {
        Recordatorio r =
            seguimiento.programarRecordatorio(u, nro(c, 0), c.getParam(1), c.getParam(2));
        yield fmt.success(
            "Éxito: recordatorio programado",
            "Recordatorio "
                + r.nro()
                + " para el "
                + r.fechaHora()
                + " ("
                + r.prospecto()
                + "). A esa hora le llegará un correo.");
      }
      case LISTARRECORDATORIOS -> {
        List<Recordatorio> l = seguimiento.listarRecordatorios(u);
        yield fmt.successTable(
            "Recordatorios pendientes",
            l.size() + " pendiente(s). Márquelos con COMPLETARRECORDATORIO[\"nro_recordatorio\",\"resultado\"].",
            Recordatorio.COLUMNAS,
            filas(l, Recordatorio::fila));
      }
      case COMPLETARRECORDATORIO -> {
        Recordatorio r = seguimiento.completarRecordatorio(u, nro(c, 0), c.getParam(1));
        yield fmt.success(
            "Éxito: recordatorio realizado",
            "El recordatorio " + r.nro() + " (" + r.descripcion() + ") quedó como Realizado.");
      }
      case VERHISTORIAL -> {
        Historial h = seguimiento.historial(u, nro(c, 0));
        Interes t = h.tarjeta();
        yield fmt.successTable(
            "Historial de la tarjeta #" + t.nro(),
            t.prospecto().getNombreCompleto()
                + " · "
                + t.ofertaNombre()
                + " · "
                + EmbudoService.etapaCorreo(t.estado())
                + " · "
                + t.nombreVendedor(),
            SeguimientoService.COLUMNAS_HISTORIAL,
            h.eventos().stream().map(RolCommandProcessor::conEtapasCorreo).toList());
      }
      case VERBITACORA -> {
        int n = c.getParam(0) == null ? 30 : Valida.entero(c.getParam(0), "cantidad");
        List<String[]> l = seguimiento.bitacora(n);
        yield fmt.successTable(
            "Bitácora del sistema",
            "Últimos " + l.size() + " correos procesados.",
            SeguimientoService.COLUMNAS_BITACORA,
            l);
      }

      // CU8. Reportes y Estadísticas
      case REPORTEEMBUDO -> {
        List<String[]> l = reportes.embudo(u);
        int conv = cuenta(l, "Convertido"), perd = cuenta(l, "Perdido");
        yield fmt.successTable(
            "Reporte del embudo de ventas",
            conv + perd == 0
                ? "Aún no hay tarjetas cerradas."
                : "Tasa de conversión de las tarjetas cerradas: "
                    + Math.round(100.0 * conv / (conv + perd))
                    + " % (convertidas: "
                    + conv
                    + "; perdidas: "
                    + perd
                    + ").",
            ReporteService.COLUMNAS_EMBUDO,
            l);
      }
      case REPORTEVENDEDORES ->
          fmt.successTable(
              "Reporte de vendedores",
              "Desempeño por vendedor.",
              ReporteService.COLUMNAS_VENDEDORES,
              reportes.vendedores(u));
      case REPORTEPERDIDAS ->
          fmt.successTable(
              "Reporte de pérdidas",
              "Motivos por los que se pierden prospectos.",
              new Tabla(
                  "Por categoría",
                  ReporteService.COLUMNAS_CATEGORIAS,
                  reportes.perdidasPorCategoria(u)),
              new Tabla(
                  "Por motivo", ReporteService.COLUMNAS_MOTIVOS, reportes.perdidasPorMotivo(u)));
      case REPORTEOFERTA ->
          fmt.successTable(
              "Reporte por oferta",
              "Interés y conversiones por programa o módulo.",
              ReporteService.COLUMNAS_OFERTA,
              reportes.oferta(u));

    };
  }

  private Respuesta ayuda(String nombre, Usuario u) throws CrmException {
    String saludo =
        u == null
            ? "Su correo no está registrado en el CRM: como visitante puede consultar la oferta y"
                + " registrar su interés."
            : "Hola, " + u.getNombreCompleto() + " (" + rol() + ").";
    if (nombre == null) {
      List<String[]> f = new ArrayList<>();
      for (Comando k : Comando.values())
        if (comandos().contains(k)) f.add(new String[] {k.firma(), k.cu, k.descripcion});
      return fmt.successTable(
          "Ayuda: comandos del rol " + rol(),
          saludo
              + " Escriba el comando en el asunto del correo, "
              + "con los parámetros entre comillas y separados por comas.",
          new String[] {"Comando", "CU", "Descripción"},
          f);
    }
    Comando k;
    try {
      k = Comando.valueOf(nombre.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new CrmException(
          "El comando " + nombre + " no existe. Envíe HELP[] para ver la lista.");
    }
    return fmt.successTable(
        "Ayuda: " + k.name(),
        saludo,
        new String[] {"Campo", "Valor"},
        List.of(
            new String[] {"Forma", k.firma()},
            new String[] {"Caso de uso", k.cu},
            new String[] {"Qué hace", k.descripcion},
            new String[] {"Ejemplo", k.ejemplo},
            new String[] {"Disponible para su rol", comandos().contains(k) ? "Sí" : "No"}));
  }

  private Respuesta tablero(Usuario u) throws SQLException {
    Map<String, List<String[]>> carriles = new LinkedHashMap<>();
    for (String e : EmbudoService.ESTADOS) carriles.put(e, new ArrayList<>());
    List<Interes> l = embudo.tablero(u);
    for (Interes t : l) {
      List<String> lineas =
          new ArrayList<>(
              List.of("#" + t.nro() + " · " + t.prospecto().getNombreCompleto(), t.ofertaNombre()));
      if (u.rol() != Rol.VENDEDOR) lineas.add("Vendedor: " + t.nombreVendedor());
      if (t.prospecto().telefono() != null) lineas.add("Tel. " + t.prospecto().telefono());
      if (t.motivo() != null) lineas.add("Motivo: " + t.motivo());
      carriles.get(EmbudoService.etapaCorreo(t.estado())).add(lineas.toArray(String[]::new));
    }
    Bloque[] bloques =
        carriles.entrySet().stream()
            .map(e -> new Carril(e.getKey(), e.getValue()))
            .toArray(Bloque[]::new);
    return fmt.successTable(
        "Tablero Kanban",
        l.size()
            + " tarjeta(s)"
            + (u.rol() == Rol.VENDEDOR ? " en su cartera." : ".")
            + " Muévalas con MOVERTARJETA[\"nro_tarjeta\",\"etapa\"].",
        bloques);
  }

  /** Un correo por vendedor con las tarjetas que recibió. */
  private List<Aviso> avisos(List<Asignacion> hechas) {
    Map<Usuario, List<String[]>> porVendedor = new LinkedHashMap<>();
    for (Asignacion a : hechas)
      porVendedor.computeIfAbsent(a.vendedor(), k -> new ArrayList<>()).add(a.tarjeta().fila());
    List<Aviso> out = new ArrayList<>();
    porVendedor.forEach(
        (v, f) ->
            out.add(
                new Aviso(
                    v.correo(),
                    fmt.aviso(
                        "Nuevos prospecto asignados",
                        "Hola, "
                            + v.nombre()
                            + ". Se le asignaron "
                            + f.size()
                            + " prospecto(s). Revise su tablero con VERTABLERO[].",
                        new Tabla(null, Interes.COLUMNAS, f)))));
    return out;
  }

  /** Movimientos del historial con las etapas del tablero; el detalle financiero (Pago enviado, En revisión) se conserva. */
  private static String[] conEtapasCorreo(String[] e) {
    if (!"Movimiento".equals(e[1])) return e;
    String[] r = e.clone();
    r[2] =
        r[2].replace("Contactado", "En Contacto")
            .replace("Interesado", "Seguimiento")
            .replace("En revision", "En revisión");
    return r;
  }

  private static int nro(Command c, int i) throws CrmException {
    return Valida.entero(c.getParam(i), c.getTipo().parametros[i].replace("?", ""));
  }

  private static int cuenta(List<String[]> filas, String estado) {
    return filas.stream()
        .filter(f -> f[0].equals(estado))
        .mapToInt(f -> Integer.parseInt(f[1]))
        .sum();
  }

  private static <T> List<String[]> filas(List<T> l, Function<T, String[]> f) {
    return l.stream().map(f).toList();
  }
}
