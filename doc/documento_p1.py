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
    """Inserta una figura en el flujo del texto. Solo horizontal=True reserva una p?gina apaisada
    para diagramas globales anchos; por defecto la figura sigue el texto en la orientaci?n actual."""
    ruta = DIA / archivo
    if ruta.exists():
        import pymupdf
        px = pymupdf.Pixmap(str(ruta))
        w, h = px.width, px.height
        if horizontal is None:
            horizontal = False  # solo diagramas seleccionados expl?citamente van en p?gina apaisada
        if not horizontal and w >= 1600: ancho = 16.0
    horizontal = bool(horizontal)
    if horizontal: d.orientacion(True); ancho = 22.9
    for t, nivel in titulos: d.h(t, nivel)
    if ruta.exists():
        import pymupdf
        px = pymupdf.Pixmap(str(ruta))
        alto_max = (14.5 if horizontal else 20.0) - 1.7 * len(titulos)   # reservar rótulo y títulos en la misma página
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
    ('Base de datos', f'PostgreSQL 18.6, db.tecnoweb.org.bo:5432, db_grupo05sa, esquema public (compartido con el Proyecto 2): {len(TABLAS)} tablas', False),
    ('Ejecución', 'p1/ejecutar.ps1 en la PC del grupo; revisa el buzón cada 10 segundos y registra cada correo procesado en p1/logs/access.log y cada falla o excepción en p1/logs/error.log', False),
    ('Diagnóstico', 'p1/ejecutar.ps1 -Diagnostico revisa DNS, puertos, POP3, SMTP y la base de datos', False),
], 'El remitente se identifica por su dirección de correo: su rol, estado y vigencia (fecha de inicio y fin) se consultan en '
   'usuarios (el administrador crea las cuentas con INSERTARUSUARIO). Un correo no registrado actúa como visitante (consulta la '
   'oferta y registra su interés).')
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
    '`MOVERTARJETA["7","En Contacto"]`; identifica al remitente por su dirección, comprueba que su rol (administrador, '
    'coordinador o vendedor) pueda usar ese comando, lo ejecuta sobre una base de datos PostgreSQL y le responde con un correo HTML.')
d.p('El desarrollo siguió el Proceso Unificado de Desarrollo de Software (PUDS) con UML, modelado en Enterprise Architect. '
    'Como resultado se entrega un servicio Java que lee el buzón por POP3 con sockets propios, responde por SMTP, implementa los '
    f'ocho casos de uso aprobados con {N_CMD} comandos y se ejecuta en un equipo del grupo. Cada operación se ejecuta en una '
    'transacción con consultas preparadas y cada correo procesado queda registrado en la bitácora y en los archivos de log.')

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
    ('Transición', 'Ejecución en la PC del grupo contra el correo y la base de datos de la facultad, fallas provocadas y pruebas con correos reales por rol', 'Windows, Oracle JDK 23.0.1 con release 21', '5'),
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
    'los ocho casos de uso el 05/10/2026, más los que se ampliaron en las reuniones con la unidad (RF2.4, RF3.5 a RF3.7 y RF4.4) '
    'dentro de esos mismos casos de uso. La tabla indica el caso de uso que cubre cada uno y cómo se resuelve en este proyecto '
    'vía correo; lo propio de una interfaz web queda para el Proyecto 2.')
d.tabla('Requerimientos publicados y caso de uso que los cubre', ['Código', 'Requerimiento', 'CU', 'En el Proyecto 1 (correo)'], [
    ('RF1.1', 'Acceso con credenciales para tres roles (RBAC)', 'CU1', 'Remitente registrado en usuarios con rol, estado y vigencia (fecha de inicio y fin)'),
    ('RF1.2', 'El vendedor ve su cartera o la bolsa común', 'CU3, CU4', 'Alcance por rol en cada consulta'),
    ('RF1.3', 'El coordinador ve las versiones de programas a su cargo', 'CU2, CU8', 'ASIGNARCOORDINADOR y alcance por programa'),
    ('RF2.1', 'Registro manual o masivo (CSV) de prospectos', 'CU2', 'REGISTRARPROSPECTO, IMPORTARPROSPECTOS con .csv adjunto'),
    ('RF2.2', 'Interés vinculado al catálogo (programa o módulo)', 'CU2', 'Catálogo simulado: programas y sus módulos en catalogo_cache (API del SIS pendiente)'),
    ('RF2.3', 'Asignación manual o automática Round-Robin', 'CU3', 'ASIGNARPROSPECTO y ASIGNARAUTOMATICO por menor carga'),
    ('RF2.4', 'Reasignar un prospecto a otro vendedor sin perder su historial', 'CU3, CU7', 'ASIGNARPROSPECTO sobre una tarjeta ya asignada; VERHISTORIAL muestra quién hizo cada interacción'),
    ('RF3.1', 'Tablero Kanban con los prospectos como tarjetas', 'CU4', 'VERTABLERO responde el tablero en carriles'),
    ('RF3.2', 'Etapas Nuevo, En Contacto, Seguimiento, Promesa de Pago, Convertido, Perdido', 'CU4', 'Las seis etapas en MOVERTARJETA y VERTABLERO (en intereses.estado: Contactado, Interesado y Pago enviado/En revision para las etapas intermedias)'),
    ('RF3.3', 'Motivo obligatorio al pasar a Perdido', 'CU5', 'MARCARPERDIDO con motivo clasificado'),
    ('RF3.4', 'Validar datos antes de la matrícula', 'CU6', 'VALIDARCONVERSION y CONVERTIRPROSPECTO'),
    ('RF3.5', 'Orden de pago con descuentos, comprobante y revisión del pago', 'CU6', 'GENERARORDEN, REGISTRARCOMPROBANTE, APROBARPAGO y RECHAZARPAGO'),
    ('RF3.6', 'Comisión del vendedor al confirmar la inscripción', 'CU6', 'Se calcula al aprobar el pago con el porcentaje guardado en configuraciones'),
    ('RF3.7', 'Envío de las inscripciones al SIS del Grupo 06', 'CU6', 'EXPORTARMATRICULAS (CSV adjunto) y REGISTRARRESULTADOSIS'),
    ('RF4.1', 'Historial cronológico de interacciones', 'CU7', 'VERHISTORIAL'),
    ('RF4.2', 'Notas manuales', 'CU7', 'REGISTRARINTERACCION de tipo Nota'),
    ('RF4.3', 'Recordatorios de contacto', 'CU7', 'PROGRAMARRECORDATORIO; el aviso llega por correo'),
    ('RF4.4', 'El coordinador ve por vendedor cuántos prospectos tiene, contactó y concretó', 'CU8', 'REPORTEVENDEDORES'),
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
            'Administrador', 'El remitente es un administrador registrado, activo y con la credencial vigente.',
            ['El administrador envía INSERTARUSUARIO, MODIFICARUSUARIO, ELIMINARUSUARIO o LISTARUSUARIOS con sus parámetros en el asunto.',
             'El sistema descarga el correo por POP3 y CommandParser separa el comando y sus parámetros.',
             'CommandProcessor identifica al remitente por su correo y AdministradorCommandProcessor comprueba el permiso.',
             'UsuarioService valida nombre, correo, teléfono, rol y vigencia (fecha_fin no anterior a fecha_inicio; por omisión, un año) y ejecuta la operación en una transacción. El usuario se identifica por su correo.',
             'Al dar de baja a un vendedor, sus tarjetas abiertas vuelven a la bolsa común.',
             'El sistema responde con un correo HTML con el resultado.'],
            'El usuario queda registrado o actualizado y puede enviar comandos mientras su credencial esté vigente.',
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
             'Todo correo procesado queda en la bitácora del sistema (log_comandos) con su remitente, comando y resultado (Éxito, Error, Denegado o Ignorado).'],
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
SUB_CU = {
    'CU1': ['S3. Sin fechas de vigencia: la credencial vale desde hoy y por un año.',
            'S4. Baja de un vendedor: sus tarjetas abiertas vuelven a la bolsa común.'],
    'CU2': ['S3. Visitante (correo no registrado): solo LISTAROFERTA y REGISTRARINTERES; su interés entra a la bolsa común.',
            'S4. IMPORTARPROSPECTOS: cada fila del CSV se valida e informa por separado.'],
    'CU3': ['S3. ASIGNARAUTOMATICO reparte por menor carga, deja juntos los intereses de un mismo prospecto y avisa por correo a cada vendedor.',
            'S4. ASIGNARPROSPECTO sobre una tarjeta ya asignada la pasa a otro vendedor y conserva todo su historial.'],
    'CU4': ['S3. Las etapas financieras (Promesa de Pago, Convertido) no se mueven a mano: las fija el pago del CU6.'],
    'CU5': ['S3. Un motivo ya usado no se elimina; se puede cambiar su nombre o su categoría.'],
    'CU6': ['S3. Pago rechazado: queda en el historial y el vendedor adjunta otro comprobante.',
            'S4. CONVERTIRPROSPECTO sobre una tarjeta ya inscrita comprueba la inscripción sin duplicarla.'],
    'CU7': ['S3. Recordatorio vencido: cada ciclo se envía por correo a su responsable y queda marcado como avisado.'],
    'CU8': ['S3. El vendedor obtiene REPORTEEMBUDO solo de su cartera.']}
EXC_INFRA = ['Base de datos caída antes de leer el buzón: no se retira ningún correo; los comandos esperan en el buzón y se '
             'atienden cuando vuelve la conexión.',
             'Base de datos caída durante el proceso: la transacción se deshace (ROLLBACK) y el remitente recibe "Error: no hay '
             'conexión con la base de datos"; la causa queda en error.log.',
             'Servidor POP3 caído o sin respuesta: los comandos esperan en el buzón y el ciclo siguiente reintenta.',
             'Gmail no acepta la respuesta: la operación ya quedó confirmada; el fallo de envío queda en la bitácora y en error.log.',
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
        ('Subflujos', '\n'.join(SUB_COMUN + SUB_CU[cu])),
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
    'tecnoweb.org.bo: mail.tecnoweb.org.bo, dirección resuelta por DNS al operar), donde Sendmail lo deja en el buzón grupo05sa. '
    'El lector se configura en un equipo del grupo (Windows, JDK 21 o posterior, bytecode Java 21): p1/ejecutar.ps1 arranca '
    'p1/target/crm-mail.jar con la configuración .secrets/crm-mail.properties, que no se publica porque lleva las contraseñas. El '
    f'programa lee el buzón por POP3 (Dovecot, puerto 110), consulta y actualiza la base db_grupo05sa en PostgreSQL 18.6 (puerto '
    f'5432; {len(TABLAS)} tablas) y responde por smtp.gmail.com (puerto 587 con STARTTLS), porque el SMTP de la facultad rechaza '
    'el reenvío a dominios externos con "550 5.7.1 Relaying denied". Cada ciclo y cada correo atendido quedan en '
    'p1/logs/access.log, y cada falla en p1/logs/error.log. PostgreSQL acepta la conexión según la IP de origen (pg_hba.conf): si '
    'el equipo cambia de red, el diagnóstico muestra la IP rechazada.')
fig('despliegue.png', 'Diagrama de despliegue', horizontal=True)
d.p('En el diagrama, cada servidor es un nodo «device» con sus entornos de ejecución («executionEnvironment»: sistema operativo, '
    'máquina virtual y servicios). Los artefactos llevan su ruta real y su estereotipo («executable» para el .jar, «file» para el '
    'script de arranque, la configuración, el log y el buzón) y contienen los componentes que despliegan: las cuatro capas en el .jar '
    'y las tablas y la bitácora en la base de datos. Las dependencias entre artefactos muestran quién arranca, lee o escribe a quién, y cada '
    'ruta de comunicación lleva su protocolo y puerto. Google, que no administra el grupo, se modela como «External System».')
d.h('Arquitectura lógica: capas', 3)
d.tabla('Capas del sistema', ['Capa', 'Paquete Java', 'Clases'], [
    ('Presentación', 'bo.uagrm.crm.presentacion y bo.uagrm.crm', 'App (bucle del servicio: lee, procesa y responde), Diagnostico (causa probable de cada falla y revisión de conexiones), Pop3Client (lector POP3 con sockets), SmtpSender (emisor SMTP), Correo (MIME)'),
    ('Aplicación', 'bo.uagrm.crm.aplicacion', 'CommandParser, Command, Comando, CommandProcessor, RolCommandProcessor y sus cuatro subclases por rol, ResponseFormatter, Respuesta'),
    ('Dominio', 'bo.uagrm.crm.dominio.<cu>', 'Un paquete por caso de uso con su servicio y sus entidades: usuarios, captacion, asignacion, embudo, perdidas, conversion, pagos, seguimiento, reportes'),
    ('Persistencia', 'bo.uagrm.crm.persistencia', 'DatabaseService: conexión JDBC, consultas parametrizadas y transacciones'),
], anchos=[2.8, 4.6, 8.6])
fig('capas.png', 'Diagrama de capas', horizontal=True)

d.h('Ciclo de lectura y manejo de errores', 3)
d.p('App revisa el buzón cada 10 segundos, contados desde el fin del ciclo anterior (enunciado: lectura periódica de la '
    'casilla grupo05sa@tecnoweb.org.bo). Antes de abrir el buzón comprueba que la base de datos responda y tenga sus tablas: si '
    'no, no retira ningún correo y anota la causa en error.log. Luego descarga hasta 20 mensajes por POP3 (STAT, RETR, DELE, QUIT; '
    'de un correo de más de 8 MB solo las cabeceras, con TOP) y atiende cada uno: CommandParser valida el comando, los corchetes, '
    'las comillas y la cantidad de parámetros; el procesador del rol valida el permiso y el servicio valida el tipo y el formato '
    'de cada parámetro antes de ejecutar la consulta preparada dentro de una transacción. Si algo falla, el remitente recibe un '
    'correo de Error que nombra el problema concreto (comando inexistente, parámetro N incorrecto, base de datos no disponible).')
d.tabla('Momentos de falla y comportamiento del sistema', ['Momento de la falla', 'Qué hace el sistema'], [
    ('Base de datos no disponible al empezar el ciclo', 'No abre el buzón: los comandos esperan y se atienden cuando vuelve la conexión'),
    ('Base de datos caída durante la operación', 'ROLLBACK; el remitente recibe "Error: no hay conexión con la base de datos"'),
    ('Servidor POP3 caído o sin respuesta', 'El ciclo siguiente reintenta; los comandos siguen en el buzón'),
    ('Gmail no acepta la respuesta', 'La operación ya quedó confirmada; el fallo queda en la bitácora y en error.log'),
    ('Comando inexistente, sin corchetes o con parámetros incorrectos', 'Correo de Error con el parámetro y la forma correcta del comando'),
    ('Comando no permitido al rol, cuenta inactiva o vencida', 'Correo Denegado; no se toca la base de datos'),
], anchos=[6.0, 10.0])
d.p('Cada ciclo deja una línea en access.log con la latencia de cada etapa, y cada correo atendido otra con su remitente, '
    'comando y resultado; cada falla queda en error.log con la etapa, el destino (host:puerto) y la causa probable que traduce '
    'Diagnostico (DNS, puerto cerrado, IP fuera de pg_hba.conf, clave cambiada, tabla borrada, buzón bloqueado).')

d.h('Diagramas de secuencia', 3)
d.p('Cada diagrama de CU muestra el flujo desde el correo recibido por POP3 hasta la respuesta SMTP. App entrega el mensaje a procesarEmail(), que interpreta el comando, consulta al remitente y deriva a la l\u00f3gica p\u00fablica o al procesador del rol. Si SMTP falla, la respuesta queda en memoria para reintento; no hay bandeja persistente ni procesamiento IMAP.')
d.p('En cada diagrama, un fragmento alt distingue el permiso del rol y los comandos de ese caso de uso. Se muestran las llamadas al servicio y las operaciones de DatabaseService usadas por el c\u00f3digo. Los flujos generales de lectura y respuesta est\u00e1n integrados en cada CU; se retiraron los diagramas auxiliares antiguos de bandeja persistente y rebotes IMAP.')
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
fig('conceptual-global.png', 'Modelo conceptual global (modelo de dominio final)', horizontal=True)


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
    ('programas.codigo → nombre; catalogo_cache.codigo → programa_codigo', 'El catálogo local, réplica de la oferta del SIS del Grupo 06 SA, separa programas y módulos: el nombre del programa se guarda una sola vez. Dos FK vinculan los módulos y sus responsables con programas. La migración 2026-11-19_normalizar_catalogo.sql hizo esta separación en la base vigente conservando códigos, oferta e históricos.'),
    ('intereses.programa_nombre, modulo_nombre y precio; solicitudes_compra.programa_nombre, modulo_nombre y precio_acordado; orden_pago_descuentos.porcentaje; log_comandos.rol_registrado', 'Conservan el nombre, precio, descuento o rol de la operación. Se cotejan con su significado histórico: un precio acordado o un rol registrado en una fecha no equivalen al precio o rol actuales. Su almacenamiento no demuestra por sí solo una infracción de 3FN.'),
    ('ordenes_pago.total y pagos.monto', 'El total (subtotal menos descuento_total) y el monto del pago se guardan para que la orden y el pago emitidos no cambien si se recalculan.'),
], anchos=[6.0, 10.0], tam=9)

d.h('Diseño de datos: modelo lógico', 3)
d.p(f'El modelo lógico tiene {len(TABLAS)} tablas para el esquema public de db_grupo05sa, el mismo que usará el Proyecto 2 web, con '
    f'{len(FKS)} claves foráneas; ninguna tabla queda sin relación. En el diagrama, la clave '
    'primaria va primero y las claves foráneas no se repiten como atributo: cada una es una asociación con su multiplicidad, '
    'rotulada con el papel cuando no es obvio. El modelo se presenta completo en una sola figura global. Una FK que es también '
    'PK simple, como pagos.nro_orden, limita a uno el registro dependiente: no se representa como una relación 1 a N.')
d.p('Cada tabla tiene una sola clave, la de su diseño y con su nombre; ninguna lleva un campo id. Las entidades que tienen una '
    'clave natural se identifican por ella: usuarios por su correo, '
    'prospectos por el documento (CI), descuentos por su código, motivos_perdida por su nombre, roles por su nombre, catalogo_cache '
    'por el código del módulo, programas por su código, configuraciones por su clave y api_tokens por el hash del token. Las que dependen 1 a 1 de otra '
    'toman la clave de su dueño: pagos la de su orden (nro_orden), y comisiones e inscripciones_comerciales la de su solicitud de '
    'compra (nro_solicitud). Las que no tienen clave natural usan un correlativo con el nombre del diseño: nro_tarjeta, nro_orden, '
    'nro_solicitud, nro_interaccion, nro_movimiento, nro_recordatorio, nro_notificacion, nro_registro, nro_exportacion, '
    'nro_comprobante. Cada clave foránea lleva el nombre de la clave a la que apunta o el del papel que cumple '
    '(vendedor_correo, coordinador_correo). La intermedia orden_pago_descuentos tiene como clave las dos claves foráneas (nro_orden y '
    'descuento_codigo) y guarda el porcentaje y el monto aplicados. La credencial de cada usuario tiene vigencia: fecha_inicio y '
    'fecha_fin, con la restricción fecha_fin >= fecha_inicio.')
fig('datos-global.png', 'Modelo lógico global de datos', horizontal=True)

d.h('Diseño de datos: modelo físico', 3)
d.p('El modelo físico es el de PostgreSQL 18: cada tabla con sus columnas, tipos, claves y nulos (tablas siguientes) y, en el '
    'script del Anexo B, sus secuencias, valores por omisión, restricciones CHECK y UNIQUE, índices y disparadores. Las columnas '
    'de cada tabla coinciden una a una con las de la base db_grupo05sa.')
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
    'Las dependencias se muestran entre capas y componentes, sin repetir los métodos de los procedimentales. App lee los correos con '
    'Pop3Client, los atiende con CommandProcessor y responde con SmtpSender; Diagnostico revisa el lector, el emisor y la base de datos, y UsuarioService usa '
    'jBCrypt para guardar la clave web del usuario como hash. Hacia afuera, los ensamblajes muestran una sola pareja de interfaz requerida '
    '(semicírculo) y provista (círculo), con la API o protocolo de la conexión: el lector '
    'de correo con el POP3 de Dovecot (puerto 110), el emisor SMTP con la API Jakarta Mail de Angus Mail y esta con el SMTP de '
    'Gmail (587), y DatabaseService con JDBC de PostgreSQL JDBC y este con el protocolo '
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
    ('Oracle JDK', '23.0.1', 'Compilación con release 21, pruebas y ejecución del lector'),
    ('Windows', '11', 'Sistema operativo de la PC del grupo que ejecuta el lector'),
    ('Apache Maven', '3.9.9', 'Construcción y dependencias; jar único con maven-shade-plugin 3.6.0'),
    ('Angus Mail / Jakarta Mail API', '2.0.5 / 2.1.5', 'Análisis MIME y envío SMTP'),
    ('PostgreSQL JDBC', '42.7.13', 'Conexión a la base de datos'),
    ('jBCrypt', '0.4', 'Hash de la clave web al registrar un usuario'),
    ('PostgreSQL', '18.6', 'Base de datos db_grupo05sa en db.tecnoweb.org.bo'),
    ('JUnit Jupiter', '5.10.5', 'Pruebas unitarias y de integración'),
    ('PostgreSQL (pruebas)', '18', 'Base desechable en 127.0.0.1 para las pruebas de integración'),
    ('Python', '3.8.10', 'Generación del documento y de los diagramas de Enterprise Architect'),
    ('Enterprise Architect', '15.0.0.1514', 'Herramienta CASE (modelado UML)'),
    ('Git / GitHub', '2.49.0 / repositorio remoto', 'Control de versiones; sin publicación automática de esta revisión'),
], anchos=[5.2, 3.2, 7.6])

# ---------------------------------------------------------------- VIII
d.h('VIII. Resultados', 1)
junit = []
for x in sorted((P1 / 'target' / 'surefire-reports').glob('TEST-*.xml')):
    r = ET.parse(x).getroot()
    junit.append((r.get('name').split('.')[-1], r.get('tests'),
                  str(int(r.get('tests')) - int(r.get('failures')) - int(r.get('errors')) - int(r.get('skipped', '0'))),
                  r.get('skipped')))
if junit:
    d.tabla('Pruebas automáticas (JUnit) de la versión entregada', ['Clase de prueba', 'Casos', 'Aprobados', 'Omitidos'], junit,
            anchos=[7, 3, 3, 3], nota_txt='Ejecución del 20/11/2026 con mvn clean test. Las pruebas de integración usan una base '
            'PostgreSQL 18 desechable en 127.0.0.1, creada con sql/esquema.sql y sql/referencias.sql; las omitidas son las que '
            'se ejecutan contra el servidor de la facultad.')
else:
    rojo('ejecutar mvn test para generar target/surefire-reports')
d.p('La prueba de robustez (RobustezCorreoTest) envió cada comando, con cada rol y con un visitante, con entradas malas: '
    'parámetros vacíos o solo con espacios, letras donde va un número, negativos, números enormes, inyección SQL, textos de 5000 '
    'caracteres, emojis, fechas imposibles, parámetros de más, corchetes y comillas sin cerrar, y siete archivos CSV dañados. '
    'Exige que no aparezcan errores internos ni SQL sin traducir, que lo malformado no se acepte y que las tablas conserven su '
    'integridad.')
d.tabla('Cotejo con el enunciado del Proyecto 1', ['Requisito del enunciado', 'Cómo se cumple'], [
    ('Comandos solo por el asunto del correo', 'CommandParser lee COMANDO["p1","p2",...] del Subject; ningún comando necesita la web'),
    ('Lectura periódica de la casilla del grupo', 'App lee grupo05sa@tecnoweb.org.bo por POP3 cada 10 segundos (ejecutar.ps1)'),
    ('Validación sintáctica y semántica', 'Comando existente, corchetes, comillas, cantidad exacta de parámetros (CommandParser) y tipo y formato de cada uno (servicio del CU)'),
    ('Espacios, UTF-8 y comillas escapadas', 'Se aceptan espacios de más, texto UTF-8 y comillas dentro de un parámetro como "" o \\" (CommandParserTest)'),
    ('Sentencias preparadas', 'Toda consulta pasa por PreparedStatement en DatabaseService; ningún dato del usuario se concatena al SQL'),
    ('Ejecución transaccional', 'DatabaseService.enTransaccion(): COMMIT si todo sale bien, ROLLBACK si algo falla'),
    ('Respuesta de éxito o de error al remitente', 'SmtpSender responde al From del correo: Éxito con los datos, o Error con el problema concreto'),
    ('Logs error.log y access.log', 'p1/logs/access.log (cada ciclo y cada correo) y p1/logs/error.log (fallas y excepciones con su causa)'),
    ('README y repositorio inf513-proyecto1', 'README.md con arquitectura, estructura de la base de datos, instalación y diagrama de flujo del analizador'),
], anchos=[5.2, 10.8], tam=9)
d.h('Funcionamiento con correos reales', 2)
d.p('El lector se ejecuta en la PC del grupo. Al arrancar revisa las conexiones (POP3, SMTP y base de datos); luego atiende los '
    'comandos que llegan al buzón. Se comprobó con el remitente de un vendedor registrado: HELP[] (Éxito) y VERBITACORA["5"], '
    'reservado al administrador (Denegado). Ambos quedaron en la tabla log_comandos con el rol del remitente. Del mismo modo se '
    'comprobó un remitente institucional (@ficct.uagrm.edu.bo) con HELP[] y con Re: LISTAROFERTA[]: ambas respuestas llegaron a '
    'esa cuenta.')
captura = CAP / 'servicio-local-v2.png'
if captura.exists():
    d.figura(captura, 'Arranque del lector, revisión de conexiones y comandos atendidos',
             'Salida en consola del lector en la PC del grupo (hora de Bolivia); se ocultó el último octeto de la IP pública.', 16)
else:
    d.rotulo('Figura', 'Arranque del lector, revisión de conexiones y comandos atendidos')
    rojo('captura nueva del lector con access.log (capturas/servicio-local-v2.png)')
for nombre, titulo in [('gmail-help.png', 'Respuesta a HELP[]: comandos permitidos al rol del remitente'),
                       ('gmail-denegado.png', 'Respuesta a VERBITACORA["5"] enviado por un vendedor: comando denegado por rol')]:
    if (CAP / nombre).exists():
        d.figura(CAP / nombre, titulo, 'Correo de respuesta recibido en la cuenta Gmail del vendedor de prueba, mostrado con su '
                 'HTML original, 18 de noviembre de 2026.', 12)
    else:
        d.rotulo('Figura', titulo); rojo(f'captura de Gmail capturas/{nombre}')
d.h('Diagnóstico de fallas', 2)
d.p('El docente puede deshabilitar un servicio o cambiar una configuración durante la defensa; el sistema debe decir qué pasó. '
    'Cada ciclo deja una línea en access.log con la latencia de la base de datos y del POP3. Cuando una etapa falla, error.log '
    'nombra la etapa, el destino (host y puerto) y la causa probable; mientras la falla sigue, una línea corta por ciclo, y al '
    'volver, RECUPERADO con cuántos ciclos y cuánto tiempo duró. Si la base de datos no está disponible, el ciclo no retira '
    'mensajes del buzón: se leen en el siguiente.')
d.tabla('Fallas que el sistema detecta y cómo las informa', ['Falla', 'Señal', 'Mensaje en error.log'], [
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
    ('Conexión cortada por el servidor', 'SocketException', '<host>:<puerto> cortó la conexión'),
    ('Respuesta no enviada (Gmail caído o rechazo)', 'MessagingException', 'SMTP smtp.gmail.com:587 → <causa> (respuesta a <correo> no enviada)'),
], anchos=[4.6, 4.2, 7.2], tam=9, nota_txt='Traducción hecha por la clase Diagnostico (p1/src/main/java/bo/uagrm/crm/Diagnostico.java); DiagnosticoTest la comprueba sin red.')
d.p('La revisión de conexiones (ejecutar.ps1 -Diagnostico) se probó con la configuración real y con copias alteradas: un '
    'host inexistente, el puerto 5433 en lugar del 5432, la contraseña del buzón, la contraseña de aplicación de Gmail y un '
    'esquema inexistente. Cada una mostró su causa.')
captura = CAP / 'diagnostico-fallas-v2.png'
if captura.exists():
    d.figura(captura, 'Revisión de conexiones con la configuración real y con fallas provocadas',
             'Salida de p1/ejecutar.ps1 -Diagnostico en Windows PowerShell; las configuraciones alteradas se borraron al terminar.', 16)
else:
    d.rotulo('Figura', 'Revisión de conexiones con la configuración real y con fallas provocadas')
    rojo('captura nueva de ejecutar.ps1 -Diagnostico (capturas/diagnostico-fallas-v2.png)')
d.p('Para comprobar la detección y la recuperación durante el servicio, el lector se conectó al POP3 de la facultad a través de '
    'un puerto local que se cerró durante dos ciclos y se volvió a abrir.')
d.figura(CAP / 'caida-recuperacion.png', 'Falla del POP3 detectada en el ciclo 3 y recuperación en el ciclo 5',
         'Extracto del log del lector, 17 de noviembre de 2026 (entonces en un solo archivo; hoy la falla va a error.log y los ciclos a access.log).', 16)

# ---------------------------------------------------------------- IX
d.h('IX. Conclusiones', 1)
CONC = [f'Se identificaron cuatro actores y se detallaron los ocho casos de uso aprobados. La matriz de acceso de {N_CMD} comandos '
        'por rol se definió en el código y de ella se generó el anexo; así, los requerimientos, la documentación y los permisos '
        'del sistema quedan alineados.',
        'El análisis organizó los casos de uso en cuatro paquetes. Los diagramas de colaboración muestran cómo las clases de control '
        'coordinan la interacción con las entidades para resolver cada caso.',
        f'Se diseñaron la arquitectura en cuatro capas, el modelo de datos conceptual, su normalización hasta 3FN y el diseño lógico '
        f'y físico de {len(TABLAS)} tablas compartidas con el Proyecto 2. También se documentó el despliegue en los servidores de la '
        'facultad, Gmail y la PC del grupo.',
        'Se implementaron en Java el lector POP3 con sockets, el emisor SMTP, el intérprete con control por rol y los servicios de '
        'los casos de uso. Las consultas usan sentencias preparadas y cada operación se confirma o se revierte dentro de una transacción.',
        'El lector se puso en marcha en la PC del grupo y revisa el buzón cada 10 segundos. access.log registra los correos atendidos '
        'y error.log las fallas y sus causas. Las pruebas automáticas recorren los casos aprobados y los comandos por rol; las capturas '
        'de Gmail documentan la interacción con el servicio.']
inicio_conclusiones = len(d.doc.paragraphs)
d.lista(CONC, numerada=True)
for par in d.doc.paragraphs[inicio_conclusiones:]:
    par.paragraph_format.keep_together = True

# ---------------------------------------------------------------- X
d.h('X. Recomendaciones', 1)
d.lista([
    'Comprobar la autenticidad del remitente con SPF y DKIM (Kitterman, 2014; Crocker, Hansen y Kucherawy, 2011) o firmar con '
    'OpenPGP los comandos sensibles (Callas, Donnerhacke, Finney, Shaw y Thayer, 2007): hoy el remitente se identifica por la '
    'dirección From, que un tercero podría falsificar.',
    'Usar POP3 sobre TLS (puerto 995) cuando el servidor de la facultad lo habilite; hoy la contraseña del buzón viaja sin cifrar por el puerto 110.',
    'Reemplazar el catálogo simulado por la API del SIS académico del Grupo 06 SA y enviarle las inscripciones exportadas.',
    'Ejecutar el lector en un equipo con IP fija autorizada en pg_hba.conf o, si la facultad lo habilita, dentro de su servidor, para no '
    'depender de la red desde la que se conecta la PC del grupo.',
])

d.h('XI. Bibliografía Citada', 1)
d.referencias([
    'Booch, G., Rumbaugh, J., & Jacobson, I. (2006). El lenguaje unificado de modelado: guía del usuario (2.ª ed.). Madrid: Pearson Addison-Wesley.',
    'Callas, J., Donnerhacke, L., Finney, H., Shaw, D., & Thayer, R. (2007). OpenPGP message format (RFC 4880). https://www.rfc-editor.org/rfc/rfc4880',
    'Crocker, D., Hansen, T., & Kucherawy, M. (2011). DomainKeys Identified Mail (DKIM) signatures (RFC 6376). https://www.rfc-editor.org/rfc/rfc6376',
    'Eclipse Foundation. (2021). Jakarta Mail 2.1 specification. https://jakarta.ee/specifications/mail/2.1/',
    'Freed, N., & Borenstein, N. (1996). Multipurpose Internet Mail Extensions (MIME) part one: Format of Internet message bodies (RFC 2045). https://www.rfc-editor.org/rfc/rfc2045',
    'Gamma, E., Helm, R., Johnson, R., & Vlissides, J. (2003). Patrones de diseño: elementos de software orientado a objetos reutilizable. Madrid: Pearson Addison-Wesley.',
    'Hoffman, P. (2002). SMTP service extension for secure SMTP over Transport Layer Security (RFC 3207). https://www.rfc-editor.org/rfc/rfc3207',
    'Jacobson, I., Booch, G., & Rumbaugh, J. (2000). El proceso unificado de desarrollo de software. Madrid: Addison-Wesley.',
    'Kitterman, S. (2014). Sender Policy Framework (SPF) for authorizing use of domains in email, version 1 (RFC 7208). https://www.rfc-editor.org/rfc/rfc7208',
    'Klensin, J. (2008). Simple Mail Transfer Protocol (RFC 5321). https://www.rfc-editor.org/rfc/rfc5321',
    'Kotler, P., & Keller, K. L. (2016). Dirección de marketing (15.ª ed.). México: Pearson.',
    'Kurose, J. F., & Ross, K. W. (2017). Redes de computadoras: un enfoque descendente (7.ª ed.). Madrid: Pearson.',
    'Moore, K. (2004). Recommendations for automatic responses to electronic mail (RFC 3834). https://www.rfc-editor.org/rfc/rfc3834',
    'Myers, J., & Rose, M. (1996). Post Office Protocol - version 3 (RFC 1939). https://www.rfc-editor.org/rfc/rfc1939',
    'OWASP Foundation. (s.f.). SQL injection prevention cheat sheet. https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html',
    'Pressman, R. S. (2010). Ingeniería del software: un enfoque práctico (7.ª ed.). México: McGraw-Hill.',
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
d.bloque((P1 / 'sql' / 'migraciones' / '2026-11-19_normalizar_catalogo.sql').read_text(encoding='utf-8').strip(),
         'sql/migraciones/2026-11-19_normalizar_catalogo.sql (programas y módulos sin repetir el nombre del programa)')
d.h('Anexo C. Ejecución y configuración', 2)
d.bloque((P1 / 'ejecutar.ps1').read_text(encoding='utf-8'), 'ejecutar.ps1')
d.bloque((P1 / 'crm-mail.properties.example').read_text(encoding='utf-8'), 'crm-mail.properties.example (las contraseñas reales no se publican)')

d.guardar(DOC / 'P1-INF513-GRUPO05SA.docx')
print(f'pendientes: {len(PENDIENTES)}')
for p in PENDIENTES: print('  -', p)
