-- P1 correo: estructura PostgreSQL vigente, sin datos personales.
-- Destino de produccion: db_grupo05sa.public. Instalar SOLO en una base nueva y vacia.
-- Ejecucion: psql -X -v ON_ERROR_STOP=1 --single-transaction -d BASE_NUEVA -f esquema.sql
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
             WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S','f')) THEN
    RAISE EXCEPTION 'El esquema public contiene objetos; no se modifica una base existente';
  END IF;
END $$;

--
-- PostgreSQL database dump
--

\restrict 8q65OelT68gd3jlNk9G3lNJmnGEz99oGodYauGkACfddBWpiEm9fuDJKgKt0S8f

-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS public;


--
-- Name: clasificar_perdida(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.clasificar_perdida() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.estado <> 'Perdido' THEN NEW.motivo_perdida_id := NULL;
    ELSIF NEW.motivo_perdida_id IS NULL THEN
        SELECT id INTO NEW.motivo_perdida_id FROM motivos_perdida WHERE nombre='Sin clasificar (web)';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: registrar_movimiento_web(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.registrar_movimiento_web() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE anterior text; vendedor_anterior bigint;
BEGIN
    IF current_setting('crm.canal',true) IS DISTINCT FROM 'correo' THEN
        IF TG_OP='UPDATE' THEN
            anterior := OLD.estado;
            vendedor_anterior := OLD.vendedor_id;
        END IF;
        IF TG_OP='INSERT' OR NEW.estado IS DISTINCT FROM anterior OR NEW.vendedor_id IS DISTINCT FROM vendedor_anterior THEN
            INSERT INTO movimientos_interes(interes_id,usuario_id,estado_anterior,estado_nuevo,detalle)
            VALUES(NEW.id, NULLIF(current_setting('crm.usuario_id',true),'')::bigint,anterior,NEW.estado,
                'Cambio desde web. Vendedor: ' || COALESCE(vendedor_anterior::text,'sin asignar') || ' -> ' || COALESCE(NEW.vendedor_id::text,'sin asignar'));
        END IF;
    END IF;
    RETURN NEW;
END $$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: api_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_tokens (
    id bigint NOT NULL,
    usuario_id bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    ultimo_uso_en timestamp(0) without time zone,
    expira_en timestamp(0) without time zone,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone
);


--
-- Name: api_tokens_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.api_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: api_tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.api_tokens_id_seq OWNED BY public.api_tokens.id;


--
-- Name: catalogo_cache; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.catalogo_cache (
    id bigint NOT NULL,
    codigo character varying(255) NOT NULL,
    external_anuncio_id character varying(255),
    programa_codigo character varying(255) NOT NULL,
    programa_nombre character varying(255) NOT NULL,
    nombre character varying(255) NOT NULL,
    descripcion text,
    precio numeric(12,2) NOT NULL,
    estado character varying(255) NOT NULL,
    sincronizado_en timestamp(0) without time zone NOT NULL
);


--
-- Name: catalogo_cache_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.catalogo_cache_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: catalogo_cache_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.catalogo_cache_id_seq OWNED BY public.catalogo_cache.id;


--
-- Name: comisiones; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.comisiones (
    id bigint NOT NULL,
    vendedor_id bigint NOT NULL,
    solicitud_compra_id bigint CONSTRAINT comisiones_venta_id_not_null NOT NULL,
    porcentaje numeric(5,2) DEFAULT '2'::numeric NOT NULL,
    monto numeric(12,2) NOT NULL,
    estado character varying(255) DEFAULT 'Pendiente'::character varying NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    fecha_pago timestamp(0) without time zone,
    CONSTRAINT comisiones_estado_check CHECK (((estado)::text = ANY (ARRAY[('Pendiente'::character varying)::text, ('Pagada'::character varying)::text])))
);


--
-- Name: comisiones_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.comisiones_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: comisiones_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.comisiones_id_seq OWNED BY public.comisiones.id;


--
-- Name: comprobantes_pago; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.comprobantes_pago (
    id bigint NOT NULL,
    pago_id bigint NOT NULL,
    archivo text NOT NULL,
    nombre_archivo character varying(255),
    mime character varying(255),
    fecha_envio timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    observacion text,
    estado_revision character varying(255) DEFAULT 'En revision'::character varying NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    remitente character varying(255),
    hora_pago timestamp(0) without time zone,
    CONSTRAINT comprobantes_pago_estado_revision_check CHECK (((estado_revision)::text = ANY (ARRAY[('En revision'::character varying)::text, ('Pago aprobado'::character varying)::text, ('Pago rechazado'::character varying)::text])))
);


--
-- Name: comprobantes_pago_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.comprobantes_pago_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: comprobantes_pago_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.comprobantes_pago_id_seq OWNED BY public.comprobantes_pago.id;


--
-- Name: configuraciones; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.configuraciones (
    clave character varying(255) NOT NULL,
    valor character varying(255) NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    actualizado_por bigint
);


--
-- Name: descuentos; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.descuentos (
    id bigint NOT NULL,
    nombre character varying(255) NOT NULL,
    codigo character varying(255) NOT NULL,
    porcentaje numeric(5,2) NOT NULL,
    estado character varying(255) DEFAULT 'activo'::character varying NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    CONSTRAINT descuentos_estado_check CHECK (((estado)::text = ANY (ARRAY[('activo'::character varying)::text, ('inactivo'::character varying)::text])))
);


--
-- Name: descuentos_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.descuentos_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: descuentos_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.descuentos_id_seq OWNED BY public.descuentos.id;


--
-- Name: exportaciones; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exportaciones (
    id bigint NOT NULL,
    usuario_id bigint,
    prospecto_id bigint,
    fecha_exportacion timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    estado character varying(255) DEFAULT 'preparada'::character varying NOT NULL,
    respuesta text,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    inscripcion_comercial_id bigint,
    CONSTRAINT exportaciones_estado_check CHECK (((estado)::text = ANY (ARRAY[('preparada'::character varying)::text, ('exportada'::character varying)::text, ('recibida'::character varying)::text, ('error'::character varying)::text])))
);


--
-- Name: exportaciones_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.exportaciones_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: exportaciones_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.exportaciones_id_seq OWNED BY public.exportaciones.id;


--
-- Name: inscripciones_comerciales; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inscripciones_comerciales (
    id bigint NOT NULL,
    solicitud_compra_id bigint NOT NULL,
    fecha_confirmacion timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    estado character varying(255) DEFAULT 'Confirmada'::character varying NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    CONSTRAINT inscripciones_comerciales_estado_check CHECK (((estado)::text = 'Confirmada'::text))
);


--
-- Name: inscripciones_comerciales_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.inscripciones_comerciales_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: inscripciones_comerciales_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.inscripciones_comerciales_id_seq OWNED BY public.inscripciones_comerciales.id;


--
-- Name: interacciones; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interacciones (
    id bigint NOT NULL,
    usuario_id bigint NOT NULL,
    tipo character varying(255) NOT NULL,
    descripcion text NOT NULL,
    resultado text,
    fecha timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    interes_id bigint NOT NULL,
    CONSTRAINT interacciones_tipo_check CHECK (((tipo)::text = ANY (ARRAY[('WhatsApp'::character varying)::text, ('Llamada'::character varying)::text, ('Correo electronico'::character varying)::text, ('Reunion'::character varying)::text, ('Nota comercial'::character varying)::text, ('Orden de pago enviada'::character varying)::text, ('Pago informado'::character varying)::text, ('Compra validada'::character varying)::text, ('Orden finalizada'::character varying)::text])))
);


--
-- Name: interacciones_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.interacciones_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: interacciones_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.interacciones_id_seq OWNED BY public.interacciones.id;


--
-- Name: intereses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.intereses (
    id bigint NOT NULL,
    prospecto_id bigint NOT NULL,
    fecha timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    external_modulo_id character varying(255) NOT NULL,
    programa_nombre character varying(255) NOT NULL,
    modulo_nombre character varying(255) NOT NULL,
    precio numeric(12,2),
    external_anuncio_id character varying(255),
    modulo_descripcion text,
    vendedor_id bigint,
    estado character varying(255) DEFAULT 'Nuevo'::character varying NOT NULL,
    fecha_asignacion timestamp(0) without time zone,
    ultima_actividad_en timestamp(0) without time zone,
    proximo_contacto timestamp(0) without time zone,
    observacion text,
    motivo_perdida_id bigint,
    CONSTRAINT intereses_estado_check CHECK (((estado)::text = ANY (ARRAY[('Nuevo'::character varying)::text, ('Contactado'::character varying)::text, ('Interesado'::character varying)::text, ('Pago enviado'::character varying)::text, ('En revision'::character varying)::text, ('Convertido'::character varying)::text, ('Perdido'::character varying)::text])))
);


--
-- Name: intereses_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.intereses_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: intereses_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.intereses_id_seq OWNED BY public.intereses.id;


--
-- Name: log_comandos; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.log_comandos (
    id bigint NOT NULL,
    usuario_id bigint,
    remitente character varying(160) NOT NULL,
    asunto character varying(300) NOT NULL,
    resultado character varying(20) NOT NULL,
    detalle text,
    fecha timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    rol_registrado character varying(40)
);


--
-- Name: log_comandos_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.log_comandos_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: log_comandos_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.log_comandos_id_seq OWNED BY public.log_comandos.id;


--
-- Name: migrations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.migrations_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: motivos_perdida; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.motivos_perdida (
    id bigint NOT NULL,
    nombre character varying(80) NOT NULL,
    categoria character varying(20) NOT NULL,
    CONSTRAINT motivos_categoria_check CHECK (((categoria)::text = ANY (ARRAY[('Económico'::character varying)::text, ('Horario'::character varying)::text, ('Incompatibilidad'::character varying)::text, ('Contacto'::character varying)::text, ('Otro'::character varying)::text])))
);


--
-- Name: motivos_perdida_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.motivos_perdida_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: motivos_perdida_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.motivos_perdida_id_seq OWNED BY public.motivos_perdida.id;


--
-- Name: movimientos_interes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.movimientos_interes (
    id bigint NOT NULL,
    interes_id bigint NOT NULL,
    usuario_id bigint,
    estado_anterior character varying(255),
    estado_nuevo character varying(255) NOT NULL,
    detalle text,
    fecha timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: movimientos_interes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.movimientos_interes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: movimientos_interes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.movimientos_interes_id_seq OWNED BY public.movimientos_interes.id;


--
-- Name: notificaciones; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notificaciones (
    id bigint NOT NULL,
    usuario_id bigint,
    destinatario character varying(255),
    tipo character varying(255) NOT NULL,
    mensaje text NOT NULL,
    estado character varying(255) DEFAULT 'enviada'::character varying NOT NULL,
    fecha_envio timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    CONSTRAINT notificaciones_estado_check CHECK (((estado)::text = ANY (ARRAY[('enviada'::character varying)::text, ('fallida'::character varying)::text, ('leida'::character varying)::text])))
);


--
-- Name: notificaciones_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.notificaciones_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: notificaciones_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.notificaciones_id_seq OWNED BY public.notificaciones.id;


--
-- Name: orden_pago_descuentos; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orden_pago_descuentos (
    id bigint NOT NULL,
    orden_pago_id bigint NOT NULL,
    descuento_id bigint NOT NULL,
    porcentaje numeric(5,2) NOT NULL,
    monto numeric(12,2) NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone
);


--
-- Name: orden_pago_descuentos_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.orden_pago_descuentos_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: orden_pago_descuentos_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.orden_pago_descuentos_id_seq OWNED BY public.orden_pago_descuentos.id;


--
-- Name: ordenes_pago; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ordenes_pago (
    id bigint NOT NULL,
    interes_id bigint NOT NULL,
    vendedor_id bigint NOT NULL,
    subtotal numeric(12,2) NOT NULL,
    descuento_total numeric(12,2) DEFAULT '0'::numeric NOT NULL,
    total numeric(12,2) NOT NULL,
    estado character varying(255) DEFAULT 'Pendiente de pago'::character varying NOT NULL,
    fecha_generacion timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    CONSTRAINT ordenes_pago_estado_check CHECK (((estado)::text = ANY (ARRAY[('Pendiente de pago'::character varying)::text, ('En revision'::character varying)::text, ('Pago aprobado'::character varying)::text, ('Pago rechazado'::character varying)::text])))
);


--
-- Name: ordenes_pago_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ordenes_pago_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ordenes_pago_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ordenes_pago_id_seq OWNED BY public.ordenes_pago.id;


--
-- Name: pagos; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pagos (
    id bigint NOT NULL,
    monto numeric(12,2) NOT NULL,
    codigo_qr character varying(255) NOT NULL,
    estado character varying(255) DEFAULT 'Pendiente de pago'::character varying NOT NULL,
    fecha_generacion timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    fecha_validacion timestamp(0) without time zone,
    observacion_validacion text,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    orden_pago_id bigint NOT NULL,
    CONSTRAINT pagos_estado_check CHECK (((estado)::text = ANY (ARRAY[('Pendiente de pago'::character varying)::text, ('En revision'::character varying)::text, ('Pago aprobado'::character varying)::text, ('Pago rechazado'::character varying)::text])))
);


--
-- Name: pagos_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pagos_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pagos_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pagos_id_seq OWNED BY public.pagos.id;


--
-- Name: programas_responsables; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.programas_responsables (
    external_programa_id character varying(255) NOT NULL,
    coordinador_id bigint NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone
);


--
-- Name: prospectos; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.prospectos (
    id bigint NOT NULL,
    nombre character varying(255) NOT NULL,
    apellido character varying(255) NOT NULL,
    documento character varying(255) NOT NULL,
    correo character varying(255) NOT NULL,
    telefono character varying(255) NOT NULL,
    estado character varying(255) DEFAULT 'Nuevo'::character varying NOT NULL,
    fecha_registro timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    profesion character varying(255),
    ubicacion character varying(255),
    origen character varying(20) DEFAULT 'Web'::character varying NOT NULL,
    CONSTRAINT prospectos_estado_check CHECK (((estado)::text = ANY (ARRAY[('Nuevo'::character varying)::text, ('Contactado'::character varying)::text, ('Interesado'::character varying)::text, ('En proceso'::character varying)::text, ('Convertido'::character varying)::text, ('Perdido'::character varying)::text])))
);


--
-- Name: prospectos_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.prospectos_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: prospectos_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.prospectos_id_seq OWNED BY public.prospectos.id;


--
-- Name: recordatorios; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.recordatorios (
    id bigint NOT NULL,
    usuario_id bigint NOT NULL,
    fecha_programada timestamp(0) without time zone NOT NULL,
    descripcion text NOT NULL,
    estado character varying(255) DEFAULT 'Pendiente'::character varying NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    interes_id bigint NOT NULL,
    fecha_realizada timestamp(0) without time zone,
    notificado boolean DEFAULT false NOT NULL,
    CONSTRAINT recordatorios_estado_check CHECK (((estado)::text = ANY (ARRAY[('Pendiente'::character varying)::text, ('Realizada'::character varying)::text, ('Atrasada'::character varying)::text, ('Cancelado'::character varying)::text])))
);


--
-- Name: recordatorios_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.recordatorios_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: recordatorios_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.recordatorios_id_seq OWNED BY public.recordatorios.id;


--
-- Name: roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.roles (
    nombre character varying(40) NOT NULL,
    descripcion character varying(255) NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone
);


--
-- Name: solicitudes_compra; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.solicitudes_compra (
    id bigint NOT NULL,
    precio_acordado numeric(12,2),
    estado character varying(255) DEFAULT 'pendiente'::character varying NOT NULL,
    fecha timestamp(0) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    external_anuncio_id character varying(255),
    external_modulo_id character varying(255) NOT NULL,
    programa_nombre character varying(255) NOT NULL,
    modulo_nombre character varying(255) NOT NULL,
    modulo_descripcion text,
    anuncio_titulo character varying(255),
    orden_pago_id bigint NOT NULL,
    CONSTRAINT solicitudes_compra_estado_check CHECK (((estado)::text = ANY (ARRAY[('pendiente'::character varying)::text, ('confirmada'::character varying)::text])))
);


--
-- Name: solicitudes_compra_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.solicitudes_compra_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: solicitudes_compra_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.solicitudes_compra_id_seq OWNED BY public.solicitudes_compra.id;


--
-- Name: usuarios; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.usuarios (
    id bigint NOT NULL,
    nombre character varying(255) NOT NULL,
    apellido character varying(255) NOT NULL,
    correo character varying(255) NOT NULL,
    telefono character varying(255),
    password character varying(255) NOT NULL,
    rol character varying(40) NOT NULL,
    estado character varying(255) DEFAULT 'activo'::character varying NOT NULL,
    created_at timestamp(0) without time zone,
    updated_at timestamp(0) without time zone,
    CONSTRAINT usuarios_estado_check CHECK (((estado)::text = ANY (ARRAY[('activo'::character varying)::text, ('inactivo'::character varying)::text])))
);


--
-- Name: usuarios_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.usuarios_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: usuarios_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.usuarios_id_seq OWNED BY public.usuarios.id;


--
-- Name: api_tokens id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_tokens ALTER COLUMN id SET DEFAULT nextval('public.api_tokens_id_seq'::regclass);


--
-- Name: catalogo_cache id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.catalogo_cache ALTER COLUMN id SET DEFAULT nextval('public.catalogo_cache_id_seq'::regclass);


--
-- Name: comisiones id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comisiones ALTER COLUMN id SET DEFAULT nextval('public.comisiones_id_seq'::regclass);


--
-- Name: comprobantes_pago id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comprobantes_pago ALTER COLUMN id SET DEFAULT nextval('public.comprobantes_pago_id_seq'::regclass);


--
-- Name: descuentos id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.descuentos ALTER COLUMN id SET DEFAULT nextval('public.descuentos_id_seq'::regclass);


--
-- Name: exportaciones id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exportaciones ALTER COLUMN id SET DEFAULT nextval('public.exportaciones_id_seq'::regclass);


--
-- Name: inscripciones_comerciales id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inscripciones_comerciales ALTER COLUMN id SET DEFAULT nextval('public.inscripciones_comerciales_id_seq'::regclass);


--
-- Name: interacciones id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interacciones ALTER COLUMN id SET DEFAULT nextval('public.interacciones_id_seq'::regclass);


--
-- Name: intereses id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intereses ALTER COLUMN id SET DEFAULT nextval('public.intereses_id_seq'::regclass);


--
-- Name: log_comandos id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.log_comandos ALTER COLUMN id SET DEFAULT nextval('public.log_comandos_id_seq'::regclass);


--
-- Name: motivos_perdida id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.motivos_perdida ALTER COLUMN id SET DEFAULT nextval('public.motivos_perdida_id_seq'::regclass);


--
-- Name: movimientos_interes id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.movimientos_interes ALTER COLUMN id SET DEFAULT nextval('public.movimientos_interes_id_seq'::regclass);


--
-- Name: notificaciones id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notificaciones ALTER COLUMN id SET DEFAULT nextval('public.notificaciones_id_seq'::regclass);


--
-- Name: orden_pago_descuentos id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orden_pago_descuentos ALTER COLUMN id SET DEFAULT nextval('public.orden_pago_descuentos_id_seq'::regclass);


--
-- Name: ordenes_pago id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ordenes_pago ALTER COLUMN id SET DEFAULT nextval('public.ordenes_pago_id_seq'::regclass);


--
-- Name: pagos id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pagos ALTER COLUMN id SET DEFAULT nextval('public.pagos_id_seq'::regclass);


--
-- Name: prospectos id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prospectos ALTER COLUMN id SET DEFAULT nextval('public.prospectos_id_seq'::regclass);


--
-- Name: recordatorios id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recordatorios ALTER COLUMN id SET DEFAULT nextval('public.recordatorios_id_seq'::regclass);


--
-- Name: solicitudes_compra id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.solicitudes_compra ALTER COLUMN id SET DEFAULT nextval('public.solicitudes_compra_id_seq'::regclass);


--
-- Name: usuarios id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usuarios ALTER COLUMN id SET DEFAULT nextval('public.usuarios_id_seq'::regclass);


--
-- Name: api_tokens api_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_tokens
    ADD CONSTRAINT api_tokens_pkey PRIMARY KEY (id);


--
-- Name: api_tokens api_tokens_token_hash_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_tokens
    ADD CONSTRAINT api_tokens_token_hash_unique UNIQUE (token_hash);


--
-- Name: catalogo_cache catalogo_cache_codigo_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.catalogo_cache
    ADD CONSTRAINT catalogo_cache_codigo_unique UNIQUE (codigo);


--
-- Name: catalogo_cache catalogo_cache_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.catalogo_cache
    ADD CONSTRAINT catalogo_cache_pkey PRIMARY KEY (id);


--
-- Name: comisiones comisiones_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comisiones
    ADD CONSTRAINT comisiones_pkey PRIMARY KEY (id);


--
-- Name: comisiones comisiones_solicitud_compra_id_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comisiones
    ADD CONSTRAINT comisiones_solicitud_compra_id_unique UNIQUE (solicitud_compra_id);


--
-- Name: comprobantes_pago comprobantes_pago_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comprobantes_pago
    ADD CONSTRAINT comprobantes_pago_pkey PRIMARY KEY (id);


--
-- Name: configuraciones configuraciones_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.configuraciones
    ADD CONSTRAINT configuraciones_pkey PRIMARY KEY (clave);


--
-- Name: descuentos descuentos_codigo_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.descuentos
    ADD CONSTRAINT descuentos_codigo_unique UNIQUE (codigo);


--
-- Name: descuentos descuentos_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.descuentos
    ADD CONSTRAINT descuentos_pkey PRIMARY KEY (id);


--
-- Name: exportaciones exportaciones_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exportaciones
    ADD CONSTRAINT exportaciones_pkey PRIMARY KEY (id);


--
-- Name: inscripciones_comerciales inscripciones_comerciales_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inscripciones_comerciales
    ADD CONSTRAINT inscripciones_comerciales_pkey PRIMARY KEY (id);


--
-- Name: inscripciones_comerciales inscripciones_comerciales_solicitud_compra_id_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inscripciones_comerciales
    ADD CONSTRAINT inscripciones_comerciales_solicitud_compra_id_unique UNIQUE (solicitud_compra_id);


--
-- Name: interacciones interacciones_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interacciones
    ADD CONSTRAINT interacciones_pkey PRIMARY KEY (id);


--
-- Name: intereses intereses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intereses
    ADD CONSTRAINT intereses_pkey PRIMARY KEY (id);


--
-- Name: intereses intereses_prospecto_external_modulo_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intereses
    ADD CONSTRAINT intereses_prospecto_external_modulo_unique UNIQUE (prospecto_id, external_modulo_id);


--
-- Name: log_comandos log_comandos_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.log_comandos
    ADD CONSTRAINT log_comandos_pkey PRIMARY KEY (id);


--
-- Name: motivos_perdida motivos_perdida_nombre_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.motivos_perdida
    ADD CONSTRAINT motivos_perdida_nombre_unique UNIQUE (nombre);


--
-- Name: motivos_perdida motivos_perdida_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.motivos_perdida
    ADD CONSTRAINT motivos_perdida_pkey PRIMARY KEY (id);


--
-- Name: movimientos_interes movimientos_interes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.movimientos_interes
    ADD CONSTRAINT movimientos_interes_pkey PRIMARY KEY (id);


--
-- Name: notificaciones notificaciones_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notificaciones
    ADD CONSTRAINT notificaciones_pkey PRIMARY KEY (id);


--
-- Name: orden_pago_descuentos orden_pago_descuentos_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orden_pago_descuentos
    ADD CONSTRAINT orden_pago_descuentos_pkey PRIMARY KEY (id);


--
-- Name: ordenes_pago ordenes_pago_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ordenes_pago
    ADD CONSTRAINT ordenes_pago_pkey PRIMARY KEY (id);


--
-- Name: pagos pagos_codigo_qr_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pagos
    ADD CONSTRAINT pagos_codigo_qr_unique UNIQUE (codigo_qr);


--
-- Name: pagos pagos_orden_pago_id_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pagos
    ADD CONSTRAINT pagos_orden_pago_id_unique UNIQUE (orden_pago_id);


--
-- Name: pagos pagos_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pagos
    ADD CONSTRAINT pagos_pkey PRIMARY KEY (id);


--
-- Name: programas_responsables programas_responsables_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.programas_responsables
    ADD CONSTRAINT programas_responsables_pkey PRIMARY KEY (external_programa_id);


--
-- Name: prospectos prospectos_correo_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prospectos
    ADD CONSTRAINT prospectos_correo_unique UNIQUE (correo);


--
-- Name: prospectos prospectos_documento_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prospectos
    ADD CONSTRAINT prospectos_documento_unique UNIQUE (documento);


--
-- Name: prospectos prospectos_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prospectos
    ADD CONSTRAINT prospectos_pkey PRIMARY KEY (id);


--
-- Name: recordatorios recordatorios_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recordatorios
    ADD CONSTRAINT recordatorios_pkey PRIMARY KEY (id);


--
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (nombre);


--
-- Name: solicitudes_compra solicitudes_compra_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.solicitudes_compra
    ADD CONSTRAINT solicitudes_compra_pkey PRIMARY KEY (id);


--
-- Name: usuarios usuarios_correo_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usuarios
    ADD CONSTRAINT usuarios_correo_unique UNIQUE (correo);


--
-- Name: usuarios usuarios_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usuarios
    ADD CONSTRAINT usuarios_pkey PRIMARY KEY (id);


--
-- Name: exportaciones_estado_fecha_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX exportaciones_estado_fecha_idx ON public.exportaciones USING btree (estado, fecha_exportacion);


--
-- Name: interacciones_usuario_fecha_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX interacciones_usuario_fecha_idx ON public.interacciones USING btree (usuario_id, fecha);


--
-- Name: movimientos_interes_interes_id_fecha_index; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX movimientos_interes_interes_id_fecha_index ON public.movimientos_interes USING btree (interes_id, fecha);


--
-- Name: notificaciones_usuario_estado_fecha_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX notificaciones_usuario_estado_fecha_idx ON public.notificaciones USING btree (usuario_id, estado, fecha_envio);


--
-- Name: pagos_estado_fecha_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX pagos_estado_fecha_idx ON public.pagos USING btree (estado, fecha_generacion);


--
-- Name: prospectos_correo_lower_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX prospectos_correo_lower_unique ON public.prospectos USING btree (lower((correo)::text));


--
-- Name: recordatorios_usuario_estado_fecha_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX recordatorios_usuario_estado_fecha_idx ON public.recordatorios USING btree (usuario_id, estado, fecha_programada);


--
-- Name: solicitudes_external_modulo_fecha_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX solicitudes_external_modulo_fecha_idx ON public.solicitudes_compra USING btree (external_modulo_id, fecha);


--
-- Name: usuarios_correo_lower_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX usuarios_correo_lower_unique ON public.usuarios USING btree (lower((correo)::text));


--
-- Name: usuarios_rol_estado_index; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX usuarios_rol_estado_index ON public.usuarios USING btree (rol, estado);


--
-- Name: intereses intereses_clasificar; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER intereses_clasificar BEFORE INSERT OR UPDATE ON public.intereses FOR EACH ROW EXECUTE FUNCTION public.clasificar_perdida();


--
-- Name: intereses intereses_historial; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER intereses_historial AFTER INSERT OR UPDATE ON public.intereses FOR EACH ROW EXECUTE FUNCTION public.registrar_movimiento_web();


--
-- Name: api_tokens api_tokens_usuario_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_tokens
    ADD CONSTRAINT api_tokens_usuario_id_foreign FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id) ON DELETE CASCADE;


--
-- Name: comisiones comisiones_solicitud_compra_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comisiones
    ADD CONSTRAINT comisiones_solicitud_compra_id_foreign FOREIGN KEY (solicitud_compra_id) REFERENCES public.solicitudes_compra(id) ON DELETE RESTRICT;


--
-- Name: comisiones comisiones_vendedor_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comisiones
    ADD CONSTRAINT comisiones_vendedor_id_foreign FOREIGN KEY (vendedor_id) REFERENCES public.usuarios(id) ON DELETE RESTRICT;


--
-- Name: comprobantes_pago comprobantes_pago_pago_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comprobantes_pago
    ADD CONSTRAINT comprobantes_pago_pago_id_foreign FOREIGN KEY (pago_id) REFERENCES public.pagos(id) ON DELETE CASCADE;


--
-- Name: configuraciones configuraciones_actualizado_por_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.configuraciones
    ADD CONSTRAINT configuraciones_actualizado_por_foreign FOREIGN KEY (actualizado_por) REFERENCES public.usuarios(id) ON DELETE SET NULL;


--
-- Name: exportaciones exportaciones_inscripcion_comercial_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exportaciones
    ADD CONSTRAINT exportaciones_inscripcion_comercial_id_foreign FOREIGN KEY (inscripcion_comercial_id) REFERENCES public.inscripciones_comerciales(id) ON DELETE RESTRICT;


--
-- Name: exportaciones exportaciones_prospecto_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exportaciones
    ADD CONSTRAINT exportaciones_prospecto_id_foreign FOREIGN KEY (prospecto_id) REFERENCES public.prospectos(id) ON DELETE SET NULL;


--
-- Name: exportaciones exportaciones_usuario_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exportaciones
    ADD CONSTRAINT exportaciones_usuario_id_foreign FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id) ON DELETE SET NULL;


--
-- Name: inscripciones_comerciales inscripciones_comerciales_solicitud_compra_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inscripciones_comerciales
    ADD CONSTRAINT inscripciones_comerciales_solicitud_compra_id_foreign FOREIGN KEY (solicitud_compra_id) REFERENCES public.solicitudes_compra(id) ON DELETE RESTRICT;


--
-- Name: interacciones interacciones_interes_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interacciones
    ADD CONSTRAINT interacciones_interes_id_foreign FOREIGN KEY (interes_id) REFERENCES public.intereses(id) ON DELETE RESTRICT;


--
-- Name: interacciones interacciones_usuario_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interacciones
    ADD CONSTRAINT interacciones_usuario_id_foreign FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id) ON DELETE RESTRICT;


--
-- Name: intereses intereses_motivo_perdida_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intereses
    ADD CONSTRAINT intereses_motivo_perdida_id_foreign FOREIGN KEY (motivo_perdida_id) REFERENCES public.motivos_perdida(id) ON DELETE RESTRICT;


--
-- Name: intereses intereses_prospecto_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intereses
    ADD CONSTRAINT intereses_prospecto_id_foreign FOREIGN KEY (prospecto_id) REFERENCES public.prospectos(id) ON DELETE RESTRICT;


--
-- Name: intereses intereses_vendedor_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intereses
    ADD CONSTRAINT intereses_vendedor_id_foreign FOREIGN KEY (vendedor_id) REFERENCES public.usuarios(id) ON DELETE SET NULL;


--
-- Name: log_comandos log_comandos_usuario_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.log_comandos
    ADD CONSTRAINT log_comandos_usuario_id_foreign FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id) ON DELETE RESTRICT;


--
-- Name: movimientos_interes movimientos_interes_interes_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.movimientos_interes
    ADD CONSTRAINT movimientos_interes_interes_id_foreign FOREIGN KEY (interes_id) REFERENCES public.intereses(id) ON DELETE RESTRICT;


--
-- Name: movimientos_interes movimientos_interes_usuario_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.movimientos_interes
    ADD CONSTRAINT movimientos_interes_usuario_id_foreign FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id) ON DELETE RESTRICT;


--
-- Name: notificaciones notificaciones_usuario_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notificaciones
    ADD CONSTRAINT notificaciones_usuario_id_foreign FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id) ON DELETE SET NULL;


--
-- Name: orden_pago_descuentos orden_pago_descuentos_descuento_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orden_pago_descuentos
    ADD CONSTRAINT orden_pago_descuentos_descuento_id_foreign FOREIGN KEY (descuento_id) REFERENCES public.descuentos(id) ON DELETE RESTRICT;


--
-- Name: orden_pago_descuentos orden_pago_descuentos_orden_pago_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orden_pago_descuentos
    ADD CONSTRAINT orden_pago_descuentos_orden_pago_id_foreign FOREIGN KEY (orden_pago_id) REFERENCES public.ordenes_pago(id) ON DELETE CASCADE;


--
-- Name: ordenes_pago ordenes_pago_interes_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ordenes_pago
    ADD CONSTRAINT ordenes_pago_interes_id_foreign FOREIGN KEY (interes_id) REFERENCES public.intereses(id) ON DELETE RESTRICT;


--
-- Name: ordenes_pago ordenes_pago_vendedor_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ordenes_pago
    ADD CONSTRAINT ordenes_pago_vendedor_id_foreign FOREIGN KEY (vendedor_id) REFERENCES public.usuarios(id) ON DELETE RESTRICT;


--
-- Name: pagos pagos_orden_pago_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pagos
    ADD CONSTRAINT pagos_orden_pago_id_foreign FOREIGN KEY (orden_pago_id) REFERENCES public.ordenes_pago(id) ON DELETE RESTRICT;


--
-- Name: programas_responsables programas_responsables_coordinador_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.programas_responsables
    ADD CONSTRAINT programas_responsables_coordinador_id_foreign FOREIGN KEY (coordinador_id) REFERENCES public.usuarios(id) ON DELETE RESTRICT;


--
-- Name: recordatorios recordatorios_interes_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recordatorios
    ADD CONSTRAINT recordatorios_interes_id_foreign FOREIGN KEY (interes_id) REFERENCES public.intereses(id) ON DELETE RESTRICT;


--
-- Name: recordatorios recordatorios_usuario_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.recordatorios
    ADD CONSTRAINT recordatorios_usuario_id_foreign FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id) ON DELETE RESTRICT;


--
-- Name: solicitudes_compra solicitudes_compra_orden_pago_id_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.solicitudes_compra
    ADD CONSTRAINT solicitudes_compra_orden_pago_id_foreign FOREIGN KEY (orden_pago_id) REFERENCES public.ordenes_pago(id) ON DELETE CASCADE;


--
-- Name: usuarios usuarios_rol_foreign; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usuarios
    ADD CONSTRAINT usuarios_rol_foreign FOREIGN KEY (rol) REFERENCES public.roles(nombre) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- PostgreSQL database dump complete
--

\unrestrict 8q65OelT68gd3jlNk9G3lNJmnGEz99oGodYauGkACfddBWpiEm9fuDJKgKt0S8f
