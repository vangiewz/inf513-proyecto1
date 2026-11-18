-- Complemento de 2026-11-17_claves_diseno.sql (17/11/2026). En PostgreSQL 18 cada NOT NULL es una restricción con nombre y
-- conserva el que tenía la columna antes de renombrarla (intereses_id_not_null, ..._interes_id_not_null). Se les da el nombre
-- por omisión, tabla_columna_not_null, para que ningún nombre del esquema hable de un "id" que ya no existe.
BEGIN;
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT c.conname, t.relname, a.attname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = c.conkey[1]
        WHERE n.nspname = 'public' AND c.contype = 'n' AND t.relname <> 'migrations'
          AND c.conname <> t.relname || '_' || a.attname || '_not_null'
    LOOP
        EXECUTE format('ALTER TABLE public.%I RENAME CONSTRAINT %I TO %I',
                       r.relname, r.conname, r.relname || '_' || r.attname || '_not_null');
    END LOOP;
END $$;
COMMIT;
