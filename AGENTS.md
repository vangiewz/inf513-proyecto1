# Proyecto 1: correo

- Antes de continuar cambios, leer `CONTINUAR_P1.md`: decisiones del usuario sobre diagramas por CU y globales,
  ejemplos aprobados, evidencia local y pendientes de despliegue. Actualizarlo cuando cambie el estado.

- La prioridad actual es terminar P1 correo y sus ocho casos de uso aprobados por el docente.
- P2 web sigue en desarrollo. Ambos usaran la misma base y registros; se comenzo por web, pero ahora se termina correo primero.
- Destino vigente: PostgreSQL `db.tecnoweb.org.bo:5432`, base `db_grupo05sa`, esquema `public`.
- No crear una segunda base de produccion para correo ni recrear los esquemas retirados `crm` y `crm_compartido`.
- No modificar web como parte de una tarea de correo. P2 se continuara despues sobre esta base y podra ampliarse.
- Estructura y referencias: `sql/esquema.sql`, `sql/referencias.sql`; instrucciones en `BASE_DATOS.md`.
- Las credenciales reales solo se conservan en `.secrets/credenciales.md` del workspace privado y nunca se publican.
- Conservar codigo, pruebas y documentacion utiles; no agregar copias de frameworks, respaldos, datos personales ni resultados temporales.
