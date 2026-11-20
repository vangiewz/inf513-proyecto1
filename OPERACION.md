# Operación y despliegue de P1 correo

Este procedimiento prepara una intervención futura autorizada. Durante el desarrollo final no se
contactan servicios institucionales, no se aplican migraciones remotas ni se envían correos reales.
La base compartida es db_grupo05sa, esquema public; no crear otra base de producción para correo.

## Preparación local

1. Compilar con JDK 21 o posterior: `mvn -B package`.
2. Ejecutar `python scripts/probar_local.py --java-home RUTA_JDK --defensa`.
3. Revisar `doc/pruebas/local-final.json`: no contabilizar omitidos como aprobados.
4. Preparar configuración privada y claves HMAC por usuario; nunca copiarlas al repositorio o ZIP.
5. Probar el trayecto de firma del cliente antes de autorizar operaciones registradas.

La firma demuestra posesión de la clave individual, no certifica identidad civil ni propiedad de un
dominio. Mantener cada clave privada, rotarla al retirar el acceso y no distribuir el archivo completo
de claves del servidor entre los clientes. Cada cliente recibe únicamente su propia clave.
El script de firma crea EML, no envía. No basta redactar un comando sin firma en Gmail.
La firma tiene una vigencia de 24 horas y admite cinco minutos de desfase futuro. Una solicitud
que espere más tiempo durante una caída requiere volver a firmarse; un rechazo por caducidad
es un error controlado, no una inserción confirmada ni una solicitud eliminada sin trazabilidad.

## Sondeo y conexiones

Configurar mail.poll.interval.ms=3000, mail.poll.backoff.max.ms=30000 y
mail.poll.permanent.retry.ms=60000. El bucle espera después de cada ciclo, incluso si el ciclo tarda
más de tres segundos. No hay compensación de ciclos atrasados. Los errores temporales producen
esperas progresivas; los permanentes se identifican para corregir configuración o estructura.
No se introducen pausas entre sentencias de una transacción normal.

No ejecutar el lector de la PC y el del VPS simultáneamente. InstanciaCorreo utiliza un bloqueo
del sistema operativo sobre el buzón en lock.directory. Todos los lanzamientos de una máquina
deben usar el mismo directorio, con permisos que impidan crear un directorio alternativo por error.
Este bloqueo no coordina máquinas diferentes: el responsable debe mantener un solo lector activo.
No ejecutar diagnósticos automáticos, cron ni monitores adicionales consultando el mismo servicio.
La exigencia de tres segundos se aplica aquí al sondeo y a la reconexión después de fallos;
confirmar con el docente cualquier límite adicional por servicio. No se garantiza cómo clasifica
el firewall un tráfico que no está documentado por la institución.

## Inspección y migraciones futuras

Antes de cualquier cambio remoto: obtener autorización, identificar y detener el lector activo,
comprobar un respaldo recuperable y confirmar servidor/base/esquema. Los comandos siguientes
son instrucciones para esa intervención futura, no acciones ejecutadas durante esta tarea.

```powershell
# Configuración privada y PostgreSQL 18 cliente disponibles:
.\respaldo.ps1 -Config RUTA_PRIVADA\crm-mail.properties
# Inspección de sólo lectura: el acceso y la contraseña se configuran fuera de esta línea.
psql -X -h db.tecnoweb.org.bo -p 5432 -U grupo05sa -d db_grupo05sa -v ON_ERROR_STOP=1 -f sql/diagnostico_estructura.sql
```

El diagnóstico enumera diferencias de columnas/tipos, restricciones e índices y muestra sus
definiciones. No deducir migraciones a partir del número de tablas. Comparar con el SQL requerido,
revisar conflictos de catálogo y verificar claves de las filas existentes. Una base con 23 tablas
podría tener una combinación de cambios distinta de la que sugieren sus cantidades.

Aplicar solamente los scripts necesarios, con el lector detenido:

```powershell
psql -X -h db.tecnoweb.org.bo -p 5432 -U grupo05sa -d db_grupo05sa -v ON_ERROR_STOP=1 --single-transaction -f sql/migraciones/2026-11-19_bandeja_correo.sql
psql -X -h db.tecnoweb.org.bo -p 5432 -U grupo05sa -d db_grupo05sa -v ON_ERROR_STOP=1 --single-transaction -f sql/migraciones/2026-11-19_normalizar_catalogo.sql
psql -X -h db.tecnoweb.org.bo -p 5432 -U grupo05sa -d db_grupo05sa -v ON_ERROR_STOP=1 --single-transaction -f sql/migraciones/2026-11-19_persona.sql
```

No ejecutar sql/esquema.sql ni sql/referencias.sql sobre producción. Bandeja y catálogo rechazan
una reaplicación incompatible; persona admite reaplicación sólo si la estructura existente es válida.
Persona registra schema.persona.version=1 en configuraciones, sin tabla de versiones aislada.
No usar ese valor como única prueba de compatibilidad: también deben coincidir columnas, tipos,
CHECK, PK e índice UNIQUE global. Después de confirmar que configuraciones existe, su lectura manual es:
`SELECT clave,valor FROM public.configuraciones WHERE clave='schema.persona.version';`.
Después repetir el diagnóstico, comprobar restricciones/datos, permisos y ejecutar el diagnóstico del JAR.
No publicar contraseñas en comandos, capturas o informes; psql puede solicitarlas interactivamente.

La reversión de bandeja rechaza cualquier dato que pudiera perderse. La reversión del catálogo
rechaza programas sin módulos. Restaurar un respaldo sólo mediante intervención explícita sobre
el destino confirmado y con el lector detenido; volver también al JAR compatible. No borrar persona
para revertir la aplicación: conservar sus registros y deshabilitar sus comandos en la versión anterior.

P2 debe adaptar sus consultas a programas.nombre antes de operar el catálogo normalizado. No se
modifica P2 durante esta tarea; coordinarlo antes de una intervención en la base compartida.

## Linux systemd

Requiere usuario de servicio crmmail, JRE/JDK 21+, artefacto en /opt/crm-mail/crm-mail.jar,
configuración privada en /etc/crm-mail/crm-mail.properties y claves privadas en /etc/crm-mail/.
Preparar archivos de sólo lectura para crmmail y permisos 0600 para configuración y claves.
No almacenar contraseñas en la unidad systemd.

La unidad deploy/crm-mail.service conserva ProtectSystem=strict y dispone de LogsDirectory y
RuntimeDirectory. systemd crea /var/log/crm-mail y /run/crm-mail con dueño del servicio y modo 0750;
la JVM utiliza esas rutas para logs y bloqueo. UMask=0077 protege archivos nuevos.
El reinicio por fallos espera tres segundos y existe un límite de arranques para evitar bucles.

Comandos de instalación/operación para ejecutar únicamente cuando se autorice:

```sh
sudo install -m 0644 deploy/crm-mail.service /etc/systemd/system/crm-mail.service
sudo systemd-analyze verify /etc/systemd/system/crm-mail.service
sudo systemctl daemon-reload
sudo systemctl enable --now crm-mail
sudo systemctl status crm-mail --no-pager
sudo journalctl -u crm-mail --since today
sudo systemctl stop crm-mail
```

Primero detener el lector anterior. SIGTERM interrumpe la espera y solicita cierre del bucle;
las conexiones de red tienen timeouts. La bandeja conserva los trabajos no confirmados.
La validación de systemd en Linux requiere ese sistema operativo; una revisión estática en Windows
no equivale a ejecutar la unidad ni a demostrar su arranque tras reiniciar el VPS.

## Fallas y recuperación

| Situación | Estado y respuesta |
|---|---|
| BD inaccesible antes de leer | No se retira correo POP3; reintento con backoff |
| BD accesible, esquema incompleto | Falla permanente identificada; requiere migración, sin ciclo agresivo |
| Credenciales inválidas | Falla de autenticación distinguida de conectividad |
| Caída antes del COMMIT | ROLLBACK; solicitud pendiente recuperable |
| COMMIT confirmado, SMTP caído | Respuesta persistida pendiente; no repetir negocio |
| SMTP aceptó, se perdió confirmación | Reenvío con Message-ID estable; pueden llegar copias |
| Excepción interna recuperable | Contingencia en transacción; error con respuesta o pendiente |
| Mensaje automático, propio o sin dirección válida | Ignorado/fallo documentado; no contestar para evitar bucles |
| Rebote posterior | Correlación por Message-ID original y destinatario; marcar leído tras persistir |

No es técnicamente posible enviar una respuesta si no existe dirección válida o la dependencia SMTP
permanece inaccesible. Tampoco hay respuesta inmediata si la BD falla antes de guardar/leer el correo:
la solicitud permanece en el servidor POP3 y se atiende al recuperarse.

## Verificación externa de aceptación

Cuando se autorice y los servicios estén disponibles: confirmar DNS/TCP y TLS real, estructura y
permisos, conservación de los mensajes firmados, un LISPER, un INSPER seguido de consulta, un error,
una operación de cada rol y los ocho CU representativos. Correlacionar registros con respuestas.
Coordinar con el administrador cualquier prueba de caída; nunca apagar servicios compartidos por iniciativa propia.
No realizar estrés ni conexiones repetitivas a la universidad para comprobar disponibilidad.
