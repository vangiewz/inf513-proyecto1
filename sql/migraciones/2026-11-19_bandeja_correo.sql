-- Bandeja de entrada y de salida del correo (19/11/2026). PARA REVISIÓN: todavía NO aplicada en
-- db_grupo05sa.public. Aplicar con respaldo previo (p1/respaldo.ps1) y con el lector detenido:
--   psql -v ON_ERROR_STOP=1 --single-transaction -f 2026-11-19_bandeja_correo.sql
-- Reversión: 2026-11-19_bandeja_correo_revertir.sql.
--
-- Por qué:
--   1. Un correo se guardaba solo en memoria entre el DELE/QUIT del POP3 y su proceso: una caída
--      en ese intervalo lo perdía. Ahora cada correo se guarda en log_comandos (resultado
--      'Recibido', con su contenido) ANTES de borrarlo del buzón, y se procesa desde la BD.
--   2. message_id (cabecera Message-ID, RFC 5322) es único: si el mismo correo se vuelve a leer
--      (QUIT no confirmado, reentrega), no se registra ni se ejecuta dos veces.
--   3. La respuesta se guarda en correos_salientes en la MISMA transacción que la operación y su
--      bitácora; se envía después y se reintenta con espera progresiva sin volver a ejecutar la
--      operación. Los avisos de recordatorios también salen por esta cola.
-- Solo P1 usa log_comandos; la web (P2) no la toca. correos_salientes es nueva y propia del
-- correo; notificaciones (avisos internos de la web) no cambia.

SET search_path TO public;

ALTER TABLE public.log_comandos
    ADD COLUMN message_id character varying(255),
    ADD COLUMN contenido bytea,
    ADD COLUMN bytes integer,
    ADD COLUMN procesado_en timestamp(0) without time zone;

ALTER TABLE public.log_comandos
    ADD CONSTRAINT log_comandos_message_id_unique UNIQUE (message_id);

CREATE INDEX log_comandos_recibidos_idx ON public.log_comandos USING btree (nro_registro)
    WHERE ((resultado)::text = 'Recibido'::text);

COMMENT ON COLUMN public.log_comandos.message_id IS 'Cabecera Message-ID del correo recibido (o sha256 del contenido si no la trae)';
COMMENT ON COLUMN public.log_comandos.contenido IS 'Correo crudo mientras espera proceso (resultado Recibido); se vacía al procesarlo';
COMMENT ON COLUMN public.log_comandos.bytes IS 'Tamaño informado por POP3 LIST';

CREATE SEQUENCE public.correos_salientes_nro_correo_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE public.correos_salientes (
    nro_correo bigint DEFAULT nextval('public.correos_salientes_nro_correo_seq'::regclass) NOT NULL,
    nro_registro bigint,
    nro_recordatorio bigint,
    destinatario character varying(160) NOT NULL,
    asunto character varying(300) NOT NULL,
    mensaje bytea NOT NULL,
    estado character varying(20) DEFAULT 'Pendiente'::character varying NOT NULL,
    intentos integer DEFAULT 0 NOT NULL,
    proximo_intento timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ultimo_error text,
    enviado_en timestamp(0) without time zone,
    created_at timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT correos_salientes_pkey PRIMARY KEY (nro_correo),
    CONSTRAINT correos_salientes_estado_check CHECK (((estado)::text = ANY (ARRAY[
        ('Pendiente'::character varying)::text, ('Enviado'::character varying)::text,
        ('Fallido'::character varying)::text, ('Rebotado'::character varying)::text]))),
    CONSTRAINT correos_salientes_origen_check CHECK (((nro_registro IS NOT NULL) OR (nro_recordatorio IS NOT NULL))),
    CONSTRAINT correos_salientes_nro_registro_fk FOREIGN KEY (nro_registro)
        REFERENCES public.log_comandos(nro_registro) ON DELETE RESTRICT,
    CONSTRAINT correos_salientes_nro_recordatorio_fk FOREIGN KEY (nro_recordatorio)
        REFERENCES public.recordatorios(nro_recordatorio) ON DELETE CASCADE
);

ALTER SEQUENCE public.correos_salientes_nro_correo_seq OWNED BY public.correos_salientes.nro_correo;

CREATE INDEX correos_salientes_pendientes_idx ON public.correos_salientes USING btree (proximo_intento)
    WHERE ((estado)::text = 'Pendiente'::text);
CREATE INDEX correos_salientes_nro_registro_idx ON public.correos_salientes USING btree (nro_registro);

COMMENT ON TABLE public.correos_salientes IS 'Cola de respuestas y avisos por correo: se envían y reintentan sin repetir la operación que los originó';
