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
| Natural | `usuarios(correo)`, `prospectos(documento)`, `descuentos(codigo)`, `motivos_perdida(nombre)`, `roles(nombre)`, `programas(codigo)`, `catalogo_cache(codigo)`, `configuraciones(clave)`, `api_tokens(token_hash)`, `programas_responsables(programa_codigo)` |
| La del dueño (1 a 1) | `pagos(nro_orden)`, `comisiones(nro_solicitud)`, `inscripciones_comerciales(nro_solicitud)` |
| Correlativo con nombre | `intereses.nro_tarjeta`, `ordenes_pago.nro_orden`, `solicitudes_compra.nro_solicitud`, `interacciones.nro_interaccion`, `movimientos_interes.nro_movimiento`, `recordatorios.nro_recordatorio`, `notificaciones.nro_notificacion`, `log_comandos.nro_registro`, `exportaciones.nro_exportacion`, `comprobantes_pago.nro_comprobante` |
| Compuesta (N-N con campos propios) | `orden_pago_descuentos(nro_orden, descuento_codigo)` con `porcentaje` y `monto` |

- Cada FK lleva el nombre de la clave a la que apunta o su papel: `vendedor_correo`, `coordinador_correo`, `usuario_correo`,
  `documento`, `nro_tarjeta`, `modulo_codigo`... Las que apuntan a usuarios, prospectos, motivos y descuentos son
  `ON UPDATE CASCADE`: cambiar un correo o el nombre de un motivo se propaga solo.
- `usuarios.fecha_inicio` y `usuarios.fecha_fin` (CHECK `fecha_fin >= fecha_inicio`): fuera de vigencia, todo comando se
  responde "Denegado".
- Resultado al 17/11: 23 tablas y 30 claves foraneas; ninguna tabla aislada (con la normalización de abajo: 24 y 32). La tabla `migrations` de Laravel se elimino del
  servidor el 17/11 (respaldo en `.secrets/respaldos/public_20261117_233046.dump`): el P2 debe recrear su control de
  migraciones sin dejar una tabla suelta en `public` (por ejemplo, en otro esquema).
- **P2 web debe replicar estas claves** en sus migraciones y modelos (`$primaryKey`, `$incrementing`, `$keyType`).

## Normalización del catálogo (20/11/2026) — aplicar en el servidor antes de la defensa

En `catalogo_cache` (módulos), `programa_codigo → programa_nombre` era una dependencia transitiva (el nombre del
programa se repetía en cada uno de sus módulos: no está en 3FN). `sql/migraciones/2026-11-19_normalizar_catalogo.sql`
crea `programas(codigo, nombre)`, copia cada nombre una sola vez, agrega las FK `catalogo_cache.programa_codigo` y
`programas_responsables.programa_codigo` hacia `programas` y retira `catalogo_cache.programa_nombre`. Valida antes
de tocar nada (código o nombre vacío, un código con dos nombres, responsable sin programa) y no cambia los registros
comerciales. Reversión: `2026-11-19_normalizar_catalogo_revertir.sql` (exige volver al JAR anterior).

Resultado de esta migración: **24 tablas y 32 claves foráneas** (31 después de la siguiente), ninguna aislada. Los `programa_nombre` de `intereses` y
`solicitudes_compra` se conservan: son el nombre **al momento de la venta** (dato histórico de la transacción, como su
precio), no una copia del catálogo.

Orden para aplicarla (dejar **al menos 3,2 s entre cada conexión al servidor**):
1. Detener el lector (`Ctrl+C` en `ejecutar.ps1`).
2. `.\respaldo.ps1` y comprobar que el `.dump` existe.
3. `psql -X -v ON_ERROR_STOP=1 --single-transaction -f sql/migraciones/2026-11-19_normalizar_catalogo.sql`.
4. `.\ejecutar.ps1 -Diagnostico` y arrancar el lector.

**P2 web** debe leer el nombre del programa desde `programas` (JOIN por `programa_codigo`) en vez de
`catalogo_cache.programa_nombre` antes de volver a usarse sobre esta base.

## Columnas redundantes (20/11/2026) — aplicar después de la normalización

Clase 46: *"si hay un campo de más en una tabla ya está mal"*. Se revisó cada columna en P1 y en P2 (`crm-web`) y se
quitan solo las que repiten un dato que ya está en otra columna o que es constante.
`sql/migraciones/2026-11-20_quitar_redundancias.sql` antes de borrar comprueba con los datos que coinciden; si una fila
difiere, aborta sin tocar nada.

| Columna quitada | Por qué sobraba | Dónde queda el dato |
|---|---|---|
| `ordenes_pago.estado` | P1 y P2 la escribían siempre igual a `pagos.estado` (pago 1 a 1 con su orden) | `pagos.estado` |
| `pagos.monto` | Igual a `ordenes_pago.total` | `ordenes_pago.total` |
| `pagos.fecha_generacion` | Igual a `ordenes_pago.fecha_generacion` | `ordenes_pago.fecha_generacion` |
| `inscripciones_comerciales.estado` | Su CHECK solo admitía `'Confirmada'`: constante | La fila existe = confirmada |
| `exportaciones.documento` (y su FK) | Dependencia transitiva: sale de `nro_solicitud` | `solicitudes_compra → ordenes_pago → intereses.documento` |
| `solicitudes_compra.estado` | `'confirmada'` si y solo si existe su inscripción | `EXISTS` en `inscripciones_comerciales` |

Se conservan, revisadas: `prospectos.estado` (la web lo edita), `fecha`/`fecha_registro` frente a `created_at` (fecha del
negocio frente al sello de Laravel), `catalogo_cache.sincronizado_en` (fecha de la réplica del catálogo del SIS) y las
copias de nombre y precio en `intereses` y `solicitudes_compra` (datos históricos de cada operación).

Resultado con ambas migraciones: **24 tablas y 31 FK**, ninguna aislada. **P2 web** debe leer el estado del pago desde
`pagos`, el total desde `ordenes_pago` y dejar de escribir las columnas quitadas.

**Estado del servidor:** el 19/11/2026 22:50 se aplicaron allí `2026-11-19_bandeja_correo.sql` y
`2026-11-19_normalizar_catalogo.sql` (25 tablas, 34 FK; respaldo previo en `.secrets/respaldos/`). El lector actual no usa la
bandeja: `2026-11-20_retirar_bandeja.sql` quita `correos_salientes` y las 4 columnas agregadas a `log_comandos` (conserva
todas las filas de la bitácora; se rechaza si hay un correo guardado sin procesar o una respuesta pendiente de envío).

Orden en el servidor, con el lector detenido, un respaldo comprobado y **al menos 3,2 s entre cada conexión**:
`.\respaldo.ps1` → `2026-11-20_retirar_bandeja.sql` → `2026-11-20_quitar_redundancias.sql` → `.\ejecutar.ps1 -Diagnostico`
(la de catálogo **no** se repite: ya está aplicada y se rechaza sola si se vuelve a correr).
Cada una con `psql -X -v ON_ERROR_STOP=1 --single-transaction -f ARCHIVO`. Ensayo local sobre el respaldo real del 19/11:
estructura final idéntica a `sql/esquema.sql`, ninguna fila perdida y el JAR respondiendo sobre esos datos.

## Primer administrador de una base nueva

`sql/referencias.sql` no crea usuarios. El primero se inserta a mano (después, los demás con `INSERTARUSUARIO`):

```sql
INSERT INTO usuarios(correo, nombre, apellido, telefono, password, rol, estado, fecha_inicio, fecha_fin, created_at, updated_at)
VALUES ('admin@ejemplo.com', 'Nombre', 'Apellido', '70000000', '(clave web, la asigna P2)', 'administrador', 'activo',
        CURRENT_DATE, CURRENT_DATE + INTERVAL '1 year', now(), now());
```

## Estructura y referencias

`sql/esquema.sql` describe la estructura instalada, sin usuarios ni datos comerciales. `sql/referencias.sql` contiene roles, motivos, descuentos, porcentaje de comision, los 5 programas y sus 25 modulos del catalogo simulado. Son archivos para preparar una base nueva de desarrollo o pruebas; no son una migracion para aplicar sobre produccion.

```sh
psql -X -v ON_ERROR_STOP=1 --single-transaction -d BASE_NUEVA -f sql/esquema.sql
psql -X -v ON_ERROR_STOP=1 --single-transaction -d BASE_NUEVA -f sql/referencias.sql
```

El esquema SQL rechaza una instalacion sobre un `public` que ya contiene tablas, vistas o secuencias. Las cuentas se crean aparte: estos archivos no incluyen contrasenas ni cuentas reales. Los entornos QA son temporales y se eliminan al finalizar.

## Catalogo y SIS

La API del Grupo 06 todavia no esta disponible. El catalogo es simulado. Correo prepara exportaciones CSV y permite registrar una constancia manual del resultado. La matricula academica automatica requiere el contrato real de su API.
