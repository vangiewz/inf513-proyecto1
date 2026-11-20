-- Separar el programa de sus modulos sin cambiar codigos ni registros comerciales.
-- Destino: db_grupo05sa.public. Detener el lector y respaldar antes de aplicar.
-- Ejecutar con psql -X -v ON_ERROR_STOP=1 --single-transaction -f ESTE_ARCHIVO.
-- 3FN: el nombre del programa deja de repetirse en cada modulo (dependencia transitiva programa_codigo -> nombre).
SET client_encoding = 'UTF8';
LOCK TABLE public.catalogo_cache, public.programas_responsables IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
  IF to_regclass('public.programas') IS NOT NULL THEN
    RAISE EXCEPTION 'programas ya existe; no se repite ni se mezcla esta migracion';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public'
                 AND table_name='catalogo_cache' AND column_name='programa_nombre') THEN
    RAISE EXCEPTION 'Falta catalogo_cache.programa_nombre; revisar la version antes de migrar';
  END IF;
  IF EXISTS (SELECT 1 FROM public.catalogo_cache WHERE btrim(programa_codigo)='' OR btrim(programa_nombre)='') THEN
    RAISE EXCEPTION 'Hay programas con codigo o nombre vacio; no se inventan ni descartan datos';
  END IF;
  IF EXISTS (SELECT programa_codigo FROM public.catalogo_cache GROUP BY programa_codigo
             HAVING count(DISTINCT programa_nombre)<>1) THEN
    RAISE EXCEPTION 'Un codigo de programa tiene nombres distintos; resolver antes de normalizar';
  END IF;
  IF EXISTS (SELECT 1 FROM public.programas_responsables r WHERE NOT EXISTS
             (SELECT 1 FROM public.catalogo_cache c WHERE c.programa_codigo=r.programa_codigo)) THEN
    RAISE EXCEPTION 'Hay responsables de programas sin oferta; no se elimina ni se inventa el programa';
  END IF;
END $$;
CREATE TABLE public.programas (
    codigo character varying(255) NOT NULL,
    nombre character varying(255) NOT NULL,
    CONSTRAINT programas_pkey PRIMARY KEY (codigo)
);
INSERT INTO public.programas(codigo,nombre)
SELECT programa_codigo,min(programa_nombre) FROM public.catalogo_cache GROUP BY programa_codigo;
ALTER TABLE public.catalogo_cache ADD CONSTRAINT catalogo_cache_programa_codigo_fk
  FOREIGN KEY (programa_codigo) REFERENCES public.programas(codigo) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.programas_responsables ADD CONSTRAINT programas_responsables_programa_codigo_fk
  FOREIGN KEY (programa_codigo) REFERENCES public.programas(codigo) ON UPDATE CASCADE ON DELETE RESTRICT;
-- El nombre ya esta preservado una sola vez en programas; las instantaneas comerciales no se modifican.
ALTER TABLE public.catalogo_cache DROP COLUMN programa_nombre;
COMMENT ON TABLE public.programas IS 'Programas del catalogo: codigo natural y nombre, sin repeticion por modulo';
COMMENT ON TABLE public.catalogo_cache IS 'Modulos del catalogo local; su programa y nombre se consultan en programas';
