package com.usbtrackpad.app;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gestiona la emulación nativa USB HID Gadget de Linux/Android (/dev/hidg0, /dev/hidg1)
 * mediante ConfigFS (/config/usb_gadget o /sys/kernel/config/usb_gadget).
 * Permite que el teléfono Android sea detectado por USB como un Mouse/Trackpad físico Plug & Play
 * en cualquier dispositivo (PC, Mac, Linux, Smart TV, PS4/PS5, BIOS, otro móvil) sin instalar nada en el receptor.
 */
public class UsbGadgetHidManager {

    private static final String[] CANDIDATE_DEV_PATHS = new String[]{
            "/dev/hidg0",
            "/dev/hidg1",
            "/dev/hidg_mouse"
    };

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private volatile FileOutputStream hidFileStream;
    private volatile DataOutputStream rootShellStream;
    private volatile Process rootProcess;
    private volatile String activeDevicePath = null;
    private volatile String lastStatusMessage = "No inicializado";

    public interface StatusCallback {
        void onStatusChanged(boolean ready, String message);
    }

    public boolean isReady() {
        return activeDevicePath != null && (hidFileStream != null || rootShellStream != null);
    }

    public String getActiveDevicePath() {
        return activeDevicePath;
    }

    public String getLastStatusMessage() {
        return lastStatusMessage;
    }

    /**
     * Intenta abrir /dev/hidg0 directamente o configurarlo vía USB ConfigFS (Root/Kernel HID).
     */
    public void initializeAsync(StatusCallback callback) {
        ioExecutor.execute(() -> {
            closeInternal();

            // 1. Intentar acceso directo si /dev/hidg* ya existe y tiene permisos de escritura
            for (String path : CANDIDATE_DEV_PATHS) {
                File f = new File(path);
                if (f.exists() && f.canWrite()) {
                    try {
                        hidFileStream = new FileOutputStream(f);
                        activeDevicePath = path;
                        lastStatusMessage = "USB HID Activo (" + path + ")";
                        callback.onStatusChanged(true, lastStatusMessage);
                        return;
                    } catch (IOException ignored) {
                    }
                }
            }

            // 2. Intentar configurar ConfigFS USB Gadget y permisos vía shell root (su)
            try {
                Process suCheck = new ProcessBuilder("su", "-c", "id").start();
                int code = suCheck.waitFor();
                if (code == 0) {
                    configureUsbConfigFsGadget();
                    for (String path : CANDIDATE_DEV_PATHS) {
                        File f = new File(path);
                        if (f.exists()) {
                            runRootCommand("chmod 666 " + path);
                            if (f.canWrite()) {
                                hidFileStream = new FileOutputStream(f);
                                activeDevicePath = path;
                                lastStatusMessage = "USB HID Gadget Activo (" + path + ")";
                                callback.onStatusChanged(true, lastStatusMessage);
                                return;
                            }
                        }
                    }

                    // Si el nodo existe pero SELinux bloquea FileOutputStream directo desde la app,
                    // abrimos un pipeline persistente por root shell hacia /dev/hidg0
                    if (devNodeExistsViaRoot("/dev/hidg0")) {
                        rootProcess = new ProcessBuilder("su").start();
                        rootShellStream = new DataOutputStream(rootProcess.getOutputStream());
                        activeDevicePath = "/dev/hidg0 (Root Pipe)";
                        lastStatusMessage = "USB HID Activo (/dev/hidg0 vía Root)";
                        callback.onStatusChanged(true, lastStatusMessage);
                        return;
                    }

                    lastStatusMessage = "Root detectado, pero el kernel no expuso /dev/hidg0. Usa modo USB Host/ADB o Bluetooth HID.";
                    callback.onStatusChanged(false, lastStatusMessage);
                } else {
                    lastStatusMessage = "Sin acceso Root para /dev/hidg0. Cambia a USB OTG / USB ADB o Bluetooth HID.";
                    callback.onStatusChanged(false, lastStatusMessage);
                }
            } catch (Exception e) {
                lastStatusMessage = "Modo Gadget requiere Root/Kernel HID (/dev/hidg0). Usa USB OTG, USB ADB o Bluetooth HID.";
                callback.onStatusChanged(false, lastStatusMessage);
            }
        });
    }

    private boolean devNodeExistsViaRoot(String path) {
        try {
            Process p = new ProcessBuilder("su", "-c", "test -e " + path).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Configura automáticamente una función hid.usb0 en el gadget USB de Android ConfigFS
     * si aún no existe /dev/hidg0.
     */
    private void configureUsbConfigFsGadget() {
        StringBuilder hexDesc = new StringBuilder();
        for (byte b : HidMouseReport.MOUSE_REPORT_DESCRIPTOR) {
            hexDesc.append(String.format("\\\\x%02x", b & 0xFF));
        }

        String script = "set +e\n" +
                "if [ -e /dev/hidg0 ]; then chmod 666 /dev/hidg0; exit 0; fi\n" +
                "GADGET_BASE=\"/config/usb_gadget/g1\"\n" +
                "if [ ! -d \"$GADGET_BASE\" ]; then GADGET_BASE=\"/sys/kernel/config/usb_gadget/g1\"; fi\n" +
                "if [ ! -d \"$GADGET_BASE\" ]; then exit 1; fi\n" +
                "FUNC_DIR=\"$GADGET_BASE/functions/hid.usb0\"\n" +
                "CFG_DIR=$(ls -d \"$GADGET_BASE/configs/\"* 2>/dev/null | head -n 1)\n" +
                "UDC_VAL=$(cat \"$GADGET_BASE/UDC\" 2>/dev/null)\n" +
                "if [ -z \"$UDC_VAL\" ]; then UDC_VAL=$(ls /sys/class/udc | head -n 1); fi\n" +
                "mkdir -p \"$FUNC_DIR\"\n" +
                "echo 2 > \"$FUNC_DIR/protocol\"\n" +
                "echo 1 > \"$FUNC_DIR/subclass\"\n" +
                "echo 4 > \"$FUNC_DIR/report_length\"\n" +
                "printf '" + hexDesc + "' > \"$FUNC_DIR/report_desc\"\n" +
                "if [ -n \"$CFG_DIR\" ] && [ ! -e \"$CFG_DIR/hid.usb0\" ]; then\n" +
                "  echo \"\" > \"$GADGET_BASE/UDC\"\n" +
                "  ln -s \"$FUNC_DIR\" \"$CFG_DIR/hid.usb0\"\n" +
                "  echo \"$UDC_VAL\" > \"$GADGET_BASE/UDC\"\n" +
                "fi\n" +
                "sleep 0.3\n" +
                "chmod 666 /dev/hidg0 /dev/hidg1 2>/dev/null\n";

        runRootCommand(script);
    }

    private void runRootCommand(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).start();
            p.waitFor();
        } catch (Exception ignored) {
        }
    }

    /**
     * Envía un reporte HID de 4 bytes [buttons, dx, dy, wheel] por USB.
     */
    public void sendReport(final byte[] report4Bytes) {
        if (report4Bytes == null || report4Bytes.length != 4) return;
        ioExecutor.execute(() -> {
            try {
                if (hidFileStream != null) {
                    hidFileStream.write(report4Bytes);
                    hidFileStream.flush();
                } else if (rootShellStream != null) {
                    String cmd = String.format(
                            "printf '\\x%02x\\x%02x\\x%02x\\x%02x' > /dev/hidg0\n",
                            report4Bytes[0] & 0xFF,
                            report4Bytes[1] & 0xFF,
                            report4Bytes[2] & 0xFF,
                            report4Bytes[3] & 0xFF
                    );
                    rootShellStream.writeBytes(cmd);
                    rootShellStream.flush();
                }
            } catch (IOException e) {
                activeDevicePath = null;
            }
        });
    }

    public String buildDiagnosticsReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Diagnóstico USB HID ===\n");
        for (String path : CANDIDATE_DEV_PATHS) {
            File f = new File(path);
            sb.append(path).append(": existe=").append(f.exists())
                    .append(", escribible=").append(f.canWrite()).append("\n");
        }
        File config1 = new File("/config/usb_gadget/g1");
        File config2 = new File("/sys/kernel/config/usb_gadget/g1");
        sb.append("ConfigFS (/config/usb_gadget/g1): ").append(config1.exists()).append("\n");
        sb.append("ConfigFS (/sys/kernel/config/usb_gadget/g1): ").append(config2.exists()).append("\n");
        File udc = new File("/sys/class/udc");
        if (udc.exists() && udc.list() != null) {
            sb.append("Controladores UDC: ").append(String.join(", ", udc.list())).append("\n");
        }
        sb.append("Estado actual: ").append(lastStatusMessage).append("\n");
        return sb.toString();
    }

    private void closeInternal() {
        activeDevicePath = null;
        if (hidFileStream != null) {
            try {
                hidFileStream.close();
            } catch (IOException ignored) {
            }
            hidFileStream = null;
        }
        if (rootShellStream != null) {
            try {
                rootShellStream.writeBytes("exit\n");
                rootShellStream.flush();
                rootShellStream.close();
            } catch (IOException ignored) {
            }
            rootShellStream = null;
        }
        if (rootProcess != null) {
            rootProcess.destroy();
            rootProcess = null;
        }
    }

    public void close() {
        ioExecutor.execute(this::closeInternal);
    }
}
