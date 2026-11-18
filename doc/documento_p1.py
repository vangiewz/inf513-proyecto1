"""Genera el documento PUDS del Proyecto 1 (CRM vía e-mail, Grupo 05 SA) con el formato FICCT/APA.
Uso: python p1/doc/documento_p1.py  ->  p1/doc/P1-INF513-GRUPO05SA.docx
Luego: powershell -File tools/word_export.ps1 p1/doc/P1-INF513-GRUPO05SA.docx
Estructura: la del P1 aprobado (Grupo 17 SA) — 12 secciones del Anexo B y Desarrollo por flujos del PUDS.
Lo que aún no existe (figuras de Enterprise Architect, datos de la empresa) sale en rojo y se lista al final."""
import csv, json, pathlib, re, sys
import xml.etree.ElementTree as ET

RAIZ = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(RAIZ / 'tools'))
from doc_ficct import Informe
from docx.shared import RGBColor

P1 = RAIZ / 'p1'
DOC = P1 / 'doc'
DIA = DOC / 'diagramas'
SQL = (P1 / 'sql' / 'esquema.sql').read_text(encoding='utf-8')
MATRIZ = list(csv.reader((DOC / 'matriz-acceso.tsv').read_text(encoding='utf-8').splitlines(), delimiter='\t'))[1:]
N_CMD = sum(1 for f in MATRIZ if f[0] != 'General')   # comandos de los CU (sin HELP)
sys.path.insert(0, str(DOC / 'ea'))
import ea_p1   # noqa: E402  el modelo de datos se lee igual que para los diagramas
TABLAS, FKS, PKS, UNICAS = ea_p1.tablas_sql(), ea_p1.fks_sql(), ea_p1.pks_sql(), ea_p1.unicas_sql()
PENDIENTES = []

# Datos que el grupo aún debe entregar: None = pendiente (se imprime en rojo).
GITHUB = 'https://github.com/vangiewz/inf513-proyecto1'
FECHA = '17 de noviembre de 2026'

d = Informe()


def rojo(texto):
    PENDIENTES.append(texto)
    par = d.p(f'[PENDIENTE: {texto}]', sangria=False)
    for r in par.runs:
        r.font.color.rgb = RGBColor(0xC0, 0x00, 0x00); r.bold = True
    return par


def fig(archivo, titulo, ancho=16.0, nota=None, horizontal=False, titulos=()):
    """Figura de EA. horizontal=True: va sola en una página apaisada (diagramas anchos, legibles al imprimir).
    titulos: [(texto, nivel)] que encabezan la página apaisada, para que no queden huérfanos en la página anterior."""
    ruta = DIA / archivo
    if horizontal and ruta.exists():
        import pymupdf
        px = pymupdf.Pixmap(str(ruta))
        if px.width / px.height < 1.3: horizontal = False; ancho = 16.0   # más alta que ancha: rinde más en vertical
    if horizontal: d.orientacion(True); ancho = 22.9
    for t, nivel in titulos: d.h(t, nivel)
    if ruta.exists():
        import pymupdf
        px = pymupdf.Pixmap(str(ruta))
        alto_max = (15.0 if horizontal else 20.5) - 1.2 * len(titulos)   # cm disponibles bajo el rótulo
        d.figura(ruta, titulo, nota, min(ancho, alto_max * px.width / px.height))
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
    ('Base de datos', 'PostgreSQL 18.6, db.tecnoweb.org.bo:5432, db_grupo05sa, esquema public (compartido con el Proyecto 2)', False),
    ('Ejecución', 'p1/ejecutar.ps1 en la PC del grupo; revisa el buzón cada 10 segundos y deja cada ciclo y cada falla en p1/logs/crm-mail.0.log', False),
    ('Diagnóstico', 'p1/ejecutar.ps1 -Diagnostico revisa DNS, puertos, POP3, SMTP, IMAP de rebotes y la base de datos', False),
], 'El remitente se identifica por su correo: el rol lo da la cuenta registrada (el administrador crea las demás con '
   'INSERTARUSUARIO). Un correo no registrado actúa como visitante (consulta la oferta y registra su interés).')
d.indices()

# ---------------------------------------------------------------- I
d.h('I. Introducción', 1)
d.p('El presente documento describe el análisis, diseño, implementación y despliegue de un sistema de gestión de relaciones con '
    'clientes (CRM) orientado a la captación de prospectos y su conversión en matrícula, operado exclusivamente por correo '
    'electrónico. La unidad académica que comercializa programas de formación (programas de varios módulos y módulos '
    'independientes) trabaja hoy con planillas y mensajes sueltos: los prospectos se reparten a mano, su avance no queda '
    'registrado y las pérdidas no se analizan.')
d.p('El sistema recibe en el asunto de un correo enviado a grupo05sa@tecnoweb.org.bo un comando con sus parámetros, por ejemplo '
    '`MOVERTARJETA["7","En Contacto"]`; identifica al remitente por su dirección, comprueba que su rol (administrador, coordinador '
    'o vendedor) pueda usar ese comando, lo ejecuta sobre una base de datos PostgreSQL y le responde con un correo HTML.')
d.p('El desarrollo siguió el Proceso Unificado de Desarrollo de Software (PUDS) con UML, modelado en Enterprise Architect. '
    'Como resultado se entrega un servicio Java que lee el buzón por POP3 con sockets propios, responde por SMTP, implementa los '
    f'ocho casos de uso aprobados con {N_CMD} comandos y se ejecuta en un equipo del grupo, donde registra cada ciclo de lectura y '
    'la causa de cada falla de red, de correo o de base de datos.')

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
    ('Construcción', 'Programación por capas, script SQL y pruebas unitarias', 'Java, Maven 3.9.16, JUnit 5.10.5, Git', '4'),
    ('Transición', 'Ejecución local con diagnóstico de conexiones, fallas provocadas y pruebas con correos reales por rol', 'Windows 11, Eclipse Temurin JDK 21.0.12', '5'),
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
d.h('Priorizar casos de uso', 3)
PRIO = {'CU1': 'Alta', 'CU2': 'Alta', 'CU3': 'Alta', 'CU4': 'Alta', 'CU5': 'Media', 'CU6': 'Alta', 'CU7': 'Media', 'CU8': 'Baja'}
d.tabla('Priorización de casos de uso', ['ID', 'Actores involucrados', 'Comandos', 'Prioridad'],
        [(cu, ', '.join(actores(cu)), str(len(comandos(cu))), PRIO[cu]) for cu in PRIO], anchos=[1.6, 9.0, 2.4, 3.0])

CU = {
    'CU1': ('Gestión de Usuarios', 'Registrar, listar, modificar y dar de baja a los usuarios del CRM con su rol.',
            'Administrador', 'El remitente es un administrador activo.',
            ['El administrador envía INSERTARUSUARIO, MODIFICARUSUARIO, ELIMINARUSUARIO o LISTARUSUARIOS con sus parámetros en el asunto.',
             'El sistema descarga el correo por POP3 y CommandParser separa el comando y sus parámetros.',
             'CommandProcessor identifica al remitente por su correo y AdministradorCommandProcessor comprueba el permiso.',
             'UsuarioService valida nombre, correo, teléfono y rol y ejecuta la operación en una transacción.',
             'Al dar de baja a un vendedor, sus tarjetas abiertas vuelven a la bolsa común.',
             'El sistema responde con un correo HTML con el resultado.'],
            'El usuario queda registrado o actualizado y puede enviar comandos desde su correo.',
            'Correo duplicado, rol inexistente, datos inválidos, intento de cambiar el propio rol o un rol sin permiso: el sistema responde con un correo de error o de acceso denegado.'),
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
            ['El actor envía LISTARBOLSA[], ASIGNARPROSPECTO["id_tarjeta","id_vendedor"] o ASIGNARAUTOMATICO[].',
             'El sistema verifica el rol y el alcance (el coordinador solo reparte los programas a su cargo).',
             'AsignacionService bloquea las tarjetas (SELECT ... FOR UPDATE) y las reparte al vendedor activo con menos carga, manteniendo juntos los intereses de un mismo prospecto.',
             'Registra la asignación en el historial de cada tarjeta.',
             'Responde al actor y envía a cada vendedor un correo con los prospectos que recibió.'],
            'Las tarjetas quedan en la cartera de un vendedor.',
            'Bolsa vacía, sin vendedores activos, tarjeta ya asignada o un vendedor que intenta asignarse: se responde con el motivo o como acceso denegado.'),
    'CU4': ('Gestión de Embudo de Ventas en Tablero Kanban', 'Ver el tablero de seis etapas y mover las tarjetas entre Nuevo, En Contacto, Seguimiento y Promesa de Pago.',
            'Administrador, Coordinador o Vendedor', 'La tarjeta está en la cartera del vendedor o en el alcance del coordinador.',
            ['El actor envía VERTABLERO[] o MOVERTARJETA["id","etapa"].',
             'El sistema verifica el rol y que la tarjeta esté a su cargo.',
             'EmbudoService bloquea la tarjeta, cambia la etapa y registra el movimiento.',
             'Responde con el tablero en carriles (uno por etapa) o con la tarjeta movida.'],
            'La tarjeta queda en la nueva etapa con su movimiento en el historial.',
            'Mover a Perdido o Convertido por esta vía, tarjeta ajena o ya convertida: se indica el comando correcto o el motivo.'),
    'CU5': ('Gestión de Pérdida de Prospectos y Clasificación de Motivos', 'Marcar una tarjeta como perdida con un motivo obligatorio y administrar los motivos clasificados por categoría.',
            'Administrador, Coordinador o Vendedor', 'Existe el motivo y la tarjeta no está convertida.',
            ['El actor envía MARCARPERDIDO["id","id_motivo","detalle"] o gestiona los motivos con LISTARMOTIVOS, INSERTARMOTIVO, MODIFICARMOTIVO o ELIMINARMOTIVO.',
             'El sistema verifica el rol y el alcance sobre la tarjeta.',
             'PerdidaService registra el motivo, cambia la etapa a Perdido y anota el movimiento con la categoría.',
             'Responde con la tarjeta y cómo reactivarla.'],
            'La pérdida queda clasificada y cuenta en el reporte de pérdidas.',
            'Tarjeta ya cerrada, motivo inexistente o motivo en uso que se intenta eliminar: se responde con el error.'),
    'CU6': ('Gestión de Validar Datos y Procesar Conversión a Matrícula', 'Validar la ficha del prospecto, generar la orden de pago, revisar el comprobante, confirmar la inscripción comercial y preparar su exportación al SIS académico.',
            'Administrador, Coordinador o Vendedor', 'La tarjeta tiene vendedor asignado y su módulo está abierto en el catálogo.',
            ['El vendedor envía VALIDARCONVERSION["id_tarjeta"] y, con la ficha completa, GENERARORDEN["id_tarjeta"] (con otros módulos del programa y un descuento opcionales); consulta LISTARORDENES, VERORDEN y LISTARDESCUENTOS.',
             'PagoService calcula subtotal, descuentos y total en una transacción y devuelve el ID de pago con una referencia demostrativa (no se cobra en un banco).',
             'El vendedor adjunta el comprobante real (PNG, JPEG, GIF, WebP o PDF de hasta 5 MB) con REGISTRARCOMPROBANTE["id_pago","observación"]; el tipo se comprueba por su contenido.',
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
             'Todo correo recibido queda en la bitácora del sistema con su resultado (Éxito, Error, Denegado o Ignorado).'],
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
d.h('Detallar casos de uso', 3)
for cu, (nombre, desc, iniciador, pre, flujo, post, exc) in CU.items():
    d.h(f'{cu}: {nombre}', 3)
    fig(f'uc-{cu.lower()}.png', f'Detalle {cu}: {nombre}', ancho=13)
    # Qué puede hacer cada actor en el CU, tomado de la matriz de acceso del código (no solo la lista de actores)
    permisos = '\n'.join(f'{a}: ' + ', '.join(c.split('[')[0] for c in comandos(cu, 'Público' if a == 'Visitante' else a))
                         for a in actores(cu))
    d.tabla(f'Detalle de {cu}: {nombre}', ['Elemento', 'Descripción'], [
        ('Descripción', desc), ('Actores y comandos', permisos), ('Actor iniciador', iniciador), ('Precondición', pre),
        ('Flujo principal', '\n'.join(f'{i}. {t}' for i, t in enumerate(flujo, 1))), ('Postcondición', post), ('Excepción', exc),
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
d.h('Diseño físico: despliegue', 3)
d.p('El usuario escribe desde su cuenta de correo. El mensaje llega por SMTP al servidor de la facultad (registro MX de '
    'tecnoweb.org.bo: mail.tecnoweb.org.bo, 139.99.68.169), donde Sendmail lo deja en el buzón grupo05sa. El lector corre en la '
    'PC del grupo (Windows 11, Eclipse Temurin JDK 21.0.12): p1/ejecutar.ps1 arranca p1/target/crm-mail.jar con la configuración '
    '.secrets/crm-mail.properties, que no se publica porque lleva las contraseñas. El programa lee el buzón por POP3 (Dovecot, puerto '
    '110), consulta y actualiza la base db_grupo05sa en PostgreSQL 18.6 (puerto 5432) y responde por smtp.gmail.com (puerto 587 con '
    'STARTTLS), porque el SMTP de la facultad rechaza el reenvío a dominios externos con "550 5.7.1 Relaying denied". Si después '
    'el servidor del destinatario rechaza una respuesta, el aviso de rebote vuelve a la cuenta de Gmail del sistema: el lector lo '
    'lee cada minuto por imap.gmail.com (puerto 993, TLS) y lo registra. Cada ciclo y '
    'cada falla quedan en p1/logs/crm-mail.0.log. PostgreSQL acepta la conexión según la IP de origen (pg_hba.conf): si el equipo '
    'cambia de red, el diagnóstico muestra la IP rechazada.')
fig('despliegue.png', 'Diagrama de despliegue', horizontal=True)
d.p('En el diagrama, cada servidor es un nodo «device» con sus entornos de ejecución («executionEnvironment»: sistema operativo, '
    'máquina virtual y servicios). Los artefactos llevan su ruta real y su estereotipo («executable» para el .jar, «file» para el '
    'script de arranque, la configuración, el log y el buzón) y contienen los componentes que despliegan: las cuatro capas en el .jar '
    'y las tablas y la bitácora en la base de datos. Las dependencias entre artefactos muestran quién arranca, lee o escribe a quién, y cada '
    'ruta de comunicación lleva su protocolo y puerto. Google, que no administra el grupo, se modela como «External System».')
d.h('Diseño lógico: capas', 3)
d.tabla('Capas del sistema', ['Capa', 'Paquete Java', 'Clases'], [
    ('Presentación', 'bo.uagrm.crm.presentacion y bo.uagrm.crm', 'App (bucle del servicio: lee, procesa y responde), Diagnostico (causa probable de cada falla y revisión de conexiones), Pop3Client (lector POP3 con sockets), SmtpSender (emisor SMTP), Rebotes (lector IMAP de los avisos de rebote), Correo (MIME)'),
    ('Aplicación', 'bo.uagrm.crm.aplicacion', 'CommandParser, Command, Comando, CommandProcessor, RolCommandProcessor y sus cuatro subclases por rol, ResponseFormatter, Respuesta'),
    ('Dominio', 'bo.uagrm.crm.dominio.<cu>', 'Un paquete por caso de uso con su servicio y sus entidades: usuarios, captacion, asignacion, embudo, perdidas, conversion, pagos, seguimiento, reportes'),
    ('Persistencia', 'bo.uagrm.crm.persistencia', 'DatabaseService: conexión JDBC, consultas parametrizadas y transacciones'),
], anchos=[2.8, 4.6, 8.6])
fig('capas.png', 'Diagrama de capas', horizontal=True)
d.h('Diagramas de secuencia', 3)
d.p('Todo comando se recibe y se responde igual. Recibir comando muestra el ciclo de App con un fragmento loop cada intervalo '
    '(10 segundos): comprueba el modelo de la base de datos, consulta el buzón y, en un loop por mensaje, descarga cada correo '
    '(alt: solo las cabeceras con top() si pasa de 8 MB, o completo con retr()) y lo borra; después procesa cada mensaje leído. '
    'Registrar y responder muestra la bitácora, el envío por SMTP y, en un fragmento opt, el registro del envío fallido.')
d.p('Cada caso de uso tiene un solo diagrama. Lo común se dibuja una vez: el comando llega a procesador, que llama a procesar() '
    'del procesador del rol, y este consulta comandos(). Un fragmento alt separa el comando no permitido al rol (denegado()) del '
    'resto; dentro de [else], ejecutar() lleva a un segundo alt con un operando por comando del caso de uso, cada uno con el método '
    'real del servicio y la operación de DatabaseService con la que llega a la base de datos (enTransaccion(), lista(), filas() o '
    'uno()). Al salir del alt, procesador registra la bitácora y el actor recibe la respuesta HTML.')
d.p('Los correos (el comando que llega al buzón y la respuesta HTML) son mensajes asíncronos; las demás llamadas son síncronas, '
    'llevan los parámetros del código y su retorno con el tipo que devuelven. Las líneas de vida usan los estereotipos de robustez '
    '(boundary para los clientes POP3 y SMTP, control para la lógica y entity para DatabaseService) y se nombran con la variable o '
    'el campo que las referencia en el código (pop, smtp, procesador, parser, usuarios, db). Cuando la lista de parámetros no cabe '
    'en la etiqueta se muestran los primeros y puntos suspensivos; la lista completa de cada comando está en el Anexo A.')
fig('sd-recibir.png', 'Diagrama de secuencia común: Recibir comando')
fig('sd-responder.png', 'Diagrama de secuencia común: Registrar y responder')
fig('sd-rebotes.png', 'Diagrama de secuencia común: Leer rebotes')
for cu, v in CU.items():
    fig(f'sd-{cu.lower()}.png', f'Diagrama de secuencia {cu}: {v[0]}')
for i, (cu, v) in enumerate(CU.items()):
    fig(f'cls-{cu.lower()}.png', f'Diseño procedimental {cu}: {v[0]}', horizontal=True,
        titulos=[('Diseño procedimental', 3)] if i == 0 else ())
fig('clases-datos.png', 'Diagrama de clases del modelo de datos', horizontal=True, titulos=[('Diseño de datos: clases y mapeo', 3)])


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


d.p(f'El modelo tiene {len(TABLAS)} tablas en el esquema public de db_grupo05sa, el mismo que usará el Proyecto 2 web, unidas por '
    f'{len(FKS)} claves foráneas: ninguna tabla queda sin relación. La tabla migrations, propia de Laravel, registra las '
    'migraciones del Proyecto 2 y no forma parte del modelo del negocio. En el diagrama, las claves foráneas no se repiten como '
    'atributo: cada una es una asociación con su multiplicidad, rotulada con el papel cuando no es obvio (vendedor, coordinador, '
    'emisor, autor, responsable, destinatario, módulo, actualizado por).')
d.p('Cada tabla usa su clave de diseño, sin una segunda clave artificial: catalogo_cache se identifica por el código del módulo '
    '(codigo), roles por su nombre, configuraciones por su clave y programas_responsables por el código del programa; la tabla '
    'intermedia orden_pago_descuentos tiene como clave las dos claves foráneas (orden y descuento) y guarda el porcentaje y el '
    'monto aplicados; las demás usan un identificador bigint. Las reglas del negocio son restricciones CHECK y UNIQUE (script '
    'completo en el Anexo B).')
for nombre, cols in tablas_sql():
    d.tabla(f'Mapeo de la tabla {nombre}', ['Columna', 'Tipo', 'Clave', 'Nulo'], cols, anchos=[4.4, 4.0, 5.2, 2.4], tam=9)

fig('componentes.png', 'Diagrama de componentes', horizontal=True,
    titulos=[('Flujo de Trabajo: Implementación', 2), ('Implementación de la arquitectura', 3)])
d.p('El subsistema agrupa las cuatro capas como componentes contenedores, y el artefacto crm-mail.jar lo manifiesta («manifest»). '
    'Las dependencias internas llevan la operación que se invoca; Diagnostico revisa el lector, el emisor y el lector de rebotes, y UsuarioService usa '
    'jBCrypt para guardar la clave web del usuario como hash. Hacia afuera, cada componente expone una interfaz requerida '
    '(semicírculo) que se une por un conector de ensamblaje con la interfaz provista (círculo) de quien la implementa: el lector '
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
    ('Eclipse Temurin JDK', '21.0.12', 'Compilación, pruebas y ejecución del lector'),
    ('Windows 11 Home', '10.0.26300', 'Sistema operativo del equipo que ejecuta el lector'),
    ('Apache Maven', '3.9.16', 'Construcción y dependencias; jar único con maven-shade-plugin 3.6.0'),
    ('Angus Mail / Jakarta Mail API', '2.0.5 / 2.1.5', 'Análisis MIME, envío SMTP y lectura IMAP de rebotes'),
    ('PostgreSQL JDBC', '42.7.13', 'Conexión a la base de datos'),
    ('jBCrypt', '0.4', 'Hash de la clave web al registrar un usuario'),
    ('PostgreSQL', '18.6', 'Base de datos de la facultad'),
    ('JUnit Jupiter', '5.10.5', 'Pruebas unitarias'),
    ('Enterprise Architect', '15.0.0.1514', 'Herramienta CASE (modelado UML)'),
    ('Visual Studio Code', '1.139.1', 'Editor'),
    ('Git / GitHub', '2.53.0', 'Control de versiones'),
], anchos=[5.2, 3.2, 7.6])

# ---------------------------------------------------------------- VIII
d.h('VIII. Resultados', 1)
junit = []
for x in sorted((P1 / 'target' / 'surefire-reports').glob('TEST-*.xml')):
    r = ET.parse(x).getroot()
    if int(r.get('skipped')) == int(r.get('tests')): continue   # integración: va en la tabla siguiente
    junit.append((r.get('name').split('.')[-1], r.get('tests'), str(int(r.get('tests')) - int(r.get('failures')) - int(r.get('errors')))))
if junit:
    d.tabla('Pruebas unitarias (JUnit)', ['Clase de prueba', 'Casos', 'Aprobados'], junit, anchos=[8, 3, 3])
else:
    rojo('ejecutar mvn package para generar target/surefire-reports')
integ = json.loads((DOC / 'pruebas' / 'integracion-servidor.json').read_text(encoding='utf-8'))
d.tabla('Pruebas de integración contra el PostgreSQL de la facultad', ['Clase de prueba', 'Casos', 'Aprobados'],
        [(x['suite'], str(x['casos']), str(x['aprobados'])) for x in integ['suites']], anchos=[8, 3, 3],
        nota_txt=f"Ejecutadas el 17/11/2026 con {integ['comando']} sobre un esquema temporal (crm_qa_correo) creado con "
                 'sql/esquema.sql y sql/referencias.sql y eliminado al terminar: roles, alcance, órdenes, comprobantes, pagos, '
                 'conversión, exportación y concurrencia.')
d.p('La prueba de robustez (RobustezCorreoTest) envió cada comando, con cada rol y con un visitante, con entradas malas: '
    'parámetros vacíos o solo con espacios, letras donde va un número, negativos, números enormes, inyección SQL, textos de 5000 '
    'caracteres, emojis, fechas imposibles, parámetros de más, corchetes y comillas sin cerrar, y siete archivos CSV dañados. '
    'En 4515 casos ninguna respuesta fue un error interno ni un error SQL sin traducir, nada vacío o mal formado se aceptó y '
    'las tablas siguieron en pie. Se ejecutó el 17/11/2026 sobre un PostgreSQL 18 desechable creado con sql/esquema.sql y '
    'sql/referencias.sql (p1/PRUEBAS.md).')
d.h('Funcionamiento con correos reales', 2)
d.p('El 17 de noviembre de 2026 el lector se ejecutó en la PC del grupo con el servicio del VPS ya detenido. Al arrancar revisó '
    'las tres conexiones; luego recibió dos comandos enviados al puerto 25 de mail.tecnoweb.org.bo con el remitente de un vendedor '
    'registrado: HELP[] (Éxito) y VERBITACORA["5"], reservado al administrador (Denegado). Ambos quedaron en la tabla log_comandos '
    'con el rol del remitente. Del mismo modo se comprobó un remitente institucional (@ficct.uagrm.edu.bo) con HELP[] y con '
    'Re: LISTAROFERTA[]: ambas respuestas llegaron a esa cuenta. Una respuesta dirigida a una dirección inexistente de ese '
    'dominio quedó en el log como REBOTE, con el código 550 5.1.1 que devolvió el servidor del destinatario.')
CAP = DOC / 'capturas'
d.figura(CAP / 'servicio-local.png', 'Arranque del lector, revisión de conexiones y dos comandos atendidos',
         'Salida en consola del lector en la PC del grupo (hora de Bolivia), 17 de noviembre de 2026; se ocultó el último octeto de la IP pública.', 16)
for nombre, titulo in [('gmail-help.png', 'Respuesta a HELP[]: comandos permitidos al rol del remitente'),
                       ('gmail-denegado.png', 'Respuesta a VERBITACORA["5"] enviado por un vendedor: comando denegado por rol')]:
    if (CAP / nombre).exists():
        d.figura(CAP / nombre, titulo, 'Correo de respuesta recibido en la cuenta Gmail del sistema (alias +vend1 del vendedor de '
                 'prueba), leído por IMAP y mostrado en Chrome con su HTML original, 17 de noviembre de 2026.', 12)
    else:
        d.rotulo('Figura', titulo); rojo(f'captura de Gmail capturas/{nombre}')
d.h('Diagnóstico de fallas', 2)
d.p('El docente puede deshabilitar un servicio o cambiar una configuración durante la defensa; el sistema debe decir qué pasó. '
    'Cada ciclo deja una línea con la latencia de la base de datos y del POP3. Cuando una etapa falla, el log nombra la etapa, el '
    'destino (host y puerto) y la causa probable; mientras la falla sigue, una línea corta por ciclo, y al volver, RECUPERADO con '
    'cuántos ciclos y cuánto tiempo duró. Si la base de datos no está disponible, el ciclo no retira mensajes del buzón: se leen '
    'en el siguiente. También queda en el log un cambio de DNS de los servidores de la facultad.')
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
        f'La arquitectura en cuatro capas, el modelo relacional de {len(TABLAS)} tablas compartido con el Proyecto 2, todas unidas por '
        'claves foráneas y con su clave de diseño, y el despliegue sobre los servidores reales de la facultad, Gmail y la PC del grupo '
        'quedaron diseñados con un único valor verificado por cada nodo y puerto.',
        'Se implementó el lector POP3 con sockets propios, el emisor SMTP, el intérprete de comandos y un servicio por caso de uso; '
        'toda consulta usa parámetros enlazados y toda operación compuesta, una transacción con bloqueo de fila.',
        'El lector se ejecuta en la PC del grupo, respondió correos reales y diagnostica las fallas de DNS, red, correo y base de '
        'datos con su causa; las pruebas automáticas comprueban el intérprete, la matriz de acceso, el diagnóstico y, contra el '
        'PostgreSQL de la facultad, los ocho casos de uso.']
d.lista(CONC, numerada=True)

# ---------------------------------------------------------------- X
d.h('X. Recomendaciones', 1)
d.lista([
    'Verificar la autenticidad del remitente. Hoy el usuario se identifica por la cabecera From, y el servidor de la facultad '
    'acepta en el puerto 25 correo local con un From arbitrario. Se recomienda que el servidor de correo de la facultad '
    'compruebe SPF (Kitterman, 2014) y DKIM (Crocker, Hansen y Kucherawy, 2011) y añada el resultado en la cabecera '
    'Authentication-Results, para que el sistema rechace los correos que no la superen.',
    'Firmar o cifrar con OpenPGP (Callas, Donnerhacke, Finney, Shaw y Thayer, 2007) los comandos sensibles, como '
    'INSERTARUSUARIO, ELIMINARUSUARIO o CONVERTIRPROSPECTO, y aceptarlos solo con una firma válida de la clave registrada del usuario.',
    'Usar POP3 sobre TLS (puerto 995) cuando el servidor de la facultad lo habilite; hoy la contraseña del buzón viaja sin cifrar por el puerto 110.',
    'Sustituir la consulta periódica del buzón por IMAP IDLE para recibir los correos al instante.',
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
d.h('Anexo C. Ejecución y configuración', 2)
d.bloque((P1 / 'ejecutar.ps1').read_text(encoding='utf-8'), 'ejecutar.ps1')
d.bloque((P1 / 'crm-mail.properties.example').read_text(encoding='utf-8'), 'crm-mail.properties.example (las contraseñas reales no se publican)')

d.guardar(DOC / 'P1-INF513-GRUPO05SA.docx')
print(f'pendientes: {len(PENDIENTES)}')
for p in PENDIENTES: print('  -', p)
