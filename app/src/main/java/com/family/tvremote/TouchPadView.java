package com.family.tvremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * TV 포인터용 터치패드.
 * 한 손가락으로 문지르면 이동, 짧게 톡 치면 클릭, 두 손가락으로 위아래로 밀면 스크롤.
 */
public class TouchPadView extends View {

    public interface Listener {
        void onMove(int dx, int dy);
        void onClick();
        void onScroll(int dy);
    }

    private static final float SPEED = 1.6f;      // 포인터 속도
    private static final float SCROLL_STEP = 24f; // 이만큼 밀면 스크롤 한 칸

    private final Listener listener;
    private final float density;
    private final Paint padPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fingerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fingerRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float lastX, lastY, lastScrollY;
    private float carryX, carryY, scrollCarry;
    private float travel;
    private long downTime;
    private boolean touching, multi;
    private float fingerX, fingerY;

    public TouchPadView(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        this.density = ctx.getResources().getDisplayMetrics().density;
        padPaint.setColor(Color.parseColor("#16171B"));
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1.5f * density);
        edgePaint.setColor(Color.parseColor("#26282E"));
        fingerFill.setColor(Color.parseColor("#2EFF7A1A"));
        fingerRing.setStyle(Paint.Style.STROKE);
        fingerRing.setStrokeWidth(1.5f * density);
        fingerRing.setColor(Color.parseColor("#8CFF7A1A"));
        hintPaint.setColor(Color.parseColor("#5F6470"));
        hintPaint.setTextSize(13 * density);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = 24 * density;
        rect.set(0, 0, getWidth(), getHeight());
        c.drawRoundRect(rect, r, r, padPaint);
        rect.inset(edgePaint.getStrokeWidth() / 2, edgePaint.getStrokeWidth() / 2);
        c.drawRoundRect(rect, r, r, edgePaint);

        if (touching) {
            float fr = 24 * density;
            c.drawCircle(fingerX, fingerY, fr, fingerFill);
            c.drawCircle(fingerX, fingerY, fr, fingerRing);
        } else {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float line = 20 * density;
            c.drawText("문지르면 포인터 이동", cx, cy - line, hintPaint);
            c.drawText("톡 치면 클릭", cx, cy + 4 * density, hintPaint);
            c.drawText("두 손가락으로 스크롤", cx, cy + line + 8 * density, hintPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                multi = false;
                travel = 0;
                carryX = carryY = scrollCarry = 0;
                downTime = e.getEventTime();
                lastX = fingerX = e.getX();
                lastY = fingerY = e.getY();
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                lastScrollY = avgY(e);
                return true;

            case MotionEvent.ACTION_MOVE:
                fingerX = e.getX();
                fingerY = e.getY();
                if (multi && e.getPointerCount() >= 2) {
                    float y = avgY(e);
                    scrollCarry += y - lastScrollY;
                    lastScrollY = y;
                    while (Math.abs(scrollCarry) >= SCROLL_STEP * density) {
                        int dir = scrollCarry > 0 ? 1 : -1;
                        listener.onScroll(dir * 1);
                        scrollCarry -= dir * SCROLL_STEP * density;
                    }
                } else if (!multi) {
                    float dx = (e.getX() - lastX) * SPEED / density * 2f;
                    float dy = (e.getY() - lastY) * SPEED / density * 2f;
                    travel += Math.abs(e.getX() - lastX) + Math.abs(e.getY() - lastY);
                    lastX = e.getX();
                    lastY = e.getY();
                    carryX += dx;
                    carryY += dy;
                    int ix = (int) carryX;
                    int iy = (int) carryY;
                    if (ix != 0 || iy != 0) {
                        listener.onMove(ix, iy);
                        carryX -= ix;
                        carryY -= iy;
                    }
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                boolean tap = !multi && travel < 10 * density && e.getEventTime() - downTime < 300;
                if (tap) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    listener.onClick();
                }
                touching = false;
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                touching = false;
                invalidate();
                return true;

            default:
                return true;
        }
    }

    private static float avgY(MotionEvent e) {
        float sum = 0;
        int n = e.getPointerCount();
        for (int i = 0; i < n; i++) sum += e.getY(i);
        return sum / n;
    }
}
