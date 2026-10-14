"""Mapa de ubicación de la FICCT (sección III) con teselas de OpenStreetMap.
Uso: python p1/doc/mapa.py -> p1/doc/diagramas/ubicacion.png
Coordenadas del edificio (way 307614597 de OSM, consultado el 13/10/2026): módulo 236, Av. Dr. Enrique Aponte Canseco."""
import io, math, pathlib, urllib.request
from PIL import Image, ImageDraw, ImageFont

LAT, LON = -17.7762188, -63.1950312
SALIDA = pathlib.Path(__file__).resolve().parent / 'diagramas' / 'ubicacion.png'
CACHE = pathlib.Path(__file__).resolve().parent / 'diagramas' / '.teselas'
F = 'C:/Windows/Fonts/'
NEGRITA, NORMAL, CHICA = (ImageFont.truetype(F + n, s) for n, s in (('timesbd.ttf', 30), ('times.ttf', 26), ('times.ttf', 20)))
TAM = 256


def tesela(z, x, y):
    ruta = CACHE / f'{z}_{x}_{y}.png'
    if not ruta.exists():
        CACHE.mkdir(exist_ok=True)
        req = urllib.request.Request(f'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                                     headers={'User-Agent': 'TecnoTarea-INF513/1.0 (documento academico)'})
        ruta.write_bytes(urllib.request.urlopen(req, timeout=30).read())
    return Image.open(io.BytesIO(ruta.read_bytes())).convert('RGB')


def panel(z, ancho, alto, titulo):
    n = 2 ** z
    px = (LON + 180) / 360 * n * TAM
    py = (1 - math.asinh(math.tan(math.radians(LAT))) / math.pi) / 2 * n * TAM
    x0, y0 = int(px - ancho / 2), int(py - alto / 2)
    img = Image.new('RGB', (ancho, alto))
    for tx in range(x0 // TAM, (x0 + ancho) // TAM + 1):
        for ty in range(y0 // TAM, (y0 + alto) // TAM + 1):
            img.paste(tesela(z, tx, ty), (tx * TAM - x0, ty * TAM - y0))
    d = ImageDraw.Draw(img)
    cx, cy = ancho // 2, alto // 2
    d.ellipse([cx - 14, cy - 14, cx + 14, cy + 14], fill='#c00000', outline='white', width=4)
    d.text((cx + 22, cy), 'FICCT (módulo 236)', font=NEGRITA, fill='#c00000', anchor='lm', stroke_width=4, stroke_fill='white')
    d.rectangle([0, 0, ancho - 1, alto - 1], outline='black', width=3)
    d.text((12, 10), titulo, font=NORMAL, fill='black', stroke_width=3, stroke_fill='white')
    return img


ciudad = panel(14, 900, 700, 'Santa Cruz de la Sierra')
campus = panel(17, 900, 700, 'Ciudad Universitaria (UAGRM)')
img = Image.new('RGB', (1830, 750), 'white')
img.paste(ciudad, (0, 0)); img.paste(campus, (930, 0))
ImageDraw.Draw(img).text((1830, 735), '© colaboradores de OpenStreetMap (openstreetmap.org/copyright, ODbL)',
                         font=CHICA, fill='black', anchor='rm')
img.save(SALIDA, dpi=(300, 300))
print(SALIDA)
