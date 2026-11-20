"""Compila y verifica P1 en PostgreSQL exclusivo de 127.0.0.1, sin servicios reales.

Requiere PostgreSQL (initdb/pg_ctl/psql), Maven y JDK 21+. No lee secretos.
Con --defensa ejecuta también el JAR y corta su proceso y PostgreSQL de prueba.
Los informes se guardan en doc/pruebas/local-final.json; los logs en target/qa-local.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


def main():
    # PowerShell/Windows pueden heredar CP1252; los informes y la salida son siempre UTF-8.
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--pg-bin', type=Path, default=Path('C:/Program Files/PostgreSQL/18/bin'))
    ap.add_argument('--java-home', type=Path)
    ap.add_argument('--defensa', action='store_true')
    ap.add_argument('--cortes', type=int, default=5)
    ap.add_argument('--tests', help='Clases concretas para una verificación adicional; no representa suite completa')
    ap.add_argument('--report', type=Path, help='Informe separado para una verificación adicional')
    args = ap.parse_args()
    root = Path(__file__).resolve().parents[1]
    logs = root / 'target/qa-local'
    logs.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='p1-final-qa-')).resolve()
    data = work / 'cluster'
    marker = work / 'EXCLUSIVO_QA_P1'
    marker.write_text(str(root), encoding='utf-8')
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        port = s.getsockname()[1]
    env = dict(os.environ, PGCLIENTENCODING='UTF8', PYTHONIOENCODING='utf-8')
    # Ninguna variable heredada puede dirigir las integraciones a una base real.
    for key in list(env):
        if key.startswith('P1_'):
            env.pop(key)
    if args.java_home:
        env['JAVA_HOME'] = str(args.java_home.resolve())
        env['PATH'] = str(args.java_home.resolve() / 'bin') + os.pathsep + env.get('PATH', '')
    env['MAVEN_OPTS'] = env.get('MAVEN_OPTS', '') + ' -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    checks = []
    report = {'fecha': datetime.now(timezone.utc).isoformat(),
              'entorno': 'PostgreSQL exclusivo en 127.0.0.1; POP3 y SMTP de prueba; sin servicios institucionales',
              'alcance': args.tests or 'suite completa',
              'comprobaciones': checks, 'aprobado': False}

    def executable(name):
        p = args.pg_bin / (name + ('.exe' if os.name == 'nt' else ''))
        if not p.is_file():
            raise FileNotFoundError(p)
        return str(p)

    def run(command, timeout=120, check=True, name=None):
        result = subprocess.run(list(map(str, command)), cwd=root, env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                encoding='utf-8', errors='replace', timeout=timeout,
                                creationflags=flags)
        if name:
            logs.mkdir(parents=True, exist_ok=True)
            (logs / name).write_text(result.stdout, encoding='utf-8')
        if check and result.returncode:
            raise RuntimeError('Fallo: ' + str(command[0]) + '\n' + result.stdout[-9000:])
        return result

    def ctl(*params, check=True):
        # Evita que el postmaster herede un PIPE que mantenga bloqueado communicate().
        logs.mkdir(parents=True, exist_ok=True)
        with (logs / 'postgres-control.txt').open('a', encoding='utf-8') as log:
            return subprocess.run([executable('pg_ctl'), '-D', str(data), *map(str, params)],
                                  env=env, stdout=log, stderr=subprocess.STDOUT,
                                  timeout=60, check=check, creationflags=flags)

    def sql(query=None, file=None, database='crm_qa', check=True):
        cmd = [executable('psql'), '-X', '-q', '-tA', '-h', '127.0.0.1', '-p', port,
               '-U', 'crm_user', '-d', database, '-v', 'ON_ERROR_STOP=1']
        cmd += ['--single-transaction', '-f', file] if file else ['-c', query]
        return run(cmd, check=check)

    def verify(name, value):
        checks.append({'comprobacion': name, 'aprobada': bool(value)})
        print(('OK ' if value else 'FALLO ') + name, flush=True)
        if not value:
            raise AssertionError(name)

    def snapshot():
        tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").stdout.split()
        return {table: sql("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text "
                           "FROM public." + table + " t").stdout.strip() for table in tables}

    try:
        run([executable('initdb'), '-D', data, '-U', 'crm_user', '-A', 'trust',
             '--encoding=UTF8', '--no-locale'], name='initdb.txt')
        ctl('-l', work / 'postgres.log', '-o',
            '-h 127.0.0.1 -p %s -c max_connections=40 -c shared_buffers=32MB' % port, '-w', 'start')
        sql('CREATE DATABASE crm_qa', database='postgres')
        sql(file=root / 'sql/esquema.sql')
        sql(file=root / 'sql/referencias.sql')
        initial = snapshot()
        verify('Instalación nueva rechaza un esquema ocupado sin modificar registros',
               sql(file=root / 'sql/esquema.sql', check=False).returncode != 0 and snapshot() == initial)
        sql(file=root / 'sql/referencias.sql')
        verify('Referencias repetibles conservan registros', snapshot() == initial)
        # Reversión sólo sobre este clúster vacío de solicitudes, nunca sobre producción.
        sql(file=root / 'sql/migraciones/2026-11-19_bandeja_correo_revertir.sql')
        sql(file=root / 'sql/migraciones/2026-11-19_bandeja_correo.sql')
        verify('Migración de bandeja conserva los registros existentes', snapshot() == initial)
        sql(file=root / 'sql/migraciones/2026-11-19_normalizar_catalogo_revertir.sql')
        sql(file=root / 'sql/migraciones/2026-11-19_normalizar_catalogo.sql')
        verify('Migración del catálogo conserva todos los datos de referencia', snapshot() == initial)
        sql(file=root / 'sql/migraciones/2026-11-19_normalizar_catalogo_revertir.sql')
        sql("UPDATE catalogo_cache SET programa_nombre='Conflicto QA' WHERE codigo='desarrollo-web-m1'")
        conflicting = snapshot()
        verify('Conflicto del catálogo rechaza la migración y revierte su transacción',
               sql(file=root / 'sql/migraciones/2026-11-19_normalizar_catalogo.sql', check=False).returncode != 0
               and snapshot() == conflicting)
        sql("UPDATE catalogo_cache SET programa_nombre='Desarrollo de Aplicaciones Web' WHERE codigo='desarrollo-web-m1'")
        sql(file=root / 'sql/migraciones/2026-11-19_normalizar_catalogo.sql')
        for migration in sorted((root / 'sql/migraciones').glob('*_persona.sql')):
            sql(file=migration)
            once = snapshot()
            sql(file=migration)
            verify('Migración persona repetible conserva datos y versión', snapshot() == once)
        # Las suites de nombre histórico «servidor» se redirigen expresamente a QA local.
        sql('CREATE SCHEMA crm_qa_correo')
        for filename in ['esquema.sql', 'referencias.sql']:
            temp_sql = work / filename
            temp_sql.write_text((root / 'sql' / filename).read_text(encoding='utf-8').replace('public', 'crm_qa_correo'), encoding='utf-8')
            sql(file=temp_sql)
        url = 'jdbc:postgresql://127.0.0.1:%s/crm_qa' % port
        env.update(P1_TEST_DB_URL=url, P1_ROBUSTEZ_DB_URL=url,
                   P1_TEST_DB_USER='crm_user', P1_TEST_DB_PASSWORD='crm_password',
                   P1_REMOTE_DB_URL=url + '?currentSchema=crm_qa_correo',
                   P1_REMOTE_DB_USER='crm_user', P1_REMOTE_DB_PASSWORD='crm_password')
        mvn = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn', path=env.get('PATH'))
        if not mvn:
            raise FileNotFoundError('Maven no está en PATH')
        print('Ejecutando Maven y todas las integraciones locales...', flush=True)
        # El postmaster de Windows hereda el archivo de control abierto: clean no debe borrar
        # ese archivo mientras el clúster de QA está ejecutándose. Maven recompila los cambios.
        command = [mvn, '-B', 'package'] + (['-Dtest=' + args.tests] if args.tests else [])
        result = run(command, timeout=1200, check=False, name='maven-adicional.txt' if args.tests else 'maven.txt')
        suites = [ET.parse(p).getroot() for p in (root / 'target/surefire-reports').glob('TEST-*.xml')]
        if args.tests:
            seleccion = set(args.tests.split(','))
            suites = [s for s in suites if s.get('name') in seleccion or s.get('name').split('.')[-1] in seleccion]
        counts = {k: sum(int(s.get(k, '0')) for s in suites) for k in ['tests', 'failures', 'errors', 'skipped']}
        report['junit'] = counts
        report['suites'] = [{'clase': s.get('name'), **{k: int(s.get(k, '0')) for k in counts}} for s in suites]
        report['compilacion_exit_code'] = result.returncode
        if result.returncode:
            print(result.stdout[-14000:], flush=True)
        verify(('Verificación adicional' if args.tests else 'Suite completa') + ' y compilación sin fallas, errores ni omisiones',
               result.returncode == 0 and counts['tests'] > (0 if args.tests else 100)
               and all(counts[k] == 0 for k in ['failures', 'errors', 'skipped']))
        if args.defensa:
            print('Ejecutando el JAR con interrupciones reales de proceso y PostgreSQL local...', flush=True)
            out = root / 'doc/pruebas/defensa-local.json'
            run([sys.executable, '-B', root / 'scripts/prueba_caidas.py', '--db', url,
                 '--psql', executable('psql'), '--pgdata', data, '--pg-ctl', executable('pg_ctl'),
                 '--cortes', args.cortes, '--semilla', '31', '--salida', out],
                timeout=600, name='defensa.txt')
            report['defensa'] = json.loads(out.read_text(encoding='utf-8'))
            verify('JAR recupera solicitudes tras caída real de PostgreSQL y reinicio de proceso', report['defensa']['aprobado'])
        report['fuentes_sha256'] = {str(p.relative_to(root)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest()
                                    for folder in ['src/main/java', 'sql'] for p in sorted((root / folder).rglob('*')) if p.is_file()}
        report['aprobado'] = True
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        if (data / 'postmaster.pid').exists():
            ctl('-w', 'stop', '-m', 'fast', check=False)
        output = args.report or root / 'doc/pruebas/local-final.json'
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        # Se conserva detenido fuera del repositorio para investigar una falla, sin borrar directorios ajenos.
        print('Clúster QA detenido: ' + str(work), flush=True)
        print('Informe: ' + str(output), flush=True)


if __name__ == '__main__':
    main()
