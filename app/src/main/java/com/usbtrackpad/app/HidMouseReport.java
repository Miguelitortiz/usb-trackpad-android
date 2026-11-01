package com.usbtrackpad.app;

/**
 * Construye reportes estándar USB HID de Mouse/Trackpad (4 bytes: Botones, X, Y, Rueda)
 * y descriptores HID estándar compatibles con cualquier SO (Windows, Linux, macOS, Android, iOS, Smart TV, BIOS).
 */
public final class HidMouseReport {

    public static final int BTN_LEFT = 0x01;
    public static final int BTN_RIGHT = 0x02;
    public static final int BTN_MIDDLE = 0x04;
    public static final int ACTION_ZOOM_IN = 0x01;
    public static final int ACTION_ZOOM_OUT = 0x02;
    public static final int ACTION_BACK = 0x03;
    public static final int ACTION_FORWARD = 0x04;

    /**
     * Descriptor USB HID estándar de 52 bytes para Mouse de 3 botones + Rueda de Scroll vertical.
     */
    public static final byte[] MOUSE_REPORT_DESCRIPTOR = new byte[]{
            (byte) 0x05, (byte) 0x01, // Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x02, // Usage (Mouse)
            (byte) 0xA1, (byte) 0x01, // Collection (Application)
            (byte) 0x09, (byte) 0x01, //   Usage (Pointer)
            (byte) 0xA1, (byte) 0x00, //   Collection (Physical)
            (byte) 0x05, (byte) 0x09, //     Usage Page (Buttons)
            (byte) 0x19, (byte) 0x01, //     Usage Minimum (1)
            (byte) 0x29, (byte) 0x03, //     Usage Maximum (3)
            (byte) 0x15, (byte) 0x00, //     Logical Minimum (0)
            (byte) 0x25, (byte) 0x01, //     Logical Maximum (1)
            (byte) 0x95, (byte) 0x03, //     Report Count (3)
            (byte) 0x75, (byte) 0x01, //     Report Size (1)
            (byte) 0x81, (byte) 0x02, //     Input (Data, Variable, Absolute)
            (byte) 0x95, (byte) 0x01, //     Report Count (1)
            (byte) 0x75, (byte) 0x05, //     Report Size (5 padding bits)
            (byte) 0x81, (byte) 0x01, //     Input (Constant)
            (byte) 0x05, (byte) 0x01, //     Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x30, //     Usage (X)
            (byte) 0x09, (byte) 0x31, //     Usage (Y)
            (byte) 0x09, (byte) 0x38, //     Usage (Wheel)
            (byte) 0x15, (byte) 0x81, //     Logical Minimum (-127)
            (byte) 0x25, (byte) 0x7F, //     Logical Maximum (127)
            (byte) 0x75, (byte) 0x08, //     Report Size (8)
            (byte) 0x95, (byte) 0x03, //     Report Count (3)
            (byte) 0x81, (byte) 0x06, //     Input (Data, Variable, Relative)
            (byte) 0xC0,              //   End Collection
            (byte) 0xC0               // End Collection
    };

    private HidMouseReport() {
    }

    public static byte clampDelta(int val) {
        if (val > 127) return (byte) 127;
        if (val < -127) return (byte) -127;
        return (byte) val;
    }

    /**
     * Empaqueta un reporte HID estándar de 4 bytes: [buttons, dx, dy, wheel].
     */
    public static byte[] build4ByteReport(int buttonsMask, int dx, int dy, int wheel) {
        return new byte[]{
                (byte) (buttonsMask & 0x07),
                clampDelta(dx),
                clampDelta(dy),
                clampDelta(wheel)
        };
    }

    /**
     * Semantic extension frame: [0xFF, action, value high, value low, reserved].
     * 0xFF cannot be a legacy button byte because only bits 0..2 are valid.
     */
    public static byte[] buildSemanticFrame(int action, int value) {
        int clamped = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        return new byte[]{
                (byte) 0xFF,
                (byte) action,
                (byte) (clamped >> 8),
                (byte) clamped,
                0
        };
    }

    /**
     * Empaqueta una trama compatible con puentes hardware USB OTG a HID (CH9329 / Serial-HID):
     * [0x57, 0xAB, 0x00, 0x05, 0x05, 0x01, buttons, dx, dy, wheel, checksum]
     */
    public static byte[] buildCh9329Frame(int buttonsMask, int dx, int dy, int wheel) {
        byte b = (byte) (buttonsMask & 0x07);
        byte x = clampDelta(dx);
        byte y = clampDelta(dy);
        byte w = clampDelta(wheel);
        byte[] frame = new byte[]{
                (byte) 0x57, (byte) 0xAB, (byte) 0x00, (byte) 0x05, (byte) 0x05,
                (byte) 0x01, b, x, y, w, (byte) 0x00
        };
        int sum = 0;
        for (int i = 0; i < frame.length - 1; i++) {
            sum += (frame[i] & 0xFF);
        }
        frame[frame.length - 1] = (byte) (sum & 0xFF);
        return frame;
    }
}
