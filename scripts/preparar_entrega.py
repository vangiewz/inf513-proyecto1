"""Prepara el ZIP de P1 por lista permitida; no publica ni incluye configuración privada."""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile


def main():
    p1 = Path(__file__).resolve().parents[1]
    root = p1.parent
    dest = root / 'entrega/2026-2_INF513-P1_grupo05sa.zip'
    files = set()
    for name in ('README.md', 'BASE_DATOS.md', 'PRUEBAS.md', 'GUIA_CORREO_CASOS_APROBADOS.md',
                 'REVISION_DOCUMENTO.md', 'AUDITORIA_ENUNCIADO.md', 'AUDITORIA_ADICIONAL.md', 'CONTINUAR_P1.md', 'pom.xml', 'ejecutar.ps1',
                 'respaldo.ps1', 'crm-mail.properties.example', 'target/crm-mail.jar',
                 'doc/P1-INF513-GRUPO05SA.doc', 'doc/P1-INF513-GRUPO05SA.docx',
                 'doc/P1-INF513-GRUPO05SA.pdf', 'doc/P1-LAMINAS-UML-GRUPO05SA.pdf'):
        path = p1 / name
        if not path.is_file():
            raise FileNotFoundError(path)
        files.add(path)
    for folder, extensions in (('src', {'.java'}), ('sql', {'.sql'}), ('deploy', {'.service', '.sh'}),
                               ('scripts', {'.py', '.ps1'}), ('doc', {'.py', '.png', '.emf', '.eapx', '.json', '.tsv', '.txt', '.html'})):
        files.update(p for p in (p1 / folder).rglob('*') if p.is_file() and p.suffix.lower() in extensions
                     and '__pycache__' not in p.parts and '.teselas' not in p.parts
                     and p.name not in {'usuarios.sql', 'paquete-entrega.json'} and not p.name.startswith('~$'))
    if (root / 'tools/doc_ficct.py').is_file():
        files.add(root / 'tools/doc_ficct.py')
    records = []
    for path in sorted(files):
        relative = path.relative_to(root)
        assert '.secrets' not in relative.parts and '.git' not in relative.parts
        assert path.suffix != '.properties' and path.suffix not in {'.dump', '.log', '.key', '.pem'}
        records.append({'archivo': relative.as_posix(), 'bytes': path.stat().st_size,
                        'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    manifest = {'fecha': datetime.now().isoformat(timespec='seconds'), 'estado': 'Preparado localmente; no entregado',
                'pendiente': ['Migraciones y circuito real en servidor', 'Acceso público a GitHub y publicación',
                              'Fecha del aula, subida e impresión'], 'archivos': records}
    dest.parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='p1-paquete-') as td:
        temp = Path(td) / dest.name
        with ZipFile(temp, 'w', ZIP_DEFLATED) as archive:
            for row in records:
                archive.write(root / row['archivo'], row['archivo'])
            archive.writestr('MANIFIESTO.json', json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        with ZipFile(temp) as archive:
            assert archive.testzip() is None
            for row in records:
                assert hashlib.sha256(archive.read(row['archivo'])).hexdigest() == row['sha256']
        if dest.exists():
            backup = Path(tempfile.mkdtemp(prefix='p1-entrega-anterior-')) / dest.name
            shutil.copy2(dest, backup)
            print('Paquete anterior conservado:', backup)
        shutil.copy2(temp, dest)
    report = {'zip': dest.name, 'archivos': len(records) + 1, 'bytes': dest.stat().st_size,
              'sha256': hashlib.sha256(dest.read_bytes()).hexdigest(), 'integridad': 'CRC y SHA256 verificados',
              'configuracion_privada_incluida': False, 'publicado_o_entregado': False}
    (p1 / 'doc/pruebas/paquete-entrega.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
