-- Retira la bandeja persistente (2026-11-19_bandeja_correo.sql, aplicada en el servidor el 19/11/2026 22:50).
-- El lector actual atiende y responde cada correo antes de su DELE y no usa estas columnas ni correos_salientes:
-- dejarlas sería "un campo de más" frente al documento (clase 46).
-- Destino: db_grupo05sa.public. Detener el lector y respaldar antes (.\respaldo.ps1).
-- Ejecutar con psql -X -v ON_ERROR_STOP=1 --single-transaction -f ESTE_ARCHIVO.
-- La bitácora (filas de log_comandos) se conserva completa; solo se quitan el correo crudo, su tamaño, la marca de
-- proceso, el Message-ID y la cola de salida, que ya quedan en el respaldo.
-- Solo se rechaza si algo se perdería de verdad: un correo guardado sin procesar o una respuesta sin enviar.
SET client_encoding = 'UTF8';
SET search_path TO public;
LOCK TABLE public.log_comandos, public.correos_salientes IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM public.log_comandos WHERE resultado = 'Recibido') THEN
    RAISE EXCEPTION 'Hay correos guardados sin procesar (resultado Recibido): atenderlos antes de retirar la bandeja';
  END IF;
  IF EXISTS (SELECT 1 FROM public.correos_salientes WHERE estado = 'Pendiente') THEN
    RAISE EXCEPTION 'Hay respuestas pendientes de envio en correos_salientes: enviarlas antes de retirar la bandeja';
  END IF;
END $$;
DROP TABLE public.correos_salientes;
DROP INDEX IF EXISTS public.log_comandos_recibidos_idx;
ALTER TABLE public.log_comandos DROP CONSTRAINT IF EXISTS log_comandos_message_id_unique;
ALTER TABLE public.log_comandos
    DROP COLUMN message_id,
    DROP COLUMN contenido,
    DROP COLUMN bytes,
    DROP COLUMN procesado_en;
