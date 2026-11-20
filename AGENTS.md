# Proyecto 1: correo

- 20/11/2026: el código vuelve a la versión del 17/11 (sin firma HMAC, sin tabla `persona`/LISPER/INSPER, sin bandeja
  persistente ni rebotes por IMAP) + normalización del catálogo (`programas`), logs `access.log`/`error.log` y lector
  cada 10 s. Lo de Codex quedó en la etiqueta git `codex-20-11`. Contra el servidor: 3,2 s como mínimo entre conexiones.
- El servidor ya tiene la bandeja y el catálogo (aplicados el 19/11 22:50). Pendiente allí: `2026-11-20_retirar_bandeja.sql`
  y `2026-11-20_quitar_redundancias.sql` (ver `BASE_DATOS.md`). No subir nada al servidor sin OK explícito del usuario.
- Decisiones de diagramas: uno de cada tipo por CU (detalle, secuencia, colaboración, procedimental) y vistas globales
  (dominio, CU general, conceptual, lógico, capas, despliegue, componentes). Diagramas solo cuando el usuario lo pida.

- La prioridad actual es terminar P1 correo y sus ocho casos de uso aprobados por el docente.
- P2 web sigue en desarrollo. Ambos usaran la misma base y registros; se comenzo por web, pero ahora se termina correo primero.
- Destino vigente: PostgreSQL `db.tecnoweb.org.bo:5432`, base `db_grupo05sa`, esquema `public`.
- No crear una segunda base de produccion para correo ni recrear los esquemas retirados `crm` y `crm_compartido`.
- No modificar web como parte de una tarea de correo. P2 se continuara despues sobre esta base y podra ampliarse.
- Estructura y referencias: `sql/esquema.sql`, `sql/referencias.sql`; instrucciones en `BASE_DATOS.md`.
- Las credenciales reales solo se conservan en `.secrets/credenciales.md` del workspace privado y nunca se publican.
- Conservar codigo, pruebas y documentacion utiles; no agregar copias de frameworks, respaldos, datos personales ni resultados temporales.
