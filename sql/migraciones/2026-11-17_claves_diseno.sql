-- Claves de diseño en todas las tablas y vigencia de la credencial (17/11/2026), para db_grupo05sa.public.
-- Clase 46 del docente: "No quiero ver un campo ID ... La llave es la llave que tiene que ser, el nombre que tiene que ser";
-- sin dos llaves en una tabla. Clase 03: "Toda credencial lleva fecha inicio, fecha fin y rol".
--   1. Clave natural donde la entidad la tiene (sin id):
--        usuarios(correo), prospectos(documento), descuentos(codigo), motivos_perdida(nombre), api_tokens(token_hash).
--   2. Entidad 1 a 1 con su dueño: la clave es la de él (sin id):
--        pagos(nro_orden), comisiones(nro_solicitud), inscripciones_comerciales(nro_solicitud).
--   3. Sin clave natural: correlativo con el nombre del diseño (id -> nro_*):
--        intereses.nro_tarjeta, ordenes_pago.nro_orden, solicitudes_compra.nro_solicitud, interacciones.nro_interaccion,
--        movimientos_interes.nro_movimiento, recordatorios.nro_recordatorio, notificaciones.nro_notificacion,
--        log_comandos.nro_registro, exportaciones.nro_exportacion, comprobantes_pago.nro_comprobante.
--   4. Cada clave foránea toma el nombre de la clave a la que apunta (o el papel: vendedor_correo, coordinador_correo).
--   5. Códigos del catálogo externo con el nombre de catalogo_cache: modulo_codigo, programa_codigo, anuncio_codigo.
--   6. usuarios.fecha_inicio y usuarios.fecha_fin (CHECK fecha_fin >= fecha_inicio).
-- Respaldo previo obligatorio (p1/respaldo.ps1). Todo o nada: una transacción. P2 web debe replicarlo con una migración.

BEGIN;

-- ---------------------------------------------------------------- 0. triggers que nombran las columnas viejas
DROP TRIGGER intereses_clasificar ON public.intereses;
DROP TRIGGER intereses_historial ON public.intereses;

-- ---------------------------------------------------------------- 1. nuevas columnas FK, llenas con la clave nueva
ALTER TABLE public.api_tokens          ADD COLUMN usuario_correo varchar(255);
ALTER TABLE public.comisiones          ADD COLUMN vendedor_correo varchar(255);
ALTER TABLE public.configuraciones     ADD COLUMN actualizado_por_correo varchar(255);
ALTER TABLE public.exportaciones       ADD COLUMN usuario_correo varchar(255), ADD COLUMN documento varchar(255),
                                       ADD COLUMN nro_solicitud bigint;
ALTER TABLE public.interacciones       ADD COLUMN usuario_correo varchar(255);
ALTER TABLE public.intereses           ADD COLUMN vendedor_correo varchar(255), ADD COLUMN documento varchar(255),
                                       ADD COLUMN motivo_perdida varchar(80);
ALTER TABLE public.log_comandos        ADD COLUMN usuario_correo varchar(255);
ALTER TABLE public.movimientos_interes ADD COLUMN usuario_correo varchar(255);
ALTER TABLE public.notificaciones      ADD COLUMN usuario_correo varchar(255);
ALTER TABLE public.ordenes_pago        ADD COLUMN vendedor_correo varchar(255);
ALTER TABLE public.programas_responsables ADD COLUMN coordinador_correo varchar(255);
ALTER TABLE public.recordatorios       ADD COLUMN usuario_correo varchar(255);
ALTER TABLE public.orden_pago_descuentos ADD COLUMN descuento_codigo varchar(255);
ALTER TABLE public.comprobantes_pago   ADD COLUMN nro_orden bigint;

UPDATE public.api_tokens t          SET usuario_correo = u.correo FROM public.usuarios u WHERE u.id = t.usuario_id;
UPDATE public.comisiones t          SET vendedor_correo = u.correo FROM public.usuarios u WHERE u.id = t.vendedor_id;
UPDATE public.configuraciones t     SET actualizado_por_correo = u.correo FROM public.usuarios u WHERE u.id = t.actualizado_por;
UPDATE public.exportaciones t       SET usuario_correo = u.correo FROM public.usuarios u WHERE u.id = t.usuario_id;
UPDATE public.exportaciones t       SET documento = p.documento FROM public.prospectos p WHERE p.id = t.prospecto_id;
UPDATE public.exportaciones t       SET nro_solicitud = m.solicitud_compra_id
                                    FROM public.inscripciones_comerciales m WHERE m.id = t.inscripcion_comercial_id;
UPDATE public.interacciones t       SET usuario_correo = u.correo FROM public.usuarios u WHERE u.id = t.usuario_id;
UPDATE public.intereses t           SET vendedor_correo = u.correo FROM public.usuarios u WHERE u.id = t.vendedor_id;
UPDATE public.intereses t           SET documento = p.documento FROM public.prospectos p WHERE p.id = t.prospecto_id;
UPDATE public.intereses t           SET motivo_perdida = m.nombre FROM public.motivos_perdida m WHERE m.id = t.motivo_perdida_id;
UPDATE public.log_comandos t        SET usuario_correo = u.correo FROM public.usuarios u WHERE u.id = t.usuario_id;
UPDATE public.movimientos_interes t SET usuario_correo = u.correo FROM public.usuarios u WHERE u.id = t.usuario_id;
UPDATE public.notificaciones t      SET usuario_correo = u.correo FROM public.usuarios u WHERE u.id = t.usuario_id;
UPDATE public.ordenes_pago t        SET vendedor_correo = u.correo FROM public.usuarios u WHERE u.id = t.vendedor_id;
UPDATE public.programas_responsables t SET coordinador_correo = u.correo FROM public.usuarios u WHERE u.id = t.coordinador_id;
UPDATE public.recordatorios t       SET usuario_correo = u.correo FROM public.usuarios u WHERE u.id = t.usuario_id;
UPDATE public.orden_pago_descuentos t SET descuento_codigo = d.codigo FROM public.descuentos d WHERE d.id = t.descuento_id;
UPDATE public.comprobantes_pago t   SET nro_orden = p.orden_pago_id FROM public.pagos p WHERE p.id = t.pago_id;

-- ---------------------------------------------------------------- 2. fuera las FK y columnas viejas
ALTER TABLE public.api_tokens          DROP COLUMN usuario_id;
ALTER TABLE public.comisiones          DROP COLUMN vendedor_id;
ALTER TABLE public.configuraciones     DROP COLUMN actualizado_por;
ALTER TABLE public.configuraciones     RENAME COLUMN actualizado_por_correo TO actualizado_por;
ALTER TABLE public.exportaciones       DROP COLUMN usuario_id, DROP COLUMN prospecto_id, DROP COLUMN inscripcion_comercial_id;
ALTER TABLE public.interacciones       DROP COLUMN usuario_id;
ALTER TABLE public.intereses           DROP CONSTRAINT intereses_prospecto_external_modulo_unique;
ALTER TABLE public.intereses           DROP COLUMN vendedor_id, DROP COLUMN prospecto_id, DROP COLUMN motivo_perdida_id;
ALTER TABLE public.log_comandos        DROP COLUMN usuario_id;
ALTER TABLE public.movimientos_interes DROP COLUMN usuario_id;
ALTER TABLE public.notificaciones      DROP COLUMN usuario_id;
ALTER TABLE public.ordenes_pago        DROP COLUMN vendedor_id;
ALTER TABLE public.programas_responsables DROP COLUMN coordinador_id;
ALTER TABLE public.recordatorios       DROP COLUMN usuario_id;
ALTER TABLE public.orden_pago_descuentos DROP CONSTRAINT orden_pago_descuentos_pkey;
ALTER TABLE public.orden_pago_descuentos DROP COLUMN descuento_id;
ALTER TABLE public.comprobantes_pago   DROP COLUMN pago_id;

-- ---------------------------------------------------------------- 3. clave natural o del dueño: sin id
ALTER TABLE public.usuarios        DROP CONSTRAINT usuarios_correo_unique, DROP COLUMN id;
ALTER TABLE public.usuarios        ADD CONSTRAINT usuarios_pkey PRIMARY KEY (correo);
ALTER TABLE public.prospectos      DROP CONSTRAINT prospectos_documento_unique, DROP COLUMN id;
ALTER TABLE public.prospectos      ADD CONSTRAINT prospectos_pkey PRIMARY KEY (documento);
ALTER TABLE public.descuentos      DROP CONSTRAINT descuentos_codigo_unique, DROP COLUMN id;
ALTER TABLE public.descuentos      ADD CONSTRAINT descuentos_pkey PRIMARY KEY (codigo);
ALTER TABLE public.motivos_perdida DROP CONSTRAINT motivos_perdida_nombre_unique, DROP COLUMN id;
ALTER TABLE public.motivos_perdida ADD CONSTRAINT motivos_perdida_pkey PRIMARY KEY (nombre);
ALTER TABLE public.api_tokens      DROP CONSTRAINT api_tokens_token_hash_unique, DROP COLUMN id;
ALTER TABLE public.api_tokens      ADD CONSTRAINT api_tokens_pkey PRIMARY KEY (token_hash);

-- 1 a 1: pago de la orden, comisión e inscripción de la solicitud
ALTER TABLE public.pagos DROP CONSTRAINT pagos_orden_pago_id_unique, DROP COLUMN id;
ALTER TABLE public.pagos RENAME COLUMN orden_pago_id TO nro_orden;
ALTER TABLE public.pagos ADD CONSTRAINT pagos_pkey PRIMARY KEY (nro_orden);
ALTER TABLE public.comisiones DROP CONSTRAINT comisiones_solicitud_compra_id_unique, DROP COLUMN id;
ALTER TABLE public.comisiones RENAME COLUMN solicitud_compra_id TO nro_solicitud;
ALTER TABLE public.comisiones ADD CONSTRAINT comisiones_pkey PRIMARY KEY (nro_solicitud);
ALTER TABLE public.inscripciones_comerciales
    DROP CONSTRAINT inscripciones_comerciales_solicitud_compra_id_unique, DROP COLUMN id;
ALTER TABLE public.inscripciones_comerciales RENAME COLUMN solicitud_compra_id TO nro_solicitud;
ALTER TABLE public.inscripciones_comerciales ADD CONSTRAINT inscripciones_comerciales_pkey PRIMARY KEY (nro_solicitud);

-- ---------------------------------------------------------------- 4. correlativos con nombre de diseño
ALTER TABLE public.intereses           RENAME COLUMN id TO nro_tarjeta;
ALTER SEQUENCE public.intereses_id_seq RENAME TO intereses_nro_tarjeta_seq;
ALTER TABLE public.ordenes_pago        RENAME COLUMN id TO nro_orden;
ALTER SEQUENCE public.ordenes_pago_id_seq RENAME TO ordenes_pago_nro_orden_seq;
ALTER TABLE public.solicitudes_compra  RENAME COLUMN id TO nro_solicitud;
ALTER SEQUENCE public.solicitudes_compra_id_seq RENAME TO solicitudes_compra_nro_solicitud_seq;
ALTER TABLE public.interacciones       RENAME COLUMN id TO nro_interaccion;
ALTER SEQUENCE public.interacciones_id_seq RENAME TO interacciones_nro_interaccion_seq;
ALTER TABLE public.movimientos_interes RENAME COLUMN id TO nro_movimiento;
ALTER SEQUENCE public.movimientos_interes_id_seq RENAME TO movimientos_interes_nro_movimiento_seq;
ALTER TABLE public.recordatorios       RENAME COLUMN id TO nro_recordatorio;
ALTER SEQUENCE public.recordatorios_id_seq RENAME TO recordatorios_nro_recordatorio_seq;
ALTER TABLE public.notificaciones      RENAME COLUMN id TO nro_notificacion;
ALTER SEQUENCE public.notificaciones_id_seq RENAME TO notificaciones_nro_notificacion_seq;
ALTER TABLE public.log_comandos        RENAME COLUMN id TO nro_registro;
ALTER SEQUENCE public.log_comandos_id_seq RENAME TO log_comandos_nro_registro_seq;
ALTER TABLE public.exportaciones       RENAME COLUMN id TO nro_exportacion;
ALTER SEQUENCE public.exportaciones_id_seq RENAME TO exportaciones_nro_exportacion_seq;
ALTER TABLE public.comprobantes_pago   RENAME COLUMN id TO nro_comprobante;
ALTER SEQUENCE public.comprobantes_pago_id_seq RENAME TO comprobantes_pago_nro_comprobante_seq;

-- FK que apuntan a correlativos: toman el nombre de la clave
ALTER TABLE public.ordenes_pago        RENAME COLUMN interes_id TO nro_tarjeta;
ALTER TABLE public.interacciones       RENAME COLUMN interes_id TO nro_tarjeta;
ALTER TABLE public.movimientos_interes RENAME COLUMN interes_id TO nro_tarjeta;
ALTER TABLE public.recordatorios       RENAME COLUMN interes_id TO nro_tarjeta;
ALTER TABLE public.solicitudes_compra  RENAME COLUMN orden_pago_id TO nro_orden;
ALTER TABLE public.orden_pago_descuentos RENAME COLUMN orden_pago_id TO nro_orden;
-- las FK que siguen vivas conservan su columna; se recrean abajo con el nombre nuevo
ALTER TABLE public.comisiones          DROP CONSTRAINT comisiones_solicitud_compra_id_foreign;
ALTER TABLE public.inscripciones_comerciales DROP CONSTRAINT inscripciones_comerciales_solicitud_compra_id_foreign;
ALTER TABLE public.interacciones       DROP CONSTRAINT interacciones_interes_id_foreign;
ALTER TABLE public.movimientos_interes DROP CONSTRAINT movimientos_interes_interes_id_foreign;
ALTER TABLE public.recordatorios       DROP CONSTRAINT recordatorios_interes_id_foreign;
ALTER TABLE public.ordenes_pago        DROP CONSTRAINT ordenes_pago_interes_id_foreign;
ALTER TABLE public.solicitudes_compra  DROP CONSTRAINT solicitudes_compra_orden_pago_id_foreign;
ALTER TABLE public.orden_pago_descuentos DROP CONSTRAINT orden_pago_descuentos_orden_pago_id_foreign;
ALTER TABLE public.pagos               DROP CONSTRAINT pagos_orden_pago_id_foreign;

-- ---------------------------------------------------------------- 5. obligatoriedad, claves compuestas y unicidad
ALTER TABLE public.api_tokens          ALTER COLUMN usuario_correo SET NOT NULL;
ALTER TABLE public.comisiones          ALTER COLUMN vendedor_correo SET NOT NULL;
ALTER TABLE public.interacciones       ALTER COLUMN usuario_correo SET NOT NULL;
ALTER TABLE public.intereses           ALTER COLUMN documento SET NOT NULL;
ALTER TABLE public.log_comandos        ALTER COLUMN usuario_correo DROP NOT NULL;
ALTER TABLE public.ordenes_pago        ALTER COLUMN vendedor_correo SET NOT NULL;
ALTER TABLE public.programas_responsables ALTER COLUMN coordinador_correo SET NOT NULL;
ALTER TABLE public.recordatorios       ALTER COLUMN usuario_correo SET NOT NULL;
ALTER TABLE public.orden_pago_descuentos ALTER COLUMN descuento_codigo SET NOT NULL;
ALTER TABLE public.comprobantes_pago   ALTER COLUMN nro_orden SET NOT NULL;
ALTER TABLE public.exportaciones       ALTER COLUMN nro_solicitud SET NOT NULL;
ALTER TABLE public.orden_pago_descuentos ADD CONSTRAINT orden_pago_descuentos_pkey PRIMARY KEY (nro_orden, descuento_codigo);
ALTER TABLE public.intereses ADD CONSTRAINT intereses_documento_modulo_unique UNIQUE (documento, external_modulo_id);

-- ---------------------------------------------------------------- 6. claves foráneas (nombre = tabla_columna_fk)
ALTER TABLE public.api_tokens ADD CONSTRAINT api_tokens_usuario_correo_fk FOREIGN KEY (usuario_correo)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE CASCADE;
ALTER TABLE public.comisiones ADD CONSTRAINT comisiones_vendedor_correo_fk FOREIGN KEY (vendedor_correo)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.comisiones ADD CONSTRAINT comisiones_nro_solicitud_fk FOREIGN KEY (nro_solicitud)
    REFERENCES public.solicitudes_compra(nro_solicitud) ON DELETE RESTRICT;
ALTER TABLE public.comprobantes_pago ADD CONSTRAINT comprobantes_pago_nro_orden_fk FOREIGN KEY (nro_orden)
    REFERENCES public.pagos(nro_orden) ON DELETE CASCADE;
ALTER TABLE public.configuraciones ADD CONSTRAINT configuraciones_actualizado_por_fk FOREIGN KEY (actualizado_por)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE public.exportaciones ADD CONSTRAINT exportaciones_nro_solicitud_fk FOREIGN KEY (nro_solicitud)
    REFERENCES public.inscripciones_comerciales(nro_solicitud) ON DELETE RESTRICT;
ALTER TABLE public.exportaciones ADD CONSTRAINT exportaciones_documento_fk FOREIGN KEY (documento)
    REFERENCES public.prospectos(documento) ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE public.exportaciones ADD CONSTRAINT exportaciones_usuario_correo_fk FOREIGN KEY (usuario_correo)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE public.inscripciones_comerciales ADD CONSTRAINT inscripciones_comerciales_nro_solicitud_fk FOREIGN KEY (nro_solicitud)
    REFERENCES public.solicitudes_compra(nro_solicitud) ON DELETE RESTRICT;
ALTER TABLE public.interacciones ADD CONSTRAINT interacciones_nro_tarjeta_fk FOREIGN KEY (nro_tarjeta)
    REFERENCES public.intereses(nro_tarjeta) ON DELETE RESTRICT;
ALTER TABLE public.interacciones ADD CONSTRAINT interacciones_usuario_correo_fk FOREIGN KEY (usuario_correo)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.intereses ADD CONSTRAINT intereses_documento_fk FOREIGN KEY (documento)
    REFERENCES public.prospectos(documento) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.intereses ADD CONSTRAINT intereses_motivo_perdida_fk FOREIGN KEY (motivo_perdida)
    REFERENCES public.motivos_perdida(nombre) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.intereses ADD CONSTRAINT intereses_vendedor_correo_fk FOREIGN KEY (vendedor_correo)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE public.log_comandos ADD CONSTRAINT log_comandos_usuario_correo_fk FOREIGN KEY (usuario_correo)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.movimientos_interes ADD CONSTRAINT movimientos_interes_nro_tarjeta_fk FOREIGN KEY (nro_tarjeta)
    REFERENCES public.intereses(nro_tarjeta) ON DELETE RESTRICT;
ALTER TABLE public.movimientos_interes ADD CONSTRAINT movimientos_interes_usuario_correo_fk FOREIGN KEY (usuario_correo)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.notificaciones ADD CONSTRAINT notificaciones_usuario_correo_fk FOREIGN KEY (usuario_correo)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE public.orden_pago_descuentos ADD CONSTRAINT orden_pago_descuentos_nro_orden_fk FOREIGN KEY (nro_orden)
    REFERENCES public.ordenes_pago(nro_orden) ON DELETE CASCADE;
ALTER TABLE public.orden_pago_descuentos ADD CONSTRAINT orden_pago_descuentos_descuento_codigo_fk FOREIGN KEY (descuento_codigo)
    REFERENCES public.descuentos(codigo) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.ordenes_pago ADD CONSTRAINT ordenes_pago_nro_tarjeta_fk FOREIGN KEY (nro_tarjeta)
    REFERENCES public.intereses(nro_tarjeta) ON DELETE RESTRICT;
ALTER TABLE public.ordenes_pago ADD CONSTRAINT ordenes_pago_vendedor_correo_fk FOREIGN KEY (vendedor_correo)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.pagos ADD CONSTRAINT pagos_nro_orden_fk FOREIGN KEY (nro_orden)
    REFERENCES public.ordenes_pago(nro_orden) ON DELETE RESTRICT;
ALTER TABLE public.programas_responsables ADD CONSTRAINT programas_responsables_coordinador_correo_fk FOREIGN KEY (coordinador_correo)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.recordatorios ADD CONSTRAINT recordatorios_nro_tarjeta_fk FOREIGN KEY (nro_tarjeta)
    REFERENCES public.intereses(nro_tarjeta) ON DELETE RESTRICT;
ALTER TABLE public.recordatorios ADD CONSTRAINT recordatorios_usuario_correo_fk FOREIGN KEY (usuario_correo)
    REFERENCES public.usuarios(correo) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE public.solicitudes_compra ADD CONSTRAINT solicitudes_compra_nro_orden_fk FOREIGN KEY (nro_orden)
    REFERENCES public.ordenes_pago(nro_orden) ON DELETE CASCADE;

-- índices que se fueron con las columnas viejas
CREATE INDEX interacciones_usuario_fecha_idx ON public.interacciones (usuario_correo, fecha);
CREATE INDEX movimientos_interes_nro_tarjeta_fecha_idx ON public.movimientos_interes (nro_tarjeta, fecha);
DROP INDEX IF EXISTS public.movimientos_interes_interes_id_fecha_index;
CREATE INDEX notificaciones_usuario_estado_fecha_idx ON public.notificaciones (usuario_correo, estado, fecha_envio);
CREATE INDEX recordatorios_usuario_estado_fecha_idx ON public.recordatorios (usuario_correo, estado, fecha_programada);

-- códigos del catálogo externo: mismo nombre que en catalogo_cache (sin "_id")
ALTER TABLE public.catalogo_cache         RENAME COLUMN external_anuncio_id TO anuncio_codigo;
ALTER TABLE public.intereses              RENAME COLUMN external_modulo_id TO modulo_codigo;
ALTER TABLE public.intereses              RENAME COLUMN external_anuncio_id TO anuncio_codigo;
ALTER TABLE public.solicitudes_compra     RENAME COLUMN external_modulo_id TO modulo_codigo;
ALTER TABLE public.solicitudes_compra     RENAME COLUMN external_anuncio_id TO anuncio_codigo;
ALTER TABLE public.programas_responsables RENAME COLUMN external_programa_id TO programa_codigo;
ALTER TABLE public.intereses RENAME CONSTRAINT intereses_external_modulo_id_foreign TO intereses_modulo_codigo_fk;
ALTER TABLE public.solicitudes_compra
    RENAME CONSTRAINT solicitudes_compra_external_modulo_id_foreign TO solicitudes_compra_modulo_codigo_fk;
ALTER INDEX public.solicitudes_external_modulo_fecha_idx RENAME TO solicitudes_modulo_fecha_idx;
ALTER TABLE public.intereses RENAME CONSTRAINT intereses_documento_modulo_unique TO intereses_documento_modulo_codigo_unique;
ALTER TABLE public.usuarios RENAME CONSTRAINT usuarios_rol_foreign TO usuarios_rol_fk;

-- ---------------------------------------------------------------- 7. vigencia de la credencial
ALTER TABLE public.usuarios ADD COLUMN fecha_inicio date, ADD COLUMN fecha_fin date;
UPDATE public.usuarios SET fecha_inicio = created_at::date, fecha_fin = (created_at + interval '1 year')::date;
UPDATE public.usuarios SET fecha_inicio = CURRENT_DATE, fecha_fin = CURRENT_DATE + 365 WHERE fecha_inicio IS NULL;
ALTER TABLE public.usuarios ALTER COLUMN fecha_inicio SET NOT NULL, ALTER COLUMN fecha_inicio SET DEFAULT CURRENT_DATE,
                            ALTER COLUMN fecha_fin SET NOT NULL;
ALTER TABLE public.usuarios ADD CONSTRAINT usuarios_vigencia_check CHECK (fecha_fin >= fecha_inicio);

-- ---------------------------------------------------------------- 8. triggers con las columnas nuevas
CREATE OR REPLACE FUNCTION public.clasificar_perdida() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.estado <> 'Perdido' THEN NEW.motivo_perdida := NULL;
    ELSIF NEW.motivo_perdida IS NULL THEN NEW.motivo_perdida := 'Sin clasificar (web)';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION public.registrar_movimiento_web() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE anterior text; vendedor_anterior text;
BEGIN
    IF current_setting('crm.canal',true) IS DISTINCT FROM 'correo' THEN
        IF TG_OP='UPDATE' THEN
            anterior := OLD.estado;
            vendedor_anterior := OLD.vendedor_correo;
        END IF;
        IF TG_OP='INSERT' OR NEW.estado IS DISTINCT FROM anterior OR NEW.vendedor_correo IS DISTINCT FROM vendedor_anterior THEN
            INSERT INTO movimientos_interes(nro_tarjeta,usuario_correo,estado_anterior,estado_nuevo,detalle)
            VALUES(NEW.nro_tarjeta, NULLIF(current_setting('crm.usuario_correo',true),''),anterior,NEW.estado,
                'Cambio desde web. Vendedor: ' || COALESCE(vendedor_anterior,'sin asignar') || ' -> ' || COALESCE(NEW.vendedor_correo,'sin asignar'));
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER intereses_clasificar BEFORE INSERT OR UPDATE ON public.intereses FOR EACH ROW EXECUTE FUNCTION public.clasificar_perdida();
CREATE TRIGGER intereses_historial AFTER INSERT OR UPDATE ON public.intereses FOR EACH ROW EXECUTE FUNCTION public.registrar_movimiento_web();

COMMIT;
