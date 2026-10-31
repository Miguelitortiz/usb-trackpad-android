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

import java.util.Collections;
import java.util.List;
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
    private boolean appRegistered = false;

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

    public boolean isBluetoothEnabled() {
        return btAdapter != null && btAdapter.isEnabled();
    }

    public boolean isAppRegistered() {
        return appRegistered && hidDevice != null;
    }

    public String getConnectedDeviceName() {
        if (connectedHost != null) {
            String name = connectedHost.getName();
            return name != null ? name : connectedHost.getAddress();
        }
        if (hidDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            List<BluetoothDevice> list = hidDevice.getConnectedDevices();
            if (list != null && !list.isEmpty()) {
                BluetoothDevice dev = list.get(0);
                String name = dev.getName();
                return name != null ? name : dev.getAddress();
            }
        }
        return "Ninguno";
    }

    public Set<BluetoothDevice> getBondedDevices() {
        return btAdapter != null ? btAdapter.getBondedDevices() : Collections.emptySet();
    }

    public void initialize(StatusCallback callback) {
        this.currentCallback = callback;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            callback.onStatusChanged(false, "Bluetooth HID requiere Android 9 (API 28) o superior");
            return;
        }
        if (btAdapter == null || !btAdapter.isEnabled()) {
            callback.onStatusChanged(false, "Activa Bluetooth en tu teléfono");
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
                    appRegistered = false;
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
                appRegistered = registered;
                if (registered) {
                    // Verificar si ya hay dispositivos conectados al perfil HID
                    List<BluetoothDevice> connected = hidDevice.getConnectedDevices();
                    if (connected != null && !connected.isEmpty()) {
                        connectedHost = connected.get(0);
                        if (currentCallback != null) {
                            currentCallback.onStatusChanged(true, "Bluetooth HID conectado a " + getConnectedDeviceName());
                        }
                        return;
                    }

                    if (pluggedDevice != null) {
                        hidDevice.connect(pluggedDevice);
                    }
                    if (currentCallback != null) {
                        currentCallback.onStatusChanged(false, "Bluetooth HID listo. Toca para conectar a tu PC.");
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
                        String name = device.getName() != null ? device.getName() : device.getAddress();
                        currentCallback.onStatusChanged(true, "BT HID: " + name);
                    }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (connectedHost != null && connectedHost.equals(device)) {
                        connectedHost = null;
                    }
                    if (currentCallback != null) {
                        currentCallback.onStatusChanged(false, "BT HID desconectado");
                    }
                }
            }
        });
    }

    public boolean connect(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hidDevice != null && device != null) {
            connectedHost = device;
            return hidDevice.connect(device);
        }
        return false;
    }

    public boolean isConnected() {
        if (hidDevice == null) return false;
        if (connectedHost != null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            List<BluetoothDevice> list = hidDevice.getConnectedDevices();
            if (list != null && !list.isEmpty()) {
                connectedHost = list.get(0);
                return true;
            }
        }
        return false;
    }

    public void sendReport(byte[] report4Bytes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hidDevice != null) {
            if (connectedHost == null) {
                List<BluetoothDevice> list = hidDevice.getConnectedDevices();
                if (list != null && !list.isEmpty()) {
                    connectedHost = list.get(0);
                }
            }
            if (connectedHost != null) {
                hidDevice.sendReport(connectedHost, 0, report4Bytes);
            }
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
            appRegistered = false;
        }
    }
}
