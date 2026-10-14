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
COLORES = [NEGRO, ROJO, VERDE, AZUL]   # en colaboración EA colorea solo por número de mensaje (paleta propia desde el 5.º)


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

    def asociacion(self, a, b, rol, card='1', vis='Private'):
        """Asociación navegable de a hacia b: el campo `rol` de a (con su visibilidad) y su multiplicidad."""
        c = a.Connectors.AddNew('', 'Association'); c.SupplierID = b.ElementID; c.Direction = 'Source -> Destination'; c.Update()
        c.SupplierEnd.Role = rol; c.SupplierEnd.Cardinality = card; c.SupplierEnd.Navigable = 'Navigable'; c.SupplierEnd.Visibility = vis
        c.SupplierEnd.Update()
        c.ClientEnd.Navigable = 'Non-Navigable'; c.ClientEnd.Update(); c.Update()
        # Actualizar un extremo invierte Direction en EA 15: se fija al final, directo en la tabla.
        self.r.Execute(f"UPDATE t_connector SET Direction='Source -> Destination', DestIsNavigable=TRUE, SourceIsNavigable=FALSE "
                       f"WHERE Connector_ID={c.ConnectorID}")
        return c

    def interfaz(self, comp, nombre, tipo, clasificador):
        """Interfaz provista (lollipop) o requerida (socket) expuesta en el borde de un componente."""
        e = comp.EmbeddedElements.AddNew(nombre, tipo); e.ClassifierID = clasificador.ElementID; e.Update()
        return e

    def mensaje_seq(self, d, a, b, texto, n, tipo='call', args=''):
        """tipo: 'call' (síncrono), 'signal' (asíncrono: un correo) o 'return'. args se muestra entre paréntesis."""
        c = a.Connectors.AddNew(texto, 'Sequence'); c.SupplierID = b.ElementID; c.DiagramID = d.DiagramID; c.SequenceNo = n; c.Update()
        p1, p3 = ('Asynchronous', 'Signal') if tipo == 'signal' else ('Synchronous', 'Call')
        p2 = f"retval=void;params={args};paramsDlg={args};" if args else ''
        self.r.Execute(f"UPDATE t_connector SET PDATA1='{p1}', PDATA2='{p2}', PDATA3='{p3}', PDATA4='{int(tipo == 'return')}', "
                       f"StateFlags='IsReturn={str(tipo == 'return').lower()};' WHERE Connector_ID={c.ConnectorID}")
        return c

    def mensaje_com(self, d, enlace, a, b, texto, numero, n, apilado, color, args='', vertical=False, centrado=False, sx=-15):
        """Mensaje de comunicación colgado de su enlace (SourceIsAggregate), como los crea EA.
        args: lo que va entre paréntesis (el diagrama usa SuppressBrackets: un comando no lleva paréntesis)."""
        c = a.Connectors.AddNew(texto, 'Collaboration'); c.SupplierID = b.ElementID; c.DiagramID = d.DiagramID
        c.SequenceNo = n; c.Direction = 'Source -> Destination'; c.Color = color; c.Update()
        sy = 18 + (30 if vertical else 15) * apilado - (centrado or 0)   # centrado: desplazamiento que lleva la pila al medio del enlace
        p2 = f"retval=void;params={args};paramsDlg={args};" if args else ''
        self.r.Execute(f"UPDATE t_connector SET PDATA1='Synchronous', PDATA2='{p2}', PDATA3='Call', PDATA4='{numero}', "
                       f"PDATA5='SX={sx};SY={sy};EX={sx + 30};EY={sy};EDGE=2;', SourceIsAggregate={enlace.ConnectorID}, RouteStyle=1, "
                       f"DestIsNavigable=TRUE, StateFlags='IsReturn=false;', SourceTS='instance', DestTS='instance' "
                       f"WHERE Connector_ID={c.ConnectorID}")

    def ordenar(self, d):
        self.pi.LayoutDiagramEx(d.DiagramGUID, 0x40000000, 4, 50, 40, False)

    def exportar(self, d, archivo):
        d.Update(); self.r.ReloadDiagram(d.DiagramID)
        ruta = SALIDA / archivo
        if not self.pi.PutDiagramImageToFile(d.DiagramGUID, str(ruta), 1): raise SystemExit('no se exportó ' + archivo)
        # EMF (vectorial): word_export.ps1 lo pone en lugar del PNG para que el PDF y la impresión salgan nítidos
        if not self.pi.PutDiagramImageToFile(d.DiagramGUID, str(ruta.with_suffix('.emf')), 0): raise SystemExit('no se exportó EMF ' + archivo)
        self.exportados.append(archivo); print('  ', archivo)


def clase(ea, pkg, spec, tipo='Class'):
    """spec: 'Nombre|estereotipo|-attr: T; -attr2: T|+op(p: T, q: T): R; #op2(): R'. '$' tras la visibilidad = static."""
    nombre, st, attrs, ops = (spec.split('|') + ['', '', ''])[:4]
    e = ea.elemento(pkg, nombre, tipo, st)
    vis = {'+': 'Public', '-': 'Private', '#': 'Protected'}
    for i, a in enumerate(filter(None, (x.strip() for x in attrs.split(';')))):
        n, t = (a[1:].split(':') + [''])[:2]
        at = e.Attributes.AddNew(n.strip().lstrip('$'), t.strip()); at.Visibility = vis[a[0]]; at.Pos = i
        at.IsStatic = n.startswith('$'); at.Update()
    for o in filter(None, (x.strip() for x in ops.split(';'))):
        m = re.match(r'([+\-#])(\$?)(\w+)\((.*)\)(?::\s*(.+))?$', o)
        v, est, n, ps, ret = m.groups()
        mt = e.Methods.AddNew(n, (ret or 'void').strip()); mt.Visibility = vis[v]; mt.IsStatic = bool(est); mt.Update()
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
# Campo de RolCommandProcessor que referencia a cada servicio
CAMPO = {'UsuarioService': 'usuarios', 'ProspectoService': 'prospectos', 'AsignacionService': 'asignacion', 'EmbudoService': 'embudo',
         'PerdidaService': 'perdidas', 'ConversionService': 'conversion', 'SeguimientoService': 'seguimiento', 'ReporteService': 'reportes'}
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
            ('Coordinador', 'IMPORTARPROSPECTOS[] + .csv', 'importar()', 'enTransaccion()'),
            ('Vendedor', 'LISTARPROSPECTOS["texto"]', 'listar()', 'getConnection()'),
            ('Vendedor', 'MODIFICARPROSPECTO["ci",...,"origen"]', 'modificar()', 'enTransaccion()'),
            ('Administrador', 'ELIMINARPROSPECTO["ci"]', 'eliminar()', 'enTransaccion()'),
            ('Administrador', 'ASIGNARCOORDINADOR["codigo_programa","id_coordinador"]', 'asignarCoordinador()', 'enTransaccion()')],
    'CU3': [('Vendedor', 'LISTARBOLSA[]', 'bolsa()', 'lista()'),
            ('Vendedor', 'TOMARPROSPECTO["id_tarjeta"]', 'tomar()', 'enTransaccion()'),
            ('Coordinador', 'ASIGNARPROSPECTO["id_tarjeta","id_vendedor"]', 'asignar()', 'enTransaccion()'),
            ('Coordinador', 'ASIGNARAUTOMATICO[]', 'asignarAutomatico()', 'enTransaccion()')],
    'CU4': [('Vendedor', 'VERTABLERO[]', 'tablero()', 'lista()'),
            ('Vendedor', 'MOVERTARJETA["id_tarjeta","estado"]', 'mover()', 'enTransaccion()')],
    'CU5': [('Vendedor', 'LISTARMOTIVOS[]', 'listarMotivos()', 'lista()'),
            ('Vendedor', 'MARCARPERDIDO["id_tarjeta","id_motivo","detalle"]', 'marcarPerdido()', 'enTransaccion()'),
            ('Coordinador', 'INSERTARMOTIVO["nombre","categoria"]', 'insertarMotivo()', 'enTransaccion()'),
            ('Coordinador', 'MODIFICARMOTIVO["id","nombre","categoria"]', 'modificarMotivo()', 'enTransaccion()'),
            ('Coordinador', 'ELIMINARMOTIVO["id"]', 'eliminarMotivo()', 'enTransaccion()')],
    'CU6': [('Vendedor', 'VALIDARCONVERSION["id_tarjeta"]', 'validar()', 'getConnection()'),
            ('Vendedor', 'CONVERTIRPROSPECTO["id_tarjeta"]', 'convertir()', 'enTransaccion()'),
            ('Coordinador', 'LISTARMATRICULAS[]', 'listarMatriculas()', 'lista()')],
    'CU7': [('Vendedor', 'REGISTRARINTERACCION["id_tarjeta","tipo","descripcion"]', 'registrarInteraccion()', 'enTransaccion()'),
            ('Vendedor', 'PROGRAMARRECORDATORIO["id_tarjeta","fecha_hora","descripcion"]', 'programarRecordatorio()', 'enTransaccion()'),
            ('Vendedor', 'LISTARRECORDATORIOS[]', 'listarRecordatorios()', 'getConnection()'),
            ('Vendedor', 'COMPLETARRECORDATORIO["id_recordatorio"]', 'completarRecordatorio()', 'enTransaccion()'),
            ('Vendedor', 'VERHISTORIAL["id_tarjeta"]', 'historial()', 'filas()'),
            ('Administrador', 'VERBITACORA["cantidad"]', 'bitacora()', 'filas()')],
    'CU8': [('Vendedor', 'REPORTEEMBUDO[]', 'embudo()', 'filas()'),
            ('Coordinador', 'REPORTEVENDEDORES[]', 'vendedores()', 'filas()'),
            ('Coordinador', 'REPORTEPERDIDAS[]', 'perdidasPorCategoria()', 'filas()'),
            ('Coordinador', 'REPORTEOFERTA[]', 'oferta()', 'filas()')],
}

# Clases de diseño (firmas reales de p1/src)
CLASES = [
    'Pop3Client|||+stat(): int; +tamano(n: int): long; +retr(n: int): byte[]; +top(n: int, lineas: int): byte[]; +dele(n: int): void; +quit(): void; +close(): void',
    'SmtpSender|||+enviar(para: String, asunto: String, html: String, texto: String): void; +getRemitente(): String',
    'Command||-nombre: String; -tipo: Comando; -params: List<String>; -valido: boolean; -mensajeError: String; -remitente: String; -adjunto: String'
    '|+getNombre(): String; +getTipo(): Comando; +getParam(i: int): String; +cantidad(): int; +isValido(): boolean; +getMensajeError(): String; +getRemitente(): String; +getAdjunto(): String',
    'CommandParser|||+parse(asunto: String, remitente: String, adjunto: String): Command; +$pareceComando(asunto: String): boolean',
    'CommandProcessor||-parser: CommandParser; -fmt: ResponseFormatter; -usuarios: UsuarioService; -seguimiento: SeguimientoService; -porRol: Map<Rol, RolCommandProcessor>; -publico: PublicoCommandProcessor'
    '|+procesarEmail(remitente: String, asunto: String, adjunto: String): Respuesta; +bitacora(remitente: String, usuarioId: Integer, asunto: String, resultado: String, detalle: String): void; '
    '+formatter(): ResponseFormatter; +procesadores(): Map<String, RolCommandProcessor>',
    'RolCommandProcessor|abstract|#fmt: ResponseFormatter; #usuarios: UsuarioService; #prospectos: ProspectoService; #asignacion: AsignacionService; #embudo: EmbudoService; '
    '#perdidas: PerdidaService; #conversion: ConversionService; #seguimiento: SeguimientoService; #reportes: ReporteService'
    '|+comandos(): Set<Comando>; +rol(): String; +procesar(cmd: Command, u: Usuario): Respuesta; #mensajeDenegado(cmd: Command): String; -ejecutar(c: Command, u: Usuario): Respuesta',
    'AdministradorCommandProcessor||-$COMANDOS: Set<Comando>|+comandos(): Set<Comando>; +rol(): String',
    'CoordinadorCommandProcessor||-$COMANDOS: Set<Comando>|+comandos(): Set<Comando>; +rol(): String',
    'VendedorCommandProcessor||-$COMANDOS: Set<Comando>|+comandos(): Set<Comando>; +rol(): String',
    'PublicoCommandProcessor||-$COMANDOS: Set<Comando>|+comandos(): Set<Comando>; +rol(): String; #mensajeDenegado(cmd: Command): String',
    'ResponseFormatter|||+success(titulo: String, mensaje: String): Respuesta; +successTable(titulo: String, mensaje: String, columnas: String[], filas: List<String[]>): Respuesta; '
    '+successTable(titulo: String, mensaje: String, bloques: Bloque...): Respuesta; +aviso(titulo: String, mensaje: String, bloques: Bloque...): Respuesta; '
    '+error(comando: String, mensaje: String): Respuesta; +denegado(comando: String, mensaje: String): Respuesta; +estadoBadge(estado: String): String; +html(r: Respuesta, asuntoRecibido: String): String; '
    '+texto(r: Respuesta, asuntoRecibido: String): String',
    'DatabaseService||-url: String; -usuario: String; -password: String|+getConnection(): Connection; +enTransaccion(trabajo: Trabajo): T; +$lista(c: Connection, mapeo: Mapeo, sql: String, params: Object...): List<T>; '
    '+$uno(c: Connection, mapeo: Mapeo, sql: String, params: Object...): T; +$filas(c: Connection, sql: String, params: Object...): List<String[]>; +$actualizar(c: Connection, sql: String, params: Object...): int; '
    '+$insertar(c: Connection, sql: String, params: Object...): int; +$texto(v: Object): String',
    'UsuarioService||-db: DatabaseService|+buscarPorCorreo(correo: String): Usuario; +buscarPorId(id: int): Usuario; +listar(rol: String): List<Usuario>; +insertar(nombre: String, apellido: String, correo: String, telefono: String, rol: String): Usuario; '
    '+modificar(actor: Usuario, id: int, nombre: String, apellido: String, correo: String, telefono: String, rol: String, estado: String): Usuario; +eliminar(actor: Usuario, id: int): int',
    'ProspectoService||-db: DatabaseService|+listarOferta(todas: boolean): List<Oferta>; +registrar(actor: Usuario, ci: String, nombre: String, apellido: String, telefono: String, correo: String, origen: String, codigoOferta: String): Alta; '
    '+importar(actor: Usuario, csv: String): List<String[]>; +listar(u: Usuario, texto: String): List<Interes>; +modificar(u: Usuario, ci: String, nombre: String, apellido: String, telefono: String, correo: String, origen: String): Prospecto; '
    '+eliminar(ci: String): Prospecto; +asignarCoordinador(codigoPrograma: String, coordinadorId: int): int',
    'AsignacionService||-db: DatabaseService|+bolsa(u: Usuario): List<Interes>; +asignar(u: Usuario, tarjetaId: int, vendedorId: int): Asignacion; +asignarAutomatico(u: Usuario): List<Asignacion>; +tomar(u: Usuario, tarjetaId: int): Interes',
    'EmbudoService||-db: DatabaseService|+tablero(u: Usuario): List<Interes>; +mover(u: Usuario, id: int, estado: String): Interes; +$tarjeta(c: Connection, u: Usuario, id: int, modificar: boolean): Interes; '
    '+$registrarMovimiento(c: Connection, interesId: int, usuarioId: Integer, anterior: String, nuevo: String, detalle: String): void',
    'PerdidaService||-db: DatabaseService|+listarMotivos(): List<MotivoPerdida>; +insertarMotivo(nombre: String, categoria: String): MotivoPerdida; +modificarMotivo(id: int, nombre: String, categoria: String): MotivoPerdida; '
    '+eliminarMotivo(id: int): MotivoPerdida; +marcarPerdido(u: Usuario, tarjetaId: int, motivoId: int, detalle: String): Interes',
    'ConversionService||-db: DatabaseService|+validar(u: Usuario, tarjetaId: int): List<Requisito>; +convertir(u: Usuario, tarjetaId: int): Matricula; +listarMatriculas(u: Usuario): List<Matricula>',
    'SeguimientoService||-db: DatabaseService|+registrarInteraccion(u: Usuario, tarjetaId: int, tipo: String, descripcion: String): Interaccion; +programarRecordatorio(u: Usuario, tarjetaId: int, fechaHora: String, descripcion: String): Recordatorio; '
    '+listarRecordatorios(u: Usuario): List<Recordatorio>; +completarRecordatorio(u: Usuario, id: int): Recordatorio; +historial(u: Usuario, tarjetaId: int): Historial; '
    '+registrarComando(remitente: String, usuarioId: Integer, asunto: String, resultado: String, detalle: String): void; +bitacora(cantidad: int): List<String[]>; +vencidos(): List<Recordatorio>; +marcarNotificado(id: int): void',
    'ReporteService||-db: DatabaseService|+embudo(u: Usuario): List<String[]>; +vendedores(u: Usuario): List<String[]>; +perdidasPorMotivo(u: Usuario): List<String[]>; +perdidasPorCategoria(u: Usuario): List<String[]>; +oferta(u: Usuario): List<String[]>',
    'Usuario|record|-id: int; -nombre: String; -apellido: String; -correo: String; -telefono: String; -rol: Rol; -estado: String|+$de(rs: ResultSet): Usuario; +getNombreCompleto(): String; +activo(): boolean; +fila(): String[]',
    'Oferta|record|-id: int; -codigo: String; -nombre: String; -tipo: String; -programa: String; -numModulos: int; -precio: BigDecimal; -estado: String; -coordinadorId: Integer; -coordinador: String|+$de(rs: ResultSet): Oferta; +abierta(): boolean; +descripcionTipo(): String; +fila(): String[]',
    'Prospecto|record|-id: int; -ci: String; -nombre: String; -apellido: String; -telefono: String; -correo: String; -origen: String|+$de(rs: ResultSet): Prospecto; +getNombreCompleto(): String',
    'Interes|record|-id: int; -estado: String; -prospecto: Prospecto; -ofertaCodigo: String; -ofertaNombre: String; -ofertaEstado: String; -precio: BigDecimal; -coordinadorId: Integer; -vendedorId: Integer; -vendedor: String; -motivo: String; -creadoEn: String|+$de(rs: ResultSet): Interes; +cerrada(): boolean; +nombreVendedor(): String; +fila(): String[]',
    'MotivoPerdida|record|-id: int; -nombre: String; -categoria: String; -usos: int|+$de(rs: ResultSet): MotivoPerdida; +fila(): String[]',
    'Matricula|record|-id: int; -tarjetaId: int; -ci: String; -prospecto: String; -oferta: String; -monto: BigDecimal; -estado: String; -registradoPor: String; -fecha: String|+$de(rs: ResultSet): Matricula; +fila(): String[]',
    'Interaccion|record|-id: int; -tarjetaId: int; -tipo: String; -descripcion: String; -fecha: String|+$de(rs: ResultSet): Interaccion',
    'Recordatorio|record|-id: int; -tarjetaId: int; -prospecto: String; -telefono: String; -fechaHora: String; -descripcion: String; -estado: String; -usuarioId: int; -usuario: String; -correoUsuario: String|+$de(rs: ResultSet): Recordatorio; +fila(): String[]',
    'Alta|record|-tarjeta: Interes; -prospectoNuevo: boolean|',
    'Asignacion|record|-tarjeta: Interes; -vendedor: Usuario|',
    'Requisito|record|-nombre: String; -valor: String; -cumple: boolean|+fila(): String[]',
    'Historial|record|-tarjeta: Interes; -eventos: List<String[]>|',
]
ROL_ENUM = 'Rol|enumeration||+nombre(): String; +deNombre(rol: String): Rol'
# Núcleo del diseño (firmas reales de p1/src); los tipos anidados llevan el nombre calificado de Java.
NUCLEO = [
    'App||-$MAX_POR_CICLO: int; -$MAX_BYTES: long; -cfg: Config; -procesador: CommandProcessor; -seguimiento: SeguimientoService; -smtp: SmtpSender; -fmt: ResponseFormatter'
    '|+$main(args: String[]): void; -ejecutar(): void; -ciclo(): void; -atender(m: Recibido): void; -enviar(para: String, r: Respuesta, asuntoRecibido: String): void; -avisarRecordatorios(): void',
    'Config|record|-pop3Host: String; -pop3Puerto: int; -pop3Usuario: String; -pop3Password: String; -smtpHost: String; -smtpPuerto: int; -smtpUsuario: String; '
    '-smtpPassword: String; -buzonSistema: String; -dbUrl: String; -dbUsuario: String; -dbPassword: String; -intervaloSegundos: int|+$cargar(archivo: Path): Config',
    'Correo|record|-remitente: String; -asunto: String; -adjuntoCsv: String; -automatico: boolean|+$leer(crudo: byte[]): Correo',
    'Respuesta|record|-resultado: String; -titulo: String; -mensaje: String; -bloques: List<Bloque>; -avisos: List<Aviso>|+conAvisos(nuevos: List<Aviso>): Respuesta; +asunto(): String',
    'Respuesta.Tabla|record|-titulo: String; -columnas: String[]; -filas: List<String[]>|',
    'Respuesta.Carril|record|-estado: String; -tarjetas: List<String[]>|',
    'Respuesta.Aviso|record|-para: String; -contenido: Respuesta|',
]
INTERFACES = {'Closeable': '+close(): void', 'Respuesta.Bloque': '', 'DatabaseService.Mapeo<T>': '+de(rs: ResultSet): T',
              'DatabaseService.Trabajo<T, E>': '+ejecutar(c: Connection): T'}
PROC_ROL = {'Administrador': 'AdministradorCommandProcessor', 'Coordinador': 'CoordinadorCommandProcessor',
            'Vendedor': 'VendedorCommandProcessor', 'Visitante': 'PublicoCommandProcessor'}
ENTIDADES_CU = {'CU1': ['Usuario', 'Rol'], 'CU2': ['Oferta', 'Prospecto', 'Interes', 'Alta'], 'CU3': ['Interes', 'Usuario', 'Asignacion'],
                'CU4': ['Interes'], 'CU5': ['MotivoPerdida', 'Interes'], 'CU6': ['Matricula', 'Requisito', 'Interes'],
                'CU7': ['Interaccion', 'Recordatorio', 'Historial'], 'CU8': []}
MAPEADAS = ('Usuario', 'Oferta', 'Prospecto', 'Interes', 'MotivoPerdida', 'Matricula', 'Interaccion', 'Recordatorio')


def firma(clase_, metodo):
    """Nombres de los parámetros del método según CLASES (los del código)."""
    spec = next(c for c in CLASES if c.startswith(clase_ + '|'))
    ps = re.search(r'[+#-]\$?' + metodo + r'\(([^)]*)\)', spec).group(1)
    return ', '.join(x.split(':')[0].strip() for x in ps.split(',') if x.strip())


def retorno(clase_, metodo):
    """Tipo de retorno declarado del método (vacío si es void)."""
    spec = next(c for c in CLASES if c.startswith(clase_ + '|'))
    ret = re.search(r'[+#-]\$?' + metodo + r'\([^)]*\):\s*([^;|]+)', spec).group(1).strip()
    return '' if ret == 'void' else ret


def tipo_bd(servicio, met, dbm):
    """Retorno de DatabaseService con el parámetro T ya ligado al tipo que devuelve el servicio."""
    r = retorno('DatabaseService', dbm[:-2])
    return retorno(servicio, met[:-2]) if r in ('T', 'List<T>') else r


def corta(args):
    """Argumentos para un mensaje de comunicación: los reales si caben en la etiqueta, si no '...' ('' -> paréntesis vacíos)."""
    return ' ' if not args else (args if len(args) <= 14 else '...')


def entidad_mapeada(cu, metodo):
    """Entidad que DatabaseService construye con su método de() para el resultado del servicio (None si devuelve filas o int)."""
    spec = next(c for c in CLASES if c.startswith(SERVICIO[cu] + '|'))
    ret = re.search(r'[+#-]\$?' + metodo[:-2] + r'\([^)]*\):\s*([\w<>\[\]]+)', spec).group(1)
    tipo = ret[5:-1] if ret.startswith('List<') else ret
    return tipo if tipo in MAPEADAS else None


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
        ea.poner(d, uc[k], 420, 40 + (len(acts) - 1) * 55, 220, 90)
        ea.exportar(d, f'uc-{k.lower()}.png')
    d = ea.diagrama(req, 'Modelo de casos de uso', 'Use Case')
    frontera = ea.elemento(req, 'CRM vía e-mail', 'Boundary')
    ea.poner(d, frontera, 460, 20, 330, 8 * 95 + 50, seq=50)
    for i, k in enumerate(CU):
        ea.poner(d, uc[k], 510, 55 + i * 95, 230, 75, seq=1)
    # Sin generalización entre actores (el docente no generaliza en requisitos: el modelo es el menú por actor) y como en el
    # código, donde AdministradorCommandProcessor y CoordinadorCommandProcessor son hermanos bajo RolCommandProcessor.
    ea.poner(d, act['Administrador'], 60, 300, 45, 80); ea.poner(d, act['Coordinador'], 60, 470, 45, 80)
    ea.poner(d, act['Vendedor'], 1200, 470, 45, 80); ea.poner(d, act['Visitante'], 1200, 140, 45, 80)
    ea.exportar(d, 'uc-modelo.png')

    # ---------------------------------------------------------------- Análisis: paquetes
    PAQ = [('Administración', ['CU1']), ('Captación y Asignación', ['CU2', 'CU3']),
           ('Gestión del Embudo', ['CU4', 'CU5', 'CU7']), ('Conversión y Reportes', ['CU6', 'CU8'])]
    # Los paquetes de análisis cuelgan del mismo paquete que los CU: EA no rotula "(from ...)" y el
    # encapsulamiento puede mostrar los CU y sus actores dentro del paquete, como el P1 aprobado.
    paqs = [ea.paquete(req, n) for n, _ in PAQ]
    traces = []
    d = ea.diagrama(req, 'Paquetes de análisis', 'Package')
    for i, p in enumerate(paqs):
        ea.poner(d, p, 40 + i * 230, 60, 190, 110)
    ea.exportar(d, 'pkg-paquetes.png')
    for i, ((n, cus), p) in enumerate(zip(PAQ, paqs), 1):
        d = ea.diagrama(req, f'Relación del paquete {n}', 'Package')
        ea.poner(d, p, 40, 40 + (len(cus) - 1) * 60, 200, 110)
        for j, k in enumerate(cus):
            traces.append(ea.conector(p, uc[k], 'Dependency', st='trace', dirigido=True))
            ea.poner(d, uc[k], 410, 35 + j * 120, 240, 85)
        ea.exportar(d, f'pkg-trace-{i}.png')
    for i, ((n, cus), p) in enumerate(zip(PAQ, paqs), 1):
        d = ea.diagrama(req, f'Encapsulamiento del paquete {n}', 'Package')
        acts = [a for a in ROLES if any(a in actores(k) for k in cus)]
        filas = max(len(acts), len(cus))
        ea.poner(d, p, 40, 30, 600, 70 + filas * 115, seq=50)
        for j, a in enumerate(acts):
            ea.poner(d, act[a], 110, 80 + j * 115 + (filas - len(acts)) * 57, 45, 80, seq=1)
        cus = sorted(cus, key=lambda k: len(actores(k)))   # el CU con más actores abajo, cerca de los últimos
        for j, k in enumerate(cus):
            ea.poner(d, uc[k], 360, 75 + j * 115 + (filas - len(cus)) * 57, 230, 90, seq=1)
        # contención y asociaciones directas de cada actor, no la dependencia «trace»
        for tr in traces:
            dl = d.DiagramLinks.AddNew('', ''); dl.ConnectorID = tr.ConnectorID; dl.IsHidden = True; dl.Update()
        ea.exportar(d, f'pkg-encap-{i}.png')

    # ---------------------------------------------------------------- Análisis: explosión de cada CU en sus comandos
    # La explosión va en el análisis (no en requisitos): cada CU aprobado se abre en un caso de uso por comando, y cada actor
    # se une solo a los comandos que le permite la matriz de acceso del código (doc/matriz-acceso.tsv).
    # Los elementos van en el paquete de los actores (req), como los encapsulamientos: así EA no rotula "(from ...)".
    col = {'Administrador': 2, 'Coordinador': 3, 'Vendedor': 4, 'Visitante': 5}
    PASO_C, Y0, ALTO_UC = 108, 60, 94   # EA dibuja el óvalo con ancho = 1,5 x alto: el alto da el ancho
    for k in CU:
        filas = [f for f in MATRIZ if f[0] == k]
        d = ea.diagrama(req, f'Explosión {k}: {CORTO[k]}', 'Use Case')
        alto = Y0 + len(filas) * PASO_C
        ea.poner(d, ea.elemento(req, f'{k}: {CORTO[k]}', 'Boundary'), 320, 20, 360, alto, seq=50)
        cmd = {}
        for j, f in enumerate(filas):
            cmd[f[1]] = ea.elemento(req, f[1].split('[')[0], 'UseCase')
            ea.poner(d, cmd[f[1]], 360, Y0 + j * PASO_C, 280, ALTO_UC, seq=1)
        usados = [a for a in ROLES if any(f[col[a]] == 'Sí' for f in filas)]
        # cada actor a la altura media de sus comandos (menos cruces), separados al menos 130 en su lado
        for lado, x in ((('Administrador', 'Coordinador'), 90), (('Vendedor', 'Visitante'), 900)):
            ys = sorted((sum(Y0 + j * PASO_C + ALTO_UC // 2 for j, f in enumerate(filas) if f[col[a]] == 'Sí') /
                         sum(f[col[a]] == 'Sí' for f in filas), a) for a in lado if a in usados)
            for j in range(1, len(ys)):
                if ys[j][0] - ys[j - 1][0] < 130: ys[j] = (ys[j - 1][0] + 130, ys[j][1])
            for y, a in ys:
                ea.poner(d, act[a], x, int(y) - 40, 45, 80)
        for f in filas:
            for a in usados:
                if f[col[a]] == 'Sí': ea.conector(act[a], cmd[f[1]], 'Association')
        ea.exportar(d, f'uc-exp-{k.lower()}.png')

    # ---------------------------------------------------------------- Análisis: colaboración por CU
    col = ea.paquete(ana, 'Colaboración')
    bnd = ea.elemento(col, 'Cliente de correo', 'Object', 'boundary')
    cp = ea.elemento(col, 'CommandProcessor', 'Object', 'control')
    rcp = ea.elemento(col, 'RolCommandProcessor', 'Object', 'control')
    servs = {k: ea.elemento(col, SERVICIO[k], 'Object', 'control') for k in CU}
    dbo = ea.elemento(col, 'DatabaseService', 'Object', 'entity')
    act_col = {n: ea.elemento(col, n, 'Actor') for n in ROLES}   # actores del modelo de análisis
    ents = {}
    enlaces = {}

    def enlace(a, b):
        if (a.ElementID, b.ElementID) not in enlaces:
            enlaces[(a.ElementID, b.ElementID)] = ea.conector(a, b, 'Association')
        return enlaces[(a.ElementID, b.ElementID)]

    for k in CU:
        d = ea.diagrama(col, f'Colaboración {k}: {CORTO[k]}', 'Collaboration')
        d.StyleEx = 'SuppressBrackets=1;'; d.Update()   # los paréntesis los ponen los argumentos
        acts = list(dict.fromkeys(o[0] for o in OPS[k]))
        # En U, para que quepa en media página con letra legible: arriba actor → cliente de correo → controles,
        # abajo (de derecha a izquierda) servicio → DatabaseService → entidad.
        extra = max(0, len(OPS[k]) - 4)   # la fila de arriba baja y la pila vertical crece con los comandos del CU
        Y, DX = 200 + extra * 30, 250
        Y2 = Y + 360 + extra * 45
        sitio_act = [(30, Y - 10), (307, Y + 190), (307, Y - 190), (30, Y + 190)]   # izquierda, debajo, encima y abajo a la izquierda
        # con dos actores el segundo va encima: así el marco de EA abarca la pila de etiquetas de la fila superior
        sitio = lambda a: ([0, 2] if len(acts) == 2 else [0, 1, 2, 3])[acts.index(a)]
        for i, a in enumerate(acts):
            ea.poner(d, act_col[a], *sitio_act[sitio(a)], 45, 80)
        mapeadas = list(dict.fromkeys(e for o in OPS[k] if (e := entidad_mapeada(k, o[2]))))
        for e in mapeadas:
            if e not in ents: ents[e] = ea.elemento(col, e, 'Object', 'entity')
        x1, x2, x3 = 300, 300 + DX, 300 + 2 * DX
        pos = [(bnd, x1, Y), (cp, x2, Y), (rcp, x3, Y), (servs[k], x3, Y2), (dbo, x2, Y2)]
        pos += [(ents[e], *[(x1, Y2), (x2, Y2 + 200), (x1, Y2 + 200)][i]) for i, e in enumerate(mapeadas)]
        for e, x, y in pos:
            ea.poner(d, e, x, y, 60, 60)
        apil = {}
        n = 0
        for j, (a, cmd, met, dbm) in enumerate(OPS[k], 1):
            nombre_cmd = cmd.split('[')[0] + ('[]' if '[]' in cmd else '[...]')
            pasos = [(act_col[a], bnd, nombre_cmd, f'{j}', ''),
                     (bnd, cp, 'procesarEmail', f'{j}.1', corta(firma('CommandProcessor', 'procesarEmail'))),
                     (cp, rcp, 'procesar', f'{j}.2', firma('RolCommandProcessor', 'procesar')),
                     (rcp, servs[k], met[:-2], f'{j}.3', corta(firma(SERVICIO[k], met[:-2]))),
                     (servs[k], dbo, dbm[:-2], f'{j}.4', corta(firma('DatabaseService', dbm[:-2])))]
            if (e := entidad_mapeada(k, met)): pasos.append((dbo, ents[e], 'de', f'{j}.5', 'rs'))
            for o, dst, txt, num, args in pasos:
                ln = enlace(o, dst); n += 1
                c = apil.get(ln.ConnectorID, 0); apil[ln.ConnectorID] = c + 1
                vertical = ((o is act_col[a] and sitio(a) > 0) or (dst in ents.values() and mapeadas.index(dst.Name) == 1)
                            or (o is rcp and dst is servs[k]))
                # enlace vertical de un actor: la etiqueta al costado de la línea (el eje se invierte según el actor esté abajo o arriba)
                sx = {1: 25, 2: -55}.get(sitio(a), -15) if o is act_col[a] else -15
                ea.mensaje_com(d, ln, o, dst, txt, num, n, c, COLORES[(j - 1) % 4], args, vertical, 15 * len(OPS[k]) + 15 if (o is rcp and dst is servs[k]) else (75 if vertical and dst in ents.values() else 0), sx)
        ea.exportar(d, f'com-{k.lower()}.png')

    d = ea.diagrama(req, 'Dependencia de paquetes', 'Package')
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
    goo = ea.elemento(dep, 'Google', 'Node', 'External System')
    gweb = ea.elemento(dep, 'Gmail (mail.google.com): buzón del usuario', 'ExecutionEnvironment', padre=goo)
    gsmtp = ea.elemento(dep, 'smtp.gmail.com (SMTP 587)', 'ExecutionEnvironment', padre=goo)
    fac = ea.elemento(dep, 'Servidor tecnoweb.org.bo 139.99.68.169 (mail, db)', 'Device')
    so_f = ea.elemento(dep, 'Linux', 'ExecutionEnvironment', padre=fac)
    pg = ea.elemento(dep, 'PostgreSQL 18.6 (5432)', 'ExecutionEnvironment', padre=so_f)
    bd = ea.elemento(dep, 'db_grupo05sa, esquema crm', 'Artifact', padre=pg)
    tablas_c = ea.elemento(dep, '10 tablas (crm_p1.sql)', 'Component', padre=bd)
    bitac_c = ea.elemento(dep, 'Bitácora (log_comando)', 'Component', padre=bd)
    dove = ea.elemento(dep, 'Dovecot (POP3 110)', 'ExecutionEnvironment', padre=so_f)
    smail = ea.elemento(dep, 'Sendmail (SMTP 25, MX tecnoweb.org.bo)', 'ExecutionEnvironment', padre=so_f)
    buzon = ea.elemento(dep, 'buzón grupo05sa', 'Artifact', 'file', padre=so_f)
    vps = ea.elemento(dep, 'VPS Contabo 164.68.96.50', 'Device')
    so_v = ea.elemento(dep, 'Fedora Linux 44', 'ExecutionEnvironment', padre=vps)
    unidad = ea.elemento(dep, '/etc/systemd/system/crm-mail.service', 'Artifact', 'file', padre=so_v)
    props = ea.elemento(dep, '/etc/crm-mail/crm-mail.properties', 'Artifact', 'file', padre=so_v)
    jvm = ea.elemento(dep, 'OpenJDK 25.0.4', 'ExecutionEnvironment', padre=so_v)
    jar = ea.elemento(dep, '/opt/crm-mail/crm-mail.jar', 'Artifact', 'executable', padre=jvm)
    capas_jar = [ea.elemento(dep, n, 'Component', padre=jar) for n in ('Presentación', 'Aplicación', 'Dominio', 'Persistencia')]
    for e, x, y, w, h, s_ in [(pc, 30, 450, 260, 160, 9), (cli, 50, 510, 220, 80, 1),
                              (fac, 380, 30, 520, 530, 9), (so_f, 400, 80, 480, 460, 8),
                              (pg, 420, 120, 440, 180, 2), (bd, 440, 170, 400, 115, 2), (tablas_c, 460, 215, 175, 55, 1), (bitac_c, 650, 215, 175, 55, 1),
                              (dove, 620, 320, 240, 70, 2), (smail, 620, 440, 240, 80, 2), (buzon, 420, 390, 170, 50, 1),
                              (vps, 980, 30, 600, 530, 9), (so_v, 1000, 80, 560, 460, 8),
                              (unidad, 1020, 110, 260, 50, 1), (props, 1300, 110, 240, 50, 1),
                              (jvm, 1020, 300, 520, 230, 3), (jar, 1040, 350, 480, 165, 2),
                              (capas_jar[0], 1060, 392, 210, 52, 1), (capas_jar[1], 1290, 392, 210, 52, 1),
                              (capas_jar[2], 1060, 455, 210, 52, 1), (capas_jar[3], 1290, 455, 210, 52, 1),
                              (goo, 380, 650, 1200, 150, 9), (gweb, 420, 705, 440, 70, 1), (gsmtp, 1100, 705, 440, 70, 1)]:
        ea.poner(d, e, x, y, w, h, s_)
    for a, b, t_ in [(cli, gweb, 'HTTPS 443'), (gweb, smail, 'SMTP 25'), (jvm, dove, 'POP3 110'),
                     (jvm, pg, 'JDBC TCP 5432'), (jvm, gsmtp, 'SMTP 587 STARTTLS')]:
        ea.conector(a, b, 'CommunicationPath', t_, dirigido=True)
    for a, b, t_ in [(unidad, jar, 'ExecStart'), (jar, props, 'lee'), (smail, buzon, 'entrega'), (dove, buzon, 'lee'), (gsmtp, gweb, 'entrega')]:
        ea.conector(a, b, 'Dependency', t_, dirigido=True)
    ea.exportar(d, 'despliegue.png')

    # ---------------------------------------------------------------- Diseño: capas
    cap = ea.paquete(dis, 'Capas')
    d = ea.diagrama(cap, 'Diagrama de capas', 'Package')
    capas = [('Capa de Presentación', 20, 270), ('Capa de Aplicación', 310, 150), ('Capa de Dominio', 480, 150), ('Capa de Persistencia', 650, 150)]
    for nombre, y, h in capas:
        # EA centra el nombre de un boundary, justo donde pasan las dependencias verticales: se rotula arriba a la izquierda
        ea.poner(d, ea.elemento(cap, '', 'Boundary'), 30, y, 1180, h, seq=50)
        ea.poner(d, ea.elemento(cap, nombre, 'Text', notas=nombre), 40, y + 6, 200, 20, seq=40)
    pk = {}
    W_ = 260
    sitio = {'App (servicio crm-mail)': (490, 60), 'Lector de Correos POP3 (Pop3Client)': (60, 180), 'Correo (MIME)': (360, 180),
             'Emisor SMTP (SmtpSender)': (700, 180), 'CommandProcessor y procesadores por rol': (490, 350),
             'DatabaseService (PostgreSQL)': (490, 690)}
    dominio = ['usuarios', 'captacion', 'asignacion', 'embudo', 'perdidas', 'conversion', 'seguimiento', 'reportes']
    for j, h in enumerate(dominio):
        sitio[h] = (45 + j * 145, 520)
    CPR = 'CommandProcessor y procesadores por rol'
    for h, (x, y) in sitio.items():
        pk[h] = ea.paquete(cap, h)
        ea.poner(d, pk[h], x - (20 if h == CPR else 0), y, 125 if h in dominio else (170 if h == 'Correo (MIME)' else (300 if h == CPR else W_)), 80, seq=1)
    APP, CPR, DBS = 'App (servicio crm-mail)', 'CommandProcessor y procesadores por rol', 'DatabaseService (PostgreSQL)'
    for b in ['Lector de Correos POP3 (Pop3Client)', 'Correo (MIME)', 'Emisor SMTP (SmtpSender)', CPR]:
        ea.conector(pk[APP], pk[b], 'Dependency', dirigido=True)
    ea.conector(pk[CPR], pk[DBS], 'Dependency', dirigido=True)
    for h in dominio:
        ea.conector(pk[CPR], pk[h], 'Dependency', dirigido=True)
        ea.conector(pk[h], pk[DBS], 'Dependency', dirigido=True)
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
    # La recepción (POP3, análisis, identidad del remitente) y el envío (SMTP) son iguales para todo comando: van una vez en
    # dos diagramas comunes. Cada CU muestra solo lo propio con 5 o 6 líneas de vida, como el P1 aprobado, sin fragmentos ref.
    seq = ea.paquete(dis, 'Secuencia')
    SLOT = 35.2   # alto que EA da a cada mensaje (medido en el PNG)
    Y1 = 133      # y del primer mensaje con las líneas de vida en y=40
    ROBUSTEZ = {'Pop3Client': 'boundary', 'SmtpSender': 'boundary', 'DatabaseService': 'entity'}

    def vida(clase_, campo):
        """Línea de vida con el nombre de la variable o del campo que la referencia en el código."""
        return ea.elemento(seq, f'{campo}:{clase_}', 'Object', ROBUSTEZ.get(clase_, 'control'))

    def lineas(d, cols, xs, alto):
        for e, x in zip(cols, xs):
            w = 60 if e.Type == 'Actor' else max(120, 8 * len(e.Name.split(':')[-1]))   # ancho del nombre de la clase
            ea.poner(d, e, x - w // 2, 50 if e.Stereotype == 'entity' else 40, w, int(alto))   # el icono entity es más bajo

    def ancho_msg(texto):
        return 30 + 4.4 * len(texto)   # medido en el PNG: ~4,4 unidades por carácter, más la punta de la flecha

    def args_msg(args, max_=24):
        """Parámetros reales; si no caben en la etiqueta, los primeros y '...'."""
        if len(args) <= max_: return args
        corto = ''
        for a in args.split(', '):
            if len(corto) + len(a) > max_ - 5: break
            corto += a + ', '
        return corto + '...'

    def cmd_msg(cmd, max_=30):
        """Comando con sus parámetros reales; si es largo, los primeros y '...' (la lista completa está en el Anexo A)."""
        if len(cmd) <= max_ or '[' not in cmd or cmd.endswith('[]'): return cmd
        nombre, ps = cmd.split('[', 1)
        corto = []
        for x in ps.rstrip(']').split(','):
            if x == '...': continue
            if corto and len(nombre) + len(','.join(corto + [x])) > max_ - 6: break   # al menos el primero
            corto.append(x)
        return f"{nombre}[{','.join(corto + ['...'])}]"

    def xs_de(cols, etiquetas):
        """Centro de cada línea de vida: lo justo para la etiqueta más larga entre cada par de columnas vecinas."""
        xs = [80]
        for i in range(1, len(cols)):
            gap = 110 if cols[i].Type == 'Actor' else max([130] + [ancho_msg(t) for (a, b), t in etiquetas if {a, b} == {i - 1, i}])
            xs.append(int(xs[-1] + gap))
        return xs

    rem = 'Remitente'
    campo_c = {'Pop3Client': 'pop', 'SmtpSender': 'smtp', 'App': '', 'CommandProcessor': 'procesador', 'CommandParser': 'parser',
               'UsuarioService': 'usuarios'}
    for titulo, archivo, nombres, pasos_f in [
        ('Recibir comando', 'sd-recibir.png', ['Pop3Client', 'App', 'CommandProcessor', 'CommandParser', 'UsuarioService'],
         lambda o: [(o[rem], o['Pop3Client'], '1. COMANDO[...]', 'signal', ''),
                    (o['App'], o['Pop3Client'], '1.1 retr', 'call', 'n'),
                    (o['Pop3Client'], o['App'], 'byte[]', 'return', ''),
                    (o['App'], o['CommandProcessor'], '1.2 procesarEmail', 'call', firma('CommandProcessor', 'procesarEmail')),
                    (o['CommandProcessor'], o['CommandParser'], '1.3 parse', 'call', firma('CommandParser', 'parse')),
                    (o['CommandParser'], o['CommandProcessor'], 'Command', 'return', ''),
                    (o['CommandProcessor'], o['UsuarioService'], '1.4 buscarPorCorreo', 'call', firma('UsuarioService', 'buscarPorCorreo')),
                    (o['UsuarioService'], o['CommandProcessor'], 'Usuario', 'return', '')]),
        ('Registrar y responder', 'sd-responder.png', ['App', 'CommandProcessor', 'SmtpSender'],
         lambda o: [(o['CommandProcessor'], o['CommandProcessor'], '1. bitacora', 'call', firma('CommandProcessor', 'bitacora')),
                    (o['CommandProcessor'], o['App'], 'Respuesta', 'return', ''),
                    (o['App'], o['SmtpSender'], '2. enviar', 'call', firma('SmtpSender', 'enviar')),
                    (o['SmtpSender'], o[rem], '3. respuesta HTML', 'signal', '')])]:
        d = ea.diagrama(seq, titulo, 'Sequence')
        d.StyleEx = 'SuppressBrackets=1;'; d.Update()
        o = {rem: ea.elemento(seq, rem, 'Actor')} | {n: vida(n, campo_c[n]) for n in nombres}
        cols = list(o.values())
        pasos = pasos_f(o)
        idx = {e.ElementID: i for i, e in enumerate(cols)}
        etiq = [((idx[a.ElementID], idx[b.ElementID]), t + (f'({args})' if args else '')) for a, b, t, _, args in pasos]
        xs = xs_de(cols, etiq)
        if titulo == 'Registrar y responder':   # la etiqueta del self-message bitacora() se extiende a la derecha
            xs[3] += 260
        lineas(d, cols, xs, Y1 + len(pasos) * SLOT + 60)
        for n, (a, b, t, tipo, args) in enumerate(pasos, 1):
            ea.mensaje_seq(d, a, b, t, n, tipo, args)
        ea.exportar(d, archivo)

    for k in CU:
        partes = [OPS[k][i:i + 2] for i in range(0, len(OPS[k]), 2)]   # 2 comandos por figura
        for p_, ops in enumerate(partes, 1):
            nombre_d = f'{k}: {CORTO[k]}' + (f' ({p_}/{len(partes)})' if len(partes) > 1 else '')
            d = ea.diagrama(seq, nombre_d, 'Sequence')
            d.StyleEx = 'SuppressBrackets=1;'; d.Update()   # los paréntesis los ponen los argumentos reales
            acts = list(dict.fromkeys(o[0] for o in ops))
            sv = SERVICIO[k]
            vidas = {a: ea.elemento(seq, a, 'Actor') for a in acts}
            obj = {'CommandProcessor': vida('CommandProcessor', 'procesador'), 'RolCommandProcessor': vida('RolCommandProcessor', ''),
                   sv: vida(sv, CAMPO[sv]), 'DatabaseService': vida('DatabaseService', 'db')}
            cols = list(vidas.values()) + list(obj.values())
            iP = len(acts)                                       # columna de procesador
            # Sin ref: la recepción (POP3, parse, remitente) y el envío (SMTP) están en sd-recibir y sd-responder. Aquí cada comando
            # termina con lo que hace procesador en procesarEmail(): bitacora() y la respuesta HTML que le llega al actor por correo.
            pasos, tam, etiq = [], [], []
            arg_b = args_msg(firma('CommandProcessor', 'bitacora'))
            for j, (a, cmd, met, dbm) in enumerate(ops, (p_ - 1) * 2 + 1):
                ini_op = len(pasos)
                arg_s, arg_d = args_msg(firma(sv, met[:-2])), args_msg(firma('DatabaseService', dbm[:-2]))
                pasos += [(vidas[a], obj['CommandProcessor'], f'{j}. {cmd_msg(cmd)}', 'signal', ''),
                          (obj['CommandProcessor'], obj['RolCommandProcessor'], f'{j}.1 procesar', 'call', firma('RolCommandProcessor', 'procesar')),
                          (obj['RolCommandProcessor'], obj[sv], f'{j}.2 {met[:-2]}', 'call', arg_s or ' '),
                          (obj[sv], obj['DatabaseService'], f'{j}.3 {dbm[:-2]}', 'call', arg_d or ' '),
                          (obj['DatabaseService'], obj[sv], tipo_bd(sv, met, dbm), 'return', ''),
                          (obj[sv], obj['RolCommandProcessor'], retorno(sv, met[:-2]), 'return', ''),
                          (obj['RolCommandProcessor'], obj['CommandProcessor'], 'Respuesta', 'return', ''),
                          (obj['CommandProcessor'], obj['CommandProcessor'], f'{j}.4 bitacora', 'call', arg_b),
                          (obj['CommandProcessor'], vidas[a], f'{j}.5 respuesta HTML', 'signal', '')]
                tam.append((len(pasos) - ini_op) * SLOT)
                etiq += [((iP, iP + 1), f'{j}.1 procesar(cmd, u)'), ((iP + 1, iP + 2), f'{j}.2 {met[:-2]}({arg_s})'),
                         ((iP + 2, iP + 3), f'{j}.3 {dbm[:-2]}({arg_d})'), ((iP + 1, iP + 2), retorno(sv, met[:-2])),
                         ((iP + 2, iP + 3), tipo_bd(sv, met, dbm)), ((iP, iP + 1), f'{j}.4 bitacora({arg_b})   ')]
            xs = xs_de(cols, etiq)
            # el comando va del actor a procesador: su etiqueta debe caber entre el primer actor y procesador
            ancho_cmd = max(ancho_msg(f'{j}. {cmd_msg(cmd)}') for j, (a, cmd, *_) in enumerate(ops, 1))
            extra = max(0, int(ancho_cmd - (xs[iP] - xs[0])))
            xs = xs[:iP] + [x + extra for x in xs[iP:]]
            if len(ops) > 1: xs = [x + 90 for x in xs]          # margen izquierdo para la etiqueta alt y las guardas
            lineas(d, cols, xs, Y1 + len(pasos) * SLOT + 60)
            for n, (o, dst, txt, tipo, args) in enumerate(pasos, 1):
                ea.mensaje_seq(d, o, dst, txt, n, tipo, args)
            if len(ops) == 1:   # un solo comando: sin fragmento alt (un alt de un operando no tiene alternativas)
                ea.exportar(d, f'sd-{k.lower()}' + (f'-{p_}' if len(partes) > 1 else '') + '.png')
                continue
            fr = ea.elemento(seq, 'comando recibido', 'InteractionFragment'); fr.Subtype = 0; fr.Update()
            # La API de EA 15 falla con más de dos operandos: se crean dos y se escribe la lista completa en t_xref.
            for _ in range(2):
                part = fr.Partitions.AddNew('x', ''); part.Size = 100
            fr.Update()
            pars = ''.join(f"@PAR;Name={cmd.split('[')[0]};Size={int(t) - 14};GUID={{{str(uuid.uuid4()).upper()}}};@ENDPAR;"
                           for (a, cmd, *_), t in reversed(list(zip(ops, tam))))
            ea.r.Execute(f"UPDATE t_xref SET Description='{pars}' WHERE Client='{fr.ElementGUID}' AND Name='Partitions'")
            ea.poner(d, fr, 15, 120, xs[-1] + 90, int(sum(tam)) + 35, seq=60)
            ea.exportar(d, f'sd-{k.lower()}' + (f'-{p_}' if len(partes) > 1 else '') + '.png')

    # ---------------------------------------------------------------- Diseño: procedimental por CU (como el P1 aprobado)
    # Un diagrama de clases por CU con CommandProcessor, CommandParser, Command, ResponseFormatter, el procesador de cada rol
    # del CU, los servicios que intervienen, las entidades y DatabaseService; todas con sus miembros y unidas por dependencias.
    # Única diferencia con el aprobado: aquí procesar() está en RolCommandProcessor y cada rol la hereda (extends), y se dibuja.
    hechos = set()

    def dep(a, b):
        if (a, b) not in hechos:
            hechos.add((a, b)); ea.conector(cls[a], cls[b], 'Dependency', dirigido=True)

    for a, b in [('CommandProcessor', 'CommandParser'), ('CommandProcessor', 'Command'), ('CommandProcessor', 'ResponseFormatter'),
                 ('CommandProcessor', 'RolCommandProcessor'), ('CommandProcessor', 'UsuarioService'), ('CommandProcessor', 'SeguimientoService'),
                 ('CommandParser', 'Command'), ('RolCommandProcessor', 'Command'), ('RolCommandProcessor', 'ResponseFormatter'),
                 ('UsuarioService', 'Usuario'), ('Usuario', 'Rol'), ('Interes', 'Prospecto'), ('Alta', 'Interes'),
                 ('Asignacion', 'Interes'), ('Asignacion', 'Usuario'), ('Historial', 'Interes')]:
        dep(a, b)
    for k in CU:
        dep('RolCommandProcessor', SERVICIO[k]); dep(SERVICIO[k], 'DatabaseService')
        for e in ENTIDADES_CU[k]: dep(SERVICIO[k], e)
    dep('UsuarioService', 'DatabaseService'); dep('SeguimientoService', 'DatabaseService')

    def alto(nombre):
        e = cls[nombre]; e.Attributes.Refresh(); e.Methods.Refresh()   # EA no actualiza Count sin Refresh
        n = e.Attributes.Count + e.Methods.Count
        return 44 + 14 * n + (10 if e.Attributes.Count and e.Methods.Count else 0) + (16 if e.Stereotype else 0)   # medido en el PNG

    def ancho(nombre):
        """Ancho justo para el miembro más largo (medido: ~6,3 unidades por carácter en Calibri 8)."""
        e = cls[nombre]; e.Attributes.Refresh(); e.Methods.Refresh()   # EA no carga las colecciones sin Refresh
        textos = [e.Name] + [f'{a.Name}: {a.Type}' for a in e.Attributes]
        textos += [f'{m.Name}({", ".join(pa.Type for pa in m.Parameters)}): {m.ReturnType}' for m in e.Methods]
        return int(50 + 6.3 * max(len(t) for t in textos))

    for k in CU:
        d = ea.diagrama(cls_pkg, f'{k}_' + re.sub(r'\W+', '_', CORTO[k]).strip('_'), 'Logical')
        # Grilla fija como el P1 aprobado (clases relacionadas lado a lado, flechas cortas y rectas):
        #   fila A:  CommandParser | CommandProcessor  | UsuarioService | DatabaseService
        #   fila B:  Command       | RolCommandProcessor | servicio del CU | entidades (Usuario arriba)
        #   fila C:  ResponseFormatter
        #   fila D:  procesadores de cada rol, bajo RolCommandProcessor (la herencia sube sin cruzar nada)
        sv = SERVICIO[k]
        ORDEN = {'CU1': ['Usuario', 'Rol'], 'CU2': ['Usuario', 'Oferta', 'Alta', 'Interes', 'Prospecto'],
                 'CU3': ['Usuario', 'Interes', 'Asignacion'], 'CU4': ['Usuario', 'Interes'], 'CU5': ['Usuario', 'MotivoPerdida', 'Interes'],
                 'CU6': ['Usuario', 'Matricula', 'Requisito', 'Interes'], 'CU7': ['Usuario', 'Interaccion', 'Recordatorio', 'Historial'],
                 'CU8': ['Usuario']}
        ents = ORDEN[k]
        assert set(ents) == set(ENTIDADES_CU[k] + ['Usuario'] + (['Rol'] if k == 'CU1' else [])), k
        roles = [PROC_ROL[a] for a in actores(k)]
        filaB2 = [sv] if sv != 'UsuarioService' else []
        G = 90                                                   # separación entre columnas y filas
        w0 = max(ancho(n) for n in ('CommandParser', 'Command', 'ResponseFormatter'))
        w1 = max(ancho(n) for n in ('CommandProcessor', 'RolCommandProcessor'))
        w2 = max(ancho(n) for n in ['UsuarioService'] + filaB2)
        w3 = max(ancho(n) for n in ['DatabaseService'] + ents)
        x0, x1 = 30, 30 + w0 + G
        x2 = x1 + w1 + G; x3 = x2 + w2 + G
        yA = 30
        yB = yA + max(alto(n) for n in ('CommandParser', 'CommandProcessor', 'UsuarioService', 'DatabaseService')) + G
        yC = yB + alto('Command') + G
        sitio = {'CommandParser': (x0, yA), 'CommandProcessor': (x1, yA), 'UsuarioService': (x2, yA), 'DatabaseService': (x3, yA),
                 'Command': (x0, yB), 'RolCommandProcessor': (x1, yB), 'ResponseFormatter': (x0, yC)}
        if filaB2: sitio[sv] = (x2, yB)
        # Usuario (y Rol) bajo DatabaseService, junto a UsuarioService; las entidades del CU en una fila bajo su servicio,
        # en orden de dependencia (cada una al lado de la que usa): las flechas bajan o van de lado sin atravesar clases.
        y = yB
        for e in [e for e in ents if e in ('Usuario', 'Rol')]:
            sitio[e] = (x3, y); y += alto(e) + 50
        fila = [e for e in ents if e not in ('Usuario', 'Rol')]
        yE = yB + max([alto(n) for n in filaB2] + [0]) + G
        if any(sitio[e][1] + alto(e) > yE - 20 and True for e in ents if e in sitio):   # que la fila pase bajo Usuario
            yE = max(yE, max(sitio[e][1] + alto(e) for e in ents if e in sitio) + 50) if len(fila) > 2 else yE
        xe = x2
        for e in fila:
            sitio[e] = (xe, yE); xe += ancho(e) + 50
        wr = max(ancho(r) for r in roles)
        ancho_fila = len(roles) * (wr + 30) - 30
        xr = max(30, x1 + w1 // 2 - ancho_fila // 2)          # centrada bajo RolCommandProcessor
        # justo debajo de lo que tenga encima (solo las cajas que comparten franja horizontal con la fila)
        fondo = max(y_ + alto(n) for n, (x_, y_) in sitio.items() if x_ < xr + ancho_fila and x_ + ancho(n) > xr)
        for i, r_ in enumerate(roles):
            sitio[r_] = (xr + i * (wr + 30), fondo + G)
        for nombre, (x, y_) in sitio.items():
            ea.poner(d, cls[nombre], x, y_, ancho(nombre), alto(nombre))
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
    # usuario arriba e interes abajo; las tablas que referencian a ambos van en la fila del medio (sin cruces),
    # con un hueco central para la asociación directa interes.vendedor_id -> usuario.
    for nombre, (x, y) in {'usuario': (715, 30), 'log_comando': (1150, 30),
                           'prospecto': (30, 330), 'oferta': (270, 330), 'matricula': (600, 330),
                           'movimiento': (900, 330), 'interaccion': (1150, 330), 'recordatorio': (1400, 330),
                           'interes': (715, 660), 'motivo_perdida': (1150, 660)}.items():
        ea.poner(d, tablas[nombre], x, y, 220, 175)
    unicos = {('matricula', 'interes')}
    for hijo, padre, obligatorio, col_ in fks:
        mult_hijo = '0..1' if (hijo, padre) in unicos else '0..*'
        ea.conector(tablas[hijo], tablas[padre], 'Association', col_, card=(mult_hijo, '1' if obligatorio else '0..1'))
    ea.exportar(d, 'clases-datos.png')

    # ---------------------------------------------------------------- Implementación: componentes
    # Capas como componentes contenedores (como el P2 aprobado), interfaces provistas/requeridas con conectores de
    # ensamblaje hacia los servicios externos y el artefacto ejecutable que manifiesta las capas.
    d = ea.diagrama(imp, 'Diagrama de componentes', 'Component')

    def comp(nombre, st='', padre=None):
        return ea.elemento(imp, nombre, 'Component', st, padre=padre)

    c_sis = comp('Sistema CRM vía e-mail', 'subsystem')
    c_pre, c_apl, c_dom, c_per = (comp(n, padre=c_sis) for n in ('Capa de Presentación', 'Capa de Aplicación', 'Capa de Dominio', 'Capa de Persistencia'))
    app_c = comp('App (bucle del servicio)', padre=c_pre)
    lector = comp('Lector de correo (Pop3Client, Correo)', padre=c_pre)
    emisor = comp('Emisor SMTP (SmtpSender)', padre=c_pre)
    proc = comp('Procesador de comandos (CommandProcessor, CommandParser)', padre=c_apl)
    porrol = comp('Procesadores por rol (RolCommandProcessor y 4 subclases)', padre=c_apl)
    fmt_c = comp('Formateador (ResponseFormatter)', padre=c_apl)
    servs_c = [comp(SERVICIO[k], 'service', padre=c_dom) for k in CU]
    dbs = comp('DatabaseService', padre=c_per)
    angus = comp('Angus Mail 2.0.5', 'library')
    pgj = comp('PostgreSQL JDBC 42.7.13', 'library')
    dov = comp('Dovecot mail.tecnoweb.org.bo', 'system extern')
    gm = comp('Gmail smtp.gmail.com', 'system extern')
    pgc = comp('PostgreSQL 18.6 db.tecnoweb.org.bo', 'Database')
    jar_a = ea.elemento(imp, 'crm-mail.jar', 'Artifact', 'executable')
    i_pop = ea.elemento(imp, 'POP3 (RFC 1939)', 'Interface')
    i_mail = ea.elemento(imp, 'Jakarta Mail API', 'Interface')
    i_smtp = ea.elemento(imp, 'SMTP (RFC 5321) + STARTTLS', 'Interface')
    i_jdbc = ea.elemento(imp, 'JDBC (java.sql)', 'Interface')
    i_pgp = ea.elemento(imp, 'Protocolo PostgreSQL v3', 'Interface')
    # interfaces expuestas: (componente, nombre, tipo, clasificador, x, y)
    expuestas = [(lector, ' ', 'RequiredInterface', i_pop, 932, 82), (dov, 'POP3 :110', 'ProvidedInterface', i_pop, 1452, 82),
                 (emisor, ' ', 'RequiredInterface', i_mail, 932, 192), (angus, 'Jakarta Mail API', 'ProvidedInterface', i_mail, 1082, 192),
                 (angus, ' ', 'RequiredInterface', i_smtp, 1282, 192), (gm, 'SMTP :587', 'ProvidedInterface', i_smtp, 1452, 192),
                 (dbs, ' ', 'RequiredInterface', i_jdbc, 732, 862), (pgj, 'JDBC', 'ProvidedInterface', i_jdbc, 1082, 862),
                 (pgj, ' ', 'RequiredInterface', i_pgp, 1282, 862), (pgc, 'PostgreSQL v3 :5432', 'ProvidedInterface', i_pgp, 1452, 862)]
    for e, x, y, w, h, s_ in [(c_sis, 255, -50, 770, 990, 10),
                              (c_pre, 280, 20, 720, 250, 9), (app_c, 310, 90, 190, 80, 1), (lector, 640, 55, 300, 70, 1), (emisor, 640, 165, 300, 70, 1),
                              (c_apl, 280, 300, 720, 230, 9), (proc, 300, 355, 240, 70, 1), (porrol, 760, 355, 225, 70, 1), (fmt_c, 560, 455, 200, 60, 1),
                              (c_dom, 280, 560, 720, 200, 9), (c_per, 280, 800, 720, 120, 9), (dbs, 540, 835, 200, 60, 1),
                              (angus, 1090, 165, 200, 70, 1), (pgj, 1090, 830, 200, 70, 1),
                              (dov, 1460, 45, 260, 80, 1), (gm, 1460, 155, 260, 80, 1), (pgc, 1460, 820, 260, 90, 1),
                              (jar_a, 20, 430, 190, 70, 1)]:
        ea.poner(d, e, x, y + 60, w, h, s_)   # +60: cabecera del subsistema contenedor
    for i, sv in enumerate(servs_c):
        ea.poner(d, sv, 300 + (i % 4) * 172, 665 + (i // 4) * 75, 160, 60, 1)
    pts = {}
    for e, n_, t_, cl, x, y in expuestas:
        pts[(e.ElementID, t_)] = ie = ea.interfaz(e, n_, t_, cl)
        ea.poner(d, ie, x, y + 60, 16, 16, 0)
    for ra, pa, nombre in [(lector, dov, 'sockets TCP 110'), (emisor, angus, ''), (angus, gm, 'STARTTLS'), (dbs, pgj, ''), (pgj, pgc, 'TCP')]:
        c = pts[(ra.ElementID, 'RequiredInterface')].Connectors.AddNew(nombre, 'Assembly')
        c.SupplierID = pts[(pa.ElementID, 'ProvidedInterface')].ElementID; c.Update()
    for a_, b_, t_ in [(app_c, lector, 'retr()'), (app_c, emisor, 'enviar()'), (app_c, proc, 'procesarEmail()'), (app_c, fmt_c, 'html()'),
                       (proc, porrol, 'procesar()'), (proc, fmt_c, ''), (porrol, fmt_c, ''), (porrol, c_dom, 'invoca el servicio del CU'),
                       (c_dom, dbs, 'enTransaccion(), lista(), filas()')]:
        ea.conector(a_, b_, 'Dependency', t_, dirigido=True)
    ea.conector(jar_a, c_sis, 'Manifest', dirigido=True)
    ea.exportar(d, 'componentes.png')


if __name__ == '__main__':
    main()
