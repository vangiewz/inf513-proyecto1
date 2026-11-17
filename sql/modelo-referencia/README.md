# Estructura de referencia para correo

Esta carpeta conserva las 31 migraciones PHP que describen la estructura PostgreSQL instalada para el proyecto de correo. Se copiaron aqui antes de revertir los cambios no solicitados en el repositorio web.

Las 29 migraciones anteriores al 17/11/2026 son el modelo base de web. Las dos del 17/11/2026 agregan soporte de correo (catalogo en cache, motivos, historial, comandos, responsables por programa).

No contienen datos del servidor ni credenciales. No se ejecutan con Java ni constituyen por si solas una instalacion de Laravel. No aplicar sus metodos `down` al servidor: eliminarian estructuras usadas por el correo desplegado. `sql/crm_p1.sql` pertenece al modelo antiguo y tampoco debe ejecutarse sobre la base vigente.

Destino vigente de correo: `db.tecnoweb.org.bo:5432`, base `db_grupo05sa`, esquema `public`. El proyecto web permanece separado y su codigo fue restaurado al contenido anterior mediante el commit `c6ba1cd`.
