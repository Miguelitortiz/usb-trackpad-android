package com.usbtrackpad.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;

import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Alternativa Plug & Play oficial de Android (API 28+) que emula un Trackpad/Mouse HID estándar
 * sin necesitar Root ni instalar software en el dispositivo receptor.
 */
@SuppressLint("MissingPermission")
public class BluetoothHidManager {

    private final Context context;
    private BluetoothAdapter btAdapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice connectedHost;

    public interface StatusCallback {
        void onStatusChanged(boolean connected, String status);
    }

    private StatusCallback currentCallback;

    public BluetoothHidManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            this.btAdapter = bm.getAdapter();
        }
    }

    public void initialize(StatusCallback callback) {
        this.currentCallback = callback;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            callback.onStatusChanged(false, "Bluetooth HID requiere Android 9 (API 28) o superior");
            return;
        }
        if (btAdapter == null || !btAdapter.isEnabled()) {
            callback.onStatusChanged(false, "Activa Bluetooth para usar el modo HID sin Root");
            return;
        }

        btAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = (BluetoothHidDevice) proxy;
                    registerHidApp();
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null;
                    connectedHost = null;
                    if (currentCallback != null) {
                        currentCallback.onStatusChanged(false, "Servicio Bluetooth HID desconectado");
                    }
                }
            }
        }, BluetoothProfile.HID_DEVICE);
    }

    private void registerHidApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || hidDevice == null) return;

        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "USB & BT Trackpad HID",
                "Android Universal HID Trackpad",
                "Android",
                BluetoothHidDevice.SUBCLASS1_MOUSE,
                HidMouseReport.MOUSE_REPORT_DESCRIPTOR
        );

        hidDevice.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), new BluetoothHidDevice.Callback() {
            @Override
            public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                if (registered) {
                    if (pluggedDevice != null) {
                        hidDevice.connect(pluggedDevice);
                    } else {
                        Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
                        if (bonded != null && !bonded.isEmpty()) {
                            BluetoothDevice first = bonded.iterator().next();
                            hidDevice.connect(first);
                            if (currentCallback != null) {
                                currentCallback.onStatusChanged(false,
                                        "HID registrado. Conectando a " + first.getName() + "...");
                            }
                            return;
                        }
                    }
                    if (currentCallback != null) {
                        currentCallback.onStatusChanged(false,
                                "Bluetooth HID listo. Vincúlalo desde el dispositivo receptor.");
                    }
                } else if (currentCallback != null) {
                    currentCallback.onStatusChanged(false, "No se pudo registrar Bluetooth HID");
                }
            }

            @Override
            public void onConnectionStateChanged(BluetoothDevice device, int state) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedHost = device;
                    if (currentCallback != null) {
                        currentCallback.onStatusChanged(true, "Bluetooth HID conectado a " + device.getName());
                    }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (connectedHost != null && connectedHost.equals(device)) {
                        connectedHost = null;
                    }
                    if (currentCallback != null) {
                        currentCallback.onStatusChanged(false, "Bluetooth HID desconectado");
                    }
                }
            }
        });
    }

    public boolean isConnected() {
        return hidDevice != null && connectedHost != null;
    }

    public void sendReport(byte[] report4Bytes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hidDevice != null && connectedHost != null) {
            hidDevice.sendReport(connectedHost, 0, report4Bytes);
        }
    }

    public void close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hidDevice != null) {
            try {
                hidDevice.unregisterApp();
                btAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
            } catch (Exception ignored) {
            }
            hidDevice = null;
            connectedHost = null;
        }
    }
}
