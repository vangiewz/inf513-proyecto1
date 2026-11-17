# Base compartida de correo y web

Configuracion vigente desde el 17/11/2026:

- PostgreSQL: `db.tecnoweb.org.bo:5432`.
- Base del Grupo 05 SA: **`db_grupo05sa`**.
- Esquema de ambos proyectos: **`public`**.
- JDBC: `currentSchema=public`; Laravel: `DB_SEARCH_PATH=public`.
- `crm` y `crm_compartido` fueron retirados. No recrearlos ni ejecutar el instalador historico `sql/crm_p1.sql` sobre el destino vigente.
- Las tablas se gestionan mediante las 31 migraciones de `backend/database/migrations/` en [Tecno](https://github.com/b-shadow/Tecno), incluidas las dos del 17/11/2026.
- Desarrollo Docker y QA son destinos separados; nunca ejecutar pruebas comerciales en produccion.
- Las credenciales reales permanecen exclusivamente en `.secrets/credenciales.md` del workspace privado.

Correo cubre los ocho casos de uso aprobados; ver [guia operativa](GUIA_CORREO_CASOS_APROBADOS.md). Las ordenes pueden incluir varios modulos del mismo programa; aprobar exige comprobante y perfil completo y confirma inscripciones/comisiones sin duplicarlas.

Correo limita coordinacion a `programas_responsables`. **Aplicar tambien este alcance a las autorizaciones de coordinacion web antes de publicar P2.**

La API del Grupo 06 aun no esta disponible: el catalogo sigue simulado y el intercambio SIS se prepara por CSV con constancia manual. No existe confirmacion automatica de matricula academica.

Verificacion: 154 pruebas Java sin fallas ni omisiones (12 contra QA del docente), 264 comprobaciones web y 12 comandos de consulta del JAR desplegado. No se enviaron correos externos durante esta verificacion. Los diagramas/PUDS anteriores requieren actualizacion antes de una entrega academica.
