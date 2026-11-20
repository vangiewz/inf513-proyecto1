"""Firmas de las clases Java de p1/src para los diagramas de EA, leídas del código (no escritas a mano).
spec('Nombre') -> 'Nombre|estereotipo|-attr: T; ...|+op(p: T): R; ...' (formato de ea_p1.clase()).
Atributos: campos de instancia y componentes de record. Operaciones: públicas y protegidas (sin constructores ni
toString/equals/hashCode). '$' tras la visibilidad = static.
Uso: python firmas.py  -> imprime todas las firmas (comprobación rápida)."""
import pathlib, re

SRC = pathlib.Path(__file__).resolve().parents[2] / 'src' / 'main' / 'java'
VIS = {'public': '+', 'protected': '#', 'private': '-'}
OMITIR = {'toString', 'equals', 'hashCode'}


def _limpio(texto):
    """Sin comentarios ni contenido de cadenas (pueden traer llaves o paréntesis)."""
    texto = re.sub(r'"""(?:.|\n)*?"""', '""', texto)
    texto = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', texto)
    texto = re.sub(r"'(?:\\.|[^'\\])'", "''", texto)
    texto = re.sub(r'/\*(?:.|\n)*?\*/', '', texto)
    return re.sub(r'//[^\n]*', '', texto)


def _partir(lista):
    """Separa por comas fuera de <> y ()."""
    partes, nivel, actual = [], 0, ''
    for ch in lista:
        if ch in '<(': nivel += 1
        if ch in '>)': nivel -= 1
        if ch == ',' and nivel == 0:
            partes.append(actual.strip()); actual = ''
        else:
            actual += ch
    return [p for p in partes + [actual.strip()] if p]


def _corto(tipo):
    """Sin el paquete: bo.uagrm.crm.presentacion.Adjunto -> Adjunto, java.util.List<X> -> List<X>."""
    return re.sub(r'\b(?:[a-z]\w*\.)+(?=[A-Z])', '', ' '.join(tipo.split()))


def _param(p):
    p = re.sub(r'@\w+\s+|\bfinal\s+', '', p).strip()
    tipo, nombre = p.rsplit(None, 1)
    return f'{nombre}: {_corto(tipo)}'


def _miembros(cuerpo):
    """Cabeceras de los miembros de primer nivel del cuerpo de un tipo: (texto, es_bloque)."""
    out, actual, llaves, parens, i = [], '', 0, 0, 0
    while i < len(cuerpo):
        ch = cuerpo[i]
        if ch == '(':
            parens += 1
        elif ch == ')':
            parens -= 1
        if ch == '{' and parens == 0:
            out.append((actual.strip(), True)); actual = ''
            nivel = 1; i += 1
            while nivel:
                nivel += {'{': 1, '}': -1}.get(cuerpo[i], 0); i += 1
            continue
        if ch == ';' and parens == 0:
            out.append((actual.strip(), False)); actual = ''
        else:
            actual += ch
        i += 1
    return [(t, b) for t, b in out if t]


def _tipo(nombre):
    """(texto del archivo limpio, coincidencia de la declaración) del tipo `nombre` ('Respuesta.Tabla' para anidados)."""
    simple = nombre.split('.')[-1]
    patron = re.compile(r'((?:\b(?:public|protected|private|static|final|abstract|sealed)\s+)*)(class|record|interface|enum)\s+'
                        + simple + r'\b')
    for f in SRC.rglob('*.java'):
        t = _limpio(f.read_text(encoding='utf-8'))
        m = patron.search(t)
        if m: return t, m
    raise KeyError(nombre)


def spec(nombre, estereotipo=None):
    t, m = _tipo(nombre)
    clase_ = nombre.split('.')[-1]
    clase_tipo = m.group(2)
    abre = t.index('{', m.end())
    cabecera = t[m.end():abre]
    nivel, j = 1, abre + 1
    while nivel:
        nivel += {'{': 1, '}': -1}.get(t[j], 0); j += 1
    cuerpo = t[abre + 1:j - 1]
    attrs, ops = [], []
    if clase_tipo == 'record':
        comps = cabecera[cabecera.index('(') + 1:cabecera.rindex(')')]
        attrs += ['-' + _param(c) for c in _partir(comps)]
    if clase_tipo == 'enum':
        constantes = cuerpo.split(';')[0]
        attrs += ['+' + c.split('(')[0].strip() for c in _partir(constantes) if c.strip()]
        cuerpo = cuerpo[cuerpo.index(';') + 1:] if ';' in cuerpo else ''
    for texto, bloque in _miembros(cuerpo):
        texto = re.sub(r'@\w+(\([^)]*\))?\s*', '', texto).strip()
        if re.search(r'\b(class|record|interface|enum)\s+\w+', texto) or texto in ('static', ''):
            continue
        mods = set(re.findall(r'\b(public|protected|private|static|final|abstract|default)\b', texto.split('(')[0].split('=')[0]))
        vis = next((VIS[v] for v in ('public', 'protected', 'private') if v in mods), '+' if clase_tipo == 'interface' else '~')
        est = '$' if 'static' in mods else ''
        mm = re.match(r'(?:(?:public|protected|private|static|final|abstract|synchronized|default)\s+)*(?:<[^()]*?>\s+)?'
                      r'([\w.<>\[\], ?]+?)\s+(\w+)\s*\((.*)\)\s*(?:throws\s+[\w., ]+)?$', texto, re.S)
        if mm and '=' not in texto.split('(')[0]:
            ret, n, ps = mm.groups()
            if n in OMITIR or n == clase_ or vis not in '+#': continue   # sin constructores
            ops.append(f'{vis}{est}{n}({", ".join(_param(p) for p in _partir(ps))}): {_corto(ret)}')
            continue
        decl = texto.split('=')[0].strip()
        decl = re.sub(r'\b(public|protected|private|final|transient|volatile)\s+', '', decl).strip()
        if '(' in decl or 'static' in mods or ' ' not in decl: continue   # constructores (y el compacto) y constantes static
        tipo_, n = decl.rsplit(None, 1)
        attrs.append(f'{vis}{n}: {_corto(tipo_)}')
    st = estereotipo if estereotipo is not None else {'record': 'record', 'interface': 'interface', 'enum': 'enumeration'}.get(clase_tipo, '')
    if 'abstract' in m.group(1) and not st: st = 'abstract'
    return f'{nombre}|{st}|{"; ".join(attrs)}|{"; ".join(ops)}'


def metodos(nombre):
    """Nombres de las operaciones públicas/protegidas (para validar los mensajes de los diagramas)."""
    ops = spec(nombre).split('|')[3]
    return {re.match(r'[+#~-]\$?(\w+)', o).group(1) for o in ops.split('; ') if o}


def _cuerpo_metodo(clase_, metodo):
    """Cuerpo (texto limpio) de la primera sobrecarga del método."""
    t, m = _tipo(clase_)
    i = re.search(r'\b' + metodo + r'\s*\([^;{]*\)\s*(?:throws[\w\s.,]*)?\{', t[m.end():])
    if not i: return ''
    ini = m.end() + i.end()
    nivel, j = 1, ini
    while nivel:
        nivel += {'{': 1, '}': -1}.get(t[j], 0); j += 1
    return t[ini:j - 1]


def delega(clase_, metodo):
    """(Clase, método) si el método solo delega en otro servicio: return new ProgramaService(db).asignar(...)."""
    d = re.search(r'new (\w+)\(db\)\.(\w+)\(', _cuerpo_metodo(clase_, metodo))
    return d.groups() if d else None


def bd(clase_, metodo, _visto=None):
    """Operación de DatabaseService con la que el método llega a la BD (sigue los métodos propios que invoca y la
    delegación en otro servicio)."""
    _visto = _visto or set()
    _visto.add(metodo)
    if (d := delega(clase_, metodo)): return bd(*d)
    c = _cuerpo_metodo(clase_, metodo)
    if 'enTransaccion(' in c: return 'enTransaccion'
    for op in ('filas', 'lista', 'uno', 'actualizar', 'insertar'):
        if re.search(r'(?<![\w.])' + op + r'\(', c): return op
    for otro in re.findall(r'(?<![\w.])(\w+)\(', c):
        if otro not in _visto and otro in _metodos_de(clase_):
            r = bd(clase_, otro, _visto)
            if r: return r
    return 'getConnection' if 'getConnection(' in c else None


def _metodos_de(clase_):
    t, m = _tipo(clase_)
    return set(re.findall(r'\b(?:public|private|protected|static)\s[\w<>\[\], .?]*?\b(\w+)\s*\(', t))


CAMPOS = {'usuarios': 'UsuarioService', 'personas': 'PersonaService', 'prospectos': 'ProspectoService', 'asignacion': 'AsignacionService',
          'embudo': 'EmbudoService', 'perdidas': 'PerdidaService', 'conversion': 'ConversionService', 'pagos': 'PagoService',
          'exportaciones': 'ExportacionService', 'seguimiento': 'SeguimientoService', 'reportes': 'ReporteService'}


def llamadas():
    """{COMANDO: [(campo, Servicio, método)]} tal como lo despacha RolCommandProcessor.ejecutar(), con los métodos propios
    (tablero(), ayuda()...) seguidos un nivel."""
    t, m = _tipo('RolCommandProcessor')
    sw = t[t.index('switch (c.getTipo())'):]
    sw = sw[:sw.index('\n    };')]
    casos = list(re.finditer(r'\n\s+case (\w+(?:, \w+)*) ->', sw))
    patron = r'\b(' + '|'.join(CAMPOS) + r')\.(\w+)\('
    out = {}
    for i, c in enumerate(casos):
        cuerpo = sw[c.end(): casos[i + 1].start() if i + 1 < len(casos) else len(sw)]
        usos = re.findall(patron, cuerpo)
        if not usos:
            for h in re.findall(r'(?<![\w.])(\w+)\(', cuerpo):
                if h in _metodos_de('RolCommandProcessor'):
                    usos += re.findall(patron, _cuerpo_metodo('RolCommandProcessor', h))
        for k in c.group(1).split(', '):
            out[k] = list(dict.fromkeys((a, CAMPOS[a], b) for a, b in usos))
    return out


if __name__ == '__main__':
    ll = llamadas()
    for k, v in ll.items():
        print(k, [(s, mt, bd(s, mt)) for _, s, mt in v])
    assert ll['VERTABLERO'] == [('embudo', 'EmbudoService', 'tablero')], ll['VERTABLERO']
    assert bd('PagoService', 'generar') == 'enTransaccion'
    for n in ('App', 'Config', 'Diagnostico', 'Pop3Client', 'SmtpSender', 'Correo', 'Command', 'CommandParser',
              'CommandProcessor', 'RolCommandProcessor', 'AdministradorCommandProcessor', 'ResponseFormatter', 'Respuesta',
              'Respuesta.Tabla', 'DatabaseService', 'UsuarioService', 'PagoService', 'PagoService.Orden', 'Oferta', 'Rol'):
        print(spec(n), '\n')
    assert 'procesarEmail' in metodos('CommandProcessor') and 'generar' in metodos('PagoService')
    assert '-codigo: String' in spec('Oferta') and '-id: int' not in spec('Oferta'), spec('Oferta')
