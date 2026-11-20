"""Prueba de caídas del lector real (target/crm-mail.jar), solo en 127.0.0.1.

Arranca un POP3 y un SMTP de prueba, deposita correos de a poco y ejecuta el JAR una y otra vez cortándolo con
TerminateProcess/SIGKILL en momentos al azar (durante la descarga, el proceso o el envío). En
paralelo el POP3 a veces pierde la respuesta del QUIT o no aplica los DELE, y el SMTP a veces está
caído o recibe el correo y corta antes de confirmar (entrega incierta). Al final deja correr el
lector sin cortes hasta vaciar todo y comprueba:

  1. ninguna solicitud se pierde: cada comando distinto se ejecutó exactamente una vez;
  2. ninguna operación se duplica: mismo correo releído o reintento del usuario = una ejecución;
  3. toda respuesta llega al menos una vez; las copias repetidas tienen el mismo Message-ID;
  4. no quedan correos en el buzón, ni registros 'Recibido', ni respuestas 'Pendiente'.
Con --pgdata también apaga PostgreSQL de golpe (pg_ctl stop -m immediate) en plena ejecución.

Requiere una base PostgreSQL DESECHABLE en 127.0.0.1 con sql/esquema.sql y sql/referencias.sql.
Uso:
  python -I scripts/prueba_caidas.py --db jdbc:postgresql://127.0.0.1:55443/crm_caidas \
      --psql "C:/Program Files/PostgreSQL/18/bin/psql.exe" [--cortes 40] [--semilla 7]       [--pgdata CARPETA_DEL_CLUSTER --pg-ctl ".../pg_ctl.exe"]
Deja el resumen en doc/pruebas/caidas.json.
"""
import argparse, base64, json, os, random, re, secrets, socket, subprocess, sys, tempfile, threading, time, uuid
from firmar_correo import firmar

ap = argparse.ArgumentParser()
ap.add_argument("--db", required=True)
ap.add_argument("--usuario", default="crm_user")
ap.add_argument("--clave", default="crm_password")
ap.add_argument("--psql", default="psql")
ap.add_argument("--jar", default=os.path.join(os.path.dirname(__file__), "..", "target", "crm-mail.jar"))
ap.add_argument("--cortes", type=int, default=40)
ap.add_argument("--semilla", type=int, default=7)
ap.add_argument("--pgdata", help="carpeta del clúster desechable: si se da, también se cae la BD")
ap.add_argument("--pg-ctl", default="pg_ctl")
ap.add_argument("--salida", default=os.path.join(os.path.dirname(__file__), "..", "doc", "pruebas", "caidas.json"))
a = ap.parse_args()
m = re.match(r"jdbc:postgresql://(127\.0\.0\.1):(\d+)/([^?]+)", a.db)
if not m:
    sys.exit("Solo contra una base desechable en 127.0.0.1")
HOST, PUERTO, BASE = m.groups()
rnd = random.Random(a.semilla)


def sql(q):
    env = dict(os.environ, PGPASSWORD=a.clave, PGCLIENTENCODING="UTF8")
    r = subprocess.run([a.psql, "-X", "-q", "-tA", "-v", "ON_ERROR_STOP=1", "-h", HOST, "-p", PUERTO,
                        "-U", a.usuario, "-d", BASE, "-c", q], capture_output=True, text=True,
                       encoding="utf-8", env=env)
    if r.returncode:
        sys.exit("psql: " + r.stderr)
    return r.stdout.strip()


# ---------------------------------------------------------------- servidores de prueba
class Pop3:
    def __init__(self):
        self.buzon, self.cerrojo, self.ocupado = [], threading.Lock(), False
        self.modo_quit = "ok"  # ok | perder_respuesta | sin_aplicar
        self.eventos = {"ok": 0, "perder_respuesta": 0, "sin_aplicar": 0}

    def atender(self, c):
        f = c.makefile("rb")
        out = lambda s: c.sendall((s + "\r\n").encode("latin-1"))
        out("+OK QA")
        vista, borrar, mio = [], set(), False
        try:
            while True:
                l = f.readline()
                if not l:
                    return
                p = l.decode("latin-1").strip().split(" ")
                v = p[0].upper()
                if v == "USER":
                    out("+OK")
                elif v == "PASS":
                    with self.cerrojo:
                        if self.ocupado:
                            out("-ERR [IN-USE] Mailbox is locked")
                            continue
                        self.ocupado = mio = True
                        vista = list(self.buzon)
                    out("+OK")
                elif v == "STAT":
                    out("+OK %d 0" % len(vista))
                elif v == "LIST":
                    out("+OK %s %d" % (p[1], len(vista[int(p[1]) - 1])))
                elif v in ("RETR", "TOP"):
                    out("+OK")
                    lineas = vista[int(p[1]) - 1].split(b"\r\n")
                    # El terminador del mensaje no es una línea vacía adicional. Agregarla
                    # alteraría el cuerpo MIME y, correctamente, invalidaría la firma HMAC.
                    if lineas and lineas[-1] == b"":
                        lineas.pop()
                    for x in lineas:
                        c.sendall((b"." + x if x.startswith(b".") else x) + b"\r\n")
                    out(".")
                elif v == "DELE":
                    borrar.add(int(p[1]))
                    out("+OK")
                elif v == "QUIT":
                    modo = self.modo_quit if borrar else "ok"
                    self.eventos[modo] += 1
                    if modo != "sin_aplicar":
                        with self.cerrojo:
                            for n in borrar:
                                if vista[n - 1] in self.buzon:
                                    self.buzon.remove(vista[n - 1])
                    if modo == "ok":
                        out("+OK")
                    return
                else:
                    out("-ERR")
        finally:
            if mio:
                with self.cerrojo:
                    self.ocupado = False


class Smtp:
    def __init__(self):
        self.recibidos, self.cerrojo = [], threading.Lock()
        self.modo = "ok"  # ok | caido | incierto
        self.eventos = {"ok": 0, "caido": 0, "incierto": 0}

    def atender(self, c):
        modo = self.modo
        if modo == "caido":
            self.eventos["caido"] += 1
            return
        f = c.makefile("rb")
        out = lambda s: c.sendall((s + "\r\n").encode("latin-1"))
        out("220 qa")
        while True:
            l = f.readline()
            if not l:
                return
            u = l.decode("latin-1").strip().upper()
            if u.startswith("EHLO"):
                out("250-qa\r\n250 AUTH PLAIN LOGIN")
            elif u.startswith("AUTH"):
                out("235 ok")
            elif u.startswith("DATA"):
                out("354 datos")
                datos = []
                while True:
                    d = f.readline()
                    if not d or d.rstrip(b"\r\n") == b".":
                        break
                    datos.append(d)
                with self.cerrojo:
                    self.recibidos.append(b"".join(datos).decode("utf-8", "replace"))
                self.eventos[modo] += 1
                if modo == "incierto":
                    return  # lo recibió pero no confirma: el cliente no sabe si se entregó
                out("250 ok")
            elif u.startswith("QUIT"):
                out("221 adios")
                return
            else:
                out("250 ok")


def servir(obj):
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    s.listen(50)

    def bucle():
        while True:
            c, _ = s.accept()

            def uno(c=c):
                with c:
                    c.settimeout(30)
                    try:
                        obj.atender(c)
                    except OSError:
                        pass

            threading.Thread(target=uno, daemon=True).start()

    threading.Thread(target=bucle, daemon=True).start()
    return s.getsockname()[1]


pop, smtp = Pop3(), Smtp()
p_pop, p_smtp = servir(pop), servir(smtp)

# ---------------------------------------------------------------- datos
admin = "caidas.%s@example.test" % uuid.uuid4().hex[:8]
clave_firma = secrets.token_bytes(32)
doc = str(rnd.randint(10_000_000, 99_999_999))
sql("INSERT INTO usuarios(nombre,apellido,correo,password,rol,estado,fecha_inicio,fecha_fin,created_at,updated_at)"
    " VALUES('Admin','Caidas','%s','x','administrador','activo',CURRENT_DATE-1,CURRENT_DATE+30,now(),now())" % admin)
sql("INSERT INTO prospectos(nombre,apellido,documento,correo,telefono,created_at,updated_at)"
    " VALUES('Prospecto','Caidas','%s','%s@example.test','70000001',now(),now())" % (doc, doc))
tarjeta = sql("INSERT INTO intereses(documento,modulo_codigo,programa_nombre,modulo_nombre,created_at,updated_at)"
              " VALUES('%s','desarrollo-web-m1','Desarrollo de Aplicaciones Web','Fundamentos',now(),now())"
              " RETURNING nro_tarjeta" % doc).splitlines()[0]
marca = uuid.uuid4().hex[:6]


def correo(asunto, mid=None):
    mid = mid or "<%s@qa.test>" % uuid.uuid4()
    crudo = ("From: %s\r\nTo: buzon@example.test\r\nSubject: %s\r\nMessage-ID: %s\r\n"
            "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nqa\r\n"
            % (admin, asunto, mid)).encode("utf-8")
    return firmar(crudo, clave_firma), mid


textos = ["%s nota %02d" % (marca, i) for i in range(30)]
origen = {}  # Message-ID del correo recibido -> comando
asunto = lambda t: 'REGISTRARINTERACCION["%s","Nota","%s"]' % (tarjeta, t)
por_llegar = []  # se depositan de a poco, para que los cortes y fallas caigan con correo en curso
for t in textos:
    crudo, mid = correo(asunto(t))
    origen[mid] = t
    por_llegar.append(crudo)
    if t.endswith(("03", "11", "19")):
        por_llegar.append(crudo)  # el servidor entrega dos veces el mismo correo
    if t.endswith(("07", "23")):
        otro, mid2 = correo(asunto(t))  # el usuario lo reenvía (otro Message-ID)
        origen[mid2] = t
        por_llegar.append(otro)
total_buzon = len(por_llegar)

# ---------------------------------------------------------------- ejecución con cortes
tmp = tempfile.mkdtemp(prefix="p1-caidas-")
props = os.path.join(tmp, "qa.properties")
claves = os.path.join(tmp, "qa.hmac")
with open(claves, 'x', encoding='utf-8') as f:
    f.write(admin + '=' + base64.b64encode(clave_firma).decode('ascii') + '\n')
with open(props, "w", encoding="utf-8") as f:
    f.write("pop3.host=127.0.0.1\npop3.puerto=%d\npop3.usuario=qa\npop3.password=qa\n"
            "smtp.host=127.0.0.1\nsmtp.puerto=%d\nsmtp.usuario=sistema@example.test\nsmtp.password=qa\n"
            "buzon.sistema=buzon@example.test\ndb.url=%s\ndb.usuario=%s\ndb.password=%s\n"
            "mail.poll.interval.ms=3000\nauth.keys.file=%s\n" % (p_pop, p_smtp, a.db, a.usuario, a.clave, claves.replace('\\', '/')))
java = ["java", "-Dcrm.smtp.starttls=false", "-Dstdout.encoding=UTF-8", "-jar", os.path.abspath(a.jar), props]
log = open(os.path.join(tmp, "consola.txt"), "ab")
ultimo_fin_proceso = 0.0


def lanzar():
    # El arnés reproduce también el RestartSec=3 previsto, incluso entre procesos nuevos.
    demora = 3.0 - (time.monotonic() - ultimo_fin_proceso)
    if demora > 0:
        time.sleep(demora)
    return subprocess.Popen(java, cwd=tmp, stdout=log, stderr=subprocess.STDOUT)


def pg(accion):
    args = [a.pg_ctl, "-D", a.pgdata, "-w"]
    args += ["stop", "-m", "immediate"] if accion == "caer" else ["start", "-o", "-p %s -c listen_addresses=127.0.0.1" % PUERTO, "-l", os.path.join(tmp, "pg.log")]
    subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, timeout=60)


cortes, caidas_bd, mensajes_durante_caida, recuperaciones_mismo_proceso = [], 0, 0, 0
for i in range(a.cortes):
    with pop.cerrojo:
        for _ in range(rnd.randint(1, 3)):
            if por_llegar:
                pop.buzon.append(por_llegar.pop(0))
    pop.modo_quit = rnd.choice(["ok", "ok", "perder_respuesta", "sin_aplicar"])
    smtp.modo = rnd.choice(["ok", "ok", "caido", "incierto"])
    inicio_proceso_log = os.path.getsize(os.path.join(tmp, "consola.txt"))
    p = lanzar()
    espera = rnd.uniform(0.8, 4.0)  # el arranque toma ~1 s; luego cae en cualquier etapa
    if a.pgdata and i % 5 == 2:  # la BD se cae en plena ejecución (sin apagado ordenado)
        time.sleep(espera)
        pg("caer")
        caidas_bd += 1
        # Llegan mensajes NUEVOS mientras PostgreSQL está detenido. El POP3 sigue disponible:
        # deben conservarse en el buzón y procesarse al recuperar la base.
        with pop.cerrojo:
            for _ in range(2):
                if por_llegar:
                    pop.buzon.append(por_llegar.pop(0))
                    mensajes_durante_caida += 1
        # La caída dura más de dos intervalos normales para que el lector la observe.
        time.sleep(7)
        pg("levantar")
        # Esperar una recuperación OBSERVADA del MISMO proceso. Un tiempo fijo podría
        # cortarlo justo antes del siguiente backoff y dar un falso negativo al arnés.
        inicio_recuperacion = time.monotonic()
        while time.monotonic() - inicio_recuperacion < 90:
            if p.poll() is not None:
                raise RuntimeError("El lector terminó durante la recuperación de PostgreSQL QA")
            log.flush()
            with open(os.path.join(tmp, "consola.txt"), "rb") as actual:
                actual.seek(inicio_proceso_log)
                recuperado = b"RECUPERADO BD" in actual.read()
            if recuperado:
                recuperaciones_mismo_proceso += 1
                break
            time.sleep(0.5)
        else:
            p.kill()
            p.wait()
            raise RuntimeError("El lector no confirmó recuperación de PostgreSQL QA en 90 segundos")
        espera = 0.0
    time.sleep(espera)
    p.kill()  # TerminateProcess / SIGKILL: sin cierre ordenado
    p.wait()
    ultimo_fin_proceso = time.monotonic()
    cortes.append(round(espera, 2))

# Sin cortes ni fallas hasta vaciar todo. Las respuestas con reintento programado se adelantan
# (la espera progresiva llega a 60 min); no se toca nada más.
pop.modo_quit, smtp.modo = "ok", "ok"
with pop.cerrojo:
    pop.buzon.extend(por_llegar)
sql("UPDATE correos_salientes SET proximo_intento=now() WHERE estado='Pendiente'")
p = lanzar()
fin = time.time() + 90
while time.time() < fin:
    time.sleep(2)
    sql("UPDATE correos_salientes SET proximo_intento=now() WHERE estado='Pendiente'")
    pend = sql("SELECT (SELECT count(*) FROM log_comandos WHERE resultado='Recibido')"
               "+(SELECT count(*) FROM correos_salientes WHERE estado='Pendiente')")
    if not pop.buzon and pend == "0":
        time.sleep(3)
        break
p.kill()
p.wait()

# ---------------------------------------------------------------- verificación
ejecuciones = {t: int(sql("SELECT count(*) FROM interacciones WHERE descripcion='%s'" % t)) for t in textos}
regs = sql("SELECT l.nro_registro||'|'||l.resultado||'|'||coalesce(string_agg(s.estado,',' ORDER BY s.nro_correo),'')"
           " FROM log_comandos l LEFT JOIN correos_salientes s ON s.nro_registro=l.nro_registro"
           " WHERE l.remitente='%s' GROUP BY l.nro_registro,l.resultado ORDER BY 1" % admin).splitlines()
por_resultado = {}
for r in regs:
    por_resultado[r.split("|")[1]] = por_resultado.get(r.split("|")[1], 0) + 1
# Un Éxito debe tener su respuesta Enviada; un Repetido, o ninguna copia (la original seguía en
# cola) o su copia Enviada.
sin_enviar = [r for r in regs if (r.split("|")[1] == "Éxito" and "Enviado" not in r.split("|")[2])
              or (r.split("|")[1] == "Repetido" and r.split("|")[2] not in ("", "Enviado"))]
ids = {}
for m_ in smtp.recibidos:
    mid = re.search(r"(?im)^Message-ID:\s*(\S+)", m_).group(1)
    irt = re.search(r"(?im)^In-Reply-To:\s*(\S+)", m_)
    ids.setdefault(mid, []).append(origen.get(irt.group(1)) if irt else None)
respondidos = {t for v in ids.values() for t in v if t}
log.flush()
with open(os.path.join(tmp, "consola.txt"), "r", encoding="utf-8", errors="replace") as registro:
    salida_lector = registro.read()
resultado = {
    "fecha": time.strftime("%Y-%m-%d %H:%M"),
    "semilla": a.semilla,
    "cortes_forzados": len(cortes),
    "caidas_de_la_bd": caidas_bd,
    "mensajes_recibidos_durante_caida_bd": mensajes_durante_caida,
    "falla_bd_observada_por_lector": "FALLA BD" in salida_lector,
    "recuperacion_bd_observada_por_mismo_proceso": recuperaciones_mismo_proceso == caidas_bd and caidas_bd > 0,
    "recuperaciones_observadas_mismo_proceso": recuperaciones_mismo_proceso,
    "segundos_hasta_cada_corte": cortes,
    "correos_depositados": total_buzon,
    "comandos_distintos": len(textos),
    "relecturas_mismo_message_id": 3,
    "reintentos_del_usuario": 2,
    "pop3_quit": pop.eventos,
    "smtp_conexiones": smtp.eventos,
    "registros_por_resultado": por_resultado,
    "ejecuciones_por_comando": sorted(set(ejecuciones.values())),
    "comandos_perdidos": [t for t, n in ejecuciones.items() if n == 0],
    "comandos_duplicados": [t for t, n in ejecuciones.items() if n > 1],
    "respuestas_recibidas_por_smtp": len(smtp.recibidos),
    "message_id_distintos": len(ids),
    "copias_repetidas_mismo_message_id": len(smtp.recibidos) - len(ids),
    "comandos_sin_respuesta_recibida": sorted(set(textos) - respondidos),
    "registros_sin_respuesta_enviada": sin_enviar,
    "quedan_en_buzon": len(pop.buzon),
    "quedan_recibidos": int(sql("SELECT count(*) FROM log_comandos WHERE resultado='Recibido'")),
    "quedan_pendientes": int(sql("SELECT count(*) FROM correos_salientes WHERE estado='Pendiente'")),
}
ok = (not resultado["comandos_perdidos"] and not resultado["comandos_duplicados"]
      and not resultado["comandos_sin_respuesta_recibida"] and not sin_enviar
      and resultado["quedan_en_buzon"] == 0 and resultado["quedan_recibidos"] == 0
      and resultado["quedan_pendientes"] == 0)
if caidas_bd:
    ok = ok and resultado["falla_bd_observada_por_lector"] and resultado["recuperacion_bd_observada_por_mismo_proceso"]
resultado["aprobado"] = ok
resultado["log_del_lector"] = os.path.join(tmp, "consola.txt")
os.makedirs(os.path.dirname(a.salida), exist_ok=True)
with open(a.salida, "w", encoding="utf-8") as f:
    json.dump(resultado, f, ensure_ascii=False, indent=2)
print(json.dumps({k: v for k, v in resultado.items() if k != "segundos_hasta_cada_corte"}, ensure_ascii=False, indent=2))
sys.exit(0 if ok else 1)
