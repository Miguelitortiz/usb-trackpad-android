package com.usbtrackpad.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Actividad principal del emulador de Trackpad USB HID para Android.
 * Soporta salida por USB ADB Socket, USB HID Gadget (/dev/hidg0), USB OTG Bridge y Bluetooth HID.
 */
public class MainActivity extends AppCompatActivity {

    public static final int MODE_USB_ADB_SOCKET = 0;
    public static final int MODE_USB_GADGET = 1;
    public static final int MODE_USB_HOST_BRIDGE = 2;
    public static final int MODE_BLUETOOTH_HID = 3;

    private static final String PREFS_NAME = "usb_trackpad_prefs";
    private static final String KEY_MODE = "saved_mode";
    private static final String KEY_HOST = "saved_host";
    private static final String KEY_ROTATION = "saved_manual_rotation";

    private static final int[] ROTATION_MODES = new int[]{
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,           // 0°
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,          // 90°
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,   // 180°
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE   // 270°
    };

    private UsbGadgetHidManager usbGadgetManager;
    private UsbHostHidBridgeManager usbHostManager;
    private BluetoothHidManager bluetoothHidManager;

    private View statusDot;
    private TextView tvStatus;
    private TextView tvTelemetry;
    private TextView tvSensitivityLabel;
    private TextView tvScrollLabel;
    private EditText etHostAddress;
    private View scrimSettings;
    private View panelSettingsCard;
    private LinearLayout rowTcpHost;
    private ImageButton btnDragLock;
    private ImageButton btnRotate;

    private int activeMode = MODE_USB_ADB_SOCKET;
    private int currentRotationIndex = 0;
    private int currentButtonMask = 0;
    private boolean dragLockEnabled = false;
    private long packetsSent = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Restaurar orientación manual guardada
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentRotationIndex = sp.getInt(KEY_ROTATION, 0);
        if (currentRotationIndex > 0 && currentRotationIndex < ROTATION_MODES.length) {
            setRequestedOrientation(ROTATION_MODES[currentRotationIndex]);
        }

        setContentView(R.layout.activity_main);

        usbGadgetManager = new UsbGadgetHidManager();
        usbHostManager = new UsbHostHidBridgeManager(this);
        bluetoothHidManager = new BluetoothHidManager(this);

        determineDefaultMode();
        bindViews();
        initializeActiveTransport();
    }

    private void determineDefaultMode() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean hasRootHid = new File("/dev/hidg0").exists();
        activeMode = sp.getInt(KEY_MODE, hasRootHid ? MODE_USB_GADGET : MODE_USB_ADB_SOCKET);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindViews() {
        statusDot = findViewById(R.id.statusDot);
        tvStatus = findViewById(R.id.tvStatus);
        tvTelemetry = findViewById(R.id.tvTelemetry);
        tvSensitivityLabel = findViewById(R.id.tvSensitivityLabel);
        tvScrollLabel = findViewById(R.id.tvScrollLabel);
        etHostAddress = findViewById(R.id.etHostAddress);
        scrimSettings = findViewById(R.id.scrimSettings);
        panelSettingsCard = findViewById(R.id.panelSettingsCard);
        rowTcpHost = findViewById(R.id.rowTcpHost);
        btnDragLock = findViewById(R.id.btnDragLock);
        btnRotate = findViewById(R.id.btnRotate);

        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedHost = sp.getString(KEY_HOST, "127.0.0.1:5057");
        etHostAddress.setText(savedHost);

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        ImageButton btnCloseSettings = findViewById(R.id.btnCloseSettings);
        ImageButton btnInitUsb = findViewById(R.id.btnInitUsb);
        ImageButton btnConnectAdb = findViewById(R.id.btnConnectAdb);
        ImageButton btnDiag = findViewById(R.id.btnDiag);
        View pillStatus = findViewById(R.id.pillStatus);
        Spinner spinnerMode = findViewById(R.id.spinnerMode);
        TrackpadGestureView trackpadView = findViewById(R.id.trackpadView);

        String[] modes = new String[]{
                "USB ADB (127.0.0.1:5057)",
                "USB Gadget (/dev/hidg0)",
                "USB OTG Bridge (CH9329)",
                "Bluetooth HID (Inalámbrico)"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, modes);
        spinnerMode.setAdapter(adapter);
        spinnerMode.setSelection(activeMode);
        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (activeMode != position) {
                    activeMode = position;
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putInt(KEY_MODE, activeMode)
                            .apply();
                    updateModeUiVisibility();
                    initializeActiveTransport();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        updateModeUiVisibility();

        btnSettings.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            openSettings();
        });

        btnCloseSettings.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            closeSettings();
        });

        scrimSettings.setOnClickListener(v -> closeSettings());
        panelSettingsCard.setOnClickListener(v -> {
        });

        btnRotate.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            v.animate().rotationBy(90f).setDuration(250).start();
            cycleManualRotation();
        });

        pillStatus.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (activeMode == MODE_BLUETOOTH_HID && !bluetoothHidManager.isConnected()) {
                showBluetoothDevicePicker();
            } else {
                initializeActiveTransport();
            }
        });

        btnInitUsb.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (activeMode == MODE_BLUETOOTH_HID) {
                showBluetoothDevicePicker();
            } else {
                initializeActiveTransport();
            }
            closeSettings();
        });

        btnConnectAdb.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            saveHostPreference();
            initializeActiveTransport();
        });

        btnDiag.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            showDiagnosticsDialog();
        });

        btnDragLock.setOnClickListener(v -> {
            dragLockEnabled = !dragLockEnabled;
            btnDragLock.setSelected(dragLockEnabled);
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            updateButtonBit(HidMouseReport.BTN_LEFT, dragLockEnabled);
            dispatchHidEvent(0, 0, 0, dragLockEnabled ? "DRAG ON" : "DRAG OFF");
        });

        SeekBar seekSensitivity = findViewById(R.id.seekSensitivity);
        seekSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = Math.max(3, progress) / 10.0f;
                trackpadView.setSensitivity(val);
                tvSensitivityLabel.setText(String.format(Locale.US, "%.1fx", val));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        SeekBar seekScroll = findViewById(R.id.seekScroll);
        seekScroll.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = Math.max(2, progress) / 10.0f;
                trackpadView.setScrollSensitivity(val);
                tvScrollLabel.setText(String.format(Locale.US, "%.1fx", val));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        CheckBox cbNaturalScroll = findViewById(R.id.cbNaturalScroll);
        cbNaturalScroll.setOnCheckedChangeListener((btn, checked) -> trackpadView.setNaturalScroll(checked));

        CheckBox cbTapToClick = findViewById(R.id.cbTapToClick);
        cbTapToClick.setOnCheckedChangeListener((btn, checked) -> trackpadView.setTapToClick(checked));

        trackpadView.setTrackpadListener(new TrackpadGestureView.TrackpadListener() {
            @Override
            public void onHidAction(int buttonsDeltaOrState, int dx, int dy, int wheel, String gestureLabel) {
                dispatchHidEvent(dx, dy, wheel, gestureLabel);
            }

            @Override
            public void onSemanticAction(int action, int value, String gestureLabel) {
                dispatchSemanticEvent(action, value, gestureLabel);
            }

            @Override
            public void onTapClick(int buttonMask, String label) {
                currentButtonMask |= buttonMask;
                dispatchHidEvent(0, 0, 0, label + " DOWN");
                mainHandler.postDelayed(() -> {
                    if (!dragLockEnabled || buttonMask != HidMouseReport.BTN_LEFT) {
                        currentButtonMask &= ~buttonMask;
                        dispatchHidEvent(0, 0, 0, label + " UP");
                    }
                }, 35);
            }

            @Override
            public void onDoubleTapDragChanged(boolean dragging) {
                updateButtonBit(HidMouseReport.BTN_LEFT, dragging || dragLockEnabled);
                dispatchHidEvent(0, 0, 0, dragging ? "HOLD DRAG" : "RELEASE DRAG");
            }
        });

        setupMouseButton(findViewById(R.id.btnMouseLeft), HidMouseReport.BTN_LEFT, "BTN IZQ");
        setupMouseButton(findViewById(R.id.btnMouseMiddle), HidMouseReport.BTN_MIDDLE, "BTN MED");
        setupMouseButton(findViewById(R.id.btnMouseRight), HidMouseReport.BTN_RIGHT, "BTN DER");
    }

    private void openSettings() {
        scrimSettings.setAlpha(0f);
        scrimSettings.setVisibility(View.VISIBLE);
        scrimSettings.animate().alpha(1f).setDuration(220).start();
    }

    private void closeSettings() {
        scrimSettings.animate().alpha(0f).setDuration(160).withEndAction(() ->
                scrimSettings.setVisibility(View.GONE)).start();
    }

    private void cycleManualRotation() {
        currentRotationIndex = (currentRotationIndex + 1) % ROTATION_MODES.length;
        setRequestedOrientation(ROTATION_MODES[currentRotationIndex]);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_ROTATION, currentRotationIndex)
                .apply();
    }

    private void updateModeUiVisibility() {
        rowTcpHost.setVisibility(activeMode == MODE_USB_ADB_SOCKET ? View.VISIBLE : View.GONE);
    }

    private void saveHostPreference() {
        String host = etHostAddress.getText().toString().trim();
        if (host.isEmpty()) host = "127.0.0.1:5057";
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_HOST, host)
                .apply();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupMouseButton(View button, final int mask, final String label) {
        button.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start();
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                updateButtonBit(mask, true);
                dispatchHidEvent(0, 0, 0, label + " DOWN");
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                if (!(mask == HidMouseReport.BTN_LEFT && dragLockEnabled)) {
                    updateButtonBit(mask, false);
                }
                dispatchHidEvent(0, 0, 0, label + " UP");
                return true;
            }
            return false;
        });
    }

    private void updateButtonBit(int bit, boolean enabled) {
        if (enabled) {
            currentButtonMask |= bit;
        } else {
            currentButtonMask &= ~bit;
        }
    }

    private void initializeActiveTransport() {
        usbHostManager.stopUsbAdbLoop();
        updateStatusUi(false, "Conectando...");

        if (activeMode == MODE_USB_ADB_SOCKET) {
            saveHostPreference();
            String hostPort = etHostAddress.getText().toString();
            usbHostManager.startUsbAdbLoop(hostPort, (connected, status) ->
                    mainHandler.post(() -> updateStatusUi(connected, connected ? "ADB OK" : "ADB...")));
        } else if (activeMode == MODE_USB_GADGET) {
            usbGadgetManager.initializeAsync((ready, message) ->
                    mainHandler.post(() -> updateStatusUi(ready, ready ? "HID OK" : "HID...")));
        } else if (activeMode == MODE_USB_HOST_BRIDGE) {
            usbHostManager.connectUsbHostBridge((connected, status) ->
                    mainHandler.post(() -> updateStatusUi(connected, connected ? "OTG OK" : "OTG...")));
        } else if (activeMode == MODE_BLUETOOTH_HID) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE
                    }, 101);
                    return;
                }
            }
            bluetoothHidManager.initialize((connected, status) ->
                    mainHandler.post(() -> updateStatusUi(connected, connected ? "BT OK" : "BT...")));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeActiveTransport();
        }
    }

    private void showBluetoothDevicePicker() {
        Set<BluetoothDevice> bonded = bluetoothHidManager.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Dispositivos Bluetooth")
                    .setMessage("No hay dispositivos vinculados aún.\n\nVe a los Ajustes de Bluetooth de tu teléfono y vincula tu PC/Laptop primero.")
                    .setPositiveButton("Aceptar", null)
                    .show();
            return;
        }

        List<BluetoothDevice> deviceList = new ArrayList<>(bonded);
        String[] names = new String[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++) {
            BluetoothDevice d = deviceList.get(i);
            String name = d.getName() != null ? d.getName() : d.getAddress();
            names[i] = name;
        }

        new AlertDialog.Builder(this)
                .setTitle("Conectar Ratón Bluetooth a:")
                .setItems(names, (dialog, which) -> {
                    BluetoothDevice selected = deviceList.get(which);
                    updateStatusUi(false, "Conectando...");
                    boolean started = bluetoothHidManager.connect(selected);
                    if (!started) {
                        initializeActiveTransport();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void updateStatusUi(boolean connected, String statusText) {
        tvStatus.setText(statusText);
        statusDot.setBackgroundResource(connected
                ? android.R.drawable.presence_online
                : android.R.drawable.presence_away);
    }

    private void dispatchHidEvent(int dx, int dy, int wheel, String label) {
        byte[] report = HidMouseReport.build4ByteReport(currentButtonMask, dx, dy, wheel);
        packetsSent++;

        if (activeMode == MODE_USB_ADB_SOCKET) {
            usbHostManager.sendTcpReport(report);
        } else if (activeMode == MODE_USB_GADGET) {
            usbGadgetManager.sendReport(report);
        } else if (activeMode == MODE_USB_HOST_BRIDGE) {
            usbHostManager.sendUsbHostReport(currentButtonMask, dx, dy, wheel);
        } else if (activeMode == MODE_BLUETOOTH_HID) {
            bluetoothHidManager.sendReport(report);
        }

        tvTelemetry.setText(String.format(
                Locale.US,
                "HID [%02X | X:%+3d Y:%+3d W:%+1d]",
                report[0] & 0xFF,
                report[1],
                report[2],
                report[3]
        ));
    }

    private void dispatchSemanticEvent(int action, int value, String label) {
        if (activeMode == MODE_USB_ADB_SOCKET) {
            usbHostManager.sendTcpSemanticAction(action, value);
        }
        tvTelemetry.setText(label);
    }

    private void showDiagnosticsDialog() {
        StringBuilder btInfo = new StringBuilder();
        btInfo.append("Bluetooth Activo: ").append(bluetoothHidManager.isBluetoothEnabled());
        btInfo.append("\nPerfil HID registrado: ").append(bluetoothHidManager.isAppRegistered());
        btInfo.append("\nDispositivo Conectado: ").append(bluetoothHidManager.getConnectedDeviceName());
        Set<BluetoothDevice> bonded = bluetoothHidManager.getBondedDevices();
        if (bonded != null && !bonded.isEmpty()) {
            btInfo.append("\nDispositivos vinculados:");
            for (BluetoothDevice d : bonded) {
                btInfo.append("\n • ").append(d.getName() != null ? d.getName() : d.getAddress());
            }
        }

        String report = "MODO ACTIVO: " + (activeMode == MODE_USB_ADB_SOCKET ? "USB ADB" :
                activeMode == MODE_USB_GADGET ? "USB HID Gadget" :
                activeMode == MODE_USB_HOST_BRIDGE ? "USB Host Bridge" : "Bluetooth HID")
                + "\n\n--- DIAGNÓSTICO BLUETOOTH ---\n" + btInfo
                + "\n\n--- DIAGNÓSTICO USB ADB ---\n"
                + "Conectado al Host: " + (usbHostManager.isTcpConnected() ? "SÍ" : "NO")
                + "\nDirección: " + etHostAddress.getText().toString()
                + "\n\n--- DIAGNÓSTICO KERNEL /dev/hidg0 ---\n"
                + usbGadgetManager.buildDiagnosticsReport();

        new AlertDialog.Builder(this)
                .setTitle("Diagnóstico del Sistema")
                .setMessage(report)
                .setPositiveButton("Reintentar Conexión", (d, w) -> initializeActiveTransport())
                .setNegativeButton("Cerrar", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        usbGadgetManager.close();
        usbHostManager.closeAll();
        bluetoothHidManager.close();
    }
}
