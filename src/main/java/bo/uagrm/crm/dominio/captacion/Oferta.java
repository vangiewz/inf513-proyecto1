package bo.uagrm.crm.dominio.captacion;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

import bo.uagrm.crm.persistencia.DatabaseService;

/** Entidad oferta: réplica local de lo que publica el SIS académico (Grupo 06 SA). */
public record Oferta(int id, String codigo, String nombre, String tipo, String programa, int numModulos,
                     BigDecimal precio, String estado, Integer coordinadorId, String coordinador) {

    public static final String SQL = """
            SELECT o.id, o.codigo, o.nombre, o.tipo, p.nombre AS programa, o.num_modulos, o.precio, o.estado,
                   o.coordinador_id, u.nombre || ' ' || u.apellido AS coordinador
            FROM oferta o
            LEFT JOIN oferta p ON p.id = o.programa_id
            LEFT JOIN usuario u ON u.id = o.coordinador_id""";
    public static final String[] COLUMNAS = {"Código", "Oferta", "Tipo", "Precio (Bs)", "Estado", "Coordinador"};

    public static Oferta de(ResultSet rs) throws SQLException {
        return new Oferta(rs.getInt("id"), rs.getString("codigo"), rs.getString("nombre"), rs.getString("tipo"),
                rs.getString("programa"), rs.getInt("num_modulos"), rs.getBigDecimal("precio"), rs.getString("estado"),
                (Integer) rs.getObject("coordinador_id"), rs.getString("coordinador"));
    }

    public boolean abierta() {
        return "abierto".equals(estado);
    }

    public String descripcionTipo() {
        return "programa".equals(tipo) ? "Programa de " + numModulos + " módulos" : "Módulo de " + programa;
    }

    public String[] fila() {
        return new String[]{codigo, nombre, descripcionTipo(), DatabaseService.texto(precio), estado,
                coordinador == null ? "Sin asignar" : coordinador};
    }
}
