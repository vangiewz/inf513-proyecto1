# Operacion por correo de los ocho casos de uso

Destino: `grupo05sa@tecnoweb.org.bo`. El comando y sus parametros van en el **asunto**.
Los parametros se escriben entre comillas y se separan con comas. `HELP[]` muestra
solo los comandos del rol del remitente; `HELP["GENERARORDEN"]` explica uno.
La base de despliegue es **`db_grupo05sa`, esquema `public`**. No usar ni recrear
`crm` o `crm_compartido`. El detalle vigente esta en [BASE_DATOS.md](BASE_DATOS.md).

## Cobertura funcional

| Caso aprobado | Operaciones disponibles por correo | Permisos |
|---|---|---|
| CU1 Usuarios | LISTARUSUARIOS, INSERTARUSUARIO, MODIFICARUSUARIO, ELIMINARUSUARIO | Administrador; coordinador lista vendedores |
| CU2 Captacion y oferta | LISTAROFERTA, REGISTRARINTERES, REGISTRARPROSPECTO, IMPORTARPROSPECTOS, LISTARPROSPECTOS, MODIFICARPROSPECTO, ELIMINARPROSPECTO, ASIGNARCOORDINADOR | Visitante consulta y se registra; personal segun su alcance; administrador asigna responsables |
| CU3 Asignacion | LISTARBOLSA, ASIGNARPROSPECTO, ASIGNARAUTOMATICO | Administrador y coordinador responsable; vendedor no se autoasigna |
| CU4 Tablero | VERTABLERO, MOVERTARJETA | Personal dentro de su alcance |
| CU5 Perdidas | LISTARMOTIVOS, INSERTARMOTIVO, MODIFICARMOTIVO, ELIMINARMOTIVO, MARCARPERDIDO | Administracion/coordinacion gestionan motivos; vendedor registra perdidas de su cartera |
| CU6 Conversion | VALIDARCONVERSION, GENERARORDEN, REGISTRARCOMPROBANTE, VERCOMPROBANTE, APROBARPAGO, RECHAZARPAGO, CONVERTIRPROSPECTO, LISTARMATRICULAS | Vendedor opera su cartera; aprobacion/rechazo solo administracion o coordinador del programa |
| CU7 Seguimiento y bitacora | REGISTRARINTERACCION, PROGRAMARRECORDATORIO, LISTARRECORDATORIOS, COMPLETARRECORDATORIO, VERHISTORIAL, VERBITACORA | Alcance por cartera/programa; bitacora general solo administrador |
| CU8 Reportes | REPORTEEMBUDO, REPORTEVENDEDORES, REPORTEPERDIDAS, REPORTEOFERTA | Administrador global, coordinador por programa; vendedor consulta su embudo |

Adicionalmente: LISTARORDENES, VERORDEN, LISTARPAGOS, LISTARDESCUENTOS,
EXPORTARMATRICULAS, LISTAREXPORTACIONES, VEREXPORTACION y REGISTRARRESULTADOSIS.

## Preparacion del administrador

1. Registrar al personal con su correo real y su rol. La identidad de P1 es el
   correo registrado; el sistema no crea una contrasena web comun.
2. Consultar `LISTAROFERTA[]`: muestra los codigos reales de modulo y programa.
3. Asignar cada programa a su coordinador:

```text
ASIGNARCOORDINADOR["desarrollo-web","id_coordinador"]
```

Un coordinador sin programas asignados no tiene acceso global. Si cambia el responsable,
el anterior pierde acceso a prospectos, historial, pagos y reportes del programa.
El administrador conserva la vista global. Esta responsabilidad se guarda en
`programas_responsables`, dentro del mismo esquema `public`.

## Recorrido de una venta sin abrir web

Las personas e IDs siguientes son ejemplos. Sustituir los IDs por los devueltos por el sistema.

Visitante (correo aun no registrado como personal):

```text
LISTAROFERTA[]
REGISTRARINTERES["desarrollo-web-m1","4715292","Juan","Perez","70000001","Ingenieria","Santa Cruz"]
```

Para interes por programa completo, usar `desarrollo-web` en lugar del codigo de modulo.
Se crea una tarjeta por cada modulo que realmente tenga ese programa en el catalogo.
El personal tambien puede registrar con:

```text
REGISTRARPROSPECTO["4715292","Juan","Perez","70000001","juan@example.com","Correo","desarrollo-web-m1","Ingenieria","Santa Cruz"]
```

Coordinador o administrador:

```text
LISTARBOLSA[]
ASIGNARPROSPECTO["id_tarjeta","id_vendedor"]
```

La alternativa `ASIGNARAUTOMATICO[]` distribuye por menor carga y conserva juntos
los intereses de un prospecto. El coordinador solo distribuye los programas a su cargo.

Vendedor:

```text
VERTABLERO[]
MOVERTARJETA["id_tarjeta","En Contacto"]
REGISTRARINTERACCION["id_tarjeta","WhatsApp","Se explico el contenido y el precio"]
MOVERTARJETA["id_tarjeta","Seguimiento"]
VALIDARCONVERSION["id_tarjeta"]
```

Si faltan profesion o ubicacion, el vendedor puede completar la ficha por correo:

```text
MODIFICARPROSPECTO["4715292","Juan","Perez","70000001","juan@example.com","Correo","Ingenieria","Santa Cruz"]
```

Generar la orden del modulo de la tarjeta:

```text
GENERARORDEN["id_tarjeta"]
```

Para vender varios modulos del mismo programa se separan sus codigos con punto y coma.
La orden debe incluir el modulo de la tarjeta elegida. Los descuentos son opcionales:

```text
LISTARDESCUENTOS[]
GENERARORDEN["id_tarjeta","desarrollo-web-m1;desarrollo-web-m2","codigo_descuento"]
VERORDEN["id_orden"]
```

La respuesta contiene el ID de pago, el total y una **referencia demostrativa**.
No se ejecuta un cobro bancario ni se fabrica un comprobante.

Vendedor: adjuntar un unico archivo PNG, JPEG, GIF, WebP o PDF, de hasta 5 MB:

```text
REGISTRARCOMPROBANTE["id_pago","Comprobante recibido del interesado"]
```

El archivo viaja como adjunto real, nunca como una ruta local o una cadena base64 en el asunto.
El tipo se comprueba por su contenido. El mensaje completo admite hasta 8 MB para dar
cabida a la codificacion MIME del adjunto.

Coordinador responsable:

```text
LISTARPAGOS["En revision"]
VERCOMPROBANTE["id_pago"]
```

`VERCOMPROBANTE` devuelve el archivo adjunto y el historial de comprobantes. Para
revisar uno anterior se puede indicar su ID como segundo parametro. Luego:

```text
RECHAZARPAGO["id_pago","El comprobante no es legible"]
```

El vendedor puede enviar otro comprobante; el anterior y el motivo de rechazo
permanecen en el historial. Cuando el comprobante sea correcto:

```text
APROBARPAGO["id_pago","Datos y comprobante revisados"]
CONVERTIRPROSPECTO["id_tarjeta"]
LISTARMATRICULAS[]
```

La aprobacion exige ficha completa, vendedor asignado y comprobante en revision.
Confirma la inscripcion comercial de cada modulo y registra las comisiones con el
porcentaje configurado. Vender un programa completo convierte sus tarjetas vinculadas;
antes deben estar asignadas y activas. La aprobacion repetida se rechaza y no duplica
matriculas ni comisiones. `CONVERTIRPROSPECTO` comprueba el resultado de forma idempotente.

## Perdida, agenda e indicadores

```text
LISTARMOTIVOS[]
MARCARPERDIDO["id_tarjeta","id_motivo","Detalle de la perdida"]
PROGRAMARRECORDATORIO["id_tarjeta","2090-01-10 10:00","Confirmar decision"]
LISTARRECORDATORIOS[]
COMPLETARRECORDATORIO["id_recordatorio","Se contacto al interesado"]
VERHISTORIAL["id_tarjeta"]
REPORTEEMBUDO[]
```

Las fechas de entrada se interpretan en Bolivia y se almacenan en UTC. El resultado
de una actividad es obligatorio. El tablero y el reporte de correo muestran las
seis etapas aprobadas: Nuevo, En Contacto, Seguimiento, Promesa de Pago, Convertido y
Perdido. La base compartida conserva el detalle financiero de web: Pago enviado y
En revision se muestran dentro de Promesa de Pago.

## Intercambio con el SIS del Grupo 06

El grupo aun no proporciono su API (confirmado por el usuario). El catalogo se
identifica como simulado. La preparacion puede hacerse por correo:

```text
EXPORTARMATRICULAS["id_inscripcion"]
LISTAREXPORTACIONES[]
VEREXPORTACION["id_exportacion"]
```

Se devuelve un CSV y se registra estado `preparada`. Recuperar el archivo no crea
otra exportacion. Una recepcion real no se presume. Si el responsable obtiene una
constancia por un medio externo, puede registrarla explicitamente:

```text
REGISTRARRESULTADOSIS["id_exportacion","recibida","Constancia real facilitada por el SIS"]
```

Estados admitidos: preparada, exportada, recibida y error. La constancia es manual;
la conexion automatica al SIS requiere su URL, contrato y autenticacion reales.

## Pruebas y documentos

Pruebas reproducibles y preparacion del entorno: [PRUEBAS.md](PRUEBAS.md).
Solo se crean datos ficticios en bases/esquemas QA.
El resumen esta en `doc/pruebas/base-compartida.json` y la matriz de permisos se genera
con `java -jar target/crm-mail.jar --anexo`.

Esta guia operativa no reemplaza el PUDS ni sus diagramas EA, que deben reflejar las
clases, comandos y tablas actuales antes de entregar el documento academico.
