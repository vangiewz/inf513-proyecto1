-- Quita columnas redundantes (clase 46: "si hay un campo de más en una tabla ya está mal"; 3FN).
-- Destino: db_grupo05sa.public. Aplicar DESPUÉS de 2026-11-19_normalizar_catalogo.sql.
-- Detener el lector y respaldar antes. Ejecutar con psql -X -v ON_ERROR_STOP=1 --single-transaction -f ESTE_ARCHIVO.
--   ordenes_pago.estado              = pagos.estado (pago 1 a 1 con su orden; P1 y P2 los escriben juntos)
--   pagos.monto                      = ordenes_pago.total
--   pagos.fecha_generacion           = ordenes_pago.fecha_generacion
--   inscripciones_comerciales.estado  solo admite 'Confirmada' (constante: la fila existe = confirmada)
--   exportaciones.documento          se obtiene de nro_solicitud → solicitudes_compra → ordenes_pago → intereses
--   solicitudes_compra.estado        'confirmada' si y solo si existe su inscripcion_comercial (P1 y P2 los escriben juntos)
-- Antes de borrar comprueba con los datos que de verdad coinciden; si una fila difiere, no toca nada.
SET client_encoding = 'UTF8';
LOCK TABLE public.ordenes_pago, public.pagos, public.inscripciones_comerciales, public.exportaciones IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public'
                 AND table_name='ordenes_pago' AND column_name='estado') THEN
    RAISE EXCEPTION 'ordenes_pago.estado ya no existe; esta migracion ya se aplico';
  END IF;
  IF EXISTS (SELECT 1 FROM public.ordenes_pago o JOIN public.pagos p ON p.nro_orden=o.nro_orden
             WHERE o.estado IS DISTINCT FROM p.estado OR o.total IS DISTINCT FROM p.monto
                OR o.fecha_generacion IS DISTINCT FROM p.fecha_generacion) THEN
    RAISE EXCEPTION 'Hay ordenes cuyo estado, total o fecha difieren de su pago; revisar antes de quitar columnas';
  END IF;
  IF EXISTS (SELECT 1 FROM public.ordenes_pago o WHERE NOT EXISTS (SELECT 1 FROM public.pagos p WHERE p.nro_orden=o.nro_orden)) THEN
    RAISE EXCEPTION 'Hay ordenes sin pago; su estado se perderia';
  END IF;
  IF EXISTS (SELECT 1 FROM public.exportaciones e
             JOIN public.solicitudes_compra sc ON sc.nro_solicitud=e.nro_solicitud
             JOIN public.ordenes_pago o ON o.nro_orden=sc.nro_orden
             JOIN public.intereses i ON i.nro_tarjeta=o.nro_tarjeta
             WHERE e.documento IS DISTINCT FROM i.documento AND e.documento IS NOT NULL) THEN
    RAISE EXCEPTION 'Hay exportaciones cuyo documento no es el del prospecto de su solicitud';
  END IF;
  IF EXISTS (SELECT 1 FROM public.solicitudes_compra sc
             WHERE (sc.estado='confirmada') <> EXISTS (SELECT 1 FROM public.inscripciones_comerciales ic
                                                       WHERE ic.nro_solicitud=sc.nro_solicitud)) THEN
    RAISE EXCEPTION 'Hay solicitudes cuyo estado no coincide con la existencia de su inscripcion';
  END IF;
END $$;
ALTER TABLE public.ordenes_pago DROP COLUMN estado;
DROP INDEX IF EXISTS public.pagos_estado_fecha_idx;
ALTER TABLE public.pagos DROP COLUMN monto, DROP COLUMN fecha_generacion;
CREATE INDEX pagos_estado_idx ON public.pagos USING btree (estado);
ALTER TABLE public.inscripciones_comerciales DROP COLUMN estado;
ALTER TABLE public.exportaciones DROP COLUMN documento;
ALTER TABLE public.solicitudes_compra DROP COLUMN estado;
