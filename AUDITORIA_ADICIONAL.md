# Segunda auditoría de P1 — 19/11/2026

## Resultado y alcance

**54 comprobaciones aprobadas** en la nueva ejecución de `scripts/auditar_modelos.py --ampliada`.
Se instaló el SQL actual en un PostgreSQL 18 efímero local y se inspeccionó el modelo real de Enterprise
Architect por COM. Se cargaron las referencias dos veces y se comprobó que el instalador rechaza un esquema
ocupado. Se detuvo y eliminó el clúster de esta ejecución. No se modificó la BD de Tecnoweb ni `crm-web/`.

Evidencia: `doc/pruebas/auditoria-ampliada.json`. No se repitió en esta auditoría la suite Java completa;
su resultado anterior sigue siendo 144 casos aprobados, más la prueba adicional posterior del parser.

## Criterios del docente y los ejemplos aprobados

Fuentes: `../CLASES_DOCENTE.md` §2, §4 y §5; `../GUIA_DOCUMENTO_PUDS.md` §3b; los P1/P2 aprobados del
Grupo 17 en `../referencias/aprobado/`. El P1 aprobado presenta el modelo de datos global en Figura 34,
página 58; el P2 en Figura 55, página 68. Esta comparación acredita organización, no aprobación de nuestro CRM.

| Revisión | Resultado |
|---|---|
| Estructura real frente a EA | 25 tablas, 209 columnas y 34 FK; atributos/tipos y multiplicidades del lógico coinciden |
| Tablas relacionadas | Ninguna tabla aislada; todas las FK están validadas en PostgreSQL |
| Claves del diseño | Todas las tablas tienen una PK; no hay columnas genéricas `id` ni `id_...` |
| Relación N–N | Solo orden/descuento tiene PK compuesta; su conceptual usa una clase de asociación UML real |
| Dependientes 1–1 | Pagos, comisiones e inscripciones toman la PK de su dueño y se representan con 0..1 |
| Credenciales con vigencia | Inicio y fin obligatorios y CHECK de fin mayor o igual al inicio |
| Normalización del catálogo | Programa separado del módulo, sin `catalogo_cache.programa_nombre`; dos FK hacia programas |
| SQL frente a Java | La lista de tablas que valida DatabaseService coincide con PostgreSQL |
| Conceptual | Sin tipos, métodos, atributos inventados ni marcas PK/FK; una vista global completa |
| Dominio inicial | Una vista global sin atributos ni métodos |
| Modelo lógico | Una vista global completa con todas las tablas |
| CU1–CU8 | Una secuencia, una colaboración y un procedimental completo por CU; sin miembros plegados |
| Canal común | Cuatro secuencias comunes y un procedimental global, sin reintroducir detalles adicionales de CU6 |
| Implementación | Una vista global, cuatro capas y ensamblajes sin interfaces expuestas duplicadas |
| Referencias simuladas | Repetir la carga conserva 5 programas y 25 módulos; no crea usuarios ni prospectos |
| Instalador | Rechaza el esquema ocupado; se reserva para bases nuevas |

La separación programas/módulos corrige esa dependencia transitiva del catálogo. Este control no demuestra
por sí solo que toda tabla esté en 3FN: las instantáneas comerciales, claves candidatas y totales conservan
la explicación del PUDS. Tampoco es una prueba exhaustiva de todas las reglas de negocio ni de carga.

## Documento, funcionamiento y límites pendientes

- Se conserva la revisión visual y de índices del PDF final: 150 páginas, 78 figuras y 53 tablas, sin páginas
  vacías ni discrepancias en índices. Los 181 atributos lógicos se encontraron en el PDF. Las 33 láminas
  ampliadas conservan cada diagrama completo. Algunas figuras siguen teniendo letra pequeña en carta;
  no confundir una lámina ampliada con garantía de legibilidad de todo el informe impreso en carta.
- No se detectó una infracción nueva en los criterios estructurales comprobados. No equivale a un visto
  bueno del docente ni a declarar el sistema “perfecto” o “blindado ante todo”.
- Quedan las migraciones de bandeja y catálogo en producción, diagnóstico y circuito real de correo con el
  JAR actualizado. La última consulta de conectividad no resolvió el host de BD; no prueba bloqueo de IP.
- P2 debe adaptar sus consultas antes de usar la columna retirada; no se cambió su código.
- Persisten los límites de autenticidad de From, POP3 sin TLS, respuesta diferida si la BD cae antes de leer,
  copias posibles de una respuesta ante entrega SMTP incierta y API SIS pendiente. La evidencia de caídas
  anterior a la normalización no se presenta como una nueva corrida del JAR actual.
- Se aclaró POP3/SMTP/IMAP en `CONTINUAR_P1.md`: POP3 recibe comandos, SMTP responde, IMAP de Gmail
  vigila rebotes. No se halló una prohibición general de IMAP en las fuentes revisadas.
- GitHub público, publicación, fecha del aula, subida del ZIP, impresión y defensa siguen pendientes.

Para continuar, empezar por `CONTINUAR_P1.md`; contiene el orden de trabajo, los archivos y las restricciones.
