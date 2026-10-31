#!/usr/bin/env python3
"""
Receptor por cable USB (ADB / TCP) para teléfonos Android.
Permite recibir eventos de movimiento de cursor, clics y scroll desde la app Android.

Uso:
    python tools/usb_adb_receiver.py [PUERTO]
    python tools/usb_adb_receiver.py -p 5057
"""

import argparse
import os
import shutil
import socket
import struct
import subprocess
import sys

DEFAULT_PORT = 5057
HOST = "0.0.0.0"

# Intentar soporte de control del ratón nativo si pynput o pyautogui están instalados
mouse_controller = None
try:
    from pynput.mouse import Button, Controller
    mouse_controller = Controller()
except ImportError:
    pass

btn_left_pressed = False
btn_right_pressed = False
btn_middle_pressed = False


def update_system_mouse(buttons, dx, dy, wheel):
    global mouse_controller, btn_left_pressed, btn_right_pressed, btn_middle_pressed
    if not mouse_controller:
        return

    # Movimiento relativo del cursor
    if dx != 0 or dy != 0:
        mouse_controller.move(dx, dy)

    # Rueda de scroll vertical
    if wheel != 0:
        mouse_controller.scroll(0, wheel)

    # Botón izquierdo
    left = bool(buttons & 0x01)
    if left != btn_left_pressed:
        if left:
            mouse_controller.press(Button.left)
        else:
            mouse_controller.release(Button.left)
        btn_left_pressed = left

    # Botón derecho
    right = bool(buttons & 0x02)
    if right != btn_right_pressed:
        if right:
            mouse_controller.press(Button.right)
        else:
            mouse_controller.release(Button.right)
        btn_right_pressed = right

    # Botón central
    middle = bool(buttons & 0x04)
    if middle != btn_middle_pressed:
        if middle:
            mouse_controller.press(Button.middle)
        else:
            mouse_controller.release(Button.middle)
        btn_middle_pressed = middle


def get_selected_port():
    parser = argparse.ArgumentParser(description="Receptor USB ADB para Trackpad HID")
    parser.add_argument("port", nargs="?", type=int, help=f"Puerto TCP a escuchar (por defecto: {DEFAULT_PORT})")
    parser.add_argument("-p", "--port-opt", type=int, dest="p_opt", help="Puerto alternativo")
    args, _ = parser.parse_known_args()

    port = args.p_opt or args.port
    if port is not None:
        return port

    # Si se ejecuta interactivamente en terminal y no se especificó argumento, pedirlo al usuario
    if sys.stdin.isatty():
        try:
            user_input = input(f"Ingresa el puerto a escuchar [Enter para {DEFAULT_PORT}]: ").strip()
            if user_input:
                return int(user_input)
        except (ValueError, KeyboardInterrupt, EOFError):
            print()
    return DEFAULT_PORT


def try_adb_reverse(port):
    adb_path = shutil.which("adb") or "/var/home/Moi/Android/Sdk/platform-tools/adb"
    if os.path.exists(adb_path) or shutil.which("adb"):
        try:
            res = subprocess.run(
                [adb_path, "reverse", f"tcp:{port}", f"tcp:{port}"],
                capture_output=True,
                text=True,
                timeout=3
            )
            if res.returncode == 0:
                print(f"[✓] Regla adb reverse tcp:{port} tcp:{port} aplicada con éxito.")
            else:
                if "device unauthorized" in res.stderr:
                    print("[!] Teléfono conectado pero pendiente de autorización en pantalla.")
                elif "no devices" in res.stderr:
                    print("[i] Ningún teléfono detectado por USB actualmente.")
        except Exception:
            pass


def main():
    port = get_selected_port()

    print("=" * 60)
    print(f"  🖱️  Receptor USB Trackpad HID (TCP Puerto {port})")
    print("=" * 60)

    try_adb_reverse(port)

    print(f"\n[*] Escuchando conexiones entrantes en {HOST}:{port}...")
    print(f"[*] En la app de Android escribe el host: 127.0.0.1:{port}")
    if mouse_controller:
        print("[*] Control nativo del cursor activo (pynput detectado).")
    else:
        print("[i] Modo de telemetría activo. (Para controlar el puntero de la PC: pip install pynput)")
    print("[*] Presiona Ctrl+C para salir.\n")

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            srv.bind((HOST, port))
        except OSError as e:
            print(f"[!] Error al enlazar el puerto {port}: {e}")
            sys.exit(1)

        srv.listen(1)

        while True:
            try:
                conn, addr = srv.accept()
            except KeyboardInterrupt:
                break

            print(f"[+] Dispositivo Android conectado desde {addr}")
            with conn:
                while True:
                    data = conn.recv(4)
                    if not data or len(data) < 4:
                        print("[-] Dispositivo desconectado. Esperando nueva conexión...")
                        break

                    buttons, dx, dy, wheel = struct.unpack("Bbbb", data)
                    update_system_mouse(buttons, dx, dy, wheel)

                    btn_str = []
                    if buttons & 0x01: btn_str.append("IZQ")
                    if buttons & 0x02: btn_str.append("DER")
                    if buttons & 0x04: btn_str.append("MED")
                    btn_desc = "+".join(btn_str) if btn_str else "---"

                    sys.stdout.write(f"\rHID -> Botones: [{btn_desc:^7}] | dX: {dx:+4d} | dY: {dy:+4d} | Scroll: {wheel:+3d}  ")
                    sys.stdout.flush()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[*] Saliendo...")
        sys.exit(0)
