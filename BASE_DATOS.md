# Base de datos del CRM

P1 correo es la entrega actual. P2 web sigue en desarrollo: ambos proyectos usaran la misma base y los mismos registros. Aunque se comenzo desarrollando web, ahora se completa correo con los ocho casos de uso aprobados; despues se continuara web sobre esta base.

## Destino vigente

- PostgreSQL 18: `db.tecnoweb.org.bo:5432`.
- Base del Grupo 05 SA: **`db_grupo05sa`**.
- Esquema: **`public`**.
- JDBC: `currentSchema=public`.
- Los antiguos esquemas `crm` y `crm_compartido` estan retirados: no recrearlos.
- Las credenciales reales permanecen en `.secrets/credenciales.md` del workspace privado, fuera de GitHub.

Correo consulta directamente las tablas del CRM. No requiere ejecutar Laravel ni abrir la web. No crear otra base de produccion para correo.

## Claves de diseño y vigencia (17/11/2026)

Migraciones aplicadas en produccion, en orden (respaldo previo en `.secrets/respaldos/`):
`sql/migraciones/2026-11-17_claves_catalogo.sql`, `2026-11-17_claves_diseno.sql` y `2026-11-17_nombres_not_null.sql`.
Clase 46 del docente: ninguna tabla sin relacion, una sola clave por tabla con el nombre del diseño, **ningun campo `id`**.
Clase 03: toda credencial lleva fecha de inicio, fecha de fin y rol.

| Tipo de clave | Tablas |
|---|---|
| Natural | `usuarios(correo)`, `prospectos(documento)`, `descuentos(codigo)`, `motivos_perdida(nombre)`, `roles(nombre)`, `catalogo_cache(codigo)`, `configuraciones(clave)`, `api_tokens(token_hash)`, `programas_responsables(programa_codigo)` |
| La del dueño (1 a 1) | `pagos(nro_orden)`, `comisiones(nro_solicitud)`, `inscripciones_comerciales(nro_solicitud)` |
| Correlativo con nombre | `intereses.nro_tarjeta`, `ordenes_pago.nro_orden`, `solicitudes_compra.nro_solicitud`, `interacciones.nro_interaccion`, `movimientos_interes.nro_movimiento`, `recordatorios.nro_recordatorio`, `notificaciones.nro_notificacion`, `log_comandos.nro_registro`, `exportaciones.nro_exportacion`, `comprobantes_pago.nro_comprobante` |
| Compuesta (N-N con campos propios) | `orden_pago_descuentos(nro_orden, descuento_codigo)` con `porcentaje` y `monto` |

- Cada FK lleva el nombre de la clave a la que apunta o su papel: `vendedor_correo`, `coordinador_correo`, `usuario_correo`,
  `documento`, `nro_tarjeta`, `modulo_codigo`... Las que apuntan a usuarios, prospectos, motivos y descuentos son
  `ON UPDATE CASCADE`: cambiar un correo o el nombre de un motivo se propaga solo.
- `usuarios.fecha_inicio` y `usuarios.fecha_fin` (CHECK `fecha_fin >= fecha_inicio`): fuera de vigencia, todo comando se
  responde "Denegado".
- Resultado: 23 tablas de negocio y 30 claves foraneas; ninguna tabla aislada. `migrations` es la tabla de control de
  Laravel (P2): queda pendiente decidir con el P2 que hacer con ella antes de la defensa.
- **P2 web debe replicar estas claves** en sus migraciones y modelos (`$primaryKey`, `$incrementing`, `$keyType`).

## Estructura y referencias

`sql/esquema.sql` describe la estructura instalada, sin usuarios ni datos comerciales. `sql/referencias.sql` contiene roles, motivos, descuentos, porcentaje de comision y las 25 ofertas del catalogo simulado. Son archivos para preparar una base nueva de desarrollo o pruebas; no son una migracion para aplicar sobre produccion.

```sh
psql -X -v ON_ERROR_STOP=1 --single-transaction -d BASE_NUEVA -f sql/esquema.sql
psql -X -v ON_ERROR_STOP=1 --single-transaction -d BASE_NUEVA -f sql/referencias.sql
```

El esquema SQL rechaza una instalacion sobre un `public` que ya contiene tablas, vistas o secuencias. Las cuentas se crean aparte: estos archivos no incluyen contrasenas ni cuentas reales. Los entornos QA son temporales y se eliminan al finalizar.

## Catalogo y SIS

La API del Grupo 06 todavia no esta disponible. El catalogo es simulado. Correo prepara exportaciones CSV y permite registrar una constancia manual del resultado. La matricula academica automatica requiere el contrato real de su API.
