# CRM de captación de prospectos vía e-mail

Proyecto 1 de INF513 Tecnología Web (SA), UAGRM FICCT, gestión 2-2026. Grupo 05 SA:
Lobo Gutierrez Carlos Gabriel, Serrate Basma Leonardo y Vasquez Ardaya Brandon Lider.

El usuario escribe un comando en el **asunto** de un correo a `grupo05sa@tecnoweb.org.bo`, por ejemplo
`VERTABLERO[]` o `MOVERTARJETA["7","Contactado"]`. El sistema lee el buzón por POP3, identifica al remitente por su
correo, comprueba que su rol pueda usar el comando, lo ejecuta en PostgreSQL y le responde con un correo HTML.
`HELP[]` devuelve los comandos de cada rol.

## Arquitectura

| Capa | Paquete | Contenido |
|---|---|---|
| Presentación | `presentacion` | `Pop3Client` (sockets, RFC 1939), `SmtpSender` (Jakarta Mail), `Correo` (MIME) |
| Aplicación | `aplicacion` | `CommandParser`, `Command`, `CommandProcessor`, un `RolCommandProcessor` por rol, `ResponseFormatter` |
| Dominio | `dominio.<cu>` | un paquete por caso de uso con su `XService` y sus entidades |
| Persistencia | `persistencia` | `DatabaseService` (JDBC, consultas parametrizadas) |

Casos de uso: CU1 usuarios · CU2 captación y oferta · CU3 asignación · CU4 embudo Kanban · CU5 pérdidas y motivos ·
CU6 validación y conversión a matrícula · CU7 bitácora, interacciones y recordatorios · CU8 reportes.
Roles: `administrador`, `coordinador`, `vendedor` y el remitente no registrado (público).
La matriz de acceso se genera del código con `java -jar target/crm-mail.jar --anexo`.

## Despliegue

| Pieza | Valor |
|---|---|
| Lectura | POP3 `mail.tecnoweb.org.bo:110` (Dovecot), cuenta `grupo05sa` |
| Respuesta | SMTP `smtp.gmail.com:587` STARTTLS (la facultad no reenvía a dominios externos) |
| Base de datos | PostgreSQL 18.6 `db.tecnoweb.org.bo:5432`, BD `db_grupo05sa`, esquema compartido `public` |
| Ejecución | PC del grupo (Windows 11, JDK 21) con `ejecutar.ps1`; el servicio del VPS (`deploy/crm-mail.service`) quedó deshabilitado como respaldo |
| Log | consola y `logs/crm-mail.N.log` (5 MB x 5): una línea por ciclo y la causa de cada falla |

## Compilar y ejecutar

```sh
mvn package                                   # Java 21+, Maven 3.9; genera target/crm-mail.jar

# Para instalar una base nueva de pruebas, consultar BASE_DATOS.md.
cp crm-mail.properties.example crm-mail.properties   # completar las contraseñas (no se versiona)
java -jar target/crm-mail.jar crm-mail.properties
```

En la PC del grupo (PowerShell), con la configuración privada en `..\.secrets\crm-mail.properties`:

```powershell
.\ejecutar.ps1                # servicio: revisa el buzón cada 10 s (Ctrl+C para detener)
.\ejecutar.ps1 -Diagnostico   # solo revisa DNS, puertos, POP3, SMTP y la BD (IP vista por PostgreSQL, tablas, permisos)
```

Cada ciclo deja una línea (`ciclo 812 OK · BD 42 ms · POP3 0 msg 310 ms`). Ante una falla, el log nombra la etapa, el
destino y la causa probable (DNS, puerto cerrado, IP fuera de `pg_hba.conf`, clave cambiada, tabla borrada, permiso
revocado, buzón bloqueado por otro lector, clave de aplicación de Gmail revocada); mientras sigue, una línea corta por
ciclo, y al volver, `RECUPERADO` con su duración. La traducción está en `Diagnostico.java` (prueba: `DiagnosticoTest`).

Prueba sin correo: `java -jar target/crm-mail.jar --probar crm-mail.properties remitente@gmail.com "VERTABLERO[]"`.

## Base de datos y alcance

Ahora se termina **P1 correo**, con los ocho casos de uso aprobados. P2 web sigue en
desarrollo y se continuara despues sobre la misma base y registros. Aunque se comenzo
por web, correo funciona directamente con PostgreSQL y no requiere ejecutar Laravel.
La base vigente es `db_grupo05sa`, esquema `public`.
Estructura e instalacion en una base nueva: [BASE_DATOS.md](BASE_DATOS.md).

- El vendedor no se autoasigna ni ve la bolsa; administracion/coordinacion distribuyen por menor carga.
- Se aceptan los alias En Contacto y Seguimiento; el tablero muestra las seis etapas aprobadas y los pagos pueden gestionarse por correo.
- CU6 permite generar ordenes, adjuntar comprobantes reales, revisar pagos y confirmar inscripciones sin abrir web.
- La conversion consulta la inscripcion confirmada, sin duplicarla; la exportacion CSV prepara el intercambio con SIS.
- `COMPLETARRECORDATORIO["id","resultado"]` exige una nota de cumplimiento.
- El catalogo externo sigue simulado; las referencias iniciales estan en `sql/referencias.sql`.
- El acceso actual de correo identifica al remitente registrado; las claves de acceso web se gestionaran en P2.
- Coordinacion en correo se limita a sus programas (RF1.3); administracion los asigna con `ASIGNARCOORDINADOR`.
- Guia funcional: [GUIA_CORREO_CASOS_APROBADOS.md](GUIA_CORREO_CASOS_APROBADOS.md).
- La API del Grupo 06 todavia no esta disponible: no se realiza una matriculacion automatica en SIS.

Pruebas y preparacion del entorno aislado: [PRUEBAS.md](PRUEBAS.md).
Resumen verificado: `doc/pruebas/base-compartida.json`.
Documento PUDS y diagramas EA regenerados el 17/11/2026 sobre el modelo vigente: `doc/documento_p1.py` y `doc/ea/ea_p1.py`
(las firmas de las clases y el despacho de comandos se leen del código con `doc/ea/firmas.py`).
