# Continuación de la revisión del PUDS — 19/11/2026

Se continuó la revisión documental donde quedó Claude y, por instrucción posterior del usuario, se normalizó
el catálogo en código Java y SQL locales. No se modificaron base de producción, configuración privada ni
`crm-web/`. Las migraciones se prueban en PostgreSQL desechable; no se aplicaron al servidor ni se hizo commit,
push o entrega al aula.
Se conservó una copia previa del directorio `doc/` y de los informes JUnit en una carpeta temporal local.

## Cambios de esta continuación

- `doc/ea/ea_p1.py`: se conserva una secuencia por CU con todos sus comandos. Las seis vistas adicionales del CU6
  se retiraron por indicación del usuario, tanto del generador como del modelo EA y del documento; se conservó
  una copia de la versión anterior en una carpeta temporal local.
- El conceptual y el lógico se reúnen en una vista global completa por nivel. Se reúne el canal de correo en
  una vista global; los ocho procedimentales vuelven a incluir completas las clases compartidas, como el aprobado.
  Se corrige la multiplicidad 1 a 1 de las FK que también son PK simples, sin modificar el esquema.
- Las asociaciones se enrutan en EA cuando atraviesan otras cajas. La intermedia N a N es una clase de asociación
  real, situada junto a la relación para que su línea punteada no atraviese entidades ajenas.
- `doc/documento_p1.py`: conserva una secuencia por CU, aclara la migración pendiente en los datos de revisión y evita
  contar pruebas omitidas como aprobadas. Ajusta los saltos entre índices y la explicación de las vistas de CU6.
- `scripts/exportar_documento.ps1`: exportación específica de P1 con Word real. Genera primero en una carpeta
  temporal, conserva las figuras vectoriales, actualiza los índices después de convertir al formato DOC y solo
  reemplaza los entregables cuando existen ambos archivos. No modifica la herramienta de informes compartida.
- `README.md` y `PRUEBAS.md`: explican la bandeja persistente, la evidencia de caídas y el despliegue pendiente.
- `scripts/auditar_modelos.py`: cotejo reproducible contra PostgreSQL desechable. `scripts/laminas_uml.py`:
  las mismas figuras completas en PDF vectorial ampliado, sin dividir modelos ni casos de uso.
- Implementación de arquitectura: se redistribuyen las cuatro capas, servicios, bibliotecas y sistemas externos
  en EA. Los ensamblajes dibujan una sola pareja bola/socket por conexión. Las etiquetas conservan protocolos
  y puertos reales; las dependencias se muestran por capa y el detalle de métodos queda en los procedimentales.
  La dependencia directa Bandeja → DatabaseService discurre por un corredor libre lateral.

## Evidencia conservada y límites

Los XML originales de 141 pruebas preparados por Claude se respaldaron antes de ejecutar la suite actual.
`doc/pruebas/catalogo-normalizado.json` registra la nueva corrida local completa y la prueba de migración/reversión.
Resultado: **144 pruebas aprobadas, sin errores, fallas ni omisiones**; JAR regenerado con `mvn -q -o package`.
`doc/pruebas/caidas.json` conserva la evidencia anterior a la normalización: 60 cierres del lector, 12 caídas de BD, sin comandos perdidos ni
operaciones duplicadas en ese escenario. Las entregas SMTP inciertas sí produjeron copias de respuestas.

Se revisan las páginas del documento en hojas de contacto y, ampliados, los modelos globales, el CU6, las tablas
con subflujos, la normalización y el resultado de caídas. Las figuras más densas tienen letra pequeña en carta;
el archivo de láminas conserva cada diagrama completo y permite leerlo ampliado. No se garantiza legibilidad
de todos los miembros al imprimir figuras reducidas en carta.

El cotejo de modelos contra PostgreSQL se conserva en `doc/pruebas/modelos-globales.json` y se regenera
después del cambio de catálogo. La estructura propuesta pasa a 25 tablas, 209 columnas y 34 FK.
Los atributos representados y las FK como asociaciones deben coincidir con el esquema instalado en QA.
El conceptual no incorpora tipos ni métodos, la clase de asociación N a N es real y hay una secuencia y un
procedimental completo por CU. Evidencia: `doc/pruebas/modelos-globales.json`. Se retiraron los clústeres de las
auditorías concluidas; la carpeta de una prueba interrumpida quedó detenida y conservada por bloqueo de la
revisión automática de eliminación. No se afectó ningún servicio compartido.

La exportación y sus índices se verifican después de retirar los detalles del CU6. El extracto de logs se muestra
abreviado con `[…]`, sin alterar su archivo de evidencia. El resumen y las huellas de los entregables están en
`doc/pruebas/revision-documental.json`.

## Comparación con el diseño de datos aprobado

El P1 de Bulacia muestra una figura global de clases de datos (Figura 34, página 58), seguida del mapeo y el SQL.
Su P2 hace lo mismo en la Figura 55, página 68. Nuestro conceptual y lógico tienen ahora una vista global por
nivel; no se mezclan ambos ni se omiten tablas. Los modelos general de CU, de dominio inicial, de paquetes,
despliegue, capas y componentes ya eran globales y conservan esa organización.

Se elimina la dependencia transitiva de `catalogo_cache.programa_nombre`: el nombre actual queda una vez
en `programas(codigo,nombre)`; `catalogo_cache` conserva los módulos y su FK al programa. La consulta `Oferta.SQL`
obtiene ese nombre mediante JOIN, manteniendo el mismo contrato de resultados y todos los comandos.
Las instantáneas de intereses y solicitudes se conservan. La migración valida conflictos antes de modificar
la estructura y tiene reversión sin CASCADE. P2 necesita adaptar sus consultas antes de usar el esquema nuevo;
no se modificó su código durante esta tarea de correo.

## Antes del despliegue y la entrega

Las migraciones `2026-11-19_bandeja_correo.sql` y `2026-11-19_normalizar_catalogo.sql` siguen PENDIENTES
DE APLICAR al servidor vigente. El esquema SQL y el documento describen la versión propuesta con 25 tablas.
El JAR nuevo exige ambas estructuras y debe detener el procesamiento sin retirar mensajes cuando falten.
No confundir las pruebas locales con
la validación de esa versión en `db_grupo05sa.public`.

Cuando corresponda, con respaldo y lector detenido, aplicar las migraciones revisadas y verificar diagnóstico,
estructura y correos reales. Después cerrar el paquete actualizado, GitHub, impresión y defensa. La
autenticidad de From y la integración automática con el SIS continúan siendo límites documentados.
