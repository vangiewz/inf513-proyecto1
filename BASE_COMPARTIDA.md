# Modelo de base web usado por correo

**Alcance corregido (17/11/2026):** correo y web son proyectos separados. El trabajo actual corresponde solo a correo, tomando el modelo de datos de web como referencia. El commit que modificaba web fue revertido mediante `c6ba1cd`; no debe repetirse esa modificacion para trabajar en P1.

Configuracion vigente desde el 17/11/2026:

- PostgreSQL: `db.tecnoweb.org.bo:5432`.
- Base del Grupo 05 SA: **`db_grupo05sa`**.
- Esquema de ambos proyectos: **`public`**.
- JDBC: `currentSchema=public`; Laravel: `DB_SEARCH_PATH=public`.
- `crm` y `crm_compartido` fueron retirados. No recrearlos ni ejecutar el instalador historico `sql/crm_p1.sql` sobre el destino vigente.
- La copia de las 31 migraciones que describen el modelo instalado para correo se conserva en `sql/modelo-referencia/`. Las anteriores al 17/11/2026 proceden de web; las dos del 17/11/2026 contienen ampliaciones de correo. Son una referencia de estructura, no un instalador Java ejecutable ni una instruccion para modificar web.
- Desarrollo Docker y QA son destinos separados; nunca ejecutar pruebas comerciales en produccion.
- Las credenciales reales permanecen exclusivamente en `.secrets/credenciales.md` del workspace privado.

Correo cubre los ocho casos de uso aprobados; ver [guia operativa](GUIA_CORREO_CASOS_APROBADOS.md). Las ordenes pueden incluir varios modulos del mismo programa; aprobar exige comprobante y perfil completo y confirma inscripciones/comisiones sin duplicarlas.

Correo limita coordinacion a `programas_responsables`. Esto describe P1 y no autoriza cambios en P2.

La API del Grupo 06 aun no esta disponible: el catalogo sigue simulado y el intercambio SIS se prepara por CSV con constancia manual. No existe confirmacion automatica de matricula academica.

Verificacion anterior a revertir web: 154 pruebas Java sin fallas ni omisiones (12 contra QA del docente), 264 comprobaciones web y 12 comandos de consulta del JAR desplegado. Las pruebas HTTP entre clientes se ejecutaron con la revision web `098794f`, posteriormente revertida; no certifican el comportamiento de la web restaurada. El JAR desplegado y la base del servidor no se modificaron al revertir el codigo web. No se enviaron correos externos durante esta verificacion. Los diagramas/PUDS anteriores requieren actualizacion antes de una entrega academica.
