package bo.uagrm.crm.aplicacion;

/**
 * Catálogo de comandos: caso de uso, parámetros, descripción y ejemplo.
 * Qué rol puede usar cada comando lo define su RolCommandProcessor (matriz de acceso).
 * Un parámetro que termina en "?" es opcional. HELP[] sale de aquí.
 */
public enum Comando {
    HELP("General", "comando?",
            "Lista los comandos de su rol o explica uno", "HELP[\"INSERTARUSUARIO\"]"),

    LISTARUSUARIOS("CU1", "rol?",
            "Lista los usuarios, opcionalmente de un rol", "LISTARUSUARIOS[\"vendedor\"]"),
    INSERTARUSUARIO("CU1", "nombre,apellido,correo,telefono,rol",
            "Registra un usuario (administrador, coordinador o vendedor)",
            "INSERTARUSUARIO[\"Ana\",\"Rojas Vaca\",\"ana.rojas@example.com\",\"71234567\",\"vendedor\"]"),
    MODIFICARUSUARIO("CU1", "id,nombre,apellido,correo,telefono,rol,estado",
            "Modifica los datos, el rol o el estado (activo/inactivo) de un usuario",
            "MODIFICARUSUARIO[\"4\",\"Ana\",\"Rojas Vaca\",\"ana.rojas@example.com\",\"71234567\",\"coordinador\",\"activo\"]"),
    ELIMINARUSUARIO("CU1", "id",
            "Da de baja a un usuario y devuelve sus prospectos abiertos a la bolsa común", "ELIMINARUSUARIO[\"4\"]"),

    LISTAROFERTA("CU2", "",
            "Lista la oferta académica que publica el SIS (Grupo 06 SA)", "LISTAROFERTA[]"),
    REGISTRARINTERES("CU2", "codigo_oferta,ci,nombre,apellido,telefono",
            "Registra su interés en una oferta; un asesor le contactará",
            "REGISTRARINTERES[\"desarrollo-web\",\"4715292\",\"Juan Carlos\",\"Perez Seras\",\"71055123\"]"),
    REGISTRARPROSPECTO("CU2", "ci,nombre,apellido,telefono,correo,origen,codigo_oferta",
            "Registra un prospecto con su interés (origen: Facebook, Instagram, TikTok, WhatsApp, Referido, Web, Correo, Feria u Otro)",
            "REGISTRARPROSPECTO[\"4715292\",\"Juan Carlos\",\"Perez Seras\",\"71055123\",\"juanperez@uagrm.edu.bo\",\"Facebook\",\"desarrollo-web\"]"),
    IMPORTARPROSPECTOS("CU2", "",
            "Importa prospectos del archivo .csv adjunto (columnas: ci,nombre,apellido,telefono,correo,origen,codigo_oferta)",
            "IMPORTARPROSPECTOS[] con prospectos.csv adjunto"),
    LISTARPROSPECTOS("CU2", "texto?",
            "Lista los prospectos que puede ver; opcional: CI o parte del nombre", "LISTARPROSPECTOS[\"Perez\"]"),
    MODIFICARPROSPECTO("CU2", "ci,nombre,apellido,telefono,correo,origen",
            "Modifica los datos de un prospecto",
            "MODIFICARPROSPECTO[\"4715292\",\"Juan Carlos\",\"Perez Seras\",\"71055123\",\"juan.perez@example.com\",\"Facebook\"]"),
    ELIMINARPROSPECTO("CU2", "ci",
            "Elimina un prospecto y su historial (no si ya tiene matrícula)", "ELIMINARPROSPECTO[\"4715292\"]"),
    ASIGNARCOORDINADOR("CU2", "codigo_programa,id_coordinador",
            "Pone un programa y sus módulos a cargo de un coordinador", "ASIGNARCOORDINADOR[\"desarrollo-web\",\"2\"]"),

    LISTARBOLSA("CU3", "",
            "Lista los prospectos sin vendedor (bolsa común)", "LISTARBOLSA[]"),
    ASIGNARPROSPECTO("CU3", "id_tarjeta,id_vendedor",
            "Asigna un prospecto a un vendedor", "ASIGNARPROSPECTO[\"7\",\"3\"]"),
    ASIGNARAUTOMATICO("CU3", "",
            "Reparte la bolsa común entre los vendedores activos por Round-Robin", "ASIGNARAUTOMATICO[]"),
    TOMARPROSPECTO("CU3", "id_tarjeta",
            "Toma un prospecto de la bolsa común para su cartera", "TOMARPROSPECTO[\"7\"]"),

    VERTABLERO("CU4", "",
            "Muestra el tablero Kanban del embudo de ventas", "VERTABLERO[]"),
    MOVERTARJETA("CU4", "id_tarjeta,estado",
            "Mueve una tarjeta a Nuevo, En Contacto, Seguimiento o Promesa de Pago", "MOVERTARJETA[\"7\",\"En Contacto\"]"),

    LISTARMOTIVOS("CU5", "",
            "Lista los motivos de pérdida y su categoría", "LISTARMOTIVOS[]"),
    INSERTARMOTIVO("CU5", "nombre,categoria",
            "Registra un motivo (categoría: Económico, Horario, Incompatibilidad, Contacto u Otro)",
            "INSERTARMOTIVO[\"Prefiere clases presenciales\",\"Incompatibilidad\"]"),
    MODIFICARMOTIVO("CU5", "id,nombre,categoria",
            "Modifica un motivo o su categoría", "MODIFICARMOTIVO[\"10\",\"Prefiere modalidad presencial\",\"Incompatibilidad\"]"),
    ELIMINARMOTIVO("CU5", "id",
            "Elimina un motivo que no se haya usado", "ELIMINARMOTIVO[\"10\"]"),
    MARCARPERDIDO("CU5", "id_tarjeta,id_motivo,detalle?",
            "Marca una tarjeta como Perdido; el motivo es obligatorio", "MARCARPERDIDO[\"7\",\"3\",\"Trabaja por las noches\"]"),

    VALIDARCONVERSION("CU6", "id_tarjeta",
            "Revisa si los datos están completos para la matrícula", "VALIDARCONVERSION[\"7\"]"),
    CONVERTIRPROSPECTO("CU6", "id_tarjeta",
            "Valida los datos y convierte la tarjeta en matrícula para el SIS", "CONVERTIRPROSPECTO[\"7\"]"),
    LISTARMATRICULAS("CU6", "",
            "Lista las conversiones enviadas al SIS académico", "LISTARMATRICULAS[]"),

    REGISTRARINTERACCION("CU7", "id_tarjeta,tipo,descripcion",
            "Registra una interacción o nota (tipo: Llamada, WhatsApp, Correo, Reunión o Nota)",
            "REGISTRARINTERACCION[\"7\",\"WhatsApp\",\"Se envió el PDF del diplomado\"]"),
    PROGRAMARRECORDATORIO("CU7", "id_tarjeta,fecha_hora,descripcion",
            "Programa un recordatorio (AAAA-MM-DD HH:MM); llega por correo a esa hora",
            "PROGRAMARRECORDATORIO[\"7\",\"2026-10-15 10:00\",\"Llamar para confirmar el pago\"]"),
    LISTARRECORDATORIOS("CU7", "",
            "Lista sus recordatorios pendientes", "LISTARRECORDATORIOS[]"),
    COMPLETARRECORDATORIO("CU7", "id_recordatorio",
            "Marca un recordatorio como realizado", "COMPLETARRECORDATORIO[\"5\"]"),
    VERHISTORIAL("CU7", "id_tarjeta",
            "Bitácora cronológica de una tarjeta: movimientos, interacciones y recordatorios", "VERHISTORIAL[\"7\"]"),
    VERBITACORA("CU7", "cantidad?",
            "Bitácora del sistema: últimos correos procesados, incluidos los rechazados por rol", "VERBITACORA[\"20\"]"),

    REPORTEEMBUDO("CU8", "",
            "Tarjetas por etapa del embudo y tasa de conversión", "REPORTEEMBUDO[]"),
    REPORTEVENDEDORES("CU8", "",
            "Desempeño de cada vendedor", "REPORTEVENDEDORES[]"),
    REPORTEPERDIDAS("CU8", "",
            "Pérdidas por motivo y por categoría", "REPORTEPERDIDAS[]"),
    REPORTEOFERTA("CU8", "",
            "Interés y conversiones por oferta", "REPORTEOFERTA[]");

    public final String cu;
    public final String[] parametros;
    public final String descripcion;
    public final String ejemplo;

    Comando(String cu, String parametros, String descripcion, String ejemplo) {
        this.cu = cu;
        this.parametros = parametros.isEmpty() ? new String[0] : parametros.split(",");
        this.descripcion = descripcion;
        this.ejemplo = ejemplo;
    }

    public int minimos() {
        int n = 0;
        for (String p : parametros) if (!p.endsWith("?")) n++;
        return n;
    }

    /** Forma de uso: INSERTARUSUARIO["nombre","apellido",...]; los opcionales van entre corchetes. */
    public String firma() {
        StringBuilder sb = new StringBuilder(name()).append('[');
        for (int i = 0; i < parametros.length; i++) {
            if (i > 0) sb.append(',');
            String p = parametros[i];
            sb.append(p.endsWith("?") ? "(\"" + p.substring(0, p.length() - 1) + "\")" : "\"" + p + "\"");
        }
        return sb.append(']').toString();
    }
}
