# Cotejo del enunciado P1 recibido el 19/11/2026

## Actualización del contrato y de la revisión local

La solicitud posterior exige integrar literalmente LISPER e INSPER: ambos están implementados
como extensión de CU1 sobre persona, sin sustituir los comandos CRM. La interpretación anterior
de la tabla siguiente que los trataba sólo como ejemplos queda superada por esa instrucción.
La implementación actual agrega firma HMAC individual, sondeo mínimo de tres segundos, control
de reconexiones, soporte POP3 seguro configurable, contingencia de errores y rebotes correlacionados.
Las pruebas actuales se ejecutan con scripts/probar_local.py; su informe es local-final.json.
El ZIP se prepara y se compila de nuevo desde una extracción mediante verificar_entrega.py.
No se aplican migraciones remotas, se envían correos reales ni se publica esta revisión.

El resto de este archivo conserva el cotejo histórico previo. El estado vigente se resume en
README.md, OPERACION.md, BASE_DATOS.md y CONTINUAR_P1.md; no usar las cifras anteriores como nueva evidencia.

Fuente: texto facilitado por el usuario, “Proyecto #1: Sistema de Automatización y Procesamiento de Datos
Basado en Correo Electrónico”. No se copian sus credenciales de laboratorio a esta auditoría.

| Punto | Evidencia del proyecto | Estado y límite |
|---|---|---|
| Comandos exclusivamente por Subject | Correo, CommandParser, App; ocho CU en GUIA_CORREO_CASOS_APROBADOS.md | Implementado; P1 no necesita abrir web |
| Casilla del grupo y lectura periódica | Configuración de ejemplo, ejecutar.ps1, App | Polling de 10 s en la PC; no equivale a un demonio activo en el servidor de la facultad |
| Parser: comando, corchetes, cantidad y tipos | CommandParserTest, RobustezCorreoTest y validaciones de servicios | Probado localmente; LISPER/INSPER son ejemplos del enunciado, los comandos del CRM tienen sus nombres propios |
| Espacios, UTF-8 y comillas escapadas | CommandParserTest.espaciosUtf8YComillasEscapadasDentroDelParametro, CorreoTest | Comillas literales duplicadas `""`, explicadas en README |
| PostgreSQL y SQL preparado | DatabaseService, servicios de dominio, suites de integración | Probado en PostgreSQL 18.4 efímero local; destino vigente db_grupo05sa.public |
| Ejecución transaccional | DatabaseService.enTransaccion, BandejaCorreoTest, CasosAprobadosCorreoTest | Negocio, bitácora y respuesta pendiente se confirman juntos |
| Respuesta automática de éxito/error | SmtpSender, ResponseFormatter, pruebas POP3/SMTP locales | Probado localmente; falta repetir circuito real con el JAR nuevo y migraciones aplicadas |
| Error de conexión a BD | Diagnostico, App, prueba de caídas | Si falla antes de leer, conserva el correo en buzón y difiere su proceso; no envía error inmediato. Si se exige ese comportamiento inmediato literalmente, este punto queda parcial |
| Remitente original | Correo.remitente usa From | No es prueba de autenticidad ni acceso al MAIL FROM original de SMTP. From puede falsificarse; no declarar autenticación fuerte ni inmunidad a suplantación |
| Excepciones y logs locales | logs/crm-mail.N.log, rotación 5 MB × 5, DiagnosticoTest | Nombres propios de archivos; error.log/access.log se interpretan como ejemplos. Trazas técnicas en log y descripción legible por correo |
| README: arquitectura, BD, instalación y flujo | README.md, BASE_DATOS.md, Mermaid del analizador | Incluido localmente; falta verificar que la revisión final esté publicada |
| GitHub personal público y funcional | Origin: https://github.com/vangiewz/inf513-proyecto1 | Al preparar el push autorizado del 19/11, la API confirmó que existe y es privado. El compañero necesita acceso; hacer público el repositorio para la entrega sigue pendiente. Ver CONTINUAR_P1.md |
| PUDS, carátula, arquitectura, datos y CU | doc/P1-INF513-GRUPO05SA.doc y .pdf, modelo EA .eapx | Revisado y regenerado localmente; las láminas ampliadas conservan diagramas completos |
| MER/modelo de datos y normalización | Conceptual global, lógico global, mapeo, SQL; modelos-globales.json | 25 tablas propuestas, 209 columnas, 34 FK; catálogo con programas separados de módulos |
| Compartir base con P2 | BASE_DATOS.md | Misma base y registros; P2 debe adaptar la columna retirada antes de usar el esquema normalizado. No se modificó crm-web |
| ZIP con nomenclatura exacta | scripts/preparar_entrega.py, doc/pruebas/paquete-entrega.json | Preparado localmente: 2026-2_INF513-P1_grupo05sa.zip; CRC y SHA256 verificados. El pie del texto recibido exige ZIP. No se subió al aula |
| Impresión y defensa 15–20 min | PUDS y láminas; guía funcional de ocho CU | Actividades pendientes del grupo; no se pueden acreditar con pruebas automáticas |
| Entrega antes de las 15:00 | Aula y fecha establecida por docente | El texto no fija fecha concreta. No suponer que la fecha actual sea el vencimiento; revisar el aula. No se entregó al aula |

## Evidencia y despliegue pendiente

`doc/pruebas/catalogo-normalizado.json`: 144 pruebas de la suite completa, todas aprobadas en un clúster
local desechable, más migración, reversión y rechazo seguro de conflictos. Después se añadió y ejecutó
una prueba de parser para el requisito explícito de espacios, UTF-8 y comillas escapadas; el XML de
CommandParserTest conserva su resultado. No se presenta esta ejecución aislada como otra corrida completa.

`doc/pruebas/modelos-globales.json`: 28 comprobaciones de los modelos reales de EA frente a PostgreSQL.
`doc/pruebas/caidas.json` corresponde a la corrida de caídas anterior a normalizar el catálogo; se conserva
sin cambiarla por resultados inventados de la versión nueva.

Las migraciones de bandeja y normalización no se aplicaron a producción. El JAR nuevo detiene la lectura
cuando falta la estructura, para conservar el buzón. Antes de sustituir el JAR en operación: lector detenido,
respaldo comprobado, migraciones en transacción, diagnóstico y circuito real con correo del grupo.
La integración automática con el SIS sigue pendiente de su contrato de API.

Este cotejo no acredita que el sistema esté “blindado ante todo” ni que la entrega ya esté completada.
