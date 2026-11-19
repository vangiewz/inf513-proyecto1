-- Reversion sin descartar programas o datos comerciales. Requiere lector detenido y respaldo previo.
-- Ejecutar con psql -X -v ON_ERROR_STOP=1 --single-transaction -f ESTE_ARCHIVO.
SET client_encoding = 'UTF8';
LOCK TABLE public.programas, public.catalogo_cache, public.programas_responsables IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public'
             AND table_name='catalogo_cache' AND column_name='programa_nombre') THEN
    RAISE EXCEPTION 'programa_nombre ya existe; revisar la version antes de revertir';
  END IF;
  IF EXISTS (SELECT 1 FROM public.programas p WHERE NOT EXISTS
             (SELECT 1 FROM public.catalogo_cache c WHERE c.programa_codigo=p.codigo)) THEN
    RAISE EXCEPTION 'Hay programas sin modulos: se conservaran, no se puede revertir sin perderlos';
  END IF;
END $$;
ALTER TABLE public.catalogo_cache ADD COLUMN programa_nombre character varying(255);
UPDATE public.catalogo_cache c SET programa_nombre=p.nombre FROM public.programas p WHERE p.codigo=c.programa_codigo;
ALTER TABLE public.catalogo_cache ALTER COLUMN programa_nombre SET NOT NULL;
ALTER TABLE public.catalogo_cache DROP CONSTRAINT catalogo_cache_programa_codigo_fk;
ALTER TABLE public.programas_responsables DROP CONSTRAINT programas_responsables_programa_codigo_fk;
DROP TABLE public.programas;
COMMENT ON TABLE public.catalogo_cache IS 'Catalogo anterior a la separacion de programas y modulos';
