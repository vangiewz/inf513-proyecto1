"""Construye en Enterprise Architect (COM, EA.Repository) los diagramas UML del Proyecto 1 y los exporta a PNG y EMF.
Uso: python p1/doc/ea/ea_p1.py  ->  p1/doc/ea/crm-p1.eapx y p1/doc/diagramas/*.png
Todo nombre de clase, método, comando, host y puerto sale del código de p1/ o de lo verificado en los servidores
(lista y criterio en P1_CORREO.md § Diagramas)."""
import os, pathlib, re, shutil, sys, uuid
import win32com.client

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import firmas  # noqa: E402

AQUI = pathlib.Path(__file__).resolve().parent
P1 = AQUI.parents[1]
SALIDA = AQUI.parent / 'diagramas'
EAPX = AQUI / 'crm-p1.eapx'
BASE = pathlib.Path(r'C:/Program Files (x86)/Sparx Systems/EA Trial/EABase.eapx')
SQL = (P1 / 'sql' / 'esquema.sql').read_text(encoding='utf-8')

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
        for i, p in enumerate(firmas._partir(ps)):
            pn, pt = p.split(':', 1)
            pa = mt.Parameters.AddNew(pn.strip(), pt.strip()); pa.Position = i; pa.Update()
        mt.Update()
    e.Update(); e.Refresh() if hasattr(e, 'Refresh') else None
    return e


# ------------------------------------------------------------------------------------------------ datos del sistema
# Todo sale del código: firmas.py lee p1/src (clases, métodos, despacho de comandos y operación de BD de cada método),
# la matriz de acceso sale de crm-mail.jar --anexo y el modelo de datos de sql/esquema.sql.
CU = {'CU1': 'Gestión de Usuarios (Propietario, Clientes)', 'CU2': 'Gestión de Captar, Consumir Oferta',
      'CU3': 'Gestión de Asignar Prospectos (Leads)', 'CU4': 'Gestión de Embudo de Ventas en Tablero Kanban',
      'CU5': 'Gestión de Pérdida de Prospectos y Clasificación de Motivos',
      'CU6': 'Gestión de Validar Datos y Procesar Conversión a Matrícula',
      'CU7': 'Gestión de Bitácora, Interacciones y Recordatorios', 'CU8': 'Reportes y Estadísticas'}
CORTO = {'CU1': 'Gestión de Usuarios', 'CU2': 'Gestión de Captar, Consumir Oferta', 'CU3': 'Gestión de Asignar Prospectos',
         'CU4': 'Gestión de Embudo de Ventas en Tablero Kanban', 'CU5': 'Gestión de Pérdida de Prospectos',
         'CU6': 'Gestión de Validar Datos y Conversión a Matrícula', 'CU7': 'Gestión de Bitácora, Interacciones y Recordatorios',
         'CU8': 'Reportes y Estadísticas'}
# Matriz de acceso (doc/matriz-acceso.tsv, generada por crm-mail.jar --anexo): CU, firma, admin, coord, vend, público, ejemplo
MATRIZ = [l.split('\t') for l in (P1 / 'doc' / 'matriz-acceso.tsv').read_text(encoding='utf-8').splitlines()[1:]]
ROLES = ['Administrador', 'Coordinador', 'Vendedor', 'Visitante']
COL = {'Administrador': 2, 'Coordinador': 3, 'Vendedor': 4, 'Visitante': 5}


def actores(cu):
    return [n for n in ROLES if any(f[0] == cu and f[COL[n]] == 'Sí' for f in MATRIZ)]


SERVICIOS = {'CU1': ['UsuarioService'], 'CU2': ['ProspectoService', 'ProgramaService'], 'CU3': ['AsignacionService'],
             'CU4': ['EmbudoService'], 'CU5': ['PerdidaService'], 'CU6': ['ConversionService', 'PagoService', 'ExportacionService'],
             'CU7': ['SeguimientoService'], 'CU8': ['ReporteService']}
CAMPO = {s: c for c, s in firmas.CAMPOS.items()} | {'ProgramaService': ''}   # nombre del campo que referencia al servicio
LLAMADAS = firmas.llamadas()


def comandos(cu):
    """[(COMANDO, actor que lo muestra, [(Servicio, método, operación de BD)])] en el orden de la matriz."""
    out = []
    for f in MATRIZ:
        if f[0] != cu: continue
        k = f[1].split('[')[0]
        actor = next(a for a in ('Visitante', 'Vendedor', 'Coordinador', 'Administrador') if f[COL[a]] == 'Sí')
        pasos = []
        for _, s, m in LLAMADAS[k]:
            d = firmas.delega(s, m)
            pasos.append((s, m, firmas.bd(s, m), d))
        out.append((k, actor, pasos))
    return out


ENTIDADES_CU = {'CU1': ['Usuario', 'Rol'], 'CU2': ['Oferta', 'Prospecto', 'Interes', 'ProspectoService.Alta'],
                'CU3': ['Interes', 'AsignacionService.Asignacion'], 'CU4': ['Interes'], 'CU5': ['MotivoPerdida', 'Interes'],
                'CU6': ['Matricula', 'ConversionService.Requisito', 'PagoService.Orden', 'PagoService.Comprobante',
                        'ExportacionService.Preparacion', 'Interes'],
                'CU7': ['Interaccion', 'Recordatorio', 'SeguimientoService.Historial'], 'CU8': []}
NUCLEO = ['Command', 'CommandParser', 'CommandProcessor', 'RolCommandProcessor', 'AdministradorCommandProcessor',
          'CoordinadorCommandProcessor', 'VendedorCommandProcessor', 'PublicoCommandProcessor', 'ResponseFormatter',
          'DatabaseService', 'Usuario', 'Rol']
PROC_ROL = {'Administrador': 'AdministradorCommandProcessor', 'Coordinador': 'CoordinadorCommandProcessor',
            'Vendedor': 'VendedorCommandProcessor', 'Visitante': 'PublicoCommandProcessor'}
SPEC = {}


def spec(n):
    if n not in SPEC: SPEC[n] = firmas.spec(n)
    return SPEC[n]


def firma(clase_, metodo):
    """Nombres de los parámetros del método, como en el código."""
    ps = re.search(r'[+#~-]\$?' + metodo + r'\(([^)]*)\)', spec(clase_)).group(1)
    return ', '.join(x.split(':')[0].strip() for x in firmas._partir(ps))


def retorno(clase_, metodo):
    ret = re.search(r'[+#~-]\$?' + metodo + r'\([^)]*\):\s*([^;|]+)', spec(clase_)).group(1).strip()
    return '' if ret == 'void' else ret


def simple(n):
    return n.split('.')[-1]


def args_msg(args, max_=26):
    """Parámetros reales; si no caben en la etiqueta, los primeros y '...'."""
    if len(args) <= max_: return args
    corto = ''
    for a in args.split(', '):
        if len(corto) + len(a) > max_ - 5: break
        corto += a + ', '
    return corto + '...'


def main():
    SALIDA.mkdir(parents=True, exist_ok=True)
    for viejo in SALIDA.glob('sd-cu*-*.*'): viejo.unlink()   # las secuencias partidas en varias figuras ya no se usan
    ea = EA()
    try:
        construir(ea)
        malos = sueltos(ea)
    finally:
        ea.cerrar()
    print(f'{len(ea.exportados)} diagramas en {SALIDA}')
    print('Elementos sin relación en su diagrama:', malos or 'ninguno')


def sueltos(ea):
    """Ningún elemento de un diagrama (salvo marcos, rótulos y paquetes) sin una relación con otro elemento del mismo
    diagrama, ni contenido en otro. Las secuencias se omiten: sus líneas de vida se unen por mensajes."""
    malos = []

    def recorrer(p):
        for d in p.Diagrams:
            if d.Type == 'Sequence': continue
            ids = {o.ElementID for o in d.DiagramObjects}
            padres = {ea.r.GetElementByID(i).ParentID for i in ids}
            for i in ids:
                e = ea.r.GetElementByID(i)
                if e.Type in ('Boundary', 'Text', 'InteractionFragment', 'Note', 'Package'): continue
                otros = {c.SupplierID if c.ClientID == i else c.ClientID for c in e.Connectors}
                if otros & ids or e.ParentID in ids or i in padres: continue
                malos.append(f'{d.Name}: {e.Name}')
        for q in p.Packages: recorrer(q)

    recorrer(ea.modelo)
    return malos


def construir(ea):
    m = ea.modelo
    req, ana, dis, imp = (ea.paquete(m, n) for n in ('1. Requisitos', '2. Análisis', '3. Diseño', '4. Implementación'))

    # ---------------------------------------------------------------- Requisitos: casos de uso
    act = {n: ea.elemento(req, n, 'Actor') for n in ROLES}
    uc = {k: ea.elemento(req, f'{k}: {v}', 'UseCase') for k, v in CU.items()}
    for k in CU:
        for a in actores(k):
            ea.conector(act[a], uc[k], 'Association')
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
    # Sin generalización entre actores (el docente no generaliza en requisitos) y como en el código, donde los
    # procesadores de cada rol son hermanos bajo RolCommandProcessor.
    ea.poner(d, act['Administrador'], 60, 300, 45, 80); ea.poner(d, act['Coordinador'], 60, 470, 45, 80)
    ea.poner(d, act['Vendedor'], 1200, 470, 45, 80); ea.poner(d, act['Visitante'], 1200, 140, 45, 80)
    ea.exportar(d, 'uc-modelo.png')

    # ---------------------------------------------------------------- Análisis: paquetes
    PAQ = [('Administración', ['CU1']), ('Captación y Asignación', ['CU2', 'CU3']),
           ('Gestión del Embudo', ['CU4', 'CU5', 'CU7']), ('Conversión y Reportes', ['CU6', 'CU8'])]
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
        cus = sorted(cus, key=lambda k: len(actores(k)))
        for j, k in enumerate(cus):
            ea.poner(d, uc[k], 360, 75 + j * 115 + (filas - len(cus)) * 57, 230, 90, seq=1)
        for tr in traces:   # contención y asociaciones directas de cada actor, no la dependencia «trace»
            dl = d.DiagramLinks.AddNew('', ''); dl.ConnectorID = tr.ConnectorID; dl.IsHidden = True; dl.Update()
        ea.exportar(d, f'pkg-encap-{i}.png')

    # ---------------------------------------------------------------- Análisis: colaboración por CU
    col = ea.paquete(ana, 'Colaboración')
    bnd = ea.elemento(col, 'Cliente de correo', 'Object', 'boundary')
    cp = ea.elemento(col, 'CommandProcessor', 'Object', 'control')
    rcp = ea.elemento(col, 'RolCommandProcessor', 'Object', 'control')
    servs = {s: ea.elemento(col, s, 'Object', 'control') for ss in SERVICIOS.values() for s in ss}
    dbo = ea.elemento(col, 'DatabaseService', 'Object', 'entity')
    act_col = {n: ea.elemento(col, n, 'Actor') for n in ROLES}
    enlaces = {}

    def enlace(a, b):
        if (a.ElementID, b.ElementID) not in enlaces:
            enlaces[(a.ElementID, b.ElementID)] = ea.conector(a, b, 'Association')
        return enlaces[(a.ElementID, b.ElementID)]

    for k in CU:
        d = ea.diagrama(col, f'Colaboración {k}: {CORTO[k]}', 'Collaboration')
        d.StyleEx = 'SuppressBrackets=1;'; d.Update()
        cmds = comandos(k)
        acts = list(dict.fromkeys(a for _, a, _ in cmds))
        sv = SERVICIOS[k]
        n_ = len(cmds)
        # Una fila: actores (en columna) → cliente de correo → CommandProcessor → RolCommandProcessor → servicios (en
        # columna) → DatabaseService. Las etiquetas se apilan sobre cada enlace: las separaciones salen de cuántas lleva.
        msgs = []
        for j, (cmd, a, pasos) in enumerate(cmds, 1):
            msgs.append((j, act_col[a], bnd, cmd + '[...]', f'{j}', ''))
            msgs.append((j, bnd, cp, 'procesarEmail', f'{j}.1', '...'))
            msgs.append((j, cp, rcp, 'procesar', f'{j}.2', firma('RolCommandProcessor', 'procesar')))
            sub = 3
            for s_, met, dbm, dlg in pasos:
                msgs.append((j, rcp, servs[s_], met, f'{j}.{sub}', '...' if firma(s_, met) else ' ')); sub += 1
                origen = s_
                if dlg:
                    msgs.append((j, servs[s_], servs[dlg[0]], dlg[1], f'{j}.{sub}', '...')); sub += 1; origen = dlg[0]
                if dbm:
                    msgs.append((j, servs[origen], dbo, dbm, f'{j}.{sub}', '...')); sub += 1
        cuenta = {}
        for _, o, dst, *_ in msgs:
            cuenta[o.ElementID, dst.ElementID] = cuenta.get((o.ElementID, dst.ElementID), 0) + 1
        pila = lambda o, dst: 15 * cuenta.get((o.ElementID, dst.ElementID), 0)
        DX, H = 290, 60
        pa = max([pila(act_col[a], bnd) for a in acts])
        ps = max([pila(rcp, servs[s_]) for s_ in sv] + [pila(servs[s_], dbo) for s_ in sv] + [15])
        da, ds = max(170, 2 * pa + 70), max(170, 2 * ps + 70)   # la diagonal vecina apila desde su punto medio
        Yc = 0
        ya = [Yc + (i - (len(acts) - 1) / 2) * da for i in range(len(acts))]
        ys = [Yc + (i - (len(sv) - 1) / 2) * ds for i in range(len(sv))]
        tope = min([Yc - pila(bnd, cp), Yc - pila(cp, rcp)] + [y - pa for y in ya] + [y - ps for y in ys])
        off = 70 - tope
        x1 = 330; x2, x3 = x1 + DX, x1 + 2 * DX; x4 = x3 + DX + 40; x5 = x4 + DX + 40
        for a, y in zip(acts, ya):
            ea.poner(d, act_col[a], 40, int(y + off) - 10, 45, 80)
        for e, x, y in [(bnd, x1, Yc), (cp, x2, Yc), (rcp, x3, Yc), (dbo, x5, Yc)] + [(servs[s_], x4, y) for s_, y in zip(sv, ys)]:
            ea.poner(d, e, x, int(y + off), H, H)
        apil, n = {}, 0
        for j, o, dst, txt, num, args in msgs:
            ln = enlace(o, dst); n += 1
            c = apil.get(ln.ConnectorID, 0); apil[ln.ConnectorID] = c + 1
            ea.mensaje_com(d, ln, o, dst, txt, num, n, c, COLORES[(j - 1) % 4], args)
        ea.exportar(d, f'com-{k.lower()}.png')

    d = ea.diagrama(req, 'Dependencia de paquetes', 'Package')
    for i, p in enumerate(paqs):
        ea.poner(d, p, [40, 330, 330, 620][i], [190, 40, 340, 190][i], 190, 110)
    for a, b in [(1, 0), (2, 0), (3, 0), (3, 2), (2, 1)]:
        ea.conector(paqs[a], paqs[b], 'Dependency', dirigido=True)
    ea.exportar(d, 'pkg-dependencias.png')
    if os.environ.get('EA_SOLO') == 'com': return

    # ---------------------------------------------------------------- Diseño: despliegue
    # El lector corre en la PC del grupo (Windows 11, JDK 21) con ejecutar.ps1; el buzón y la BD son los de la facultad
    # y la respuesta sale por Gmail (la facultad no reenvía a dominios externos: 550 Relaying denied).
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
    bd = ea.elemento(dep, 'db_grupo05sa, esquema public', 'Artifact', padre=pg)
    tablas_c = ea.elemento(dep, f'{len(tablas_sql())} tablas (sql/esquema.sql)', 'Component', padre=bd)
    bitac_c = ea.elemento(dep, 'Bitácora (log_comandos)', 'Component', padre=bd)
    dove = ea.elemento(dep, 'Dovecot (POP3 110)', 'ExecutionEnvironment', padre=so_f)
    smail = ea.elemento(dep, 'Sendmail (SMTP 25, MX tecnoweb.org.bo)', 'ExecutionEnvironment', padre=so_f)
    buzon = ea.elemento(dep, 'buzón grupo05sa', 'Artifact', 'file', padre=so_f)
    gpc = ea.elemento(dep, 'PC del grupo', 'Device')
    so_w = ea.elemento(dep, 'Windows 11 Home', 'ExecutionEnvironment', padre=gpc)
    script = ea.elemento(dep, 'p1/ejecutar.ps1', 'Artifact', 'file', padre=so_w)
    props = ea.elemento(dep, '.secrets/crm-mail.properties', 'Artifact', 'file', padre=so_w)
    logf = ea.elemento(dep, 'p1/logs/crm-mail.0.log', 'Artifact', 'file', padre=so_w)
    jvm = ea.elemento(dep, 'Eclipse Temurin JDK 21.0.12', 'ExecutionEnvironment', padre=so_w)
    jar = ea.elemento(dep, 'p1/target/crm-mail.jar', 'Artifact', 'executable', padre=jvm)
    capas_jar = [ea.elemento(dep, n, 'Component', padre=jar) for n in ('Presentación', 'Aplicación', 'Dominio', 'Persistencia')]
    for e, x, y, w, h, s_ in [(pc, 30, 450, 260, 160, 9), (cli, 50, 510, 220, 80, 1),
                              (fac, 380, 30, 520, 530, 9), (so_f, 400, 80, 480, 460, 8),
                              (pg, 420, 120, 440, 180, 2), (bd, 440, 170, 400, 115, 2), (tablas_c, 460, 215, 175, 55, 1), (bitac_c, 650, 215, 175, 55, 1),
                              (dove, 620, 320, 240, 70, 2), (smail, 620, 440, 240, 80, 2), (buzon, 420, 390, 170, 50, 1),
                              (gpc, 980, 30, 600, 530, 9), (so_w, 1000, 80, 560, 460, 8),
                              (script, 1020, 110, 160, 50, 1), (props, 1195, 110, 170, 50, 1), (logf, 1380, 110, 160, 50, 1),
                              (jvm, 1020, 300, 520, 230, 3), (jar, 1040, 350, 480, 165, 2),
                              (capas_jar[0], 1060, 392, 210, 52, 1), (capas_jar[1], 1290, 392, 210, 52, 1),
                              (capas_jar[2], 1060, 455, 210, 52, 1), (capas_jar[3], 1290, 455, 210, 52, 1),
                              (goo, 380, 650, 1200, 150, 9), (gweb, 420, 705, 440, 70, 1), (gsmtp, 1100, 705, 440, 70, 1)]:
        ea.poner(d, e, x, y, w, h, s_)
    for a, b, t_ in [(cli, gweb, 'HTTPS 443'), (gweb, smail, 'SMTP 25'), (jvm, dove, 'POP3 110'),
                     (jvm, pg, 'JDBC TCP 5432'), (jvm, gsmtp, 'SMTP 587 STARTTLS')]:
        ea.conector(a, b, 'CommunicationPath', t_, dirigido=True)
    for a, b, t_ in [(script, jar, 'java -jar'), (jar, props, 'lee'), (jar, logf, 'escribe'), (smail, buzon, 'entrega'),
                     (dove, buzon, 'lee'), (gsmtp, gweb, 'entrega')]:
        ea.conector(a, b, 'Dependency', t_, dirigido=True)
    ea.exportar(d, 'despliegue.png')

    # ---------------------------------------------------------------- Diseño: capas
    cap = ea.paquete(dis, 'Capas')
    d = ea.diagrama(cap, 'Diagrama de capas', 'Package')
    for nombre, y, h in [('Capa de Presentación', 20, 270), ('Capa de Aplicación', 310, 150), ('Capa de Dominio', 480, 150),
                         ('Capa de Persistencia', 650, 150)]:
        # EA centra el nombre de un boundary, justo donde pasan las dependencias: se rotula arriba a la izquierda
        ea.poner(d, ea.elemento(cap, '', 'Boundary'), 30, y, 1260, h, seq=50)
        ea.poner(d, ea.elemento(cap, nombre, 'Text', notas=nombre), 40, y + 6, 200, 20, seq=40)
    APP, DIAG, CPR, DBS = 'App (bucle cada 10 s)', 'Diagnostico (causa de cada falla)', 'CommandProcessor y procesadores por rol', 'DatabaseService (PostgreSQL)'
    sitio = {APP: (420, 60), DIAG: (760, 60), 'Lector de Correos POP3 (Pop3Client)': (60, 180), 'Correo (MIME)': (380, 180),
             'Emisor SMTP (SmtpSender)': (620, 180), CPR: (470, 350), DBS: (520, 690)}
    dominio = ['usuarios', 'captacion', 'asignacion', 'embudo', 'perdidas', 'conversion', 'pagos', 'seguimiento', 'reportes']
    for j, h in enumerate(dominio):
        sitio[h] = (45 + j * 137, 520)
    pk = {}
    for h, (x, y) in sitio.items():
        pk[h] = ea.paquete(cap, h)
        ea.poner(d, pk[h], x, y, 120 if h in dominio else (170 if h == 'Correo (MIME)' else (300 if h == CPR else 260)), 80, seq=1)
    for b in ['Lector de Correos POP3 (Pop3Client)', 'Correo (MIME)', 'Emisor SMTP (SmtpSender)', CPR, DIAG]:
        ea.conector(pk[APP], pk[b], 'Dependency', dirigido=True)
    ea.conector(pk[CPR], pk[DBS], 'Dependency', dirigido=True)
    for h in dominio:
        ea.conector(pk[CPR], pk[h], 'Dependency', dirigido=True)
        ea.conector(pk[h], pk[DBS], 'Dependency', dirigido=True)
    ea.exportar(d, 'capas.png')

    # ---------------------------------------------------------------- Diseño: clases (código)
    cls_pkg = ea.paquete(dis, 'Clases')
    todos = list(dict.fromkeys(NUCLEO + [s for ss in SERVICIOS.values() for s in ss] + [e for es in ENTIDADES_CU.values() for e in es]))
    cls = {n: clase(ea, cls_pkg, spec(n), 'Enumeration' if spec(n).split('|')[1] == 'enumeration' else 'Class') for n in todos}
    for sub in PROC_ROL.values():
        ea.conector(cls[sub], cls['RolCommandProcessor'], 'Generalization')
    cls['RolCommandProcessor'].Abstract = '1'; cls['RolCommandProcessor'].Update()
    hechos = set()

    def dep(a, b):
        if (a, b) not in hechos and a != b:
            hechos.add((a, b)); ea.conector(cls[a], cls[b], 'Dependency', dirigido=True)

    for a, b in [('CommandProcessor', 'CommandParser'), ('CommandProcessor', 'Command'), ('CommandProcessor', 'ResponseFormatter'),
                 ('CommandProcessor', 'RolCommandProcessor'), ('CommandProcessor', 'UsuarioService'), ('CommandProcessor', 'SeguimientoService'),
                 ('CommandParser', 'Command'), ('RolCommandProcessor', 'Command'), ('RolCommandProcessor', 'ResponseFormatter'),
                 ('ProspectoService', 'ProgramaService')]:
        dep(a, b)
    # Lo demás, de los tipos que aparecen en los campos y firmas reales: servicio → entidad, entidad → entidad.
    servicios = [s for ss in SERVICIOS.values() for s in ss]
    for s in servicios:
        dep('RolCommandProcessor', s) if s != 'ProgramaService' else None
        dep(s, 'DatabaseService')
    entidades = [n for n in todos if n not in NUCLEO[:10] and n not in servicios]
    for a in servicios + entidades:
        cuerpo = '|'.join(spec(a).split('|')[2:])
        for b in entidades:
            if re.search(r'\b' + simple(b) + r'\b', cuerpo) and simple(b) != simple(a):
                dep(a, b)

    def alto(nombre):
        e = cls[nombre]; e.Attributes.Refresh(); e.Methods.Refresh()   # EA no actualiza Count sin Refresh
        n = e.Attributes.Count + e.Methods.Count
        return 44 + 14 * n + (10 if e.Attributes.Count and e.Methods.Count else 0) + (16 if e.Stereotype else 0)

    def ancho(nombre):
        """Ancho justo para el miembro más largo (~6,3 unidades por carácter en Calibri 8)."""
        e = cls[nombre]; e.Attributes.Refresh(); e.Methods.Refresh()
        textos = [e.Name] + [f'{a.Name}: {a.Type}' for a in e.Attributes]
        textos += [f'{m.Name}({", ".join(pa.Type for pa in m.Parameters)}): {m.ReturnType}' for m in e.Methods]
        return int(50 + 6.3 * max(len(t) for t in textos))

    # ---------------------------------------------------------------- Diseño: procedimental por CU (como el P1 aprobado)
    for k in CU:
        d = ea.diagrama(cls_pkg, f'{k}_' + re.sub(r'\W+', '_', CORTO[k]).strip('_'), 'Logical')
        # fila A: CommandParser | CommandProcessor | UsuarioService | DatabaseService
        # fila B: Command | RolCommandProcessor | servicios del CU
        # fila C: ResponseFormatter          ·  fila E (bajo los servicios): entidades, Usuario y Rol
        # fila D: procesadores de cada rol del CU, bajo RolCommandProcessor (la herencia sube sin cruzar nada)
        sv = [s for s in SERVICIOS[k] if s != 'UsuarioService']
        ents = list(dict.fromkeys(ENTIDADES_CU[k] + ['Usuario', 'Rol']))
        roles = [PROC_ROL[a] for a in actores(k)]
        G = 80
        w0 = max(ancho(n) for n in ('CommandParser', 'Command', 'ResponseFormatter'))
        w1 = max(ancho(n) for n in ('CommandProcessor', 'RolCommandProcessor'))
        x0, x1 = 30, 30 + w0 + G
        x2 = x1 + w1 + G
        x3 = x2 + ancho('UsuarioService') + G
        yA = 30
        yB = yA + max(alto(n) for n in ('CommandParser', 'CommandProcessor', 'UsuarioService', 'DatabaseService')) + G
        yC = yB + alto('Command') + G
        sitio = {'CommandParser': (x0, yA), 'CommandProcessor': (x1, yA), 'UsuarioService': (x2, yA), 'DatabaseService': (x3, yA),
                 'Command': (x0, yB), 'RolCommandProcessor': (x1, yB), 'ResponseFormatter': (x0, yC)}
        x = x2
        for s in sv:
            sitio[s] = (x, yB); x += ancho(s) + G
        yE = yB + max([alto(s) for s in sv] + [alto('RolCommandProcessor')]) + G
        borde = max(x3 + ancho('DatabaseService'), max(sitio[s][0] + ancho(s) for s in sv) if sv else 0)
        x, alto_fila = x2, 0
        for e in ents:   # en filas que no pasen del borde derecho de DatabaseService o de los servicios
            if x > x2 and x + ancho(e) > borde:
                x, yE, alto_fila = x2, yE + alto_fila + 50, 0
            sitio[e] = (x, yE); x += ancho(e) + 50; alto_fila = max(alto_fila, alto(e))
        wr = max(ancho(r) for r in roles)
        fila = len(roles) * (wr + 30) - 30
        xr = max(30, min(x1 + w1 // 2 - fila // 2, x2 - fila - 30))
        fondo = max(y_ + alto(n) for n, (x_, y_) in sitio.items() if x_ < xr + fila and x_ + ancho(n) > xr)
        for i, r_ in enumerate(roles):
            sitio[r_] = (xr + i * (wr + 30), fondo + G)
        for nombre, (x, y_) in sitio.items():
            ea.poner(d, cls[nombre], x, y_, ancho(nombre), alto(nombre))
        ea.exportar(d, f'cls-{k.lower()}.png')

    secuencias(ea, dis)
    modelo_datos(ea, dis)

    # ---------------------------------------------------------------- Implementación: componentes
    d = ea.diagrama(imp, 'Diagrama de componentes', 'Component')

    def comp(nombre, st='', padre=None):
        return ea.elemento(imp, nombre, 'Component', st, padre=padre)

    c_sis = comp('Sistema CRM vía e-mail', 'subsystem')
    c_pre, c_apl, c_dom, c_per = (comp(n, padre=c_sis) for n in ('Capa de Presentación', 'Capa de Aplicación', 'Capa de Dominio', 'Capa de Persistencia'))
    app_c = comp('App (bucle del servicio)', padre=c_pre)
    diag_c = comp('Diagnostico (causa de cada falla)', padre=c_pre)
    lector = comp('Lector de correo (Pop3Client, Correo)', padre=c_pre)
    emisor = comp('Emisor SMTP (SmtpSender)', padre=c_pre)
    proc = comp('Procesador de comandos (CommandProcessor, CommandParser)', padre=c_apl)
    porrol = comp('Procesadores por rol (RolCommandProcessor y 4 subclases)', padre=c_apl)
    fmt_c = comp('Formateador (ResponseFormatter)', padre=c_apl)
    servs_c = [comp(s, 'service', padre=c_dom) for s in [s for s in servicios if s != 'UsuarioService'] + ['UsuarioService']]   # al final: junto a jBCrypt
    dbs = comp('DatabaseService', padre=c_per)
    angus = comp('Angus Mail 2.0.5', 'library')
    pgj = comp('PostgreSQL JDBC 42.7.13', 'library')
    bcr = comp('jBCrypt 0.4', 'library')
    dov = comp('Dovecot mail.tecnoweb.org.bo', 'system extern')
    gm = comp('Gmail smtp.gmail.com', 'system extern')
    pgc = comp('PostgreSQL 18.6 db.tecnoweb.org.bo', 'Database')
    jar_a = ea.elemento(imp, 'crm-mail.jar', 'Artifact', 'executable')
    i_pop = ea.elemento(imp, 'POP3 (RFC 1939)', 'Interface')
    i_mail = ea.elemento(imp, 'Jakarta Mail API', 'Interface')
    i_smtp = ea.elemento(imp, 'SMTP (RFC 5321) + STARTTLS', 'Interface')
    i_jdbc = ea.elemento(imp, 'JDBC (java.sql)', 'Interface')
    i_pgp = ea.elemento(imp, 'Protocolo PostgreSQL v3', 'Interface')
    DY = 80   # la capa de dominio crece una fila (11 servicios): lo de abajo baja DY
    expuestas = [(lector, ' ', 'RequiredInterface', i_pop, 932, 82), (dov, 'POP3 :110', 'ProvidedInterface', i_pop, 1452, 82),
                 (emisor, ' ', 'RequiredInterface', i_mail, 932, 192), (angus, 'Jakarta Mail API', 'ProvidedInterface', i_mail, 1082, 192),
                 (angus, ' ', 'RequiredInterface', i_smtp, 1282, 192), (gm, 'SMTP :587', 'ProvidedInterface', i_smtp, 1452, 192),
                 (dbs, ' ', 'RequiredInterface', i_jdbc, 732, 862 + DY), (pgj, 'JDBC', 'ProvidedInterface', i_jdbc, 1082, 862 + DY),
                 (pgj, ' ', 'RequiredInterface', i_pgp, 1282, 862 + DY), (pgc, 'PostgreSQL v3 :5432', 'ProvidedInterface', i_pgp, 1452, 862 + DY)]
    for e, x, y, w, h, s_ in [(c_sis, 255, -50, 770, 990 + DY, 10),
                              (c_pre, 280, 20, 720, 250, 9), (diag_c, 300, 55, 190, 70, 1), (app_c, 300, 165, 190, 70, 1),
                              (lector, 640, 55, 300, 70, 1), (emisor, 640, 165, 300, 70, 1),
                              (c_apl, 280, 300, 720, 230, 9), (proc, 300, 355, 240, 70, 1), (porrol, 760, 355, 225, 70, 1), (fmt_c, 560, 455, 200, 60, 1),
                              (c_dom, 280, 560, 720, 200 + DY, 9), (c_per, 280, 800 + DY, 720, 120, 9), (dbs, 540, 835 + DY, 200, 60, 1),
                              (angus, 1090, 165, 200, 70, 1), (pgj, 1090, 830 + DY, 200, 70, 1), (bcr, 1090, 815, 200, 70, 1),
                              (dov, 1460, 45, 260, 80, 1), (gm, 1460, 155, 260, 80, 1), (pgc, 1460, 820 + DY, 260, 90, 1),
                              (jar_a, 20, 430, 190, 70, 1)]:
        ea.poner(d, e, x, y + 60, w, h, s_)   # +60: cabecera del subsistema contenedor
    for i, s in enumerate(servs_c):
        ea.poner(d, s, 300 + (i % 4) * 172, 665 + (i // 4) * 75, 160, 60, 1)
    pts = {}
    for e, n_, t_, cl, x, y in expuestas:
        pts[(e.ElementID, t_)] = ie = ea.interfaz(e, n_, t_, cl)
        ea.poner(d, ie, x, y + 60, 16, 16, 0)
    for ra, pa, nombre in [(lector, dov, 'sockets TCP 110'), (emisor, angus, ''), (angus, gm, 'STARTTLS'), (dbs, pgj, ''), (pgj, pgc, 'TCP')]:
        c = pts[(ra.ElementID, 'RequiredInterface')].Connectors.AddNew(nombre, 'Assembly')
        c.SupplierID = pts[(pa.ElementID, 'ProvidedInterface')].ElementID; c.Update()
    for a_, b_, t_ in [(app_c, lector, 'retr()'), (app_c, emisor, 'enviar()'), (app_c, proc, 'procesarEmail()'), (app_c, fmt_c, 'html()'),
                       (app_c, diag_c, 'revisar(), explicar()'), (diag_c, lector, ''), (diag_c, emisor, 'verificar()'),
                       (proc, porrol, 'procesar()'), (proc, fmt_c, ''), (porrol, fmt_c, ''), (porrol, c_dom, 'invoca el servicio del CU'),
                       (c_dom, dbs, 'enTransaccion(), lista(), filas()'), (servs_c[-1], bcr, 'hashpw()')]:
        ea.conector(a_, b_, 'Dependency', t_, dirigido=True)
    ea.conector(jar_a, c_sis, 'Manifest', dirigido=True)
    ea.exportar(d, 'componentes.png')


# ---------------------------------------------------------------- Diseño: secuencia
SLOT, Y1 = 35, 133   # alto fijo de cada mensaje en EA 15 y y del primero con las líneas de vida en y=40 (medidos)
ROBUSTEZ = {'Pop3Client': 'boundary', 'SmtpSender': 'boundary', 'DatabaseService': 'entity'}


def ancho_msg(texto):
    return 30 + 4.4 * len(texto)   # medido en el PNG: ~4,4 unidades por carácter, más la punta de la flecha


def secuencias(ea, dis):
    """Un diagrama común de recepción (loop cada 10 s), uno de respuesta, y uno por CU: el flujo común una vez y un
    alt [no permitido al rol] / [else] con un alt anidado de un operando por comando del CU."""
    seq = ea.paquete(dis, 'Secuencia')

    def vida(clase_, campo):
        return ea.elemento(seq, f'{campo}:{clase_}', 'Object', ROBUSTEZ.get(clase_, 'control'))

    def dibujar(d, cols, pasos, frags, archivo, extra_der=0):
        """pasos: (origen, destino, texto, tipo, args). frags: (subtipo, [(guarda, msg_ini, msg_fin)], col_izq, col_der, nivel)."""
        # llamada sin parámetros: "()" en el nombre (el diagrama suprime los paréntesis automáticos)
        pasos = [(o, dd, t + '()' if tipo == 'call' and not a.strip() else t, tipo, a.strip()) for o, dd, t, tipo, a in pasos]
        idx = {e.ElementID: i for i, e in enumerate(cols)}
        # cada fragmento empieza entre la columna ci-1 y ci: ese hueco debe dar cabida a sus guardas
        guarda = {}
        for _, ops, ci, _, nivel in frags:
            guarda[ci] = max(guarda.get(ci, 0), 60 + 22 * nivel + max(ancho_msg('[' + g + ']') for g, _, _ in ops))
        xs = [80]
        for i in range(1, len(cols)):
            etq = [t + (f'({a})' if a else '') for o, dd, t, _, a in pasos
                   if {idx[o.ElementID], idx[dd.ElementID]} == {i - 1, i} or (idx[o.ElementID] == idx[dd.ElementID] == i - 1)]
            xs.append(int(xs[-1] + max([150, guarda.get(i, 0) + 40] + [ancho_msg(t) for t in etq])))
        alto = Y1 + len(pasos) * SLOT + 60
        for e, x in zip(cols, xs):
            w = 60 if e.Type == 'Actor' else max(120, int(9.5 * len(e.Name)))
            ea.poner(d, e, x - w // 2, 50 if e.Stereotype == 'entity' else 40, w, int(alto))
        for n, (o, dd, t, tipo, a) in enumerate(pasos, 1):
            ea.mensaje_seq(d, o, dd, t, n, tipo, a)
        y = lambda k: Y1 + (k - 1) * SLOT
        for sub, ops, ci, cd, nivel in frags:
            fr = ea.elemento(seq, '', 'InteractionFragment'); fr.Subtype = sub; fr.Update()
            top = y(ops[0][1]) - 24 + 4 * nivel
            cortes = [top] + [(y(a_) + y(b_)) / 2 for (_, a_, _), (_, _, b_) in zip(ops[1:], ops)] + [y(ops[-1][2]) + 16 - 4 * nivel]
            for _ in ops[:2]:   # la API de EA 15 falla con más de dos; el resto va por t_xref
                part = fr.Partitions.AddNew('x', ''); part.Size = 100
            fr.Update()
            # La API de EA 15 falla con más de dos operandos: la lista completa se escribe en t_xref (en orden inverso).
            pars = ''.join(f"@PAR;Name={g};Size={int(cortes[i + 1] - cortes[i])};GUID={{{str(uuid.uuid4()).upper()}}};@ENDPAR;"
                           for i, (g, _, _) in reversed(list(enumerate(ops))))
            ea.r.Execute(f"UPDATE t_xref SET Description='{pars}' WHERE Client='{fr.ElementGUID}' AND Name='Partitions'")
            x0 = xs[ci] - guarda[ci] + 22 * nivel
            x1 = xs[cd] + 90 + extra_der - 22 * nivel
            ea.poner(d, fr, int(x0), int(top), int(x1 - x0), int(cortes[-1] - top), seq=60 - nivel)
        ea.exportar(d, archivo)

    rem = ea.elemento(seq, 'Remitente', 'Actor')

    # --- Recibir: App.ciclo() cada intervalo y procesarEmail() de cada mensaje
    d = ea.diagrama(seq, 'Recibir comando', 'Sequence'); d.StyleEx = 'SuppressBrackets=1;'; d.Update()
    o = {'App': vida('App', ''), 'db': vida('DatabaseService', 'db'), 'pop': vida('Pop3Client', 'pop'),
         'proc': vida('CommandProcessor', 'procesador'), 'parser': vida('CommandParser', 'parser'), 'usr': vida('UsuarioService', 'usuarios')}
    cols = [rem, o['pop'], o['App'], o['db'], o['proc'], o['parser'], o['usr']]
    pasos = [(rem, o['pop'], '1. COMANDO[...] en el asunto', 'signal', ''),
             (o['App'], o['db'], '1.1 verificarModelo', 'call', ' '),
             (o['App'], o['pop'], '1.2 stat', 'call', ' '),
             (o['pop'], o['App'], 'int', 'return', ''),
             (o['App'], o['pop'], '1.3 tamano', 'call', 'i'),
             (o['pop'], o['App'], 'long', 'return', ''),
             (o['App'], o['pop'], '1.4 top', 'call', 'i, 0'),
             (o['pop'], o['App'], 'byte[]', 'return', ''),
             (o['App'], o['pop'], '1.4 retr', 'call', 'i'),
             (o['pop'], o['App'], 'byte[]', 'return', ''),
             (o['App'], o['pop'], '1.5 dele', 'call', 'i'),
             (o['App'], o['pop'], '1.6 quit', 'call', ' '),
             (o['App'], o['proc'], '1.7 procesarEmail', 'call', 'remitente, asunto, csv, archivo, errorArchivo'),
             (o['proc'], o['parser'], '1.8 parse', 'call', firma('CommandParser', 'parse')),
             (o['parser'], o['proc'], 'Command', 'return', ''),
             (o['proc'], o['usr'], '1.9 buscarPorCorreo', 'call', firma('UsuarioService', 'buscarPorCorreo')),
             (o['usr'], o['proc'], 'Usuario', 'return', '')]
    frags = [(4, [('cada intervalo.segundos (10 s)', 2, 17)], 1, 6, 0),
             (4, [('por mensaje, máximo 20', 5, 11)], 1, 3, 1),
             (0, [('tamaño > 8 MB', 7, 8), ('else', 9, 10)], 1, 3, 2),
             (4, [('por mensaje leído', 13, 17)], 1, 6, 1)]
    dibujar(d, cols, pasos, frags, 'sd-recibir.png')

    # --- Registrar y responder
    d = ea.diagrama(seq, 'Registrar y responder', 'Sequence'); d.StyleEx = 'SuppressBrackets=1;'; d.Update()
    o2 = {'App': vida('App', ''), 'proc': vida('CommandProcessor', 'procesador'), 'smtp': vida('SmtpSender', 'smtp')}
    cols = [rem, o2['App'], o2['proc'], o2['smtp']]
    pasos = [(o2['proc'], o2['proc'], '1. bitacora', 'call', args_msg(firma('CommandProcessor', 'bitacora'))),
             (o2['proc'], o2['App'], 'Respuesta', 'return', ''),
             (o2['App'], o2['smtp'], '2. enviar', 'call', 'para, asunto, html, texto, archivos'),
             (o2['smtp'], rem, '3. respuesta HTML', 'signal', ''),
             (o2['App'], o2['proc'], '4. bitacora', 'call', 'para, null, asunto, "Error", ...')]
    dibujar(d, cols, pasos, [(1, [('el envío falló', 5, 5)], 1, 2, 0)], 'sd-responder.png', extra_der=120)

    # --- Una por CU
    for k in CU:
        d = ea.diagrama(seq, f'{k}: {CORTO[k]}', 'Sequence'); d.StyleEx = 'SuppressBrackets=1;'; d.Update()
        cmds = comandos(k)
        proc = vida('CommandProcessor', 'procesador'); rol = vida('RolCommandProcessor', ''); fmt = vida('ResponseFormatter', 'fmt')
        sv = {s: vida(s, CAMPO[s]) for s in SERVICIOS[k]}
        db = vida('DatabaseService', 'db')
        cols = [rem, proc, rol, fmt] + list(sv.values()) + [db]
        pasos = [(rem, proc, '1. COMANDO[...]', 'signal', ''),
                 (proc, rol, '1.1 procesar', 'call', firma('RolCommandProcessor', 'procesar')),
                 (rol, rol, '1.2 comandos', 'call', ' '),
                 (rol, fmt, '1.3 denegado', 'call', firma('ResponseFormatter', 'denegado')),
                 (fmt, rol, 'Respuesta', 'return', ''),
                 (rol, rol, '1.3 ejecutar', 'call', 'c, u')]
        operandos, ultimo = [], 4
        for cmd, _, llam in cmds:
            ini, nro = len(pasos) + 1, 4
            for s, met, dbm, dlg in llam:
                pasos.append((rol, sv[s], f'1.{nro} {met}', 'call', args_msg(firma(s, met)) or ' ')); nro += 1
                origen = sv[s]
                if dlg:
                    pasos.append((sv[s], sv[dlg[0]], f'1.{nro} {dlg[1]}', 'call', args_msg(firma(*dlg)) or ' ')); nro += 1
                    origen = sv[dlg[0]]
                if dbm:
                    pasos.append((origen, db, f'1.{nro} {dbm}', 'call', args_msg(firma('DatabaseService', dbm)) or ' ')); nro += 1
            operandos.append((cmd, ini, len(pasos)))
            ultimo = max(ultimo, nro)
        fin_alt = len(pasos)
        pasos += [(rol, proc, 'Respuesta', 'return', ''),
                  (proc, proc, f'1.{ultimo} bitacora', 'call', args_msg(firma('CommandProcessor', 'bitacora'))),
                  (proc, rem, f'1.{ultimo + 1} respuesta HTML', 'signal', '')]
        der = len(cols) - 1
        frags = [(0, [('comando no permitido al rol', 4, 5), ('else', 6, fin_alt)], 2, der, 0),
                 (0, [(c, a_, b_) for c, a_, b_ in operandos], 2, der, 1)]
        dibujar(d, cols, pasos, frags, f'sd-{k.lower()}.png')


# ---------------------------------------------------------------- Diseño: modelo de datos (sql/esquema.sql)
TIPO_CORTO = [(r'character varying', 'varchar'), (r'timestamp\(0\) without time zone', 'timestamp(0)'),
              (r'timestamp without time zone', 'timestamp')]
NO_NEGOCIO = {'migrations'}   # tabla de infraestructura de Laravel (P2): no es parte del modelo del CRM


def tablas_sql():
    """{tabla: [(columna, tipo, not_null)]} y [(hija, columna, padre, columna_padre)] desde el volcado de esquema.sql."""
    tablas = {}
    for nombre, cuerpo in re.findall(r'CREATE TABLE public\.(\w+) \((.*?)\n\);', SQL, re.S):
        if nombre in NO_NEGOCIO: continue
        cols = []
        for linea in cuerpo.splitlines():
            mm = re.match(r'\s+(\w+) (.+?)(?: DEFAULT .*?)?( NOT NULL)?,?$', linea)
            if not mm or mm.group(1) == 'CONSTRAINT': continue
            tipo = mm.group(2).split(' DEFAULT ')[0]
            for a, b in TIPO_CORTO: tipo = re.sub(a, b, tipo)
            cols.append((mm.group(1), tipo, bool(mm.group(3))))
        tablas[nombre] = cols
    return tablas


def fks_sql():
    return re.findall(r'ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT \w+ FOREIGN KEY \((\w+)\) REFERENCES public\.(\w+)\((\w+)\)', SQL)


def unicas_sql():
    """Columnas con UNIQUE propia (la FK con UNIQUE da multiplicidad 0..1 del lado de la hija)."""
    return set(re.findall(r'ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT \w+ UNIQUE \((\w+)\);', SQL))


def pks_sql():
    return {t: [c.strip() for c in cs.split(',')]
            for t, cs in re.findall(r'ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT \w+ PRIMARY KEY \(([^)]+)\);', SQL)}


ROL_FK = {'vendedor_id': 'vendedor', 'coordinador_id': 'coordinador', 'actualizado_por': 'actualizado por',
          'external_modulo_id': 'módulo', ('log_comandos', 'usuario_id'): 'emisor', ('interacciones', 'usuario_id'): 'autor',
          ('movimientos_interes', 'usuario_id'): 'autor', ('recordatorios', 'usuario_id'): 'responsable',
          ('exportaciones', 'usuario_id'): 'responsable', ('notificaciones', 'usuario_id'): 'destinatario'}


def modelo_datos(ea, dis):
    """Clases persistentes de sql/esquema.sql, como el P1 aprobado: atributos privados con su tipo y las FK como
    asociaciones con su papel y multiplicidad (las columnas FK están en el mapeo). La clave primaria va primero."""
    datos = ea.paquete(dis, 'Modelo de datos')
    d = ea.diagrama(datos, 'Diagrama de clases del modelo de datos', 'Logical')
    tablas, fks, unicas, pks = tablas_sql(), fks_sql(), unicas_sql(), pks_sql()
    col_fk = {(h, c) for h, c, _, _ in fks}
    elem, caja = {}, {}
    for nombre, cols in tablas.items():
        e = ea.elemento(datos, nombre, 'Class')
        pk = pks.get(nombre, [])
        # la clave primaria primero; las FK que no son clave se ven como asociaciones, no como atributos
        visibles = [c for c in cols if c[0] in pk] + [c for c in cols if c[0] not in pk and (nombre, c[0]) not in col_fk]
        for i, (c, tipo, _) in enumerate(visibles):
            a = e.Attributes.AddNew(c, tipo); a.Visibility = 'Private'; a.Pos = i; a.Update()
        e.Update(); elem[nombre] = e
        caja[nombre] = (int(50 + 6.3 * max(len(nombre), *(len(f'- {c}: {t}') for c, t, _ in visibles))), 44 + 14 * (len(visibles) - 1))
    for hija, c, padre, _ in fks:
        nn = next(x[2] for x in tablas[hija] if x[0] == c)
        ea.conector(elem[hija], elem[padre], 'Association', ROL_FK.get((hija, c)) or ROL_FK.get(c, ''),
                    card=('0..1' if (hija, c) in unicas else '0..*', '1' if nn else '0..1'))
    # Grilla de 6 columnas: arriba lo que solo cuelga de usuarios; al centro usuarios sobre intereses; abajo la venta
    # (orden → pago → comprobante, solicitud → inscripción → exportación). Cada tabla junto a las que referencia.
    GRILLA = [['configuraciones', 'api_tokens', 'notificaciones', 'roles', 'log_comandos', 'programas_responsables'],
              ['movimientos_interes', 'interacciones', 'usuarios', None, 'recordatorios', 'comisiones'],
              ['motivos_perdida', 'prospectos', 'intereses', None, 'catalogo_cache', 'exportaciones'],
              ['comprobantes_pago', 'pagos', 'ordenes_pago', 'solicitudes_compra', 'inscripciones_comerciales', None],
              [None, 'descuentos', 'orden_pago_descuentos', None, None, None]]
    assert sorted(n for f in GRILLA for n in f if n) == sorted(tablas), 'la grilla debe tener todas las tablas'
    COLW, GX, GY = max(w for w, _ in caja.values()), 45, 70
    y = 30
    for fila in GRILLA:
        for c, n in enumerate(fila):
            if not n: continue
            w, h = caja[n]
            ancho_celda = COLW * 2 + GX if n in ('usuarios', 'intereses') else COLW   # centrados entre dos columnas
            ea.poner(d, elem[n], 30 + c * (COLW + GX) + (ancho_celda - w) // 2, y, w, h)
        y += max(caja[n][1] for n in fila if n) + GY
    ea.exportar(d, 'clases-datos.png')


if __name__ == '__main__':
    main()
