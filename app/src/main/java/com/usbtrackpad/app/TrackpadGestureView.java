package com.usbtrackpad.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Superficie táctil reactiva estilo cristal pulido (Apple Glassmorphism):
 * - Canvas limpio sin saturación de texto (la disposición física define la función).
 * - Halo reactivo de cristal líquido con gradientes suaves y retroalimentación táctil inmediata.
 * - Soporte para gestos fluidos de 1 y 2 dedos con puente luminoso multi-touch.
 */
public class TrackpadGestureView extends View {

    public interface TrackpadListener {
        void onHidAction(int buttonsDeltaOrState, int dx, int dy, int wheel, String gestureLabel);
        void onTapClick(int buttonMask, String label);
        void onDoubleTapDragChanged(boolean dragging);
        void onSemanticAction(int action, int value, String gestureLabel);
    }

    private TrackpadListener listener;

    private final Paint dotGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touchHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touchCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touchRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bridgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
    private float lastPinchDistance = 0f;
    private float horizontalGestureDistance = 0f;
    private boolean horizontalGesture = false;

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
        // Puntos de referencia ultra sutiles (micro rejilla de cristal)
        dotGridPaint.setColor(0x14FFFFFF);
        dotGridPaint.setStyle(Paint.Style.FILL);

        touchHaloPaint.setStyle(Paint.Style.FILL);

        touchCorePaint.setColor(0x8038BDF8);
        touchCorePaint.setStyle(Paint.Style.FILL);

        touchRingPaint.setStyle(Paint.Style.STROKE);
        touchRingPaint.setStrokeWidth(2.5f);
        touchRingPaint.setColor(0xE638BDF8);

        bridgePaint.setColor(0x4038BDF8);
        bridgePaint.setStyle(Paint.Style.STROKE);
        bridgePaint.setStrokeWidth(3.0f);
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

        // 1. Matriz de micro-puntos estética (Apple Glass dot matrix)
        float step = 56f;
        for (float x = step; x < w; x += step) {
            for (float y = step; y < h; y += step) {
                canvas.drawCircle(x, y, 1.4f, dotGridPaint);
            }
        }

        // 2. Elementos táctiles reactivos de cristal
        if (isTouching) {
            int glowColor = doubleTapDragging ? 0xFF10B981 : 0xFF00E5FF;
            int ringColor = doubleTapDragging ? 0xFF34D399 : 0xFF38BDF8;

            // Si hay dos dedos activos (gesto de scroll/clic derecho), dibujar enlace luminoso
            if (activeTouchX >= 0 && secondTouchX >= 0) {
                bridgePaint.setColor(Color.argb(70, Color.red(ringColor), Color.green(ringColor), Color.blue(ringColor)));
                canvas.drawLine(activeTouchX, activeTouchY, secondTouchX, secondTouchY, bridgePaint);
            }

            // Primer punto de toque (Dedo principal)
            if (activeTouchX >= 0 && activeTouchY >= 0) {
                drawGlassTouchAura(canvas, activeTouchX, activeTouchY, glowColor, ringColor);
            }

            // Segundo punto de toque
            if (secondTouchX >= 0 && secondTouchY >= 0) {
                drawGlassTouchAura(canvas, secondTouchX, secondTouchY, glowColor, ringColor);
            }
        }
    }

    /**
     * Dibuja un aura reactiva con profundidad óptica simulando cristal pulido iluminado.
     */
    private void drawGlassTouchAura(Canvas canvas, float x, float y, int glowColor, int ringColor) {
        // Halo exterior con gradiente suave
        int haloOuter = Color.argb(0, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor));
        int haloInner = Color.argb(60, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor));

        RadialGradient gradient = new RadialGradient(
                x, y, 64f,
                new int[]{haloInner, haloOuter},
                new float[]{0.3f, 1.0f},
                Shader.TileMode.CLAMP
        );
        touchHaloPaint.setShader(gradient);
        canvas.drawCircle(x, y, 64f, touchHaloPaint);

        // Núcleo translúcido interior
        touchCorePaint.setColor(Color.argb(85, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)));
        canvas.drawCircle(x, y, 26f, touchCorePaint);

        // Anillo exterior especular
        touchRingPaint.setColor(ringColor);
        canvas.drawCircle(x, y, 32f, touchRingPaint);

        // Destello especular central de cristal
        touchCorePaint.setColor(0xCCFFFFFF);
        canvas.drawCircle(x, y, 5f, touchCorePaint);
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
                lastPinchDistance = 0f;
                horizontalGestureDistance = 0f;
                horizontalGesture = false;

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
                    if (doubleTapDragging) {
                        doubleTapDragging = false;
                        if (listener != null) {
                            listener.onDoubleTapDragChanged(false);
                        }
                    }
                    lastX = (event.getX(0) + event.getX(1)) * 0.5f;
                    lastY = (event.getY(0) + event.getY(1)) * 0.5f;
                    lastPinchDistance = distance(event);
                    horizontalGestureDistance = 0f;
                    horizontalGesture = false;
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

                    // Aceleración de movimiento sub-píxel suave
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
                    float rawDx = midX - lastX;
                    float rawDy = midY - lastY;
                    lastX = midX;
                    lastY = midY;

                    float pinchDelta = distance(event) - lastPinchDistance;
                    lastPinchDistance = distance(event);
                    if (Math.abs(pinchDelta) > 10f && listener != null) {
                        movedSignificantly = true;
                        listener.onSemanticAction(
                                pinchDelta > 0
                                        ? HidMouseReport.ACTION_ZOOM_IN
                                        : HidMouseReport.ACTION_ZOOM_OUT,
                                (int) Math.abs(pinchDelta),
                                pinchDelta > 0 ? "ZOOM IN" : "ZOOM OUT");
                    }

                    if (!horizontalGesture && Math.abs(rawDx) > Math.abs(rawDy) * 1.35f
                            && Math.abs(rawDx) > 5f) {
                        horizontalGesture = true;
                        movedSignificantly = true;
                    }
                    if (horizontalGesture) {
                        horizontalGestureDistance += rawDx;
                        if (Math.abs(horizontalGestureDistance) >= 90f && listener != null) {
                            boolean forward = horizontalGestureDistance < 0;
                            listener.onSemanticAction(
                                    forward ? HidMouseReport.ACTION_FORWARD : HidMouseReport.ACTION_BACK,
                                    (int) Math.abs(horizontalGestureDistance),
                                    forward ? "FORWARD" : "BACK");
                            horizontalGestureDistance = 0f;
                        }
                    } else if (Math.abs(rawDy) > 6f) {
                        movedSignificantly = true;
                        float dir = naturalScroll ? 1.0f : -1.0f;
                        float scaledScroll = (rawDy * 0.14f * scrollSensitivity * dir) + remScroll;
                        int wheelSteps = (int) scaledScroll;
                        remScroll = scaledScroll - wheelSteps;
                        if (wheelSteps != 0 && listener != null) {
                            listener.onHidAction(0, 0, 0, wheelSteps, "SCROLL");
                        }
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

    private static float distance(MotionEvent event) {
        float dx = event.getX(1) - event.getX(0);
        float dy = event.getY(1) - event.getY(0);
        return (float) Math.hypot(dx, dy);
    }
}
