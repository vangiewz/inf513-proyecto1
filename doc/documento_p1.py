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
SQL = (P1 / 'sql' / 'crm_p1.sql').read_text(encoding='utf-8')
MATRIZ = list(csv.reader((DOC / 'matriz-acceso.tsv').read_text(encoding='utf-8').splitlines(), delimiter='\t'))[1:]
PENDIENTES = []

# Datos que el grupo aún debe entregar: None = pendiente (se imprime en rojo).
EMPRESA = None            # nombre de la unidad o empresa real (sección III)
CORREO_DOCENTE = None     # correo con el que el docente probará el sistema (se registra como administrador)
GITHUB = 'https://github.com/vangiewz/inf513-proyecto1'
FECHA = None              # "14 de octubre de 2026" al entregar

d = Informe()


def rojo(texto):
    PENDIENTES.append(texto)
    par = d.p(f'[PENDIENTE: {texto}]', sangria=False)
    for r in par.runs:
        r.font.color.rgb = RGBColor(0xC0, 0x00, 0x00); r.bold = True
    return par


def fig(archivo, titulo, ancho=16.0, nota=None, horizontal=False):
    """Figura de EA. horizontal=True: va sola en una página apaisada (diagramas anchos, legibles al imprimir)."""
    ruta = DIA / archivo
    if horizontal: d.orientacion(True); ancho = 22.9
    if ruta.exists():
        import pymupdf
        px = pymupdf.Pixmap(str(ruta))
        alto_max = 14.0 if horizontal else 20.5   # cm disponibles bajo el rótulo
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
    ('Cuenta de prueba', f'Administrador: {dato(CORREO_DOCENTE, "correo del docente")}', False),
    ('Repositorio', dato(GITHUB, 'URL de GitHub inf513-proyecto1'), False),
    ('Base de datos', 'PostgreSQL 18.6, db.tecnoweb.org.bo:5432, db_grupo05sa, esquema crm', False),
    ('Servicio', 'crm-mail.service en el VPS Fedora 44 (164.68.96.50); revisa el buzón cada 10 segundos', False),
], 'El remitente se identifica por su correo: el correo de la cuenta de prueba se registra como administrador antes de la defensa. '
   'Un correo no registrado actúa como visitante (consulta la oferta y registra su interés).')
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
    'ocho casos de uso aprobados con 38 comandos y queda desplegado en un servidor Fedora Linux.')

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
         'La bolsa común se reparte por turno rotativo (Round-Robin) y cada vendedor recibe un aviso por correo.',
         'Cada interés es una tarjeta de un tablero Kanban de seis etapas; perderla exige un motivo clasificado.',
         'La conversión a matrícula valida antes los datos completos del prospecto.',
         'Cada tarjeta guarda su historial cronológico y los recordatorios llegan por correo a su hora.',
         'Coordinación y administración obtienen reportes del embudo, de vendedores, de pérdidas y por oferta.'])
d.h('Descripción de la Empresa', 2)
if EMPRESA:
    d.p(EMPRESA)
else:
    rojo('nombre, misión, visión, historia, imagen corporativa, web y redes de la unidad o empresa real')
d.h('Estructura Organizacional', 2)
rojo('organigrama de la unidad (niveles administrativo y operativo)')
d.h('Ubicación e Infraestructura', 2)
rojo('ubicación geográfica (mapa) e infraestructura física')

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
       'Desplegar el sistema como servicio en Fedora Linux y validarlo con pruebas automáticas y con correos reales por cada rol.']
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
    ('Transición', 'Despliegue como servicio systemd y pruebas con correos reales por rol', 'Fedora Linux 44, OpenJDK 25.0.4', '5'),
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
    ('Coordinador', 'Encargado de los programas a su cargo (RF1.3).', 'Registra e importa prospectos, reparte la bolsa común, clasifica motivos de pérdida y consulta reportes de sus ofertas.'),
    ('Vendedor', 'Asesor comercial que trabaja desde el celular (RNF1).', 'Atiende su cartera y la bolsa común (RF1.2): toma prospectos, mueve tarjetas, registra interacciones y recordatorios y convierte a matrícula.'),
    ('Visitante', 'Persona cuyo correo no está registrado.', 'Consulta la oferta abierta y registra su propio interés.'),
], anchos=[3.0, 5.0, 8.0])
d.tabla('Casos de uso aprobados', ['ID', 'Caso de uso'], [
    ('CU1', 'Gestión de Usuarios (Propietario, Clientes)'), ('CU2', 'Gestión de Captar, Consumir Oferta'),
    ('CU3', 'Gestión de Asignar Prospectos (Leads)'), ('CU4', 'Gestión de Embudo de Ventas en Tablero Kanban'),
    ('CU5', 'Gestión de Pérdida de Prospectos y Clasificación de Motivos'), ('CU6', 'Gestión de Validar Datos y Procesar Conversión a Matrícula'),
    ('CU7', 'Gestión de Bitácora, Interacciones y Recordatorios'), ('CU8', 'Reportes y Estadísticas')],
    anchos=[1.6, 14.4], nota_txt='Nota. Títulos tal como los aprobó el docente el 05/10/2026. En CU1 los actores son los roles de RF1.1: administrador, coordinador y vendedor.')
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
            ['El actor envía LISTAROFERTA[], REGISTRARPROSPECTO, IMPORTARPROSPECTOS (con un .csv adjunto) o, si es visitante, REGISTRARINTERES.',
             'El sistema lee el correo, interpreta el comando y verifica el rol del remitente.',
             'ProspectoService valida CI, teléfono, correo, origen y que la oferta esté abierta (y a cargo del coordinador, si es coordinador).',
             'Crea el prospecto si su CI no existe y una tarjeta en la etapa Nuevo: en la bolsa común o en la cartera del vendedor que lo registró.',
             'El sistema responde con la tarjeta creada o con el resultado de cada fila del CSV.'],
            'El prospecto y su interés quedan registrados con su movimiento inicial en el historial.',
            'Oferta cerrada o inexistente, CI o teléfono inválidos, interés duplicado o fila de CSV incompleta: se informa el error de esa fila o del comando.'),
    'CU3': ('Gestión de Asignar Prospectos', 'Repartir los prospectos de la bolsa común entre los vendedores: manual, automático por turno rotativo o tomado por el vendedor.',
            'Administrador, Coordinador o Vendedor', 'Existen tarjetas abiertas sin vendedor y vendedores activos.',
            ['El actor envía LISTARBOLSA[], ASIGNARPROSPECTO, ASIGNARAUTOMATICO[] o, si es vendedor, TOMARPROSPECTO.',
             'El sistema verifica el rol y el alcance (el coordinador solo reparte las ofertas a su cargo).',
             'AsignacionService bloquea las tarjetas (SELECT ... FOR UPDATE) y las asigna continuando el turno después del último vendedor que recibió una.',
             'Registra la asignación en el historial de cada tarjeta.',
             'Responde al actor y envía a cada vendedor un correo con los prospectos que recibió.'],
            'Las tarjetas quedan en la cartera de un vendedor.',
            'Bolsa vacía, sin vendedores activos o tarjeta ya tomada por otro vendedor: se responde con el motivo.'),
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
    'CU6': ('Gestión de Validar Datos y Procesar Conversión a Matrícula', 'Verificar que el prospecto tenga los datos completos y convertir la tarjeta en matrícula para el SIS académico.',
            'Administrador, Coordinador o Vendedor', 'La tarjeta está en Promesa de Pago, con vendedor asignado y oferta abierta.',
            ['El actor envía VALIDARCONVERSION["id"] para revisar los requisitos o CONVERTIRPROSPECTO["id"].',
             'ConversionService revisa siete requisitos: CI, nombre y apellido, teléfono, correo, oferta abierta, vendedor asignado y etapa Promesa de Pago.',
             'Si todos se cumplen, en una transacción cambia la etapa a Convertido y registra la matrícula con el precio de la oferta y estado Pendiente SIS.',
             'Responde con la lista de requisitos o con la matrícula creada.'],
            'La matrícula queda lista para que el SIS del Grupo 06 SA la matricule.',
            'Falta algún requisito: no se convierte y se indica cuál falta.'),
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
    d.tabla(f'Detalle de {cu}: {nombre}', ['Elemento', 'Descripción'], [
        ('Descripción', desc), ('Actores', ', '.join(actores(cu))), ('Actor iniciador', iniciador), ('Precondición', pre),
        ('Flujo principal', '\n'.join(f'{i}. {t}' for i, t in enumerate(flujo, 1))), ('Postcondición', post), ('Excepción', exc),
        ('Comandos', ', '.join(c.split('[')[0] for c in comandos(cu))),
    ], anchos=[3.4, 12.6])
d.h('Estructurar el modelo de casos de uso', 3)
fig('uc-modelo.png', 'Modelo de casos de uso')

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
for cu, v in CU.items():
    fig(f'com-{cu.lower()}.png', f'Diagrama de colaboración {cu}: {v[0]}')
d.h('Dependencia de paquetes', 3)
fig('pkg-dependencias.png', 'Dependencia de paquetes', ancho=13)

d.h('Flujo de Trabajo: Diseño', 2)
d.h('Diseño físico: despliegue', 3)
d.p('El usuario escribe desde su cuenta de correo. El mensaje llega por SMTP al servidor de la facultad (registro MX de '
    'tecnoweb.org.bo: mail.tecnoweb.org.bo, 139.99.68.169), donde Sendmail lo deja en el buzón grupo05sa. El servicio crm-mail, '
    'que corre en un VPS con Fedora Linux 44, lee ese buzón por POP3 (Dovecot, puerto 110), consulta y actualiza la base '
    'db_grupo05sa en PostgreSQL 18.6 (puerto 5432) y responde por smtp.gmail.com (puerto 587 con STARTTLS), porque el SMTP de la '
    'facultad rechaza el reenvío a dominios externos con "550 5.7.1 Relaying denied".')
fig('despliegue.png', 'Diagrama de despliegue', horizontal=True)
d.h('Diseño lógico: capas', 3)
d.tabla('Capas del sistema', ['Capa', 'Paquete Java', 'Clases'], [
    ('Presentación', 'bo.uagrm.crm.presentacion', 'Pop3Client (lector POP3 con sockets), SmtpSender (emisor SMTP), Correo (MIME)'),
    ('Aplicación', 'bo.uagrm.crm.aplicacion', 'CommandParser, Command, Comando, CommandProcessor, RolCommandProcessor y sus cuatro subclases por rol, ResponseFormatter, Respuesta'),
    ('Dominio', 'bo.uagrm.crm.dominio.<cu>', 'Un paquete por caso de uso con su servicio y sus entidades: usuarios, captacion, asignacion, embudo, perdidas, conversion, seguimiento, reportes'),
    ('Persistencia', 'bo.uagrm.crm.persistencia', 'DatabaseService: conexión JDBC, consultas parametrizadas y transacciones'),
], anchos=[2.8, 4.6, 8.6])
fig('capas.png', 'Diagrama de capas', horizontal=True)
d.h('Diagramas de secuencia', 3)
for cu, v in CU.items():
    fig(f'sd-{cu.lower()}.png', f'Diagrama de secuencia {cu}: {v[0]}')
d.h('Diseño procedimental', 3)
for cu, v in CU.items():
    fig(f'cls-{cu.lower()}.png', f'Diseño procedimental {cu}: {v[0]}', horizontal=True)
d.h('Diseño de datos: clases y mapeo', 3)
fig('clases-datos.png', 'Diagrama de clases del modelo de datos', horizontal=True)


def tablas_sql():
    out = []
    for nombre, cuerpo in re.findall(r'CREATE TABLE (\w+) \((.*?)\n\);', SQL, re.S):
        cols = []
        for linea in cuerpo.splitlines():
            m = re.match(r'\s+(\w+)\s+(serial|int|varchar\(\d+\)|numeric\(\d+,\d+\)|timestamptz|boolean)\s*(.*)', linea)
            if not m or m.group(1) in ('CHECK', 'UNIQUE'): continue
            c, tipo, resto = m.groups()
            fk = re.search(r'REFERENCES (\w+)', resto)
            cols.append((c, tipo, 'PK' if 'PRIMARY KEY' in resto else ('FK → ' + fk.group(1) if fk else ''),
                         'No' if ('NOT NULL' in resto or 'PRIMARY KEY' in resto) else 'Sí'))
        out.append((nombre, cols))
    return out


d.p('Las clases persistentes se transforman en tablas del esquema crm. Cada tabla tiene una clave primaria serial; las '
    'asociaciones se implementan como claves foráneas y las reglas del negocio como restricciones CHECK y UNIQUE '
    '(script completo en el Anexo B).')
for nombre, cols in tablas_sql():
    d.tabla(f'Mapeo de la tabla {nombre}', ['Columna', 'Tipo', 'Clave', 'Nulo'], cols, anchos=[4.4, 4.0, 5.2, 2.4], tam=9)

d.h('Flujo de Trabajo: Implementación', 2)
d.h('Implementación de la arquitectura', 3)
fig('componentes.png', 'Diagrama de componentes', horizontal=True)
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
    ('Eclipse Temurin JDK', '21.0.12', 'Compilación y pruebas en desarrollo'),
    ('OpenJDK (Fedora)', '25.0.4', 'Ejecución en el servidor'),
    ('Apache Maven', '3.9.16', 'Construcción y dependencias; jar único con maven-shade-plugin 3.6.0'),
    ('Angus Mail / Jakarta Mail API', '2.0.5 / 2.1.5', 'Análisis MIME y envío SMTP'),
    ('PostgreSQL JDBC', '42.7.13', 'Conexión a la base de datos'),
    ('PostgreSQL', '18.6', 'Base de datos de la facultad'),
    ('JUnit Jupiter', '5.10.5', 'Pruebas unitarias'),
    ('Fedora Linux', '44', 'Sistema operativo del servidor del servicio'),
    ('Enterprise Architect', '15.0.0.1514', 'Herramienta CASE (modelado UML)'),
    ('Visual Studio Code', '1.139.1', 'Editor'),
    ('Git / GitHub', '2.53.0', 'Control de versiones'),
], anchos=[5.2, 3.2, 7.6])

# ---------------------------------------------------------------- VIII
d.h('VIII. Resultados', 1)
junit = []
for x in sorted((P1 / 'target' / 'surefire-reports').glob('TEST-*.xml')):
    r = ET.parse(x).getroot()
    junit.append((r.get('name').split('.')[-1], r.get('tests'), str(int(r.get('tests')) - int(r.get('failures')) - int(r.get('errors')))))
if junit:
    d.tabla('Pruebas unitarias (JUnit)', ['Clase de prueba', 'Casos', 'Aprobados'], junit, anchos=[8, 3, 3])
else:
    rojo('ejecutar mvn package para generar target/surefire-reports')
esc = DOC / 'pruebas' / 'escenario.json'
if esc.exists():
    datos = json.loads(esc.read_text(encoding='utf-8'))
    d.tabla('Pruebas funcionales por rol (un correo por fila)', ['Rol', 'Comando', 'Esperado', 'Obtenido'],
            [(x['rol'], x['comando'], x['esperado'], x['obtenido']) for x in datos], anchos=[2.6, 8.6, 2.4, 2.4], tam=8)
else:
    rojo('resultados del escenario funcional (p1/doc/pruebas/escenario.json)')
rojo('capturas de las respuestas en Gmail (escritorio y celular) y del servicio activo en el VPS (systemctl status crm-mail)')

# ---------------------------------------------------------------- IX
d.h('IX. Conclusiones', 1)
CONC = ['Se identificaron cuatro actores y se detallaron los ocho casos de uso aprobados; la matriz de acceso de 38 comandos '
        'por rol se definió en el código y de ahí se generó el anexo de comandos, de modo que el documento y el sistema coinciden.',
        'El análisis agrupó los casos de uso en cuatro paquetes y mostró, en los diagramas de colaboración, que todo comando pasa '
        'por el mismo control central antes de llegar a la entidad que lo resuelve.',
        'La arquitectura en cuatro capas, el modelo relacional de diez tablas con sus restricciones y el despliegue sobre los '
        'servidores reales de la facultad, Gmail y el VPS quedaron diseñados con un único valor verificado por cada nodo y puerto.',
        'Se implementó el lector POP3 con sockets propios, el emisor SMTP, el intérprete de comandos y un servicio por caso de uso; '
        'toda consulta usa parámetros enlazados y toda operación compuesta, una transacción con bloqueo de fila.',
        'El servicio quedó desplegado en Fedora Linux 44 y respondió correos reales de cada rol; las pruebas automáticas comprueban '
        'el intérprete, la lectura de correos mal formados y que un comando denegado no llega a la base de datos.']
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
    'Reemplazar la réplica local de la oferta por la API del SIS académico del Grupo 06 SA y enviarle las matrículas en estado Pendiente SIS.',
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
        nota_txt='Nota. Generada con java -jar crm-mail.jar --anexo a partir de las clases AdministradorCommandProcessor, '
                 'CoordinadorCommandProcessor, VendedorCommandProcessor y PublicoCommandProcessor. Los parámetros entre paréntesis son opcionales.')
d.tabla('Ejemplo de uso de cada comando', ['Comando', 'Ejemplo de asunto'],
        [(f[1].split('[')[0], f[6]) for f in MATRIZ], anchos=[4.6, 11.4], tam=8)
d.h('Anexo B. Script de la base de datos', 2)
d.bloque(SQL, 'sql/crm_p1.sql')
d.h('Anexo C. Servicio systemd y configuración', 2)
d.bloque((P1 / 'deploy' / 'crm-mail.service').read_text(encoding='utf-8'), 'deploy/crm-mail.service')
d.bloque((P1 / 'crm-mail.properties.example').read_text(encoding='utf-8'), 'crm-mail.properties.example (las contraseñas reales no se publican)')

d.guardar(DOC / 'P1-INF513-GRUPO05SA.docx')
print(f'pendientes: {len(PENDIENTES)}')
for p in PENDIENTES: print('  -', p)
