# 📱 USB Trackpad HID para Android

Aplicación nativa para Android que convierte la pantalla multitáctil de tu teléfono o tablet en un **Trackpad / Mouse USB HID** capaz de enviar movimientos de cursor, clics y gestos multitáctiles por USB a cualquier dispositivo receptor (PC con Windows, Linux, macOS, Smart TV, consolas PS4/PS5, BIOS/UEFI u otro dispositivo móvil).

## 📦 Descarga directa del APK compilado

El archivo `.apk` listo para instalar se encuentra incluido directamente en el repositorio y en la sección de **Releases**:

- **Última versión:** [`releases/USBTrackpad-v1.0.2.apk`](releases/USBTrackpad-v1.0.2.apk)
- [`releases/USBTrackpad-v1.0.1.apk`](releases/USBTrackpad-v1.0.1.apk)
- [`releases/USBTrackpad-v1.0.0.apk`](releases/USBTrackpad-v1.0.0.apk)

---

## ✨ Características principales

- **Diseño Cristal Pulido Reactivo (Apple Glassmorphism)**:
  - **Aprovechamiento 100% de Pantalla**: La superficie multitáctil cubre toda la pantalla del dispositivo de extremo a extremo sin saturación de texto innecesario.
  - **Botón Flotante de Ajustes**: Acceso a configuración mediante una elegante tarjeta modal esmerilada con animación suave.
  - **Giro Manual Independiente (0°, 90°, 180°, 270°)**: Botón de orientación en pantalla para rotar manualmente la interfaz sin depender del bloqueo o sensor de rotación del sistema.
  - **Botones Intuitivos sin Sobrecarga de Texto**: La propia disposición espacial y la iconografía definen la función de cada control.
  - **Retroalimentación Táctil Suave**: Auras reactivas que siguen los dedos, puente luminoso multitouch y micro-animaciones elásticas en cada pulsación.

- **Superficie Multitáctil de Alta Precisión**:
  - **1 dedo deslizar**: Movimiento suave del cursor con curva de aceleración dinámica y acumulación sub-píxel.
  - **Tap con 1 dedo**: Clic izquierdo instantáneo.
  - **Tap con 2 dedos**: Clic derecho.
  - **Deslizar con 2 dedos**: Rueda de scroll vertical suave (con opción de Scroll Natural / Invertido).
  - **Doble tap y deslizar**: Arrastrar elementos (*Drag & Drop*) sin soltar, o mediante el botón dedicado **ARRASTRAR: ON/OFF**.
- **Botones Físicos Dedicados en Pantalla**: Clic Izquierdo, Clic Central (Rueda) y Clic Derecho con respuesta háptica.
- **4 Modos de Salida USB / HID Integrados**:
  1. **USB HID Gadget (Universal / ConfigFS `/dev/hidg0`)**: Emulación hardware USB HID real a nivel de kernel de Linux/Android. Cualquier dispositivo al que conectes el cable USB detecta el teléfono como un ratón USB físico estándar sin instalar nada en el receptor (*requiere acceso Root / Kernel con ConfigFS HID*).
  2. **USB OTG Bridge (`UsbManager` Host)**: Envía tramas HID a través de adaptadores puente USB OTG (como chips CH9329 o puentes USB-HID hardware) sin requerir Root.
  3. **USB ADB / Tethering TCP (`127.0.0.1:5057`)**: Canal por cable USB mediante `adb reverse tcp:5057 tcp:5057` o USB Tethering RNDIS para dispositivos sin Root.
  4. **Bluetooth HID Nativo (Sin Root)**: Usa la API oficial `BluetoothHidDevice` de Android 9+ para funcionar como Trackpad HID Plug & Play inalámbrico sin instalar software en el receptor.

---

## 🛠️ Compilación desde el código fuente

```bash
./gradlew assembleRelease
```

El APK generado se ubicará en `app/build/outputs/apk/release/app-release.apk`.

## Receptor Linux

El receptor conserva el protocolo existente de 4 bytes por reporte:
`[botones, dx, dy, rueda]`, con los tres últimos valores como enteros con signo de 8 bits.
También acepta una extensión semántica de 5 bytes para gestos: `0xFF, acción, valor16, reservado`.
Las acciones 1/2 son zoom in/out y 3/4 son Back/Forward. Los reportes legacy siguen funcionando.
Por seguridad, escucha en `127.0.0.1:5057` por defecto y funciona con ADB reverse:

```bash
adb reverse tcp:5057 tcp:5057
python tools/usb_adb_receiver.py 5057
```

En Android, pellizcar con dos dedos realiza zoom; deslizar dos dedos horizontalmente realiza
Back/Forward. El zoom se emite como Ctrl+rueda y la navegación como botones virtuales del ratón.

Para una conexión de USB tethering que requiera una interfaz de red, selecciona explícitamente
la dirección de escucha, por ejemplo `--host 0.0.0.0 5057`, y limita el acceso mediante el
cortafuegos del sistema.
