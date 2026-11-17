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
      "nombre,apellido,correo,telefono,rol",
      "Registra un usuario (administrador, coordinador o vendedor)",
      "INSERTARUSUARIO[\"Ana\",\"Rojas"
          + " Vaca\",\"ana.rojas@example.com\",\"71234567\",\"vendedor\"]"),
  MODIFICARUSUARIO(
      "CU1",
      "id,nombre,apellido,correo,telefono,rol,estado",
      "Modifica los datos, el rol o el estado (activo/inactivo) de un usuario",
      "MODIFICARUSUARIO[\"4\",\"Ana\",\"Rojas"
          + " Vaca\",\"ana.rojas@example.com\",\"71234567\",\"coordinador\",\"activo\"]"),
  ELIMINARUSUARIO(
      "CU1",
      "id",
      "Da de baja a un usuario y devuelve sus prospectos abiertos a la bolsa común",
      "ELIMINARUSUARIO[\"4\"]"),

  LISTAROFERTA(
      "CU2",
      "",
      "Lista el catalogo simulado compartido con web; API SIS pendiente",
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
      "codigo_programa,id_coordinador",
      "Pone un programa y sus módulos a cargo de un coordinador",
      "ASIGNARCOORDINADOR[\"desarrollo-web\",\"2\"]"),

  LISTARBOLSA("CU3", "", "Lista los prospectos sin vendedor (bolsa común)", "LISTARBOLSA[]"),
  ASIGNARPROSPECTO(
      "CU3",
      "id_tarjeta,id_vendedor",
      "Asigna un prospecto a un vendedor",
      "ASIGNARPROSPECTO[\"7\",\"3\"]"),
  ASIGNARAUTOMATICO(
      "CU3",
      "",
      "Distribuye por menor carga y mantiene juntos los intereses de cada prospecto",
      "ASIGNARAUTOMATICO[]"),
  TOMARPROSPECTO(
      "CU3",
      "id_tarjeta",
      "Toma un prospecto de la bolsa común para su cartera",
      "TOMARPROSPECTO[\"7\"]"),

  VERTABLERO("CU4", "", "Muestra el tablero Kanban del embudo de ventas", "VERTABLERO[]"),
  MOVERTARJETA(
      "CU4",
      "id_tarjeta,estado",
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
      "id,nombre,categoria",
      "Modifica un motivo o su categoría",
      "MODIFICARMOTIVO[\"10\",\"Prefiere modalidad presencial\",\"Incompatibilidad\"]"),
  ELIMINARMOTIVO("CU5", "id", "Elimina un motivo que no se haya usado", "ELIMINARMOTIVO[\"10\"]"),
  MARCARPERDIDO(
      "CU5",
      "id_tarjeta,id_motivo,detalle?",
      "Marca una tarjeta como Perdido; el motivo es obligatorio",
      "MARCARPERDIDO[\"7\",\"3\",\"Trabaja por las noches\"]"),

  VALIDARCONVERSION(
      "CU6",
      "id_tarjeta",
      "Revisa si los datos están completos para la matrícula",
      "VALIDARCONVERSION[\"7\"]"),
  CONVERTIRPROSPECTO(
      "CU6",
      "id_tarjeta",
      "Comprueba la inscripcion confirmada mediante pago aprobado por APROBARPAGO",
      "CONVERTIRPROSPECTO[\"7\"]"),
  LISTARMATRICULAS(
      "CU6",
      "",
      "Lista inscripciones comerciales confirmadas; el envio al SIS es independiente",
      "LISTARMATRICULAS[]"),

  GENERARORDEN(
      "CU6",
      "id_tarjeta,codigos_modulos?,codigos_descuentos?",
      "Genera una orden y referencia de pago demostrativa; codigos multiples separados por punto y"
          + " coma",
      "GENERARORDEN[\"7\",\"desarrollo-web-m1;desarrollo-web-m2\"]"),
  LISTARORDENES(
      "CU6", "estado_pago?", "Lista las ordenes y pagos de su alcance", "LISTARORDENES[]"),
  VERORDEN("CU6", "id_orden", "Consulta la orden y sus modulos", "VERORDEN[\"1\"]"),
  LISTARDESCUENTOS("CU6", "", "Lista los descuentos activos para una orden", "LISTARDESCUENTOS[]"),
  LISTARPAGOS(
      "CU6", "estado?", "Consulta pagos dentro de su alcance", "LISTARPAGOS[\"En revision\"]"),
  REGISTRARCOMPROBANTE(
      "CU6",
      "id_pago,observacion?",
      "Adjunta un unico comprobante PNG, JPEG, GIF, WebP o PDF (hasta 5 MB)",
      "REGISTRARCOMPROBANTE[\"1\",\"Deposito realizado\"] con recibo.pdf adjunto"),
  VERCOMPROBANTE(
      "CU6",
      "id_pago,id_comprobante?",
      "Devuelve el comprobante adjunto y el historial de revisiones",
      "VERCOMPROBANTE[\"1\"]"),
  APROBARPAGO(
      "CU6",
      "id_pago,observacion?",
      "Aprueba el pago, valida la ficha y confirma las inscripciones y comisiones",
      "APROBARPAGO[\"1\",\"Comprobante verificado\"]"),
  RECHAZARPAGO(
      "CU6",
      "id_pago,motivo",
      "Rechaza un comprobante para que el vendedor lo corrija",
      "RECHAZARPAGO[\"1\",\"El monto no coincide\"]"),
  EXPORTARMATRICULAS(
      "CU6",
      "id_inscripcion?",
      "Prepara un CSV adjunto para el SIS; la API del Grupo 06 aun no esta disponible",
      "EXPORTARMATRICULAS[]"),
  LISTAREXPORTACIONES(
      "CU6",
      "",
      "Consulta las preparaciones y constancias del intercambio academico",
      "LISTAREXPORTACIONES[]"),
  VEREXPORTACION(
      "CU6",
      "id_exportacion",
      "Recupera el CSV de una preparacion sin duplicarla",
      "VEREXPORTACION[\"1\"]"),
  REGISTRARRESULTADOSIS(
      "CU6",
      "id_exportacion,estado,constancia",
      "Registra una constancia manual: preparada, exportada, recibida o error",
      "REGISTRARRESULTADOSIS[\"1\",\"recibida\",\"Confirmacion facilitada por el SIS\"]"),

  REGISTRARINTERACCION(
      "CU7",
      "id_tarjeta,tipo,descripcion",
      "Registra una interacción o nota (tipo: Llamada, WhatsApp, Correo, Reunión o Nota)",
      "REGISTRARINTERACCION[\"7\",\"WhatsApp\",\"Se envió el PDF del diplomado\"]"),
  PROGRAMARRECORDATORIO(
      "CU7",
      "id_tarjeta,fecha_programada,descripcion",
      "Programa un recordatorio (AAAA-MM-DD HH:MM); llega por correo a esa hora",
      "PROGRAMARRECORDATORIO[\"7\",\"2026-10-15 10:00\",\"Llamar para confirmar el pago\"]"),
  LISTARRECORDATORIOS("CU7", "", "Lista sus recordatorios pendientes", "LISTARRECORDATORIOS[]"),
  COMPLETARRECORDATORIO(
      "CU7",
      "id_recordatorio,resultado",
      "Marca un recordatorio como realizado",
      "COMPLETARRECORDATORIO[\"5\",\"Se contacto al prospecto\"]"),
  VERHISTORIAL(
      "CU7",
      "id_tarjeta",
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
  REPORTEOFERTA("CU8", "", "Interés y conversiones por oferta", "REPORTEOFERTA[]"),
  NUEVOPRUEBA("CU9", "texto", "Nueva prueba", "NUEVOPRUEBA[texto]");

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
