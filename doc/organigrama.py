"""Organigrama de la unidad (sección III). Uso: python p1/doc/organigrama.py -> p1/doc/diagramas/organigrama.png
Cada cargo lleva el rol que tiene en el sistema, porque de aquí salen los actores (Captura de Requisitos)."""
import pathlib
from PIL import Image, ImageDraw, ImageFont

SALIDA = pathlib.Path(__file__).resolve().parent / 'diagramas' / 'organigrama.png'
F = 'C:/Windows/Fonts/'
NEGRITA, NORMAL = (ImageFont.truetype(F + n, s) for n, s in (('timesbd.ttf', 44), ('times.ttf', 34)))

# (título, detalle, relleno, color de texto), de arriba abajo
CAJAS = [
    ('Decanato de la FICCT', 'Facultad de Ingeniería en Ciencias de la\nComputación y Telecomunicaciones', '#3a3a3a', 'white'),
    ('Jefatura de la Unidad de Capacitación', '1 jefe de unidad\nRol en el sistema: administrador', '#d9d9d9', 'black'),
    ('Coordinación de Programas', '2 coordinadores, uno por grupo de programas\nRol en el sistema: coordinador', 'white', 'black'),
    ('Equipo Comercial', 'Asesores comerciales\nRol en el sistema: vendedor', 'white', 'black'),
]
ANCHO, W, H, PASO, MARGEN = 1500, 1000, 230, 330, 40
img = Image.new('RGB', (ANCHO, MARGEN * 2 + PASO * (len(CAJAS) - 1) + H), 'white')
d = ImageDraw.Draw(img)
x0 = (ANCHO - W) // 2
for i, (titulo, detalle, fondo, tinta) in enumerate(CAJAS):
    y0 = MARGEN + i * PASO
    if i: d.line([(ANCHO // 2, y0 - (PASO - H)), (ANCHO // 2, y0)], fill='black', width=4)
    d.rectangle([x0, y0, x0 + W, y0 + H], fill=fondo, outline='black', width=4)
    d.text((ANCHO // 2, y0 + 62), titulo, font=NEGRITA, fill=tinta, anchor='mm')
    d.multiline_text((ANCHO // 2, y0 + 155), detalle, font=NORMAL, fill=tinta, anchor='mm', align='center', spacing=8)
img.save(SALIDA, dpi=(300, 300))
print(SALIDA)
