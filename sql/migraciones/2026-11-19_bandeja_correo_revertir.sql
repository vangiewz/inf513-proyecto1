-- Revierte 2026-11-19_bandeja_correo.sql. Antes: detener el lector y respaldar.
-- Si quedaron correos 'Recibido' sin procesar o respuestas 'Pendiente', se pierden al revertir:
-- revisar primero con
--   SELECT count(*) FROM log_comandos WHERE resultado='Recibido';
--   SELECT count(*) FROM correos_salientes WHERE estado='Pendiente';
SET search_path TO public;

DROP TABLE public.correos_salientes;
DROP INDEX public.log_comandos_recibidos_idx;
ALTER TABLE public.log_comandos DROP CONSTRAINT log_comandos_message_id_unique;
ALTER TABLE public.log_comandos
    DROP COLUMN message_id,
    DROP COLUMN contenido,
    DROP COLUMN bytes,
    DROP COLUMN procesado_en;
