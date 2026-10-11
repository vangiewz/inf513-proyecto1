# CRM de captación de prospectos vía e-mail

Proyecto 1 de INF513 Tecnología Web (SA), UAGRM FICCT, gestión 2-2026. Grupo 05 SA:
Lobo Gutierrez Carlos Gabriel, Serrate Basma Leonardo y Vasquez Ardaya Brandon Lider.

El usuario escribe un comando en el **asunto** de un correo a `grupo05sa@tecnoweb.org.bo`, por ejemplo
`VERTABLERO[]` o `MOVERTARJETA["7","En Contacto"]`. El sistema lee el buzón por POP3, identifica al remitente por su
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
| Base de datos | PostgreSQL 18.6 `db.tecnoweb.org.bo:5432`, BD `db_grupo05sa`, esquema `crm` |
| Ejecución | VPS Fedora 44, OpenJDK 25, servicio `systemd` `crm-mail` (`deploy/crm-mail.service`) |

## Compilar y ejecutar

```sh
mvn package                                   # Java 21+, Maven 3.9; genera target/crm-mail.jar
psql -h db.tecnoweb.org.bo -U grupo05sa -d db_grupo05sa -f sql/crm_p1.sql
cp crm-mail.properties.example crm-mail.properties   # completar las contraseñas (no se versiona)
java -jar target/crm-mail.jar crm-mail.properties
```

Prueba sin correo: `java -jar target/crm-mail.jar --probar crm-mail.properties remitente@gmail.com "VERTABLERO[]"`.
