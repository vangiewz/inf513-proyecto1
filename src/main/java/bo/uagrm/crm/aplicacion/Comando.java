package bo.uagrm.crm.aplicacion;

/**
 * Catálogo de comandos: caso de uso, parámetros, descripción y ejemplo. Qué rol puede usar cada
 * comando lo define su RolCommandProcessor (matriz de acceso). Un parámetro que termina en "?" es
 * opcional. HELP[] sale de aquí.
 */
public enum Comando {
  HELP(
      "General",
      "comando?",
      "Lista los comandos de su rol o explica uno",
      "HELP[\"INSERTARUSUARIO\"]"),

  LISTARUSUARIOS(
      "CU1", "rol?", "Lista los usuarios, opcionalmente de un rol", "LISTARUSUARIOS[\"vendedor\"]"),
  INSERTARUSUARIO(
      "CU1",
      "nombre,apellido,correo,telefono,rol,fecha_inicio?,fecha_fin?",
      "Registra un usuario (administrador, coordinador o vendedor) con la vigencia de su credencial"
          + " (por omisión, un año desde hoy)",
      "INSERTARUSUARIO[\"Ana\",\"Rojas"
          + " Vaca\",\"ana.rojas@example.com\",\"71234567\",\"vendedor\",\"2026-11-18\",\"2027-06-30\"]"),
  MODIFICARUSUARIO(
      "CU1",
      "correo,nombre,apellido,telefono,rol,estado,fecha_inicio?,fecha_fin?,correo_nuevo?",
      "Modifica los datos, el rol, el estado (activo/inactivo), la vigencia o el correo de un usuario",
      "MODIFICARUSUARIO[\"ana.rojas@example.com\",\"Ana\",\"Rojas"
          + " Vaca\",\"71234567\",\"coordinador\",\"activo\",\"2026-11-18\",\"2027-12-31\"]"),
  ELIMINARUSUARIO(
      "CU1",
      "correo",
      "Da de baja a un usuario y devuelve sus prospectos abiertos a la bolsa común",
      "ELIMINARUSUARIO[\"ana.rojas@example.com\"]"),

  LISTAROFERTA(
      "CU2",
      "",
      "Lista el catalogo simulado del CRM; API SIS pendiente",
      "LISTAROFERTA[]"),
  REGISTRARINTERES(
      "CU2",
      "codigo_oferta,documento,nombre,apellido,telefono,profesion?,ubicacion?",
      "Registra su interés en una oferta; un asesor le contactará",
      "REGISTRARINTERES[\"desarrollo-web\",\"4715292\",\"Juan Carlos\",\"Perez"
          + " Seras\",\"71055123\"]"),
  REGISTRARPROSPECTO(
      "CU2",
      "documento,nombre,apellido,telefono,correo,origen,codigo_oferta,profesion?,ubicacion?",
      "Registra un prospecto con su interés (origen: Facebook, Instagram, TikTok, WhatsApp,"
          + " Referido, Web, Correo, Feria u Otro)",
      "REGISTRARPROSPECTO[\"4715292\",\"Juan Carlos\",\"Perez"
          + " Seras\",\"71055123\",\"juanperez@uagrm.edu.bo\",\"Facebook\",\"desarrollo-web\"]"),
  IMPORTARPROSPECTOS(
      "CU2",
      "",
      "Importa prospectos del archivo .csv adjunto (columnas:"
          + " documento,nombre,apellido,telefono,correo,origen,codigo_oferta)",
      "IMPORTARPROSPECTOS[] con prospectos.csv adjunto"),
  LISTARPROSPECTOS(
      "CU2",
      "texto?",
      "Lista los prospectos que puede ver; opcional: CI o parte del nombre",
      "LISTARPROSPECTOS[\"Perez\"]"),
  MODIFICARPROSPECTO(
      "CU2",
      "documento,nombre,apellido,telefono,correo,origen,profesion?,ubicacion?",
      "Modifica los datos de un prospecto",
      "MODIFICARPROSPECTO[\"4715292\",\"Juan Carlos\",\"Perez"
          + " Seras\",\"71055123\",\"juan.perez@example.com\",\"Facebook\"]"),
  ELIMINARPROSPECTO(
      "CU2",
      "documento",
      "Elimina un prospecto solo si no tiene intereses ni historial",
      "ELIMINARPROSPECTO[\"4715292\"]"),
  ASIGNARCOORDINADOR(
      "CU2",
      "codigo_programa,correo_coordinador",
      "Pone un programa y sus módulos a cargo de un coordinador",
      "ASIGNARCOORDINADOR[\"desarrollo-web\",\"coordinador@example.com\"]"),

  LISTARBOLSA("CU3", "", "Lista los prospectos sin vendedor (bolsa común)", "LISTARBOLSA[]"),
  ASIGNARPROSPECTO(
      "CU3",
      "nro_tarjeta,correo_vendedor",
      "Asigna un prospecto a un vendedor",
      "ASIGNARPROSPECTO[\"7\",\"vendedor@example.com\"]"),
  ASIGNARAUTOMATICO(
      "CU3",
      "",
      "Distribuye por menor carga y mantiene juntos los intereses de cada prospecto",
      "ASIGNARAUTOMATICO[]"),

  VERTABLERO("CU4", "", "Muestra el tablero Kanban del embudo de ventas", "VERTABLERO[]"),
  MOVERTARJETA(
      "CU4",
      "nro_tarjeta,estado",
      "Mueve a Nuevo, Contactado o Interesado; el pago determina las etapas financieras",
      "MOVERTARJETA[\"7\",\"En Contacto\"]"),

  LISTARMOTIVOS("CU5", "", "Lista los motivos de pérdida y su categoría", "LISTARMOTIVOS[]"),
  INSERTARMOTIVO(
      "CU5",
      "nombre,categoria",
      "Registra un motivo (categoría: Económico, Horario, Incompatibilidad, Contacto u Otro)",
      "INSERTARMOTIVO[\"Prefiere clases presenciales\",\"Incompatibilidad\"]"),
  MODIFICARMOTIVO(
      "CU5",
      "motivo,nombre_nuevo,categoria",
      "Cambia el nombre de un motivo o su categoría",
      "MODIFICARMOTIVO[\"Prefiere clases presenciales\",\"Prefiere modalidad presencial\",\"Incompatibilidad\"]"),
  ELIMINARMOTIVO(
      "CU5",
      "motivo",
      "Elimina un motivo que no se haya usado",
      "ELIMINARMOTIVO[\"Prefiere modalidad presencial\"]"),
  MARCARPERDIDO(
      "CU5",
      "nro_tarjeta,motivo,detalle?",
      "Marca una tarjeta como Perdido; el motivo (su nombre, ver LISTARMOTIVOS) es obligatorio",
      "MARCARPERDIDO[\"7\",\"Horario incompatible con su trabajo\",\"Trabaja por las noches\"]"),

  VALIDARCONVERSION(
      "CU6",
      "nro_tarjeta",
      "Revisa si los datos están completos para la matrícula",
      "VALIDARCONVERSION[\"7\"]"),
  CONVERTIRPROSPECTO(
      "CU6",
      "nro_tarjeta",
      "Comprueba la inscripcion confirmada mediante pago aprobado por APROBARPAGO",
      "CONVERTIRPROSPECTO[\"7\"]"),
  LISTARMATRICULAS(
      "CU6",
      "",
      "Lista inscripciones comerciales confirmadas; el envio al SIS es independiente",
      "LISTARMATRICULAS[]"),

  GENERARORDEN(
      "CU6",
      "nro_tarjeta,codigos_modulos?,codigos_descuentos?",
      "Genera una orden y referencia de pago demostrativa; codigos multiples separados por punto y"
          + " coma",
      "GENERARORDEN[\"7\",\"desarrollo-web-m1;desarrollo-web-m2\"]"),
  LISTARORDENES(
      "CU6", "estado_pago?", "Lista las ordenes y pagos de su alcance", "LISTARORDENES[]"),
  VERORDEN("CU6", "nro_orden", "Consulta la orden y sus modulos", "VERORDEN[\"1\"]"),
  LISTARDESCUENTOS("CU6", "", "Lista los descuentos activos para una orden", "LISTARDESCUENTOS[]"),
  LISTARPAGOS(
      "CU6", "estado?", "Consulta pagos dentro de su alcance", "LISTARPAGOS[\"En revision\"]"),
  REGISTRARCOMPROBANTE(
      "CU6",
      "nro_orden,observacion?",
      "Adjunta un unico comprobante PNG, JPEG, GIF, WebP o PDF (hasta 5 MB)",
      "REGISTRARCOMPROBANTE[\"1\",\"Deposito realizado\"] con recibo.pdf adjunto"),
  VERCOMPROBANTE(
      "CU6",
      "nro_orden,nro_comprobante?",
      "Devuelve el comprobante adjunto y el historial de revisiones",
      "VERCOMPROBANTE[\"1\"]"),
  APROBARPAGO(
      "CU6",
      "nro_orden,observacion?",
      "Aprueba el pago, valida la ficha y confirma las inscripciones y comisiones",
      "APROBARPAGO[\"1\",\"Comprobante verificado\"]"),
  RECHAZARPAGO(
      "CU6",
      "nro_orden,motivo",
      "Rechaza un comprobante para que el vendedor lo corrija",
      "RECHAZARPAGO[\"1\",\"El monto no coincide\"]"),
  EXPORTARMATRICULAS(
      "CU6",
      "nro_solicitud?",
      "Prepara un CSV adjunto para el SIS; la API del Grupo 06 aun no esta disponible",
      "EXPORTARMATRICULAS[]"),
  LISTAREXPORTACIONES(
      "CU6",
      "",
      "Consulta las preparaciones y constancias del intercambio academico",
      "LISTAREXPORTACIONES[]"),
  VEREXPORTACION(
      "CU6",
      "nro_exportacion",
      "Recupera el CSV de una preparacion sin duplicarla",
      "VEREXPORTACION[\"1\"]"),
  REGISTRARRESULTADOSIS(
      "CU6",
      "nro_exportacion,estado,constancia",
      "Registra una constancia manual: preparada, exportada, recibida o error",
      "REGISTRARRESULTADOSIS[\"1\",\"recibida\",\"Confirmacion facilitada por el SIS\"]"),

  REGISTRARINTERACCION(
      "CU7",
      "nro_tarjeta,tipo,descripcion",
      "Registra una interacción o nota (tipo: Llamada, WhatsApp, Correo, Reunión o Nota)",
      "REGISTRARINTERACCION[\"7\",\"WhatsApp\",\"Se envió el PDF del diplomado\"]"),
  PROGRAMARRECORDATORIO(
      "CU7",
      "nro_tarjeta,fecha_programada,descripcion",
      "Programa un recordatorio (AAAA-MM-DD HH:MM); llega por correo a esa hora",
      "PROGRAMARRECORDATORIO[\"7\",\"2026-10-15 10:00\",\"Llamar para confirmar el pago\"]"),
  LISTARRECORDATORIOS("CU7", "", "Lista sus recordatorios pendientes", "LISTARRECORDATORIOS[]"),
  COMPLETARRECORDATORIO(
      "CU7",
      "nro_recordatorio,resultado",
      "Marca un recordatorio como realizado",
      "COMPLETARRECORDATORIO[\"5\",\"Se contacto al prospecto\"]"),
  VERHISTORIAL(
      "CU7",
      "nro_tarjeta",
      "Bitácora cronológica de una tarjeta: movimientos, interacciones y recordatorios",
      "VERHISTORIAL[\"7\"]"),
  VERBITACORA(
      "CU7",
      "cantidad?",
      "Bitácora del sistema: últimos correos procesados, incluidos los rechazados por rol",
      "VERBITACORA[\"20\"]"),

  REPORTEEMBUDO("CU8", "", "Tarjetas por etapa del embudo y tasa de conversión", "REPORTEEMBUDO[]"),
  REPORTEVENDEDORES("CU8", "", "Desempeño de cada vendedor", "REPORTEVENDEDORES[]"),
  REPORTEPERDIDAS(
      "CU8", "",
      "Pérdidas por motivo y por categoría", "REPORTEPERDIDAS[]"),
  REPORTEOFERTA("CU8", "", "Interés y conversiones por oferta", "REPORTEOFERTA[]");

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
