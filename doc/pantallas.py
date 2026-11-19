"""Pantallas del P1 (Diseño): el comando enviado arriba y, abajo, la respuesta HTML que devuelve el sistema, como PNG en
p1/doc/capturas/pantalla-*.png (Chrome headless). documento_p1.py importa PANTALLAS para la tabla y las figuras.
Uso: python p1/doc/pantallas.py   (antes: mvn package; usa .secrets/crm-mail.properties y db_grupo05sa)
- Comandos de lectura o que fallan en la validación: se ejecutan en vivo con crm-mail.jar --probar (sin enviar correo;
  cada uno deja su fila en log_comandos).
- Comandos que escriben: se usa la respuesta guardada en capturas/respuestas/ cuando se ejecutaron sobre los datos de
  demostración del 18/11/2026 (repetirlos daría otro resultado)."""
import html, pathlib, subprocess, tempfile

P1 = pathlib.Path(__file__).resolve().parents[1]
RAIZ = P1.parent
JAR, CFG = P1 / 'target/crm-mail.jar', RAIZ / '.secrets/crm-mail.properties'
OUT = P1 / 'doc/capturas'
CHROME = r'C:\Program Files\Google\Chrome\Application\chrome.exe'
G = 'pruebatecnoweb123+{}@gmail.com'
VEND1, VEND2, COORD = G.format('vend1'), G.format('vend2'), G.format('coord')

# (archivo, CU, título de la figura, remitente mostrado, rol, asunto, fuente: correo real para ejecutar o respuesta guardada)
PANTALLAS = [
    ('pantalla-help.png', 'Menú', 'HELP[]: menú del rol vendedor', VEND1, 'vendedor', 'HELP[]', VEND1),
    ('pantalla-cu1-insertar.png', 'CU1', 'INSERTARUSUARIO: alta de un vendedor', 'cuenta del administrador', 'administrador',
     'INSERTARUSUARIO["Vendedor Tres","Pruebas","pruebatecnoweb123+vend3@gmail.com","70011223","vendedor"]', 'cu1-insertar.html'),
    ('pantalla-cu2-oferta.png', 'CU2', 'LISTAROFERTA: oferta académica para un visitante', G.format('curioso'),
     'visitante, no registrado', 'LISTAROFERTA[]', G.format('curioso')),
    ('pantalla-cu2-registrar.png', 'CU2', 'REGISTRARPROSPECTO: prospecto registrado en la bolsa común', VEND1, 'vendedor',
     'REGISTRARPROSPECTO["4815263","Camila","Vargas Antelo","78123456","camila.vargas@example.com","Feria",'
     '"ia-aplicada-m3","Ingeniería de Sistemas","Santa Cruz"]', 'cu2-registrar.html'),
    ('pantalla-cu3-asignar.png', 'CU3', 'ASIGNARPROSPECTO: tarjeta asignada a un vendedor', COORD, 'coordinador',
     'ASIGNARPROSPECTO["6","pruebatecnoweb123+vend3@gmail.com"]', 'cu3-asignar.html'),
    ('pantalla-cu4-tablero.png', 'CU4', 'VERTABLERO: tablero Kanban del vendedor', VEND1, 'vendedor', 'VERTABLERO[]', VEND1),
    ('pantalla-cu4-mover.png', 'CU4', 'MOVERTARJETA: tarjeta movida a En Contacto', VEND2, 'vendedor',
     'MOVERTARJETA["5","En Contacto"]', 'cu4-mover.html'),
    ('pantalla-cu5-perdido.png', 'CU5', 'MARCARPERDIDO: pérdida con su motivo', VEND1, 'vendedor',
     'MARCARPERDIDO["2","Precio fuera de su presupuesto","Pidió que lo contacten el próximo semestre"]', 'cu5-perdido.html'),
    ('pantalla-cu6-orden.png', 'CU6', 'GENERARORDEN: orden de pago con referencia', VEND1, 'vendedor',
     'GENERARORDEN["3"]', 'cu6-orden.html'),
    ('pantalla-cu6-aprobar.png', 'CU6', 'APROBARPAGO: pago aprobado por el coordinador', COORD, 'coordinador',
     'APROBARPAGO["3","Datos y comprobante revisados"]', 'cu6-aprobar.html'),
    ('pantalla-cu6-convertir.png', 'CU6', 'CONVERTIRPROSPECTO: inscripción comercial confirmada', VEND2, 'vendedor',
     'CONVERTIRPROSPECTO["4"]', 'cu6-convertir.html'),
    ('pantalla-cu7-interaccion.png', 'CU7', 'REGISTRARINTERACCION: llamada registrada', VEND1, 'vendedor',
     'REGISTRARINTERACCION["3","Llamada","Consultó horarios del módulo Desarrollo Backend; se le enviará la orden de pago"]',
     'cu7-interaccion.html'),
    ('pantalla-cu7-historial.png', 'CU7', 'VERHISTORIAL: historial de una tarjeta', VEND1, 'vendedor', 'VERHISTORIAL["3"]', VEND1),
    ('pantalla-cu8-embudo.png', 'CU8', 'REPORTEEMBUDO: embudo de ventas del coordinador', COORD, 'coordinador',
     'REPORTEEMBUDO[]', COORD),
    ('pantalla-error-param.png', 'Error', 'Error: parámetros incompletos', VEND1, 'vendedor',
     'REGISTRARPROSPECTO["7012345","Ana"]', VEND1),
    ('pantalla-inexistente.png', 'Error', 'Error: comando inexistente', VEND1, 'vendedor', 'LISTARCLIENTES[]', VEND1),
    ('pantalla-denegado.png', 'Denegado', 'Denegado: comando fuera del rol', VEND1, 'vendedor', 'VERBITACORA["5"]', VEND1),
]
BODY = '<body style="margin:0;padding:0;background:#FFFFFF;">'
CAB = ('<div style="font-family:Segoe UI,Arial,sans-serif;font-size:14px;line-height:21px;background:#F3F4F6;'
       'border-bottom:1px solid #D1D5DB;padding:12px 18px;color:#1B2430">'
       '<div style="font-weight:600;color:#4B5563">Correo enviado al sistema</div>'
       '<div>De: {de} ({rol})</div><div>Para: grupo05sa@tecnoweb.org.bo</div>'
       '<div style="word-break:break-all">Asunto: <b style="font-family:Consolas,monospace">{asunto}</b></div></div>'
       '<div style="font-family:Segoe UI,Arial,sans-serif;font-size:14px;font-weight:600;color:#4B5563;'
       'padding:12px 18px 0">Respuesta del sistema (cuerpo HTML del correo)</div>')


def respuesta(fuente, asunto, tmp):
    if fuente.endswith('.html'):
        return (OUT / 'respuestas' / fuente).read_text(encoding='utf-8')
    subprocess.run(['java', '-jar', str(JAR), '--probar', str(CFG), fuente, asunto], cwd=tmp, check=True, capture_output=True)
    return (tmp / 'respuesta.html').read_text(encoding='utf-8')


def main():
    from PIL import Image, ImageChops
    for png, _, _, de, rol, asunto, fuente in PANTALLAS:
        with tempfile.TemporaryDirectory() as tmp:
            tmp = pathlib.Path(tmp)
            cuerpo = respuesta(fuente, asunto, tmp)
            assert BODY in cuerpo, 'cambió el <body> de ResponseFormatter.html()'
            cab = CAB.format(de=html.escape(de), rol=rol, asunto=html.escape(asunto))
            (tmp / 'p.html').write_text(cuerpo.replace(BODY, BODY + cab, 1), encoding='utf-8')
            subprocess.run([CHROME, '--headless=new', '--hide-scrollbars', '--force-device-scale-factor=2',
                            '--window-size=900,2600', f'--screenshot={tmp / "s.png"}', (tmp / 'p.html').as_uri()],
                           check=True, capture_output=True)
            im = Image.open(tmp / 's.png').convert('RGB')
            fondo = ImageChops.difference(im, Image.new('RGB', im.size, 'white')).getbbox()
            im.crop((0, 0, im.width, min(im.height, fondo[3] + 30))).save(OUT / png)
        print(png)


if __name__ == '__main__':
    main()
