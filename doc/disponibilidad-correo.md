# Disponibilidad del lector de correo

Arranque desde la raíz del proyecto (no repetir si ya hay un lector activo):

```powershell
.\ejecutar.ps1 -Config .\crm-mail.properties
```

El lanzador utiliza JAVA_HOME si corresponde a Java 21 o el Microsoft OpenJDK 21
instalado. No cambia el PATH global de otros proyectos.

La comprobación del modelo y permisos JDBC se ejecuta en un monitor independiente.
Durante la comprobación y durante una caída, sólo HELP[] sin parámetros puede generar
la ayuda pública sin acceder a JDBC. La ayuda por rol y todos los demás comandos
esperan en POP3. Los permisos se consultan de nuevo al procesarlos tras la recuperación;
no se conserva una autorización de usuarios en caché.

El monitor verifica cada 30 segundos cuando está sano. Tras un fallo espera
5, 10, 20, 40 y hasta 60 segundos entre intentos. A estos plazos se añade el tiempo
de la comprobación JDBC. No realiza conexiones agresivas ni cambia el servidor.
POP3 conserva el mínimo de 3,2 segundos entre aperturas y rota el recorrido cuando
hay más de 20 mensajes, para no dejar una ayuda detrás de los mismos pendientes.
Una operación que ya comenzó justo antes del corte todavía puede esperar su timeout
JDBC; el monitor no cancela una transacción en curso ni confirma su resultado.

## Diario local

`.secrets/entregas-correo/` contiene estado privado y MIME de respuestas pendientes.
No versionarlo, borrarlo, mover el lector a otro directorio ni ejecutar una segunda
copia con otro diario. Conservarlo junto con la configuración al trasladar el servicio.
Los estados se escriben con force y reemplazo atómico antes del siguiente efecto.

- Sin archivo: no ejecutado; el correo sigue en POP3 si la BD no está disponible.
- EJECUTANDO: el proceso inició la preparación. Si quedó interrumpido, puede haber
  una operación confirmada sin respuesta guardada: requiere conciliación manual.
- PREPARADO: respuesta y avisos guardados; puede continuar el envío sin ejecutar
  otra vez el comando, incluso si PostgreSQL vuelve a caer.
- ENVIANDO: comenzó el intento SMTP. Un corte durante DATA puede significar que el
  servidor aceptó el correo aunque Java no recibiera la confirmación. No se reenvía
  automáticamente: requiere comprobar la aceptación con el proveedor.
- TERMINADO: todos los envíos fueron aceptados. Se permite DELE; si QUIT falla,
  la siguiente lectura borra el mismo mensaje sin repetir operación ni respuesta.

Si JDBC falla antes de intentar cualquier escritura, se permite reintentar desde
POP3. Si SMTP falla durante la apertura, antes de sendMessage/DATA, se conserva
PREPARADO y se reintenta. Si falla el disco o un diario no puede leerse, el lector
conserva el mensaje y registra el error. Los recordatorios también usan el diario:
si falla su marca JDBC después de enviar, la recuperación no repite el aviso.

Consultar entregas locales pendientes sin acceder a ningún servicio:

```powershell
java -jar target/crm-mail.jar --estado-entregas
```

EJECUTANDO y ENVIANDO no deben desbloquearse borrando archivos a ciegas. Detener el
lector antes de una conciliación, identificar la huella completa del mensaje en el
log y comprobar efectos en la BD o aceptación SMTP. La conciliación necesita
evidencia y una decisión operativa; no se automatizó una acción que pueda duplicar
una matrícula, un pago o un correo. La entrega exactamente una vez entre PostgreSQL,
POP3 y SMTP no puede garantizarse en todos los cortes con estas interfaces. Este
diseño prioriza conservar la solicitud y evitar repeticiones en los casos inciertos.
Un fallo físico del almacenamiento local requiere recuperar su respaldo.

## Diagnóstico JDBC

`maxLifetime=600000`, `connectionTimeout=15000`, `validationTimeout=5000`,
`connectTimeout=10 s`, `socketTimeout=30 s`, máximo una conexión y mínimo cero.
No se cambió maxLifetime: la coincidencia temporal del primer incidente no prueba
que Hikari cause las desconexiones. Retirar una conexión y fallar al crear su
reemplazo son hechos diferentes.

Los registros ahora conservan wrapper Hikari, stack, causas JDBC, SQLState, código,
tiempo de préstamo y conexiones activas/libres/en espera. SLF4J se dirige a JUL:
las renovaciones y estadísticas Hikari quedan en access.log y sus avisos en error.log.
Los logs históricos previos sólo tenían resúmenes: no permiten determinar qué
equipo, firewall o servicio originó los timeouts del 20/11/2026. Los fallos POP3
contemporáneos son indicios de un problema más amplio, no prueba de su origen.

## Pruebas locales

Los tests DisponibilidadCorreoTest utilizan un servidor POP3 sobre loopback y un
emisor que captura MIME sin enviarlo. Un proxy TCP corta y restaura el acceso al
PostgreSQL local sin recrear App ni su pool. Comprueban HELP durante el corte,
retención del privado, revisión de permisos al recuperar y pérdida de QUIT/reinicio.
EntregaCorreoTest comprueba progreso de avisos, reconexión antes de DATA, estados
inciertos y diarios dañados. PoolLocalTest termina exclusivamente su propia sesión
PostgreSQL local y verifica la renovación JDBC.

CasosAprobadosCorreoTest recorre los ocho CU en una base local nueva con
sql/esquema.sql y sql/referencias.sql. Las pruebas institucionales permanecen
deshabilitadas; no establecer P1_REMOTE_DB_PASSWORD para estas verificaciones.
