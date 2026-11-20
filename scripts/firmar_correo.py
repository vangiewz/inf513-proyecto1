"""Firma local HMAC v1 de un .eml. No abre conexiones ni envía correo.

Uso:
  python scripts/firmar_correo.py --crear-clave correo@example.test claves.properties
  python scripts/firmar_correo.py entrada.eml claves.properties salida.eml

Las claves son privadas: distribuir por un canal seguro y no incluir en Git ni ZIP.
"""
import base64
import email.header
import email.parser
import email.policy
import email.utils
import hashlib
import hmac
import pathlib
import re
import secrets
import sys
import time


def firmar(crudo, clave, instante=None):
    """Devuelve bytes firmados; clave es bytes aleatorios de al menos 32 bytes."""
    if len(clave) < 32:
        raise ValueError("Se requieren claves de al menos 32 bytes")
    if not crudo.endswith(b"\n"):
        crudo += b"\r\n"
    mensaje = email.parser.BytesParser(policy=email.policy.compat32).parsebytes(crudo)
    if mensaje.get_all("X-CRM-Signature") or mensaje.get_all("X-CRM-Timestamp"):
        raise ValueError("El mensaje ya está firmado")
    timestamp = str(int(time.time() if instante is None else instante))
    partes = ["CRM-HMAC-SHA256-v1", timestamp]
    for nombre in ("From", "To", "Subject", "Message-ID", "MIME-Version", "Content-Type", "Content-Transfer-Encoding", "Content-Disposition", "Content-ID", "Auto-Submitted", "Precedence"):
        valores = mensaje.get_all(nombre, [])
        if len(valores) > 1:
            raise ValueError("Cabecera repetida")
        valor = str(valores[0]) if valores else ""
        if nombre == "Subject":
            valor = str(email.header.make_header(email.header.decode_header(valor)))
        valor = re.sub(r"\r?\n[ \t]+", " ", valor).strip()
        partes.append(nombre.lower() + ":" + valor)
    if len(email.utils.getaddresses(mensaje.get_all("From", []))) != 1 or not mensaje.get("Message-ID"):
        raise ValueError("Se requieren From único y Message-ID")
    segmentos = re.split(br"\r?\n\r?\n", crudo, maxsplit=1)
    cuerpo = segmentos[1] if len(segmentos) == 2 else b""
    cuerpo = cuerpo.replace(b"\r\n", b"\n").replace(b"\r", b"\n").replace(b"\n", b"\r\n")
    partes.append(hashlib.sha256(cuerpo).hexdigest())
    firma = hmac.new(clave, "\n".join(partes).encode("utf-8"), hashlib.sha256).hexdigest()
    return ("X-CRM-Timestamp: " + timestamp + "\r\nX-CRM-Signature: " + firma + "\r\n").encode("ascii") + crudo


def main(args):
    if len(args) == 3 and args[0] == "--crear-clave":
        correo, salida = args[1:]
        if not re.fullmatch(r"[^\s@=:]+@[^\s@=:]+", correo):
            raise ValueError("Correo inválido")
        with pathlib.Path(salida).open("x", encoding="utf-8") as archivo:
            archivo.write(correo.lower() + "=" + base64.b64encode(secrets.token_bytes(32)).decode("ascii") + "\n")
        try:
            pathlib.Path(salida).chmod(0o600)
        except OSError:
            pass  # Windows: aplicar ACL privada según las instrucciones de despliegue.
        print("Archivo privado creado; no se muestra ni transmite la clave.")
        return
    if len(args) != 3:
        raise ValueError("Uso: entrada.eml claves.properties salida.eml; o --crear-clave correo archivo")
    entrada, claves, salida = map(pathlib.Path, args)
    crudo = entrada.read_bytes()
    mensaje = email.parser.BytesParser(policy=email.policy.compat32).parsebytes(crudo)
    remitente = email.utils.parseaddr(mensaje.get("From", ""))[1].lower()
    valores = {}
    for linea in claves.read_text(encoding="utf-8").splitlines():
        if linea.strip() and not linea.lstrip().startswith("#"):
            k, v = linea.split("=", 1)
            valores[k.strip().lower()] = base64.b64decode(v.strip(), validate=True)
    if remitente not in valores:
        raise ValueError("No hay clave para el remitente")
    with salida.open("xb") as archivo:
        archivo.write(firmar(crudo, valores[remitente]))
    print("Archivo firmado; no se envió ningún correo.")


if __name__ == "__main__":
    main(sys.argv[1:])
