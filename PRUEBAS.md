# Pruebas de P1 correo

## Ejecución reproducible de la revisión final

```powershell
python scripts/probar_local.py --java-home RUTA_JDK_21_O_POSTERIOR --defensa
```

Requiere Maven y PostgreSQL 18; `--pg-bin` permite indicar sus binarios. Crea un clúster exclusivo
escuchando sólo en 127.0.0.1 y lo detiene al terminar. No lee credenciales privadas; sobrescribe
las variables P1 de las integraciones para que ninguna suite se dirija al servidor institucional.
También las clases llamadas ServidorCompartidoTest y CorreoServidorFinancieroTest se ejecutan localmente.
Los logs de desarrollo se conservan bajo target/qa-local y no se incluyen en el ZIP.

El runner comprueba instalación y referencias repetibles, migración de bandeja, conservación del
catálogo y rollback de conflictos, y reaplicación de persona. JUnit incluye además el rechazo de
CHECK debilitados e índices parciales, compuestos o sin normalización de correo, y versión futura.

Las pruebas de persona recorren inserción, consulta posterior, duplicados, tipos y
longitudes, errores y permisos. PersonaCorreoTest utiliza POP3/SMTP locales, PostgreSQL real y firma
HMAC válida para recorrer App.ciclo. BandejaCorreoTest incluye caída SMTP, persistencia, relectura,
suplantación de From, rebotes exactos y contingencia después del rollback de un fallo interno.

ControlSondeoTest y AppTest utilizan relojes/esperas simulados para comprobar fixed delay, ciclo de
siete segundos más espera de tres, exclusión, cierre, backoff y frecuencia IMAP independiente.
ReconexionTest verifica la espera institucional mínima y clasificación de credenciales sin abrir red.
AutenticidadCorreoTest prueba firmas, alteraciones, caducidad y representación hexadecimal canónica.
El vector HMAC coincide con scripts/firmar_correo.py; ninguna clave de prueba es una credencial real.

Con --defensa se ejecuta el JAR real, con EML firmados y polling de 3000 ms, se corta su proceso y
se interrumpe PostgreSQL mediante pg_ctl sobre el clúster exclusivo. Al final se comprueba ausencia
de solicitudes perdidas y operaciones duplicadas y recuperación de las respuestas SMTP.
Esta prueba no utiliza correo real ni demuestra la política del firewall institucional.

Evidencias actuales: doc/pruebas/local-final.json, defensa-local.json y XML de target/surefire-reports.
Los números del registro histórico siguiente no sustituyen las pruebas actuales. La corrida inicial
de esta revisión encontró un fallo de validación telefónica en persona; se corrigió conservando la
compatibilidad CRM y se repitieron las verificaciones correspondientes.

Requiere Java 21+ y Maven. Desde este repositorio:

```sh
mvn test
```

Sin variables de integracion se ejecutan las pruebas unitarias; Maven informa las suites de integracion omitidas. Esto no equivale a ejecutar las 154 pruebas del registro anterior.

Para verificar los SQL de instalacion, con Docker:

```sh
python scripts/verificar_sql.py
```

Crea PostgreSQL 18 sin red ni puertos publicados, comprueba la instalacion y las referencias, verifica el rechazo sobre un esquema ocupado y elimina su contenedor al terminar.

## Integracion de correo con PostgreSQL

`ServidorCompartidoTest` y `CorreoServidorFinancieroTest` prueban correo directamente contra un esquema QA del servidor del docente, sin depender de web. Incluyen roles, alcance, ordenes, comprobantes, pagos, conversion, exportacion y concurrencia.

Requieren preparar exclusivamente `crm_qa_correo`, con la estructura y referencias de `sql/`, adaptando el destino del esquema a QA. No ejecutar sobre `public`. Cargar `P1_REMOTE_DB_PASSWORD` en memoria desde la fuente privada y ejecutar:

```sh
mvn -Dtest=ServidorCompartidoTest,CorreoServidorFinancieroTest test
```

Al terminar, retirar la variable y eliminar el esquema temporal. Se crean datos ficticios; la clave nunca se escribe en scripts ni archivos publicados.

## Pruebas locales: los 8 CU, la vigencia y la robustez

`CasosAprobadosCorreoTest` recorre los ocho casos de uso por el despachador de correo (alta y modificación de usuario con
su vigencia, registro público, asignación, embudo, pérdida, orden, comprobante, aprobación, inscripción, exportación,
reportes y bitácora), prueba que una credencial vencida o todavía no vigente queda "Denegado" y las reglas de alcance,
rechazo y transacción de los pagos.

`RobustezCorreoTest` manda cada comando con cada rol (administrador, coordinador, vendedor y un visitante de Outlook) y
con entradas malas: vacías, solo espacios, letras donde va un número, negativos, cero, números enormes, inyección SQL,
5000 caracteres, emojis, tabulaciones, HTML, fechas imposibles, parámetros de más, comas sueltas, corchetes y comillas
sin cerrar, minúsculas, espacios y `RE:`; además siete CSV dañados para `IMPORTARPROSPECTOS`. Exige que ninguna
respuesta sea un error interno ni un error SQL sin traducir, que lo vacío o mal formado nunca dé "Éxito" y que las
tablas sigan en pie.

Ambas corren contra un PostgreSQL 18 desechable (solo acepta `127.0.0.1`):

```sh
docker run -d --name p1-qa -p 127.0.0.1:55442:5432 -e POSTGRES_USER=crm_user -e POSTGRES_PASSWORD=crm_password -e POSTGRES_DB=crm_qa postgres:18-alpine
docker exec -i p1-qa psql -U crm_user -d crm_qa -c "DROP SCHEMA public CASCADE"
docker exec -i p1-qa psql -v ON_ERROR_STOP=1 --single-transaction -U crm_user -d crm_qa < sql/esquema.sql
docker exec -i p1-qa psql -v ON_ERROR_STOP=1 --single-transaction -U crm_user -d crm_qa < sql/referencias.sql
P1_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55442/crm_qa P1_ROBUSTEZ_DB_URL=jdbc:postgresql://127.0.0.1:55442/crm_qa   mvn test -Dtest=CasosAprobadosCorreoTest,RobustezCorreoTest
docker rm -f p1-qa
```

Las pruebas de servidor también pueden correr en local: `P1_REMOTE_DB_URL` (con `currentSchema=crm_qa_correo`),
`P1_REMOTE_DB_USER` y `P1_REMOTE_DB_PASSWORD` apuntan a otra base.

## Evidencia (17/11/2026, después de pasar a claves de diseño)

- 129 pruebas sin fallas ni omisiones: 103 unitarias, 13 de `CasosAprobadosCorreoTest`, 1 de robustez (4515 casos) y las
  12 de integración, en local y luego contra el PostgreSQL de la facultad (`doc/pruebas/integracion-servidor.json`).
- Robustez: los 76 "Éxito" con valores raros son legítimos: parámetros opcionales vacíos (listar todo), texto de búsqueda
  en `LISTARPROSPECTOS` y `VERBITACORA` con cantidad enorme (se limita a 200).
- Contra la base real migrada se ejecutaron 19 comandos de consulta del JAR (tablero, bolsa, órdenes, matrículas,
  exportaciones, recordatorios, bitácora y los cuatro reportes) sin crear ventas.
- La interoperabilidad HTTP con la versión web retirada (`BaseCompartidaTest`) se eliminó: el P2 debe replicar las claves
  de diseño en sus migraciones de Laravel y tendrá sus propias pruebas.

## Caidas y recuperacion

La versión del 19/11 incorpora `BandejaCorreoTest` (8 pruebas con POP3/SMTP locales y PostgreSQL desechable),
`ColaCorreoTest` (3 pruebas del mensaje MIME y sus identificadores) y `AppTest` (1 prueba). Los informes
JUnit conservados en `target/surefire-reports/` registran 141 pruebas sin fallas ni omisiones en la corrida
preparada por Claude. No confundir esta evidencia local con una ejecución en el servidor de la facultad.

`scripts/prueba_caidas.py` ejercita el JAR completo con servidores de correo de prueba. Solo admite una URL
de BD en `127.0.0.1`; requiere una base nueva desechable, con la estructura y referencias actuales.
Si se usa `--pgdata`, debe apuntar exclusivamente al clúster desechable: el script lo apaga de golpe.

```sh
python -I scripts/prueba_caidas.py --db jdbc:postgresql://127.0.0.1:55443/crm_caidas --cortes 60 --semilla 31 --psql RUTA_PSQL --pgdata RUTA_CLUSTER_DESECHABLE --pg-ctl RUTA_PG_CTL
```

La ejecución conservada en `doc/pruebas/caidas.json` (19/11, semilla 31) registró 60 cierres forzados,
12 caídas de BD, pérdidas de confirmación QUIT y fallas SMTP. Los 30 comandos distintos se ejecutaron una
vez cada uno; no quedaron comandos perdidos, operaciones duplicadas ni respuestas pendientes. Hubo 37
recepciones SMTP para 30 Message-ID distintos: las entregas inciertas pueden producir copias de una
respuesta. La garantía no es entrega de correo exactamente una vez. El extracto está en
`doc/pruebas/caidas-log.txt`.

El script sobrescribe por omisión `doc/pruebas/caidas.json`; para una comprobación adicional sin reemplazar
la evidencia del documento, indicar `--salida RUTA_TEMPORAL`. La cuenta del servidor y el correo real no se
utilizan en esta prueba.

**Despliegue pendiente:** la migración `sql/migraciones/2026-11-19_bandeja_correo.sql` no está aplicada a
`db_grupo05sa.public`. La estructura del SQL actual es la propuesta con bandeja; la integración del 17/11
corresponde a la versión anterior. Falta validar la versión nueva contra el servidor cuando esté disponible.

## Auditoria de los modelos globales

Segunda revisión: `python scripts/auditar_modelos.py --ampliada`. Agrega controles de PK, ausencia de `id`,
vigencia, normalización del catálogo, ocho colaboraciones, vistas globales, referencias repetibles y rechazo
del instalador sobre esquema ocupado. Resultado del 19/11: **54 comprobaciones aprobadas**, en
`doc/pruebas/auditoria-ampliada.json`. Resumen: `AUDITORIA_ADICIONAL.md`.

```sh
python scripts/auditar_modelos.py
```

Requiere Enterprise Architect por COM, pywin32 y PostgreSQL 18 instalado (opción `--pg-bin` para indicar sus
binarios). Crea un clúster nuevo y exclusivo en `127.0.0.1`, instala `sql/esquema.sql` y compara sus metadatos
con `doc/ea/crm-p1.eapx`. Detiene y elimina el clúster al terminar. No usa credenciales ni datos del CRM real.

La revisión histórica después de normalizar tenía 25 tablas, 209 columnas y 34 FK; el lógico
representa las columnas que no son FK como atributos (incluyendo las FK que también son PK) y las FK restantes como asociaciones. Se comprueban PK, UNIQUE y
nulos al determinar las multiplicidades, el conceptual sin tipos ni métodos, la clase de asociación N a N,
una vista global por nivel y una secuencia y un procedimental completo por cada CU. Evidencia:
`doc/pruebas/modelos-globales.json`. Esta auditoría es adicional a las pruebas de aplicación.
La revisión final agrega persona y alcanza 26 tablas, 216 columnas y las mismas 34 FK; la entidad
docente independiente no inventa relaciones con el CRM. Consultar la fecha y el alcance del informe
de modelos regenerado antes de atribuir sus comprobaciones a esta revisión.

## Catálogo normalizado y regresión de P1

`python scripts/probar_catalogo.py --sql-anterior RUTA_SQL_ANTERIOR` crea un PostgreSQL efímero solo en
127.0.0.1. Comprueba conservación de filas y valores al migrar, reversión, rechazo con rollback de nombres
contradictorios y equivalencia con la instalación nueva. Ejecuta toda la suite con sus integraciones en ese
mismo clúster local; las clases llamadas ServidorCompartidoTest y CorreoServidorFinancieroTest usan aquí
el esquema QA local, no el servidor de la facultad. Genera `doc/pruebas/catalogo-normalizado.json`.

CatalogoNormalizadoTest agrega tres casos: programas y módulos separados con sus FK, rechazo de referencias
inexistentes y renombrado del programa sin cambiar el histórico comercial. Las migraciones de bandeja y
catálogo siguen pendientes en producción; P2 requiere adaptar sus consultas a `programas.nombre`.
