"""Comprueba manifiesto, integridad, exclusiones y compilación del ZIP en una carpeta nueva.

No ejecuta la aplicación ni utiliza servicios institucionales. Maven puede descargar dependencias.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    root = Path(__file__).resolve().parents[1]
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--zip', type=Path, default=root.parent / 'entrega/2026-2_INF513-P1_grupo05sa.zip')
    ap.add_argument('--java-home', type=Path)
    args = ap.parse_args()
    report = {'fecha': datetime.now(timezone.utc).isoformat(), 'zip': args.zip.name,
              'sha256': hashlib.sha256(args.zip.read_bytes()).hexdigest(),
              'integridad': False, 'compilacion_desde_zip': False,
              'aplicacion_ejecutada': False, 'servicios_institucionales_utilizados': False}
    work = Path(tempfile.mkdtemp(prefix='p1-entrega-verificacion-')).resolve()
    try:
        with ZipFile(args.zip) as archive:
            if archive.testzip() is not None:
                raise AssertionError('CRC incorrecto')
            names = archive.namelist()
            for name in names:
                path = PurePosixPath(name)
                if path.is_absolute() or '..' in path.parts or '\\' in name or ':' in name:
                    raise AssertionError('Ruta insegura en ZIP')
                if any(x in path.parts for x in ['.git', '.secrets', '__pycache__', 'node_modules']):
                    raise AssertionError('Directorio privado o temporal')
                if path.suffix.lower() in {'.log', '.dump', '.backup', '.bak', '.tmp', '.key', '.pem', '.eml', '.hmac'}:
                    raise AssertionError('Archivo excluido: ' + name)
                if path.name == '.env' or (path.suffix == '.properties' and path.name != 'crm-mail.properties.example'):
                    raise AssertionError('Configuración privada')
            manifest = json.loads(archive.read('MANIFIESTO.json').decode('utf-8'))
            for row in manifest['archivos']:
                payload = archive.read(row['archivo'])
                if len(payload) != row['bytes'] or hashlib.sha256(payload).hexdigest() != row['sha256']:
                    raise AssertionError('Manifiesto no coincide: ' + row['archivo'])
                if PurePosixPath(row['archivo']).suffix.lower() in {'.java', '.md', '.py', '.sql', '.json', '.xml', '.example', '.ps1'}:
                    for pattern in [rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
                                    rb'gh[pousr]_[A-Za-z0-9]{30,}', rb'github_pat_[A-Za-z0-9_]{50,}',
                                    rb'AKIA[0-9A-Z]{16}']:
                        if re.search(pattern, payload):
                            raise AssertionError('Posible secreto de formato conocido en ' + row['archivo'])
            if set(names) != {'MANIFIESTO.json'} | {r['archivo'] for r in manifest['archivos']}:
                raise AssertionError('Entradas fuera del manifiesto')
            project_dirs = {str(PurePosixPath(n).parent) for n in names if PurePosixPath(n).name == 'pom.xml'}
            if len(project_dirs) != 1:
                raise AssertionError('No se encontró un único proyecto Maven')
            project_relative = next(iter(project_dirs))
            for name in ['README.md', '.gitignore', 'OPERACION.md', 'crm-mail.properties.example',
                         'sql/esquema.sql', 'sql/diagnostico_estructura.sql',
                         'sql/migraciones/2026-11-19_persona.sql', 'target/crm-mail.jar',
                         'doc/P1-INF513-GRUPO05SA.docx', 'doc/P1-INF513-GRUPO05SA.pdf',
                         'doc/ea/crm-p1.eapx', 'scripts/probar_local.py']:
                if project_relative + '/' + name not in names:
                    raise AssertionError('Falta archivo requerido: ' + name)
            archive.extractall(work)
        report['integridad'] = True
        report['archivos'] = len(names)
        env = dict(os.environ)
        for name in list(env):
            if name.startswith('P1_'):
                env.pop(name)
        if args.java_home:
            env['JAVA_HOME'] = str(args.java_home.resolve())
            env['PATH'] = str(args.java_home.resolve() / 'bin') + os.pathsep + env.get('PATH', '')
        env['MAVEN_OPTS'] = env.get('MAVEN_OPTS', '') + ' -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8'
        mvn = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn', path=env.get('PATH'))
        if not mvn:
            raise FileNotFoundError('Maven no disponible')
        # clean obliga a reconstruir desde fuentes y descarta el JAR incluido; no ejecuta pruebas
        # que escriban en bases de datos. testCompile sí compila todas las pruebas distribuidas.
        result = subprocess.run([mvn, '-B', 'clean', 'package', '-DskipTests'], cwd=work / project_relative,
                                env=env, capture_output=True, encoding='utf-8', errors='replace', timeout=600,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        (root / 'target/qa-local').mkdir(parents=True, exist_ok=True)
        (root / 'target/qa-local/compilacion-zip.txt').write_text(result.stdout + result.stderr, encoding='utf-8')
        report['compilacion_exit_code'] = result.returncode
        report['compilacion_desde_zip'] = result.returncode == 0
        if result.returncode:
            raise RuntimeError('La compilación extraída falló; ver target/qa-local/compilacion-zip.txt')
        print('ZIP íntegro, manifiesto comprobado y compilación nueva desde fuentes aprobada.')
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        report['aprobado'] = report['integridad'] and report['compilacion_desde_zip']
        (root / 'doc/pruebas/entrega-verificada.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print('Carpeta exclusiva de verificación: ' + str(work))


if __name__ == '__main__':
    main()
