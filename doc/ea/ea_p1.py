"""Construye en Enterprise Architect (COM, EA.Repository) los 47 diagramas UML del Proyecto 1 y los exporta a PNG.
Uso: python p1/doc/ea/ea_p1.py  ->  p1/doc/ea/crm-p1.eapx y p1/doc/diagramas/*.png
Todo nombre de clase, método, comando, host y puerto sale del código de p1/ o de lo verificado en los servidores
(lista y criterio en P1_CORREO.md § Diagramas)."""
import pathlib, re, shutil, sys, uuid
import win32com.client

AQUI = pathlib.Path(__file__).resolve().parent
P1 = AQUI.parents[1]
SALIDA = AQUI.parent / 'diagramas'
EAPX = AQUI / 'crm-p1.eapx'
BASE = pathlib.Path(r'C:/Program Files (x86)/Sparx Systems/EA Trial/EABase.eapx')
SQL = (P1 / 'sql' / 'crm_p1.sql').read_text(encoding='utf-8')

NEGRO, ROJO, VERDE, AZUL = 0x000000, 0x0000C0, 0x008000, 0xC00000   # COLORREF (0xBBGGRR)
COLORES = [NEGRO, ROJO, VERDE, AZUL]


class EA:
    def __init__(self):
        shutil.copy(BASE, EAPX)
        self.r = win32com.client.Dispatch('EA.Repository')
        if not self.r.OpenFile(str(EAPX)): raise SystemExit('EA no abrió ' + str(EAPX))
        self.pi = self.r.GetProjectInterface()
        self.modelo = self.r.Models.AddNew('CRM vía e-mail (P1, Grupo 05 SA)', ''); self.modelo.Update()
        self.exportados = []

    def cerrar(self):
        self.r.CloseFile(); self.r.Exit()

    def paquete(self, padre, nombre):
        p = padre.Packages.AddNew(nombre, ''); p.Update(); return p

    def elemento(self, pkg, nombre, tipo, st='', padre=None, notas=''):
        e = pkg.Elements.AddNew(nombre, tipo)
        if st: e.Stereotype = st
        if notas: e.Notes = notas
        e.Update()
        if padre is not None: e.ParentID = padre.ElementID; e.Update()
        return e

    def diagrama(self, pkg, nombre, tipo):
        d = pkg.Diagrams.AddNew(nombre, tipo); d.Update(); return d

    def poner(self, d, el, x, y, w, h, seq=None):
        o = d.DiagramObjects.AddNew(f'l={x};r={x + w};t={y};b={y + h};', '')
        o.ElementID = el.ElementID if hasattr(el, 'ElementID') else el.Element.ElementID
        if seq is not None: o.Sequence = seq
        o.Update(); return o

    def conector(self, a, b, tipo, nombre='', st='', dirigido=False, card=None):
        a = a if hasattr(a, 'Connectors') and not hasattr(a, 'Packages') else a.Element
        b = b if hasattr(b, 'ElementID') else b.Element
        c = a.Connectors.AddNew(nombre, tipo); c.SupplierID = b.ElementID
        if st: c.Stereotype = st
        if dirigido: c.Direction = 'Source -> Destination'
        c.Update()
        if card:
            c.ClientEnd.Cardinality, c.SupplierEnd.Cardinality = card
            c.ClientEnd.Update(); c.SupplierEnd.Update(); c.Update()
        return c

    def mensaje_seq(self, d, a, b, texto, n, retorno=False):
        c = a.Connectors.AddNew(texto, 'Sequence'); c.SupplierID = b.ElementID; c.DiagramID = d.DiagramID; c.SequenceNo = n; c.Update()
        if retorno: self.r.Execute(f"UPDATE t_connector SET PDATA4='1' WHERE Connector_ID={c.ConnectorID}")
        return c

    def mensaje_com(self, d, enlace, a, b, texto, numero, n, apilado, color):
        """Mensaje de comunicación colgado de su enlace (SourceIsAggregate), como los crea EA."""
        c = a.Connectors.AddNew(texto, 'Collaboration'); c.SupplierID = b.ElementID; c.DiagramID = d.DiagramID
        c.SequenceNo = n; c.Direction = 'Source -> Destination'; c.Color = color; c.Update()
        sy = 18 + 15 * apilado
        self.r.Execute(f"UPDATE t_connector SET PDATA1='Synchronous', PDATA3='Call', PDATA4='{numero}', "
                       f"PDATA5='SX=-15;SY={sy};EX=15;EY={sy};EDGE=2;', SourceIsAggregate={enlace.ConnectorID}, RouteStyle=1, "
                       f"DestIsNavigable=TRUE, StateFlags='IsReturn=false;', SourceTS='instance', DestTS='instance' "
                       f"WHERE Connector_ID={c.ConnectorID}")

    def ordenar(self, d):
        self.pi.LayoutDiagramEx(d.DiagramGUID, 0x40000000, 4, 50, 40, False)

    def exportar(self, d, archivo):
        d.Update(); self.r.ReloadDiagram(d.DiagramID)
        ruta = SALIDA / archivo
        if not self.pi.PutDiagramImageToFile(d.DiagramGUID, str(ruta), 1): raise SystemExit('no se exportó ' + archivo)
        self.exportados.append(archivo); print('  ', archivo)


def clase(ea, pkg, spec, tipo='Class'):
    """spec: 'Nombre|estereotipo|-attr: T; -attr2: T|+op(p: T, q: T): R; #op2(): R'."""
    nombre, st, attrs, ops = (spec.split('|') + ['', '', ''])[:4]
    e = ea.elemento(pkg, nombre, tipo, st)
    vis = {'+': 'Public', '-': 'Private', '#': 'Protected'}
    for i, a in enumerate(filter(None, (x.strip() for x in attrs.split(';')))):
        n, t = (a[1:].split(':') + [''])[:2]
        at = e.Attributes.AddNew(n.strip(), t.strip()); at.Visibility = vis[a[0]]; at.Pos = i; at.Update()
    for o in filter(None, (x.strip() for x in ops.split(';'))):
        m = re.match(r'([+\-#])(\w+)\((.*)\)(?::\s*(.+))?$', o)
        v, n, ps, ret = m.groups()
        mt = e.Methods.AddNew(n, (ret or 'void').strip()); mt.Visibility = vis[v]; mt.Update()
        for i, p in enumerate(filter(None, (x.strip() for x in ps.split(',')))):
            pn, pt = p.split(':')
            pa = mt.Parameters.AddNew(pn.strip(), pt.strip()); pa.Position = i; pa.Update()
        mt.Update()
    e.Update(); e.Refresh() if hasattr(e, 'Refresh') else None
    return e


# ------------------------------------------------------------------------------------------------ datos del sistema
CU = {'CU1': 'Gestión de Usuarios (Propietario, Clientes)', 'CU2': 'Gestión de Captar, Consumir Oferta',
      'CU3': 'Gestión de Asignar Prospectos (Leads)', 'CU4': 'Gestión de Embudo de Ventas en Tablero Kanban',
      'CU5': 'Gestión de Pérdida de Prospectos y Clasificación de Motivos',
      'CU6': 'Gestión de Validar Datos y Procesar Conversión a Matrícula',
      'CU7': 'Gestión de Bitácora, Interacciones y Recordatorios', 'CU8': 'Reportes y Estadísticas'}
CORTO = {'CU1': 'Gestión de Usuarios', 'CU2': 'Gestión de Captar, Consumir Oferta', 'CU3': 'Gestión de Asignar Prospectos',
         'CU4': 'Gestión de Embudo de Ventas en Tablero Kanban', 'CU5': 'Gestión de Pérdida de Prospectos',
         'CU6': 'Gestión de Validar Datos y Conversión a Matrícula', 'CU7': 'Gestión de Bitácora, Interacciones y Recordatorios',
         'CU8': 'Reportes y Estadísticas'}
# Matriz de acceso (doc/matriz-acceso.tsv, generada por crm-mail.jar --anexo)
MATRIZ = [l.split('\t') for l in (P1 / 'doc' / 'matriz-acceso.tsv').read_text(encoding='utf-8').splitlines()[1:]]
ROLES = ['Administrador', 'Coordinador', 'Vendedor', 'Visitante']


def actores(cu):
    return [n for n, i in zip(ROLES, range(2, 6)) if any(f[0] == cu and f[i] == 'Sí' for f in MATRIZ)]


SERVICIO = {'CU1': 'UsuarioService', 'CU2': 'ProspectoService', 'CU3': 'AsignacionService', 'CU4': 'EmbudoService',
            'CU5': 'PerdidaService', 'CU6': 'ConversionService', 'CU7': 'SeguimientoService', 'CU8': 'ReporteService'}
ENTIDAD = {'CU1': 'Usuario', 'CU2': 'Prospecto', 'CU3': 'Interes', 'CU4': 'Interes', 'CU5': 'MotivoPerdida',
           'CU6': 'Matricula', 'CU7': 'Recordatorio', 'CU8': 'Interes'}
# Operaciones mostradas en colaboración y secuencia: (actor, comando, método del servicio, método de DatabaseService)
OPS = {
    'CU1': [('Administrador', 'INSERTARUSUARIO["nombre","apellido","correo","telefono","rol"]', 'insertar()', 'enTransaccion()'),
            ('Administrador', 'MODIFICARUSUARIO["id",...,"rol","estado"]', 'modificar()', 'enTransaccion()'),
            ('Administrador', 'ELIMINARUSUARIO["id"]', 'eliminar()', 'enTransaccion()'),
            ('Coordinador', 'LISTARUSUARIOS["rol"]', 'listar()', 'lista()')],
    'CU2': [('Visitante', 'LISTAROFERTA[]', 'listarOferta()', 'lista()'),
            ('Visitante', 'REGISTRARINTERES["codigo_oferta","ci","nombre","apellido","telefono"]', 'registrar()', 'enTransaccion()'),
            ('Vendedor', 'REGISTRARPROSPECTO["ci",...,"origen","codigo_oferta"]', 'registrar()', 'enTransaccion()'),
            ('Coordinador', 'IMPORTARPROSPECTOS[] + .csv', 'importar()', 'enTransaccion()')],
    'CU3': [('Vendedor', 'LISTARBOLSA[]', 'bolsa()', 'lista()'),
            ('Vendedor', 'TOMARPROSPECTO["id_tarjeta"]', 'tomar()', 'enTransaccion()'),
            ('Coordinador', 'ASIGNARPROSPECTO["id_tarjeta","id_vendedor"]', 'asignar()', 'enTransaccion()'),
            ('Coordinador', 'ASIGNARAUTOMATICO[]', 'asignarAutomatico()', 'enTransaccion()')],
    'CU4': [('Vendedor', 'VERTABLERO[]', 'tablero()', 'lista()'),
            ('Vendedor', 'MOVERTARJETA["id_tarjeta","estado"]', 'mover()', 'enTransaccion()')],
    'CU5': [('Vendedor', 'LISTARMOTIVOS[]', 'listarMotivos()', 'lista()'),
            ('Vendedor', 'MARCARPERDIDO["id_tarjeta","id_motivo","detalle"]', 'marcarPerdido()', 'enTransaccion()'),
            ('Coordinador', 'INSERTARMOTIVO["nombre","categoria"]', 'insertarMotivo()', 'enTransaccion()'),
            ('Coordinador', 'ELIMINARMOTIVO["id"]', 'eliminarMotivo()', 'enTransaccion()')],
    'CU6': [('Vendedor', 'VALIDARCONVERSION["id_tarjeta"]', 'validar()', 'getConnection()'),
            ('Vendedor', 'CONVERTIRPROSPECTO["id_tarjeta"]', 'convertir()', 'enTransaccion()'),
            ('Coordinador', 'LISTARMATRICULAS[]', 'listarMatriculas()', 'lista()')],
    'CU7': [('Vendedor', 'REGISTRARINTERACCION["id_tarjeta","tipo","descripcion"]', 'registrarInteraccion()', 'enTransaccion()'),
            ('Vendedor', 'PROGRAMARRECORDATORIO["id_tarjeta","fecha_hora","descripcion"]', 'programarRecordatorio()', 'enTransaccion()'),
            ('Vendedor', 'VERHISTORIAL["id_tarjeta"]', 'historial()', 'filas()'),
            ('Administrador', 'VERBITACORA["cantidad"]', 'bitacora()', 'filas()')],
    'CU8': [('Vendedor', 'REPORTEEMBUDO[]', 'embudo()', 'filas()'),
            ('Coordinador', 'REPORTEVENDEDORES[]', 'vendedores()', 'filas()'),
            ('Coordinador', 'REPORTEPERDIDAS[]', 'perdidasPorCategoria()', 'filas()'),
            ('Coordinador', 'REPORTEOFERTA[]', 'oferta()', 'filas()')],
}

# Clases de diseño (firmas reales de p1/src)
CLASES = [
    'Command||-nombre: String; -tipo: Comando; -params: List<String>; -valido: boolean; -mensajeError: String; -remitente: String; -adjunto: String'
    '|+getNombre(): String; +getTipo(): Comando; +getParam(i: int): String; +cantidad(): int; +isValido(): boolean; +getMensajeError(): String; +getRemitente(): String; +getAdjunto(): String',
    'CommandParser|||+parse(asunto: String, remitente: String, adjunto: String): Command; +pareceComando(asunto: String): boolean',
    'CommandProcessor||-parser: CommandParser; -fmt: ResponseFormatter; -usuarios: UsuarioService; -seguimiento: SeguimientoService'
    '|+procesarEmail(remitente: String, asunto: String, adjunto: String): Respuesta; +bitacora(remitente: String, usuarioId: Integer, asunto: String, resultado: String, detalle: String): void',
    'RolCommandProcessor|abstract|#fmt: ResponseFormatter|+comandos(): Set<Comando>; +rol(): String; +procesar(cmd: Command, u: Usuario): Respuesta; -ejecutar(c: Command, u: Usuario): Respuesta',
    'AdministradorCommandProcessor||-COMANDOS: Set<Comando>|+comandos(): Set<Comando>; +rol(): String',
    'CoordinadorCommandProcessor||-COMANDOS: Set<Comando>|+comandos(): Set<Comando>; +rol(): String',
    'VendedorCommandProcessor||-COMANDOS: Set<Comando>|+comandos(): Set<Comando>; +rol(): String',
    'PublicoCommandProcessor||-COMANDOS: Set<Comando>|+comandos(): Set<Comando>; +rol(): String; #mensajeDenegado(cmd: Command): String',
    'ResponseFormatter|||+success(titulo: String, mensaje: String): Respuesta; +successTable(titulo: String, mensaje: String, columnas: String[], filas: List<String[]>): Respuesta; '
    '+error(comando: String, mensaje: String): Respuesta; +denegado(comando: String, mensaje: String): Respuesta; +estadoBadge(estado: String): String; +html(r: Respuesta, asuntoRecibido: String): String',
    'DatabaseService||-url: String; -usuario: String; -password: String|+getConnection(): Connection; +enTransaccion(trabajo: Trabajo): T; +lista(c: Connection, mapeo: Mapeo, sql: String, params: Object[]): List<T>; '
    '+uno(c: Connection, mapeo: Mapeo, sql: String, params: Object[]): T; +filas(c: Connection, sql: String, params: Object[]): List<String[]>; +actualizar(c: Connection, sql: String, params: Object[]): int; +insertar(c: Connection, sql: String, params: Object[]): int',
    'UsuarioService||-db: DatabaseService|+buscarPorCorreo(correo: String): Usuario; +listar(rol: String): List<Usuario>; +insertar(nombre: String, apellido: String, correo: String, telefono: String, rol: String): Usuario; '
    '+modificar(actor: Usuario, id: int, nombre: String, apellido: String, correo: String, telefono: String, rol: String, estado: String): Usuario; +eliminar(actor: Usuario, id: int): int',
    'ProspectoService||-db: DatabaseService|+listarOferta(todas: boolean): List<Oferta>; +registrar(actor: Usuario, ci: String, nombre: String, apellido: String, telefono: String, correo: String, origen: String, codigoOferta: String): Alta; '
    '+importar(actor: Usuario, csv: String): List<String[]>; +listar(u: Usuario, texto: String): List<Interes>; +modificar(u: Usuario, ci: String, nombre: String, apellido: String, telefono: String, correo: String, origen: String): Prospecto; '
    '+eliminar(ci: String): Prospecto; +asignarCoordinador(codigoPrograma: String, coordinadorId: int): int',
    'AsignacionService||-db: DatabaseService|+bolsa(u: Usuario): List<Interes>; +asignar(u: Usuario, tarjetaId: int, vendedorId: int): Asignacion; +asignarAutomatico(u: Usuario): List<Asignacion>; +tomar(u: Usuario, tarjetaId: int): Interes',
    'EmbudoService||-db: DatabaseService|+tablero(u: Usuario): List<Interes>; +mover(u: Usuario, id: int, estado: String): Interes; +tarjeta(c: Connection, u: Usuario, id: int, modificar: boolean): Interes; '
    '+registrarMovimiento(c: Connection, interesId: int, usuarioId: Integer, anterior: String, nuevo: String, detalle: String): void',
    'PerdidaService||-db: DatabaseService|+listarMotivos(): List<MotivoPerdida>; +insertarMotivo(nombre: String, categoria: String): MotivoPerdida; +modificarMotivo(id: int, nombre: String, categoria: String): MotivoPerdida; '
    '+eliminarMotivo(id: int): MotivoPerdida; +marcarPerdido(u: Usuario, tarjetaId: int, motivoId: int, detalle: String): Interes',
    'ConversionService||-db: DatabaseService|+validar(u: Usuario, tarjetaId: int): List<Requisito>; +convertir(u: Usuario, tarjetaId: int): Matricula; +listarMatriculas(u: Usuario): List<Matricula>',
    'SeguimientoService||-db: DatabaseService|+registrarInteraccion(u: Usuario, tarjetaId: int, tipo: String, descripcion: String): Interaccion; +programarRecordatorio(u: Usuario, tarjetaId: int, fechaHora: String, descripcion: String): Recordatorio; '
    '+listarRecordatorios(u: Usuario): List<Recordatorio>; +completarRecordatorio(u: Usuario, id: int): Recordatorio; +historial(u: Usuario, tarjetaId: int): Historial; '
    '+registrarComando(remitente: String, usuarioId: Integer, asunto: String, resultado: String, detalle: String): void; +bitacora(cantidad: int): List<String[]>; +vencidos(): List<Recordatorio>; +marcarNotificado(id: int): void',
    'ReporteService||-db: DatabaseService|+embudo(u: Usuario): List<String[]>; +vendedores(u: Usuario): List<String[]>; +perdidasPorMotivo(u: Usuario): List<String[]>; +perdidasPorCategoria(u: Usuario): List<String[]>; +oferta(u: Usuario): List<String[]>',
    'Usuario|record|-id: int; -nombre: String; -apellido: String; -correo: String; -telefono: String; -rol: Rol; -estado: String|+getNombreCompleto(): String; +activo(): boolean; +fila(): String[]',
    'Oferta|record|-id: int; -codigo: String; -nombre: String; -tipo: String; -programa: String; -numModulos: int; -precio: BigDecimal; -estado: String; -coordinadorId: Integer|+abierta(): boolean; +descripcionTipo(): String; +fila(): String[]',
    'Prospecto|record|-id: int; -ci: String; -nombre: String; -apellido: String; -telefono: String; -correo: String; -origen: String|+getNombreCompleto(): String',
    'Interes|record|-id: int; -estado: String; -prospecto: Prospecto; -ofertaCodigo: String; -ofertaEstado: String; -precio: BigDecimal; -coordinadorId: Integer; -vendedorId: Integer; -motivo: String|+cerrada(): boolean; +nombreVendedor(): String; +fila(): String[]',
    'MotivoPerdida|record|-id: int; -nombre: String; -categoria: String; -usos: int|+fila(): String[]',
    'Matricula|record|-id: int; -tarjetaId: int; -ci: String; -prospecto: String; -oferta: String; -monto: BigDecimal; -estado: String; -fecha: String|+fila(): String[]',
    'Interaccion|record|-id: int; -tarjetaId: int; -tipo: String; -descripcion: String; -fecha: String|',
    'Recordatorio|record|-id: int; -tarjetaId: int; -fechaHora: String; -descripcion: String; -estado: String; -usuarioId: int; -correoUsuario: String|+fila(): String[]',
    'Alta|record|-tarjeta: Interes; -prospectoNuevo: boolean|',
    'Asignacion|record|-tarjeta: Interes; -vendedor: Usuario|',
    'Requisito|record|-nombre: String; -valor: String; -cumple: boolean|+fila(): String[]',
    'Historial|record|-tarjeta: Interes; -eventos: List<String[]>|',
]
ROL_ENUM = 'Rol|enumeration||+nombre(): String; +deNombre(rol: String): Rol'
PROC_ROL = {'Administrador': 'AdministradorCommandProcessor', 'Coordinador': 'CoordinadorCommandProcessor',
            'Vendedor': 'VendedorCommandProcessor', 'Visitante': 'PublicoCommandProcessor'}
ENTIDADES_CU = {'CU1': ['Usuario', 'Rol'], 'CU2': ['Oferta', 'Prospecto', 'Interes', 'Alta'], 'CU3': ['Interes', 'Usuario', 'Asignacion'],
                'CU4': ['Interes'], 'CU5': ['MotivoPerdida', 'Interes'], 'CU6': ['Matricula', 'Requisito', 'Interes'],
                'CU7': ['Interaccion', 'Recordatorio', 'Historial'], 'CU8': []}


def main():
    SALIDA.mkdir(parents=True, exist_ok=True)
    ea = EA()
    try:
        construir(ea)
    finally:
        ea.cerrar()
    print(f'{len(ea.exportados)} diagramas en {SALIDA}')


def construir(ea):
    m = ea.modelo
    req, ana, dis, imp = (ea.paquete(m, n) for n in ('1. Requisitos', '2. Análisis', '3. Diseño', '4. Implementación'))

    # ---------------------------------------------------------------- Requisitos: casos de uso
    act = {n: ea.elemento(req, n, 'Actor') for n in ROLES}
    uc = {k: ea.elemento(req, f'{k}: {v}', 'UseCase') for k, v in CU.items()}
    asoc = {}
    for k in CU:
        for a in actores(k):
            asoc[(a, k)] = ea.conector(act[a], uc[k], 'Association')
    for k in CU:
        d = ea.diagrama(req, f'{k}: {CORTO[k]}', 'Use Case')
        acts = actores(k)
        for i, a in enumerate(acts):
            ea.poner(d, act[a], 60, 40 + i * 110, 45, 80)
        ea.poner(d, uc[k], 420, 40 + (len(acts) - 1) * 55 + 10, 190, 70)
        ea.exportar(d, f'uc-{k.lower()}.png')
    d = ea.diagrama(req, 'Modelo de casos de uso', 'Use Case')
    frontera = ea.elemento(req, 'CRM vía e-mail', 'Boundary')
    ea.poner(d, frontera, 260, 20, 330, 8 * 95 + 50, seq=50)
    for i, k in enumerate(CU):
        ea.poner(d, uc[k], 310, 55 + i * 95, 230, 75, seq=1)
    ea.poner(d, act['Administrador'], 60, 520, 45, 80); ea.poner(d, act['Coordinador'], 60, 280, 45, 80)
    ea.conector(act['Administrador'], act['Coordinador'], 'Generalization')
    for (a_, k_), c_ in asoc.items():   # el administrador hereda las asociaciones del coordinador
        if a_ == 'Administrador':
            dl = d.DiagramLinks.AddNew('', ''); dl.ConnectorID = c_.ConnectorID; dl.IsHidden = True; dl.Update()
    ea.poner(d, act['Vendedor'], 800, 420, 45, 80); ea.poner(d, act['Visitante'], 800, 90, 45, 80)
    ea.exportar(d, 'uc-modelo.png')

    # ---------------------------------------------------------------- Análisis: paquetes
    PAQ = [('Administración', ['CU1']), ('Captación y Asignación', ['CU2', 'CU3']),
           ('Gestión del Embudo', ['CU4', 'CU5', 'CU7']), ('Conversión y Reportes', ['CU6', 'CU8'])]
    paqs = [ea.paquete(ana, n) for n, _ in PAQ]
    traces = []
    d = ea.diagrama(ana, 'Paquetes de análisis', 'Package')
    for i, p in enumerate(paqs):
        ea.poner(d, p, 40 + i * 230, 60, 190, 110)
    ea.exportar(d, 'pkg-paquetes.png')
    for i, ((n, cus), p) in enumerate(zip(PAQ, paqs), 1):
        d = ea.diagrama(req, f'Relación del paquete {n}', 'Package')
        ea.poner(d, p, 40, 40 + (len(cus) - 1) * 60, 200, 110)
        for j, k in enumerate(cus):
            traces.append(ea.conector(p, uc[k], 'Dependency', st='trace', dirigido=True))
            ea.poner(d, uc[k], 430, 40 + j * 120, 200, 70)
        ea.exportar(d, f'pkg-trace-{i}.png')
    for i, ((n, cus), p) in enumerate(zip(PAQ, paqs), 1):
        d = ea.diagrama(req, f'Encapsulamiento del paquete {n}', 'Package')
        ea.poner(d, p, 40, 30, 280, 90 + len(cus) * 95, seq=50)
        for j, k in enumerate(cus):
            ea.poner(d, uc[k], 70, 80 + j * 95, 220, 70, seq=1)
        for tr in traces:   # el encapsulamiento muestra la contención, no la dependencia
            dl = d.DiagramLinks.AddNew('', ''); dl.ConnectorID = tr.ConnectorID; dl.IsHidden = True; dl.Update()
        ea.exportar(d, f'pkg-encap-{i}.png')

    # ---------------------------------------------------------------- Análisis: colaboración por CU
    col = ea.paquete(ana, 'Colaboración')
    bnd = ea.elemento(col, 'Cliente de correo', 'Object', 'boundary')
    cp = ea.elemento(col, 'CommandProcessor', 'Object', 'control')
    rcp = ea.elemento(col, 'RolCommandProcessor', 'Object', 'control')
    servs = {k: ea.elemento(col, SERVICIO[k], 'Object', 'control') for k in CU}
    ents = {}
    for k in CU:
        if ENTIDAD[k] not in ents: ents[ENTIDAD[k]] = ea.elemento(col, ENTIDAD[k], 'Object', 'entity')
    enlaces = {}

    def enlace(a, b):
        if (a.ElementID, b.ElementID) not in enlaces:
            enlaces[(a.ElementID, b.ElementID)] = ea.conector(a, b, 'Association')
        return enlaces[(a.ElementID, b.ElementID)]

    for k in CU:
        d = ea.diagrama(col, f'Colaboración {k}: {CORTO[k]}', 'Collaboration')
        acts = list(dict.fromkeys(o[0] for o in OPS[k]))
        for i, a in enumerate(acts):
            ea.poner(d, act[a], 30, 40 + i * 140, 45, 80)
        pos = [(bnd, 330, 60), (cp, 640, 60), (rcp, 950, 60), (servs[k], 950, 400), (ents[ENTIDAD[k]], 640, 400)]
        for e, x, y in pos:
            ea.poner(d, e, x, y, 60, 60)
        cadena = [bnd, cp, rcp, servs[k], ents[ENTIDAD[k]]]
        apil = {}
        n = 0
        for j, (a, cmd, met, dbm) in enumerate(OPS[k], 1):
            pasos = [(act[a], bnd, cmd.split('[')[0], f'{j}'), (bnd, cp, 'procesarEmail()', f'{j}.1'), (cp, rcp, 'procesar()', f'{j}.2'),
                     (rcp, servs[k], met, f'{j}.3'), (servs[k], ents[ENTIDAD[k]], dbm, f'{j}.4')]
            for o, dst, txt, num in pasos:
                ln = enlace(o, dst); n += 1
                c = apil.get(ln.ConnectorID, 0); apil[ln.ConnectorID] = c + 1
                ea.mensaje_com(d, ln, o, dst, txt, num, n, c, COLORES[(j - 1) % 4])
        ea.exportar(d, f'com-{k.lower()}.png')

    d = ea.diagrama(ana, 'Dependencia de paquetes', 'Package')
    for i, p in enumerate(paqs):
        ea.poner(d, p, [40, 330, 330, 620][i], [190, 40, 340, 190][i], 190, 110)
    for a, b in [(1, 0), (2, 0), (3, 0), (3, 2), (2, 1)]:
        ea.conector(paqs[a], paqs[b], 'Dependency', dirigido=True)
    ea.exportar(d, 'pkg-dependencias.png')

    # ---------------------------------------------------------------- Diseño: despliegue
    dep = ea.paquete(dis, 'Despliegue')
    d = ea.diagrama(dep, 'Diagrama de despliegue', 'Deployment')
    pc = ea.elemento(dep, 'PC o celular del usuario', 'Device')
    cli = ea.elemento(dep, 'Navegador o app de Gmail', 'ExecutionEnvironment', padre=pc)
    goo = ea.elemento(dep, 'Google (Gmail)', 'Device')
    gweb = ea.elemento(dep, 'Gmail: buzón del usuario y smtp.gmail.com', 'ExecutionEnvironment', padre=goo)
    fac = ea.elemento(dep, 'web.tecnoweb.org.bo 139.99.68.169 (mail / db)', 'Device')
    so_f = ea.elemento(dep, 'Linux', 'ExecutionEnvironment', padre=fac)
    dove = ea.elemento(dep, 'Dovecot (POP3 110)', 'ExecutionEnvironment', padre=so_f)
    pg = ea.elemento(dep, 'PostgreSQL 18.6 (5432)', 'ExecutionEnvironment', padre=so_f)
    bd = ea.elemento(dep, 'db_grupo05sa, esquema crm', 'Artifact', padre=pg)
    smail = ea.elemento(dep, 'Sendmail (SMTP 25, MX tecnoweb.org.bo)', 'ExecutionEnvironment', padre=so_f)
    buzon = ea.elemento(dep, 'buzón grupo05sa', 'Artifact', padre=smail)
    vps = ea.elemento(dep, 'VPS Contabo 164.68.96.50', 'Device')
    so_v = ea.elemento(dep, 'Fedora Linux 44', 'ExecutionEnvironment', padre=vps)
    jvm = ea.elemento(dep, 'OpenJDK 25.0.4', 'ExecutionEnvironment', padre=so_v)
    jar = ea.elemento(dep, 'crm-mail.jar', 'Artifact', padre=jvm)
    unidad = ea.elemento(dep, '/etc/systemd/system/crm-mail.service', 'Artifact', padre=so_v)
    props = ea.elemento(dep, '/etc/crm-mail/crm-mail.properties', 'Artifact', padre=so_v)
    for e, x, y, w, h, s in [(pc, 30, 220, 260, 160, 9), (cli, 50, 280, 220, 80, 1),
                             (fac, 380, 30, 470, 480, 9), (so_f, 400, 80, 430, 410, 8),
                             (dove, 420, 130, 390, 70, 2), (pg, 420, 215, 390, 130, 2), (bd, 560, 265, 230, 50, 1),
                             (smail, 420, 360, 390, 110, 2), (buzon, 600, 405, 190, 45, 1),
                             (goo, 380, 590, 470, 140, 9), (gweb, 400, 645, 430, 70, 1),
                             (vps, 950, 60, 400, 420, 9), (so_v, 970, 110, 360, 350, 8), (jvm, 990, 160, 320, 100, 2), (jar, 1110, 200, 180, 45, 1),
                             (unidad, 990, 290, 320, 50, 1), (props, 990, 370, 320, 50, 1)]:
        ea.poner(d, e, x, y, w, h, s)
    for a, b, t_ in [(cli, gweb, 'HTTPS 443'), (gweb, smail, 'SMTP 25'), (jvm, dove, 'POP3 110'),
                     (jvm, pg, 'JDBC TCP 5432'), (jvm, gweb, 'SMTP 587 STARTTLS')]:
        ea.conector(a, b, 'CommunicationPath', t_, dirigido=True)
    ea.exportar(d, 'despliegue.png')

    # ---------------------------------------------------------------- Diseño: capas
    cap = ea.paquete(dis, 'Capas')
    d = ea.diagrama(cap, 'Diagrama de capas', 'Package')
    capas = [('Capa de Presentación', ['Lector de Correos POP3 (Pop3Client)', 'Emisor SMTP (SmtpSender)', 'Correo (MIME)']),
             ('Capa de Aplicación', ['CommandProcessor y procesadores por rol']),
             ('Capa de Dominio', ['usuarios', 'captacion', 'asignacion', 'embudo', 'perdidas', 'conversion', 'seguimiento', 'reportes']),
             ('Capa de Persistencia', ['DatabaseService (PostgreSQL)'])]
    pk = {}
    for i, (nombre, hijos) in enumerate(capas):
        b = ea.elemento(cap, nombre, 'Boundary')
        ea.poner(d, b, 30, 20 + i * 170, 1180, 150, seq=50)
        ancho = 1100 // max(len(hijos), 1)
        for j, h in enumerate(hijos):
            p = ea.paquete(cap, h); pk[h] = p
            w = min(ancho - 20, 260)
            x = 30 + (1180 - len(hijos) * (w + 20)) // 2 + j * (w + 20)
            ea.poner(d, p, x, 60 + i * 170, w, 80, seq=1)
    for a in ['Lector de Correos POP3 (Pop3Client)', 'Emisor SMTP (SmtpSender)', 'Correo (MIME)']:
        ea.conector(pk['CommandProcessor y procesadores por rol'] if a == 'Emisor SMTP (SmtpSender)' else pk[a],
                    pk[a] if a == 'Emisor SMTP (SmtpSender)' else pk['CommandProcessor y procesadores por rol'], 'Dependency', dirigido=True)
    for h in capas[2][1]:
        ea.conector(pk['CommandProcessor y procesadores por rol'], pk[h], 'Dependency', dirigido=True)
        ea.conector(pk[h], pk['DatabaseService (PostgreSQL)'], 'Dependency', dirigido=True)
    ea.exportar(d, 'capas.png')

    # ---------------------------------------------------------------- Diseño: clases (código)
    cls_pkg = ea.paquete(dis, 'Clases')
    cls = {}
    for spec in CLASES:
        e = clase(ea, cls_pkg, spec); cls[e.Name] = e
    cls['Rol'] = clase(ea, cls_pkg, ROL_ENUM, 'Enumeration')
    for v in ('ADMINISTRADOR', 'COORDINADOR', 'VENDEDOR'):
        a = cls['Rol'].Attributes.AddNew(v, ''); a.Update()
    for sub in ('AdministradorCommandProcessor', 'CoordinadorCommandProcessor', 'VendedorCommandProcessor', 'PublicoCommandProcessor'):
        ea.conector(cls[sub], cls['RolCommandProcessor'], 'Generalization')
    cls['RolCommandProcessor'].Abstract = '1'; cls['RolCommandProcessor'].Update()

    # ---------------------------------------------------------------- Diseño: secuencia por CU
    seq = ea.paquete(dis, 'Secuencia')
    for k in CU:
        d = ea.diagrama(seq, f'{k}: {CORTO[k]}', 'Sequence')
        acts = list(dict.fromkeys(o[0] for o in OPS[k]))
        vidas = {a: ea.elemento(seq, a, 'Actor') for a in acts}
        nombres = ['App', 'CommandProcessor', 'CommandParser', 'RolCommandProcessor', SERVICIO[k], 'DatabaseService']
        obj = {n: ea.elemento(seq, f':{n}', 'Object') for n in nombres}
        cols = list(vidas.values()) + [obj[n] for n in nombres]
        filas = sum(8 for _ in OPS[k])
        alto = 120 + filas * 35.2 + 60
        for i, e in enumerate(cols):
            ea.poner(d, e, 30 + i * 140, 40, 115 if i >= len(acts) else 60, int(alto))
        n = 0
        tam = []
        for j, (a, cmd, met, dbm) in enumerate(OPS[k], 1):
            pasos = [(vidas[a], obj['App'], f'{j}. {cmd}', False), (obj['App'], obj['CommandProcessor'], f'{j}.1 procesarEmail', False),
                     (obj['CommandProcessor'], obj['CommandParser'], f'{j}.2 parse', False),
                     (obj['CommandProcessor'], obj['RolCommandProcessor'], f'{j}.3 procesar', False),
                     (obj['RolCommandProcessor'], obj[SERVICIO[k]], f'{j}.4 {met[:-2]}', False),
                     (obj[SERVICIO[k]], obj['DatabaseService'], f'{j}.5 {dbm[:-2]}', False),
                     (obj['DatabaseService'], obj[SERVICIO[k]], '', True),
                              (obj['App'], vidas[a], f'{j}.6 enviar (correo HTML)', False)]
            for o, dst, txt, ret in pasos:
                n += 1; ea.mensaje_seq(d, o, dst, txt, n, ret)
            tam.append(len(pasos) * 35.2)
        fr = ea.elemento(seq, 'comando recibido', 'InteractionFragment'); fr.Subtype = 0; fr.Update()
        # La API de EA 15 falla con más de dos operandos: se crean dos y se escribe la lista completa en t_xref.
        for _ in range(2):
            part = fr.Partitions.AddNew('x', ''); part.Size = 100
        fr.Update()
        pars = ''.join(f"@PAR;Name={cmd.split('[')[0]};Size={int(t)};GUID={{{str(uuid.uuid4()).upper()}}};@ENDPAR;"
                       for (a, cmd, *_), t in reversed(list(zip(OPS[k], tam))))
        ea.r.Execute(f"UPDATE t_xref SET Description='{pars}' WHERE Client='{fr.ElementGUID}' AND Name='Partitions'")
        ea.poner(d, fr, 15, 100, len(cols) * 140 + 10, int(sum(tam)) + 35, seq=60)
        ea.exportar(d, f'sd-{k.lower()}.png')

    # ---------------------------------------------------------------- Diseño: procedimental por CU (clases)
    comunes = ['Command', 'CommandParser', 'CommandProcessor', 'RolCommandProcessor', 'ResponseFormatter', 'DatabaseService']
    usos = [('CommandParser', 'Command'), ('CommandProcessor', 'CommandParser'), ('CommandProcessor', 'RolCommandProcessor'),
            ('CommandProcessor', 'ResponseFormatter'), ('RolCommandProcessor', 'ResponseFormatter'), ('RolCommandProcessor', 'Command')]
    hechos = set()

    def uso(a, b):
        if (a, b) not in hechos:
            hechos.add((a, b)); ea.conector(cls[a], cls[b], 'Dependency', dirigido=True)

    for a, b in usos: uso(a, b)
    for k in CU:
        roles = [PROC_ROL[a] for a in actores(k)]
        for r_ in roles: pass
        uso('RolCommandProcessor', SERVICIO[k]); uso(SERVICIO[k], 'DatabaseService')
        for e in ENTIDADES_CU[k]: uso(SERVICIO[k], e)
        d = ea.diagrama(cls_pkg, f'Diseño procedimental {k}: {CORTO[k]}', 'Logical')
        for nombre in comunes + roles + [SERVICIO[k]] + ENTIDADES_CU[k]:
            ea.poner(d, cls[nombre], 10, 10, 200, 120)
        ea.ordenar(d)
        ea.exportar(d, f'cls-{k.lower()}.png')

    # ---------------------------------------------------------------- Diseño: modelo de datos
    datos = ea.paquete(dis, 'Modelo de datos')
    d = ea.diagrama(datos, 'Diagrama de clases del modelo de datos', 'Logical')
    tablas = {}
    fks = []
    for nombre, cuerpo in re.findall(r'CREATE TABLE (\w+) \((.*?)\n\);', SQL, re.S):
        e = ea.elemento(datos, nombre, 'Class', 'table')
        for linea in cuerpo.splitlines():
            mm = re.match(r'\s+(\w+)\s+(serial|int|varchar\(\d+\)|numeric\(\d+,\d+\)|timestamptz|boolean)\s*(.*)', linea)
            if not mm: continue
            col_, tipo, resto = mm.groups()
            a = e.Attributes.AddNew(col_, tipo); a.Visibility = 'Public'; a.Update()
            fk = re.search(r'REFERENCES (\w+)', resto)
            if fk: fks.append((nombre, fk.group(1), 'NOT NULL' in resto, col_))
        e.Update(); tablas[nombre] = e
    for nombre, (x, y) in {'oferta': (280, 30), 'usuario': (760, 30), 'log_comando': (1180, 30),
                           'prospecto': (30, 330), 'interes': (520, 330), 'motivo_perdida': (1000, 360),
                           'matricula': (30, 660), 'movimiento': (350, 660), 'interaccion': (670, 660), 'recordatorio': (990, 660)}.items():
        ea.poner(d, tablas[nombre], x, y, 220, 175)
    unicos = {('matricula', 'interes')}
    for hijo, padre, obligatorio, col_ in fks:
        mult_hijo = '0..1' if (hijo, padre) in unicos else '0..*'
        ea.conector(tablas[hijo], tablas[padre], 'Association', col_, card=(mult_hijo, '1' if obligatorio else '0..1'))
    ea.exportar(d, 'clases-datos.png')

    # ---------------------------------------------------------------- Implementación: componentes
    d = ea.diagrama(imp, 'Diagrama de componentes', 'Component')
    jarc = ea.elemento(imp, 'crm-mail.jar', 'Component', 'executable')
    capas_c = {n: ea.elemento(imp, n, 'Component', 'subsystem', padre=jarc) for n in ('Presentación', 'Aplicación', 'Dominio', 'Persistencia')}
    ipop = ea.elemento(imp, 'POP3 (RFC 1939)', 'Interface')
    ismtp = ea.elemento(imp, 'SMTP (RFC 5321) + STARTTLS', 'Interface')
    ijdbc = ea.elemento(imp, 'JDBC', 'Interface')
    dov = ea.elemento(imp, 'Dovecot mail.tecnoweb.org.bo:110', 'Component', 'system extern')
    gm = ea.elemento(imp, 'Gmail smtp.gmail.com:587', 'Component', 'system extern')
    pgc = ea.elemento(imp, 'PostgreSQL 18.6 db.tecnoweb.org.bo:5432', 'Component', 'Database')
    angus = ea.elemento(imp, 'Angus Mail 2.0.5 (Jakarta Mail 2.1.5)', 'Component', 'library')
    pgj = ea.elemento(imp, 'PostgreSQL JDBC 42.7.13', 'Component', 'library')
    for e, x, y, w, h, s in [(jarc, 40, 40, 520, 520, 9), (capas_c['Presentación'], 120, 90, 360, 70, 1), (capas_c['Aplicación'], 120, 200, 360, 70, 1),
                             (capas_c['Dominio'], 120, 310, 360, 70, 1), (capas_c['Persistencia'], 120, 420, 360, 70, 1),
                             (ipop, 640, 50, 170, 70, 1), (ismtp, 640, 170, 170, 70, 1), (ijdbc, 640, 420, 170, 70, 1),
                             (dov, 860, 40, 260, 80, 1), (gm, 860, 160, 260, 80, 1), (pgc, 860, 410, 260, 80, 1),
                             (angus, 620, 290, 230, 60, 1), (pgj, 620, 560, 230, 60, 1)]:
        ea.poner(d, e, x, y, w, h, s)
    for a, b in [('Presentación', 'Aplicación'), ('Aplicación', 'Dominio'), ('Dominio', 'Persistencia')]:
        ea.conector(capas_c[a], capas_c[b], 'Dependency', dirigido=True)
    for prov, itf in [(dov, ipop), (gm, ismtp), (pgc, ijdbc)]:
        ea.conector(prov, itf, 'Realisation', dirigido=True)
    for cli_, itf in [(capas_c['Presentación'], ipop), (capas_c['Presentación'], ismtp), (capas_c['Persistencia'], ijdbc)]:
        ea.conector(cli_, itf, 'Usage', dirigido=True)
    ea.conector(capas_c['Presentación'], angus, 'Usage', dirigido=True)
    ea.conector(capas_c['Persistencia'], pgj, 'Usage', dirigido=True)
    ea.exportar(d, 'componentes.png')


if __name__ == '__main__':
    main()
