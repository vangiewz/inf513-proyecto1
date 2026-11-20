# Base de datos del CRM

## Revisión final local con contrato docente

La estructura actual de instalación incorpora **26 tablas, 216 columnas y 34 FK**. Se conservan las
25 tablas CRM y se agrega `public.persona` con siete campos independientes: documento (PK), nombres,
apellidos, ocupación, teléfono fijo, teléfono celular y correo. No se inventan vínculos con usuarios
o prospectos. Esta excepción al criterio histórico de tablas relacionadas responde al contrato docente
de LISPER/INSPER y no elimina ninguna relación CRM.

La nueva migración es `sql/migraciones/2026-11-19_persona.sql`. Es incremental y repetible si la
estructura existente coincide con el contrato; compara columnas, PK, expresiones CHECK e índice
UNIQUE global de lower(correo). Rechaza estructuras debilitadas y versiones futuras. Su versión se
conserva como `schema.persona.version=1` en configuraciones, sin otra tabla de control aislada.
El instalador para base nueva incluye la misma versión. Los siete campos son NOT NULL; no se
insertan personas ficticias como referencias de producción.

| Parámetro INSPER / columna | Tipo SQL | Validación del contrato docente |
|---|---|---|
| 1. `documento` | varchar(15), PK | CI de 5–10 dígitos, con complemento opcional de hasta tres caracteres alfanuméricos |
| 2. `nombres` | varchar(120) | Texto no vacío, sin caracteres de control |
| 3. `apellidos` | varchar(120) | Texto no vacío, sin caracteres de control |
| 4. `ocupacion` | varchar(80) | Texto no vacío, sin caracteres de control |
| 5. `telefono_fijo` | varchar(16) | 7–15 dígitos; `+` inicial opcional, sin espacios ni guiones internos |
| 6. `telefono_celular` | varchar(16) | Mismo formato del teléfono fijo |
| 7. `correo` | varchar(120) | Formato de correo válido y unicidad global de `lower(correo)` |

Los delimitadores de LISPER/INSPER son comillas dobles ASCII; LISPER exige exactamente `"*"`.
La inserción no crea cuentas ni permisos. Ambos comandos requieren administrador activo, vigente
y autenticado por el canal. La tolerancia histórica del teléfono CRM no se aplica a los campos docentes.

Para inspeccionar una base existente está preparado `sql/diagnostico_estructura.sql`, de sólo lectura.
Compara nombres, tipos, nulabilidad, restricciones e índices; no se deduce el estado por contar tablas.
Orden previsto: diagnóstico y respaldo recuperable, bandeja, catálogo, persona y diagnóstico posterior.
Las tres migraciones siguen pendientes de intervención institucional autorizada.

El marcador `schema.persona.version` complementa la verificación de estructura, no la sustituye.
Una vez confirmada la existencia de configuraciones, se puede consultar su valor con
`SELECT clave,valor FROM public.configuraciones WHERE clave='schema.persona.version';`.
La migración no sobrescribe versiones distintas de 1 y compara las expresiones CHECK reales con
un contrato temporal interpretado por PostgreSQL; un nombre correcto con CHECK debilitado no basta.

En la versión final, `log_comandos.message_id` conserva una clave SHA-256 del contenido y las
cabeceras esenciales y de firma; no confía únicamente en el Message-ID suministrado por el remitente.
El Message-ID de protocolo se obtiene por separado para correlacionar respuestas y conversaciones.

La reversión de bandeja ahora rechaza estructuras con datos que pudieran perderse, incluidas salidas
ya enviadas. La recuperación debe conservar los registros y volver al JAR compatible. No retirar
persona para revertir una aplicación. Procedimiento completo: [OPERACION.md](OPERACION.md).

`scripts/probar_local.py` verifica migraciones y ejecuta las integraciones exclusivamente contra
PostgreSQL de 127.0.0.1. Sus resultados actuales se conservan en `doc/pruebas/local-final.json`.
Las cifras de 23, 24 y 25 tablas de las secciones fechadas siguientes describen revisiones anteriores.

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
- Resultado: 23 tablas y 30 claves foraneas; ninguna tabla aislada. La tabla `migrations` de Laravel se elimino del
  servidor el 17/11 (respaldo en `.secrets/respaldos/public_20261117_233046.dump`): el P2 debe recrear su control de
  migraciones sin dejar una tabla suelta en `public` (por ejemplo, en otro esquema).
- **P2 web debe replicar estas claves** en sus migraciones y modelos (`$primaryKey`, `$incrementing`, `$keyType`).

## Bandeja de entrada y salida del correo (19/11/2026) — PENDIENTE DE APLICAR

Migracion para revision: `sql/migraciones/2026-11-19_bandeja_correo.sql` (reversion:
`2026-11-19_bandeja_correo_revertir.sql`). **No esta aplicada en produccion.** El JAR nuevo exige
estos cambios: sin ellos, `verificarModelo()` informa la migracion que falta y **no retira mensajes
del buzon**. Orden para aplicarla: detener el lector, `.\respaldo.ps1`, aplicar con
`psql -X -v ON_ERROR_STOP=1 --single-transaction`, `.\ejecutar.ps1 -Diagnostico`, arrancar.

- `log_comandos` (solo la usa el correo; la web no la toca) agrega `message_id` (cabecera Message-ID,
  UNIQUE), `contenido` (correo crudo mientras espera proceso), `bytes` y `procesado_en`. Cada correo se
  guarda con resultado `Recibido` **antes** del DELE; al procesarlo pasa a `Éxito`, `Error`,
  `Denegado`, `Ignorado` o `Repetido` y su contenido se vacia.
- `correos_salientes` (nueva): `nro_correo` (correlativo), FK `nro_registro` → `log_comandos` y FK
  `nro_recordatorio` → `recordatorios` (al menos una), `destinatario`, `asunto`, `mensaje` (correo MIME
  completo con su Message-ID fijo), `estado` (`Pendiente`, `Enviado`, `Fallido`, `Rebotado`),
  `intentos`, `proximo_intento`, `ultimo_error`, `enviado_en`, `created_at`.
- Resultado: 24 tablas y 32 claves foraneas; ninguna tabla aislada. `notificaciones` (avisos internos
  de la web) no cambia.
- Garantias y su prueba: `p1/PRUEBAS.md` § Caidas.

## Estructura y referencias

`sql/esquema.sql` describe la estructura propuesta con bandeja y catálogo normalizado, sin usuarios ni datos comerciales.
La migración de la bandeja sigue pendiente en el servidor vigente; no confundir esa propuesta con la estructura
ya desplegada. `sql/referencias.sql` contiene roles, motivos, descuentos, porcentaje de comision y las 25 ofertas
del catalogo simulado. Son archivos para preparar una base nueva de desarrollo o pruebas; no son una migracion
para aplicar sobre produccion.

```sh
psql -X -v ON_ERROR_STOP=1 --single-transaction -d BASE_NUEVA -f sql/esquema.sql
psql -X -v ON_ERROR_STOP=1 --single-transaction -d BASE_NUEVA -f sql/referencias.sql
```

El esquema SQL rechaza una instalacion sobre un `public` que ya contiene tablas, vistas o secuencias. Las cuentas se crean aparte: estos archivos no incluyen contrasenas ni cuentas reales. Los entornos QA son temporales y se eliminan al finalizar.

## Normalizacion del catalogo (19/11/2026) — PENDIENTE DE APLICAR

`sql/migraciones/2026-11-19_normalizar_catalogo.sql` separa `programas(codigo, nombre)` de
`catalogo_cache` (modulos). Conserva todos los codigos y los nombres de programa, precios y datos de los
modulos. Solo retira `catalogo_cache.programa_nombre` despues de copiarlo a `programas`; las instantaneas
en intereses y solicitudes no cambian. Agrega FK desde modulos y responsables hacia programas.
La estructura propuesta con ambas migraciones tiene **25 tablas, 209 columnas y 34 FK**.

Aplicar exclusivamente con el lector detenido y respaldo comprobado, usando
`psql -X -v ON_ERROR_STOP=1 --single-transaction -f sql/migraciones/2026-11-19_normalizar_catalogo.sql`.
No ejecutar el instalador sobre produccion. La migracion rechaza nombres contradictorios para el mismo
codigo y responsables sin programa en el catalogo. Revisar esos casos; no inventar ni eliminar datos.
La reversion es `2026-11-19_normalizar_catalogo_revertir.sql`, tambien en una sola transaccion; rechaza
programas nuevos sin modulos para evitar perderlos. Revertir tambien exige usar el JAR anterior.

El JAR nuevo consulta `programas.nombre` mediante JOIN y valida la estructura antes de leer el buzon.
**P2 web necesita adaptar sus consultas y escrituras de `catalogo_cache.programa_nombre` a `programas`.**
No se modifico ni desplego `crm-web/` en esta tarea. Coordinar esa adaptacion antes de ejecutar web
sobre el esquema normalizado. La API del SIS sigue pendiente; normalizar no la sustituye.

Prueba local reproducible, con una copia anterior del SQL sin credenciales:
`python scripts/probar_catalogo.py --sql-anterior RUTA_SQL_ANTERIOR`.
Evidencia: `doc/pruebas/catalogo-normalizado.json`.

## Catalogo y SIS

La API del Grupo 06 todavia no esta disponible. El catalogo es simulado. Correo prepara exportaciones CSV y permite registrar una constancia manual del resultado. La matricula academica automatica requiere el contrato real de su API.
