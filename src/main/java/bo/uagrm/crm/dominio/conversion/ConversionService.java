package bo.uagrm.crm.dominio.conversion;

import bo.uagrm.crm.dominio.Alcance;
import bo.uagrm.crm.dominio.CrmException;
import bo.uagrm.crm.dominio.Valida;
import bo.uagrm.crm.dominio.embudo.EmbudoService;
import bo.uagrm.crm.dominio.embudo.Interes;
import bo.uagrm.crm.dominio.usuarios.Usuario;
import bo.uagrm.crm.persistencia.DatabaseService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static bo.uagrm.crm.persistencia.DatabaseService.actualizar;
import static bo.uagrm.crm.persistencia.DatabaseService.lista;
import static bo.uagrm.crm.persistencia.DatabaseService.uno;

/** CU6. Gestión de Validar Datos y Procesar Conversión a Matrícula (RF3.4). */
public final class ConversionService {

    /** Un requisito de la matrícula, con el valor revisado y si se cumple. */
    public record Requisito(String nombre, String valor, boolean cumple) {
        public static final String[] COLUMNAS = {"Requisito", "Valor", "Cumple"};

        public String[] fila() {
            return new String[]{nombre, valor, cumple ? "Sí" : "No"};
        }
    }

    private final DatabaseService db;

    public ConversionService(DatabaseService db) {
        this.db = db;
    }

    public List<Requisito> validar(Usuario u, int tarjetaId) throws SQLException, CrmException {
        try (Connection c = db.getConnection()) {
            return requisitos(EmbudoService.tarjeta(c, u, tarjetaId, false));
        }
    }

    /** Solo convierte si se cumplen todos los requisitos; deja la matrícula Pendiente SIS. */
    public Matricula convertir(Usuario u, int tarjetaId) throws SQLException, CrmException {
        return db.enTransaccion(c -> {
            Interes t = EmbudoService.tarjeta(c, u, tarjetaId, true);
            if (t.estado().equals("Convertido")) throw new CrmException("La tarjeta #" + tarjetaId + " ya fue convertida.");
            List<String> faltan = requisitos(t).stream().filter(r -> !r.cumple()).map(Requisito::nombre).toList();
            if (!faltan.isEmpty())
                throw new CrmException("No se puede convertir la tarjeta #" + tarjetaId + ". Falta: " + String.join("; ", faltan)
                        + ". Revise el detalle con VALIDARCONVERSION[\"" + tarjetaId + "\"].");
            actualizar(c, "UPDATE interes SET estado = 'Convertido' WHERE id = ?", tarjetaId);
            int id = DatabaseService.insertar(c, "INSERT INTO matricula (interes_id, usuario_id, monto) VALUES (?, ?, ?)",
                    tarjetaId, u.id(), t.precio());
            EmbudoService.registrarMovimiento(c, tarjetaId, u.id(), t.estado(), "Convertido", "Matrícula #" + id + " para el SIS");
            return uno(c, Matricula::de, Matricula.SQL + " WHERE m.id = ?", id);
        });
    }

    public List<Matricula> listarMatriculas(Usuario u) throws SQLException {
        try (Connection c = db.getConnection()) {
            return lista(c, Matricula::de, Matricula.SQL + " WHERE " + Alcance.propio(u) + " ORDER BY m.id DESC", Alcance.params(u));
        }
    }

    private static List<Requisito> requisitos(Interes t) {
        var p = t.prospecto();
        return List.of(
                new Requisito("CI válido", p.ci(), Valida.esCi(p.ci())),
                new Requisito("Nombre y apellido", p.getNombreCompleto(), !p.nombre().isBlank() && !p.apellido().isBlank()),
                new Requisito("Teléfono de 7 a 15 dígitos", nv(p.telefono()), Valida.esTelefono(p.telefono())),
                new Requisito("Correo electrónico válido", nv(p.correo()), Valida.esCorreo(p.correo())),
                new Requisito("Oferta abierta en el SIS", t.ofertaCodigo() + " (" + t.ofertaEstado() + ")", "abierto".equals(t.ofertaEstado())),
                new Requisito("Vendedor asignado", t.nombreVendedor(), t.vendedorId() != null),
                new Requisito("Etapa Promesa de Pago", t.estado(), "Promesa de Pago".equals(t.estado())));
    }

    private static String nv(String s) {
        return s == null || s.isBlank() ? "(vacío)" : s;
    }
}
