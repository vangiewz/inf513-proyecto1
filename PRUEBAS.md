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

## Pruebas complementarias entre clientes

`BaseCompartidaTest` y `CasosAprobadosCorreoTest` incluyen pruebas JDBC y HTTP de interoperabilidad con una version de desarrollo web. Se conservan como pruebas complementarias para P2. Requieren un entorno compatible separado en `crm_qa_audit`, PostgreSQL local en el puerto 55432 y API HTTP en 18002. No modificar web para ejecutarlas como parte de una tarea de correo.

## Evidencia conservada

`doc/pruebas/base-compartida.json` registra la ejecucion del 17/11/2026: 154 pruebas Java sin fallas ni omisiones, incluidas 12 pruebas PostgreSQL del servidor. Las pruebas HTTP se realizaron con una version de desarrollo web que posteriormente se retiro; el registro no certifica la web actual. Tambien se verificaron 12 comandos de consulta del JAR desplegado, sin crear ventas de prueba en produccion ni enviar correos externos.

Los escenarios y capturas academicos antiguos deben actualizarse antes de entregar el PUDS. El codigo de pruebas se conserva; los archivos temporales de Maven no se versionan.
