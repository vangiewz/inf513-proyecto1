# Pruebas del flujo compartido

Requiere Java 21+, Maven y, para integracion, Docker con [Tecno](https://github.com/b-shadow/Tecno) actualizado. `mvn test` sin variables ejecuta pruebas unitarias y omite las suites de integracion: no equivale a las 154 pruebas completas.

## Integracion local

Desde el repositorio web, con su Docker Compose iniciado:

```powershell
python scripts/qa_local.py start
docker exec crm-qa-audit php artisan catalogo:sincronizar-correo
# Primero la regresion web sobre QA nueva:
python scripts/qa_local.py test
```

Desde el repositorio de correo:

```powershell
$env:P1_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55432/crm_qa_audit'
mvn clean package
Remove-Item Env:P1_TEST_DB_URL
```

Se usan exclusivamente `crm_qa_audit` y la API aislada en `127.0.0.1:18002`. Al finalizar, desde web: `python scripts/qa_local.py stop`.

## Integracion con el servidor del docente

Las suites remotas requieren el esquema temporal `crm_qa_correo` con las mismas migraciones y catalogo. Nunca apuntarlas a `public`. Cargar `P1_REMOTE_DB_PASSWORD` en memoria desde la fuente privada; no escribir claves en scripts. Al habilitar ambas variables y ejecutar `mvn clean package` se incluyen todas las suites. Retirar la variable y el esquema QA al terminar.

En TecnoTarea, los auxiliares son `tools/base_compartida.py migrar-qa`, `tools/probar_base_compartida.py --servidor` y `tools/base_compartida.py eliminar-qa`. Pertenecen al workspace de operacion; no forman parte de este clon.

## Evidencia del 17/11/2026

`doc/pruebas/base-compartida.json`: 154 pruebas Java, 0 fallas, 0 errores, 0 omitidas; incluye 12 pruebas remotas. Ademas pasaron 264 comprobaciones web y 12 comandos de consulta del JAR desplegado. La suite financiera remota se repitio con limite de 600 segundos por latencia observada. No se crearon ventas de prueba en produccion ni se enviaron correos externos.
