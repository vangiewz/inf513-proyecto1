-- Migración incremental y no destructiva. NO aplicada a producción.
-- Detener lector, comprobar respaldo y diagnóstico de sql/diagnostico_estructura.sql.
-- Aplicación posterior autorizada: psql -X -v ON_ERROR_STOP=1 --single-transaction -f ESTE_ARCHIVO.
-- Las sentencias DDL están en un único bloque atómico; no crea usuarios ni registros persona.
-- Reaplicación: sólo acepta exactamente las siete columnas y restricciones esperadas.
SET client_encoding = 'UTF8';
DO $persona$
DECLARE
  contrato_nombre text := 'p1_persona_contrato_' || replace(gen_random_uuid()::text,'-','');
  contrato regclass;
BEGIN
  IF to_regclass('public.configuraciones') IS NULL THEN
    RAISE EXCEPTION 'Falta configuraciones; inspeccione la base existente antes de migrar';
  END IF;
  IF EXISTS (SELECT 1 FROM public.configuraciones WHERE clave='schema.persona.version' AND valor<>'1') THEN
    RAISE EXCEPTION 'Versión persona desconocida; no se sobrescribe una versión posterior ni sus datos';
  END IF;
  IF to_regclass('public.persona') IS NULL THEN
    -- Registro docente independiente: no concede acceso al CRM ni inventa relaciones comerciales.
    CREATE TABLE public.persona (
        documento character varying(15) NOT NULL,
        nombres character varying(120) NOT NULL,
        apellidos character varying(120) NOT NULL,
        ocupacion character varying(80) NOT NULL,
        telefono_fijo character varying(16) NOT NULL,
        telefono_celular character varying(16) NOT NULL,
        correo character varying(120) NOT NULL,
        CONSTRAINT persona_pkey PRIMARY KEY (documento),
        CONSTRAINT persona_documento_check CHECK (documento ~ '^[0-9]{5,10}(-?[0-9A-Z]{1,3})?$'),
        CONSTRAINT persona_nombres_check CHECK (btrim(nombres) <> '' AND nombres !~ '[[:cntrl:]]'),
        CONSTRAINT persona_apellidos_check CHECK (btrim(apellidos) <> '' AND apellidos !~ '[[:cntrl:]]'),
        CONSTRAINT persona_ocupacion_check CHECK (btrim(ocupacion) <> '' AND ocupacion !~ '[[:cntrl:]]'),
        CONSTRAINT persona_fijo_check CHECK (telefono_fijo ~ '^\+?[0-9]{7,15}$'),
        CONSTRAINT persona_celular_check CHECK (telefono_celular ~ '^\+?[0-9]{7,15}$'),
        CONSTRAINT persona_correo_check CHECK (correo ~ '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
    );
    CREATE UNIQUE INDEX persona_correo_lower_unique ON public.persona (lower(correo));
    COMMENT ON TABLE public.persona IS 'Registro independiente de los siete campos docentes LISPER/INSPER; no es usuario ni prospecto del CRM';
  ELSE

    -- PostgreSQL interpreta el contrato con su propia versión: comparar expresiones canónicas
    -- evita aceptar CHECK(true) con los nombres correctos y no depende del formato de pg_dump.
    -- La temporal vive sólo en esta transacción; no agrega tablas ni datos persistentes.
    EXECUTE format($contrato$
      CREATE TEMP TABLE %I (
        documento character varying(15) NOT NULL,
        nombres character varying(120) NOT NULL,
        apellidos character varying(120) NOT NULL,
        ocupacion character varying(80) NOT NULL,
        telefono_fijo character varying(16) NOT NULL,
        telefono_celular character varying(16) NOT NULL,
        correo character varying(120) NOT NULL,
        CONSTRAINT persona_documento_check CHECK (documento ~ '^[0-9]{5,10}(-?[0-9A-Z]{1,3})?$'),
        CONSTRAINT persona_nombres_check CHECK (btrim(nombres) <> '' AND nombres !~ '[[:cntrl:]]'),
        CONSTRAINT persona_apellidos_check CHECK (btrim(apellidos) <> '' AND apellidos !~ '[[:cntrl:]]'),
        CONSTRAINT persona_ocupacion_check CHECK (btrim(ocupacion) <> '' AND ocupacion !~ '[[:cntrl:]]'),
        CONSTRAINT persona_fijo_check CHECK (telefono_fijo ~ '^\+?[0-9]{7,15}$'),
        CONSTRAINT persona_celular_check CHECK (telefono_celular ~ '^\+?[0-9]{7,15}$'),
        CONSTRAINT persona_correo_check CHECK (correo ~ '^[A-Za-z0-9._%%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
      ) ON COMMIT DROP
    $contrato$, contrato_nombre);
    contrato := to_regclass(format('pg_temp.%I',contrato_nombre));

    IF (SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='persona') <> 7
       OR EXISTS (
         SELECT 1 FROM (VALUES ('documento',15),('nombres',120),('apellidos',120),('ocupacion',80),
                    ('telefono_fijo',16),('telefono_celular',16),('correo',120)) esperado(columna,longitud)
         LEFT JOIN information_schema.columns c ON c.table_schema='public' AND c.table_name='persona'
              AND c.column_name=esperado.columna
         WHERE c.column_name IS NULL OR c.data_type<>'character varying' OR c.is_nullable<>'NO'
            OR c.character_maximum_length<>esperado.longitud)
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.persona'::regclass
            AND contype='p' AND pg_get_constraintdef(oid)='PRIMARY KEY (documento)')
       OR NOT EXISTS (SELECT 1 FROM pg_index WHERE indrelid='public.persona'::regclass
            AND indisunique AND indisvalid AND indisready AND indpred IS NULL
            AND indnkeyatts=1 AND indnatts=1 AND pg_get_expr(indexprs,indrelid)='lower((correo)::text)')
       OR EXISTS (
         SELECT 1 FROM pg_constraint esperado
         LEFT JOIN pg_constraint actual ON actual.conrelid='public.persona'::regclass
              AND actual.conname=esperado.conname AND actual.contype='c' AND actual.convalidated
         WHERE esperado.conrelid=contrato AND esperado.contype='c'
           AND (actual.oid IS NULL OR pg_get_expr(actual.conbin,actual.conrelid)
                 IS DISTINCT FROM pg_get_expr(esperado.conbin,esperado.conrelid))) THEN
      RAISE EXCEPTION 'persona existente no coincide con la estructura aprobada; no se modifica ni se mezclan datos';
    END IF;
  END IF;

  INSERT INTO public.configuraciones(clave,valor,created_at,updated_at)
  VALUES ('schema.persona.version','1',now(),now())
  ON CONFLICT (clave) DO UPDATE SET valor=EXCLUDED.valor,updated_at=now()
  WHERE configuraciones.valor IS DISTINCT FROM EXCLUDED.valor;
END
$persona$;
