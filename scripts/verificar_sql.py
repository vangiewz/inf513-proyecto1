"""Verifica la instalacion SQL en PostgreSQL 18 desechable, sin red ni datos reales.
Uso: python scripts/verificar_sql.py (requiere Docker).
"""
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
NAME = 'p1-sql-qa'

def docker(*args, data=None, check=True):
    r = subprocess.run(['docker', *args], input=data, capture_output=True)
    if check and r.returncode:
        raise RuntimeError(r.stderr.decode(errors='replace') + r.stdout.decode(errors='replace'))
    return r

def psql(data, check=True):
    return docker('exec', '-i', NAME, 'psql', '-X', '-U', 'postgres', '-v',
                  'ON_ERROR_STOP=1', '--single-transaction', '-At', data=data, check=check)

if docker('ps', '-a', '--filter', 'name=^/'+NAME+'$', '--format', '{{.Names}}').stdout.strip():
    raise RuntimeError('Ya existe '+NAME+'; no se modifica un contenedor preexistente.')

docker('run', '-d', '--name', NAME, '--label', 'p1.qa=true', '--network', 'none',
       '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:18-alpine')
try:
    for _ in range(30):
        if docker('exec', NAME, 'pg_isready', '-U', 'postgres', check=False).returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError('PostgreSQL QA no inicio.')
    schema = (ROOT/'sql/esquema.sql').read_bytes()
    refs = (ROOT/'sql/referencias.sql').read_bytes()
    psql(schema)
    psql(refs)
    psql(refs)  # Las referencias no se duplican al repetir la carga.
    result = psql(b"SELECT (SELECT count(*) FROM public.usuarios),"
                  b"(SELECT count(*) FROM public.prospectos),"
                  b"(SELECT count(*) FROM public.catalogo_cache),"
                  b"(SELECT count(*) FROM public.roles),"
                  b"(SELECT count(*) FROM public.motivos_perdida),"
                  b"(SELECT count(*) FROM public.descuentos),"
                  b"(SELECT valor FROM public.configuraciones WHERE clave='comision_porcentaje');")
    assert result.stdout.decode().strip() == '0|0|25|3|10|3|2', result.stdout.decode()
    repeated = psql(schema, check=False)
    assert repeated.returncode != 0 and b'public contiene objetos' in repeated.stderr
    print('SQL OK: instalacion completa, referencias repetibles y rechazo sobre esquema ocupado.')
    print('Sin usuarios ni prospectos; 25 ofertas simuladas, 3 roles, 10 motivos y 3 descuentos.')
finally:
    docker('rm', '-f', NAME)
    print('Contenedor QA eliminado.')
