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

## Claves y relaciones (17/11/2026)

Migracion aplicada en produccion: `sql/migraciones/2026-11-17_claves_catalogo.sql` (respaldo previo en `.secrets/respaldos/`).
Siguiendo la clase 46 del docente (ninguna tabla sin relacion; sin un `id` extra cuando la tabla ya tiene su clave):

- `catalogo_cache` usa `codigo` como clave primaria (sin `id`); `intereses.external_modulo_id` y
  `solicitudes_compra.external_modulo_id` la referencian (ON UPDATE CASCADE, ON DELETE RESTRICT).
- `orden_pago_descuentos` tiene clave `(orden_pago_id, descuento_id)` (sin `id`) y conserva `porcentaje` y `monto`.
- Resultado: 23 tablas de negocio y 30 claves foraneas; ninguna tabla aislada (`migrations` es de Laravel).
- P2 web debe replicar el cambio con una migracion de Laravel antes de usar estas tablas.

## Estructura y referencias

`sql/esquema.sql` describe la estructura instalada, sin usuarios ni datos comerciales. `sql/referencias.sql` contiene roles, motivos, descuentos, porcentaje de comision y las 25 ofertas del catalogo simulado. Son archivos para preparar una base nueva de desarrollo o pruebas; no son una migracion para aplicar sobre produccion.

```sh
psql -X -v ON_ERROR_STOP=1 --single-transaction -d BASE_NUEVA -f sql/esquema.sql
psql -X -v ON_ERROR_STOP=1 --single-transaction -d BASE_NUEVA -f sql/referencias.sql
```

El esquema SQL rechaza una instalacion sobre un `public` que ya contiene tablas, vistas o secuencias. Las cuentas se crean aparte: estos archivos no incluyen contrasenas ni cuentas reales. Los entornos QA son temporales y se eliminan al finalizar.

## Catalogo y SIS

La API del Grupo 06 todavia no esta disponible. El catalogo es simulado. Correo prepara exportaciones CSV y permite registrar una constancia manual del resultado. La matricula academica automatica requiere el contrato real de su API.
