# Pruebas de P1 correo

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
