package bo.uagrm.crm.dominio.embudo;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

import bo.uagrm.crm.dominio.Alcance;
import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.dominio.usuarios.Rol;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** CU4. Gestión del Embudo de Ventas en Tablero Kanban. */
public final class EmbudoService {

  /** Columnas del tablero (RF3.2). */
  public static final List<String> ESTADOS_BD =
      List.of(
          "Nuevo",
          "Contactado",
          "Interesado",
          "Pago enviado",
          "En revision",
          "Convertido",
          "Perdido");

  public static final List<String> ESTADOS =
      List.of("Nuevo", "En Contacto", "Seguimiento", "Promesa de Pago", "Convertido", "Perdido");

  public static String etapaCorreo(String estado) {
    return switch (estado) {
      case "Contactado" -> "En Contacto";
      case "Interesado" -> "Seguimiento";
      case "Pago enviado", "En revision" -> "Promesa de Pago";
      default -> estado;
    };
  }

  private final DatabaseService db;

  public EmbudoService(DatabaseService db) {
    this.db = db;
  }

  /** Tarjetas del tablero: el vendedor ve su cartera; coordinador y administrador, su alcance. */
  public List<Interes> tablero(Usuario u) throws SQLException {
    try (Connection c = db.getConnection()) {
      return lista(
          c,
          Interes::de,
          Interes.SQL + " WHERE " + Alcance.propio(u) + " ORDER BY i.nro_tarjeta",
          Alcance.params(u));
    }
  }

  /**
   * Mueve la tarjeta entre las columnas abiertas. Perdido y Convertido tienen su propio comando.
   */
  public Interes mover(Usuario u, int nro, String estado) throws SQLException, CrmException {
    String entrada =
        switch (Valida.normal(estado == null ? "" : estado)) {
          case "en contacto" -> "Contactado";
          case "seguimiento" -> "Interesado";
          case "promesa de pago" -> "Pago enviado";
          default -> estado;
        };
    String nuevo = Valida.elegir(entrada, ESTADOS_BD, "estado");
    if (nuevo.equals("Perdido"))
      throw new CrmException(
          "Para marcar una tarjeta como Perdido use MARCARPERDIDO[\""
              + nro
              + "\",\"motivo\",\"detalle\"]: el motivo es obligatorio. Vea LISTARMOTIVOS[].");
    if (nuevo.equals("Convertido"))
      throw new CrmException(
          "Para convertir use CONVERTIRPROSPECTO[\""
              + nro
              + "\"]: antes se validan los datos para la matrícula.");
    if (nuevo.equals("Pago enviado") || nuevo.equals("En revision"))
      throw new CrmException(
          "Las etapas financieras se actualizan al registrar y revisar pagos con GENERARORDEN,"
              + " REGISTRARCOMPROBANTE y APROBARPAGO.");
    return db.enTransaccion(
        c -> {
          Interes t = tarjeta(c, u, nro, true);
          if (t.estado().equals("Convertido"))
            throw new CrmException("La tarjeta #" + nro + " ya fue convertida en matrícula.");
          if (t.estado().equals(nuevo))
            throw new CrmException("La tarjeta #" + nro + " ya está en " + nuevo + ".");
          actualizar(
              c,
              "UPDATE intereses SET estado = ?, motivo_perdida = NULL, observacion = NULL,"
                  + " updated_at = now(), ultima_actividad_en = now() WHERE nro_tarjeta = ?",
              nuevo,
              nro);
          registrarMovimiento(
              c, nro, u.correo(), t.estado(), nuevo, t.estado().equals("Perdido") ? "Reactivada" : null);
          return tarjeta(c, u, nro, false);
        });
  }

  /**
   * Carga una tarjeta y comprueba que el usuario puede verla (modificar = false) o trabajarla
   * (modificar = true). La usan CU3, CU5, CU6 y CU7.
   */
  public static Interes tarjeta(Connection c, Usuario u, int nro, boolean modificar)
      throws SQLException, CrmException {
    // Al modificar se bloquea la fila (FOR UPDATE) hasta el fin de la transacción: dos usuarios no
    // la cambian a la vez.
    Interes t =
        uno(
            c,
            Interes::de,
            Interes.SQL + " WHERE i.nro_tarjeta = ?" + (modificar ? " FOR UPDATE OF i" : ""),
            nro);
    if (t == null) throw new CrmException("No existe la tarjeta #" + nro + ".");
    boolean puede =
        switch (u.rol()) {
          case ADMINISTRADOR -> true;
          case COORDINADOR -> Objects.equals(t.coordinadorCorreo(), u.correo());
          case VENDEDOR -> Objects.equals(t.vendedorCorreo(), u.correo());
        };
    if (!puede)
      throw new CrmException(
          u.rol() == Rol.VENDEDOR
              ? "La tarjeta #"
                  + nro
                  + " no está en su cartera."
              : "La tarjeta #" + nro + " es de un programa que no está a su cargo.");
    return t;
  }

  public static void registrarMovimiento(
      Connection c, int nroTarjeta, String usuario, String anterior, String nuevo, String detalle)
      throws SQLException {
    actualizar(
        c,
        "INSERT INTO movimientos_interes (nro_tarjeta, usuario_correo, estado_anterior, estado_nuevo,"
            + " detalle) VALUES (?, ?, ?, ?, ?)",
        nroTarjeta,
        usuario,
        anterior,
        nuevo,
        detalle);
  }
}
