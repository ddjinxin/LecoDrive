package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * LED七段数码管显示 — 完全对齐原项目 高德辅助导航 LEDDigitView
 * 与普通TextView组合使用：[LEDDigitView(数字)] [TextView(单位+进入+道路名)]
 */
public class LEDDigitView extends View {

    private String displayText = "";
    private boolean isNightMode = false;
    private int customColor = 0;  // 0=不覆盖，非0=覆盖默认夜景/白景颜色

    private Paint segPaint;
    private Paint glowPaint;

    public LEDDigitView(Context context) {
        super(context);
        init();
    }

    public LEDDigitView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LEDDigitView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        segPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        segPaint.setStyle(Paint.Style.FILL);

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * 设置显示文本（数字+小数点），如 "500" "1.2"
     * 非数字字符（如"现在"）会用小号普通文字绘制
     */
    public void setText(String text) {
        this.displayText = text != null ? text : "";
        requestLayout();
        invalidate();
    }

    public void setNightMode(boolean nightMode) {
        this.isNightMode = nightMode;
        invalidate();
    }

    /**
     * 设置自定义颜色覆盖（0=恢复默认夜/白景颜色）
     */
    public void setCustomColor(int color) {
        this.customColor = color;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        float digitH = 28 * density;   // 数字高度 28dp
        float digitW = digitH * 0.5f;   // 数字宽度
        float dotW = 4 * density;        // 小数点宽度
        float digitGap = 2 * density;    // 数字间距

        // 计算是否全为数字/小数点
        boolean allDigits = true;
        for (char c : displayText.toCharArray()) {
            if (!Character.isDigit(c) && c != '.') {
                allDigits = false;
                break;
            }
        }

        float totalWidth;
        if (allDigits && displayText.length() > 0) {
            totalWidth = 0;
            for (int i = 0; i < displayText.length(); i++) {
                if (displayText.charAt(i) == '.') {
                    totalWidth += dotW;
                } else {
                    totalWidth += digitW;
                }
                if (i > 0) totalWidth += digitGap;
            }
        } else {
            // 非数字文本（如"现在"），估算宽度
            float textSize = 15 * density;
            totalWidth = displayText.length() * textSize * 0.7f;
        }

        int w = allDigits && displayText.length() > 0 ? (int) totalWidth : (int) Math.max(totalWidth, digitW);
        int h = (int) digitH;
        setMeasuredDimension(
                resolveSize(w, widthMeasureSpec),
                resolveSize(h, heightMeasureSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (displayText == null || displayText.isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        float digitH = 28 * density;
        float digitW = digitH * 0.5f;
        float segThick = digitH * 0.14f;
        float segGap = digitH * 0.06f;
        float digitGap = 2 * density;
        float dotRadius = segThick * 0.4f;

        // 检查是否全为数字/小数点
        boolean allDigits = true;
        for (char c : displayText.toCharArray()) {
            if (!Character.isDigit(c) && c != '.') {
                allDigits = false;
                break;
            }
        }

        // 颜色：自定义覆盖 > 白天青色/夜间绿色
        int activeColor, activeGlow;
        if (customColor != 0) {
            activeColor = customColor;
            activeGlow = (customColor & 0x00FFFFFF) | 0x66000000;
        } else {
            activeColor = isNightMode ? 0xFF00E5A0 : 0xFF00D4E8;
            activeGlow = isNightMode ? 0xFF009966 : 0x3000D4E8;
        }

        if (!allDigits) {
            // 非数字文本：用发光文字绘制
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(activeColor);
            textPaint.setTextSize(15 * density);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textPaint.setShadowLayer(4 * density, 0, 0, activeGlow);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textY = digitH / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(displayText, 0, textY, textPaint);
            return;
        }

        // 逐位绘制LED数码管
        float x = 0;
        for (int i = 0; i < displayText.length(); i++) {
            char c = displayText.charAt(i);
            if (c == '.') {
                // 小数点
                segPaint.setColor(activeColor);
                segPaint.setShadowLayer(segThick * 1.5f, 0, 0, activeGlow);
                canvas.drawCircle(x + dotRadius, digitH - segThick, dotRadius, segPaint);
                segPaint.clearShadowLayer();
                x += dotRadius * 2 + digitGap * 0.5f;
            } else if (Character.isDigit(c)) {
                LEDDigitHelper.drawLEDDigit(canvas, x, 0, digitW, digitH, segThick, segGap, c - '0', activeColor, activeGlow);
                x += digitW + digitGap;
            }
        }
    }

}
