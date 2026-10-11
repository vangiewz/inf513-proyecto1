-- =====================================================================
-- CRM de captación de prospectos vía e-mail (Proyecto 1, INF513 SA)
-- Grupo 05 SA · UAGRM FICCT · Gestión 2-2026
-- PostgreSQL 18 · BD db_grupo05sa (db.tecnoweb.org.bo:5432) · esquema crm
-- Ejecutar: psql -h db.tecnoweb.org.bo -U grupo05sa -d db_grupo05sa -f crm_p1.sql
-- =====================================================================

DROP SCHEMA IF EXISTS crm CASCADE;
CREATE SCHEMA crm;
SET search_path TO crm;

-- CU1. Usuarios del sistema: el correo es la identidad del remitente.
CREATE TABLE usuario (
    id         serial       PRIMARY KEY,
    nombre     varchar(60)  NOT NULL,
    apellido   varchar(60)  NOT NULL,
    correo     varchar(120) NOT NULL UNIQUE CHECK (correo = lower(correo)),
    telefono   varchar(20),
    rol        varchar(15)  NOT NULL CHECK (rol IN ('administrador', 'coordinador', 'vendedor')),
    estado     varchar(10)  NOT NULL DEFAULT 'activo' CHECK (estado IN ('activo', 'inactivo')),
    creado_en  timestamptz  NOT NULL DEFAULT now()
);

-- CU2. Réplica local de la oferta que publica el SIS académico (Grupo 06 SA).
-- Programa de N módulos o módulo que se vende por separado (RF2.2).
CREATE TABLE oferta (
    id              serial        PRIMARY KEY,
    codigo          varchar(40)   NOT NULL UNIQUE,
    nombre          varchar(120)  NOT NULL,
    tipo            varchar(10)   NOT NULL CHECK (tipo IN ('programa', 'modulo')),
    programa_id     int           REFERENCES oferta (id),
    num_modulos     int           NOT NULL DEFAULT 1 CHECK (num_modulos > 0),
    precio          numeric(12,2) NOT NULL CHECK (precio >= 0),
    estado          varchar(10)   NOT NULL DEFAULT 'abierto' CHECK (estado IN ('borrador', 'abierto', 'cerrado')),
    coordinador_id  int           REFERENCES usuario (id),
    CHECK ((tipo = 'programa') = (programa_id IS NULL))
);

CREATE TABLE prospecto (
    id              serial       PRIMARY KEY,
    ci              varchar(15)  NOT NULL UNIQUE,
    nombre          varchar(60)  NOT NULL,
    apellido        varchar(60)  NOT NULL,
    telefono        varchar(20),
    correo          varchar(120),
    origen          varchar(15)  NOT NULL CHECK (origen IN ('Facebook', 'Instagram', 'TikTok', 'WhatsApp',
                                                           'Referido', 'Web', 'Correo', 'Feria', 'Otro')),
    registrado_por  int          REFERENCES usuario (id),   -- NULL: se registró solo, por correo
    creado_en       timestamptz  NOT NULL DEFAULT now()
);

-- CU5. Motivos de pérdida clasificados por categoría (RF3.3).
CREATE TABLE motivo_perdida (
    id         serial       PRIMARY KEY,
    nombre     varchar(80)  NOT NULL UNIQUE,
    categoria  varchar(20)  NOT NULL CHECK (categoria IN ('Económico', 'Horario', 'Incompatibilidad', 'Contacto', 'Otro'))
);

-- CU3/CU4. Interés de un prospecto en una oferta = tarjeta del tablero Kanban.
-- vendedor_id NULL = bolsa común (RF1.2).
CREATE TABLE interes (
    id                 serial       PRIMARY KEY,
    prospecto_id       int          NOT NULL REFERENCES prospecto (id) ON DELETE CASCADE,
    oferta_id          int          NOT NULL REFERENCES oferta (id),
    vendedor_id        int          REFERENCES usuario (id),
    estado             varchar(20)  NOT NULL DEFAULT 'Nuevo'
                       CHECK (estado IN ('Nuevo', 'En Contacto', 'Seguimiento', 'Promesa de Pago', 'Convertido', 'Perdido')),
    motivo_perdida_id  int          REFERENCES motivo_perdida (id),
    detalle_perdida    varchar(200),
    asignado_en        timestamptz,
    creado_en          timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (prospecto_id, oferta_id),
    CHECK ((estado = 'Perdido') = (motivo_perdida_id IS NOT NULL))
);

-- CU3/CU4/CU7. Cada cambio de columna del tablero y cada asignación queda en el historial.
CREATE TABLE movimiento (
    id               serial        PRIMARY KEY,
    interes_id       int           NOT NULL REFERENCES interes (id) ON DELETE CASCADE,
    usuario_id       int           REFERENCES usuario (id),
    estado_anterior  varchar(20),
    estado_nuevo     varchar(20)   NOT NULL,
    detalle          varchar(200),
    fecha            timestamptz   NOT NULL DEFAULT clock_timestamp()
);

-- CU7. Interacciones y notas (RF4.1, RF4.2) y recordatorios (RF4.3).
CREATE TABLE interaccion (
    id           serial        PRIMARY KEY,
    interes_id   int           NOT NULL REFERENCES interes (id) ON DELETE CASCADE,
    usuario_id   int           NOT NULL REFERENCES usuario (id),
    tipo         varchar(12)   NOT NULL CHECK (tipo IN ('Llamada', 'WhatsApp', 'Correo', 'Reunión', 'Nota')),
    descripcion  varchar(500)  NOT NULL,
    fecha        timestamptz   NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE recordatorio (
    id           serial        PRIMARY KEY,
    interes_id   int           NOT NULL REFERENCES interes (id) ON DELETE CASCADE,
    usuario_id   int           NOT NULL REFERENCES usuario (id),
    fecha_hora   timestamptz   NOT NULL,
    descripcion  varchar(300)  NOT NULL,
    estado       varchar(10)   NOT NULL DEFAULT 'Pendiente' CHECK (estado IN ('Pendiente', 'Realizado', 'Cancelado')),
    notificado   boolean       NOT NULL DEFAULT false
);

-- CU6. Conversión validada: queda lista para que el SIS (Grupo 06 SA) la matricule.
CREATE TABLE matricula (
    id          serial         PRIMARY KEY,
    interes_id  int            NOT NULL UNIQUE REFERENCES interes (id),
    usuario_id  int            NOT NULL REFERENCES usuario (id),
    monto       numeric(12,2)  NOT NULL,
    estado      varchar(15)    NOT NULL DEFAULT 'Pendiente SIS' CHECK (estado IN ('Pendiente SIS', 'Enviada', 'Confirmada')),
    fecha       timestamptz    NOT NULL DEFAULT now()
);

-- CU7. Bitácora del sistema: cada correo recibido, incluidos los rechazados por rol y los ignorados (spam, automáticos).
CREATE TABLE log_comando (
    id          serial        PRIMARY KEY,
    fecha       timestamptz   NOT NULL DEFAULT now(),
    remitente   varchar(120)  NOT NULL,
    usuario_id  int           REFERENCES usuario (id),
    asunto      varchar(300)  NOT NULL,
    resultado   varchar(10)   NOT NULL CHECK (resultado IN ('Éxito', 'Error', 'Denegado', 'Ignorado')),
    detalle     varchar(300)
);

CREATE INDEX interes_vendedor_estado_idx ON interes (vendedor_id, estado);
CREATE INDEX recordatorio_pendiente_idx  ON recordatorio (estado, fecha_hora) WHERE NOT notificado;

-- ---------------------------------------------------------------------
-- Datos iniciales
-- ---------------------------------------------------------------------

INSERT INTO motivo_perdida (nombre, categoria) VALUES
    ('Precio fuera de su presupuesto', 'Económico'),
    ('Sin financiamiento o plan de pagos', 'Económico'),
    ('Horario incompatible con su trabajo', 'Horario'),
    ('Fecha de inicio no le conviene', 'Horario'),
    ('No cumple los requisitos de admisión', 'Incompatibilidad'),
    ('Eligió otra institución', 'Incompatibilidad'),
    ('No responde llamadas ni mensajes', 'Contacto'),
    ('Datos de contacto erróneos', 'Contacto'),
    ('Otro motivo', 'Otro');

-- Oferta: mismos códigos que el catálogo simulado del CRM web (CatalogoExternoDemo).
INSERT INTO oferta (codigo, nombre, tipo, num_modulos, precio) VALUES
    ('desarrollo-web',   'Desarrollo de Aplicaciones Web',            'programa', 5, 3380),
    ('ia-aplicada',      'Inteligencia Artificial Aplicada',          'programa', 5, 5940),
    ('ciberseguridad',   'Ciberseguridad',                            'programa', 5, 5120),
    ('redes-servidores', 'Redes y Administracion de Servidores',      'programa', 5, 4340),
    ('datos-bi',         'Analisis de Datos y Business Intelligence', 'programa', 5, 4380);

INSERT INTO oferta (codigo, nombre, tipo, programa_id, precio)
SELECT p.codigo || '-m' || m.n, m.nombre, 'modulo', p.id, m.precio
FROM (VALUES
    ('desarrollo-web', 1, 'Fundamentos de Desarrollo Web', 420),
    ('desarrollo-web', 2, 'Desarrollo Frontend', 780),
    ('desarrollo-web', 3, 'Desarrollo Backend', 960),
    ('desarrollo-web', 4, 'Bases de Datos', 680),
    ('desarrollo-web', 5, 'Despliegue de Aplicaciones', 540),
    ('ia-aplicada', 1, 'Fundamentos de Inteligencia Artificial', 600),
    ('ia-aplicada', 2, 'Programacion con Python', 720),
    ('ia-aplicada', 3, 'Machine Learning', 1280),
    ('ia-aplicada', 4, 'Redes Neuronales', 1460),
    ('ia-aplicada', 5, 'Inteligencia Artificial Generativa', 1880),
    ('ciberseguridad', 1, 'Fundamentos de Ciberseguridad', 500),
    ('ciberseguridad', 2, 'Seguridad de Redes', 840),
    ('ciberseguridad', 3, 'Seguridad de Sistemas Operativos', 900),
    ('ciberseguridad', 4, 'Analisis de Vulnerabilidades', 1320),
    ('ciberseguridad', 5, 'Seguridad de Aplicaciones Web', 1560),
    ('redes-servidores', 1, 'Fundamentos de Redes', 460),
    ('redes-servidores', 2, 'Configuracion de Redes', 740),
    ('redes-servidores', 3, 'Administracion de Servidores Linux', 980),
    ('redes-servidores', 4, 'Administracion de Servidores Windows', 980),
    ('redes-servidores', 5, 'Virtualizacion y Contenedores', 1180),
    ('datos-bi', 1, 'Fundamentos de Bases de Datos', 520),
    ('datos-bi', 2, 'SQL para Analisis de Datos', 700),
    ('datos-bi', 3, 'Analisis de Datos con Python', 1060),
    ('datos-bi', 4, 'Business Intelligence con Power BI', 1220),
    ('datos-bi', 5, 'Diseno de Dashboards', 880)
) AS m (programa, n, nombre, precio)
JOIN oferta p ON p.codigo = m.programa;

-- El SIS publica datos-bi como cerrado: sirve para probar la validación de CU6.
UPDATE oferta SET estado = 'cerrado' WHERE codigo = 'datos-bi' OR codigo LIKE 'datos-bi-m%';

-- Los usuarios (con su correo real) se cargan con usuarios.sql, que no se versiona.
