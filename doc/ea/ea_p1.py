"""Construye en Enterprise Architect (COM, EA.Repository) los diagramas UML del Proyecto 1 y los exporta a PNG y EMF.
Uso: python p1/doc/ea/ea_p1.py  ->  p1/doc/ea/crm-p1.eapx y p1/doc/diagramas/*.png
Todo nombre de clase, método, comando, host y puerto sale del código de p1/ o de lo verificado en los servidores
(lista y criterio en P1_CORREO.md § Diagramas)."""
import heapq, math, os, pathlib, random, re, shutil, sys, uuid
import win32com.client

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import firmas  # noqa: E402

AQUI = pathlib.Path(__file__).resolve().parent
P1 = AQUI.parents[1]
SALIDA = AQUI.parent / 'diagramas'
EAPX = AQUI / 'crm-p1.eapx'
BASE = pathlib.Path(r'C:/Program Files (x86)/Sparx Systems/EA Trial/EABase.eapx')
SQL = (P1 / 'sql' / 'esquema.sql').read_text(encoding='utf-8')

# Visibilidad de miembros en un diagrama (t_diagramobjects.ObjectStyle): oculta atributos y operaciones.
OCULTO = 'AttPub=0;AttPri=0;AttPro=0;AttPkg=0;AttCustom=0;OpCustom=0;OpPub=0;OpPri=0;OpPro=0;OpPkg=0;'


def efectivo(w, h):
    """Letra impresa (pt) de un diagrama de w×h unidades de EA: el PNG sale al doble y el texto de EA mide ~20 px.
    Se toma la mejor orientación: carta apaisada (650×428 pt útiles bajo el rótulo) o vertical (468×588 pt)."""
    w, h = 2 * w + 80, 2 * h + 80
    return 20 * max(min(650 / w, 428 / h), min(468 / w, 588 / h))


def estantes(grupos, ancho_max, gx=60, gy=60, x0=30, y0=30):
    """grupos: [[(clave, w, h)]]; cada grupo empieza fila nueva y se parte en filas de hasta ancho_max.
    Devuelve ({clave: (x, y, w, h)}, ancho, alto)."""
    pos, y, ancho = {}, y0, 0
    for grupo in grupos:
        x, alto = x0, 0
        for n, w, h in grupo:
            if x > x0 and x + w > x0 + ancho_max:
                y, x, alto = y + alto + gy, x0, 0
            pos[n] = (x, y, w, h); x += w + gx; alto = max(alto, h); ancho = max(ancho, x - gx - x0)
        y += alto + gy
    return pos, ancho, y - gy - y0


def mejor(grupos, **kw):
    """La disposición en estantes cuyo ancho máximo da la letra impresa más grande."""
    return max((estantes(grupos, a, **kw) for a in range(500, 3001, 50)), key=lambda r: efectivo(r[1], r[2]))


def _cruzan(p1, p2, p3, p4):
    o = lambda a, b, c: (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
    return o(p3, p4, p1) * o(p3, p4, p2) < 0 and o(p1, p2, p3) * o(p1, p2, p4) < 0


def _atraviesa(p1, p2, r):
    """¿El segmento p1-p2 pasa por el rectángulo r = (x0, y0, x1, y1)? (recorte de Liang-Barsky)"""
    t0, t1, dx, dy = 0.0, 1.0, p2[0] - p1[0], p2[1] - p1[1]
    for p, q in ((-dx, p1[0] - r[0]), (dx, r[2] - p1[0]), (-dy, p1[1] - r[1]), (dy, r[3] - p1[1])):
        if p == 0:
            if q < 0: return False
        else:
            t = q / p
            if p < 0: t0 = max(t0, t)
            else: t1 = min(t1, t)
            if t0 > t1: return False
    return True


def _grilla(asig, medidas, cols, gx, gy):
    """Posiciones de una asignación {nodo: (fila, col)}: cada columna y fila mide lo que su caja más grande;
    las filas y columnas vacías no ocupan lugar."""
    anchos, altos = {}, {}
    for n, (f, c) in asig.items():
        anchos[c] = max(anchos.get(c, 0), medidas[n][0]); altos[f] = max(altos.get(f, 0), medidas[n][1])
    xs, x = {}, 30
    for c in sorted(anchos): xs[c] = x; x += anchos[c] + gx
    ys, y = {}, 30
    for f in sorted(altos): ys[f] = y; y += altos[f] + gy
    pos = {n: (xs[c] + (anchos[c] - medidas[n][0]) // 2, ys[f], *medidas[n]) for n, (f, c) in asig.items()}
    return pos, x - gx - 30, y - gy - 30


def _costo(pos, aristas):
    cen = {n: (x + w / 2, y + h / 2) for n, (x, y, w, h) in pos.items()}
    cajas = {n: (x - 8, y - 8, x + w + 8, y + h + 8) for n, (x, y, w, h) in pos.items()}
    atraviesan = cruces = largo = 0
    for i, (a, b) in enumerate(aristas):
        largo += abs(cen[a][0] - cen[b][0]) + abs(cen[a][1] - cen[b][1])
        for n, r in cajas.items():
            if n not in (a, b) and _atraviesa(cen[a], cen[b], r): atraviesan += 1
        for c, d in aristas[i + 1:]:
            if len({a, b, c, d}) == 4 and _cruzan(cen[a], cen[b], cen[c], cen[d]): cruces += 1
    return atraviesan, cruces, largo


def acomodar(medidas, aristas, gx=60, gy=70, semilla=5, sin_atravesar=False):
    """Ubica las cajas en una grilla para que ninguna línea atraviese una caja, se crucen lo menos posible y queden
    cortas (recocido simulado), y elige el número de columnas que da la letra impresa más grande.
    medidas: {nodo: (w, h)}; aristas: [(a, b)]. Devuelve ({nodo: (x, y, w, h)}, ancho, alto)."""
    nodos = list(medidas)
    aristas = list(dict.fromkeys(tuple(sorted(e)) for e in aristas if e[0] != e[1] and e[0] in medidas and e[1] in medidas))
    mejor_r, mejor_puntaje = None, None
    for cols in range(2, min(7, len(nodos)) + 1):
        filas = -(-len(nodos) // cols) + 1   # una fila de holgura para separar lo que se cruza
        celdas = [(f, c) for f in range(filas) for c in range(cols)]
        rnd = random.Random(semilla)
        asig = dict(zip(nodos, celdas))
        libres = celdas[len(nodos):]

        def puntaje(a):
            pos, w, h = _grilla(a, medidas, cols, gx, gy)
            at, cr, lg = _costo(pos, aristas)
            return (50000 if sin_atravesar else 4000) * at + 700 * cr + 0.5 * lg + 2.0 * (w + h), (pos, w, h, at, cr)

        actual, info = puntaje(asig)
        mejor_c, mejor_a = actual, dict(asig)
        T = 3000.0
        for _ in range(2500):
            a = dict(asig); n = rnd.choice(nodos)
            if libres and rnd.random() < 0.35:
                k = rnd.randrange(len(libres)); a[n], nuevo_libre = libres[k], a[n]
            else:
                m = rnd.choice(nodos); a[n], a[m] = a[m], a[n]; nuevo_libre = None
            c_, _ = puntaje(a)
            if c_ < actual or rnd.random() < math.exp((actual - c_) / T):
                asig, actual = a, c_
                if nuevo_libre is not None: libres[k] = nuevo_libre
                if c_ < mejor_c: mejor_c, mejor_a = c_, dict(a)
            T *= 0.996
        _, (pos, w, h, at, cr) = puntaje(mejor_a)
        p = (-at, efectivo(w, h) - 0.4 * cr) if sin_atravesar else efectivo(w, h) - 3 * at - 0.4 * cr
        if mejor_puntaje is None or p > mejor_puntaje:
            mejor_puntaje, mejor_r = p, (pos, w, h, at, cr)
    pos, w, h, at, cr = mejor_r
    return pos, w, h, at, cr


def colocar_asociaciones(ea, d):
    """Sitúa la clase de asociación cerca de su relación, con la línea punteada libre de otras cajas."""
    d.DiagramObjects.Refresh()
    objetos = {o.ElementID: o for o in d.DiagramObjects}
    rect = lambda o: (o.left, -o.top, o.right, -o.bottom)
    centro = lambda r: ((r[0] + r[2]) / 2, (r[1] + r[3]) / 2)
    for ident, obj in objetos.items():
        e = ea.r.GetElementByID(ident)
        if e.Type != 'Class' or not e.AssociationClassConnectorID: continue
        c = ea.r.GetConnectorByID(e.AssociationClassConnectorID)
        if c.ClientID not in objetos or c.SupplierID not in objetos: continue
        ra, rb = rect(objetos[c.ClientID]), rect(objetos[c.SupplierID])
        a, b = centro(ra), centro(rb)
        def borde(r, otro):
            x, y = centro(r); dx, dy = otro[0] - x, otro[1] - y
            t = min((r[2] - r[0]) / (2 * abs(dx)) if dx else float('inf'),
                    (r[3] - r[1]) / (2 * abs(dy)) if dy else float('inf'))
            return x + t * dx, y + t * dy
        pa, pb = borde(ra, b), borde(rb, a)
        mid = ((pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2)
        largo = math.hypot(b[0] - a[0], b[1] - a[1])
        normal = (-(b[1] - a[1]) / largo, (b[0] - a[0]) / largo)
        w, h = obj.right - obj.left, obj.top - obj.bottom
        otras = [rect(o) for n, o in objetos.items() if n != ident]
        opciones = []
        for distancia in range(int(max(w, h) / 2 + 45), 1500, 30):
            for sentido in (-1, 1):
                cx, cy = mid[0] + sentido * normal[0] * distancia, mid[1] + sentido * normal[1] * distancia
                caja = (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                if any(caja[0] < x1 + 15 and caja[2] + 15 > x0 and caja[1] < y1 + 15 and caja[3] + 15 > y0
                       for x0, y0, x1, y1 in otras): continue
                if any(_atraviesa(mid, (cx, cy), r) for r in otras): continue
                opciones.append((distancia, caja))
            if opciones: break
        if not opciones: raise RuntimeError('No hay posición libre para la clase de asociación ' + e.Name)
        _, (x0, y0, x1, y1) = min(opciones)
        obj.left, obj.right, obj.top, obj.bottom = int(x0), int(x1), -int(y0), -int(y1)
        obj.Update()


def ruta_ortogonal(a, b, obstaculos):
    """A* sobre corredores de cajas: admite los quiebres necesarios sin atravesar atributos."""
    xs = sorted({a[0], b[0]} | {v for x0, _, x1, _ in obstaculos for v in (x0 - 1, x1 + 1)})
    ys = sorted({a[1], b[1]} | {v for _, y0, _, y1 in obstaculos for v in (y0 - 1, y1 + 1)})
    origen, destino = (xs.index(a[0]), ys.index(a[1])), (xs.index(b[0]), ys.index(b[1]))
    coord = lambda n: (xs[n[0]], ys[n[1]])
    h = lambda n: abs(xs[n[0]] - b[0]) + abs(ys[n[1]] - b[1])
    pendientes, costo, previo = [(h(origen), 0, origen)], {origen: 0}, {}
    libres = {}
    while pendientes:
        _, g, n = heapq.heappop(pendientes)
        if g != costo[n]: continue
        if n == destino:
            camino = [coord(n)]
            while n in previo:
                n = previo[n]; camino.append(coord(n))
            camino.reverse()
            esquinas = []
            for i, p in enumerate(camino[1:-1], 1):
                anterior, siguiente = camino[i - 1], camino[i + 1]
                if (anterior[0] == p[0] == siguiente[0]) or (anterior[1] == p[1] == siguiente[1]): continue
                esquinas.append(p)
            return esquinas
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            q = n[0] + dx, n[1] + dy
            if not (0 <= q[0] < len(xs) and 0 <= q[1] < len(ys)): continue
            borde = tuple(sorted((n, q)))
            if borde not in libres:
                libres[borde] = not any(_atraviesa(coord(n), coord(q), r) for r in obstaculos)
            if not libres[borde]: continue
            nuevo = g + abs(xs[q[0]] - xs[n[0]]) + abs(ys[q[1]] - ys[n[1]])
            if nuevo < costo.get(q, float('inf')):
                costo[q], previo[q] = nuevo, n
                heapq.heappush(pendientes, (nuevo + h(q), nuevo, q))
    raise RuntimeError('No existe corredor ortogonal entre las cajas')


def rutar_enlaces(ea, d):
    """Enruta en EA las asociaciones que atraviesan otra caja; no cambia sus extremos ni multiplicidades."""
    d.DiagramObjects.Refresh(); d.DiagramLinks.Refresh()
    cajas = {o.ElementID: (o.left, -o.top, o.right, -o.bottom) for o in d.DiagramObjects}
    centro = lambda r: ((r[0] + r[2]) / 2, (r[1] + r[3]) / 2)
    for link in d.DiagramLinks:
        c = ea.r.GetConnectorByID(link.ConnectorID)
        if c.Type != 'Association' or c.ClientID not in cajas or c.SupplierID not in cajas: continue
        a, b = centro(cajas[c.ClientID]), centro(cajas[c.SupplierID])
        otras = [(x0 - 15, y0 - 15, x1 + 15, y1 + 15) for n, (x0, y0, x1, y1) in cajas.items()
                 if n not in (c.ClientID, c.SupplierID)]
        if not any(_atraviesa(a, b, r) for r in otras): continue
        xs = {v for x0, _, x1, _ in otras for v in (x0 - 15, x1 + 15)}
        ys = {v for _, y0, _, y1 in otras for v in (y0 - 15, y1 + 15)}
        rutas = [[(x, y)] for x in xs for y in ys]
        rutas += [[(x, a[1]), (x, b[1])] for x in xs]
        rutas += [[(a[0], y), (b[0], y)] for y in ys]
        validas = []
        for puntos in rutas:
            tramos = list(zip([a] + puntos, puntos + [b]))
            if any(_atraviesa(p, q, r) for p, q in tramos for r in otras): continue
            largo = sum(math.hypot(q[0] - p[0], q[1] - p[1]) for p, q in tramos)
            validas.append((largo, puntos))
        puntos = min(validas)[1] if validas else ruta_ortogonal(a, b, otras)
        link.LineStyle = 3  # Custom Line, EA Automation API
        link.Path = ''.join(f'{int(x)}:{-int(y)};' for x, y in puntos)
        if not link.Update(): raise RuntimeError('EA no guardó la ruta: ' + link.GetLastError())


def despejar_cajas(pos, aristas):
    """Desplaza cajas a huecos libres para que las relaciones no pasen por los atributos de otra clase."""
    pos = dict(pos)
    aristas = list(dict.fromkeys(tuple(sorted(e)) for e in aristas if e[0] != e[1]))
    def dimensiones(p):
        return max(x + w for x, y, w, h in p.values()), max(y + h for x, y, w, h in p.values())
    def puntuacion(p):
        at, cr, lg = _costo(p, aristas)
        w, h = dimensiones(p)
        return at, cr, w * h, lg
    for _ in range(12):
        actual = puntuacion(pos)
        if actual[0] == 0: break
        mejor, mejor_pos = actual, None
        xs = {x for x, _, _, _ in pos.values()}
        ys = {y for _, y, _, _ in pos.values()}
        xs |= {min(xs) - max(w for _, _, w, _ in pos.values()) - 35,
               max(x + w for x, _, w, _ in pos.values()) + 35}
        ys |= {min(ys) - max(h for _, _, _, h in pos.values()) - 35,
               max(y + h for _, y, _, h in pos.values()) + 35}
        for n, (x, y, w, h) in pos.items():
            candidatos = {(xx, y) for xx in xs | {x - w - 35, x + w + 35}}
            candidatos |= {(x, yy) for yy in ys | {y - h - 35, y + h + 35}}
            candidatos |= {(xx, yy) for xx in xs for yy in ys}
            for xx, yy in candidatos:
                if (xx, yy) == (x, y): continue
                if any(xx < ox + ow + 25 and xx + w + 25 > ox and yy < oy + oh + 25 and yy + h + 25 > oy
                       for k, (ox, oy, ow, oh) in pos.items() if k != n): continue
                propuesta = {**pos, n: (xx, yy, w, h)}
                nota = puntuacion(propuesta)
                if nota < mejor: mejor, mejor_pos = nota, propuesta
        if mejor_pos is None: break
        pos = mejor_pos
    dx, dy = 30 - min(x for x, _, _, _ in pos.values()), 30 - min(y for _, y, _, _ in pos.values())
    pos = {n: (x + dx, y + dy, w, h) for n, (x, y, w, h) in pos.items()}
    w, h = dimensiones(pos)
    at, cr, _ = _costo(pos, aristas)
    return pos, w, h, at, cr


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

    def poner(self, d, el, x, y, w, h, seq=None, plegado=False):
        """plegado: la clase se muestra solo con su nombre en este diagrama (sus miembros están en otra figura)."""
        o = d.DiagramObjects.AddNew(f'l={x};r={x + w};t={y};b={y + h};', '')
        o.ElementID = el.ElementID if hasattr(el, 'ElementID') else el.Element.ElementID
        if seq is not None: o.Sequence = seq
        o.Update()
        if plegado:
            self.r.Execute(f"UPDATE t_diagramobjects SET ObjectStyle='{OCULTO}' WHERE Diagram_ID={d.DiagramID} "
                           f"AND Object_ID={o.ElementID}")
        return o

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
        if archivo in ('conceptual-global.png', 'datos-global.png'):
            if archivo == 'conceptual-global.png':
                colocar_asociaciones(self, d)
                self.r.ReloadDiagram(d.DiagramID)
            rutar_enlaces(self, d)
            self.r.ReloadDiagram(d.DiagramID)
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


SERVICIOS = {'CU1': ['UsuarioService', 'PersonaService'], 'CU2': ['ProspectoService', 'ProgramaService'], 'CU3': ['AsignacionService'],
             'CU4': ['EmbudoService'], 'CU5': ['PerdidaService'], 'CU6': ['ConversionService', 'PagoService', 'ExportacionService'],
             'CU7': ['SeguimientoService'], 'CU8': ['ReporteService']}
CAMPO = {**{s: c for c, s in firmas.CAMPOS.items()}, 'ProgramaService': ''}   # nombre del campo que referencia al servicio
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


# Modelo de dominio inicial (clase 03 del docente: "solo las entidades, sin atributos"): los sustantivos de negocio de
# los requerimientos que el grupo publicó en el foro (14/09/2026), sobre los que se aprobaron los CU (05/10/2026). No
# hubo otra reunión: cada entidad cita el requerimiento del que sale (CONTEXTO_CURSO.md §2).
DOMINIO_INICIAL = {
    'Usuario': 'RF1.1', 'Rol': 'RF1.1', 'Programa': 'RF1.3, RF2.2', 'Módulo': 'RF2.2', 'Prospecto': 'RF1.2, RF2.1',
    'Interés': 'RF2.2, RF3.1', 'Motivo de pérdida': 'RF3.3', 'Matrícula': 'RF3.4', 'Interacción': 'RF4.1, RF4.2',
    'Recordatorio': 'RF4.3'}
RELACIONES_INICIALES = [   # (origen, destino, nombre de la asociación, requerimiento)
    ('Usuario', 'Rol', 'tiene', 'RF1.1'), ('Usuario', 'Programa', 'coordina', 'RF1.3'),
    ('Programa', 'Módulo', 'agrupa', 'RF2.2'), ('Prospecto', 'Interés', 'manifiesta', 'RF2.2'),
    ('Interés', 'Módulo', 'sobre', 'RF2.2'), ('Usuario', 'Interés', 'atiende', 'RF1.2, RF2.3'),
    ('Interés', 'Motivo de pérdida', 'se pierde por', 'RF3.3'), ('Interés', 'Matrícula', 'se convierte en', 'RF3.4'),
    ('Interés', 'Interacción', 'registra', 'RF4.1'), ('Interés', 'Recordatorio', 'programa', 'RF4.3')]

ENTIDADES_CU = {'CU1': ['Usuario', 'Rol', 'Persona'], 'CU2': ['Oferta', 'Prospecto', 'Interes', 'ProspectoService.Alta'],
                'CU3': ['Interes', 'AsignacionService.Asignacion'], 'CU4': ['Interes'], 'CU5': ['MotivoPerdida', 'Interes'],
                'CU6': ['Matricula', 'ConversionService.Requisito', 'PagoService.Orden', 'PagoService.Comprobante',
                        'ExportacionService.Preparacion', 'Interes'],
                'CU7': ['Interaccion', 'Recordatorio', 'SeguimientoService.Historial'], 'CU8': []}
CANAL = ['App', 'Config', 'ControlSondeo', 'BucleSondeo', 'InstanciaCorreo', 'AutenticidadCorreo', 'Bandeja', 'Bandeja.Entrante', 'Bandeja.Saliente', 'Pop3Client', 'SmtpSender', 'Correo', 'Rebotes',
         'CommandProcessor.Atendido']
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
    for viejo in list(SALIDA.glob('*.png')) + list(SALIDA.glob('*.emf')):   # todo lo de EA se regenera
        if viejo.stem not in ('organigrama', 'ubicacion'): viejo.unlink()
    ea = EA()
    try:
        construir(ea)
        malos = sueltos(ea)
    finally:
        ea.cerrar()
    print(f'{len(ea.exportados)} diagramas en {SALIDA}')
    print('Elementos sin relación en su diagrama:', malos or 'ninguno')
    from PIL import Image
    letra = {}
    for a in ea.exportados:
        w, h = Image.open(SALIDA / a).size
        letra[a] = round(20 * max(min(650 / w, 428 / h), min(468 / w, 588 / h)), 1)
    print('Letra impresa estimada (pt), de menor a mayor:')
    for a, pt in sorted(letra.items(), key=lambda x: x[1]): print(f'  {pt:4.1f}  {a}')


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
                # Contrato docente añadido: persona no es equivalente a usuarios/prospectos.
                # Su independencia es deliberada; no se inventa una FK para satisfacer el dibujo.
                if e.Name == 'persona' and d.Name.startswith(('Modelo conceptual:', 'Modelo lógico:')): continue
                if e.Type == 'Class' and e.AssociationClassConnectorID:
                    relacion = ea.r.GetConnectorByID(e.AssociationClassConnectorID)
                    if {relacion.ClientID, relacion.SupplierID} <= ids: continue
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

    # ---------------------------------------------------------------- Requisitos: modelo de dominio inicial
    dom = ea.paquete(req, 'Modelo de dominio inicial')
    d = ea.diagrama(dom, 'Modelo de dominio inicial', 'Logical')
    ent = {n: ea.elemento(dom, n, 'Class', notas=f'Sale de {rf}.') for n, rf in DOMINIO_INICIAL.items()}
    for a, b, nombre, _ in RELACIONES_INICIALES:
        ea.conector(ent[a], ent[b], 'Association', nombre)
    pos, w, h, *_ = acomodar({n: (150, 60) for n in DOMINIO_INICIAL}, [(a, b) for a, b, _, _ in RELACIONES_INICIALES],
                             gx=110, gy=90)
    for n, (x, y, ww, hh) in pos.items():
        ea.poner(d, ent[n], x, y, ww, hh)
    ea.exportar(d, 'dominio-inicial.png')

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
      for suf, titulo, cmds in [('', '', comandos(k))]:   # un diagrama por CU
        d = ea.diagrama(col, f'Colaboración {k}: {CORTO[k]}' + (f' ({titulo})' if titulo else ''), 'Collaboration')
        d.StyleEx = 'SuppressBrackets=1;'; d.Update()
        acts = list(dict.fromkeys(a for _, a, _ in cmds))
        usados = {s for _, _, ps in cmds for s, _, _, dl in ps} | {dl[0] for _, _, ps in cmds for *_, dl in ps if dl}
        sv = [s for s in SERVICIOS[k] if s in usados] or SERVICIOS[k][:1]
        # Una fila: actores (en columna) → cliente de correo → CommandProcessor → RolCommandProcessor → servicios (en
        # columna) → DatabaseService. Las etiquetas se apilan sobre cada enlace: las separaciones salen de cuántas lleva.
        msgs = []
        for j, (cmd, a, pasos) in enumerate(cmds, 1):
            msgs.append((j, act_col[a], bnd, cmd + '[...]', f'{j}', ''))
            msgs.append((j, bnd, cp, 'atender', f'{j}.1', '...'))
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
        DX, H = 215, 60   # compacto: legible al imprimir en una hoja apaisada
        pa = max([pila(act_col[a], bnd) for a in acts])
        ps = max([pila(rcp, servs[s_]) for s_ in sv] + [pila(servs[s_], dbo) for s_ in sv] + [15])
        da, ds = max(170, 2 * pa + 70), max(170, 2 * ps + 70)   # la diagonal vecina apila desde su punto medio
        Yc = 0
        ya = [Yc + (i - (len(acts) - 1) / 2) * da for i in range(len(acts))]
        ys = [Yc + (i - (len(sv) - 1) / 2) * ds for i in range(len(sv))]
        tope = min([Yc - pila(bnd, cp), Yc - pila(cp, rcp)] + [y - pa for y in ya] + [y - ps for y in ys])
        off = 70 - tope
        x1 = 260; x2, x3 = x1 + DX, x1 + 2 * DX; x4 = x3 + DX + 60; x5 = x4 + DX + 40
        for a, y in zip(acts, ya):
            ea.poner(d, act_col[a], 40, int(y + off) - 10, 45, 80)
        for e, x, y in [(bnd, x1, Yc), (cp, x2, Yc), (rcp, x3, Yc), (dbo, x5, Yc)] + [(servs[s_], x4, y) for s_, y in zip(sv, ys)]:
            ea.poner(d, e, x, int(y + off), H, H)
        apil, n = {}, 0
        for j, o, dst, txt, num, args in msgs:
            ln = enlace(o, dst); n += 1
            c = apil.get(ln.ConnectorID, 0); apil[ln.ConnectorID] = c + 1
            ea.mensaje_com(d, ln, o, dst, txt, num, n, c, COLORES[(j - 1) % 4], args)
        ea.exportar(d, f'com-{k.lower()}{suf}.png')

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
    gimap = ea.elemento(dep, 'imap.gmail.com (IMAP 993)', 'ExecutionEnvironment', padre=goo)
    fac = ea.elemento(dep, 'Servidor tecnoweb.org.bo 139.99.68.169 (mail, db)', 'Device')
    so_f = ea.elemento(dep, 'Linux', 'ExecutionEnvironment', padre=fac)
    pg = ea.elemento(dep, 'PostgreSQL 18.6 (5432)', 'ExecutionEnvironment', padre=so_f)
    bd = ea.elemento(dep, 'db_grupo05sa, esquema public', 'Artifact', padre=pg)
    tablas_c = ea.elemento(dep, f'{len(tablas_sql())} tablas (sql/esquema.sql)', 'Component', padre=bd)
    bitac_c = ea.elemento(dep, 'Bandeja de correo (log_comandos, correos_salientes)', 'Component', padre=bd)
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
    # Cuadrícula 2×2 (legible en una hoja apaisada): PC del grupo | servidor de la facultad / usuario | Google
    for e, x, y, w, h, s_ in [(gpc, 30, 30, 570, 480, 9), (so_w, 50, 80, 530, 410, 8),
                              (script, 70, 110, 140, 55, 1), (props, 220, 110, 190, 55, 1), (logf, 420, 110, 150, 55, 1),
                              (jvm, 70, 190, 490, 280, 3), (jar, 90, 240, 450, 210, 2),
                              (capas_jar[0], 110, 285, 200, 52, 1), (capas_jar[1], 325, 285, 200, 52, 1),
                              (capas_jar[2], 110, 365, 200, 52, 1), (capas_jar[3], 325, 365, 200, 52, 1),
                              (fac, 650, 30, 540, 480, 9), (so_f, 670, 80, 500, 410, 8),
                              (pg, 690, 110, 460, 175, 2), (bd, 705, 150, 430, 120, 2),
                              (tablas_c, 720, 195, 175, 60, 1), (bitac_c, 905, 195, 215, 60, 1),
                              (dove, 690, 310, 210, 65, 2), (buzon, 950, 315, 190, 55, 1), (smail, 920, 400, 230, 75, 2),
                              (pc, 30, 570, 300, 160, 9), (cli, 50, 625, 260, 85, 1),
                              (goo, 380, 570, 810, 180, 9), (gweb, 400, 625, 260, 85, 1), (gsmtp, 675, 625, 230, 85, 1),
                              (gimap, 920, 625, 250, 85, 1)]:
        ea.poner(d, e, x, y, w, h, s_)
    for a, b, t_ in [(cli, gweb, 'HTTPS 443'), (gweb, smail, 'SMTP 25'), (jvm, dove, 'POP3 110'),
                     (jvm, pg, 'JDBC TCP 5432'), (jvm, gsmtp, 'SMTP 587 STARTTLS'),
                     (jvm, gimap, 'IMAP 993 TLS (rebotes)')]:
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
    APP, DIAG, CPR, DBS = 'App (sondeo serial mínimo 3 s)', 'Diagnostico (causa de cada falla)', 'CommandProcessor y procesadores por rol', 'DatabaseService (PostgreSQL)'
    BAN = 'Bandeja (entrada y salida persistentes)'
    sitio = {APP: (420, 60), DIAG: (740, 60), BAN: (1020, 60), 'Lector de Correos POP3 (Pop3Client)': (60, 180), 'Correo (MIME)': (380, 180),
             'Emisor SMTP (SmtpSender)': (620, 180), 'Lector de rebotes IMAP (Rebotes)': (920, 180),
             CPR: (470, 350), DBS: (520, 690)}
    dominio = ['usuarios', 'personas', 'captacion', 'asignacion', 'embudo', 'perdidas', 'conversion', 'pagos', 'seguimiento', 'reportes']
    for j, h in enumerate(dominio):
        sitio[h] = (45 + j * 137, 520)
    pk = {}
    for h, (x, y) in sitio.items():
        pk[h] = ea.paquete(cap, h)
        ea.poner(d, pk[h], x, y, 120 if h in dominio else (170 if h == 'Correo (MIME)' else (300 if h == CPR else 250)), 80, seq=1)
    for b in ['Lector de Correos POP3 (Pop3Client)', 'Correo (MIME)', 'Emisor SMTP (SmtpSender)',
              'Lector de rebotes IMAP (Rebotes)', CPR, DIAG, BAN]:
        ea.conector(pk[APP], pk[b], 'Dependency', dirigido=True)
    ea.conector(pk[CPR], pk[DBS], 'Dependency', dirigido=True)
    ea.conector(pk[BAN], pk[DBS], 'Dependency', dirigido=True)
    for h in dominio:
        ea.conector(pk[CPR], pk[h], 'Dependency', dirigido=True)
        ea.conector(pk[h], pk[DBS], 'Dependency', dirigido=True)
    ea.exportar(d, 'capas.png')

    # ---------------------------------------------------------------- Diseño: clases (código)
    cls_pkg = ea.paquete(dis, 'Clases')
    todos = list(dict.fromkeys(CANAL + NUCLEO + [s for ss in SERVICIOS.values() for s in ss]
                               + [e for es in ENTIDADES_CU.values() for e in es]))
    cls = {n: clase(ea, cls_pkg, spec(n), 'Enumeration' if spec(n).split('|')[1] == 'enumeration' else 'Class') for n in todos}
    for sub in PROC_ROL.values():
        ea.conector(cls[sub], cls['RolCommandProcessor'], 'Generalization')
    cls['RolCommandProcessor'].Abstract = '1'; cls['RolCommandProcessor'].Update()
    hechos = set()

    def dep(a, b):
        if (a, b) not in hechos and a != b:
            hechos.add((a, b)); ea.conector(cls[a], cls[b], 'Dependency', dirigido=True)

    # Las del canal de correo y del núcleo, de los campos y las llamadas reales de App, Bandeja y CommandProcessor.
    for a, b in [('App', 'Bandeja'), ('App', 'Pop3Client'), ('App', 'SmtpSender'), ('App', 'Correo'), ('App', 'Rebotes'),
                 ('App', 'Config'), ('App', 'ControlSondeo'), ('App', 'BucleSondeo'), ('App', 'InstanciaCorreo'),
                 ('App', 'AutenticidadCorreo'), ('App', 'SeguimientoService'), ('App', 'ResponseFormatter'),
                 ('DatabaseService', 'Config'),
                 ('App', 'CommandProcessor'), ('App', 'DatabaseService'), ('Bandeja', 'DatabaseService'),
                 ('Bandeja', 'Bandeja.Entrante'), ('Bandeja', 'Bandeja.Saliente'), ('App', 'CommandProcessor.Atendido'),
                 ('CommandProcessor', 'CommandProcessor.Atendido'),
                 ('CommandProcessor', 'CommandParser'), ('CommandProcessor', 'Command'), ('CommandProcessor', 'ResponseFormatter'),
                 ('CommandProcessor', 'RolCommandProcessor'), ('CommandProcessor', 'UsuarioService'), ('CommandProcessor', 'SeguimientoService'),
                 ('CommandParser', 'Command'), ('RolCommandProcessor', 'Command'), ('RolCommandProcessor', 'ResponseFormatter'),
                 ('ProspectoService', 'ProgramaService')]:
        dep(a, b)
    # Lo demás, de los tipos que aparecen en los campos y firmas reales: servicio → entidad, entidad → entidad.
    servicios = [s for ss in SERVICIOS.values() for s in ss]
    for s in servicios:
        dep('RolCommandProcessor', s) if s != 'ProgramaService' else None
        dep(s, 'DatabaseService')
    entidades = [n for n in todos if n not in CANAL + NUCLEO[:10] and n not in servicios]
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

    def caja(nombre, plegada):
        if plegada:
            e = cls[nombre]
            return (nombre, int(50 + 7 * max(len(e.Name), len(e.Stereotype) + 4)), 44 + (16 if e.Stereotype else 0))
        return (nombre, ancho(nombre), alto(nombre))

    def figura_clases(nombre_d, archivo, grupos, plegadas):
        """grupos: [[clase]] por fila lógica; plegadas: clases que aquí van solo con su nombre."""
        d = ea.diagrama(cls_pkg, nombre_d, 'Logical')
        nodos = [n for g in grupos for n in g]
        medidas = {n: caja(n, n in plegadas)[1:] for n in nodos}
        aristas = [e for e in hechos | {(r, 'RolCommandProcessor') for r in PROC_ROL.values()} if e[0] in medidas and e[1] in medidas]
        pos, w, h, at, cr = acomodar(medidas, aristas, gx=55, gy=75)
        for n, (x, y, ww, hh) in pos.items():
            ea.poner(d, cls[n], x, y, ww, hh, plegado=n in plegadas)
        ea.exportar(d, archivo)
        print(f'      {archivo}: {w}×{h} → {efectivo(w, h):.1f} pt; líneas sobre cajas {at}, cruces {cr}')

    # ---------------------------------------------------------------- Diseño: procedimental
    # Un diseño procedimental completo por CU, como el P1 aprobado. El canal tiene una vista global propia.
    COMUNES = [
        ('Canal de correo: modelo global', list(dict.fromkeys(CANAL + ['CommandProcessor', 'DatabaseService'])), set())]
    for i, (titulo, clases, plegadas) in enumerate(COMUNES, 1):
        figura_clases(titulo, 'cls-correo-global.png', [clases], plegadas)
    for k in CU:
        sv_cu = [s_ for s_ in SERVICIOS[k] if s_ != 'UsuarioService' or k == 'CU1']
        ents_cu = list(dict.fromkeys(ENTIDADES_CU[k] + (['Usuario', 'Rol'] if k == 'CU1' else ['Usuario'])))
        roles = [PROC_ROL[a] for a in actores(k)]
        llamador = ['Command', 'CommandParser', 'CommandProcessor', 'RolCommandProcessor', 'ResponseFormatter',
                    'UsuarioService'] + roles
        for titulo, sv, ents in [('', sv_cu, ents_cu)]:   # un diagrama por CU
            ents = list(dict.fromkeys(ents + ['Usuario', 'Rol']))
            plegadas = set()
            suf = ''
            figura_clases(f'{k}_' + re.sub(r'\W+', '_', CORTO[k]).strip('_') + (f' ({titulo})' if titulo else ''),
                          f'cls-{k.lower()}{suf}.png', [list(dict.fromkeys(llamador + ['DatabaseService'] + sv + ents))], plegadas)

    secuencias(ea, dis)
    modelo_datos(ea, dis)

    # ---------------------------------------------------------------- Implementación: componentes
    componentes(ea, imp, servicios)


def componentes(ea, imp, servicios):
    """Vista global de implementación: componentes, dependencias y ensamblajes UML sin símbolos duplicados."""
    d = ea.diagrama(imp, 'Diagrama de componentes', 'Component')

    def comp(nombre, st='', padre=None):
        return ea.elemento(imp, nombre, 'Component', st, padre=padre)

    c_sis = comp('Sistema CRM vía e-mail', 'subsystem')
    c_pre, c_apl, c_dom, c_per = (comp(n, padre=c_sis) for n in ('Capa de Presentación', 'Capa de Aplicación', 'Capa de Dominio', 'Capa de Persistencia'))
    app_c = comp('App', padre=c_pre)
    diag_c = comp('Diagnostico', padre=c_pre)
    ban_c = comp('Bandeja', padre=c_pre)
    lector = comp('Pop3Client / Correo', padre=c_pre)
    emisor = comp('SmtpSender / Rebotes', padre=c_pre)
    proc = comp('CommandProcessor / CommandParser', padre=c_apl)
    porrol = comp('RolCommandProcessor y 4 subclases', padre=c_apl)
    fmt_c = comp('ResponseFormatter', padre=c_apl)
    servs_c = [comp(s, 'service', padre=c_dom) for s in [s for s in servicios if s != 'UsuarioService'] + ['UsuarioService']]   # al final: junto a jBCrypt
    dbs = comp('DatabaseService', padre=c_per)
    angus = comp('Angus Mail 2.0.5', 'library')
    pgj = comp('PostgreSQL JDBC 42.7.13', 'library')
    bcr = comp('jBCrypt 0.4', 'library')
    dov = comp('Dovecot mail.tecnoweb.org.bo', 'system extern')
    gm = comp('Gmail: smtp.gmail.com / imap.gmail.com', 'system extern')
    pgc = comp('PostgreSQL 18.6 db.tecnoweb.org.bo', 'Database')
    jar_a = ea.elemento(imp, 'crm-mail.jar', 'Artifact', 'executable')
    # Los ensamblajes de EA ya dibujan bola/socket. No añadir otra pareja sobre sus extremos.
    XL, XE = 1060, 1460
    # Corredor libre a la izquierda (x 262-372): Bandeja bajo App y DatabaseService debajo, sin cajas entre ambos.
    for e, x, y, w, h, s_ in [(c_sis, 20, 20, 890, 750, 10),
                              (c_pre, 40, 80, 850, 200, 9), (app_c, 60, 120, 170, 50, 1),
                              (ban_c, 60, 210, 170, 45, 1), (diag_c, 340, 210, 210, 45, 1),
                              (lector, 660, 120, 210, 50, 1), (emisor, 660, 210, 210, 45, 1),
                              (c_apl, 40, 310, 850, 130, 9), (proc, 60, 355, 240, 55, 1),
                              (porrol, 350, 355, 230, 55, 1), (fmt_c, 650, 355, 220, 55, 1),
                              (c_dom, 40, 470, 850, 170, 9), (c_per, 40, 670, 850, 80, 9),
                              (dbs, 60, 710, 250, 30, 1),
                              (angus, XL, 200, 220, 80, 1), (bcr, XL, 560, 220, 55, 1),
                              (pgj, XL, 690, 220, 65, 1), (dov, XE, 110, 240, 60, 1),
                              (gm, XE, 210, 240, 90, 1), (pgc, XE, 680, 240, 80, 1),
                              (jar_a, 355, 805, 220, 55, 1)]:
        ea.poner(d, e, x, y, w, h, s_)
    for i, s in enumerate(servs_c):
        ea.poner(d, s, 60 + (i % 6) * 137, 515 + (i // 6) * 62, 127, 48, 1)
    for ra, pa, nombre in [(lector, dov, 'POP3 · TCP 110'), (emisor, angus, 'Jakarta Mail'),
                           (angus, gm, 'SMTP 587 STARTTLS / IMAP 993 TLS'),
                           (dbs, pgj, 'JDBC'), (pgj, pgc, 'PostgreSQL · TCP 5432')]:
        ea.conector(ra, pa, 'Assembly', nombre)
    # Dependencias por capa: el detalle de métodos y clases está en los procedimentales de cada CU.
    for a_, b_, t_ in [(app_c, lector, ''), (app_c, ban_c, ''), (app_c, diag_c, ''),
                       (diag_c, emisor, ''), (c_pre, c_apl, ''), (proc, porrol, ''),
                       (porrol, fmt_c, ''), (c_apl, c_dom, ''), (c_dom, c_per, ''),
                       (servs_c[-1], bcr, '')]:
        ea.conector(a_, b_, 'Dependency', t_, dirigido=True)
    # Bandeja tiene acceso directo a persistencia; corredor izquierdo fuera de las cajas interiores.
    c_bandeja = ea.conector(ban_c, dbs, 'Dependency', dirigido=True)
    ea.conector(jar_a, c_sis, 'Manifest', dirigido=True)
    ea.r.SaveDiagram(d.DiagramID); ea.r.ReloadDiagram(d.DiagramID); d.DiagramLinks.Refresh()
    for link in d.DiagramLinks:
        if link.ConnectorID == c_bandeja.ConnectorID:
            link.LineStyle = 3; link.Path = '30:-232;30:-725;'; link.Update()
    ea.exportar(d, 'componentes.png')


# ---------------------------------------------------------------- Diseño: secuencia
SLOT, Y1 = 35, 133   # alto fijo de cada mensaje en EA 15 y y del primero con las líneas de vida en y=40 (medidos)
ROBUSTEZ = {'Pop3Client': 'boundary', 'SmtpSender': 'boundary', 'Rebotes': 'boundary', 'Logger': 'boundary',
            'Message': 'boundary', 'DatabaseService': 'entity'}


def ancho_msg(texto):
    return 30 + 4.4 * len(texto)   # medido en el PNG: ~4,4 unidades por carácter, más la punta de la flecha


def secuencias(ea, dis):
    """Cuatro interacciones comunes del canal con fixed delay mínimo de 3000 ms, y una por CU: el flujo común una vez y un
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
        xs = [80 + guarda.get(0, 0)]
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

    # --- Recibir y guardar: App.descargar(); cada correo queda en la BD antes de su DELE
    d = ea.diagrama(seq, 'Recibir y guardar', 'Sequence'); d.StyleEx = 'SuppressBrackets=1;'; d.Update()
    o = {'App': vida('App', ''), 'db': vida('DatabaseService', 'db'), 'pop': vida('Pop3Client', 'pop'),
         'cor': vida('Correo', ''), 'ban': vida('Bandeja', 'bandeja')}
    cols = [rem, o['pop'], o['App'], o['cor'], o['ban'], o['db']]
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
             (o['App'], o['cor'], '1.5 idMensaje', 'call', firma('Correo', 'idMensaje')),
             (o['App'], o['cor'], '1.6 leer', 'call', firma('Correo', 'leer')),
             (o['App'], o['ban'], '1.7 guardar', 'call', args_msg(firma('Bandeja', 'guardar'), 34)),
             (o['ban'], o['db'], '1.8 uno', 'call', 'INSERT ... ON CONFLICT DO NOTHING'),
             (o['ban'], o['App'], 'Long', 'return', ''),
             (o['App'], o['pop'], '1.9 dele', 'call', 'i'),
             (o['App'], o['pop'], '1.10 quit', 'call', ' ')]
    frags = [(4, [('fixed delay >= 3000 ms desde fin; sin superposición ni diagnóstico paralelo', 2, 17)], 1, 5, 0),
             (1, [('BD y modelo disponibles; si fallan se conserva el buzón sin consultarlo', 3, 17)], 1, 5, 1),
             (4, [('por mensaje, máximo 20', 5, 16)], 1, 5, 2),
             (0, [('tamaño > 8 MB', 7, 8), ('else', 9, 10)], 1, 2, 3)]
    dibujar(d, cols, pasos, frags, 'sd-recibir.png')

    # --- Procesar un correo guardado: App.procesarUno() en UNA transacción
    d = ea.diagrama(seq, 'Procesar un correo guardado', 'Sequence'); d.StyleEx = 'SuppressBrackets=1;'; d.Update()
    o = {'App': vida('App', ''), 'db': vida('DatabaseService', 'db'), 'ban': vida('Bandeja', 'bandeja'),
         'cor': vida('Correo', ''), 'auth': vida('AutenticidadCorreo', 'autenticidad'),
         'proc': vida('CommandProcessor', 'procesador'), 'smtp': vida('SmtpSender', 'smtp')}
    cols = [o['App'], o['db'], o['ban'], o['cor'], o['auth'], o['proc'], o['smtp']]
    pasos = [(o['App'], o['ban'], '2. porProcesar', 'call', 'max'),
             (o['ban'], o['App'], 'List<Long>', 'return', ''),
             (o['App'], o['db'], '2.1 enTransaccion', 'call', 'c -> procesarUno(c, nro)'),
             (o['App'], o['ban'], '2.2 tomar', 'call', firma('Bandeja', 'tomar')),
             (o['ban'], o['App'], 'Entrante', 'return', ''),
             (o['App'], o['cor'], '2.3 leer', 'call', 'contenido'),
             (o['App'], o['ban'], '2.4 cerrar', 'call', 'sin remitente / automático / propio: sin respuesta'),
             (o['App'], o['db'], '2.5 uno', 'call', 'SELECT EXISTS usuario registrado'),
             (o['App'], o['auth'], '2.6 verificar', 'call', 'contenido, Instant.now()'),
             (o['auth'], o['App'], 'Resultado: HMAC y plazo válidos', 'return', ''),
             (o['App'], o['ban'], '2.7 cerrar', 'call', 'Denegado; autenticidad no acreditada'),
             (o['App'], o['App'], '2.8 encolar', 'call', 'respuesta de denegación'),
             (o['App'], o['proc'], '2.9 interpretar', 'call', firma('CommandProcessor', 'interpretar')),
             (o['proc'], o['App'], 'Command', 'return', ''),
             (o['App'], o['db'], '2.10 uno', 'call', 'rol, activo y vigencia actuales'),
             (o['App'], o['proc'], '2.11 procesadores', 'call', ' '),
             (o['proc'], o['App'], 'matriz de permisos del rol actual', 'return', ''),
             (o['App'], o['ban'], '2.12 repetido', 'call', firma('Bandeja', 'repetido')),
             (o['ban'], o['App'], 'Long', 'return', ''),
             (o['App'], o['ban'], '2.13 reenviar', 'call', firma('Bandeja', 'reenviar')),
             (o['App'], o['ban'], '2.14 cerrar', 'call', 'c, nro, null, "Repetido", ...'),
             (o['App'], o['db'], '2.15 aislado', 'call', '() -> procesador.atender(...)'),
             (o['App'], o['proc'], '2.16 atender', 'call', args_msg(firma('CommandProcessor', 'atender'), 34)),
             (o['proc'], o['App'], 'Atendido', 'return', ''),
             (o['App'], o['ban'], '2.17 cerrar', 'call', args_msg(firma('Bandeja', 'cerrar'), 34)),
             (o['App'], o['smtp'], '2.18 preparar', 'call', args_msg(firma('SmtpSender', 'preparar'), 34)),
             (o['smtp'], o['App'], 'byte[]', 'return', ''),
             (o['App'], o['ban'], '2.19 encolar', 'call', args_msg(firma('Bandeja', 'encolar'), 34)),
             (o['ban'], o['App'], 'long', 'return', ''),
             (o['db'], o['App'], 'COMMIT: operación, registro y respuesta en cola juntos', 'return', ''),
             (o['db'], o['App'], 'ROLLBACK; SQLException de conexión', 'return', ''),
             (o['App'], o['App'], '2.20 falla', 'call', 'BD; conservar Recibido; backoff >= 3000 ms'),
             (o['db'], o['App'], 'ROLLBACK; excepción interna no relacionada con conexión', 'return', ''),
             (o['App'], o['db'], '2.21 enTransaccion', 'call', 'respuesta de contingencia'),
             (o['App'], o['ban'], '2.22 tomar', 'call', 'c, nro'),
             (o['App'], o['cor'], '2.23 leer', 'call', 'contenido'),
             (o['App'], o['App'], '2.24 encolar', 'call', 'respuesta de error sin confirmar operación'),
             (o['App'], o['ban'], '2.25 cerrar', 'call', 'Error; contingencia en cola'),
             (o['db'], o['App'], 'COMMIT contingencia', 'return', ''),
             (o['db'], o['App'], 'ROLLBACK contingencia; sigue Recibido para recuperación', 'return', '')]
    frags = [(4, [('por cada correo guardado; una transacción por solicitud', 3, 40)], 0, 6, 0),
             (0, [('proceso sin excepción', 4, 30), ('conexión perdida: sigue Recibido', 31, 32),
                  ('fallo interno: contingencia', 33, 40)], 0, 6, 1),
             (0, [('sin remitente / automático / propio', 7, 7), ('correo procesable de tamaño permitido', 8, 29)], 0, 6, 2),
             (1, [('usuario registrado', 9, 10)], 0, 4, 3),
             (0, [('registrado: firma no válida', 11, 12), ('firma válida o visitante', 13, 29)], 0, 6, 3),
             (1, [('escritura y permiso actual antes del reintento', 18, 19)], 0, 2, 4),
             (0, [('repetido autorizado', 20, 21), ('else', 22, 29)], 0, 6, 4),
             (0, [('respuesta de contingencia confirmable', 34, 39), ('contingencia no confirmable', 40, 40)], 0, 6, 2)]
    dibujar(d, cols, pasos, frags, 'sd-procesar.png', extra_der=60)

    # --- Enviar la cola de salida: App.enviarCola(); el envío se reintenta sin repetir la operación
    d = ea.diagrama(seq, 'Enviar la cola de salida', 'Sequence'); d.StyleEx = 'SuppressBrackets=1;'; d.Update()
    o2 = {'App': vida('App', ''), 'db': vida('DatabaseService', 'db'), 'ban': vida('Bandeja', 'bandeja'),
          'smtp': vida('SmtpSender', 'smtp')}
    cols = [o2['App'], o2['db'], o2['ban'], o2['smtp'], rem]
    pasos = [(o2['App'], o2['ban'], '3. porEnviar', 'call', 'max'),
             (o2['ban'], o2['App'], 'List<Long>', 'return', ''),
             (o2['App'], o2['db'], '3.1 enTransaccion', 'call', 'c -> enviarUno(c, k)'),
             (o2['App'], o2['ban'], '3.2 tomarSaliente', 'call', firma('Bandeja', 'tomarSaliente')),
             (o2['ban'], o2['App'], 'Saliente', 'return', ''),
             (o2['App'], o2['smtp'], '3.3 enviarPreparado', 'call', firma('SmtpSender', 'enviarPreparado')),
             (o2['smtp'], rem, '3.4 respuesta HTML (mismo Message-ID)', 'signal', ''),
             (o2['App'], o2['ban'], '3.5 enviado', 'call', firma('Bandeja', 'enviado')),
             (o2['App'], o2['ban'], '3.6 fallo', 'call', firma('Bandeja', 'fallo')),
             (o2['ban'], o2['App'], 'Integer: 1, 2, 4 ... 60 min, o null si Fallido', 'return', '')]
    frags = [(4, [('por cada correo cuyo turno llegó', 3, 10)], 0, 4, 0),
             (0, [('Gmail lo aceptó', 7, 8), ('falló', 9, 10)], 0, 4, 1)]
    dibujar(d, cols, pasos, frags, 'sd-enviar.png', extra_der=40)

    # --- Leer rebotes: respuestas que Gmail aceptó y el servidor del destinatario rechazó después (App.revisarRebotes)
    d = ea.diagrama(seq, 'Leer rebotes', 'Sequence'); d.StyleEx = 'SuppressBrackets=1;'; d.Update()
    o3 = {'App': vida('App', ''), 'reb': vida('Rebotes', 'rebotes'), 'ban': vida('Bandeja', 'bandeja'),
          'log': vida('Logger', 'LOG'), 'seg': vida('SeguimientoService', 'seguimiento'),
          'db': vida('DatabaseService', 'db'), 'aviso': vida('Message', 'aviso')}
    cols = [o3['aviso'], o3['reb'], o3['App'], o3['ban'], o3['seg'], o3['db'], o3['log']]
    pasos = [(o3['App'], o3['App'], '4. revisarRebotesSiCorresponde', 'call', 'this::revisarRebotes'),
             (o3['App'], o3['App'], '4.1 revisarRebotes', 'call', ' '),
             (o3['App'], o3['reb'], '4.2 procesar', 'call', 'callback de confirmación'),
             (o3['reb'], o3['aviso'], '4.3 writeTo', 'call', 'crudo; IMAP peek=true'),
             (o3['reb'], o3['reb'], '4.4 leer', 'call', 'crudo.toByteArray()'),
             (o3['reb'], o3['App'], '4.5 registrarRebote', 'call', 'rebote'),
             (o3['App'], o3['db'], '4.6 enTransaccion', 'call', 'actualización y bitácora juntas'),
             (o3['App'], o3['ban'], '4.7 rebote', 'call', 'destinatario, motivo, originalMessageId'),
             (o3['ban'], o3['db'], '4.8 actualizar', 'call', 'UPDATE respuesta con Message-ID exacto'),
             (o3['App'], o3['seg'], '4.9 registrarComando', 'call', 'DSN correlacionado o aviso sin correlación'),
             (o3['seg'], o3['db'], '4.10 actualizar', 'call', 'INSERT bitácora del DSN'),
             (o3['db'], o3['App'], 'COMMIT: resultado y bitácora del DSN', 'return', ''),
             (o3['App'], o3['App'], 'int: respuestas correlacionadas', 'return', ''),
             (o3['App'], o3['log'], '4.11 warning', 'call', 'REBOTE registrado; sin datos personales'),
             (o3['App'], o3['reb'], 'callback confirmado', 'return', ''),
             (o3['reb'], o3['aviso'], '4.12 setFlag', 'call', 'Flags.Flag.SEEN, true'),
             (o3['db'], o3['App'], 'ROLLBACK; SQLException', 'return', ''),
             (o3['App'], o3['reb'], 'callback fallido', 'return', ''),
             (o3['reb'], o3['App'], 'MessagingException; aviso permanece sin leer', 'return', ''),
             (o3['App'], o3['App'], '4.13 falla', 'call', 'IMAP; reintento tras el plazo'),
             (o3['reb'], o3['App'], 'int: avisos procesados', 'return', '')]
    dibujar(d, cols, pasos, [(1, [('>= 60000 ms desde FIN último intento; también al fallar', 2, 21)], 0, 6, 0),
                             (4, [('por cada DSN sin leer', 4, 20)], 0, 6, 1),
                             (0, [('persistencia confirmada', 12, 16), ('falló persistencia: sin SEEN', 17, 20)], 0, 6, 2)],
            'sd-rebotes.png', extra_der=120)

    # --- Una por CU (o por parte): el correo guardado entra por App.atender() y vuelve como Atendido
    for k in CU:
      for suf, titulo, cmds in [('', '', comandos(k))]:  # exactamente una secuencia por caso de uso
        d = ea.diagrama(seq, f'{k}: {CORTO[k]}' + (f' ({titulo})' if titulo else ''), 'Sequence')
        d.StyleEx = 'SuppressBrackets=1;'; d.Update()
        app = vida('App', ''); proc = vida('CommandProcessor', 'procesador'); rol = vida('RolCommandProcessor', '')
        fmt = vida('ResponseFormatter', 'fmt')
        usados = [s_ for s_ in SERVICIOS[k] if any(s_ == x[0] or (x[3] and x[3][0] == s_) for _, _, ps in cmds for x in ps)]
        sv = {s_: vida(s_, CAMPO[s_]) for s_ in usados}
        db = vida('DatabaseService', 'db')
        cols = [rem, app, proc, rol, fmt] + list(sv.values()) + [db]
        pasos = [(rem, app, '1. COMANDO[...] (correo guardado)', 'signal', ''),
                 (app, proc, '1.1 atender', 'call', 'remitente, asunto, csv, ...'),
                 (proc, rol, '1.2 procesar', 'call', firma('RolCommandProcessor', 'procesar')),
                 (rol, rol, '1.3 comandos', 'call', ' '),
                 (rol, fmt, '1.4 denegado', 'call', firma('ResponseFormatter', 'denegado')),
                 (fmt, rol, 'Respuesta', 'return', ''),
                 (rol, rol, '1.4 ejecutar', 'call', 'c, u')]
        operandos, ultimo = [], 5
        for cmd, _, llam in cmds:
            ini, nro = len(pasos) + 1, 5
            for s_, met, dbm, dlg in llam:
                pasos.append((rol, sv[s_], f'1.{nro} {met}', 'call', args_msg(firma(s_, met)) or ' ')); nro += 1
                origen = sv[s_]
                if dlg:
                    pasos.append((sv[s_], sv[dlg[0]], f'1.{nro} {dlg[1]}', 'call', args_msg(firma(*dlg)) or ' ')); nro += 1
                    origen = sv[dlg[0]]
                if dbm:
                    pasos.append((origen, db, f'1.{nro} {dbm}', 'call', args_msg(firma('DatabaseService', dbm)) or ' ')); nro += 1
            operandos.append((cmd, ini, len(pasos)))
            ultimo = max(ultimo, nro)
        fin_alt = len(pasos)
        pasos += [(rol, proc, 'Respuesta', 'return', ''),
                  (proc, app, 'Atendido', 'return', ''),
                  (app, rem, f'1.{ultimo} respuesta HTML (por la cola de salida)', 'signal', '')]
        der = len(cols) - 1
        frags = [(0, [('no permitido al rol', 5, 6), ('else', 7, fin_alt)], 3, der, 0),
                 (0, [(c, a_, b_) for c, a_, b_ in operandos], 3, der, 1)]
        dibujar(d, cols, pasos, frags, f'sd-{k.lower()}{suf}.png')


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


ROL_FK = {'vendedor_correo': 'vendedor', 'coordinador_correo': 'coordinador', 'actualizado_por': 'actualizado por',
          'modulo_codigo': 'módulo', ('log_comandos', 'usuario_correo'): 'emisor', ('interacciones', 'usuario_correo'): 'autor',
          ('movimientos_interes', 'usuario_correo'): 'autor', ('recordatorios', 'usuario_correo'): 'responsable',
          ('exportaciones', 'usuario_correo'): 'responsable', ('notificaciones', 'usuario_correo'): 'destinatario',
          ('correos_salientes', 'nro_registro'): 'responde a', ('correos_salientes', 'nro_recordatorio'): 'avisa'}


# Una vista global por nivel, como el modelo de datos de los documentos aprobados.
AREAS_LOGICO = [
    ('Modelo lógico completo', 'datos-global', list(tablas_sql()))]
AREAS = [('Modelo conceptual completo', 'conceptual-global', list(tablas_sql()))]

# Modelo conceptual (= modelo de dominio final, clase 03): por entidad, sus atributos esenciales de negocio, sin tipos,
# sin claves ni métodos. Los nombres son los de la base para poder cotejarlos uno a uno; los demás campos de cada tabla
# están en su mapeo. Las relaciones son las claves foráneas; la intermedia N-N pura (orden_pago_descuentos) es una
# clase de asociación unida con línea punteada a la relación entre sus dos entidades fuertes.
ESENCIALES = {
    'persona': ['documento', 'nombres', 'apellidos', 'ocupacion', 'telefono_fijo', 'telefono_celular', 'correo'],
    'roles': ['nombre', 'descripcion'], 'usuarios': ['correo', 'nombre', 'apellido', 'estado', 'fecha_inicio', 'fecha_fin'],
    'api_tokens': ['token_hash', 'expira_en'], 'configuraciones': ['clave', 'valor'],
    'notificaciones': ['tipo', 'mensaje', 'estado'], 'log_comandos': ['remitente', 'asunto', 'resultado', 'fecha'],
    'correos_salientes': ['destinatario', 'asunto', 'estado', 'intentos'],
    'prospectos': ['documento', 'nombre', 'apellido', 'telefono', 'origen'],
    'catalogo_cache': ['codigo', 'nombre', 'precio', 'estado'], 'programas': ['codigo', 'nombre'],
    'programas_responsables': ['programa_codigo'], 'intereses': ['nro_tarjeta', 'estado', 'fecha', 'proximo_contacto'],
    'motivos_perdida': ['nombre', 'categoria'], 'movimientos_interes': ['fecha', 'estado_anterior', 'estado_nuevo'],
    'interacciones': ['tipo', 'descripcion', 'fecha'], 'recordatorios': ['fecha_programada', 'descripcion', 'estado'],
    'ordenes_pago': ['nro_orden', 'subtotal', 'descuento_total', 'total', 'estado'],
    'orden_pago_descuentos': ['porcentaje', 'monto'], 'descuentos': ['codigo', 'nombre', 'porcentaje'],
    'pagos': ['monto', 'codigo_qr', 'estado'], 'comprobantes_pago': ['nro_comprobante', 'archivo', 'estado_revision'],
    'solicitudes_compra': ['nro_solicitud', 'modulo_nombre', 'precio_acordado', 'estado'],
    'inscripciones_comerciales': ['estado', 'fecha_confirmacion'], 'comisiones': ['porcentaje', 'monto', 'estado'],
    'exportaciones': ['nro_exportacion', 'estado', 'fecha_exportacion']}
NN = ('orden_pago_descuentos', 'ordenes_pago', 'descuentos')   # intermedia, entidad fuerte, entidad fuerte


def modelo_datos(ea, dis):
    tablas, fks, unicas, pks = tablas_sql(), fks_sql(), unicas_sql(), pks_sql()
    assert sorted(t for _, _, ts in AREAS for t in ts) == sorted(tablas), 'las áreas deben tener todas las tablas'
    assert sorted(ESENCIALES) == sorted(tablas), 'el conceptual debe tener todas las tablas'
    for t, cs in ESENCIALES.items():
        assert set(cs) <= {c for c, _, _ in tablas[t]}, f'{t}: atributo que no existe en la base'
    card = lambda hija, c: ('0..1' if (hija, c) in unicas or pks.get(hija) == [c] else '0..*',
                            '1' if next(x[2] for x in tablas[hija] if x[0] == c) else '0..1')

    # ---------------------------------------------------------------- conceptual (modelo de dominio final)
    con = ea.paquete(dis, 'Modelo conceptual')
    ent = {}
    for t in tablas:
        e = ea.elemento(con, t, 'Class')
        for i, c in enumerate(ESENCIALES[t]):
            a_ = e.Attributes.AddNew(c, ''); a_.Visibility = 'Public'; a_.Pos = i; a_.Update()
        e.Update(); ent[t] = e
    nn = ea.conector(ent[NN[1]], ent[NN[2]], 'Association', card=('0..*', '0..*'))
    if not ent[NN[0]].CreateAssociationClass(nn.ConnectorID):
        raise SystemExit('EA no creó la clase de asociación ' + NN[0])
    for hija, c, padre, _ in fks:
        if hija == NN[0]: continue   # sus dos FK son la relación N-N de arriba
        ea.conector(ent[hija], ent[padre], 'Association', ROL_FK.get((hija, c)) or ROL_FK.get(c, ''), card=card(hija, c))
    caja = lambda t: (int(44 + 5.0 * max(len(t), *(len(x) + 4 for x in ESENCIALES[t]))), 40 + 12 * len(ESENCIALES[t]))
    # El conceptual usa la asociación N a N entre las entidades fuertes, no las dos FK de la intermedia.
    relaciones = [(h_, p_) for h_, _, p_, _ in fks if h_ != NN[0]] + [(NN[1], NN[2])]
    for titulo, archivo, propias in AREAS:
        vecinas = list(dict.fromkeys(x for h_, p_ in relaciones for x in ((p_,) if h_ in propias else (h_,) if p_ in propias else ())
                                     if x not in propias))
        d = ea.diagrama(con, f'Modelo conceptual: {titulo}', 'Logical')
        medidas = {**{t: caja(t) for t in propias}, **{t: (int(50 + 7 * len(t)), 44) for t in vecinas}}
        pos, w, h, at, cr = acomodar(medidas, relaciones, gx=70, gy=80)
        pos, w, h, at, cr = despejar_cajas(pos, relaciones)
        for t, (x, y, ww, hh) in pos.items():
            ea.poner(d, ent[t], x, y, ww, hh, plegado=t in vecinas)
        ea.exportar(d, archivo + '.png')
        print(f'      {archivo}.png: {w}×{h} → {efectivo(w, h):.1f} pt; líneas sobre cajas {at}, cruces {cr}')

    # ---------------------------------------------------------------- lógico por áreas
    datos = ea.paquete(dis, 'Modelo de datos')
    col_fk = {(h_, c) for h_, c, _, _ in fks}
    elem, medida = {}, {}
    for nombre, cols in tablas.items():
        e = ea.elemento(datos, nombre, 'Class')
        pk = pks.get(nombre, [])
        # la clave primaria primero; las FK que no son clave se ven como asociaciones, no como atributos
        visibles = [c for c in cols if c[0] in pk] + [c for c in cols if c[0] not in pk and (nombre, c[0]) not in col_fk]
        for i, (c, tipo, _) in enumerate(visibles):
            a_ = e.Attributes.AddNew(c, tipo); a_.Visibility = 'Private'; a_.Pos = i; a_.Update()
        e.Update(); elem[nombre] = e
        medida[nombre] = (int(44 + 5.0 * max(len(nombre), *(len(f'- {c}: {t}') for c, t, _ in visibles))), 44 + 12 * (len(visibles) - 1))
    for hija, c, padre, _ in fks:
        ea.conector(elem[hija], elem[padre], 'Association', ROL_FK.get((hija, c)) or ROL_FK.get(c, ''), card=card(hija, c))
    assert sorted(t for _, _, ts in AREAS_LOGICO for t in ts) == sorted(tablas)
    for titulo, archivo, propias in AREAS_LOGICO:
        vecinas = list(dict.fromkeys(x for h_, _, p_, _ in fks for x in ((p_,) if h_ in propias else (h_,) if p_ in propias else ())
                                     if x not in propias))
        d = ea.diagrama(datos, f'Modelo lógico: {titulo}', 'Logical')
        medidas = {**{t: medida[t] for t in propias}, **{t: (int(50 + 7 * len(t)), 44) for t in vecinas}}
        relaciones = [(h_, p_) for h_, _, p_, _ in fks]
        pos, w, h, at, cr = acomodar(medidas, relaciones, gx=70, gy=80)
        pos, w, h, at, cr = despejar_cajas(pos, relaciones)
        for t, (x, y, ww, hh) in pos.items():
            ea.poner(d, elem[t], x, y, ww, hh, plegado=t in vecinas)
        ea.exportar(d, archivo + '.png')
        print(f'      {archivo}.png: {w}×{h} → {efectivo(w, h):.1f} pt; líneas sobre cajas {at}, cruces {cr}')


def actualizar_secuencias():
    """Regenera solo las interacciones en el modelo EA existente, preservando el resto del diseño."""
    ea = EA.__new__(EA)
    ea.r = win32com.client.Dispatch('EA.Repository')
    if not ea.r.OpenFile(str(EAPX)): raise SystemExit('EA no abrió ' + str(EAPX))
    ea.pi = ea.r.GetProjectInterface()
    ea.exportados = []
    try:
        ea.modelo = next(p for p in ea.r.Models if p.Name == 'CRM vía e-mail (P1, Grupo 05 SA)')
        dis = next(p for p in ea.modelo.Packages if p.Name == '3. Diseño')
        for i in range(dis.Packages.Count - 1, -1, -1):
            if dis.Packages.GetAt(i).Name == 'Secuencia': dis.Packages.DeleteAt(i, False)
        dis.Packages.Refresh()
        secuencias(ea, dis)
        print(str(len(ea.exportados)) + ' secuencias actualizadas en EA')
    finally:
        ea.cerrar()


def actualizar_modelos():
    """Regenera conceptual/lógico completos y componentes en el modelo EA existente."""
    ea = EA.__new__(EA)
    ea.r = win32com.client.Dispatch('EA.Repository')
    if not ea.r.OpenFile(str(EAPX)): raise SystemExit('EA no abrió ' + str(EAPX))
    ea.pi = ea.r.GetProjectInterface()
    ea.exportados = []
    try:
        ea.modelo = next(p for p in ea.r.Models if p.Name == 'CRM vía e-mail (P1, Grupo 05 SA)')
        dis = next(p for p in ea.modelo.Packages if p.Name == '3. Diseño')
        imp = next(p for p in ea.modelo.Packages if p.Name == '4. Implementación')
        for i in range(dis.Packages.Count - 1, -1, -1):
            if dis.Packages.GetAt(i).Name in ('Modelo conceptual', 'Modelo de datos'):
                dis.Packages.DeleteAt(i, False)
        dis.Packages.Refresh()
        for i in range(imp.Diagrams.Count - 1, -1, -1):
            if imp.Diagrams.GetAt(i).Name == 'Diagrama de componentes': imp.Diagrams.DeleteAt(i, False)
        imp.Diagrams.Refresh()
        for i in range(imp.Elements.Count - 1, -1, -1):
            if imp.Elements.GetAt(i).Type in ('Component', 'Interface'): imp.Elements.DeleteAt(i, False)
        imp.Elements.Refresh()
        modelo_datos(ea, dis)
        componentes(ea, imp, [s for ss in SERVICIOS.values() for s in ss])
        print(str(len(ea.exportados)) + ' modelos globales actualizados en EA')
    finally:
        ea.cerrar()


if __name__ == '__main__':
    if sys.argv[1:] == ['--secuencias']: actualizar_secuencias()
    elif sys.argv[1:] == ['--modelos']: actualizar_modelos()
    else: main()
