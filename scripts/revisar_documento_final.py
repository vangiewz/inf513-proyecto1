"""Verifica índices y entregables PUDS; renderiza todas las páginas para revisión visual.

La extracción y las huellas no sustituyen la inspección de las imágenes generadas.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata
import pymupdf


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--render', action='store_true')
    args = ap.parse_args()
    root = Path(__file__).resolve().parents[1]
    docdir = root / 'doc'
    pdf = pymupdf.open(docdir / 'P1-INF513-GRUPO05SA.pdf')
    texts = [p.get_text() for p in pdf]
    report = {'fecha': datetime.now(timezone.utc).isoformat(), 'paginas': len(pdf),
              'alcance': 'Revisión local final; sin validación institucional',
              'paginas_blancas': [], 'marcadores_pendientes': [], 'indices': {},
              'revision_visual': 'Imágenes completas generadas para inspección; registrar resultado después de revisarlas',
              'migraciones_produccion': 'PENDIENTES DE APLICAR'}
    for i, p in enumerate(pdf):
        if not texts[i].strip() and not p.get_images() and not p.get_drawings():
            report['paginas_blancas'].append(i + 1)
        if re.search(r'\[PENDIENTE:', texts[i]):
            report['marcadores_pendientes'].append(i + 1)
    def lineas_normalizadas(text):
        return [''.join(c for c in unicodedata.normalize('NFD', line.strip().upper())
                        if unicodedata.category(c) != 'Mn') for line in text.splitlines()]
    figura_inicio = next(i for i, t in enumerate(texts) if 'INDICE DE FIGURAS' in lineas_normalizadas(t))
    tabla_inicio = next(i for i, t in enumerate(texts) if 'INDICE DE TABLAS' in lineas_normalizadas(t))
    cuerpo_inicio = next(i for i, t in enumerate(texts) if i > tabla_inicio and 'I. INTRODUCCION' in lineas_normalizadas(t))
    for kind, inicio, fin in [('Figura', figura_inicio, tabla_inicio), ('Tabla', tabla_inicio, cuerpo_inicio)]:
        index = {}
        actual = {}
        # Un título largo puede llenar el renglón sin puntos de guía. Separar por rótulos,
        # no por los puntos, y quitar el número de página extraído al principio de cada hoja.
        hojas = []
        for i in range(inicio, fin):
            lines = texts[i].splitlines()
            if lines and lines[0].strip() == str(i + 1):
                lines.pop(0)
            hojas.append('\n'.join(lines))
        index_text = '\n'.join(hojas)
        entries = list(re.finditer(r'(?m)^' + kind + r'\s+(\d+)\.\s*', index_text))
        for j, m in enumerate(entries):
            segment = index_text[m.end():entries[j + 1].start() if j + 1 < len(entries) else len(index_text)]
            page_number = re.search(r'(\d+)\s*$', segment)
            if page_number:
                index[int(m.group(1))] = int(page_number.group(1))
        for i, text in enumerate(texts):
            if i < cuerpo_inicio:
                continue
            for m in re.finditer(r'(?m)^' + kind + r'\s+(\d+)\.', text):
                actual[int(m.group(1))] = i + 1
        differences = [{'numero': n, 'indice': index.get(n), 'pagina_real': actual.get(n)}
                       for n in sorted(set(index) | set(actual)) if index.get(n) != actual.get(n)]
        report['indices'][kind] = {'entradas': len(index), 'rotulos': len(actual), 'diferencias': differences}
    for name in ['local-final', 'lisper-vacio', 'modelos-globales', 'auditoria-ampliada']:
        path = docdir / 'pruebas' / (name + '.json')
        if path.exists():
            data = json.loads(path.read_text(encoding='utf-8'))
            report[name] = {k: data[k] for k in ['aprobado', 'junit', 'tablas', 'columnas', 'claves_foraneas'] if k in data}
    report['sha256'] = {}
    for key, name in [('doc', 'doc/P1-INF513-GRUPO05SA.doc'), ('docx', 'doc/P1-INF513-GRUPO05SA.docx'),
                      ('pdf', 'doc/P1-INF513-GRUPO05SA.pdf'), ('laminas', 'doc/P1-LAMINAS-UML-GRUPO05SA.pdf'),
                      ('ea', 'doc/ea/crm-p1.eapx'), ('jar', 'target/crm-mail.jar')]:
        report['sha256'][key] = hashlib.sha256((root / name).read_bytes()).hexdigest()
    if args.render:
        out = root / 'target/revision-puds'
        out.mkdir(parents=True, exist_ok=True)
        for i, p in enumerate(pdf):
            p.get_pixmap(matrix=pymupdf.Matrix(1.25, 1.25), alpha=False).save(out / ('pagina-%03d.png' % (i + 1)))
        report['imagenes_renderizadas'] = len(pdf)
    report['verificacion_estructural_aprobada'] = (not report['paginas_blancas'] and not report['marcadores_pendientes']
        and all(x['entradas'] > 0 and not x['diferencias'] for x in report['indices'].values()))
    output = docdir / 'pruebas/revision-documental.json'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: report[k] for k in ['paginas', 'paginas_blancas', 'marcadores_pendientes', 'indices', 'verificacion_estructural_aprobada']}, ensure_ascii=False, indent=2))
    return 0 if report['verificacion_estructural_aprobada'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
