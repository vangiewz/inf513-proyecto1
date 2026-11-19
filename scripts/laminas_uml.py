"""Reproduce diagramas completos del PUDS en láminas vectoriales ampliadas, sin dividirlos.

El informe principal conserva el formato carta FICCT. Cada lámina complementaria contiene la misma figura
EA y aumenta su escala para poder leer atributos y operaciones. No modifica el modelo ni el PDF original.
"""
import json
from pathlib import Path
import re

import pymupdf


def main():
    root = Path(__file__).resolve().parents[1]
    source = pymupdf.open(root / 'doc/P1-INF513-GRUPO05SA.pdf')
    output = pymupdf.open()
    prefixes = ('Modelo conceptual global', 'Modelo lógico global', 'Diseño procedimental global',
                'Diseño procedimental CU', 'Diagrama de secuencia CU', 'Diagrama de colaboración CU',
                'Diagrama de despliegue', 'Diagrama de componentes', 'Diagrama de capas',
                'Modelo de casos de uso', 'Modelo de dominio inicial', 'Dependencia de paquetes')
    records, toc = [], []
    for i, page in enumerate(source):
        if i < 15: continue  # excluir los índices
        caption = re.search(r'(?m)^Figura\s+(\d+)\.\s+(.+)', page.get_text())
        if not caption or not caption.group(2).startswith(prefixes): continue
        drawings = [pymupdf.Rect(p['rect']) for p in page.get_drawings()
                    if p['rect'].width > 100 and p['rect'].height > 50]
        if not drawings: raise RuntimeError(f'No se encontró el marco vectorial de la figura {caption.group(1)}')
        clip = max(drawings, key=lambda r: r.get_area())
        clip += (-2, -2, 2, 2)
        sizes = [s['size'] for b in page.get_text('dict')['blocks'] if 'lines' in b for l in b['lines']
                 for s in l['spans'] if s['text'].strip() and clip.contains(pymupdf.Rect(s['bbox'])) and s['size'] > 0.5]
        if not sizes: raise RuntimeError(f'No se pudo medir el texto vectorial de la figura {caption.group(1)}')
        small = min(sizes)
        scale = max(1, 8 / small)
        dest = output.new_page(width=clip.width * scale + 48, height=clip.height * scale + 90)
        dest.insert_font(fontname='TimesP1', fontfile='C:/Windows/Fonts/times.ttf')
        title = f'Figura {caption.group(1)}. {caption.group(2)}'
        dest.insert_text((24, 23), title, fontsize=14, fontname='TimesP1')
        dest.insert_text((24, 44), f'PUDS, página {i + 1}. Mismo diagrama EA completo, ampliado sin dividir.',
                         fontsize=11, fontname='TimesP1')
        dest.show_pdf_page(pymupdf.Rect(24, 66, dest.rect.width - 24, dest.rect.height - 24), source, i, clip=clip)
        toc.append((1, title, len(output)))
        records.append({'figura': int(caption.group(1)), 'titulo': caption.group(2), 'pagina_puds': i + 1,
                        'pagina_lamina': len(output), 'menor_letra_puds_pt': round(small, 2),
                        'menor_letra_lamina_pt': round(small * scale, 2),
                        'ancho_mm': round(dest.rect.width / 72 * 25.4),
                        'alto_mm': round(dest.rect.height / 72 * 25.4)})
    if not records: raise RuntimeError('No se seleccionaron diagramas')
    output.set_toc(toc)
    output.set_metadata({'title': 'P1 Grupo 05 SA Láminas UML completas', 'author': 'Grupo 05 SA'})
    path = root / 'doc/P1-LAMINAS-UML-GRUPO05SA.pdf'
    output.save(path, garbage=4, deflate=True)
    report = {'fecha': '2026-11-19', 'laminas': records,
              'nota': 'Una lámina por figura, formato ampliado complementario. Informe principal en carta.'}
    (root / 'doc/pruebas/laminas-uml.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'{len(records)} láminas vectoriales completas: {path}')


if __name__ == '__main__':
    main()
