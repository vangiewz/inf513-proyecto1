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
| Ejecución | VPS Fedora 44, OpenJDK 25, servicio `systemd` `crm-mail` (`deploy/crm-mail.service`) |

## Compilar y ejecutar

```sh
mvn package                                   # Java 21+, Maven 3.9; genera target/crm-mail.jar

# Consultar el modelo copiado y su estado instalado (ver BASE_COMPARTIDA.md).
# No ejecutar sql/crm_p1.sql: es el instalador historico del modelo anterior.
cp crm-mail.properties.example crm-mail.properties   # completar las contraseñas (no se versiona)
java -jar target/crm-mail.jar crm-mail.properties
```

Prueba sin correo: `java -jar target/crm-mail.jar --probar crm-mail.properties remitente@gmail.com "VERTABLERO[]"`.

## Base comun con web (17/11/2026)

La version actual consulta directamente las tablas Laravel: `usuarios`, `prospectos`,
`intereses`, `interacciones`, `recordatorios` y las ventas normalizadas. Ambos clientes
usan el mismo esquema de despliegue `public` en `db_grupo05sa`.
La configuracion y la referencia del modelo usado estan en [BASE_COMPARTIDA.md](BASE_COMPARTIDA.md).
Correo y web son proyectos separados: este trabajo adapta correo al modelo de datos web.
Los cambios no solicitados en el codigo web fueron revertidos; la copia de la estructura para correo esta en `sql/modelo-referencia/`.

- El vendedor no se autoasigna ni ve la bolsa; administracion/coordinacion distribuyen por menor carga.
- Se aceptan los alias En Contacto y Seguimiento; el tablero muestra las seis etapas aprobadas y los pagos pueden gestionarse por correo.
- CU6 permite generar ordenes, adjuntar comprobantes reales, revisar pagos y confirmar inscripciones sin abrir web.
- La conversion consulta la inscripcion confirmada, sin duplicarla; la exportacion CSV prepara el intercambio con SIS.
- `COMPLETARRECORDATORIO["id","resultado"]` exige una nota de cumplimiento.
- El catalogo externo sigue simulado: sincronizar con `php artisan catalogo:sincronizar-correo`.
- Los usuarios nuevos de correo necesitan que administracion les asigne una clave para entrar por web.
- Coordinacion en correo se limita a sus programas (RF1.3); administracion los asigna con `ASIGNARCOORDINADOR`.
- Guia funcional: [GUIA_CORREO_CASOS_APROBADOS.md](GUIA_CORREO_CASOS_APROBADOS.md).
- La API del Grupo 06 todavia no esta disponible: no se realiza una matriculacion automatica en SIS.

Pruebas y preparacion del entorno aislado: [PRUEBAS.md](PRUEBAS.md).
Resumen verificado: `doc/pruebas/base-compartida.json`.
El documento y los diagramas academicos anteriores corresponden al modelo historico.
