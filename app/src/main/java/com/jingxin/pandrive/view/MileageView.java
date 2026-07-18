package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Mileage & fuel display: two-line layout, slides between 4 modes
 */
public class MileageView extends View {

    private static final int MODE_TRIP = 0;
    private static final int MODE_TODAY = 1;
    private static final int MODE_TOTAL = 2;
    private static final int MODE_FUEL = 3;
    private static final int MODE_RANGE = 4;
    private static final int MODE_PERCENT = 5;
    private static final int MODE_COUNT = 6;
    private static final String[] MODE_LABELS = {"实时行程", "今日行程", "累计行程", "综合", "剩余续航", "剩余"};
    private boolean isElectric = false;

    private float tripKm = 0f;
    private float todayKm = 0f;
    private float totalKm = 0f;
    private float fuelLPer100km = 0f;
    private float rangeKm = 0f;
    private float remainingPercent = 0f;

    // Cycle state
    private int currentMode = MODE_TRIP;
    private int prevMode = MODE_TRIP;
    private long lastCycleTime = 0;
    private static final long CYCLE_INTERVAL_MS = 4000;
    private static final long SLIDE_DURATION_MS = 400;

    private boolean isNightMode = false;

    private final Paint ledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Handler cycleHandler = new Handler(Looper.getMainLooper());
    private final Runnable cycleRunnable = new Runnable() {
        @Override
        public void run() {
            prevMode = currentMode;
            currentMode = (currentMode + 1) % MODE_COUNT;
            lastCycleTime = System.currentTimeMillis();
            invalidate();
            cycleHandler.postDelayed(this, CYCLE_INTERVAL_MS);
        }
    };

    public MileageView(Context context) { super(context); init(); }
    public MileageView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public MileageView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        ledPaint.setStyle(Paint.Style.FILL);
        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        lastCycleTime = System.currentTimeMillis();
        cycleHandler.postDelayed(cycleRunnable, CYCLE_INTERVAL_MS);
    }

    public void setNightMode(boolean night) { isNightMode = night; invalidate(); }
    public void setVehicleType(int type) { isElectric = (type == 1); invalidate(); }

    public void updateMileage(float trip, float today, float total) {
        this.tripKm = trip; this.todayKm = today; this.totalKm = total; invalidate();
    }

    public void updateFuel(float fuel, float range, float percent) { this.fuelLPer100km = fuel; this.rangeKm = range; this.remainingPercent = percent; invalidate(); }

    @Override
    public boolean onTouchEvent(MotionEvent event) { return false; }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cycleHandler.removeCallbacks(cycleRunnable);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        long now = System.currentTimeMillis();
        long elapsed = now - lastCycleTime;
        float slideProgress = 0f;
        boolean isSliding = elapsed < SLIDE_DURATION_MS;
        if (isSliding) {
            slideProgress = (float) elapsed / SLIDE_DURATION_MS;
            // Ease-out curve
            slideProgress = 1f - (1f - slideProgress) * (1f - slideProgress);
            // Keep animating
            invalidate();
        }

        // Colors
        int activeColor = isNightMode ? 0xFF00E5A0 : 0xFF00D4E8;
        int activeGlow = isNightMode ? 0xFF009966 : 0xFF00B8D4;
        int labelColor = isNightMode ? 0xFFFFFFFF : 0xFF000000;

        if (isSliding) {
            // Draw previous mode sliding out to the left
            int prevAlpha = (int) (255f * (1f - slideProgress));
            float prevOffsetX = -slideProgress * w * 0.3f;
            canvas.save();
            canvas.translate(prevOffsetX, 0);
            drawContent(canvas, w, h, prevMode, prevAlpha, activeColor, activeGlow, labelColor);
            canvas.restore();

            // Draw current mode sliding in from the right
            int curAlpha = (int) (255f * slideProgress);
            float curOffsetX = (1f - slideProgress) * w * 0.3f;
            canvas.save();
            canvas.translate(curOffsetX, 0);
            drawContent(canvas, w, h, currentMode, curAlpha, activeColor, activeGlow, labelColor);
            canvas.restore();
        } else {
            // Static display
            drawContent(canvas, w, h, currentMode, 255, activeColor, activeGlow, labelColor);
        }
    }

    private void drawContent(Canvas canvas, int w, int h, int mode, int alpha,
                             int activeColor, int activeGlow, int labelColor) {
        float value;
        String numStr;
        if (mode == MODE_FUEL) {
            value = fuelLPer100km;
            numStr = String.format("%.1f", value);
        } else if (mode == MODE_RANGE) {
            value = rangeKm;
            numStr = (value < 1f) ? "0" : String.format("%.0f", value);
        } else if (mode == MODE_PERCENT) {
            value = remainingPercent;
            numStr = String.format("%.0f", value);
        } else {
            switch (mode) {
                case MODE_TODAY: value = todayKm; break;
                case MODE_TOTAL: value = totalKm; break;
                default: value = tripKm; break;
            }
            if (mode == MODE_TOTAL) {
                numStr = String.format("%,.1f", value);
            } else if (value < 1f) {
                numStr = String.format("%.2f", value);
            } else {
                numStr = String.format("%.1f", value);
            }
        }
        String label;
        if (mode == MODE_FUEL) {
            label = isElectric ? "综合电耗" : "综合油耗";
        } else if (mode == MODE_RANGE) {
            label = "剩余续航";
        } else if (mode == MODE_PERCENT) {
            label = isElectric ? "剩余电量" : "剩余油量";
        } else {
            label = MODE_LABELS[mode];
        }

        // 余量<=10%时红色
        if (mode == MODE_PERCENT && remainingPercent <= 10f) {
            activeColor = 0xFFFF4444;
            activeGlow = 0xFFCC0000;
        }

        float baseSize = h;
        float digitH = baseSize * 0.21f;
        float digitW = digitH * 0.5f;
        float segThick = digitH * 0.14f;
        float segGap = digitH * 0.06f;
        float digitGap = digitW * 0.3f;
        float labelTextSize = baseSize * 0.093f;

        float centerX = w / 2f;

        // Line 1: Label
        labelPaint.setTextSize(labelTextSize);
        labelPaint.setColor(labelColor);
        labelPaint.setAlpha(alpha);
        Paint.FontMetrics labelFm = labelPaint.getFontMetrics();
        float labelBaseline = h * 0.3f - (labelFm.ascent + labelFm.descent) / 2f;
        canvas.drawText(label, centerX, labelBaseline, labelPaint);

        // Line 2: LED digits
        // Fractional digits are 20% the size of integer digits
        float fractScale = 0.8f;
        float fractW = digitW * fractScale;
        float fractH = digitH * fractScale;
        float fractThick = segThick * fractScale;
        float fractGap = segGap * fractScale;
        float fractDigitGap = digitGap * fractScale;

        float totalDigitWidth = 0;
        int intDigitCount = 0;
        int fractDigitCount = 0;
        boolean pastDecimal = false;
        for (int i = 0; i < numStr.length(); i++) {
            char c = numStr.charAt(i);
            if (c == '.') { pastDecimal = true; totalDigitWidth += digitW * 0.4f; }
            else if (c == ',') { totalDigitWidth += digitW * 0.4f; }
            else if (c >= '0' && c <= '9') {
                if (pastDecimal) { totalDigitWidth += fractW; fractDigitCount++; }
                else { totalDigitWidth += digitW; intDigitCount++; }
            }
        }
        if (intDigitCount > 1) totalDigitWidth += (intDigitCount - 1) * digitGap;
        if (fractDigitCount > 1) totalDigitWidth += (fractDigitCount - 1) * fractDigitGap;

        float ledTop = h * 0.48f;
        float fractTop = ledTop + digitH - fractH;  // bottom-aligned with integer digits
        float dx = centerX - totalDigitWidth / 2f;

        // Find decimal point position for red coloring of fractional digits
        int decimalPos = numStr.indexOf('.');

        pastDecimal = false;
        for (int i = 0; i < numStr.length(); i++) {
            char c = numStr.charAt(i);
            if (c == '.') pastDecimal = true;
            // Digits after decimal point use red color (mileage modes only)
            boolean isFractionDigit = (mode != MODE_FUEL && mode != MODE_RANGE && mode != MODE_PERCENT) && (decimalPos >= 0) && (i > decimalPos) && (c >= '0' && c <= '9');
            int digitColor = isFractionDigit ? 0xFFFF4444 : activeColor;
            int digitGlow = isFractionDigit ? 0xFFCC0000 : activeGlow;

            if (c >= '0' && c <= '9') {
                if (pastDecimal) {
                    LEDDigitHelper.drawLEDDigit(canvas, dx, fractTop, fractW, fractH, fractThick, fractGap,
                            c - '0', digitColor, digitGlow, alpha);
                    dx += fractW;
                    if (i < numStr.length() - 1) {
                        char nextC = numStr.charAt(i + 1);
                        if (nextC >= '0' && nextC <= '9') dx += fractDigitGap;
                    }
                } else {
                    LEDDigitHelper.drawLEDDigit(canvas, dx, ledTop, digitW, digitH, segThick, segGap,
                            c - '0', digitColor, digitGlow, alpha);
                    dx += digitW;
                    if (i < numStr.length() - 1) {
                        char nextC = numStr.charAt(i + 1);
                        if (nextC >= '0' && nextC <= '9') dx += digitGap;
                    }
                }
            } else if (c == '.') {
                ledPaint.setColor(digitColor);
                ledPaint.setShadowLayer(segThick * 1.5f, 0, 0, digitGlow);
                ledPaint.setAlpha(alpha);
                canvas.drawCircle(dx + digitW * 0.2f, ledTop + digitH - segThick * 0.5f, segThick * 0.5f, ledPaint);
                ledPaint.clearShadowLayer();
                dx += digitW * 0.4f;
            } else if (c == ',') {
                ledPaint.setColor(activeColor);
                ledPaint.setShadowLayer(segThick, 0, 0, activeGlow);
                ledPaint.setAlpha(alpha);
                canvas.drawCircle(dx + digitW * 0.2f, ledTop + digitH * 0.65f, segThick * 0.3f, ledPaint);
                ledPaint.clearShadowLayer();
                dx += digitW * 0.4f;
            }
        }
    }
}
