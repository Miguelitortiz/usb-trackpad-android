package com.usbtrackpad.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Superficie multitáctil de alta precisión para emulación de Trackpad USB HID:
 * - 1 dedo: Movimiento de cursor con aceleración suave y acumulación sub-píxel
 * - Tap 1 dedo: Clic izquierdo
 * - Tap 2 dedos: Clic derecho
 * - 2 dedos deslizar: Rueda de scroll vertical suave
 * - Doble tap + mantener/deslizar: Arrastrar objetos (Drag & Drop)
 */
public class TrackpadGestureView extends View {

    public interface TrackpadListener {
        void onHidAction(int buttonsDeltaOrState, int dx, int dy, int wheel, String gestureLabel);
        void onTapClick(int buttonMask, String label);
        void onDoubleTapDragChanged(boolean dragging);
    }

    private TrackpadListener listener;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touchRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float sensitivity = 1.8f;
    private float scrollSensitivity = 1.0f;
    private boolean naturalScroll = false;
    private boolean tapToClick = true;

    private float lastX, lastY;
    private float downX, downY;
    private long downTimeMs = 0;
    private long lastSingleTapUpMs = 0;
    private float lastTapX, lastTapY;

    private float remX = 0f;
    private float remY = 0f;
    private float remScroll = 0f;

    private int maxPointersInGesture = 0;
    private boolean movedSignificantly = false;
    private boolean doubleTapDragging = false;

    private float activeTouchX = -1f;
    private float activeTouchY = -1f;
    private float secondTouchX = -1f;
    private float secondTouchY = -1f;
    private boolean isTouching = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    public TrackpadGestureView(Context context) {
        super(context);
        init();
    }

    public TrackpadGestureView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(0x1894A3B8);
        gridPaint.setStrokeWidth(1.5f);
        gridPaint.setStyle(Paint.Style.STROKE);

        touchPaint.setColor(0x5506B6D4);
        touchPaint.setStyle(Paint.Style.FILL);

        touchRingPaint.setColor(0xFF06B6D4);
        touchRingPaint.setStrokeWidth(3.5f);
        touchRingPaint.setStyle(Paint.Style.STROKE);

        hintPaint.setColor(0x6694A3B8);
        hintPaint.setTextSize(30f);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setTrackpadListener(TrackpadListener listener) {
        this.listener = listener;
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = Math.max(0.3f, Math.min(5.0f, sensitivity));
    }

    public void setScrollSensitivity(float scrollSensitivity) {
        this.scrollSensitivity = Math.max(0.2f, Math.min(4.0f, scrollSensitivity));
    }

    public void setNaturalScroll(boolean naturalScroll) {
        this.naturalScroll = naturalScroll;
    }

    public void setTapToClick(boolean tapToClick) {
        this.tapToClick = tapToClick;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // Cuadrícula sutil de referencia táctil
        int step = Math.max(64, Math.min(w, h) / 7);
        for (int x = step; x < w; x += step) {
            canvas.drawLine(x, 24, x, h - 24, gridPaint);
        }
        for (int y = step; y < h; y += step) {
            canvas.drawLine(24, y, w - 24, y, gridPaint);
        }

        // Indicador central e instrucciones
        if (!isTouching) {
            canvas.drawText("ÁREA TÁCTIL MULTITOUCH USB HID", w / 2f, h / 2f - 28f, hintPaint);
            canvas.drawText("1 dedo: Mover / Tap Clic Izq  |  2 dedos: Scroll / Tap Clic Der", w / 2f, h / 2f + 16f, hintPaint);
            canvas.drawText("Doble tap + deslizar: Arrastrar (Drag & Drop)", w / 2f, h / 2f + 56f, hintPaint);
        } else {
            if (activeTouchX >= 0 && activeTouchY >= 0) {
                touchRingPaint.setColor(doubleTapDragging ? 0xFF10B981 : 0xFF06B6D4);
                canvas.drawCircle(activeTouchX, activeTouchY, 44f, touchPaint);
                canvas.drawCircle(activeTouchX, activeTouchY, 48f, touchRingPaint);
            }
            if (secondTouchX >= 0 && secondTouchY >= 0) {
                canvas.drawCircle(secondTouchX, secondTouchY, 44f, touchPaint);
                canvas.drawCircle(secondTouchX, secondTouchY, 48f, touchRingPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();
        long now = event.getEventTime();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                isTouching = true;
                maxPointersInGesture = 1;
                movedSignificantly = false;
                remX = 0f;
                remY = 0f;
                remScroll = 0f;

                downX = event.getX(0);
                downY = event.getY(0);
                lastX = downX;
                lastY = downY;
                activeTouchX = downX;
                activeTouchY = downY;
                secondTouchX = -1f;
                secondTouchY = -1f;
                downTimeMs = now;

                // Detectar doble tap para arrastrar (Double-Tap & Drag)
                if (tapToClick && (now - lastSingleTapUpMs) < 280
                        && Math.hypot(downX - lastTapX, downY - lastTapY) < 65f) {
                    doubleTapDragging = true;
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    if (listener != null) {
                        listener.onDoubleTapDragChanged(true);
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                maxPointersInGesture = Math.max(maxPointersInGesture, pointerCount);
                if (pointerCount >= 2) {
                    // Cancelar drag de 1 dedo si se apoya un segundo dedo
                    if (doubleTapDragging) {
                        doubleTapDragging = false;
                        if (listener != null) {
                            listener.onDoubleTapDragChanged(false);
                        }
                    }
                    lastX = (event.getX(0) + event.getX(1)) * 0.5f;
                    lastY = (event.getY(0) + event.getY(1)) * 0.5f;
                    activeTouchX = event.getX(0);
                    activeTouchY = event.getY(0);
                    secondTouchX = event.getX(1);
                    secondTouchY = event.getY(1);
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                maxPointersInGesture = Math.max(maxPointersInGesture, pointerCount);

                if (pointerCount == 1 && maxPointersInGesture == 1) {
                    float curX = event.getX(0);
                    float curY = event.getY(0);
                    activeTouchX = curX;
                    activeTouchY = curY;
                    secondTouchX = -1f;
                    secondTouchY = -1f;

                    float rawDx = curX - lastX;
                    float rawDy = curY - lastY;
                    lastX = curX;
                    lastY = curY;

                    if (Math.hypot(curX - downX, curY - downY) > 16f) {
                        movedSignificantly = true;
                    }

                    // Curva de aceleración suave proporcional a la velocidad del dedo
                    float speed = (float) Math.hypot(rawDx, rawDy);
                    float accel = 1.0f + Math.min(1.6f, speed * 0.045f);
                    float scaledDx = rawDx * sensitivity * accel + remX;
                    float scaledDy = rawDy * sensitivity * accel + remY;

                    int outDx = (int) scaledDx;
                    int outDy = (int) scaledDy;
                    remX = scaledDx - outDx;
                    remY = scaledDy - outDy;

                    if ((outDx != 0 || outDy != 0) && listener != null) {
                        listener.onHidAction(0, outDx, outDy, 0,
                                doubleTapDragging ? "DRAG" : "MOVE");
                    }
                } else if (pointerCount >= 2) {
                    activeTouchX = event.getX(0);
                    activeTouchY = event.getY(0);
                    secondTouchX = event.getX(1);
                    secondTouchY = event.getY(1);

                    float midX = (activeTouchX + secondTouchX) * 0.5f;
                    float midY = (activeTouchY + secondTouchY) * 0.5f;
                    float rawDy = midY - lastY;
                    lastX = midX;
                    lastY = midY;

                    if (Math.abs(rawDy) > 6f) {
                        movedSignificantly = true;
                    }

                    float dir = naturalScroll ? 1.0f : -1.0f;
                    float scaledScroll = (rawDy * 0.14f * scrollSensitivity * dir) + remScroll;
                    int wheelSteps = (int) scaledScroll;
                    remScroll = scaledScroll - wheelSteps;

                    if (wheelSteps != 0 && listener != null) {
                        listener.onHidAction(0, 0, 0, wheelSteps, "SCROLL");
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int remainingIdx = (event.getActionIndex() == 0) ? 1 : 0;
                if (remainingIdx < pointerCount) {
                    lastX = event.getX(remainingIdx);
                    lastY = event.getY(remainingIdx);
                }
                secondTouchX = -1f;
                secondTouchY = -1f;
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                isTouching = false;
                activeTouchX = -1f;
                activeTouchY = -1f;
                secondTouchX = -1f;
                secondTouchY = -1f;

                if (doubleTapDragging) {
                    doubleTapDragging = false;
                    if (listener != null) {
                        listener.onDoubleTapDragChanged(false);
                    }
                } else if (action == MotionEvent.ACTION_UP && tapToClick && !movedSignificantly) {
                    long duration = now - downTimeMs;
                    if (duration < 240) {
                        if (maxPointersInGesture == 1) {
                            lastSingleTapUpMs = now;
                            lastTapX = downX;
                            lastTapY = downY;
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            if (listener != null) {
                                listener.onTapClick(HidMouseReport.BTN_LEFT, "TAP IZQ");
                            }
                        } else if (maxPointersInGesture == 2) {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            if (listener != null) {
                                listener.onTapClick(HidMouseReport.BTN_RIGHT, "TAP DER");
                            }
                        }
                    }
                }
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }
}
