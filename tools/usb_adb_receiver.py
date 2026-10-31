#!/usr/bin/env python3
"""
Receptor opcional por cable USB (ADB / TCP puerto 5050) para teléfonos Android SIN Root.
Si tu Android tiene Root / Kernel ConfigFS HID (/dev/hidg0), NO necesitas este script
(el modo USB HID Gadget funciona Plug & Play sin instalar nada).

Uso con cable USB sin Root:
1. Conecta el teléfono por USB con Depuración USB activa.
2. Ejecuta: adb reverse tcp:5050 tcp:5050
3. Ejecuta este script: python3 tools/usb_adb_receiver.py
"""

import socket
import struct
import sys

HOST = "0.0.0.0"
PORT = 5050


def main():
    print(f"[*] Escuchando eventos HID de USB Trackpad en {HOST}:{PORT}...")
    print("[*] Para usar por cable USB ejecuta: adb reverse tcp:5050 tcp:5050")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((HOST, PORT))
        srv.listen(1)
        while True:
            conn, addr = srv.accept()
            print(f"[+] Dispositivo Android conectado desde {addr}")
            with conn:
                while True:
                    data = conn.recv(4)
                    if not data or len(data) < 4:
                        print("[-] Dispositivo desconectado")
                        break
                    buttons, dx, dy, wheel = struct.unpack("Bbbb", data)
                    print(f"HID Report -> Buttons: 0x{buttons:02X}, dX: {dx:+4d}, dY: {dy:+4d}, Wheel: {wheel:+3d}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
