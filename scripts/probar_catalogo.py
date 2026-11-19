"""Prueba migración/reversión y suite Java en PostgreSQL efímero local; nunca producción.

Requiere --sql-anterior con esquema.sql y referencias.sql anteriores a normalizar.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--sql-anterior', type=Path, required=True)
    args = ap.parse_args()
    p1 = Path(__file__).resolve().parents[1]
    pg = Path('C:/Program Files/PostgreSQL/18/bin')
    work = Path(tempfile.mkdtemp(prefix='p1-catalogo-qa-')).resolve()
    data = work / 'cluster'
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        port = s.getsockname()[1]
    env = dict(os.environ, PGCLIENTENCODING='UTF8')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    checks = []

    def run(exe, *params, check=True):
        if exe == 'pg_ctl':
            with (work / 'control.log').open('a') as log:
                return subprocess.run([str(pg / exe), *map(str, params)], env=env, stdout=log,
                                      stderr=subprocess.STDOUT, check=check, timeout=60, creationflags=flags)
        return subprocess.run([str(pg / exe), *map(str, params)], env=env, capture_output=True,
                              text=True, encoding='utf-8', errors='replace', check=check, timeout=90,
                              creationflags=flags)

    def sql(stmt=None, file=None, check=True):
        tail = ['--single-transaction', '-f', str(file)] if file else ['-tA', '-c', stmt]
        return run('psql', '-X', '-h', '127.0.0.1', '-p', port, '-U', 'crm_user', '-d', 'postgres',
                   '-v', 'ON_ERROR_STOP=1', *tail, check=check)

    def verify(name, valid):
        checks.append({'comprobacion': name, 'aprobada': bool(valid)})
        if not valid:
            raise AssertionError(name)
        print('OK ' + name, flush=True)

    def snapshot(normalized=False):
        tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").stdout.split()
        result = {}
        for table in tables:
            if table == 'programas':
                continue
            source = 'public.' + table
            if table == 'catalogo_cache' and normalized:
                source = '(SELECT c.*,p.nombre AS programa_nombre FROM public.catalogo_cache c JOIN public.programas p ON p.codigo=c.programa_codigo)'
            rows = sql(f'SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),\'[]\'::jsonb)::text FROM {source} t').stdout.strip()
            result[table] = hashlib.sha256(rows.encode()).hexdigest()
        return result

    migration = p1 / 'sql/migraciones/2026-11-19_normalizar_catalogo.sql'
    reverse = p1 / 'sql/migraciones/2026-11-19_normalizar_catalogo_revertir.sql'
    try:
        run('initdb', '-D', data, '-U', 'crm_user', '-A', 'trust', '--encoding=UTF8', '--no-locale')
        run('pg_ctl', '-D', data, '-l', work / 'postgres.log', '-o',
            f'-h 127.0.0.1 -p {port} -c max_connections=30 -c shared_buffers=32MB', '-w', 'start')
        sql(file=args.sql_anterior / 'esquema.sql')
        sql(file=args.sql_anterior / 'referencias.sql')
        before = snapshot()
        sql(file=migration)
        verify('Migración conserva cada fila y cada valor del modelo anterior', before == snapshot(True))
        verify('Cinco programas y veinticinco módulos', sql('SELECT (SELECT count(*) FROM programas)||\'|\'||(SELECT count(*) FROM catalogo_cache)').stdout.strip() == '5|25')
        verify('Reaplicar se rechaza sin modificar datos', sql(file=migration, check=False).returncode != 0 and before == snapshot(True))
        sql(file=reverse)
        verify('Reversión restituye íntegros los datos originales', before == snapshot())
        sql("UPDATE catalogo_cache SET programa_nombre='Conflicto QA' WHERE codigo='desarrollo-web-m1'")
        conflict = snapshot()
        verify('Nombres contradictorios rechazan migración y hacen rollback', sql(file=migration, check=False).returncode != 0 and conflict == snapshot())
        sql("UPDATE catalogo_cache SET programa_nombre='Desarrollo de Aplicaciones Web' WHERE codigo='desarrollo-web-m1'")
        sql(file=migration)
        # Una segunda instalación desde cero debe producir el mismo catálogo.
        migrated = snapshot(True)
        sql('DROP SCHEMA public CASCADE; CREATE SCHEMA public')
        sql(file=p1 / 'sql/esquema.sql')
        sql(file=p1 / 'sql/referencias.sql')
        verify('Instalación nueva y migración preservan los mismos datos de referencia', migrated == snapshot(True))
        # Las suites llamadas remotas se ejecutan exclusivamente contra este clúster local.
        for source in ('esquema.sql', 'referencias.sql'):
            target = work / source
            content = (p1 / 'sql' / source).read_text(encoding='utf-8').replace('public', 'crm_qa_correo')
            target.write_text(content, encoding='utf-8')
            if source == 'esquema.sql':
                sql('CREATE SCHEMA crm_qa_correo')
            sql(file=target)
        url = f'jdbc:postgresql://127.0.0.1:{port}/postgres'
        env.update(P1_TEST_DB_URL=url, P1_ROBUSTEZ_DB_URL=url, P1_TEST_DB_USER='crm_user',
                   P1_TEST_DB_PASSWORD='crm_password', P1_REMOTE_DB_URL=url + '?currentSchema=crm_qa_correo',
                   P1_REMOTE_DB_USER='crm_user', P1_REMOTE_DB_PASSWORD='crm_password')
        print('Ejecutando todas las pruebas Java en PostgreSQL local desechable...', flush=True)
        with (work / 'maven.log').open('w', encoding='utf-8') as log:
            result = subprocess.run(['mvn.cmd', '-q', '-o', 'package'], cwd=p1, env=env,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=1200, creationflags=flags)
        if result.returncode:
            print((work / 'maven.log').read_text(encoding='utf-8', errors='replace')[-14000:], flush=True)
        roots = [ET.parse(f).getroot() for f in (p1 / 'target/surefire-reports').glob('TEST-*.xml')]
        counts = {k: sum(int(r.get(k, 0)) for r in roots) for k in ('tests', 'failures', 'errors', 'skipped')}
        verify('Suite completa sin fallas, errores u omisiones', result.returncode == 0 and counts['tests'] > 100
               and all(counts[k] == 0 for k in ('failures', 'errors', 'skipped')))
        report = {'fecha': '2026-11-19', 'entorno': 'PostgreSQL efímero en 127.0.0.1; no producción',
                  'migracion_sha256': hashlib.sha256(migration.read_bytes()).hexdigest(),
                  'comprobaciones': checks, 'junit': counts, 'aprobado': True}
        (p1 / 'doc/pruebas/catalogo-normalizado.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    finally:
        if (data / 'postmaster.pid').exists():
            run('pg_ctl', '-D', data, '-w', 'stop', '-m', 'fast')
        assert work.name.startswith('p1-catalogo-qa-') and data.resolve().parent == work
        if data.exists():
            shutil.rmtree(data)


if __name__ == '__main__':
    main()
