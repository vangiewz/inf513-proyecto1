-- Claves de diseño y catálogo conectado (17/11/2026), aplicada en db_grupo05sa.public.
-- Clase 46 del docente: ninguna tabla sin relación y sin un "id" extra cuando la tabla ya tiene su clave.
--   1. catalogo_cache: la clave es codigo (se quita id y su secuencia).
--   2. orden_pago_descuentos: intermedia N-N con clave (orden_pago_id, descuento_id); conserva porcentaje y monto.
--   3. intereses y solicitudes_compra referencian el módulo del catálogo.
-- Respaldo previo: .secrets/respaldos/public_antes_fk_catalogo_20261117.dump (fuera del repositorio).
-- P2 web debe replicar este cambio con una migración de Laravel.

BEGIN;

ALTER TABLE public.catalogo_cache DROP CONSTRAINT catalogo_cache_pkey;
ALTER TABLE public.catalogo_cache DROP CONSTRAINT catalogo_cache_codigo_unique;
ALTER TABLE public.catalogo_cache DROP COLUMN id;  -- la secuencia catalogo_cache_id_seq es OWNED BY: se elimina con la columna
ALTER TABLE public.catalogo_cache ADD CONSTRAINT catalogo_cache_pkey PRIMARY KEY (codigo);

ALTER TABLE public.orden_pago_descuentos DROP CONSTRAINT orden_pago_descuentos_pkey;
ALTER TABLE public.orden_pago_descuentos DROP COLUMN id;
ALTER TABLE public.orden_pago_descuentos
    ADD CONSTRAINT orden_pago_descuentos_pkey PRIMARY KEY (orden_pago_id, descuento_id);

ALTER TABLE public.intereses
    ADD CONSTRAINT intereses_external_modulo_id_foreign FOREIGN KEY (external_modulo_id)
    REFERENCES public.catalogo_cache(codigo) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.solicitudes_compra
    ADD CONSTRAINT solicitudes_compra_external_modulo_id_foreign FOREIGN KEY (external_modulo_id)
    REFERENCES public.catalogo_cache(codigo) ON UPDATE CASCADE ON DELETE RESTRICT;

COMMIT;
