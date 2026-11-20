package bo.uagrm.crm.dominio.pagos;

import static bo.uagrm.crm.persistencia.DatabaseService.*;

import bo.uagrm.crm.dominio.*;
import bo.uagrm.crm.dominio.captacion.Oferta;
import bo.uagrm.crm.dominio.embudo.*;
import bo.uagrm.crm.dominio.usuarios.*;
import bo.uagrm.crm.persistencia.DatabaseService;
import bo.uagrm.crm.presentacion.Adjunto;
import java.math.*;
import java.sql.*;
import java.util.*;

/** CU6. Orden, comprobante, revision e inscripcion sobre las mismas filas que Laravel. */
public final class PagoService {
  /** Orden de pago; su pago es 1 a 1 y comparte la clave nro_orden. */
  public record Orden(
      int nro,
      int nroTarjeta,
      String vendedorCorreo,
      String codigoPago,
      String prospecto,
      BigDecimal subtotal,
      BigDecimal descuento,
      BigDecimal total,
      String estadoPago,
      String fecha) {
    public static final String[] COLUMNAS = {
      "Orden", "Tarjeta", "Prospecto", "Total (Bs)", "Estado del pago", "Código de referencia"
    };

    public String[] fila() {
      return new String[] {"" + nro, "" + nroTarjeta, prospecto, texto(total), estadoPago, codigoPago};
    }
  }

  public record Comprobante(
      int nro,
      int nroOrden,
      String nombre,
      String mime,
      String fecha,
      String estado,
      String observacion) {
    public static final String[] COLUMNAS = {
      "Nro.", "Orden", "Archivo", "Tipo", "Fecha", "Estado", "Observación"
    };

    public String[] fila() {
      return new String[] {
        "" + nro, "" + nroOrden, nombre, mime, fecha, estado, observacion == null ? "" : observacion
      };
    }
  }

  private static final List<String> ESTADOS =
      List.of("Pendiente de pago", "En revision", "Pago aprobado", "Pago rechazado");
  private static final String ORDEN_SQL =
      """
      SELECT op.nro_orden,op.nro_tarjeta,op.vendedor_correo,op.subtotal,op.descuento_total,op.total,
      pg.codigo_qr,pg.estado AS estado_pago,op.fecha_generacion,
      p.nombre || ' ' || p.apellido AS prospecto
      FROM ordenes_pago op JOIN intereses i ON i.nro_tarjeta=op.nro_tarjeta
      JOIN prospectos p ON p.documento=i.documento JOIN pagos pg ON pg.nro_orden=op.nro_orden
      """;
  private final DatabaseService db;

  public PagoService(DatabaseService db) {
    this.db = db;
  }

  private static Orden de(ResultSet r) throws SQLException {
    return new Orden(
        r.getInt("nro_orden"),
        r.getInt("nro_tarjeta"),
        r.getString("vendedor_correo"),
        r.getString("codigo_qr"),
        r.getString("prospecto"),
        r.getBigDecimal("subtotal"),
        r.getBigDecimal("descuento_total"),
        r.getBigDecimal("total"),
        r.getString("estado_pago"),
        texto(r.getTimestamp("fecha_generacion")));
  }

  private static String alcance(Usuario u) {
    return Alcance.propio(u) + (u.rol() == Rol.VENDEDOR ? " AND op.vendedor_correo=?" : "");
  }

  private static Object[] args(Usuario u, Object... previos) {
    var values = new ArrayList<Object>(Arrays.asList(Alcance.despues(u, previos)));
    if (u.rol() == Rol.VENDEDOR) values.add(u.correo());
    return values.toArray();
  }

  private static void actor(Connection c, Usuario u, boolean revisar)
      throws SQLException, CrmException {
    if (revisar && u.rol() == Rol.VENDEDOR)
      throw new CrmException(
          "Solo administración o el coordinador responsable pueden revisar pagos.");
    uno(c, r -> 1, "SELECT pg_advisory_xact_lock(51305)");
    if (uno(
            c,
            r -> 1,
            "SELECT 1 FROM usuarios WHERE correo=? AND estado='activo' AND rol=?"
                + " AND " + bo.uagrm.crm.persistencia.DatabaseService.HOY + " BETWEEN fecha_inicio AND fecha_fin",
            u.correo(),
            u.rol().nombre())
        == null) throw new CrmException("Su usuario o rol cambió; se requiere una cuenta activa y vigente.");
  }

  private static Orden porPago(Connection c, Usuario u, int nro, boolean bloquear)
      throws SQLException, CrmException {
    Orden o =
        uno(
            c,
            PagoService::de,
            ORDEN_SQL + " WHERE pg.nro_orden=? AND " + alcance(u) + (bloquear ? " FOR UPDATE OF pg" : ""),
            args(u, nro));
    if (o == null) fueraDeAlcance(c, nro, "El pago");
    return o;
  }

  /** RF3.6: la comisión es configurable por administración (pedido de la clienta). Devuelve la anterior. */
  public String configurarComision(Usuario u, String porcentaje) throws SQLException, CrmException {
    if (u.rol() != Rol.ADMINISTRADOR)
      throw new AccesoDenegado("Solo administración configura la comisión.");
    BigDecimal pct;
    try {
      pct = new BigDecimal(porcentaje.trim().replace(',', '.'));
    } catch (NumberFormatException | NullPointerException e) {
      throw new CrmException("El porcentaje \"" + porcentaje + "\" debe ser un número, por ejemplo 2.5.");
    }
    if (pct.signum() < 0 || pct.compareTo(new BigDecimal("100")) > 0)
      throw new CrmException("El porcentaje debe estar entre 0 y 100.");
    BigDecimal valor = pct.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    return db.enTransaccion(
        c -> {
          String anterior =
              uno(c, r -> r.getString(1), "SELECT valor FROM configuraciones WHERE clave='comision_porcentaje'");
          actualizar(
              c,
              "INSERT INTO configuraciones(clave,valor,created_at,updated_at,actualizado_por)"
                  + " VALUES('comision_porcentaje',?,now(),now(),?) ON CONFLICT (clave) DO UPDATE SET"
                  + " valor=EXCLUDED.valor, updated_at=now(), actualizado_por=EXCLUDED.actualizado_por",
              valor.toPlainString(),
              u.correo());
          return anterior == null ? "0" : anterior;
        });
  }

  /** Error si la orden no existe; Denegado si existe pero es de otra cartera o programa. */
  private static void fueraDeAlcance(Connection c, int nro, String que) throws SQLException, CrmException {
    if (uno(c, r -> 1, "SELECT 1 FROM ordenes_pago WHERE nro_orden=?", nro) == null)
      throw new CrmException(que + " #" + nro + " no existe.");
    throw new AccesoDenegado(que + " #" + nro + " no pertenece a su cartera ni a sus programas.");
  }

  public List<Orden> listar(Usuario u, String estado) throws SQLException, CrmException {
    String filtro = estado == null ? "" : Valida.elegir(estado, ESTADOS, "estado de pago");
    try (Connection c = db.getConnection()) {
      return lista(
          c,
          PagoService::de,
          ORDEN_SQL + " WHERE (?='' OR pg.estado=?) AND " + alcance(u) + " ORDER BY op.nro_orden DESC",
          args(u, filtro, filtro));
    }
  }

  public Orden ver(Usuario u, int orden) throws SQLException, CrmException {
    try (Connection c = db.getConnection()) {
      Orden o =
          uno(c, PagoService::de, ORDEN_SQL + " WHERE op.nro_orden=? AND " + alcance(u), args(u, orden));
      if (o == null) fueraDeAlcance(c, orden, "La orden");
      return o;
    }
  }

  public List<String[]> items(Usuario u, int orden) throws SQLException, CrmException {
    ver(u, orden);
    try (Connection c = db.getConnection()) {
      return filas(
          c,
          "SELECT sc.modulo_codigo,sc.programa_nombre,sc.modulo_nombre,sc.precio_acordado,CASE WHEN EXISTS(SELECT 1"
              + " FROM inscripciones_comerciales ic WHERE ic.nro_solicitud=sc.nro_solicitud) THEN 'confirmada' ELSE"
              + " 'pendiente' END FROM solicitudes_compra sc WHERE sc.nro_orden=? ORDER BY sc.nro_solicitud",
          orden);
    }
  }

  public List<String[]> descuentos() throws SQLException {
    try (Connection c = db.getConnection()) {
      return filas(
          c, "SELECT codigo,nombre,porcentaje FROM descuentos WHERE estado='activo' ORDER BY codigo");
    }
  }

  public Orden generar(Usuario u, int tarjeta, String codigos, String descuentos)
      throws SQLException, CrmException {
    return db.enTransaccion(
        c -> {
          actor(c, u, false);
          Interes t = EmbudoService.tarjeta(c, u, tarjeta, true);
          if (t.cerrada())
            throw new CrmException("No se generan órdenes sobre intereses cerrados.");
          Object[] abierta =
              uno(
                  c,
                  r -> new Object[] {r.getInt(1), r.getString(2)},
                  "SELECT op.nro_orden,pg.estado FROM ordenes_pago op JOIN pagos pg ON pg.nro_orden=op.nro_orden"
                      + " WHERE op.nro_tarjeta=? AND pg.estado IN ('Pendiente de pago','En revision') LIMIT 1",
                  t.nro());
          if (abierta != null)
            throw new CrmException(
                "La tarjeta #" + t.nro() + " ya tiene la orden #" + abierta[0] + " (" + abierta[1]
                    + "). Registre su comprobante con REGISTRARCOMPROBANTE o espere su revisión.");
          if (t.vendedorCorreo() == null
              || uno(
                      c,
                      r -> 1,
                      "SELECT 1 FROM usuarios WHERE correo=? AND rol='vendedor' AND estado='activo'"
                          + " AND " + bo.uagrm.crm.persistencia.DatabaseService.HOY + " BETWEEN fecha_inicio AND fecha_fin",
                      t.vendedorCorreo())
                  == null) throw new CrmException("Asigne primero un vendedor activo.");
          Oferta base = uno(c, Oferta::de, Oferta.SQL + " WHERE o.codigo=?", t.ofertaCodigo());
          if (base == null)
            throw new CrmException("La oferta no está en el catálogo sincronizado.");
          List<String> seleccion = separar(codigos == null ? t.ofertaCodigo() : codigos);
          if (!seleccion.contains(t.ofertaCodigo()))
            throw new CrmException(
                "La orden debe incluir el módulo de la tarjeta; seleccione otra tarjeta para vender"
                    + " otro módulo.");
          List<Oferta> ofertas = new ArrayList<>();
          for (String codigo : seleccion) {
            Oferta f = uno(c, Oferta::de, Oferta.SQL + " WHERE o.codigo=?", codigo);
            if (f == null || !f.abierta())
              throw new CrmException("Hay un módulo inexistente o cerrado: " + codigo);
            if (!f.programaCodigo().equals(base.programaCodigo()))
              throw new CrmException(
                  "Los módulos de una orden deben corresponder al programa de la tarjeta.");
            if (u.rol() == Rol.COORDINADOR && !Objects.equals(f.coordinadorCorreo(), u.correo()))
              throw new AccesoDenegado("El programa no está a su cargo.");
            ofertas.add(f);
          }
          BigDecimal subtotal =
              ofertas.stream()
                  .map(Oferta::precio)
                  .reduce(BigDecimal.ZERO, BigDecimal::add)
                  .setScale(2, RoundingMode.HALF_UP);
          List<Object[]> aplicados = new ArrayList<>();
          BigDecimal porcentaje = BigDecimal.ZERO, rebaja = BigDecimal.ZERO;
          for (String codigo : separarOpcional(descuentos)) {
            Object[] f =
                uno(
                    c,
                    r -> new Object[] {r.getString(1), r.getBigDecimal(2)},
                    "SELECT codigo,porcentaje FROM descuentos WHERE upper(codigo)=upper(?) AND estado='activo'",
                    codigo);
            if (f == null)
              throw new CrmException("No existe un descuento activo con código " + codigo);
            BigDecimal pct = (BigDecimal) f[1];
            if (pct.signum() < 0 || pct.compareTo(new BigDecimal("100")) > 0)
              throw new CrmException("El descuento tiene un porcentaje inválido.");
            porcentaje = porcentaje.add(pct);
            BigDecimal monto =
                subtotal.multiply(pct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            rebaja = rebaja.add(monto);
            aplicados.add(new Object[] {f[0], pct, monto});
          }
          if (porcentaje.compareTo(new BigDecimal("100")) > 0)
            throw new CrmException("Los descuentos combinados superan el 100 %.");
          BigDecimal total = subtotal.subtract(rebaja).max(BigDecimal.ZERO);
          int orden =
              insertar(
                  c,
                  "nro_orden",
                  """
INSERT INTO ordenes_pago(nro_tarjeta,vendedor_correo,subtotal,descuento_total,total,fecha_generacion,created_at,updated_at)
VALUES(?,?,?,?,?,now(),now(),now())
""",
                  t.nro(),
                  t.vendedorCorreo(),
                  subtotal,
                  rebaja,
                  total);
          for (Object[] f : aplicados)
            actualizar(
                c,
                "INSERT INTO"
                    + " orden_pago_descuentos(nro_orden,descuento_codigo,porcentaje,monto,created_at,updated_at)"
                    + " VALUES(?,?,?,?,now(),now())",
                orden,
                f[0],
                f[1],
                f[2]);
          // Precio acordado de cada módulo = precio de lista con el descuento de la orden; el último absorbe el
          // redondeo para que la suma sea exactamente el total cobrado (matrícula y comisión sobre lo pagado).
          List<BigDecimal> acordados = new ArrayList<>();
          BigDecimal acumulado = BigDecimal.ZERO;
          for (int k = 0; k < ofertas.size(); k++) {
            BigDecimal precio =
                k == ofertas.size() - 1
                    ? total.subtract(acumulado)
                    : subtotal.signum() == 0
                        ? BigDecimal.ZERO
                        : ofertas.get(k).precio().multiply(total).divide(subtotal, 2, RoundingMode.HALF_UP);
            acordados.add(precio);
            acumulado = acumulado.add(precio);
          }
          for (int k = 0; k < ofertas.size(); k++) {
            Oferta f = ofertas.get(k);
            actualizar(
                c,
                """
INSERT INTO solicitudes_compra(nro_orden,anuncio_codigo,modulo_codigo,programa_nombre,modulo_nombre,modulo_descripcion,precio_acordado,fecha,created_at,updated_at)
VALUES(?,?,?,?,?,?,?,now(),now(),now())
""",
                orden,
                f.anuncioId(),
                f.codigo(),
                f.programa(),
                f.nombre(),
                f.descripcion(),
                acordados.get(k));
          }
          String codigo =
              "CRM-QR-ORD-"
                  + orden
                  + "-"
                  + UUID.randomUUID()
                      .toString()
                      .replace("-", "")
                      .substring(0, 10)
                      .toUpperCase(Locale.ROOT);
          actualizar(
                  c,
                  "INSERT INTO"
                      + " pagos(nro_orden,codigo_qr,estado,created_at,updated_at)"
                      + " VALUES(?,?,'Pendiente de pago',now(),now())",
                  orden,
                  codigo);
          evento(
              c,
              u,
              t,
              "Pago enviado",
              "Orden de pago enviada",
              "Orden #"
                  + orden
                  + " por Bs "
                  + texto(total)
                  + ". Referencia de demostración; no es un cobro bancario.");
          return porPago(c, u, orden, false);
        });
  }

  public Orden registrarComprobante(Usuario u, int pago, Adjunto archivo, String observacion)
      throws SQLException, CrmException {
    if (archivo == null)
      throw new CrmException("Adjunte un comprobante PNG, JPEG, GIF, WebP o PDF de hasta 5 MB.");
    Adjunto valido = archivo.comoComprobante();
    String nota = Valida.opcional(observacion, "observacion", 2000);
    return db.enTransaccion(
        c -> {
          actor(c, u, false);
          Orden o = porPago(c, u, pago, true);
          Interes t = EmbudoService.tarjeta(c, u, o.nroTarjeta(), true);
          if (!List.of("Pendiente de pago", "Pago rechazado").contains(o.estadoPago()))
            throw new CrmException("El pago ya está en revisión o fue aprobado.");
          if (t.cerrada())
            throw new CrmException("La tarjeta está cerrada; no admite otro comprobante.");
          actualizar(
              c,
              """
INSERT INTO comprobantes_pago(nro_orden,archivo,nombre_archivo,mime,fecha_envio,observacion,estado_revision,remitente,created_at,updated_at)
VALUES(?,?,?,?,now(),?,'En revision',?,now(),now())
""",
              pago,
              valido.dataUrl(),
              valido.nombre(),
              valido.mime(),
              nota,
              u.correo());
          estados(c, o, "En revision", null);
          evento(
              c,
              u,
              t,
              "En revision",
              "Pago informado",
              "Comprobante registrado por correo para revisión. " + (nota == null ? "" : nota));
          return porPago(c, u, pago, false);
        });
  }

  public List<Comprobante> comprobantes(Usuario u, int pago) throws SQLException, CrmException {
    try (Connection c = db.getConnection()) {
      porPago(c, u, pago, false);
      return lista(
          c,
          r ->
              new Comprobante(
                  r.getInt("nro_comprobante"),
                  pago,
                  r.getString("nombre_archivo"),
                  r.getString("mime"),
                  texto(r.getTimestamp("fecha_envio")),
                  r.getString("estado_revision"),
                  r.getString("observacion")),
          "SELECT nro_comprobante,nombre_archivo,mime,fecha_envio,estado_revision,observacion FROM"
              + " comprobantes_pago WHERE nro_orden=? ORDER BY nro_comprobante DESC",
          pago);
    }
  }

  public Adjunto archivo(Usuario u, int pago, Integer nro) throws SQLException, CrmException {
    try (Connection c = db.getConnection()) {
      porPago(c, u, pago, false);
      String[] f =
          uno(
              c,
              r -> new String[] {r.getString(1), r.getString(2)},
              "SELECT nombre_archivo,archivo FROM comprobantes_pago WHERE nro_orden=? AND (?::int IS"
                  + " NULL OR nro_comprobante=?) ORDER BY nro_comprobante DESC LIMIT 1",
              pago,
              nro,
              nro);
      if (f == null) throw new CrmException("No existe ese comprobante para el pago.");
      return Adjunto.desdeDataUrl(f[0], f[1]);
    }
  }

  public Orden rechazar(Usuario u, int pago, String observacion) throws SQLException, CrmException {
    String nota = Valida.texto(observacion, "motivo de rechazo", 2000);
    return db.enTransaccion(
        c -> {
          actor(c, u, true);
          Orden o = porPago(c, u, pago, true);
          if (!"En revision".equals(o.estadoPago()))
            throw new CrmException("Solo se rechaza un pago en revisión.");
          Interes t = EmbudoService.tarjeta(c, u, o.nroTarjeta(), true);
          if (t.estado().equals("Convertido"))
            throw new CrmException("La inscripción ya fue confirmada.");
          estados(c, o, "Pago rechazado", nota);
          actualizar(
              c,
              "UPDATE comprobantes_pago SET estado_revision='Pago rechazado',updated_at=now() WHERE"
                  + " nro_orden=? AND estado_revision='En revision'",
              pago);
          evento(c, u, t, "Pago enviado", "Nota comercial", "Comprobante rechazado: " + nota);
          return porPago(c, u, pago, false);
        });
  }

  public Orden aprobar(Usuario u, int pago, String observacion) throws SQLException, CrmException {
    String nota = Valida.opcional(observacion, "observacion", 2000);
    return db.enTransaccion(
        c -> {
          actor(c, u, true);
          Orden o = porPago(c, u, pago, true);
          if (!"En revision".equals(o.estadoPago()))
            throw new CrmException(
                "Solo se aprueba un pago en revisión; no se repite una aprobación.");
          String prospecto =
              uno(
                  c,
                  r -> r.getString(1),
                  "SELECT documento FROM intereses WHERE nro_tarjeta=?",
                  o.nroTarjeta());
          uno(c, r -> r.getString(1), "SELECT documento FROM prospectos WHERE documento=? FOR UPDATE", prospecto);
          Interes t = EmbudoService.tarjeta(c, u, o.nroTarjeta(), true);
          if (t.cerrada())
            throw new CrmException("La tarjeta está cerrada; revise su estado antes de aprobar.");
          List<String> faltan = faltantes(c, t);
          if (!faltan.isEmpty())
            throw new CrmException(
                "Complete la ficha antes de convertir: "
                    + String.join(", ", faltan)
                    + ". Use MODIFICARPROSPECTO con profesion y ubicacion.");
          if (uno(
                  c,
                  r -> 1,
                  "SELECT 1 FROM comprobantes_pago WHERE nro_orden=? AND estado_revision='En"
                      + " revision'",
                  pago)
              == null) throw new CrmException("No hay un comprobante en revisión.");
          BigDecimal porcentaje =
              uno(
                  c,
                  r -> r.getBigDecimal(1),
                  "SELECT valor::numeric FROM configuraciones WHERE clave='comision_porcentaje'");
          if (porcentaje == null) porcentaje = new BigDecimal("2");
          if (porcentaje.signum() < 0 || porcentaje.compareTo(new BigDecimal("100")) > 0)
            throw new CrmException("El porcentaje de comision configurado es invalido.");
          List<Object[]> ventas =
              lista(
                  c,
                  r -> new Object[] {r.getInt(1), r.getString(2), r.getBigDecimal(3)},
                  "SELECT nro_solicitud,modulo_codigo,precio_acordado FROM solicitudes_compra WHERE"
                      + " nro_orden=? ORDER BY nro_solicitud FOR UPDATE",
                  o.nro());
          if (ventas.isEmpty()) throw new CrmException("La orden no tiene módulos.");
          for (Object[] venta : ventas) {
            if (uno(
                    c,
                    r -> 1,
                    """
SELECT 1 FROM inscripciones_comerciales ic JOIN solicitudes_compra sc ON sc.nro_solicitud=ic.nro_solicitud
JOIN ordenes_pago op ON op.nro_orden=sc.nro_orden JOIN intereses i ON i.nro_tarjeta=op.nro_tarjeta
WHERE i.documento=? AND sc.modulo_codigo=? LIMIT 1
""",
                    prospecto,
                    venta[1])
                != null)
              throw new CrmException(
                  "El prospecto ya tiene una inscripción confirmada en el módulo " + venta[1]);
            actualizar(
                c,
                "INSERT INTO"
                    + " inscripciones_comerciales(nro_solicitud,fecha_confirmacion,created_at,updated_at)"
                    + " VALUES(?,now(),now(),now())",
                venta[0]);
            BigDecimal comision =
                ((BigDecimal) venta[2])
                    .multiply(porcentaje)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            actualizar(
                c,
                "INSERT INTO"
                    + " comisiones(vendedor_correo,nro_solicitud,porcentaje,monto,estado,created_at,updated_at)"
                    + " VALUES(?,?,?,?,'Pendiente',now(),now())",
                o.vendedorCorreo(),
                venta[0],
                porcentaje,
                comision);
          }
          for (Object[] venta : ventas) {
            Interes vinculado =
                uno(
                    c,
                    Interes::de,
                    Interes.SQL
                        + " WHERE i.documento=? AND i.modulo_codigo=? FOR UPDATE OF i",
                    prospecto,
                    venta[1]);
            if (vinculado != null && vinculado.nro() != t.nro()) {
              if (vinculado.cerrada() || vinculado.vendedorCorreo() == null)
                throw new CrmException(
                    "Asigne o reactive todos los intereses del programa antes de confirmar la"
                        + " venta.");
              evento(
                  c,
                  u,
                  vinculado,
                  "Convertido",
                  "Compra validada",
                  "Módulo inscrito mediante la orden #" + o.nro());
            }
          }
          estados(c, o, "Pago aprobado", nota);
          actualizar(
              c,
              "UPDATE comprobantes_pago SET estado_revision='Pago aprobado',updated_at=now() WHERE"
                  + " nro_orden=? AND estado_revision='En revision'",
              pago);
          actualizar(
              c,
              "UPDATE prospectos SET estado='Convertido',updated_at=now() WHERE documento=?",
              prospecto);
          evento(
              c,
              u,
              t,
              "Convertido",
              "Compra validada",
              "Pago #"
                  + pago
                  + " aprobado por correo. Inscripción comercial confirmada; envío al SIS"
                  + " pendiente.");
          return porPago(c, u, pago, false);
        });
  }

  public static List<String> faltantes(Connection c, Interes t) throws SQLException {
    List<String> out = new ArrayList<>();
    var p = t.prospecto();
    if (p.ci() == null || p.ci().isBlank()) out.add("documento");
    if (p.nombre() == null
        || p.nombre().isBlank()
        || p.apellido() == null
        || p.apellido().isBlank()) out.add("nombre y apellido");
    if (!Valida.esTelefono(p.telefono())) out.add("telefono");
    if (!Valida.esCorreo(p.correo())) out.add("correo");
    String[] ficha =
        uno(
            c,
            r -> new String[] {r.getString(1), r.getString(2)},
            "SELECT profesion,ubicacion FROM prospectos WHERE documento=?",
            p.ci());
    if (ficha[0] == null || ficha[0].isBlank()) out.add("profesion");
    if (ficha[1] == null || ficha[1].isBlank()) out.add("ubicacion");
    if (t.vendedorCorreo() == null) out.add("vendedor asignado");
    return out;
  }

  private static void estados(Connection c, Orden o, String estado, String nota)
      throws SQLException {
    actualizar(
        c,
        "UPDATE pagos SET estado=?,fecha_validacion=CASE WHEN ? IN ('Pago aprobado','Pago"
            + " rechazado') THEN now() ELSE fecha_validacion"
            + " END,observacion_validacion=?,updated_at=now() WHERE nro_orden=?",
        estado,
        estado,
        nota,
        o.nro());
    actualizar(c, "UPDATE ordenes_pago SET updated_at=now() WHERE nro_orden=?", o.nro());
  }

  private static void evento(
      Connection c, Usuario u, Interes t, String estado, String tipo, String detalle)
      throws SQLException {
    actualizar(
        c,
        "UPDATE intereses SET estado=?,ultima_actividad_en=now(),updated_at=now() WHERE nro_tarjeta=?",
        estado,
        t.nro());
    actualizar(
        c,
        "INSERT INTO"
            + " interacciones(nro_tarjeta,usuario_correo,tipo,descripcion,fecha,created_at,updated_at)"
            + " VALUES(?,?,?,?,now(),now(),now())",
        t.nro(),
        u.correo(),
        tipo,
        detalle);
    EmbudoService.registrarMovimiento(c, t.nro(), u.correo(), t.estado(), estado, detalle);
  }

  private static List<String> separar(String texto) throws CrmException {
    List<String> out = separarOpcional(texto);
    if (out.isEmpty()) throw new CrmException("Seleccione al menos un módulo.");
    return out;
  }

  private static List<String> separarOpcional(String texto) throws CrmException {
    if (texto == null || texto.isBlank()) return List.of();
    var unicos = new LinkedHashSet<String>();
    for (String token : texto.split(";", -1)) unicos.add(Valida.texto(token, "codigo", 255));
    if (unicos.size() > 100) throw new CrmException("Se admiten hasta 100 codigos por orden.");
    return List.copyOf(unicos);
  }
}
