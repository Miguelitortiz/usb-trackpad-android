#!/bin/bash
# Configura permisos permanentes para /dev/uinput en COSMIC / Fedora / Linux
set -e

echo "[*] Configurando permisos de /dev/uinput para COSMIC Desktop..."

# 1. Permiso inmediato
if [ -w /dev/uinput ]; then
    echo "[✓] /dev/uinput ya tiene permisos de escritura."
else
    echo "[*] Aplicando permisos a /dev/uinput..."
    echo 'Miguel05' | sudo -S chmod 666 /dev/uinput 2>/dev/null || sudo chmod 666 /dev/uinput
    echo "[✓] Permisos temporales aplicados correctamente."
fi

# 2. Regla udev permanente (para que no pida contraseña al reiniciar la PC)
RULE_PATH="/etc/udev/rules.d/99-uinput.rules"
if [ ! -f "$RULE_PATH" ]; then
    echo "[*] Instalando regla udev permanente en $RULE_PATH..."
    echo 'KERNEL=="uinput", MODE="0660", GROUP="input", TAG+="uaccess"' | (echo 'Miguel05' | sudo -S tee "$RULE_PATH" > /dev/null 2>&1 || sudo tee "$RULE_PATH" > /dev/null)
    (echo 'Miguel05' | sudo -S usermod -aG input "$USER" 2>/dev/null || sudo usermod -aG input "$USER" 2>/dev/null) || true
    (echo 'Miguel05' | sudo -S udevadm control --reload-rules 2>/dev/null && echo 'Miguel05' | sudo -S udevadm trigger 2>/dev/null) || true
    echo "[✓] Regla permanente instalada."
fi

echo ""
echo "========================================================="
echo "  ✅ Dispositivo virtual de ratón activado con éxito"
echo "  Ahora puedes iniciar el receptor con:"
echo "  python tools/usb_adb_receiver.py 5057"
echo "========================================================="
