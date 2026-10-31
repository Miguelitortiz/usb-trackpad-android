package com.usbtrackpad.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.Locale;

/**
 * Actividad principal del emulador de Trackpad USB HID para Android.
 * Soporta salida por USB a cualquier dispositivo receptor mediante:
 * 1) Modo USB ADB / Socket Local con auto-reconexión persistente (por defecto para teléfonos sin Root).
 * 2) Modo USB HID Gadget Nativo (/dev/hidg0 ConfigFS - Plug & Play real para teléfonos con Root).
 * 3) Modo USB Host / OTG HID Bridge (CH9329 / HID Host sin Root).
 * 4) Modo Bluetooth HID Nativo (API oficial de Android sin Root).
 */
public class MainActivity extends AppCompatActivity {

    public static final int MODE_USB_ADB_SOCKET = 0;
    public static final int MODE_USB_GADGET = 1;
    public static final int MODE_USB_HOST_BRIDGE = 2;
    public static final int MODE_BLUETOOTH_HID = 3;

    private static final String PREFS_NAME = "usb_trackpad_prefs";
    private static final String KEY_MODE = "saved_mode";
    private static final String KEY_HOST = "saved_host";

    private UsbGadgetHidManager usbGadgetManager;
    private UsbHostHidBridgeManager usbHostManager;
    private BluetoothHidManager bluetoothHidManager;

    private View statusDot;
    private TextView tvStatus;
    private TextView tvTelemetry;
    private TextView tvSensitivityLabel;
    private TextView tvScrollLabel;
    private EditText etHostAddress;
    private LinearLayout panelSettings;
    private LinearLayout rowTcpHost;
    private Button btnDragLock;

    private int activeMode = MODE_USB_ADB_SOCKET;
    private int currentButtonMask = 0;
    private boolean dragLockEnabled = false;
    private long packetsSent = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        // Si ya hay preferencia guardada, usarla; si no, usar USB ADB por defecto si no hay Root
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
        panelSettings = findViewById(R.id.panelSettings);
        rowTcpHost = findViewById(R.id.rowTcpHost);
        btnDragLock = findViewById(R.id.btnDragLock);

        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedHost = sp.getString(KEY_HOST, "127.0.0.1:5057");
        etHostAddress.setText(savedHost);

        Button btnInitUsb = findViewById(R.id.btnInitUsb);
        Button btnConnectAdb = findViewById(R.id.btnConnectAdb);
        Button btnSettings = findViewById(R.id.btnSettings);
        Button btnDiag = findViewById(R.id.btnDiag);
        Spinner spinnerMode = findViewById(R.id.spinnerMode);
        TrackpadGestureView trackpadView = findViewById(R.id.trackpadView);

        String[] modes = new String[]{
                "1. USB ADB / Cable PC (127.0.0.1:5057)",
                "2. USB HID Gadget (Kernel / Root /dev/hidg0)",
                "3. USB OTG Bridge (CH9329 / Host)",
                "4. Bluetooth HID (Inalámbrico sin Root)"
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

        btnInitUsb.setOnClickListener(v -> initializeActiveTransport());
        btnConnectAdb.setOnClickListener(v -> {
            saveHostPreference();
            initializeActiveTransport();
        });

        btnSettings.setOnClickListener(v -> {
            panelSettings.setVisibility(
                    panelSettings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

        btnDiag.setOnClickListener(v -> showDiagnosticsDialog());

        btnDragLock.setOnClickListener(v -> {
            dragLockEnabled = !dragLockEnabled;
            btnDragLock.setSelected(dragLockEnabled);
            btnDragLock.setText(dragLockEnabled ? R.string.btn_drag_lock_on : R.string.btn_drag_lock);
            updateButtonBit(HidMouseReport.BTN_LEFT, dragLockEnabled);
            dispatchHidEvent(0, 0, 0, dragLockEnabled ? "DRAG ON" : "DRAG OFF");
        });

        SeekBar seekSensitivity = findViewById(R.id.seekSensitivity);
        seekSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = Math.max(3, progress) / 10.0f;
                trackpadView.setSensitivity(val);
                tvSensitivityLabel.setText(String.format(Locale.US, "Sensibilidad del cursor: %.1fx", val));
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
                tvScrollLabel.setText(String.format(Locale.US, "Velocidad de Scroll (2 dedos): %.1fx", val));
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

        // Conectar eventos del área táctil multitouch
        trackpadView.setTrackpadListener(new TrackpadGestureView.TrackpadListener() {
            @Override
            public void onHidAction(int buttonsDeltaOrState, int dx, int dy, int wheel, String gestureLabel) {
                dispatchHidEvent(dx, dy, wheel, gestureLabel);
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

        // Botones físicos en pantalla
        setupMouseButton(findViewById(R.id.btnMouseLeft), HidMouseReport.BTN_LEFT, "BTN IZQ");
        setupMouseButton(findViewById(R.id.btnMouseMiddle), HidMouseReport.BTN_MIDDLE, "BTN MED");
        setupMouseButton(findViewById(R.id.btnMouseRight), HidMouseReport.BTN_RIGHT, "BTN DER");
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
    private void setupMouseButton(Button button, final int mask, final String label) {
        button.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                updateButtonBit(mask, true);
                dispatchHidEvent(0, 0, 0, label + " DOWN");
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
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
        updateStatusUi(false, "Iniciando modo seleccionado...");

        if (activeMode == MODE_USB_ADB_SOCKET) {
            saveHostPreference();
            String hostPort = etHostAddress.getText().toString();
            usbHostManager.startUsbAdbLoop(hostPort, (connected, status) ->
                    mainHandler.post(() -> updateStatusUi(connected, status)));
        } else if (activeMode == MODE_USB_GADGET) {
            usbGadgetManager.initializeAsync((ready, message) ->
                    mainHandler.post(() -> updateStatusUi(ready, message)));
        } else if (activeMode == MODE_USB_HOST_BRIDGE) {
            usbHostManager.connectUsbHostBridge((connected, status) ->
                    mainHandler.post(() -> updateStatusUi(connected, status)));
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
                    mainHandler.post(() -> updateStatusUi(connected, status)));
        }
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
                "%s | HID [%02X | X:%+4d Y:%+4d W:%+2d] #%d",
                label,
                report[0] & 0xFF,
                report[1],
                report[2],
                report[3],
                packetsSent
        ));
    }

    private void showDiagnosticsDialog() {
        String report = "Estado USB ADB: " + (usbHostManager.isTcpConnected() ? "CONECTADO" : "DESCONECTADO")
                + "\nHost destino: " + etHostAddress.getText().toString()
                + "\n\n" + usbGadgetManager.buildDiagnosticsReport()
                + "\nDispositivos USB OTG detectados: " + usbHostManager.getDetectedUsbDeviceCount()
                + "\nBluetooth HID conectado: " + bluetoothHidManager.isConnected();

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
