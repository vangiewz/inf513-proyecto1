"""Coteja los modelos EA con PostgreSQL real DESECHABLE, exclusivamente en 127.0.0.1.

No usa configuración ni credenciales del CRM. Requiere PostgreSQL 18 y pywin32.
Uso: python scripts/auditar_modelos.py [--pg-bin RUTA_BIN_POSTGRESQL]
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--pg-bin', default='C:/Program Files/PostgreSQL/18/bin')
    ap.add_argument('--ampliada', action='store_true', help='Agrega controles del docente y guarda auditoria-ampliada.json')
    args = ap.parse_args()
    p1 = Path(__file__).resolve().parents[1]
    work = Path(tempfile.mkdtemp(prefix='p1-modelos-qa-')).resolve()
    data = work / 'cluster'
    pg = Path(args.pg_bin)
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        port = s.getsockname()[1]
    env = dict(os.environ, PGCLIENTENCODING='UTF8')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0

    def run(exe, *params, check=True):
        if exe == 'pg_ctl':
            # En Windows el servidor puede heredar los PIPE y mantener communicate() esperando EOF.
            # Un archivo evita bloquear el auditor aunque PostgreSQL siga ejecutándose en segundo plano.
            with (work / 'pg_ctl.log').open('a', encoding='utf-8') as log:
                return subprocess.run([str(pg / exe), *map(str, params)], env=env, stdout=log,
                                      stderr=subprocess.STDOUT, check=check, creationflags=flags, timeout=60)
        return subprocess.run([str(pg / exe), *map(str, params)], env=env, capture_output=True,
                              text=True, encoding='utf-8', errors='replace', check=check, creationflags=flags, timeout=90)

    def query(sql):
        result = run('psql', '-X', '-h', '127.0.0.1', '-p', port, '-U', 'auditor_p1', '-d', 'postgres',
                     '-tA', '-v', 'ON_ERROR_STOP=1', '-c', sql)
        value = result.stdout.strip()
        return value == 't' if value in {'t', 'f'} else json.loads(value)

    checks = []
    def verify(name, valid, detail=None):
        checks.append({'comprobacion': name, 'aprobada': bool(valid), 'detalle': detail})

    repo = None
    try:
        run('initdb', '-D', data, '-U', 'auditor_p1', '-A', 'trust', '--encoding=UTF8', '--no-locale')
        run('pg_ctl', '-D', data, '-l', work / 'postgres.log', '-o',
            f'-h 127.0.0.1 -p {port} -c max_connections=10 -c shared_buffers=16MB', '-w', 'start')
        run('psql', '-X', '-h', '127.0.0.1', '-p', port, '-U', 'auditor_p1', '-d', 'postgres',
            '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-f', p1 / 'sql/esquema.sql')
        cols = query("""SELECT json_agg(x) FROM (SELECT c.relname AS tabla,a.attname AS columna,
          format_type(a.atttypid,a.atttypmod) AS tipo,a.attnotnull AS obligatorio
          FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
          JOIN pg_namespace n ON n.oid=c.relnamespace
          WHERE n.nspname='public' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
          ORDER BY c.relname,a.attnum) x""")
        keys = query("""SELECT json_agg(x) FROM (SELECT c.conrelid::regclass::text AS tabla,c.contype AS tipo,
          ARRAY(SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY k(num,ord)
            JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num ORDER BY k.ord) AS columnas
          FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace
          WHERE n.nspname='public' AND c.contype IN ('p','u')) x""")
        fks = query("""SELECT json_agg(x) FROM (SELECT c.conrelid::regclass::text AS hija,
          c.confrelid::regclass::text AS padre,
          (SELECT attname FROM pg_attribute WHERE attrelid=c.conrelid AND attnum=c.conkey[1]) AS columna
          FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace
          WHERE n.nspname='public' AND c.contype='f') x""")
        tables = {c['tabla'] for c in cols}
        verify('CRM relacionado; persona independiente por contrato docente sin FK inventada',
               tables - {'persona'} <= {t for f in fks for t in (f['hija'], f['padre'])}
               and not any('persona' in (f['hija'], f['padre']) for f in fks))
        unique = {(k['tabla'], k['columnas'][0]) for k in keys if len(k['columnas']) == 1}
        foreign = {(f['hija'], f['columna']) for f in fks}
        required = {(c['tabla'], c['columna']): c['obligatorio'] for c in cols}
        pkeys = {k['tabla']: k['columnas'] for k in keys if k['tipo'] == 'p'}
        def short(t):
            return t.replace('character varying', 'varchar').replace(' without time zone', '')
        expected = {(c['tabla'], c['columna']): short(c['tipo']) for c in cols
                    if (c['tabla'], c['columna']) not in foreign or c['columna'] in pkeys[c['tabla']]}
        expected_edges = Counter((f['hija'], f['padre'],
                                 '0..1' if (f['hija'], f['columna']) in unique else '0..*',
                                 '1' if required[f['hija'], f['columna']] else '0..1') for f in fks)

        import win32com.client
        repo = win32com.client.Dispatch('EA.Repository')
        if not repo.OpenFile(str(p1 / 'doc/ea/crm-p1.eapx')):
            raise RuntimeError('EA no pudo abrir el modelo')
        packages = {}
        def visit(pkg):
            packages[pkg.Name] = pkg
            for child in pkg.Packages: visit(child)
        visit(repo.Models.GetAt(repo.Models.Count - 1))
        logical = packages['Modelo de datos']
        conceptual = packages['Modelo conceptual']
        verify('Un único diagrama lógico global', logical.Diagrams.Count == 1)
        verify('Un único diagrama conceptual global', conceptual.Diagrams.Count == 1)
        for label, package in [('lógico', logical), ('conceptual', conceptual)]:
            elements = {e.Name: e for e in package.Elements if e.Type == 'Class'}
            verify('Tablas completas en el ' + label, set(elements) == tables,
                   {'esperadas': len(tables), 'en_modelo': len(elements)})
            visible = {repo.GetElementByID(o.ElementID).Name for o in package.Diagrams.GetAt(0).DiagramObjects}
            verify('Todas las entidades en una sola vista ' + label, visible == tables)
        elements = {e.Name: e for e in logical.Elements if e.Type == 'Class'}
        actual = {(name, a.Name): a.Type for name, e in elements.items() for a in e.Attributes}
        verify('Atributos y tipos lógicos iguales a PostgreSQL', actual == expected,
               {'esperados': len(expected), 'representados': len(actual),
                'faltan': sorted(set(expected) - set(actual)), 'sobran': sorted(set(actual) - set(expected)),
                'tipos_distintos': [list(k) for k in actual.keys() & expected.keys() if actual[k] != expected[k]]})
        connectors = {c.ConnectorID: c for e in elements.values() for c in e.Connectors if c.Type == 'Association'}
        actual_edges = Counter((repo.GetElementByID(c.ClientID).Name, repo.GetElementByID(c.SupplierID).Name,
                                c.ClientEnd.Cardinality, c.SupplierEnd.Cardinality) for c in connectors.values())
        verify('Todas las FK con sus multiplicidades reales', actual_edges == expected_edges,
               {'esperadas': len(fks), 'representadas': len(connectors),
                'faltan': list((expected_edges - actual_edges).elements()),
                'sobran': list((actual_edges - expected_edges).elements())})
        errors = []
        for e in conceptual.Elements:
            if e.Type != 'Class': continue
            for a in e.Attributes:
                if a.Type or (e.Name, a.Name) not in required: errors.append([e.Name, a.Name, a.Type])
            if e.Methods.Count: errors.append([e.Name, 'métodos'])
        verify('Conceptual sin tipos, métodos ni atributos inventados', not errors, errors)
        junction = next(e for e in conceptual.Elements if e.Name == 'orden_pago_descuentos')
        association = repo.GetConnectorByID(junction.AssociationClassConnectorID) if junction.AssociationClassConnectorID else None
        verify('Intermedia N a N como clase de asociación real', association is not None and
               {repo.GetElementByID(association.ClientID).Name, repo.GetElementByID(association.SupplierID).Name}
               == {'ordenes_pago', 'descuentos'})
        for cu in range(1, 9):
            seq = [d for d in packages['Secuencia'].Diagrams if d.Name.startswith(f'CU{cu}:')]
            cls = [d for d in packages['Clases'].Diagrams if d.Name.startswith(f'CU{cu}_')]
            verify(f'CU{cu}: una secuencia y un procedimental', len(seq) == len(cls) == 1)
            folded = [o.ElementID for d in cls for o in d.DiagramObjects if 'AttPub=0' in o.Style]
            verify(f'CU{cu}: clases completas, sin miembros plegados', not folded, folded)
        verify('Un modelo global del canal de correo',
               sum('Canal de correo: modelo global' == d.Name for d in packages['Clases'].Diagrams) == 1)
        if args.ampliada:
            verify('Cada tabla tiene exactamente una PK', set(pkeys) == tables and
                   len([k for k in keys if k['tipo'] == 'p']) == len(tables))
            verify('Sin columnas genéricas id ni id_...',
                   not [c for c in cols if c['columna'] == 'id' or c['columna'].startswith('id_')])
            verify('Única PK compuesta: orden y descuento',
                   {t: cs for t, cs in pkeys.items() if len(cs) > 1} ==
                   {'orden_pago_descuentos': ['nro_orden', 'descuento_codigo']})
            verify('Dependientes 1 a 1 toman la clave de su dueño', all(
                pkeys[t] == [c] and any(f['hija'] == t and f['columna'] == c for f in fks)
                for t, c in [('pagos', 'nro_orden'), ('comisiones', 'nro_solicitud'),
                             ('inscripciones_comerciales', 'nro_solicitud')]))
            verify('No hay FK sin validar en PostgreSQL', query("SELECT count(*)=0 FROM pg_constraint c"
                   " JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='public'"
                   " AND c.contype='f' AND NOT c.convalidated"))
            verify('Vigencia de credenciales con inicio y fin obligatorios',
                   all(required.get(('usuarios', c)) for c in ('fecha_inicio', 'fecha_fin')))
            verify('CHECK de fin de vigencia mayor o igual al inicio', query("SELECT coalesce(bool_or("
                   "pg_get_constraintdef(oid) LIKE '%fecha_fin >= fecha_inicio%'),false) FROM pg_constraint"
                   " WHERE conrelid='public.usuarios'::regclass AND contype='c'"))
            verify('Catálogo sin el nombre transitivo repetido', ('catalogo_cache', 'programa_nombre') not in required
                   and pkeys.get('programas') == ['codigo']
                   and {f['hija'] for f in fks if f['padre'] == 'programas'} ==
                   {'catalogo_cache', 'programas_responsables'})
            java = (p1 / 'src/main/java/bo/uagrm/crm/persistencia/DatabaseService.java').read_text(encoding='utf-8')
            section = java.split('TABLAS =', 1)[1].split('VALORES_TABLAS', 1)[0]
            verify('Tablas exigidas por Java coinciden con PostgreSQL', set(re.findall(r'"(\w+)"', section)) == tables)
            verify('Conceptual sin marcas de PK/FK en atributos',
                   not [(e.Name, a.Name) for e in conceptual.Elements for a in e.Attributes
                        if a.IsID or a.Stereotype.lower() in {'pk', 'fk', 'primarykey', 'foreignkey'}])
            initial = [d for p in packages.values() for d in p.Diagrams if d.Name == 'Modelo de dominio inicial']
            verify('Dominio inicial global único y sin atributos/métodos', len(initial) == 1 and all(
                repo.GetElementByID(o.ElementID).Attributes.Count == 0 and
                repo.GetElementByID(o.ElementID).Methods.Count == 0 for o in initial[0].DiagramObjects))
            collaboration = [d for p in packages.values() for d in p.Diagrams if d.Type == 'Collaboration']
            for cu in range(1, 9):
                verify(f'CU{cu}: una colaboración completa',
                       sum(d.Name.startswith(f'Colaboración CU{cu}:') for d in collaboration) == 1)
            common = [d for d in packages['Secuencia'].Diagrams if not d.Name.startswith('CU')]
            verify('Cuatro secuencias comunes del canal, sin detalles extra de CU6', len(common) == 4 and
                   not any('Detalle de secuencia CU6' in d.Name for p in packages.values() for d in p.Diagrams))
            canal = next(d for d in packages['Clases'].Diagrams if d.Name == 'Canal de correo: modelo global')
            clases_canal = {repo.GetElementByID(o.ElementID).Name for o in canal.DiagramObjects}
            verify('Canal representa configuración, frecuencia, exclusión y autenticidad reales',
                   {'Config', 'ControlSondeo', 'BucleSondeo', 'InstanciaCorreo', 'AutenticidadCorreo'} <= clases_canal)

            def mensajes_diagrama(nombre):
                diagrama = next(d for d in common if d.Name == nombre)
                mensajes = {c.ConnectorID: c for o in diagrama.DiagramObjects
                            for c in repo.GetElementByID(o.ElementID).Connectors
                            if c.DiagramID == diagrama.DiagramID and c.Type == 'Sequence'}
                return [c.Name for c in sorted(mensajes.values(), key=lambda c: int(c.SequenceNo))]

            procesamiento = mensajes_diagrama('Procesar un correo guardado')
            posicion = lambda textos, termino: next((i for i, t in enumerate(textos) if termino in t), -1)
            firma_hmac = posicion(procesamiento, '2.6 verificar')
            repetido = posicion(procesamiento, '2.12 repetido')
            verify('Secuencia del canal autentica HMAC antes de deduplicar escrituras',
                   firma_hmac >= 0 and repetido > firma_hmac)
            rebotes = mensajes_diagrama('Leer rebotes')
            callback = posicion(rebotes, 'registrarRebote')
            commit = posicion(rebotes, 'COMMIT: resultado y bitácora')
            seen = posicion(rebotes, 'setFlag')
            verify('Secuencia DSN confirma actualización y bitácora antes de SEEN',
                   callback >= 0 and commit > callback and seen > commit)
            components = [d for p in packages.values() for d in p.Diagrams if d.Name == 'Diagrama de componentes']
            verify('Implementación: un único diagrama global de componentes', len(components) == 1)
            if len(components) == 1:
                component_objects = [repo.GetElementByID(o.ElementID) for o in components[0].DiagramObjects]
                verify('Componentes sin interfaces expuestas que dupliquen los ensamblajes',
                       not any(e.Type in {'ProvidedInterface', 'RequiredInterface'} for e in component_objects))
                verify('Cuatro capas visibles en implementación',
                       {e.Name for e in component_objects if e.Name.startswith('Capa de ')} ==
                       {'Capa de Presentación', 'Capa de Aplicación', 'Capa de Dominio', 'Capa de Persistencia'})
            run('psql', '-X', '-h', '127.0.0.1', '-p', port, '-U', 'auditor_p1', '-d', 'postgres',
                '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-f', p1 / 'sql/referencias.sql')
            run('psql', '-X', '-h', '127.0.0.1', '-p', port, '-U', 'auditor_p1', '-d', 'postgres',
                '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-f', p1 / 'sql/referencias.sql')
            verify('Referencias repetibles: 5 programas y 25 módulos sin duplicación', query(
                'SELECT (SELECT count(*) FROM public.programas)=5 AND (SELECT count(*) FROM public.catalogo_cache)=25'))
            verify('Instalación de referencia no crea usuarios ni prospectos reales', query(
                'SELECT (SELECT count(*) FROM public.usuarios)=0 AND (SELECT count(*) FROM public.prospectos)=0'))
            repeat = run('psql', '-X', '-h', '127.0.0.1', '-p', port, '-U', 'auditor_p1', '-d', 'postgres',
                         '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-f', p1 / 'sql/esquema.sql', check=False)
            verify('Instalador rechaza esquema ocupado', repeat.returncode != 0 and 'public contiene objetos' in repeat.stderr)
        summary = {'fecha': '2026-11-19', 'entorno': 'PostgreSQL desechable en 127.0.0.1; no producción',
                   'sql_sha256': hashlib.sha256((p1 / 'sql/esquema.sql').read_bytes()).hexdigest(),
                   'tablas': len(tables), 'columnas': len(cols), 'claves_foraneas': len(fks),
                   'atributos_logicos': [{'tabla': t, 'columna': c, 'tipo': tipo}
                                        for (t, c), tipo in sorted(expected.items())],
                   'comprobaciones': checks, 'aprobado': all(c['aprobada'] for c in checks)}
        path = p1 / 'doc/pruebas' / ('auditoria-ampliada.json' if args.ampliada else 'modelos-globales.json')
        path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0 if summary['aprobado'] else 1
    finally:
        if repo is not None:
            repo.CloseFile()
            repo.Exit()
        if (data / 'postmaster.pid').exists():
            run('pg_ctl', '-D', data, '-w', 'stop', '-m', 'fast')
        # Verificación explícita del destino antes de eliminar recursivamente el clúster de QA.
        assert work.name.startswith('p1-modelos-qa-') and data.resolve().parent == work
        if data.exists(): shutil.rmtree(data)


if __name__ == '__main__':
    raise SystemExit(main())
