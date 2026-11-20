-- Revierte 2026-11-19_bandeja_correo.sql. Antes: detener el lector y respaldar.
-- No elimina información: rechaza la reversión si cualquier columna/tabla nueva conserva datos.
-- No basta con revisar Pendiente: también los enviados y fallidos son evidencia recuperable.
-- Ejecutar exclusivamente con ON_ERROR_STOP=1 y --single-transaction.
SET search_path TO public;

LOCK TABLE public.log_comandos, public.correos_salientes IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM public.correos_salientes)
     OR EXISTS (SELECT 1 FROM public.log_comandos WHERE message_id IS NOT NULL
        OR contenido IS NOT NULL OR bytes IS NOT NULL OR procesado_en IS NOT NULL) THEN
    RAISE EXCEPTION 'Reversión rechazada: la bandeja conserva datos; respaldar y preparar recuperación sin perder mensajes';
  END IF;
END $$;

DROP TABLE public.correos_salientes;
DROP INDEX public.log_comandos_recibidos_idx;
ALTER TABLE public.log_comandos DROP CONSTRAINT log_comandos_message_id_unique;
ALTER TABLE public.log_comandos
    DROP COLUMN message_id,
    DROP COLUMN contenido,
    DROP COLUMN bytes,
    DROP COLUMN procesado_en;
