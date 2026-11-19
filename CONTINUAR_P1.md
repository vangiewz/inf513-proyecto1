# Continuidad de P1 correo — 19/11/2026

Leer este archivo antes de retomar. El trabajo actual es P1 correo, con los ocho CU aprobados.
P2 se continuará después sobre la misma base y registros; no modificar `crm-web/` durante una tarea de correo.
No dar por terminada la entrega a partir de pruebas locales ni afirmar que el sistema es infalible.

## Publicación y acceso al repositorio

El usuario autorizó subir esta revisión a GitHub el 19/11/2026. Destino: rama `main` de
`https://github.com/vangiewz/inf513-proyecto1`. La API de GitHub confirmó que el repositorio es **privado**;
un compañero necesita acceso como colaborador para clonarlo. El 404 de la consulta anónima no era prueba
de que el repositorio no existiera. La publicación del código no aplica las migraciones a Tecnoweb.
Consultar el historial y el remoto para comprobar el commit publicado; el acceso público exigido para la
entrega sigue pendiente mientras la visibilidad sea privada. Las notas de auditoría que indican que no se
había hecho push describen el estado previo a esta autorización.

## Fuentes y orden de lectura

1. `../AGENTS.md`, `AGENTS.md`, `../CONTEXTO_CURSO.md` y `../CLASES_DOCENTE.md`.
2. `../GUIA_DOCUMENTO_PUDS.md`, `../GUIA_INFORMES.md`, `../ANALISIS_REFERENCIAS.md`.
3. `AUDITORIA_ENUNCIADO.md`: cotejo del texto de presentación aportado por el usuario el 19/11.
4. `BASE_DATOS.md`, `PRUEBAS.md`, `REVISION_DOCUMENTO.md` y los JSON de `doc/pruebas/`.

Los documentos aprobados son los del Grupo 17 de Bulacia, en `../referencias/aprobado/`.
Nuestras entregas anteriores no tienen nota y no prueban aceptación del docente.

## Decisión del usuario: diagramas por CU y vistas globales

“Uno por caso de uso” significa **uno de cada tipo correspondiente por CU**, no un solo dibujo que
mezcle todas las notaciones, ni ocho diagramas en total para el proyecto.

| Vista | Organización que se debe conservar |
|---|---|
| Detalle de casos de uso | Un diagrama de detalle para cada CU1–CU8, con su especificación, subflujos y excepciones |
| Secuencias de CU | Una secuencia completa por CU1–CU8; incluir sus comandos y variantes pertinentes |
| Colaboración de CU | Una colaboración por CU1–CU8, coherente con sus mensajes y servicios reales |
| Diseño procedimental de CU | Un diagrama de clases completo por CU1–CU8, incluyendo clases compartidas y miembros visibles |
| Dominio inicial | Una vista global de entidades de los requisitos, sin atributos ni métodos |
| Casos de uso general | Una vista global de actores y los ocho CU aprobados |
| Conceptual / dominio final | Una vista global completa; entidades, atributos esenciales y relaciones, sin tipos ni métodos |
| Modelo lógico | Una vista global completa con todas las tablas; atributos/tipos y asociaciones FK con multiplicidades |
| Diseño físico | Mapeo de tablas, PK/FK/restricciones y SQL de instalación; no inventar otro diagrama decorativo |
| Canal de correo | Un procedimental global con sus clases completas |
| Arquitectura | Vistas globales de capas, despliegue y componentes; cada una con su notación y finalidad |
| Paquetes | Vistas de paquetes, encapsulación, trazabilidad y dependencias según el PUDS, conservando su finalidad |

Las cuatro secuencias comunes de recibir, procesar, enviar y leer rebotes son interacciones del canal,
no partes adicionales de un CU. Se conservan como apoyo y no sustituyen las ocho secuencias de CU.

No volver a añadir las seis vistas de detalle de CU6 que se retiraron por indicación del usuario.
No dividir los datos por áreas ni plegar atributos/métodos para aparentar que el modelo entra en una hoja.
Las láminas ampliadas conservan **cada figura completa**, no son fragmentos de un mismo diagrama.

**Comparación concreta:** el P1 aprobado muestra el modelo global de datos en Figura 34, página 58;
su P2, en Figura 55, página 68. Después presentan mapeo/SQL. Esta es la referencia de organización;
nuestro dominio, tablas y componentes deben salir de nuestro sistema, no copiar nombres del ejemplo.
La separación de vistas conceptual/lógica se fundamenta además en las clases del docente.

## Reglas de BD y UML que no se deben deshacer

- Destino único: `db.tecnoweb.org.bo:5432`, base `db_grupo05sa`, esquema `public`.
  `db_grupo05sa` es la base, `public` es el esquema. No recrear `crm` ni `crm_compartido`.
- No crear otra base de producción para correo. Los clústeres locales de QA fueron desechables.
- Una PK por tabla con el nombre del diseño; no introducir un campo genérico `id`.
  Respetar claves naturales, correlativos con nombre y la PK compuesta de `orden_pago_descuentos`.
- Ninguna tabla aislada. Las FK deben existir en PostgreSQL y coincidir con el modelo EA.
- Una FK que también es PK simple limita el dependiente a **0..1**, como en pagos/comisiones/inscripciones;
  no dibujarla como 0..* ni confundir la obligatoriedad del padre con la existencia obligatoria del hijo.
- La N–N órdenes/descuentos es una clase de asociación UML real, con su enlace punteado y campos propios.
- `programas(codigo,nombre)` separa el nombre del programa; `catalogo_cache` conserva los módulos y su FK.
  No eliminar la oferta ni los nombres/precios históricos de intereses y solicitudes.
- Los totales derivados y las instantáneas históricas se explican por su significado. No afirmar 3FN total
  sin analizar las dependencias funcionales de todas las claves candidatas.
- Usar Enterprise Architect real por COM (`EA.Repository`, EA 15 Trial instalado). No sustituirlo por
  dibujos que imiten EA ni versiones no autorizadas. Usar elementos UML reales, no cajas dibujadas con texto.
- Hosts, puertos, artefactos, clases, métodos y relaciones deben existir. La arquitectura de componentes
  mejorada usa ensamblajes con una sola pareja bola/socket; no duplicar esos símbolos en los extremos.

## Estado comprobado y límites de la evidencia

- Esquema propuesto: **25 tablas, 209 columnas y 34 FK**. No equivale a la estructura ya aplicada en Tecnoweb.
- Suite completa local: **144 pruebas aprobadas**, sin fallas, errores u omisiones. Después se añadió una
  prueba de parser y se ejecutó su clase: siete casos aprobados; los XML actuales suman 145. No confundir
  ese agregado con otra ejecución completa de 145 pruebas.
- Catálogo: se probaron migración, reversión, rechazo transaccional de conflictos y equivalencia de datos de
  referencia con la instalación nueva. La prueba comercial verifica que renombrar un programa no cambie
  el nombre histórico de un interés. No afirmar que se migraron datos reales de producción.
- Modelos: `doc/pruebas/modelos-globales.json` registra 28 comprobaciones contra PostgreSQL efímero real.
- Segunda auditoría: `doc/pruebas/auditoria-ampliada.json`, **54 comprobaciones aprobadas** de modelos y
  estructura, con PK, ausencia de `id`, vigencia, catálogo normalizado, ocho colaboraciones, vistas globales,
  referencias repetibles y rechazo del instalador sobre esquema ocupado. Resumen: `AUDITORIA_ADICIONAL.md`.
- `doc/pruebas/caidas.json` conserva la prueba anterior a normalizar: 60 cierres del lector y 12 caídas de BD.
  No presentarla como una repetición con el JAR normalizado.
- Documento auditado: 150 páginas, 78 figuras, 53 tablas, índices sin discrepancias; 181 atributos lógicos
  encontrados en el PDF. Las cifras son una fotografía: después de cambios, regenerar el informe de revisión.
- Las 33 láminas UML ampliadas son vectoriales, con letra mínima comprobada de 8 pt. En carta, algunas
  figuras completas siguen teniendo letra pequeña; no prometer legibilidad impresa total del informe principal.

## Pendientes, en orden

1. **Servidor:** la última consulta local del 19/11 no pudo resolver `db.tecnoweb.org.bo`. Repetir DNS y
   conectividad; esto por sí solo no demuestra bloqueo de IP ni caída de PostgreSQL. Evidencia: `conectividad-bd.json`.
2. **Antes de migrar:** identificar el lector activo y detenerlo, comprobar un respaldo recuperable de la base
   vigente, inspeccionar su versión real y los datos que pudieran impedir las migraciones. Evitar dos lectores.
3. **Aplicar sobre la base existente**, con transacciones y `ON_ERROR_STOP`, las migraciones pendientes:
   `sql/migraciones/2026-11-19_bandeja_correo.sql` y `2026-11-19_normalizar_catalogo.sql`.
   **No ejecutar `sql/esquema.sql` sobre producción ni reemplazar sus registros con referencias simuladas.**
   Ambas tienen scripts de reversión; la reversión también exige volver al JAR compatible.
4. **Desplegar y verificar:** diagnóstico del JAR nuevo, estructura/permisos, pruebas de integración en QA
   temporal del servidor y correos reales desde Gmail con roles y casos representativos, incluidos adjuntos.
   Limpiar solo el QA propio al terminar. Los tests llamados “ServidorCompartido” también se ejecutaron
   localmente; eso no acredita su ejecución nueva en el servidor remoto.
5. **Operación:** el lector actual está previsto en la PC del grupo. Confirmar cómo estará funcionando durante
   la defensa y el requisito de ejecución en servidor. No activar el servicio de respaldo del VPS sin coordinar
   el lector existente y las migraciones.
6. **P2:** antes de usar web sobre el esquema normalizado, adaptar consultas/escrituras a la columna retirada
   `catalogo_cache.programa_nombre`; obtener el nombre desde `programas`. Mantener base y registros compartidos.
   Esta tarea no autorizó modificar ni publicar `crm-web/`.
7. **Límites por resolver o defender:** identificación por `From` sin autenticidad fuerte; POP3 110 sin TLS;
   respuesta diferida cuando la BD cae antes de leer; SMTP incierto puede repetir una respuesta; API SIS aún
   pendiente. No equiparar ausencia de duplicación comercial con entrega de correo exactamente una vez.
8. **GitHub:** se publica esta revisión por instrucción del usuario. La API confirmó visibilidad privada;
   comprobar el commit remoto y el acceso del compañero. Para la entrega sigue pendiente el acceso público.
   Se revisaron los archivos a publicar frente a 14 valores privados y no hubo coincidencias; no incluir
   configuración real en futuros commits.
9. **Entrega:** regenerar ZIP tras cambios finales, verificar fecha vigente del aula y subirlo; imprimir el PUDS
   y preparar defensa/desafío en vivo de los tres integrantes. El ZIP preparado no significa entrega realizada.

## Archivos y herramientas para continuar

### POP3, SMTP e IMAP: aclaración solicitada por el usuario

Los comandos se reciben por **POP3 con sockets propios** desde el buzón de Tecnoweb. Las respuestas salen
por **SMTP de Gmail**. **IMAP de Gmail solo vigila rebotes** de respuestas; no sustituye la lectura POP3 de
comandos ni requiere que Dovecot de Tecnoweb tenga IMAP habilitado.

No se encontró una prohibición general de IMAP en el material revisado. `CLASES_DOCENTE.md` §5 describe
POP3 para P1 y permite elegir tecnología; §7 distingue una configuración de Dovecot solo POP3 de la
práctica posterior de webmail con IMAP. El texto de presentación facilitado por el usuario el 19/11
admite IMAP/POP3/SMTP según el servidor. El P1 aprobado utiliza POP3 para leer y JavaMail para responder;
su recomendación sobre IMAP IDLE no equivale a que lo use en su flujo aprobado.
No afirmar que el docente prohibió IMAP ni que aprobó específicamente nuestra vigilancia de rebotes.
Si aparece una instrucción nueva y explícita, contrastarla antes de modificar este flujo.

| Qué | Dónde |
|---|---|
| Aplicación y pruebas | `src/main/java/`, `src/test/java/`, `pom.xml` |
| Instalador solo para BD nueva / referencias simuladas | `sql/esquema.sql`, `sql/referencias.sql` |
| Migraciones para BD existente | `sql/migraciones/` y `BASE_DATOS.md` |
| Modelo editable EA / generador | `doc/ea/crm-p1.eapx`, `doc/ea/ea_p1.py`, `doc/ea/firmas.py` |
| Documento fuente | `doc/documento_p1.py` y `../tools/doc_ficct.py` |
| Exportar en Word real | `scripts/exportar_documento.ps1` |
| Cotejar modelos contra PostgreSQL efímero | `scripts/auditar_modelos.py` |
| Probar catálogo y suite completa local | `scripts/probar_catalogo.py --sql-anterior RUTA_SQL_ANTERIOR` |
| Prueba de caídas con JAR y BD desechable | `scripts/prueba_caidas.py`; ver parámetros y precauciones en `PRUEBAS.md` |
| Láminas completas ampliadas | `scripts/laminas_uml.py` |
| Preparar ZIP por lista permitida | `scripts/preparar_entrega.py` |
| Entregables locales | `doc/P1-INF513-GRUPO05SA.doc`, `.docx`, `.pdf`; `doc/P1-LAMINAS-UML-GRUPO05SA.pdf` |
| Paquete preparado | `../entrega/2026-2_INF513-P1_grupo05sa.zip`, sin configuración privada |

El auditor de catálogo necesita una versión anterior del SQL; no sustituirla por el instalador normalizado.
Hay una copia local de apoyo en `C:/Users/rekte/AppData/Local/Temp/p1-normalizar-catalogo-20261119-034007/sql/`.
Es temporal, no una fuente durable ni un respaldo de producción. Revisar antes de depender de ella.

Para regenerar: respaldar artefactos, regenerar EA (el generador completo reemplaza su modelo e imágenes),
ejecutar el cotejo de modelos, generar DOCX, exportar DOC/PDF con Word, comprobar índices y páginas/figuras,
regenerar láminas, actualizar `revision-documental.json` y al final preparar ZIP. No editar solo el PNG/PDF
dejando desactualizados EA o el generador. Evitar exportaciones simultáneas sobre el mismo archivo.

Desde un clon independiente se puede ejecutar `python doc/documento_p1.py`: se incluyen `doc/lib/doc_ficct.py`
y `doc/recursos/logo-ficct.png` como respaldo de la herramienta común y su logo. El nombre de la carpeta del
clon no tiene que ser `p1`. Las referencias del curso fuera de este repositorio pertenecen al workspace del
grupo; este documento resume sus decisiones, pero no incluye todo el material docente. Para regenerar UML
se requiere EA instalado; para exportar DOC/PDF se requiere Word. No confundir esas dependencias con las de Java.
`doc/pruebas/junit-resumen.json` permite reproducir la tabla histórica de resultados si el clon no tiene XML
en `target/surefire-reports/`; el documento lo identifica como evidencia conservada, no como una prueba nueva.

Las credenciales reales están exclusivamente en `../.secrets/credenciales.md`; no copiarlas a este archivo,
capturas, documentación, ZIP ni commits. No incluir bases/dumps reales, configuración privada ni claves.

Una eliminación de QA fue bloqueada por revisión automática. El clúster quedó detenido y su carpeta conservada:
`C:/Users/rekte/AppData/Local/Temp/p1-modelos-qa-li35907n/cluster`. No reintentar eliminarla por otra vía para
sortear el bloqueo ni confundirla con PostgreSQL que pudiera estar usando otra sesión.
