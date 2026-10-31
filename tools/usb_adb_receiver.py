#!/usr/bin/env python3
"""
Receptor por cable USB (ADB / TCP) para teléfonos Android.
Emula movimiento de cursor, clics y scroll en Linux (Wayland y X11) y Windows.

Uso:
    python tools/usb_adb_receiver.py [PUERTO]
    python tools/usb_adb_receiver.py 5057
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

# 1. Intentar backend uinput nativo de Linux (el único que funciona 100% en Wayland / KDE / GNOME)
uinput_device = None
ecodes = None
try:
    import evdev
    from evdev import UInput, ecodes as _ec
    ecodes = _ec
    cap = {
        ecodes.EV_REL: [ecodes.REL_X, ecodes.REL_Y, ecodes.REL_WHEEL],
        ecodes.EV_KEY: [ecodes.BTN_LEFT, ecodes.BTN_RIGHT, ecodes.BTN_MIDDLE]
    }
    uinput_device = UInput(cap, name="USB Trackpad HID Virtual Mouse")
except Exception as e:
    uinput_device = None

# 2. Fallback a pynput para entornos X11 / Windows si uinput no está disponible
pynput_mouse = None
Button = None
if not uinput_device:
    try:
        from pynput.mouse import Button as _Btn, Controller
        pynput_mouse = Controller()
        Button = _Btn
    except Exception:
        pynput_mouse = None

btn_left_state = False
btn_right_state = False
btn_middle_state = False


def update_system_mouse(buttons, dx, dy, wheel):
    global uinput_device, pynput_mouse, ecodes, Button
    global btn_left_state, btn_right_state, btn_middle_state

    # Método A: Kernel uinput (Wayland y Linux en general)
    if uinput_device:
        if dx != 0:
            uinput_device.write(ecodes.EV_REL, ecodes.REL_X, dx)
        if dy != 0:
            uinput_device.write(ecodes.EV_REL, ecodes.REL_Y, dy)
        if wheel != 0:
            uinput_device.write(ecodes.EV_REL, ecodes.REL_WHEEL, wheel)

        left = bool(buttons & 0x01)
        right = bool(buttons & 0x02)
        middle = bool(buttons & 0x04)

        if left != btn_left_state:
            uinput_device.write(ecodes.EV_KEY, ecodes.BTN_LEFT, 1 if left else 0)
            btn_left_state = left
        if right != btn_right_state:
            uinput_device.write(ecodes.EV_KEY, ecodes.BTN_RIGHT, 1 if right else 0)
            btn_right_state = right
        if middle != btn_middle_state:
            uinput_device.write(ecodes.EV_KEY, ecodes.BTN_MIDDLE, 1 if middle else 0)
            btn_middle_state = middle

        uinput_device.syn()
        return

    # Método B: pynput (X11 / Windows)
    if pynput_mouse:
        if dx != 0 or dy != 0:
            pynput_mouse.move(dx, dy)
        if wheel != 0:
            pynput_mouse.scroll(0, wheel)

        left = bool(buttons & 0x01)
        if left != btn_left_state:
            if left: pynput_mouse.press(Button.left)
            else: pynput_mouse.release(Button.left)
            btn_left_state = left

        right = bool(buttons & 0x02)
        if right != btn_right_state:
            if right: pynput_mouse.press(Button.right)
            else: pynput_mouse.release(Button.right)
            btn_right_state = right

        middle = bool(buttons & 0x04)
        if middle != btn_middle_state:
            if middle: pynput_mouse.press(Button.middle)
            else: pynput_mouse.release(Button.middle)
            btn_middle_state = middle


def get_selected_port():
    parser = argparse.ArgumentParser(description="Receptor USB ADB para Trackpad HID")
    parser.add_argument("port", nargs="?", type=int, help=f"Puerto TCP a escuchar (por defecto: {DEFAULT_PORT})")
    parser.add_argument("-p", "--port-opt", type=int, dest="p_opt", help="Puerto alternativo")
    args, _ = parser.parse_known_args()

    port = args.p_opt or args.port
    if port is not None:
        return port

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
        except Exception:
            pass


def main():
    global uinput_device, pynput_mouse
    port = get_selected_port()

    print("=" * 65)
    print(f"  🖱️  Receptor USB Trackpad HID (TCP Puerto {port})")
    print("=" * 65)

    try_adb_reverse(port)

    # Diagnóstico del backend de control del ratón
    if uinput_device:
        print("[✓] Control de ratón activo: Dispositivo virtual uinput de Linux (Compatible con Wayland y X11).")
    elif pynput_mouse:
        print("[✓] Control de ratón activo: Controlador pynput (X11).")
    else:
        print("\n[!] AVISO: El cursor no se moverá en Wayland hasta dar permisos a /dev/uinput.")
        print("    👉 Para habilitar el movimiento del cursor, ejecuta en otra terminal:")
        print("       sudo chmod 666 /dev/uinput")
        print("    (Luego reinicia este script para activar el cursor virtual)\n")

    print(f"[*] Escuchando conexiones entrantes en {HOST}:{port}...")
    print(f"[*] En la app de Android verifica que el Host sea: 127.0.0.1:{port}")
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

            print(f"\n[+] Dispositivo Android conectado desde {addr}")
            with conn:
                while True:
                    data = conn.recv(4)
                    if not data or len(data) < 4:
                        print("\n[-] Dispositivo desconectado. Esperando nueva conexión...")
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
