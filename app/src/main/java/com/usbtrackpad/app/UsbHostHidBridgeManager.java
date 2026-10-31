package com.usbtrackpad.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gestiona la salida USB sin Root mediante:
 * 1) Android USB Host API (UsbManager) para adaptadores puente OTG-HID (CH9329 / Arduino / Pico HID Bridge).
 * 2) Túnel USB ADB / USB Tethering por socket TCP de baja latencia con auto-reconexión persistente.
 */
public class UsbHostHidBridgeManager {

    public static final String ACTION_USB_PERMISSION = "com.usbtrackpad.app.USB_PERMISSION";

    private final Context context;
    private final UsbManager usbManager;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService adbWorker = Executors.newSingleThreadExecutor();

    private UsbDeviceConnection usbConnection;
    private UsbInterface usbInterface;
    private UsbEndpoint usbOutEndpoint;
    private UsbDevice activeDevice;

    private Socket tcpSocket;
    private OutputStream tcpOutputStream;
    private volatile boolean tcpConnected = false;
    private final AtomicBoolean adbRunning = new AtomicBoolean(false);
    private volatile String currentTargetHost = "127.0.0.1";
    private volatile int currentTargetPort = 5057;

    public interface StatusCallback {
        void onStatusChanged(boolean connected, String status);
    }

    private StatusCallback currentCallback;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    openUsbDevice(device);
                } else if (currentCallback != null) {
                    currentCallback.onStatusChanged(false, "Permiso USB denegado por el usuario");
                }
            }
        }
    };

    public UsbHostHidBridgeManager(Context context) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            this.context.registerReceiver(usbReceiver, filter);
        }
    }

    public void connectUsbHostBridge(StatusCallback callback) {
        this.currentCallback = callback;
        if (usbManager == null) {
            callback.onStatusChanged(false, "UsbManager no disponible en este dispositivo");
            return;
        }
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList == null || deviceList.isEmpty()) {
            callback.onStatusChanged(false, "Ningún dispositivo USB OTG detectado. Conecta el cable/adaptador.");
            return;
        }
        UsbDevice firstDevice = deviceList.values().iterator().next();
        if (usbManager.hasPermission(firstDevice)) {
            openUsbDevice(firstDevice);
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(firstDevice, pi);
            callback.onStatusChanged(false, "Solicitando permiso USB para " + firstDevice.getDeviceName() + "...");
        }
    }

    private void openUsbDevice(UsbDevice device) {
        ioExecutor.execute(() -> {
            closeUsbHost();
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                for (int j = 0; j < intf.getEndpointCount(); j++) {
                    UsbEndpoint ep = intf.getEndpoint(j);
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        UsbDeviceConnection conn = usbManager.openDevice(device);
                        if (conn != null && conn.claimInterface(intf, true)) {
                            this.usbConnection = conn;
                            this.usbInterface = intf;
                            this.usbOutEndpoint = ep;
                            this.activeDevice = device;
                            // Configurar baudrate 9600 para puentes seriales si aplica
                            conn.controlTransfer(0x40, 0x9A, 0x1312, 0xB282, null, 0, 200);
                            if (currentCallback != null) {
                                currentCallback.onStatusChanged(true,
                                        "USB Host Activo: VID=" + String.format("0x%04X", device.getVendorId())
                                                + " PID=" + String.format("0x%04X", device.getProductId()));
                            }
                            return;
                        }
                    }
                }
            }
            if (currentCallback != null) {
                currentCallback.onStatusChanged(false, "Dispositivo USB sin endpoint de salida disponible");
            }
        });
    }

    public void sendUsbHostReport(int buttonsMask, int dx, int dy, int wheel) {
        if (usbConnection == null || usbOutEndpoint == null) return;
        final byte[] rawHid = HidMouseReport.build4ByteReport(buttonsMask, dx, dy, wheel);
        final byte[] ch9329Frame = HidMouseReport.buildCh9329Frame(buttonsMask, dx, dy, wheel);
        ioExecutor.execute(() -> {
            if (usbConnection != null && usbOutEndpoint != null) {
                if (usbInterface != null && usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                    usbConnection.bulkTransfer(usbOutEndpoint, rawHid, rawHid.length, 50);
                } else {
                    usbConnection.bulkTransfer(usbOutEndpoint, ch9329Frame, ch9329Frame.length, 50);
                }
            }
        });
    }

    /**
     * Inicia un ciclo persistente en segundo plano que intenta conectar y auto-reconectar a USB ADB / TCP.
     */
    public void startUsbAdbLoop(String hostPort, StatusCallback callback) {
        this.currentCallback = callback;
        parseHostPort(hostPort);

        adbRunning.set(true);
        adbWorker.execute(() -> {
            boolean wasConnected = false;
            while (adbRunning.get()) {
                if (!tcpConnected) {
                    if (currentCallback != null && !wasConnected) {
                        currentCallback.onStatusChanged(false,
                                "Buscando receptor en " + currentTargetHost + ":" + currentTargetPort + "...");
                    }
                    try {
                        closeTcpSocket();
                        Socket s = new Socket();
                        s.setTcpNoDelay(true);
                        s.connect(new InetSocketAddress(currentTargetHost, currentTargetPort), 1500);
                        this.tcpSocket = s;
                        this.tcpOutputStream = s.getOutputStream();
                        this.tcpConnected = true;
                        wasConnected = true;
                        if (currentCallback != null) {
                            currentCallback.onStatusChanged(true,
                                    "Conectado por USB ADB a " + currentTargetHost + ":" + currentTargetPort);
                        }
                    } catch (Exception e) {
                        this.tcpConnected = false;
                        if (wasConnected && currentCallback != null) {
                            currentCallback.onStatusChanged(false,
                                    "Conexión perdida. Reintentando en " + currentTargetHost + ":" + currentTargetPort + "...");
                            wasConnected = false;
                        }
                    }
                }

                try {
                    Thread.sleep(tcpConnected ? 1000 : 1500);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        });
    }

    public void stopUsbAdbLoop() {
        adbRunning.set(false);
        closeTcpSocket();
    }

    private void parseHostPort(String hostPort) {
        String host = "127.0.0.1";
        int port = 5057;
        if (hostPort != null && !hostPort.trim().isEmpty()) {
            String clean = hostPort.trim();
            if (clean.contains(":")) {
                String[] parts = clean.split(":");
                host = parts[0].trim();
                if (parts.length > 1) {
                    try {
                        port = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else {
                try {
                    port = Integer.parseInt(clean);
                } catch (NumberFormatException e) {
                    host = clean;
                }
            }
        }
        this.currentTargetHost = host;
        this.currentTargetPort = port;
    }

    public void sendTcpReport(final byte[] report4Bytes) {
        if (!tcpConnected || tcpOutputStream == null) return;
        ioExecutor.execute(() -> {
            try {
                if (tcpOutputStream != null) {
                    tcpOutputStream.write(report4Bytes);
                    tcpOutputStream.flush();
                }
            } catch (Exception e) {
                tcpConnected = false;
            }
        });
    }

    public boolean isUsbHostConnected() {
        return usbConnection != null && usbOutEndpoint != null;
    }

    public boolean isTcpConnected() {
        return tcpConnected;
    }

    public int getDetectedUsbDeviceCount() {
        if (usbManager == null || usbManager.getDeviceList() == null) return 0;
        return usbManager.getDeviceList().size();
    }

    public void closeUsbHost() {
        if (usbConnection != null) {
            if (usbInterface != null) {
                usbConnection.releaseInterface(usbInterface);
            }
            usbConnection.close();
            usbConnection = null;
        }
        usbInterface = null;
        usbOutEndpoint = null;
        activeDevice = null;
    }

    public void closeTcpSocket() {
        tcpConnected = false;
        if (tcpOutputStream != null) {
            try {
                tcpOutputStream.close();
            } catch (Exception ignored) {
            }
            tcpOutputStream = null;
        }
        if (tcpSocket != null) {
            try {
                tcpSocket.close();
            } catch (Exception ignored) {
            }
            tcpSocket = null;
        }
    }

    public void closeAll() {
        stopUsbAdbLoop();
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        ioExecutor.execute(() -> {
            closeUsbHost();
            closeTcpSocket();
        });
    }
}
