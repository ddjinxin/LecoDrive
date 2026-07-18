package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Section 1: 日期时间LED显示
 * 两行：日期(年月日 星期) + 时间(时:分:秒, LED七段数码管风格)
 * 无手动切换按钮
 */
public class DateTimeView extends View {

    private boolean isNightMode = false;

    // Colors
    private int colorDateText;
    private int colorLedActive;
    private int colorLedGlow;

    // Paints
    private Paint datePaint;
    private Paint ledPaint;
    private Paint ledGlowPaint;

    // Time formatting
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA);
    private final Calendar calendar = Calendar.getInstance();

    // Update handler
    private final android.os.Handler timeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable timeRunnable = new Runnable() {
        @Override
        public void run() {
            invalidate();
            timeHandler.postDelayed(this, 1000); // update every second
        }
    };

    public DateTimeView(Context context) {
        super(context);
        init();
    }

    public DateTimeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DateTimeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        datePaint.setTextAlign(Paint.Align.CENTER);

        ledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ledPaint.setStyle(Paint.Style.FILL);

        ledGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ledGlowPaint.setStyle(Paint.Style.FILL);

        updateColors();
    }

    public void setNightMode(boolean isNight) {
        this.isNightMode = isNight;
        updateColors();
        invalidate();
    }

    private void updateColors() {
        colorDateText = isNightMode ? 0xFF00E5A0 : 0xFF000000;
        colorLedActive = isNightMode ? 0xFF00E5A0 : 0xFF000000;
        colorLedGlow = isNightMode ? 0x6000E5A0 : 0x33000000;

        datePaint.setColor(colorDateText);
        ledPaint.setColor(colorLedActive);
        ledGlowPaint.setColor(colorLedGlow);
        ledPaint.setShadowLayer(8f, 0, 0, colorLedGlow);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        timeHandler.post(timeRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        timeHandler.removeCallbacks(timeRunnable);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Draw date text
        calendar.setTimeInMillis(System.currentTimeMillis());
        String dateStr = dateFormat.format(new Date());
        float dateSize = h * 0.18f;
        datePaint.setTextSize(dateSize);
        float dateY = h * 0.35f;
        canvas.drawText(dateStr, w / 2f, dateY, datePaint);

        // Draw time in LED style (HH:MM:SS)
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        int second = calendar.get(Calendar.SECOND);

        float ledH = h * 0.38f;   // height of each digit
        float ledW = ledH * 0.55f; // width of each digit
        float segThick = ledH * 0.14f;
        float segGap = ledH * 0.06f;
        float gap = ledH * 0.12f;  // gap between digits
        float colonW = ledH * 0.15f; // colon width

        // Total width: 6 digits + 2 colons + gaps
        float totalW = 6 * ledW + 2 * colonW + 7 * gap;
        float startX = (w - totalW) / 2f;
        float ledY = h * 0.48f;

        float x = startX;
        // HH
        LEDDigitHelper.drawLEDDigit(canvas, x, ledY, ledW, ledH, segThick, segGap, hour / 10, colorLedActive, colorLedGlow);
        x += ledW + gap;
        LEDDigitHelper.drawLEDDigit(canvas, x, ledY, ledW, ledH, segThick, segGap, hour % 10, colorLedActive, colorLedGlow);
        x += ledW + gap;
        // :
        drawColon(canvas, x, ledY, colonW, ledH);
        x += colonW + gap;
        // MM
        LEDDigitHelper.drawLEDDigit(canvas, x, ledY, ledW, ledH, segThick, segGap, minute / 10, colorLedActive, colorLedGlow);
        x += ledW + gap;
        LEDDigitHelper.drawLEDDigit(canvas, x, ledY, ledW, ledH, segThick, segGap, minute % 10, colorLedActive, colorLedGlow);
        x += ledW + gap;
        // :
        drawColon(canvas, x, ledY, colonW, ledH);
        x += colonW + gap;
        // SS
        LEDDigitHelper.drawLEDDigit(canvas, x, ledY, ledW, ledH, segThick, segGap, second / 10, colorLedActive, colorLedGlow);
        x += ledW + gap;
        LEDDigitHelper.drawLEDDigit(canvas, x, ledY, ledW, ledH, segThick, segGap, second % 10, colorLedActive, colorLedGlow);
    }

    private void drawColon(Canvas canvas, float x, float y, float w, float h) {
        float dotR = w * 0.4f;
        float cx = x + w / 2f;
        float y1 = y + h * 0.3f;
        float y2 = y + h * 0.7f;
        ledPaint.setColor(colorLedActive);
        ledPaint.setShadowLayer(dotR * 2f, 0, 0, colorLedGlow);
        canvas.drawCircle(cx, y1, dotR, ledPaint);
        canvas.drawCircle(cx, y2, dotR, ledPaint);
    }
}
