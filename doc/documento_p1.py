"""Genera el documento PUDS del Proyecto 1 (CRM vía e-mail, Grupo 05 SA) con el formato FICCT/APA.
Desde la raíz del repositorio: python doc/documento_p1.py -> doc/P1-INF513-GRUPO05SA.docx
Luego: powershell -File scripts/exportar_documento.ps1
Estructura: la del P1 aprobado (Grupo 17 SA) — 12 secciones del Anexo B y Desarrollo por flujos del PUDS.
Lo que aún no existe (figuras de Enterprise Architect, datos de la empresa) sale en rojo y se lista al final."""
import csv, json, pathlib, re, sys, textwrap
import xml.etree.ElementTree as ET

P1 = pathlib.Path(__file__).resolve().parents[1]
DOC = P1 / 'doc'
RAIZ = P1.parent
HERRAMIENTAS = RAIZ / 'tools'
if not (HERRAMIENTAS / 'doc_ficct.py').is_file():
    HERRAMIENTAS = DOC / 'lib'
sys.path.insert(0, str(HERRAMIENTAS))
import doc_ficct
if not doc_ficct.LOGO.is_file():
    doc_ficct.LOGO = DOC / 'recursos' / 'logo-ficct.png'
from doc_ficct import Informe
from docx.shared import RGBColor

DIA = DOC / 'diagramas'
CAP = DOC / 'capturas'
SQL = (P1 / 'sql' / 'esquema.sql').read_text(encoding='utf-8')
MATRIZ = list(csv.reader((DOC / 'matriz-acceso.tsv').read_text(encoding='utf-8').splitlines(), delimiter='\t'))[1:]
N_CMD = sum(1 for f in MATRIZ if f[0] != 'General')   # comandos de los CU (sin HELP)
sys.path.insert(0, str(DOC / 'ea'))
sys.path.insert(0, str(DOC))
import ea_p1   # noqa: E402  el modelo de datos se lee igual que para los diagramas
TABLAS, FKS, PKS, UNICAS = ea_p1.tablas_sql(), ea_p1.fks_sql(), ea_p1.pks_sql(), ea_p1.unicas_sql()
PENDIENTES = []

# Datos que el grupo aún debe entregar: None = pendiente (se imprime en rojo).
GITHUB = 'https://github.com/vangiewz/inf513-proyecto1'
FECHA = '20 de noviembre de 2026'

d = Informe()


def rojo(texto):
    PENDIENTES.append(texto)
    par = d.p(f'[PENDIENTE: {texto}]', sangria=False)
    for r in par.runs:
        r.font.color.rgb = RGBColor(0xC0, 0x00, 0x00); r.bold = True
    return par


def fig(archivo, titulo, ancho=16.0, nota=None, horizontal=None, titulos=()):
    """Figura de EA. horizontal=True: va sola en una página apaisada (diagramas anchos, legibles al imprimir).
    Sin indicarlo, un diagrama grande va en la orientación en que su letra impresa sale más grande.
    titulos: [(texto, nivel)] que encabezan la página apaisada, para que no queden huérfanos en la página anterior."""
    ruta = DIA / archivo
    if ruta.exists():
        import pymupdf
        px = pymupdf.Pixmap(str(ruta))
        w, h = px.width, px.height
        if w >= 1600 or horizontal is None:   # la orientación en que la letra impresa sale más grande
            horizontal = w >= 1600 and min(650 / w, 428 / h) > min(468 / w, 588 / h)
        if not horizontal and w >= 1600: ancho = 16.0
    horizontal = bool(horizontal)
    if horizontal: d.orientacion(True); ancho = 22.9
    for t, nivel in titulos: d.h(t, nivel)
    if ruta.exists():
        import pymupdf
        px = pymupdf.Pixmap(str(ruta))
        alto_max = (13.2 if horizontal else 20.0) - 1.7 * len(titulos)   # reservar rótulo y títulos en la misma página
        n_parrafos = len(d.doc.paragraphs)
        d.figura(ruta, titulo, nota, min(ancho, alto_max * px.width / px.height))
        d.doc.paragraphs[n_parrafos].paragraph_format.keep_with_next = True
        imagen = d.doc.paragraphs[n_parrafos + 1].paragraph_format
        imagen.line_spacing = 1
        imagen.space_before = imagen.space_after = 0
    else:
        d.rotulo('Figura', titulo)
        rojo(f'figura de Enterprise Architect diagramas/{archivo} (lista en P1_CORREO.md § Diagramas)')
    if horizontal: d.orientacion(False)


def dato(valor, que):
    return valor if valor else f'[PENDIENTE: {que}]'


def comandos(cu, rol=None):
    col = {'Administrador': 2, 'Coordinador': 3, 'Vendedor': 4, 'Público': 5}
    return [f[1] for f in MATRIZ if f[0] == cu and (rol is None or f[col[rol]] == 'Sí')]


def actores(cu):
    nombres = ('Administrador', 'Coordinador', 'Vendedor', 'Visitante')
    return [n for n, i in zip(nombres, range(2, 6)) if any(f[0] == cu and f[i] == 'Sí' for f in MATRIZ)]


# ---------------------------------------------------------------- carátula, revisión e índices
d.caratula('Sistema CRM de Captación de Prospectos vía E-mail',
           'Proyecto 1: Sistema vía E-Mail — Documento del Proceso Unificado de Desarrollo de Software (PUDS)', FECHA)
d.revision('Datos para la Revisión', [
    ('Correo del sistema', 'grupo05sa@tecnoweb.org.bo', True),
    ('Primer comando', 'Enviar un correo a esa dirección con el asunto HELP[]', True),
    ('Cuentas de prueba', 'Administrador, coordinador y vendedores registrados por el grupo; se muestran en la defensa', False),
    ('Repositorio', dato(GITHUB, 'URL de GitHub inf513-proyecto1'), False),
    ('Acceso público al repositorio', 'La última verificación conservada identificó el repositorio como privado. Esta revisión se prepara localmente, sin publicación ni cambio de visibilidad.', False),
    ('Base de datos', 'PostgreSQL 18.6, db.tecnoweb.org.bo:5432, db_grupo05sa, esquema public (compartido con el Proyecto 2)', False),
    ('Estado de la versión', 'Bandeja, catálogo, persona y seguridad se verifican localmente. Las tres migraciones y el circuito institucional de esta revisión están pendientes. El JAR valida la estructura antes de procesar el buzón.', False),
    ('Ejecución', 'p1/ejecutar.ps1 en la PC del grupo; consulta el buzón con espera mínima de tres segundos después de cada ciclo, guarda cada correo en la base de datos antes de borrarlo del buzón y deja cada ciclo, cada respuesta y cada falla en p1/logs/crm-mail.0.log', False),
    ('Diagnóstico', 'p1/ejecutar.ps1 -Diagnostico revisa DNS, puertos, POP3, SMTP, IMAP de rebotes y la base de datos', False),
], 'La identidad registrada requiere firma HMAC individual válida y el rol se consulta en la cuenta vigente (el administrador crea las demás con '
   'INSERTARUSUARIO). Un correo no registrado actúa como visitante (consulta la oferta y registra su interés).')
d.indices()
# El salto en un párrafo vacío puede ocupar una hoja entera al crecer el índice automático.
# Solo ajustamos estos encabezados de P1, sin cambiar la herramienta compartida de otros informes.
for par in d.doc.paragraphs:
    if par.text in ('ÍNDICE DE FIGURAS', 'ÍNDICE DE TABLAS'):
        anterior = par._p.getprevious()
        if anterior is not None and anterior.xpath('.//w:br[@w:type="page"]'):
            anterior.getparent().remove(anterior)
        par.paragraph_format.page_break_before = True

# ---------------------------------------------------------------- I
d.h('I. Introducción', 1)
d.p('El presente documento describe el análisis, diseño, implementación y despliegue de un sistema de gestión de relaciones con '
    'clientes (CRM) orientado a la captación de prospectos y su conversión en matrícula, operado exclusivamente por correo '
    'electrónico. La unidad académica que comercializa programas de formación (programas de varios módulos y módulos '
    'independientes) trabaja hoy con planillas y mensajes sueltos: los prospectos se reparten a mano, su avance no queda '
    'registrado y las pérdidas no se analizan.')
d.p('El sistema recibe en el asunto de un correo enviado a grupo05sa@tecnoweb.org.bo un comando con sus parámetros, por ejemplo '
    '`MOVERTARJETA["7","En Contacto"]`; identifica una cuenta candidata por su dirección y verifica su firma HMAC individual antes de comprobar que su rol (administrador, coordinador '
    'o vendedor) pueda usar ese comando, lo ejecuta sobre una base de datos PostgreSQL y le responde con un correo HTML.')
d.p('El desarrollo siguió el Proceso Unificado de Desarrollo de Software (PUDS) con UML, modelado en Enterprise Architect. '
    'Como resultado se entrega un servicio Java que lee el buzón por POP3 con sockets propios, responde por SMTP, implementa los '
    f'ocho casos de uso aprobados con {N_CMD} comandos y se ejecuta en un equipo del grupo. Cada correo se guarda en la base de '
    'datos antes de borrarlo del buzón y su respuesta se reintenta con una política acotada, sin volver a ejecutar una operación '
    'confirmada. Las pruebas locales verifican recuperación de solicitudes ante caídas; los fallos permanentes quedan trazables '
    'y no se promete entrega de correo exactamente una vez.')

# ---------------------------------------------------------------- II
d.h('II. Antecedentes y Justificación', 1)
d.p('El Grupo 05 SA tiene aprobados por el docente, el 5 de octubre de 2026, ocho casos de uso para un CRM de captación de '
    'prospectos con tablero Kanban, que consume la oferta académica publicada por el sistema académico del Grupo 06 SA. '
    'El mismo modelo de negocio se implementa como sistema web en el Proyecto 2; este primer proyecto lo resuelve con correo '
    'electrónico, sobre los protocolos y servicios estudiados en la Unidad III (sockets, SMTP, POP3 y PostgreSQL).')
d.p('Desde lo teórico, el trabajo muestra que los protocolos estándar de Internet pueden servir como interfaz de una aplicación '
    'transaccional: el asunto del correo hace de petición y la respuesta de correo, de resultado. Desde lo metodológico, el PUDS '
    'ordena ocho casos de uso en flujos de requisitos, análisis, diseño e implementación trazables entre sí. Desde lo práctico, '
    'el vendedor trabaja desde el celular (RNF1) con la aplicación de correo que ya usa, sin instalar nada.')

# ---------------------------------------------------------------- III
d.h('III. El Problema', 1)
d.h('Situación Problemática', 2)
d.p('La captación de interesados en los programas de formación se registra en planillas y conversaciones de WhatsApp de cada '
    'vendedor. No existe una cartera por vendedor ni una bolsa común de prospectos sin atender; la asignación depende de quien '
    'responda primero; el avance de cada interesado (contacto, seguimiento, promesa de pago) no queda registrado, y cuando un '
    'prospecto se pierde no se sabe por qué.')
d.p('Esto ocurre porque no hay un sistema que centralice los prospectos, controle qué ve cada rol y conserve el historial de '
    'interacciones. De continuar así, se seguirán perdiendo interesados por falta de seguimiento oportuno, la carga entre '
    'vendedores será desigual y la coordinación no tendrá datos para decidir sobre la oferta.')
d.h('Formulación del Problema', 2)
d.p('¿De qué manera un sistema CRM operado mediante comandos de correo electrónico, con control de acceso por rol, puede '
    'organizar la captación, asignación, seguimiento y conversión a matrícula de los prospectos de la oferta académica?')
d.h('Situación Deseada', 2)
d.lista(['El administrador gestiona los usuarios y sus roles, y pone cada programa a cargo de un coordinador.',
         'Los prospectos se registran uno a uno o por importación de un archivo CSV, y un visitante puede registrar su propio interés por correo.',
         'La bolsa común se reparte por menor carga entre los vendedores activos y cada vendedor recibe un aviso por correo.',
         'Cada interés es una tarjeta de un tablero Kanban de seis etapas; perderla exige un motivo clasificado.',
         'La venta se cierra con una orden de pago, un comprobante revisado por coordinación y la inscripción comercial, previa validación de la ficha.',
         'Cada tarjeta guarda su historial cronológico y los recordatorios llegan por correo a su hora.',
         'Coordinación y administración obtienen reportes del embudo, de vendedores, de pérdidas y por oferta.'])
d.h('Descripción de la Empresa', 2)
d.p('La **Unidad de Capacitación** de la Facultad de Ingeniería en Ciencias de la Computación y Telecomunicaciones (FICCT) de '
    'la Universidad Autónoma Gabriel René Moreno (UAGRM) es la dependencia que organiza y vende la oferta de formación continua '
    'de la facultad. Atiende a estudiantes y egresados de la FICCT y a cualquier persona que quiera actualizarse en tecnología.')
d.h('Reseña histórica', 3)
d.p('La unidad nació para ordenar los cursos cortos que la facultad dictaba de forma aislada en sus laboratorios. Con el tiempo '
    'agrupó esos cursos en programas de cinco módulos que se pueden cursar completos o por módulo. Hoy oferta cinco programas: '
    'Desarrollo de Aplicaciones Web, Inteligencia Artificial Aplicada, Ciberseguridad, Redes y Administración de Servidores, y '
    'Análisis de Datos y Business Intelligence. La demanda creció más rápido que su personal, y la captación de interesados se '
    'sigue haciendo con planillas y mensajes, que es el problema que aborda este proyecto.')
d.h('Misión', 3)
d.p('Ofrecer a estudiantes, profesionales y a la comunidad programas de capacitación en tecnologías de la información, '
    'prácticos y al día con el mercado, con el respaldo académico de la FICCT y a un costo accesible.')
d.h('Visión', 3)
d.p('Ser la referencia en formación continua en tecnología de Santa Cruz, reconocida por la calidad de sus programas y por '
    'acompañar a cada interesado desde la primera consulta hasta su inscripción.')
d.h('Servicios', 3)
d.lista(['Programas de cinco módulos con certificación de la facultad.',
         'Módulos independientes para quien necesita un tema puntual.',
         'Asesoría a los interesados para elegir programa, horario y forma de pago.'])
d.h('Imagen corporativa, web y redes', 3)
d.p('La unidad no tiene un logotipo ni un sitio propios: usa la imagen institucional de la FICCT y de la UAGRM, y difunde su '
    'oferta por los canales oficiales de la universidad (uagrm.edu.bo) y de la facultad. La inscripción académica la registra el '
    'sistema académico de la facultad (el SIS del Grupo 06 SA), que también publica la oferta que consume este CRM.')
d.h('Estructura Organizacional', 2)
d.p('La Unidad de Capacitación es un departamento pequeño de la Facultad de Ingeniería en Ciencias de la Computación y '
    'Telecomunicaciones (FICCT) que oferta cursos, programas de varios módulos y módulos independientes a estudiantes de la '
    'facultad y a cualquier persona que quiera inscribirse. No tiene personería propia: depende del Decanato y su personal es '
    'reducido. Su estructura se organiza en tres niveles, y cada cargo corresponde a un rol del sistema, de modo que los actores '
    'de la Captura de Requisitos salen de este organigrama.')
d.h('Nivel directivo', 3)
d.lista(['Decanato de la FICCT: autoridad de la que depende la unidad; aprueba la oferta de cursos y programas.',
         'Jefatura de la Unidad: un jefe de unidad dirige la oferta, el personal y las metas de inscripción. En el sistema es el '
         'administrador: gestiona los usuarios y sus roles y pone cada programa a cargo de un coordinador.'])
d.h('Nivel de coordinación', 3)
d.lista(['Coordinación de Programas: dos coordinadores, cada uno a cargo de un grupo de programas. Registran e importan '
         'prospectos, reparten la bolsa común, supervisan el avance de sus programas y revisan los motivos de pérdida. En el '
         'sistema tienen el rol coordinador.'])
d.h('Nivel operativo', 3)
d.lista(['Equipo Comercial: asesores comerciales que contactan a los interesados, en su mayoría desde el celular, les dan '
         'seguimiento y cierran la inscripción. En el sistema tienen el rol vendedor.'])
d.p('Las personas interesadas en un curso no forman parte de la unidad: cuando escriben sin estar registradas, el sistema las '
    'atiende como visitantes.')
fig('organigrama.png', 'Organigrama de la Unidad de Capacitación', ancho=11.0,
    nota='Elaboración propia. Cada cargo indica el rol que ocupa en el sistema.')
d.h('Ubicación e Infraestructura', 2)
d.p('La unidad funciona en el edificio de la FICCT, módulo 236 de la Ciudad Universitaria de la UAGRM, sobre la avenida '
    'Dr. Enrique Aponte Canseco, entre la avenida Busch y la avenida 26 de Febrero (segundo anillo), en la ciudad de Santa Cruz de '
    'la Sierra, Bolivia (17°46\'34" S, 63°11\'42" O).')
fig('ubicacion.png', 'Ubicación de la FICCT en Santa Cruz de la Sierra y en la Ciudad Universitaria',
    nota='Elaboración propia sobre mapas de OpenStreetMap (© colaboradores de OpenStreetMap, licencia ODbL). Coordenadas del '
         'edificio según OpenStreetMap, consultado el 13 de octubre de 2026.')
d.p('Por ser una unidad pequeña, ocupa una oficina con tres áreas de trabajo y comparte el resto de la infraestructura de la '
    'facultad:')
d.lista(['Oficina de la jefatura y de la coordinación, donde también se atiende a los interesados que llegan en persona.',
         'Área del equipo comercial; los asesores trabajan casi siempre fuera de ella, desde el celular.',
         'Laboratorios de computación de la FICCT, donde se dictan los módulos en horarios que no chocan con las materias de la carrera.',
         'Servicios de red y correo de la facultad (tecnoweb.org.bo), sobre los que funciona el sistema de este proyecto.'])

# ---------------------------------------------------------------- IV
d.h('IV. Objetivos', 1)
d.h('Objetivo General', 2)
d.p('Desarrollar un sistema CRM de captación de prospectos operado por correo electrónico (POP3 y SMTP), con control de acceso '
    'por rol y base de datos PostgreSQL, que organice la captación, asignación, seguimiento y conversión a matrícula de los '
    'interesados en la oferta académica.')
d.h('Objetivos Específicos', 2)
OBJ = ['Identificar los actores, los ocho casos de uso y la matriz de acceso por rol a partir de los requerimientos aprobados.',
       'Analizar los casos de uso en paquetes y diagramas de colaboración que muestren la interacción entre las clases de control y de entidad.',
       'Diseñar la arquitectura en cuatro capas, el modelo de datos relacional y el despliegue sobre servidores reales.',
       'Implementar en Java el lector POP3 con sockets, el emisor SMTP, el intérprete de comandos con control de rol y los servicios de cada caso de uso.',
       'Poner en marcha el sistema en un equipo del grupo con un registro que diagnostique las fallas de red, correo y base de datos, '
       'y validarlo con pruebas automáticas y con correos reales por cada rol.']
d.lista(OBJ, numerada=True)

# ---------------------------------------------------------------- V
d.h('V. Metodología', 1)
d.p('Se adoptó el Proceso Unificado de Desarrollo de Software, dirigido por casos de uso, centrado en la arquitectura, iterativo '
    'e incremental (Jacobson, Booch y Rumbaugh, 2000), con UML como lenguaje de modelado (Booch, Rumbaugh y Jacobson, 2006). '
    'Cada fase se ligó a un objetivo específico:')
d.tabla('Fases del PUDS y objetivos que cumplen', ['Fase', 'Actividades', 'Herramientas', 'Objetivo'], [
    ('Inicio', 'Actores, casos de uso, priorización y matriz de acceso', 'Enterprise Architect 15', '1'),
    ('Elaboración', 'Paquetes, colaboración, capas, despliegue, secuencia, clases y modelo de datos', 'Enterprise Architect 15, PostgreSQL 18.6', '2 y 3'),
    ('Construcción', 'Programación por capas, script SQL y pruebas unitarias', 'Java 21, Maven 3.9.9, JUnit 5.10.5, Git', '4'),
    ('Transición', 'Ejecución local, fallas provocadas y preparación de la validación institucional; los correos reales conservados corresponden a una revisión anterior', 'Windows, Oracle JDK 23.0.1 con release 21', '5'),
], anchos=[2.6, 7.2, 4.6, 1.6])

# ---------------------------------------------------------------- VI
d.h('VI. Marco Teórico', 1)
d.h('Proceso Unificado y UML', 2)
d.p('El Proceso Unificado organiza el desarrollo en las fases de inicio, elaboración, construcción y transición, y en flujos de '
    'trabajo de requisitos, análisis, diseño, implementación y pruebas; los casos de uso guían todos los flujos y la arquitectura '
    'se valida temprano (Jacobson et al., 2000). El enfoque iterativo reduce los riesgos técnicos al entregar incrementos que '
    'funcionan (Pressman, 2010). UML aporta los diagramas de casos de uso, colaboración, secuencia, clases, componentes y '
    'despliegue con los que se documenta cada flujo (Booch et al., 2006).')
d.h('Correo electrónico: SMTP, POP3 y MIME', 2)
d.p('El correo se transfiere entre servidores con SMTP (Klensin, 2008); el servidor de destino lo deja en el buzón del usuario, '
    'de donde un cliente lo descarga con POP3, un protocolo de texto con las órdenes USER, PASS, STAT, LIST, RETR, DELE y QUIT '
    'en el que los borrados se confirman recién con QUIT (Myers y Rose, 1996). El contenido sigue el formato MIME, que permite '
    'cabeceras en otros juegos de caracteres y adjuntos (Freed y Borenstein, 1996). STARTTLS cifra la sesión SMTP sobre la misma '
    'conexión (Hoffman, 2002), y las respuestas automáticas se marcan con la cabecera Auto-Submitted para evitar bucles entre '
    'contestadores (Moore, 2004). Kurose y Ross (2017) describen este circuito completo entre agentes de usuario y servidores.')
d.h('Sockets y Jakarta Mail', 2)
d.p('Un socket TCP es el extremo de una conexión entre dos procesos; sobre él, la aplicación intercambia las líneas de texto del '
    'protocolo (Tanenbaum y Wetherall, 2012). Jakarta Mail es la especificación estándar de Java para construir, analizar y '
    'enviar mensajes MIME (Eclipse Foundation, 2021). En este proyecto la lectura POP3 usa sockets propios y Jakarta Mail se usa '
    'para analizar el mensaje y para el envío SMTP autenticado.')
d.h('Patrón Command y arquitectura en capas', 2)
d.p('El patrón Command encapsula una petición como un objeto para que pueda interpretarse, registrarse y despacharse a quien la '
    'ejecuta (Gamma, Helm, Johnson y Vlissides, 2003). La arquitectura en capas separa presentación, aplicación, dominio y '
    'persistencia, de modo que cada capa solo depende de la inferior (Pressman, 2010).')
d.h('Bases de datos relacionales y transacciones', 2)
d.p('Una transacción agrupa varias operaciones que deben cumplirse todas o ninguna (atomicidad), y el bloqueo de filas evita que '
    'dos transacciones concurrentes modifiquen el mismo dato (Silberschatz, Korth y Sudarshan, 2014). PostgreSQL implementa '
    'transacciones, restricciones de integridad (claves primarias y foráneas, CHECK, UNIQUE) y la cláusula SELECT ... FOR UPDATE '
    '(The PostgreSQL Global Development Group, 2026).')
d.h('Entrega confiable de mensajes', 2)
d.p('Entre dos sistemas que se comunican por mensajes, una caída puede ocurrir después de recibir un mensaje y antes de '
    'procesarlo, o después de procesarlo y antes de responder. La entrega garantizada guarda cada mensaje en un almacén '
    'persistente antes de darlo por recibido, y el receptor idempotente reconoce un mensaje repetido por su identificador para no '
    'procesarlo dos veces (Hohpe y Woolf, 2003). Message-ID cumple la función de identificador del mensaje de protocolo '
    '(Resnick, 2008), pero no se considera una prueba suficiente de unicidad o autenticidad de una entrada externa. Cuando la operación y el mensaje de salida se guardan en la misma transacción, el envío puede '
    'reintentarse sin repetir la operación; como la confirmación del envío también puede perderse, el destinatario puede '
    'recibir la misma respuesta más de una vez. La separación entre operación confirmada y envío permite reintentar la respuesta '
    'sin repetir esa operación (Kleppmann, 2017).')
d.h('Seguridad: control de acceso por rol e inyección SQL', 2)
d.p('El control de acceso basado en roles asigna permisos a roles y roles a usuarios, en lugar de permisos a cada persona '
    '(Sandhu, Coyne, Feinstein y Youman, 1996). La inyección SQL se evita enviando los datos del usuario como parámetros de '
    'sentencias preparadas y nunca concatenados al texto de la consulta (OWASP Foundation, s.f.).')
d.h('CRM y embudo de ventas', 2)
d.p('La gestión de relaciones con clientes reúne la información de cada prospecto para sostener el contacto a lo largo del '
    'proceso de compra; el embudo de ventas representa las etapas por las que avanza un prospecto hasta convertirse en cliente '
    'o perderse (Kotler y Keller, 2016).')

# ---------------------------------------------------------------- VII
d.h('VII. Desarrollo', 1)
d.h('Flujo de Trabajo: Captura de Requisitos', 2)
d.h('Identificar actores y casos de uso', 3)
d.tabla('Actores del sistema', ['Actor', 'Descripción', 'Responsabilidades'], [
    ('Administrador', 'Responsable del sistema y de la unidad comercial.', 'Gestiona usuarios y roles, asigna coordinadores a los programas, ve todo y consulta la bitácora del sistema.'),
    ('Coordinador', 'Encargado de los programas a su cargo (RF1.3).', 'Registra e importa prospectos, reparte la bolsa común entre los vendedores, aprueba o rechaza los pagos, clasifica motivos de pérdida y consulta reportes de sus programas.'),
    ('Vendedor', 'Asesor comercial que trabaja desde el celular (RNF1).', 'Atiende su cartera (no se autoasigna prospectos): mueve tarjetas, registra interacciones y recordatorios, genera órdenes de pago, adjunta comprobantes y convierte a matrícula.'),
    ('Visitante', 'Persona cuyo correo no está registrado.', 'Consulta la oferta abierta y registra su propio interés.'),
], anchos=[3.0, 5.0, 8.0])
d.tabla('Casos de uso aprobados', ['ID', 'Caso de uso'], [
    ('CU1', 'Gestión de Usuarios (Propietario, Clientes)'), ('CU2', 'Gestión de Captar, Consumir Oferta'),
    ('CU3', 'Gestión de Asignar Prospectos (Leads)'), ('CU4', 'Gestión de Embudo de Ventas en Tablero Kanban'),
    ('CU5', 'Gestión de Pérdida de Prospectos y Clasificación de Motivos'), ('CU6', 'Gestión de Validar Datos y Procesar Conversión a Matrícula'),
    ('CU7', 'Gestión de Bitácora, Interacciones y Recordatorios'), ('CU8', 'Reportes y Estadísticas')],
    anchos=[1.6, 14.4], nota_txt='Títulos tal como los aprobó el docente el 05/10/2026. En CU1 los actores son los roles de RF1.1: administrador, coordinador y vendedor.')
d.h('Lista de requerimientos', 3)
d.p('Los requerimientos son los que el grupo publicó en el foro de la materia el 14/09/2026, sobre los cuales el docente aprobó '
    'los ocho casos de uso el 05/10/2026. La tabla indica el caso de uso que cubre cada uno y cómo se resuelve en este proyecto '
    'vía correo; lo propio de una interfaz web queda para el Proyecto 2.')
d.tabla('Requerimientos publicados y caso de uso que los cubre', ['Código', 'Requerimiento', 'CU', 'En el Proyecto 1 (correo)'], [
    ('RF1.1', 'Acceso con credenciales para tres roles (RBAC)', 'CU1', 'Firma HMAC individual, rol y vigencia en usuarios'),
    ('RF1.2', 'El vendedor ve su cartera o la bolsa común', 'CU3, CU4', 'Alcance por rol en cada consulta'),
    ('RF1.3', 'El coordinador ve las versiones de programas a su cargo', 'CU2, CU8', 'ASIGNARCOORDINADOR y alcance por programa'),
    ('RF2.1', 'Registro manual o masivo (CSV) de prospectos', 'CU2', 'REGISTRARPROSPECTO, IMPORTARPROSPECTOS con .csv adjunto'),
    ('RF2.2', 'Interés vinculado al catálogo (programa o módulo)', 'CU2', 'Catálogo simulado en catalogo_cache (API del SIS pendiente)'),
    ('RF2.3', 'Asignación manual o automática Round-Robin', 'CU3', 'ASIGNARPROSPECTO y ASIGNARAUTOMATICO por menor carga'),
    ('RF3.1', 'Tablero Kanban con los prospectos como tarjetas', 'CU4', 'VERTABLERO responde el tablero en carriles'),
    ('RF3.2', 'Etapas Nuevo, En Contacto, Seguimiento, Promesa de Pago, Convertido, Perdido', 'CU4', 'Las seis etapas en MOVERTARJETA y VERTABLERO'),
    ('RF3.3', 'Motivo obligatorio al pasar a Perdido', 'CU5', 'MARCARPERDIDO con motivo clasificado'),
    ('RF3.4', 'Validar datos antes de la matrícula', 'CU6', 'VALIDARCONVERSION, orden, comprobante y APROBARPAGO'),
    ('RF4.1', 'Historial cronológico de interacciones', 'CU7', 'VERHISTORIAL'),
    ('RF4.2', 'Notas manuales', 'CU7', 'REGISTRARINTERACCION de tipo Nota'),
    ('RF4.3', 'Recordatorios de contacto', 'CU7', 'PROGRAMARRECORDATORIO; el aviso llega por correo'),
    ('RNF1', 'Interfaz responsive (vendedores desde el celular)', '—', 'La respuesta HTML se lee en la aplicación de correo del celular'),
    ('RNF2', 'Cliente API del catálogo del Proyecto 2 (SIS)', 'CU2, CU6', 'Simulado: catálogo local y exportación CSV; API pendiente'),
], anchos=[1.5, 5.8, 1.9, 6.8], tam=9)
d.h('Modelo de dominio inicial', 3)
d.p('El modelo de dominio inicial muestra solo las entidades del negocio, sin atributos. Se obtuvo de los sustantivos de los '
    'requerimientos anteriores: no hubo otra reunión de levantamiento. Cada entidad indica, en sus notas del modelo de Enterprise '
    'Architect, el requerimiento del que sale. Pagos, comprobantes, descuentos, comisiones, exportaciones y la bitácora '
    'aparecieron después, al detallar el CU6 y el CU7, y están en el modelo de dominio final (modelo conceptual, en el Diseño).')
fig('dominio-inicial.png', 'Modelo de dominio inicial', ancho=14,
    nota='Entidad y requerimiento del que sale: ' + '; '.join(f'{e} ({rf})' for e, rf in ea_p1.DOMINIO_INICIAL.items()) + '.')
d.h('Priorizar casos de uso', 3)
PRIO = {'CU1': 'Alta', 'CU2': 'Alta', 'CU3': 'Alta', 'CU4': 'Alta', 'CU5': 'Media', 'CU6': 'Alta', 'CU7': 'Media', 'CU8': 'Baja'}
d.tabla('Priorización de casos de uso', ['ID', 'Actores involucrados', 'Comandos', 'Prioridad'],
        [(cu, ', '.join(actores(cu)), str(len(comandos(cu))), PRIO[cu]) for cu in PRIO], anchos=[1.6, 9.0, 2.4, 3.0])

CU = {
    'CU1': ('Gestión de Usuarios', 'Registrar, listar, modificar y dar de baja a los usuarios del CRM con su rol y la vigencia de su credencial (fecha de inicio y de fin).',
            'Administrador', 'El remitente es un administrador autenticado con firma HMAC, activo y con la credencial vigente.',
            ['El administrador envía INSERTARUSUARIO, MODIFICARUSUARIO, ELIMINARUSUARIO, LISTARUSUARIOS, LISPER o INSPER con sus parámetros en el asunto.',
             'El sistema descarga el correo por POP3, lo guarda en la bitácora (resultado Recibido) antes de borrarlo del buzón y CommandParser separa el comando y sus parámetros.',
             'App valida la firma individual antes de que CommandProcessor identifique al usuario y AdministradorCommandProcessor compruebe el permiso.',
             'UsuarioService valida nombre, correo, teléfono, rol y vigencia (fecha_fin no anterior a fecha_inicio; por omisión, un año) y ejecuta la operación en una transacción. El usuario se identifica por su correo.',
             'Al dar de baja a un vendedor, sus tarjetas abiertas vuelven a la bolsa común.',
             'El sistema responde con un correo HTML con el resultado.'],
            'El usuario queda registrado o actualizado y puede enviar comandos firmados mientras su credencial esté vigente. LISPER e INSPER consultan o registran persona sin crear un usuario CRM.',
            'Correo duplicado, rol inexistente, datos o fechas inválidos, intento de cambiar el propio rol, cuenta inactiva, credencial vencida o todavía no vigente, o un rol sin permiso: el sistema responde con un correo de error o de acceso denegado que indica la causa.'),
    'CU2': ('Gestión de Captar, Consumir Oferta', 'Consultar la oferta publicada por el SIS académico (Grupo 06 SA) y registrar prospectos con su interés: uno a uno, por CSV o por el propio visitante.',
            'Administrador, Coordinador, Vendedor o Visitante', 'La oferta elegida está abierta para inscripción.',
            ['El actor envía LISTAROFERTA[], REGISTRARPROSPECTO, IMPORTARPROSPECTOS (con un .csv adjunto), LISTARPROSPECTOS, MODIFICARPROSPECTO o ELIMINARPROSPECTO; el visitante, REGISTRARINTERES; el administrador pone cada programa a cargo de un coordinador con ASIGNARCOORDINADOR.',
             'El sistema lee el correo, interpreta el comando y verifica el rol del remitente.',
             'ProspectoService valida CI, teléfono, correo, origen y que la oferta esté abierta (y a cargo del coordinador, si es coordinador).',
             'Crea el prospecto si su CI no existe y una tarjeta en la etapa Nuevo: en la bolsa común o en la cartera del vendedor que lo registró.',
             'El sistema responde con la tarjeta creada o con el resultado de cada fila del CSV.'],
            'El prospecto y su interés quedan registrados con su movimiento inicial en el historial.',
            'Oferta cerrada o inexistente, CI o teléfono inválidos, interés duplicado o fila de CSV incompleta: se informa el error de esa fila o del comando.'),
    'CU3': ('Gestión de Asignar Prospectos', 'Repartir los prospectos de la bolsa común entre los vendedores: uno a uno o automáticamente por menor carga. El vendedor no se autoasigna.',
            'Administrador o Coordinador', 'Existen tarjetas abiertas sin vendedor y vendedores activos.',
            ['El actor envía LISTARBOLSA[], ASIGNARPROSPECTO["nro_tarjeta","correo_vendedor"] o ASIGNARAUTOMATICO[].',
             'El sistema verifica el rol y el alcance (el coordinador solo reparte los programas a su cargo).',
             'AsignacionService bloquea las tarjetas (SELECT ... FOR UPDATE) y las reparte al vendedor activo con menos carga, manteniendo juntos los intereses de un mismo prospecto.',
             'Registra la asignación en el historial de cada tarjeta.',
             'Responde al actor y envía a cada vendedor un correo con los prospectos que recibió.'],
            'Las tarjetas quedan en la cartera de un vendedor.',
            'Bolsa vacía, sin vendedores activos, tarjeta ya asignada o un vendedor que intenta asignarse: se responde con el motivo o como acceso denegado.'),
    'CU4': ('Gestión de Embudo de Ventas en Tablero Kanban', 'Ver el tablero de seis etapas y mover las tarjetas entre Nuevo, En Contacto, Seguimiento y Promesa de Pago.',
            'Administrador, Coordinador o Vendedor', 'La tarjeta está en la cartera del vendedor o en el alcance del coordinador.',
            ['El actor envía VERTABLERO[] o MOVERTARJETA["nro_tarjeta","etapa"].',
             'El sistema verifica el rol y que la tarjeta esté a su cargo.',
             'EmbudoService bloquea la tarjeta, cambia la etapa y registra el movimiento.',
             'Responde con el tablero en carriles (uno por etapa) o con la tarjeta movida.'],
            'La tarjeta queda en la nueva etapa con su movimiento en el historial.',
            'Mover a Perdido o Convertido por esta vía, tarjeta ajena o ya convertida: se indica el comando correcto o el motivo.'),
    'CU5': ('Gestión de Pérdida de Prospectos y Clasificación de Motivos', 'Marcar una tarjeta como perdida con un motivo obligatorio y administrar los motivos clasificados por categoría.',
            'Administrador, Coordinador o Vendedor', 'Existe el motivo y la tarjeta no está convertida.',
            ['El actor envía MARCARPERDIDO["nro_tarjeta","motivo","detalle"] o gestiona los motivos con LISTARMOTIVOS, INSERTARMOTIVO, MODIFICARMOTIVO o ELIMINARMOTIVO.',
             'El sistema verifica el rol y el alcance sobre la tarjeta.',
             'PerdidaService registra el motivo, cambia la etapa a Perdido y anota el movimiento con la categoría.',
             'Responde con la tarjeta y cómo reactivarla.'],
            'La pérdida queda clasificada y cuenta en el reporte de pérdidas.',
            'Tarjeta ya cerrada, motivo inexistente o motivo en uso que se intenta eliminar: se responde con el error.'),
    'CU6': ('Gestión de Validar Datos y Procesar Conversión a Matrícula', 'Validar la ficha del prospecto, generar la orden de pago, revisar el comprobante, confirmar la inscripción comercial y preparar su exportación al SIS académico.',
            'Administrador, Coordinador o Vendedor', 'La tarjeta tiene vendedor asignado y su módulo está abierto en el catálogo.',
            ['El vendedor envía VALIDARCONVERSION["nro_tarjeta"] y, con la ficha completa, GENERARORDEN["nro_tarjeta"] (con otros módulos del programa y un descuento opcionales); consulta LISTARORDENES, VERORDEN y LISTARDESCUENTOS.',
             'PagoService calcula subtotal, descuentos y total en una transacción y devuelve el número de orden, que identifica también a su pago, con una referencia demostrativa (no se cobra en un banco).',
             'El vendedor adjunta el comprobante real (PNG, JPEG, GIF, WebP o PDF de hasta 5 MB) con REGISTRARCOMPROBANTE["nro_orden","observación"]; el tipo se comprueba por su contenido.',
             'El coordinador del programa o el administrador revisa con LISTARPAGOS y VERCOMPROBANTE, y responde APROBARPAGO o RECHAZARPAGO con su observación; un rechazo deja el historial y permite otro comprobante.',
             'Al aprobar, en una transacción se confirma la inscripción comercial de cada módulo y la comisión del vendedor; CONVERTIRPROSPECTO comprueba el resultado sin duplicarlo y LISTARMATRICULAS lo lista.',
             'EXPORTARMATRICULAS prepara el CSV para el SIS del Grupo 06 SA (estado preparada); LISTAREXPORTACIONES, VEREXPORTACION y REGISTRARRESULTADOSIS registran la constancia que se obtenga.'],
            'La venta queda inscrita, con su comisión, y lista para el intercambio con el SIS académico.',
            'Ficha incompleta, módulo cerrado, comprobante con otro formato o de más de 5 MB, pago ya aprobado o rol sin permiso: se responde con el motivo y no se modifica nada.'),
    'CU7': ('Gestión de Bitácora, Interacciones y Recordatorios', 'Registrar interacciones y notas, programar recordatorios que llegan por correo, ver el historial de una tarjeta y la bitácora del sistema.',
            'Administrador, Coordinador o Vendedor', 'La tarjeta está a cargo del actor.',
            ['El actor envía REGISTRARINTERACCION, PROGRAMARRECORDATORIO, LISTARRECORDATORIOS, COMPLETARRECORDATORIO, VERHISTORIAL o, si es administrador, VERBITACORA.',
             'SeguimientoService registra la interacción o el recordatorio, o arma el historial cronológico de movimientos, interacciones y recordatorios.',
             'Cada ciclo, el servicio envía por correo los recordatorios vencidos a su responsable.',
             'Todo correo recibido queda en la bitácora del sistema desde que se descarga (Recibido) y luego con su resultado (Éxito, Error, Denegado, Ignorado o Repetido) y el estado de su respuesta.'],
            'El seguimiento del prospecto queda trazado y los recordatorios llegan a tiempo.',
            'Fecha pasada o con otro formato, tipo de interacción inválido o tarjeta ajena: se responde con el error.'),
    'CU8': ('Reportes y Estadísticas', 'Obtener cifras del embudo, de los vendedores, de las pérdidas y por oferta, según el alcance del rol.',
            'Administrador, Coordinador o Vendedor (solo su embudo)', 'El remitente tiene un rol con acceso al reporte.',
            ['El actor envía REPORTEEMBUDO[], REPORTEVENDEDORES[], REPORTEPERDIDAS[] o REPORTEOFERTA[].',
             'ReporteService agrega los datos de su alcance (todo, las ofertas del coordinador o la cartera del vendedor).',
             'Responde con tablas y barras de porcentaje y la tasa de conversión de las tarjetas cerradas.'],
            'El actor recibe las cifras actuales.',
            'Un vendedor pide un reporte de coordinación: acceso denegado.'),
}
# Subflujos (aparte del flujo principal, clase 03) y excepciones de infraestructura: lo que hace el código en cada caso.
SUB_COMUN = ['S1. Comando no permitido al rol: RolCommandProcessor responde Denegado y sugiere HELP[]; no se toca la base de datos.',
             'S2. Comando inexistente o parámetro faltante, sobrante o con formato inválido: se responde Error nombrando el parámetro.']
SUB_REINTENTO = ('S3. El mismo remitente repite el mismo comando de escritura dentro de 30 minutos, o cuando su respuesta aún no '
                 'salió: no se ejecuta otra vez; el registro queda Repetido y se reenvía la respuesta original.')
SUB_CU = {
    'CU1': ['S4. Sin fechas de vigencia: la credencial vale desde hoy y por un año.',
            'S5. Baja de un vendedor: sus tarjetas abiertas vuelven a la bolsa común.'],
    'CU2': ['S4. Visitante (correo no registrado): solo LISTAROFERTA y REGISTRARINTERES; su interés entra a la bolsa común.',
            'S5. IMPORTARPROSPECTOS: cada fila del CSV se valida e informa por separado.'],
    'CU3': ['S4. ASIGNARAUTOMATICO reparte por menor carga, deja juntos los intereses de un mismo prospecto y avisa por correo a cada vendedor.'],
    'CU4': ['S4. Las etapas financieras (Promesa de Pago, Convertido) no se mueven a mano: las fija el pago del CU6.'],
    'CU5': ['S4. Un motivo ya usado no se elimina; se puede cambiar su nombre o su categoría.'],
    'CU6': ['S4. Pago rechazado: queda en el historial y el vendedor adjunta otro comprobante.',
            'S5. CONVERTIRPROSPECTO sobre una tarjeta ya inscrita comprueba la inscripción sin duplicarla.'],
    'CU7': ['S4. Recordatorio vencido: el ciclo lo pone en la cola de salida y lo marca avisado en la misma transacción.'],
    'CU8': ['S3. El vendedor obtiene REPORTEEMBUDO solo de su cartera.']}
EXC_INFRA = ['Base de datos caída: antes de leer el buzón, los correos esperan en él; durante el proceso, la transacción se deshace y el '
             'correo, ya guardado, se procesa al volver la conexión.',
             'Servidor POP3 caído o sin respuesta: los comandos esperan en el buzón; los ya guardados se procesan igual.',
             'Gmail no acepta la respuesta: la operación ya quedó confirmada; la respuesta se reintenta a 1, 2, 4… hasta 60 minutos '
             'y, si la dirección no existe, queda Fallida en la bitácora.',
             'Cuenta inactiva o credencial fuera de vigencia: Denegado.']

d.h('Detallar casos de uso', 3)
for cu, (nombre, desc, iniciador, pre, flujo, post, exc) in CU.items():
    d.h(f'{cu}: {nombre}', 3)
    fig(f'uc-{cu.lower()}.png', f'Detalle {cu}: {nombre}', ancho=13)
    # Qué puede hacer cada actor en el CU, tomado de la matriz de acceso del código (no solo la lista de actores)
    permisos = '\n'.join(f'{a}: ' + ', '.join(c.split('[')[0] for c in comandos(cu, 'Público' if a == 'Visitante' else a))
                         for a in actores(cu))
    d.tabla(f'Detalle de {cu}: {nombre}', ['Elemento', 'Descripción'], [
        ('Descripción', desc), ('Actores y comandos', permisos), ('Actor iniciador', iniciador), ('Precondición', pre),
        ('Flujo principal', '\n'.join(f'{i}. {t}' for i, t in enumerate(flujo, 1))),
        ('Subflujos', '\n'.join(SUB_COMUN + ([] if cu == 'CU8' else [SUB_REINTENTO]) + SUB_CU[cu])),
        ('Postcondición', post), ('Excepción', '\n'.join(['E1. ' + exc] + [f'E{i}. {t}' for i, t in enumerate(EXC_INFRA, 2)])),
    ], anchos=[3.4, 12.6])
fig('uc-modelo.png', 'Modelo de casos de uso', horizontal=True, titulos=[('Estructurar el modelo de casos de uso', 3)])

d.h('Flujo de Trabajo: Análisis', 2)
d.h('Identificar paquetes', 3)
PAQ = [('Administración', 'Usuarios, roles y responsables de las ofertas', 'CU1'),
       ('Captación y Asignación', 'Oferta, registro de prospectos y reparto entre vendedores', 'CU2, CU3'),
       ('Gestión del Embudo', 'Tablero, pérdidas con motivo y seguimiento de cada tarjeta', 'CU4, CU5, CU7'),
       ('Conversión y Reportes', 'Paso a matrícula y estadísticas', 'CU6, CU8')]
d.tabla('Paquetes de análisis', ['Paquete', 'Responsabilidad', 'Casos de uso'], PAQ, anchos=[4.2, 8.8, 3.0])
fig('pkg-paquetes.png', 'Paquetes de análisis')
d.h('Relacionar paquetes con casos de uso', 3)
for i, (p, _, _) in enumerate(PAQ, 1):
    fig(f'pkg-trace-{i}.png', f'Relación del paquete {p} con sus casos de uso', ancho=13)
d.h('Encapsulamiento de casos de uso por paquetes', 3)
for i, (p, _, _) in enumerate(PAQ, 1):
    fig(f'pkg-encap-{i}.png', f'Encapsulamiento del paquete {p}', ancho=13)

d.h('Diagramas de colaboración', 3)
d.p('Cada diagrama sigue el análisis de robustez: el actor envía el comando a la clase de interfaz (boundary, el cliente de '
    'correo), las clases de control lo validan y lo ejecutan, y las entidades guardan los datos. Los mensajes van numerados por '
    'comando y con un color por operación; cuando el resultado es una entidad, DatabaseService la construye con su método de(). '
    'Los objetos se disponen en una fila, de izquierda a derecha en el orden en que pasa el comando; los actores y los servicios '
    'del caso de uso (tres en el CU6: ConversionService, PagoService y ExportacionService) van en columna.')
for cu, v in CU.items():
    fig(f'com-{cu.lower()}.png', f'Diagrama de colaboración {cu}: {v[0]}')
d.h('Dependencia de paquetes', 3)
fig('pkg-dependencias.png', 'Dependencia de paquetes', ancho=13)

d.h('Flujo de Trabajo: Diseño', 2)
d.h('Arquitectura física: despliegue', 3)
d.p('El usuario escribe desde su cuenta de correo. El mensaje llega por SMTP al servidor de la facultad (registro MX de '
    'tecnoweb.org.bo: mail.tecnoweb.org.bo, dirección resuelta por DNS al operar), donde Sendmail lo deja en el buzón grupo05sa. El lector se configura en un '
    'equipo del grupo (Windows, JDK 21 o posterior, bytecode Java 21): p1/ejecutar.ps1 arranca p1/target/crm-mail.jar con la configuración '
    '.secrets/crm-mail.properties, que no se publica porque lleva las contraseñas. El programa lee el buzón por POP3 (Dovecot, puerto '
    f'110), guarda cada correo y consulta y actualiza la base db_grupo05sa en PostgreSQL 18.6 (puerto 5432; {len(TABLAS)} tablas, '
    'entre ellas la bandeja del correo: log_comandos y correos_salientes) y responde por smtp.gmail.com (puerto 587 con '
    'STARTTLS), porque el SMTP de la facultad rechaza el reenvío a dominios externos con "550 5.7.1 Relaying denied". Si después '
    'el servidor del destinatario rechaza una respuesta, el aviso de rebote vuelve a la cuenta de Gmail del sistema: el lector lo '
    'lee cada minuto por imap.gmail.com (puerto 993, TLS) y lo registra. Cada ciclo, cada respuesta y '
    'cada falla quedan en p1/logs/crm-mail.0.log. PostgreSQL acepta la conexión según la IP de origen (pg_hba.conf): si el equipo '
    'cambia de red, el diagnóstico muestra la IP rechazada.')
fig('despliegue.png', 'Diagrama de despliegue', horizontal=True)
d.p('En el diagrama, cada servidor es un nodo «device» con sus entornos de ejecución («executionEnvironment»: sistema operativo, '
    'máquina virtual y servicios). Los artefactos llevan su ruta real y su estereotipo («executable» para el .jar, «file» para el '
    'script de arranque, la configuración, el log y el buzón) y contienen los componentes que despliegan: las cuatro capas en el .jar '
    'y las tablas y la bandeja del correo en la base de datos. Las dependencias entre artefactos muestran quién arranca, lee o escribe a quién, y cada '
    'ruta de comunicación lleva su protocolo y puerto. Google, que no administra el grupo, se modela como «External System».')
d.h('Arquitectura lógica: capas', 3)
d.tabla('Capas del sistema', ['Capa', 'Paquete Java', 'Clases'], [
    ('Presentación', 'bo.uagrm.crm.presentacion y bo.uagrm.crm', 'App (descarga, procesa y envía), BucleSondeo, ControlSondeo e InstanciaCorreo (frecuencia, cierre y exclusión), Bandeja (entrada y salida persistentes), Diagnostico (causa de fallas), Pop3Client (POP3), SmtpSender (SMTP), Rebotes (IMAP), Correo (MIME) y AutenticidadCorreo (firma HMAC)'),
    ('Aplicación', 'bo.uagrm.crm.aplicacion', 'CommandParser, Command, Comando, CommandProcessor, RolCommandProcessor y sus cuatro subclases por rol, ResponseFormatter, Respuesta'),
    ('Dominio', 'bo.uagrm.crm.dominio.<cu>', 'Servicios y entidades de usuarios, personas, captacion, asignacion, embudo, perdidas, conversion, pagos, seguimiento y reportes; personas amplía CU1 sin añadir otro caso de uso'),
    ('Persistencia', 'bo.uagrm.crm.persistencia', 'DatabaseService: conexión JDBC, consultas parametrizadas y transacciones (una transacción en curso se comparte con los servicios que se llaman dentro de ella)'),
], anchos=[2.8, 4.6, 8.6])
fig('capas.png', 'Diagrama de capas', horizontal=True)

d.h('Recepción, persistencia y recuperación del correo', 3)
d.p('El programa puede cerrarse, y los servidores de correo y de base de datos pueden caer, en cualquier punto del ciclo. Por eso '
    'cada correo pasa por tres etapas, cada una confirmada en la base de datos antes de la siguiente. (1) Recibir: se descarga y se '
    'guarda en log_comandos con resultado Recibido, una clave SHA-256 del contenido y las cabeceras esenciales y de firma, y su contenido recuperable; recién entonces recibe su DELE en el POP3. Como '
    'la clave persistida en message_id es única, una relectura idéntica no se guarda dos veces; el Message-ID de protocolo se obtiene por separado para responder y correlacionar. (2) Procesar: en una sola '
    'transacción se ejecuta el comando, se cierra el registro con su resultado (Éxito, Error, Denegado, Ignorado o Repetido) y '
    'se encola la respuesta ya armada en correos_salientes; si algo falla antes del COMMIT, no queda nada y el correo sigue '
    'Recibido. (3) Enviar: cada respuesta en cola se envía por SMTP; si falla, se reintenta a 1, 2, 4, 8, 16, 32 y luego cada 60 '
    'minutos (hasta 10 intentos), sin volver a ejecutar la operación. Las filas en proceso se bloquean con FOR UPDATE SKIP LOCKED, '
    'así que un segundo lector abierto por error no procesa ni envía lo mismo.')
d.p('Además, si el mismo remitente vuelve a enviar el mismo comando de escritura (porque no vio la respuesta) dentro de 30 minutos, '
    'o mientras su respuesta aún no salió, el comando no se ejecuta otra vez: el registro queda Repetido y se reenvía la respuesta '
    'original. Una duplicación posible es la de una respuesta cuya confirmación de envío se perdió: el usuario puede recibirla '
    'dos veces, con el mismo Message-ID, pero la operación se ejecutó una sola vez.')
d.tabla('Momentos de falla y comportamiento del sistema', ['Momento de la falla', 'Qué hace el sistema', 'Comprobado con'], [
    ('Base de datos no disponible al empezar el ciclo', 'No abre el buzón; reintenta con esperas 3, 6, 12, 24 y 30 s; errores de esquema/credenciales esperan 60 s', 'Prueba de caídas'),
    ('Cierre del programa después de guardar un correo y antes del QUIT', 'El servidor lo vuelve a entregar; su message_id ya existe y se borra sin repetirlo (YA GUARDADO)', 'BandejaCorreoTest, prueba de caídas'),
    ('QUIT sin respuesta (el servidor sí borró los mensajes)', 'Los correos ya estaban guardados y se procesan', 'BandejaCorreoTest, prueba de caídas'),
    ('Cierre del programa o caída de la base de datos durante el proceso', 'ROLLBACK de operación, registro y respuesta; el correo sigue Recibido y se procesa una vez al volver', 'BandejaCorreoTest, prueba de caídas'),
    ('Cierre después del COMMIT y antes de enviar', 'La respuesta ya está en correos_salientes y sale en el siguiente ciclo', 'Prueba de caídas'),
    ('Gmail caído, login rechazado o falla temporal', 'Respuesta Pendiente con su próximo intento; la operación no se repite', 'BandejaCorreoTest, prueba de caídas'),
    ('Gmail recibió la respuesta pero su confirmación se perdió', 'Se reenvía la misma respuesta, con el mismo Message-ID', 'Prueba de caídas'),
    ('Dirección del destinatario inexistente (550)', 'La respuesta queda Fallida, sin reintentos', 'BandejaCorreoTest'),
    ('El usuario reenvía el mismo comando', 'Registro Repetido y reenvío de la respuesta original', 'BandejaCorreoTest, prueba de caídas'),
    ('Dos lectores abiertos a la vez', 'El bloqueo local por buzón rechaza la segunda instancia; la BD bloquea filas. Entre máquinas se requiere un solo lector autorizado', 'BandejaCorreoTest'),
    ('Rebote posterior del servidor del destinatario', 'La respuesta pasa a Rebotado y el log muestra REBOTE con el motivo', 'Lectura de rebotes por IMAP'),
], anchos=[4.8, 7.6, 3.6], tam=9)

d.h('Contrato docente y autenticidad del correo', 3)
d.p('LISPER["*"] e INSPER se integran en CU1 como operaciones administrativas complementarias. INSPER exige exactamente siete parámetros: documento, nombres, apellidos, ocupación, teléfono fijo, teléfono celular y correo. PersonaService valida tipos y longitudes, usa PreparedStatement y confirma la inserción en una transacción. El correo duplicado o el documento repetido producen un error descriptivo; una consulta vacía devuelve una respuesta válida sin registros.')
d.p('La tabla persona tiene siete columnas obligatorias, documento como clave primaria y correo con unicidad sin distinguir mayúsculas. Su contrato no equivale a usuarios o prospectos: no incorpora roles ni intereses y no se inventan relaciones con las tablas CRM. Se agrega mediante la migración incremental 2026-11-19_persona.sql, repetible sólo ante una estructura compatible, con versión en configuraciones. Las demás tablas y sus relaciones se conservan.')
d.p('Documento admite hasta 15 caracteres; nombres y apellidos, 120 cada uno; ocupación, 80; correo, 120; teléfono fijo y celular, 16 cada uno. El CI exige de cinco a diez dígitos y permite un complemento alfanumérico de hasta tres caracteres. Cada teléfono exige de siete a quince dígitos con un signo + inicial opcional, sin espacios ni guiones internos; la columna de 16 admite ese signo adicional. Los textos deben ser no vacíos y no contener caracteres de control. Las restricciones CHECK y NOT NULL de PostgreSQL respaldan estas validaciones.')
d.p('Los comandos docentes exigen comillas dobles ASCII. Se admiten UTF-8, espacios adicionales y comillas literales duplicadas o escapadas con barra inversa. Los contratos CRM conservan la tolerancia histórica explícita a parámetros sin comillas y comillas tipográficas. Los parámetros adicionales, comas finales y corchetes malformados se rechazan.')
d.p('Una dirección From sólo identifica una cuenta candidata. App exige una firma HMAC-SHA256 individual antes de autorizar a un usuario registrado o reutilizar una respuesta anterior; después comprueba rol, actividad, vigencia y alcance. La firma protege el asunto, identificadores, metadatos MIME y cuerpo completo, incluidos adjuntos. Las claves privadas no se versionan; sin clave o firma válida se deniega el acceso registrado. Los visitantes conservan únicamente comandos públicos. La ventana de firma es de 24 horas y se toleran cinco minutos de desfase futuro; una solicitud antigua requiere volver a firmarse.')
d.p('scripts/firmar_correo.py genera una clave individual o un EML firmado sin enviarlo. Cada cliente recibe sólo su propia clave y debe conservar las cabeceras y MIME firmados al enviar. El código implementa POP3 plain, STLS obligatorio o TLS implícito con comprobación de certificado y hostname, sin degradación automática; el soporte institucional se verificará antes de cambiar su configuración.')
d.h('Frecuencia de sondeo y recuperación', 3)
d.p('BucleSondeo ejecuta un ciclo por vez y aplica fixed delay desde su finalización. mail.poll.interval.ms vale 3000 por omisión y rechaza valores inferiores para Tecnoweb; intervalo.segundos continúa como propiedad histórica si no se configura la nueva. ControlSondeo produce esperas progresivas 3, 6, 12, 24 y 30 segundos ante fallos temporales; esquema y credenciales se distinguen y esperan 60 segundos. No se añaden pausas entre sentencias de una transacción normal. La conexión fallida se protege también contra reconexiones inmediatas.')
d.p('InstanciaCorreo mantiene un bloqueo del sistema operativo por buzón en el directorio compartido configurado. El cierre interrumpe la espera y permite terminar el trabajo en curso. El bloqueo es local: un responsable debe mantener un solo lector entre PC y VPS. La unidad systemd conserva ProtectSystem=strict y utiliza /var/log/crm-mail y /run/crm-mail para escritura autorizada. Los diagnósticos no se ejecutan en bucles paralelos; cualquier restricción institucional adicional por servicio requiere confirmación.')
d.p('La clave de deduplicación es un resumen del contenido y de la firma, separado del Message-ID de protocolo que se conserva para In-Reply-To. Los rebotes se correlacionan con el Message-ID de la respuesta y el destinatario; IMAP marca un aviso leído únicamente después de que su consumidor confirma la persistencia. Los logs usan referencias de registro y estado, sin imprimir asuntos o direcciones completas.')

d.h('Diagramas de secuencia', 3)
d.p('Lo común a todo comando se dibuja en cuatro diagramas. Recibir y guardar muestra el ciclo de App con un fragmento loop cada '
    'intervalo mínimo de tres segundos desde el fin del ciclo: comprueba el modelo de la base de datos, consulta el buzón y, en un loop por mensaje, lo descarga '
    '(alt: solo las cabeceras con top() si pasa de 8 MB, o completo con retr()), lo identifica (idMensaje), lo guarda con '
    'Bandeja.guardar() y recién después le da DELE; al final, QUIT. Procesar un correo guardado muestra la transacción de cada '
    'registro: tomar(), leer(), interpretar(), el opt del comando de escritura con repetido(), el alt del reintento (reenviar() y '
    'cerrar() como Repetido) y, si no se repite, atender(), cerrar(), preparar() y encolar(), con el COMMIT al final. Enviar la '
    'cola de salida muestra cada respuesta enviada con enviarPreparado() y el alt entre enviado() y fallo(), que devuelve los '
    'minutos de espera. Leer rebotes muestra cómo una respuesta rechazada después queda Rebotado.')
d.p('Cada caso de uso tiene un diagrama. El correo guardado entra por App, que llama a atender() de procesador; este llama a '
    'procesar() del procesador del rol, que consulta comandos(). Un fragmento alt separa el comando no permitido al rol '
    '(denegado()) del resto; dentro de [else], ejecutar() lleva a un segundo alt con un operando por comando del caso de uso, '
    'cada uno con el método real del servicio y la operación de DatabaseService con la que llega a la base de datos '
    '(enTransaccion(), lista(), filas() o uno()). Al salir del alt, procesador devuelve el resultado (Atendido) a App y el actor '
    'recibe la respuesta HTML por la cola de salida. Se mantiene un único diagrama de secuencia por caso de uso, '
    'incluidos todos los comandos del CU6.')
d.p('Los correos (el comando que llega al buzón y la respuesta HTML) son mensajes asíncronos; las demás llamadas son síncronas, '
    'llevan los parámetros del código y su retorno con el tipo que devuelven. Las líneas de vida usan los estereotipos de robustez '
    '(boundary para los clientes POP3 y SMTP, control para la lógica y entity para DatabaseService) y se nombran con la variable o '
    'el campo que las referencia en el código (pop, smtp, bandeja, procesador, db). Cuando la lista de parámetros no cabe '
    'en la etiqueta se muestran los primeros y puntos suspensivos; la lista completa de cada comando está en el Anexo A.')
fig('sd-recibir.png', 'Diagrama de secuencia común: Recibir y guardar')
fig('sd-procesar.png', 'Diagrama de secuencia común: Procesar un correo guardado')
fig('sd-enviar.png', 'Diagrama de secuencia común: Enviar la cola de salida')
fig('sd-rebotes.png', 'Diagrama de secuencia común: Leer rebotes')
for cu, v in CU.items():
    fig(f'sd-{cu.lower()}.png', f'Diagrama de secuencia {cu}: {v[0]}')

d.h('Diseño procedimental', 3)
d.p('El diseño procedimental muestra las clases que el programador implementa, con sus atributos y operaciones tal como están en '
    'el código (leídos de p1/src por el generador de los diagramas). El canal de correo tiene una vista global. Cada caso de uso '
    'tiene un único procedimental completo con el intérprete, el control central, el procesador de sus roles, los servicios, '
    'las entidades y el acceso a datos. Las clases compartidas se repiten completas en los CU donde intervienen, como en el '
    'P1 aprobado; no se dividen en figuras de continuación ni se ocultan sus miembros. Las dependencias salen de los campos y de '
    'los tipos de las firmas reales; RolCommandProcessor es abstracta y los cuatro procesadores de rol la especializan.')
fig('cls-correo-global.png', 'Diseño procedimental global del canal de correo')
for cu, v in CU.items():
    fig(f'cls-{cu.lower()}.png', f'Diseño procedimental {cu}: {v[0]}')


def tablas_sql():
    fk = {(h, c): p for h, c, p, _ in FKS}
    out = []
    for nombre, cols in TABLAS.items():
        filas = []
        for c, tipo, nn in cols:
            clave = ' + '.join(x for x in ('PK' if c in PKS.get(nombre, []) else '',
                                           f'FK → {fk[nombre, c]}' if (nombre, c) in fk else '',
                                           'UNIQUE' if (nombre, c) in UNICAS else '') if x)
            filas.append((c, tipo, clave, 'No' if nn or c in PKS.get(nombre, []) else 'Sí'))
        out.append((nombre, filas))
    return out


d.h('Diseño de datos: modelo conceptual', 3)
d.p('El modelo conceptual es el modelo de dominio final: el inicial (Captura de Requisitos) con las entidades que aparecieron al '
    'detallar los casos de uso. Cada entidad lleva sus atributos esenciales de negocio, sin tipos de dato, sin claves primarias ni '
    'foráneas y sin operaciones; los nombres son los de la base de datos para poder cotejarlos uno a uno, y todos los campos de cada '
    'tabla están en el diseño físico. Las relaciones llevan su multiplicidad y, cuando no es obvio, el papel (vendedor, coordinador, '
    'autor, responsable). La única relación N a N, entre ordenes_pago y descuentos, une a las dos entidades fuertes, y de la '
    'relación sale una línea punteada a orden_pago_descuentos, la clase de asociación con lo que la relación guarda: el porcentaje '
    'y el monto aplicados. El modelo se presenta completo en una única vista global, sin dividirlo por áreas.')
fig('conceptual-global.png', 'Modelo conceptual global (modelo de dominio final)')

d.h('Diseño de datos: normalización', 3)
d.p('Las entidades fuertes (usuarios, prospectos, programas, descuentos, motivos_perdida, roles) se identifican por sus claves naturales; '
    'las débiles dependen de otra: pagos de su orden y comisiones e inscripciones_comerciales de su solicitud de compra (relaciones '
    '1 a 1 que toman la clave de su dueño), y movimientos, interacciones y recordatorios de su tarjeta (1 a N). La relación N a N es '
    'la de órdenes y descuentos. Se analizan las formas normales y se distinguen las dependencias transitivas, los valores '
    'históricos y los importes derivados que se conservan deliberadamente. Se elimina la dependencia transitiva del nombre de programa en el catálogo:')
d.tabla('Formas normales en el modelo', ['Forma normal', 'Regla', 'Cómo se cumple en el modelo'], [
    ('1FN', 'Atributos atómicos, sin grupos repetidos', 'Los módulos de una orden no se guardan como lista: GENERARORDEN["7","m1;m2"] crea una fila por módulo en solicitudes_compra; los descuentos aplicados, una fila por descuento en orden_pago_descuentos.'),
    ('2FN', 'Sin dependencias parciales respecto de las claves candidatas', 'La única PK compuesta es orden_pago_descuentos (nro_orden, descuento_codigo): porcentaje y monto corresponden a ese descuento en esa orden. intereses tiene además la restricción UNIQUE (documento, modulo_codigo), que identifica el interés de una persona en un módulo.'),
    ('3FN', 'Sin dependencias transitivas no permitidas respecto de las claves candidatas', 'Se separa programas(codigo, nombre): codigo determina nombre. catalogo_cache conserva los módulos y su FK programa_codigo; el nombre actual se obtiene mediante JOIN, sin repetirlo por módulo. Los valores históricos e importes derivados se explican por separado.'),
], anchos=[1.8, 4.6, 9.6], tam=9)
d.tabla('Dependencias y valores conservados deliberadamente', ['Columnas', 'Por qué se guardan así'], [
    ('programas.codigo → nombre; catalogo_cache.codigo → programa_codigo', 'El catálogo local simulado para el futuro intercambio con el SIS del Grupo 06 SA separa programas y módulos. Dos FK vinculan los módulos y sus responsables con programas. La migración conserva códigos, oferta e históricos; su aplicación en producción sigue pendiente.'),
    ('intereses.programa_nombre, modulo_nombre y precio; solicitudes_compra.programa_nombre, modulo_nombre y precio_acordado; orden_pago_descuentos.porcentaje; log_comandos.rol_registrado', 'Conservan el nombre, precio, descuento o rol de la operación. Se cotejan con su significado histórico: un precio acordado o un rol registrado en una fecha no equivalen al precio o rol actuales. Su almacenamiento no demuestra por sí solo una infracción de 3FN.'),
    ('ordenes_pago.total y pagos.monto', 'El total (subtotal menos descuento_total) y el monto del pago se guardan para que la orden y el pago emitidos no cambien si se recalculan.'),
], anchos=[6.0, 10.0], tam=9)

d.h('Diseño de datos: modelo lógico', 3)
d.p(f'El modelo lógico propuesto tiene {len(TABLAS)} tablas para el esquema public de db_grupo05sa, el mismo que usará el Proyecto 2 web, con '
    f'{len(FKS)} claves foráneas que conservan las relaciones CRM. Persona permanece independiente porque su contrato docente no equivale a un usuario ni a un prospecto; no se inventa una relación para unirla. Las migraciones de bandeja, catálogo y persona siguen pendientes en el servidor. En el diagrama, la clave '
    'primaria va primero y las claves foráneas no se repiten como atributo: cada una es una asociación con su multiplicidad, '
    'rotulada con el papel cuando no es obvio. El modelo se presenta completo en una sola figura global. Una FK que es también '
    'PK simple, como pagos.nro_orden, limita a uno el registro dependiente: no se representa como una relación 1 a N.')
d.p('Cada tabla tiene una sola clave, la de su diseño y con su nombre; ninguna lleva un campo id. Las entidades que tienen una '
    'clave natural se identifican por ella: usuarios por el correo de la cuenta candidata cuya firma se verifica, '
    'prospectos y persona por el documento (CI), descuentos por su código, motivos_perdida por su nombre, roles por su nombre, catalogo_cache '
    'por el código del módulo, programas por su código, configuraciones por su clave y api_tokens por el hash del token. Las que dependen 1 a 1 de otra '
    'toman la clave de su dueño: pagos la de su orden (nro_orden), y comisiones e inscripciones_comerciales la de su solicitud de '
    'compra (nro_solicitud). Las que no tienen clave natural usan un correlativo con el nombre del diseño: nro_tarjeta, nro_orden, '
    'nro_solicitud, nro_interaccion, nro_movimiento, nro_recordatorio, nro_notificacion, nro_registro, nro_exportacion, '
    'nro_comprobante y nro_correo. Cada clave foránea lleva el nombre de la clave a la que apunta o el del papel que cumple '
    '(vendedor_correo, coordinador_correo). La intermedia orden_pago_descuentos tiene como clave las dos claves foráneas (nro_orden y '
    'descuento_codigo) y guarda el porcentaje y el monto aplicados. La credencial de cada usuario tiene vigencia: fecha_inicio y '
    'fecha_fin, con la restricción fecha_fin >= fecha_inicio. log_comandos.message_id conserva una clave SHA-256 de contenido y cabeceras esenciales y de firma, '
    'única para no registrar dos veces una relectura idéntica; no se confía solamente en el Message-ID externo. correos_salientes debe tener al menos uno de sus dos orígenes: el '
    'registro que responde o el recordatorio que avisa.')
fig('datos-global.png', 'Modelo lógico global de datos')

d.h('Diseño de datos: modelo físico', 3)
d.p('El modelo físico es el de PostgreSQL 18: cada tabla con sus columnas, tipos, claves y nulos (tablas siguientes) y, en el '
    'script del Anexo B, sus secuencias, valores por omisión, restricciones CHECK y UNIQUE, índices y disparadores. La bandeja del '
    'correo (columnas message_id, contenido, bytes y procesado_en de log_comandos, y la tabla correos_salientes) se agrega a la '
    'base vigente con la migración sql/migraciones/2026-11-19_bandeja_correo.sql, que se aplica con el lector detenido y después '
    'de un respaldo. También se requieren el catálogo normalizado y persona; si falta la estructura requerida, el lector identifica la migración pendiente y no retira mensajes del buzón.')
for nombre, cols in tablas_sql():
    d.tabla(f'Mapeo de la tabla {nombre}', ['Columna', 'Tipo', 'Clave', 'Nulo'], cols, anchos=[4.4, 4.0, 5.2, 2.4], tam=9)

d.h('Diseño de pantallas: interacción por correo', 3)
d.p('En un sistema vía correo las pantallas son los comandos y sus respuestas: el usuario escribe el comando en el asunto de un '
    'correo dirigido a grupo05sa@tecnoweb.org.bo y recibe otro correo con la respuesta en HTML. Toda respuesta tiene la misma '
    'estructura: una barra de color según el resultado (verde #1E6B3A para éxito y listados, rojo #A12A1F para error, ámbar '
    '#8A5300 para denegado), el título (que es también el asunto de la respuesta), el mensaje, una tabla cuando el comando '
    'devuelve filas y un pie con el comando recibido. HELP[] cumple el papel del menú: muestra solo los comandos del rol del '
    'remitente, tomados de la matriz de acceso.')
d.p('Las pantallas siguen una venta completa sobre datos de demostración: una visitante registra su interés y un vendedor '
    'registra prospectos, que entran a la bolsa común; el coordinador del programa los asigna; cada vendedor mueve sus tarjetas '
    'por el tablero, registra llamadas y reuniones y genera la orden de pago; el coordinador aprueba el comprobante y la '
    'tarjeta se convierte en inscripción comercial; otra tarjeta se cierra como perdida con su motivo. Hay al menos una '
    'pantalla por caso de uso, además del menú y de las respuestas de error y de comando denegado.')
from pantallas import PANTALLAS
d.tabla('Pantallas de interacción por correo', ['CU', 'Asunto enviado', 'Rol', 'Pantalla de respuesta'],
        [(cu, asunto if len(asunto) <= 40 else asunto[:asunto.index('[') + 1] + '…]', rol.split(',')[0].capitalize(),
          (lambda t: t[0].upper() + t[1:])(titulo.split(': ', 1)[1]))
         for _, cu, titulo, _, rol, asunto, _ in PANTALLAS], anchos=[1.8, 6.2, 2.6, 5.4], tam=9,
        nota_txt='Los asuntos largos se abrevian; el asunto completo aparece en cada figura.')
NOTA_PANT = ('Respuesta producida el 18/11/2026 por el procesador y el formateador que atienden el buzón (crm-mail.jar --probar) '
             'sobre los datos de demostración de db_grupo05sa; arriba, el correo que la origina.')
for nombre, cu, titulo, *_ in PANTALLAS:
    titulo = f'Pantalla {titulo}' if cu in ('Menú', 'Error', 'Denegado') else f'Pantalla {cu}, {titulo}'
    ruta = CAP / nombre
    if ruta.exists():
        import pymupdf
        px = pymupdf.Pixmap(str(ruta))
        d.figura(ruta, titulo, NOTA_PANT, min(14.0, 19.0 * px.width / px.height))
    else:
        d.rotulo('Figura', titulo); rojo(f'pantalla capturas/{nombre} (python p1/doc/pantallas.py)')

fig('componentes.png', 'Diagrama de componentes', horizontal=True,
    titulos=[('Flujo de Trabajo: Implementación', 2), ('Implementación de la arquitectura', 3)])
d.p('El subsistema agrupa las cuatro capas como componentes contenedores, y el artefacto crm-mail.jar lo manifiesta («manifest»). '
    'Las dependencias se muestran entre capas y componentes, sin repetir los métodos de los procedimentales. App guarda y toma los correos y encola las respuestas con '
    'Bandeja, que los persiste con DatabaseService; Diagnostico revisa el lector, el emisor y el lector de rebotes, y UsuarioService usa '
    'jBCrypt para guardar la clave web del usuario como hash. Hacia afuera, los ensamblajes muestran una sola pareja de interfaz requerida '
    '(semicírculo) y provista (círculo), con la API o protocolo de la conexión: el lector '
    'de correo con el POP3 de Dovecot (puerto 110), el emisor SMTP con la API Jakarta Mail de Angus Mail y esta con el SMTP de '
    'Gmail (587) y con su IMAP (993) para leer los rebotes, y DatabaseService con JDBC de PostgreSQL JDBC y este con el protocolo '
    'de PostgreSQL 18.6 (puerto 5432).')
d.p('El corazón del sistema es la verificación en cuatro pasos de cada correo, en este orden: formato del comando '
    '(CommandParser), identidad del remitente (tabla usuario), permiso del rol (la subclase de RolCommandProcessor) y, recién '
    'entonces, el servicio del caso de uso. Un comando no permitido se responde como "Denegado" sin abrir una conexión a la '
    'base de datos.')
d.bloque('''public Respuesta procesar(Command cmd, Usuario u) {
    if (!comandos().contains(cmd.getTipo())) return fmt.denegado(cmd.getNombre(), mensajeDenegado(cmd));
    try {
        return ejecutar(cmd, u);
    } catch (CrmException e) {
        return fmt.error(cmd.getNombre(), e.getMessage());
    } catch (SQLException e) {
        return fmt.error(cmd.getNombre(), Errores.traducir(e));
    } catch (RuntimeException e) {
        return fmt.error(cmd.getNombre(), "Ocurrió un error interno al procesar el comando. ...");
    }
}''', 'RolCommandProcessor.procesar(): el permiso del rol se comprueba antes de llegar al dominio')
d.h('Herramientas de desarrollo', 3)
d.tabla('Herramientas y versiones', ['Herramienta', 'Versión', 'Uso'], [
    ('Java (nivel de lenguaje)', '21', 'Lenguaje del sistema'),
    ('Oracle JDK (QA local final)', '23.0.1', 'Compilación con release 21, pruebas y ejecución del lector local'),
    ('Windows', 'Entorno local', 'Sistema operativo de las pruebas; ejecución institucional pendiente'),
    ('Apache Maven', '3.9.9', 'Construcción y dependencias; jar único con maven-shade-plugin 3.6.0'),
    ('Angus Mail / Jakarta Mail API', '2.0.5 / 2.1.5', 'Análisis MIME, envío SMTP y lectura IMAP de rebotes'),
    ('PostgreSQL JDBC', '42.7.13', 'Conexión a la base de datos'),
    ('jBCrypt', '0.4', 'Hash de la clave web al registrar un usuario'),
    ('PostgreSQL institucional', '18.6 (antecedente conservado)', 'Versión informada anteriormente; disponibilidad y versión actuales pendientes de comprobar'),
    ('JUnit Jupiter', '5.10.5', 'Pruebas unitarias y de integración'),
    ('PostgreSQL (QA local final)', '18.6', 'Base desechable para las pruebas de integración y de caídas'),
    ('Python', '3.8.10', 'Herramientas de QA y prueba de caídas del lector'),
    ('Enterprise Architect', '15.0.0.1514', 'Herramienta CASE (modelado UML)'),
    ('Git / GitHub', '2.49.0 / repositorio remoto', 'Control de versiones; sin publicación automática de esta revisión'),
], anchos=[5.2, 3.2, 7.6])

# ---------------------------------------------------------------- VIII
d.h('VIII. Resultados', 1)
auditoria = json.loads((DOC / 'pruebas' / 'modelos-globales.json').read_text(encoding='utf-8'))
d.tabla('Cotejo de los modelos globales con PostgreSQL desechable', ['Comprobación', 'Resultado'], [
    ('Tablas del esquema y de cada vista global', str(auditoria['tablas'])),
    ('Columnas del esquema físico', str(auditoria['columnas'])),
    ('Atributos del lógico (las FK restantes se representan como asociaciones)', str(len(auditoria['atributos_logicos']))),
    ('Claves foráneas, con multiplicidad según PK, UNIQUE y nulos', str(auditoria['claves_foraneas'])),
    ('Comprobaciones aprobadas / ejecutadas', f"{sum(c['aprobada'] for c in auditoria['comprobaciones'])} / {len(auditoria['comprobaciones'])}"),
], anchos=[12, 4], nota_txt='scripts/auditar_modelos.py instala sql/esquema.sql en un PostgreSQL 18 desechable, '
    'compara sus metadatos con el modelo EA real y elimina el clúster al terminar. No usa ni modifica la base de producción. '
    'Evidencia: doc/pruebas/modelos-globales.json, 19/11/2026.')
junit = []
for x in sorted((P1 / 'target' / 'surefire-reports').glob('TEST-*.xml')):
    r = ET.parse(x).getroot()
    junit.append((r.get('name').split('.')[-1], r.get('tests'), str(int(r.get('tests')) - int(r.get('failures')) - int(r.get('errors')) - int(r.get('skipped', '0'))),
                  r.get('skipped')))
resumen_guardado = False
if not junit and (DOC / 'pruebas' / 'junit-resumen.json').is_file():
    resumen_guardado = True
    resumen = json.loads((DOC / 'pruebas' / 'junit-resumen.json').read_text(encoding='utf-8'))
    junit = [(x['clase'], str(x['casos']), str(x['aprobados']), str(x['omitidos'])) for x in resumen['suites']]
if junit:
    d.tabla('Pruebas automáticas (JUnit) de la versión entregada', ['Clase de prueba', 'Casos', 'Aprobados', 'Omitidos'], junit,
            anchos=[7, 3, 3, 3], nota_txt='Resultados de los XML de la ejecución local actual con scripts/probar_local.py. Todas las integraciones, incluidas las clases de nombre histórico ServidorCompartidoTest y CorreoServidorFinancieroTest, se dirigen al PostgreSQL exclusivo de 127.0.0.1. No acreditan ejecución institucional. El resumen reproducible está en doc/pruebas/local-final.json.')
    evidencia_actual = DOC / 'pruebas' / 'local-final.json'
    if evidencia_actual.is_file():
        evidencia = json.loads(evidencia_actual.read_text(encoding='utf-8'))
        completa = evidencia['junit']
        adicional = evidencia.get('verificacion_adicional', {}).get('junit', {})
        d.p(f"La corrida completa actual ejecutó {completa['tests']} pruebas, con {completa['failures']} fallas, "
            f"{completa['errors']} errores y {completa['skipped']} omisiones. Después se ejecutó la clase PersonaIntegracionTest "
            f"con {adicional.get('tests', 0)} casos para añadir la comprobación de LISPER vacío con rollback y conservación de datos. "
            'Los XML muestran el último resultado de cada clase; no se presenta el agregado como otra corrida completa. '
            'El JAR final se probó con interrupciones reales de proceso y PostgreSQL local, incluyendo mensajes llegados durante la caída y recuperación observada en el mismo proceso.')
    if resumen_guardado:
        d.p('Esta regeneración utiliza el resumen conservado en doc/pruebas/junit-resumen.json, no una nueva ejecución '
            'de pruebas en esta máquina. Para revalidar los cambios se deben ejecutar las suites con su entorno de QA.')
else:
    rojo('ejecutar mvn test para generar target/surefire-reports')
d.tabla('Cotejo con el enunciado de presentación', ['Requisito', 'Evidencia y estado'], [
    ('Subject, parser, tipos y comillas', 'LISPER/INSPER con comillas ASCII obligatorias y siete parámetros exactos para INSPER; gramática CRM histórica preservada y probada. Escapes por duplicación o barra inversa.'),
    ('PostgreSQL y sentencias preparadas', 'Servicios JDBC y DatabaseService; suites locales contra PostgreSQL desechable.'),
    ('Transacciones, respuestas y logs', 'Bandeja persistente, correo de resultado, logs rotativos. Ante BD caída antes de leer, se conserva el correo y se difiere; no hay respuesta inmediata de error en ese caso.'),
    ('Despliegue operativo', 'Lector periódico en la PC del grupo. Migraciones de bandeja, catálogo y persona y validación externa siguen pendientes.'),
    ('README y GitHub público', 'README incluye arquitectura, instalación, BD y flujo del parser. La última constatación conservada lo identifica como privado; esta revisión no se publica automáticamente.'),
    ('PUDS, ZIP e impresión', 'Documento y UML en EA; paquete 2026-2_INF513-P1_grupo05sa.zip. Subida al aula e impresión pendientes del grupo; verificar fecha establecida en el aula.'),
], anchos=[4.6, 11.4], tam=9, nota_txt='Cotejo completo y límites en p1/AUDITORIA_ENUNCIADO.md, según el texto facilitado por el usuario el 19/11/2026.')
integ = json.loads((DOC / 'pruebas' / 'integracion-servidor.json').read_text(encoding='utf-8'))
d.tabla('Pruebas de integración contra el PostgreSQL de la facultad (versión del 17/11/2026)', ['Clase de prueba', 'Casos', 'Aprobados'],
        [(x['suite'], str(x['casos']), str(x['aprobados'])) for x in integ['suites']], anchos=[8, 3, 3],
        nota_txt=f"Ejecutadas el 17/11/2026, con la versión anterior a la bandeja, con {integ['comando']} sobre un esquema temporal "
                 '(crm_qa_correo) creado con sql/esquema.sql y sql/referencias.sql y eliminado al terminar. La versión entregada se '
                 'probará de nuevo contra ese servidor después de aplicar las migraciones de bandeja, catálogo y persona.')
d.p('La prueba de robustez (RobustezCorreoTest) envió cada comando, con cada rol y con un visitante, con entradas malas: '
    'parámetros vacíos o solo con espacios, letras donde va un número, negativos, números enormes, inyección SQL, textos de 5000 '
    'caracteres, emojis, fechas imposibles, parámetros de más, corchetes y comillas sin cerrar, y siete archivos CSV dañados. '
    'La ejecución actual exige que no aparezcan errores internos ni SQL sin traducir, que lo malformado no se acepte y que las tablas conserven su integridad. Se ejecuta contra PostgreSQL local desechable; los resultados de esta revisión constan en local-final.json.')
d.h('Prueba de caídas', 2)
catalogo_qa = json.loads((DOC / 'pruebas' / 'catalogo-normalizado.json').read_text(encoding='utf-8'))
d.p('La normalización del catálogo se comprobó en PostgreSQL local desechable: migrar conserva cada fila y valor del modelo '
    'anterior, la reversión los restituye y los nombres contradictorios se rechazan con rollback. La instalación nueva y la '
    'migración producen los mismos datos de referencia. CatalogoNormalizadoTest verifica además las FK y que renombrar un '
    'programa actualiza la oferta sin alterar el nombre histórico de un interés. Evidencia: doc/pruebas/catalogo-normalizado.json. '
    'P2 debe adaptar sus consultas a la columna retirada antes de usar el esquema normalizado; la web no se modificó en esta tarea.')
caidas_path = DOC / 'pruebas' / 'defensa-local.json'
caidas = json.loads((caidas_path if caidas_path.is_file() else DOC / 'pruebas' / 'caidas.json').read_text(encoding='utf-8'))
d.p('La prueba de caídas (p1/scripts/prueba_caidas.py) ejecuta el lector real, crm-mail.jar, contra un servidor POP3 y uno SMTP '
    'de prueba y una base PostgreSQL desechable, todos en el mismo equipo. Deposita correos de a poco y corta el programa sin '
    'cierre ordenado en momentos al azar, mientras el POP3 a veces pierde la respuesta del QUIT o no aplica los DELE, el SMTP a veces '
    'está caído o recibe la respuesta y corta antes de confirmarla, y PostgreSQL se apaga de golpe. Al final deja correr el lector '
    'hasta vaciar todo y comprueba que cada comando se haya ejecutado una sola vez y que cada respuesta haya llegado.')
q, sm = caidas['pop3_quit'], caidas['smtp_conexiones']
d.tabla('Resultado de la prueba de caídas', ['Medida', 'Valor'], [
    ('Cierres forzados del lector (sin cierre ordenado)', str(caidas['cortes_forzados'])),
    ('Caídas de PostgreSQL (pg_ctl stop -m immediate)', str(caidas['caidas_de_la_bd'])),
    ('QUIT con respuesta perdida / QUIT sin aplicar los DELE', f"{q['perder_respuesta']} / {q['sin_aplicar']}"),
    ('Conexiones SMTP caídas / entregas inciertas', f"{sm['caido']} / {sm['incierto']}"),
    ('Correos depositados (30 comandos distintos, 3 reentregas y 2 reenvíos del usuario)', str(caidas['correos_depositados'])),
    ('Veces que se ejecutó cada comando', ', '.join(map(str, caidas['ejecuciones_por_comando']))),
    ('Comandos perdidos / duplicados', f"{len(caidas['comandos_perdidos'])} / {len(caidas['comandos_duplicados'])}"),
    ('Respuestas recibidas / distintas (Message-ID)', f"{caidas['respuestas_recibidas_por_smtp']} / {caidas['message_id_distintos']}"),
    ('Comandos sin respuesta recibida', str(len(caidas['comandos_sin_respuesta_recibida']))),
    ('Quedan en el buzón / sin procesar / respuestas pendientes', f"{caidas['quedan_en_buzon']} / {caidas['quedan_recibidos']} / {caidas['quedan_pendientes']}"),
], anchos=[11.0, 5.0], tam=9, nota_txt=f"Ejecución del {caidas['fecha']} con semilla {caidas['semilla']}: "
   "python -I scripts/prueba_caidas.py --db <base desechable> --pgdata <clúster desechable>. Las respuestas repetidas son "
   "reenvíos de entregas inciertas y del reintento del usuario, con el mismo Message-ID que la original.")
d.p('El extracto siguiente, del log de esa ejecución, muestra cómo se distingue cada etapa: RECIBIDO (guardado en la base de datos '
    'antes de borrarlo del buzón), YA GUARDADO (el servidor lo volvió a entregar), CONFIRMADO en BD con la respuesta en cola, '
    'REPETIDO (reintento del usuario), PENDIENTE con el próximo intento, ENVIADO, y la falla de cada servidor con su causa y su '
    'recuperación.')
d.bloque('\n'.join(textwrap.shorten(linea, width=145, placeholder=' […]')
                   for linea in (DOC / 'pruebas' / 'caidas-log.txt').read_text(encoding='utf-8').strip().splitlines()),
         'Antecedente histórico del 19/11 antes de la revisión final: líneas abreviadas, no consecutivas; no sustituye la prueba actual')
d.h('Funcionamiento con correos reales', 2)
d.p('El 18 de noviembre de 2026, con la versión anterior a la bandeja persistente, el lector se ejecutó en la PC del grupo con el servicio del VPS ya detenido. Al arrancar revisó '
    'las cuatro conexiones (POP3, SMTP, IMAP de rebotes y base de datos); luego recibió dos comandos enviados al puerto 25 de mail.tecnoweb.org.bo con el remitente de un vendedor '
    'registrado: HELP[] (Éxito) y VERBITACORA["5"], reservado al administrador (Denegado). Ambos quedaron en la tabla log_comandos '
    'con el rol del remitente. Del mismo modo se comprobó un remitente institucional (@ficct.uagrm.edu.bo) con HELP[] y con '
    'Re: LISTAROFERTA[]: ambas respuestas llegaron a esa cuenta. Una respuesta dirigida a una dirección inexistente de ese '
    'dominio quedó en el log como REBOTE, con el código 550 5.1.1 que devolvió el servidor del destinatario.')
d.figura(CAP / 'servicio-local.png', 'Arranque del lector, revisión de conexiones y dos comandos atendidos',
         'Salida en consola del lector en la PC del grupo (hora de Bolivia), 18 de noviembre de 2026, versión anterior a la bandeja '
         '(su log aún no distinguía la respuesta en cola de la enviada); se ocultó el último octeto de la IP pública.', 16)
for nombre, titulo in [('gmail-help.png', 'Respuesta a HELP[]: comandos permitidos al rol del remitente'),
                       ('gmail-denegado.png', 'Respuesta a VERBITACORA["5"] enviado por un vendedor: comando denegado por rol')]:
    if (CAP / nombre).exists():
        d.figura(CAP / nombre, titulo, 'Correo de respuesta recibido en la cuenta Gmail del sistema (alias +vend1 del vendedor de '
                 'prueba), leído por IMAP y mostrado en Chrome con su HTML original, 18 de noviembre de 2026.', 12)
    else:
        d.rotulo('Figura', titulo); rojo(f'captura de Gmail capturas/{nombre}')
d.h('Diagnóstico de fallas', 2)
d.p('El docente puede deshabilitar un servicio o cambiar una configuración durante la defensa; el sistema debe decir qué pasó. '
    'Cada ciclo deja una línea con la latencia de la base de datos y del POP3. Cuando una etapa falla, el log nombra la etapa, el '
    'destino (host y puerto) y la causa probable; mientras la falla sigue, una línea corta por ciclo con el próximo intento (la '
    'espera temporal progresa por 3, 6, 12, 24 y 30 segundos; los fallos permanentes de esquema o credenciales esperan 60), y al volver, RECUPERADO con cuántos ciclos y '
    'cuánto tiempo duró. Si la base de datos no está disponible, el ciclo no retira mensajes del buzón: se leen en el siguiente. '
    'Los fallos de resolución DNS se clasifican al intentar la conexión, sin un monitor paralelo. Los resúmenes del ciclo '
    'permiten identificar trabajos pendientes de una ejecución anterior (correos guardados sin procesar y respuestas en cola).')
d.tabla('Fallas que el sistema detecta y cómo las informa', ['Falla', 'Señal', 'Mensaje en el log'], [
    ('DNS caído o nombre inexistente', 'UnknownHostException', 'DNS: no se resuelve <host> (sin Internet o DNS caído)'),
    ('Servicio detenido o puerto cerrado', 'ConnectException', 'conexión rechazada en <host>:<puerto>'),
    ('Firewall o IP bloqueada', 'SocketTimeoutException', 'sin respuesta de <host>:<puerto> a tiempo'),
    ('IP no autorizada en PostgreSQL', 'SQLState 28000 (pg_hba.conf)', 'la IP <ip> no está autorizada en pg_hba.conf'),
    ('Clave de la base de datos cambiada', 'SQLState 28P01', 'usuario o contraseña incorrectos'),
    ('Esquema o tabla borrados', 'SQLState 3F000 / 42P01', 'el esquema no existe / faltan tablas: <lista>'),
    ('Permiso revocado', 'SQLState 42501', 'permiso denegado (¿REVOKE sobre la tabla?)'),
    ('Clave del buzón cambiada o cuenta deshabilitada', 'POP3 -ERR [AUTH]', 'POP3 rechazó el login'),
    ('Buzón bloqueado por otro lector', 'POP3 -ERR [IN-USE]', 'buzón bloqueado por otra sesión'),
    ('Clave de aplicación de Gmail revocada', 'SMTP 535', 'SMTP rechazó el login'),
    ('Respuesta rechazada por el servidor del destinatario', 'Aviso de rebote en la cuenta de Gmail (IMAP)', 'REBOTE respuesta a <correo> no entregada → <motivo>'),
    ('Conexión cortada por el servidor', 'SocketException', '<host>:<puerto> cortó la conexión'),
    ('Respuesta no enviada (Gmail caído o rechazo temporal)', 'MessagingException', 'PENDIENTE correo #k a <correo> … próximo intento en N min'),
    ('Respuesta imposible de entregar', 'SMTP 550 o 10 intentos', 'FALLIDO correo #k … La operación ya estaba confirmada; no se repite'),
    ('El servidor entrega de nuevo un correo ya guardado', 'Clave SHA-256 persistente repetida', 'YA GUARDADO <clave-sha256>: se borra del buzón sin repetirlo'),
    ('El usuario repite un comando de escritura', 'Mismo remitente y asunto', 'REPETIDO registro #n = registro #m'),
    ('Falta la migración de la bandeja', 'Columna o tabla ausente', 'falta la columna log_comandos.message_id: aplique …'),
], anchos=[4.6, 4.2, 7.2], tam=9, nota_txt='Traducción hecha por la clase Diagnostico (p1/src/main/java/bo/uagrm/crm/Diagnostico.java); DiagnosticoTest la comprueba sin red.')
d.p('La revisión de conexiones (ejecutar.ps1 -Diagnostico) se probó con la configuración real y con cinco copias alteradas: un '
    'host inexistente, el puerto 5433 en lugar del 5432, la contraseña del buzón, la contraseña de aplicación de Gmail y el esquema '
    'retirado crm. Cada una mostró su causa. En la última, Dovecot cortó además la conexión POP3, recién después de varias '
    'conexiones seguidas y un intento con contraseña errónea, y el log lo informó como un corte del servidor.')
d.figura(CAP / 'diagnostico-fallas.png', 'Revisión de conexiones con la configuración real y con cinco fallas provocadas',
         'Salida de p1/ejecutar.ps1 -Diagnostico en Windows PowerShell, 17 de noviembre de 2026; las configuraciones alteradas se borraron al terminar y se ocultó el último octeto de la IP pública.', 16)
d.p('Para comprobar la detección y la recuperación durante el servicio, el lector se conectó al POP3 de la facultad a través de '
    'un puerto local que se cerró durante dos ciclos y se volvió a abrir.')
d.figura(CAP / 'caida-recuperacion.png', 'Falla del POP3 detectada en el ciclo 3 y recuperación en el ciclo 5',
         'Extracto de logs/crm-mail.0.log del lector, 17 de noviembre de 2026.', 16)

# ---------------------------------------------------------------- IX
d.h('IX. Conclusiones', 1)
CONC = [f'Se identificaron cuatro actores y se detallaron los ocho casos de uso aprobados; la matriz de acceso de {N_CMD} comandos '
        'por rol se definió en el código y de ahí se generó el anexo de comandos, de modo que el documento y el sistema coinciden.',
        'El análisis agrupó los casos de uso en cuatro paquetes y mostró, en los diagramas de colaboración, que todo comando pasa '
        'por el mismo control central antes de llegar a la entidad que lo resuelve.',
        f'La arquitectura en cuatro capas, el modelo de datos conceptual, su análisis de normalización y el diseño lógico y físico ({len(TABLAS)} tablas '
        'compartidas con el Proyecto 2, con claves de diseño y relaciones CRM por FK; persona permanece independiente por su contrato docente) y el despliegue sobre los servidores '
        'reales de la facultad, Gmail y la PC del grupo quedaron diseñados con un único valor verificado por cada nodo y puerto.',
        'Se implementó el lector POP3 con sockets propios, el emisor SMTP, el intérprete de comandos y un servicio por caso de uso; '
        'toda consulta usa parámetros enlazados, y cada correo se guarda antes de borrarlo del buzón y se procesa en una sola '
        'transacción con su respuesta en cola, de modo que una caída no pierde solicitudes ni repite operaciones.',
        'El lector está preparado para ejecutarse en la PC del grupo y diagnostica las fallas de DNS, red, correo y base de datos con su causa; las pruebas '
        'automáticas comprueban el intérprete, la matriz de acceso, los ocho casos de uso y la bandeja, y la prueba de caídas, con '
        'cierres forzados y caídas de los tres servidores, terminó sin solicitudes perdidas ni operaciones repetidas.']
d.lista(CONC, numerada=True)

# ---------------------------------------------------------------- X
d.h('X. Recomendaciones', 1)
d.lista([
    'Aplicar las migraciones de bandeja, catálogo y persona (sql/migraciones/2026-11-19_bandeja_correo.sql, 2026-11-19_normalizar_catalogo.sql y 2026-11-19_persona.sql) en db_grupo05sa con el lector detenido y '
    'un respaldo previo, y repetir allí las pruebas de integración y una prueba con correos reales antes de la defensa.',
    'Provisionar claves HMAC individuales mediante un canal privado y verificar el trayecto de correo firmado. From y Authentication-Results suministrado por un remitente no prueban identidad. Rotar claves al revocar acceso.',
    'Conservar la firma HMAC sobre asunto, MIME, cuerpo y Message-ID. El cliente debe preservar el EML firmado; la posesión de clave no certifica identidad civil ni propiedad del dominio.',
    'Usar POP3 sobre TLS (puerto 995) cuando el servidor de la facultad lo habilite; hoy la contraseña del buzón viaja sin cifrar por el puerto 110.',
    'Mantener el sondeo serial de al menos tres segundos y confirmar con el docente cualquier límite adicional por servicio; no añadir monitores paralelos.',
    'Reemplazar el catálogo simulado por la API del SIS académico del Grupo 06 SA y enviarle las inscripciones exportadas.',
    'Ejecutar el lector en un equipo con IP fija autorizada en pg_hba.conf o, si la facultad lo habilita, dentro de su servidor, para no '
    'depender de la red desde la que se conecta la PC del grupo.',
])

# ---------------------------------------------------------------- XI
d.h('XI. Bibliografía Citada', 1)
d.referencias([
    'Booch, G., Rumbaugh, J., & Jacobson, I. (2006). El lenguaje unificado de modelado: guía del usuario (2.ª ed.). Madrid: Pearson Addison-Wesley.',
    'Callas, J., Donnerhacke, L., Finney, H., Shaw, D., & Thayer, R. (2007). OpenPGP message format (RFC 4880). https://www.rfc-editor.org/rfc/rfc4880',
    'Crocker, D., Hansen, T., & Kucherawy, M. (2011). DomainKeys Identified Mail (DKIM) signatures (RFC 6376). https://www.rfc-editor.org/rfc/rfc6376',
    'Eclipse Foundation. (2021). Jakarta Mail 2.1 specification. https://jakarta.ee/specifications/mail/2.1/',
    'Freed, N., & Borenstein, N. (1996). Multipurpose Internet Mail Extensions (MIME) part one: Format of Internet message bodies (RFC 2045). https://www.rfc-editor.org/rfc/rfc2045',
    'Gamma, E., Helm, R., Johnson, R., & Vlissides, J. (2003). Patrones de diseño: elementos de software orientado a objetos reutilizable. Madrid: Pearson Addison-Wesley.',
    'Hoffman, P. (2002). SMTP service extension for secure SMTP over Transport Layer Security (RFC 3207). https://www.rfc-editor.org/rfc/rfc3207',
    'Hohpe, G., & Woolf, B. (2003). Enterprise integration patterns: designing, building, and deploying messaging solutions. Boston: Addison-Wesley.',
    'Jacobson, I., Booch, G., & Rumbaugh, J. (2000). El proceso unificado de desarrollo de software. Madrid: Addison-Wesley.',
    'Kitterman, S. (2014). Sender Policy Framework (SPF) for authorizing use of domains in email, version 1 (RFC 7208). https://www.rfc-editor.org/rfc/rfc7208',
    'Klensin, J. (2008). Simple Mail Transfer Protocol (RFC 5321). https://www.rfc-editor.org/rfc/rfc5321',
    "Kleppmann, M. (2017). Designing data-intensive applications. Sebastopol: O'Reilly Media.",
    'Kotler, P., & Keller, K. L. (2016). Dirección de marketing (15.ª ed.). México: Pearson.',
    'Kurose, J. F., & Ross, K. W. (2017). Redes de computadoras: un enfoque descendente (7.ª ed.). Madrid: Pearson.',
    'Moore, K. (2004). Recommendations for automatic responses to electronic mail (RFC 3834). https://www.rfc-editor.org/rfc/rfc3834',
    'Myers, J., & Rose, M. (1996). Post Office Protocol - version 3 (RFC 1939). https://www.rfc-editor.org/rfc/rfc1939',
    'OWASP Foundation. (s.f.). SQL injection prevention cheat sheet. https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html',
    'Pressman, R. S. (2010). Ingeniería del software: un enfoque práctico (7.ª ed.). México: McGraw-Hill.',
    'Resnick, P. (2008). Internet message format (RFC 5322). https://www.rfc-editor.org/rfc/rfc5322',
    'Sandhu, R. S., Coyne, E. J., Feinstein, H. L., & Youman, C. E. (1996). Role-based access control models. Computer, 29(2), 38-47. https://doi.org/10.1109/2.485845',
    'Silberschatz, A., Korth, H. F., & Sudarshan, S. (2014). Fundamentos de bases de datos (6.ª ed.). Madrid: McGraw-Hill.',
    'Tanenbaum, A. S., & Wetherall, D. J. (2012). Redes de computadoras (5.ª ed.). México: Pearson.',
    'The PostgreSQL Global Development Group. (2026). PostgreSQL 18 documentation. https://www.postgresql.org/docs/18/',
])

# ---------------------------------------------------------------- XII
d.h('XII. Anexos', 1)
d.h('Anexo A. Comandos por rol', 2)
d.tabla('Comandos del sistema por rol', ['CU', 'Comando', 'Adm.', 'Coord.', 'Vend.', 'Públ.'],
        [(f[0], f[1], f[2], f[3], f[4], f[5]) for f in MATRIZ], anchos=[1.6, 9.6, 1.2, 1.3, 1.2, 1.1], tam=8, centrar_desde=2,
        nota_txt='Generada con java -jar crm-mail.jar --anexo a partir de las clases AdministradorCommandProcessor, '
                 'CoordinadorCommandProcessor, VendedorCommandProcessor y PublicoCommandProcessor. Los parámetros entre paréntesis son opcionales.')
d.tabla('Ejemplo de uso de cada comando', ['Comando', 'Ejemplo de asunto'],
        [(f[1].split('[')[0], f[6]) for f in MATRIZ], anchos=[4.6, 11.4], tam=8)
d.h('Anexo B. Script de la base de datos', 2)
# El volcado sin comentarios, sin SET de pg_dump y sin líneas en blanco repetidas: la misma estructura, más corta.
script = '\n'.join(l for l in SQL.splitlines() if not l.startswith(('--', 'SET ', 'SELECT pg_catalog', '\\restrict', '\\unrestrict')))
d.bloque(re.sub(r'\n{3,}', '\n\n', script).strip(), 'sql/esquema.sql')
d.bloque((P1 / 'sql' / 'migraciones' / '2026-11-19_bandeja_correo.sql').read_text(encoding='utf-8').strip(),
         'sql/migraciones/2026-11-19_bandeja_correo.sql (bandeja del correo sobre la base vigente)')
d.bloque((P1 / 'sql' / 'migraciones' / '2026-11-19_normalizar_catalogo.sql').read_text(encoding='utf-8').strip(),
         'sql/migraciones/2026-11-19_normalizar_catalogo.sql (programas y módulos sin repetición del nombre)')
d.bloque((P1 / 'sql' / 'migraciones' / '2026-11-19_persona.sql').read_text(encoding='utf-8').strip(),
         'sql/migraciones/2026-11-19_persona.sql (contrato docente independiente y versión del esquema)')

d.p('Las tres migraciones siguen pendientes en producción. Detener el lector y verificar el respaldo antes de aplicarlas '
    'con psql -X -v ON_ERROR_STOP=1 --single-transaction. Las reversiones se conservan en sql/migraciones/; el instalador '
    'esquema.sql se reserva para bases nuevas. Al revertir también se debe restaurar el JAR correspondiente.')
d.h('Anexo C. Ejecución y configuración', 2)
d.bloque((P1 / 'ejecutar.ps1').read_text(encoding='utf-8'), 'ejecutar.ps1')
d.bloque((P1 / 'crm-mail.properties.example').read_text(encoding='utf-8'), 'crm-mail.properties.example (las contraseñas reales no se publican)')

d.h('Anexo D. Láminas UML completas para revisión', 2)
d.p('El informe conserva el formato carta del manual FICCT. Los modelos globales y los procedimentales completos contienen '
    'muchos atributos y operaciones, por lo que su reproducción en carta reduce la letra. El archivo complementario '
    'P1-LAMINAS-UML-GRUPO05SA.pdf reproduce los mismos diagramas vectoriales a escala ampliada, con una sola lámina por figura '
    'y sin dividir casos de uso ni modelos por áreas. Sirve para lectura con zoom o impresión en hoja ampliada; no reemplaza '
    'este informe. Se genera desde el PDF final con scripts/laminas_uml.py y conserva la referencia a su figura y página.')

d.guardar(DOC / 'P1-INF513-GRUPO05SA.docx')
print(f'pendientes: {len(PENDIENTES)}')
for p in PENDIENTES: print('  -', p)
