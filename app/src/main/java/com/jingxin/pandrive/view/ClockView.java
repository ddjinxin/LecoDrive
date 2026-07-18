package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.animation.ValueAnimator;

import java.util.Calendar;

/**
 * 科幻金属风圆形时钟
 *
 * 边框和视觉效果与 CompassView 完全一致：
 * - 外圈：金属渐变弧线 + LED刻度点
 * - 内部：时针/分针/秒针 + 中心螺丝帽 + 数字时间
 */
public class ClockView extends View {

    private final GaugeDrawHelper gaugeHelper = new GaugeDrawHelper();

    // 仪表盘尺寸
    private float viewWidth, viewHeight;
    private float gaugeSize;
    private float centerX, centerY;
    private float outerRadius;
    private float arcStrokeWidth;

    // 昼夜模式
    private boolean isNightMode = false;

    // 风格：0=科幻金属（默认），1=极简弧线
    private int style = 0;

    // Paint缓存标记
    private boolean paintsDirty = true;

    // 流光线动画
    private ValueAnimator shimmerAnimator;
    private float shimmerPhase = 0f;

    // 颜色变量
    private int colorLedOn, colorLedOnGlow, colorLedOff;
    private int colorOuterRing, colorTickNumber;
    private int colorArcBg, colorArcHighlight, colorArcShadow;

    // 动画：每秒刷新
    private final Runnable tickRunnable = this::tick;
    private static final long TICK_INTERVAL_MS = 1000; // 1秒刷新

    public ClockView(Context context) { super(context); startShimmerAnimation(); }
    public ClockView(Context context, AttributeSet attrs) { super(context, attrs); startShimmerAnimation(); }
    public ClockView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); startShimmerAnimation(); }

    private void startShimmerAnimation() {
        if (shimmerAnimator != null) return;
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
        shimmerAnimator.setDuration(3000);
        shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shimmerAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        shimmerAnimator.addUpdateListener(a -> {
            shimmerPhase = (float) a.getAnimatedValue();
            invalidate();
        });
        shimmerAnimator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (widthMode == MeasureSpec.UNSPECIFIED) {
            width = (int) (250 * getResources().getDisplayMetrics().density);
        }
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            height = width;
        }
        int size = Math.min(width, height);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        viewWidth = w;
        viewHeight = h;
        gaugeSize = Math.min(w, h);
        centerX = gaugeSize / 2f;
        centerY = gaugeSize * 0.5f;
        outerRadius = gaugeSize * 0.36f;
        arcStrokeWidth = gaugeSize * 0.0632f;
        paintsDirty = true;
    }

    private void updateColors() {
        if (!paintsDirty) return;

        if (isNightMode) {
            colorLedOn = 0xFF00E5A0;
            colorLedOnGlow = 0x6000E5A0;
            colorLedOff = 0xFF1A2030;
            colorTickNumber = 0xFFFFFFFF;
            colorArcBg = 0xFF8899AA;
            colorArcHighlight = 0xFFBBC8D4;
            colorArcShadow = 0xFF4A5A6A;
            colorOuterRing = 0xFF99AABB;
        } else {
            colorLedOn = 0xFF00D4E8;
            colorLedOnGlow = 0x3000D4E8;
            colorLedOff = 0xFF3A4050;
            colorTickNumber = 0xFF000000;
            colorArcBg = 0xFF556070;
            colorArcHighlight = 0xFFA0ADB8;
            colorArcShadow = 0xFF2A3540;
            colorOuterRing = 0xFF8899AA;
        }
        paintsDirty = false;
    }

    public void setNightMode(boolean nightMode) {
        if (this.isNightMode != nightMode) {
            this.isNightMode = nightMode;
            paintsDirty = true;
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(tickRunnable, TICK_INTERVAL_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(tickRunnable);
    }

    private void tick() {
        invalidate();
        postDelayed(tickRunnable, TICK_INTERVAL_MS);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateColors();

        if (style == 0) {
            drawOuterRing(canvas);
            drawShimmerArc(canvas);
            drawMetallicArc(canvas);
            drawDiskGlow(canvas);
            drawLEDTicks(canvas);
            drawHourMarks(canvas);
            drawHourNumbers(canvas);
            drawHourHand(canvas);
            drawMinuteHand(canvas);
            drawSecondHand(canvas);
            drawCenterCap(canvas);
        } else {
            // 风格1：极简弧线
            drawMinimalDiskGlow(canvas);
            drawMinimalFadeArcs(canvas);
            drawMinimalHourMarks(canvas);
            drawMinimalHourNumbers(canvas);
            drawMinimalSecondHand(canvas);
            drawMinimalMinuteHand(canvas);
            drawMinimalHourHand(canvas);
            drawCenterCap(canvas);
        }
    }

    // ========== 绘制方法（与CompassView一致的边框） ==========

    private void drawOuterRing(Canvas canvas) {
        float outerR = outerRadius + arcStrokeWidth * 0.6f;
        gaugeHelper.drawOuterRing(canvas, centerX, centerY, outerR, colorOuterRing, gaugeSize);
    }

    /**
     * 流光线：沿外弧流动的光带（360°完整圆）
     */
    private void drawShimmerArc(Canvas canvas) {
        float shimmerR = outerRadius + arcStrokeWidth * 0.6f + gaugeSize * 0.008f;
        gaugeHelper.drawShimmerArc(canvas, centerX, centerY, shimmerR, gaugeSize, shimmerPhase, isNightMode);
    }

    private void drawMetallicArc(Canvas canvas) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(arcStrokeWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        SweepGradient metalGradient = new SweepGradient(
            centerX, centerY,
            new int[]{
                colorArcHighlight, colorArcBg, colorArcShadow,
                colorArcBg, colorArcHighlight, colorArcShadow,
                colorArcHighlight
            },
            new float[]{ 0f, 0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 1f }
        );
        paint.setShader(metalGradient);
        RectF rect = new RectF(centerX - outerRadius, centerY - outerRadius, centerX + outerRadius, centerY + outerRadius);
        canvas.drawArc(rect, 0, 360, false, paint);
    }

    private void drawDiskGlow(Canvas canvas) {
        float outerEdge = outerRadius - arcStrokeWidth / 2f;
        gaugeHelper.drawDiskGlow(canvas, centerX, centerY, outerEdge, gaugeSize, isNightMode);
    }

    private void drawLEDTicks(Canvas canvas) {
        float arcHalfWidth = arcStrokeWidth / 2f;
        float minorPointR = arcHalfWidth * 0.15f;

        Calendar cal = Calendar.getInstance();
        int currentSecond = cal.get(Calendar.SECOND);

        // 每隔30度一个LED点（与秒针联动，当前秒位置附近的点亮）
        for (int deg = 0; deg < 360; deg += 6) {
            // 跳过小时标记位置（每30度）
            if (deg % 30 == 0) continue;

            // 计算秒针与这个点的夹角差
            int secondDeg = currentSecond * 6; // 每秒6度
            int diff = Math.abs(secondDeg - deg);
            if (diff > 180) diff = 360 - diff;
            boolean isActive = (diff <= 6);

            double rad = Math.toRadians(deg - 90);
            float px = centerX + (float) (outerRadius * Math.cos(rad));
            float py = centerY + (float) (outerRadius * Math.sin(rad));

            Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            pointPaint.setStyle(Paint.Style.FILL);

            if (isActive) {
                Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                glowPaint.setStyle(Paint.Style.FILL);
                float glowR = arcHalfWidth * 0.45f;
                glowPaint.setShader(new android.graphics.RadialGradient(
                        px, py, glowR,
                        colorLedOn, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
                canvas.drawCircle(px, py, glowR, glowPaint);
                pointPaint.setColor(colorLedOn);
            } else {
                pointPaint.setColor(colorLedOff);
            }
            canvas.drawCircle(px, py, minorPointR, pointPaint);
        }
    }

    // ========== 时钟特有绘制 ==========

    private void drawHourMarks(Canvas canvas) {
        float innerEdge = outerRadius - arcStrokeWidth / 2f;
        float gap = gaugeSize * 0.02f;
        float majorLen = gaugeSize * 0.04f;
        float minorLen = gaugeSize * 0.02f;

        Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < 60; i++) {
            float deg = i * 6f;
            double rad = Math.toRadians(deg - 90);
            boolean isHour = (i % 5 == 0);
            float len = isHour ? majorLen : minorLen;
            float outerR = innerEdge - gap;
            float innerR = outerR - len;

            float x1 = centerX + (float) (outerR * Math.cos(rad));
            float y1 = centerY + (float) (outerR * Math.sin(rad));
            float x2 = centerX + (float) (innerR * Math.cos(rad));
            float y2 = centerY + (float) (innerR * Math.sin(rad));

            if (isHour) {
                markPaint.setColor(colorTickNumber);
                markPaint.setStrokeWidth(Math.max(1f, gaugeSize * 0.006f));
            } else {
                markPaint.setColor(isNightMode ? 0xFF4A5A6A : 0xFF99AABB);
                markPaint.setStrokeWidth(Math.max(1f, gaugeSize * 0.003f));
            }
            canvas.drawLine(x1, y1, x2, y2, markPaint);
        }
    }

    private void drawHourNumbers(Canvas canvas) {
        float innerEdge = outerRadius - arcStrokeWidth / 2f;
        float gap = gaugeSize * 0.02f;
        float majorLen = gaugeSize * 0.04f;
        float numR = innerEdge - gap - majorLen - gaugeSize * 0.03f;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(gaugeSize * 0.055f);
        paint.setColor(colorTickNumber);

        for (int h = 1; h <= 12; h++) {
            float deg = h * 30f;
            double rad = Math.toRadians(deg - 90);
            float nx = centerX + (float) (numR * Math.cos(rad));
            float ny = centerY + (float) (numR * Math.sin(rad));
            Paint.FontMetrics fm = paint.getFontMetrics();
            float textY = ny - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(String.valueOf(h), nx, textY, paint);
        }
    }

    private void drawHourHand(Canvas canvas) {
        Calendar cal = Calendar.getInstance();
        float hourAngle = (cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f) * 30f;

        float innerEdge = outerRadius - arcStrokeWidth / 2f;
        float handLen = innerEdge * 0.5f;
        float handWidth = gaugeSize * 0.02f;

        canvas.save();
        canvas.rotate(hourAngle, centerX, centerY);

        Path handPath = new Path();
        handPath.moveTo(centerX, centerY - handLen);
        handPath.lineTo(centerX - handWidth, centerY + gaugeSize * 0.02f);
        handPath.lineTo(centerX + handWidth, centerY + gaugeSize * 0.02f);
        handPath.close();

        Paint handPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handPaint.setStyle(Paint.Style.FILL);
        int highlight = isNightMode ? 0xFF8899AA : 0xFFA0ADB8;
        int shadow = isNightMode ? 0xFF445566 : 0xFF556677;
        handPaint.setShader(new LinearGradient(centerX, centerY - handLen, centerX, centerY,
                shadow, highlight, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawPath(handPath, handPaint);

        canvas.restore();
    }

    private void drawMinuteHand(Canvas canvas) {
        Calendar cal = Calendar.getInstance();
        float minuteAngle = (cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f) * 6f;

        float innerEdge = outerRadius - arcStrokeWidth / 2f;
        float handLen = innerEdge * 0.7f;
        float handWidth = gaugeSize * 0.014f;

        canvas.save();
        canvas.rotate(minuteAngle, centerX, centerY);

        Path handPath = new Path();
        handPath.moveTo(centerX, centerY - handLen);
        handPath.lineTo(centerX - handWidth, centerY + gaugeSize * 0.02f);
        handPath.lineTo(centerX + handWidth, centerY + gaugeSize * 0.02f);
        handPath.close();

        Paint handPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handPaint.setStyle(Paint.Style.FILL);
        int highlight = isNightMode ? 0xFF99AABB : 0xFFBBC8D4;
        int shadow = isNightMode ? 0xFF556677 : 0xFF667788;
        handPaint.setShader(new LinearGradient(centerX, centerY - handLen, centerX, centerY,
                shadow, highlight, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawPath(handPath, handPaint);

        canvas.restore();
    }

    private void drawSecondHand(Canvas canvas) {
        Calendar cal = Calendar.getInstance();
        float secondAngle = cal.get(Calendar.SECOND) * 6f;

        float innerEdge = outerRadius - arcStrokeWidth / 2f;
        float handLen = innerEdge * 0.78f;
        float tailLen = gaugeSize * 0.04f;

        canvas.save();
        canvas.rotate(secondAngle, centerX, centerY);

        Paint secPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        secPaint.setStyle(Paint.Style.STROKE);
        secPaint.setColor(0xFFFF3333);
        secPaint.setStrokeWidth(Math.max(1f, gaugeSize * 0.004f));
        secPaint.setStrokeCap(Paint.Cap.ROUND);
        secPaint.setShadowLayer(gaugeSize * 0.008f, 0, 0, 0x60FF3333);

        canvas.drawLine(centerX, centerY + tailLen, centerX, centerY - handLen, secPaint);

        // 秒针尖端小圆点
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xFFFF3333);
        canvas.drawCircle(centerX, centerY - handLen, gaugeSize * 0.008f, dotPaint);

        canvas.restore();
    }

    private void drawCenterCap(Canvas canvas) {
        gaugeHelper.drawCenterCap(canvas, centerX, centerY, gaugeSize, isNightMode);
    }


    // ========== 风格切换 ==========

    public void toggleStyle() {
        style = (style + 1) % 2;
        invalidate();
    }

    public int getStyle() {
        return style;
    }

    // ========== 风格1：极简弧线（参照 CompassViewMinimal） ==========

    private void drawMinimalDiskGlow(Canvas canvas) {
        gaugeHelper.drawDiskGlowMinimal(canvas, centerX, centerY, outerRadius, gaugeSize, isNightMode);
    }

    /**
     * 三段渐隐弧线（与 CompassViewMinimal.drawFadeArcs 一致）
     * 第一圈：12点方向为中心，上下各55°
     * 第二圈：右上段 + 左下段
     */
    private void drawMinimalFadeArcs(Canvas canvas) {
        int cR = 0x00;
        int cG = isNightMode ? 0xE5 : 0xD4;
        int cB = isNightMode ? 0xA0 : 0xE8;

        float r1 = outerRadius + gaugeSize * 0.02f;
        float stroke1 = gaugeSize * 0.005f;
        gaugeHelper.drawFadeArc(canvas, centerX, centerY, r1, stroke1, -145f, 110f, 15f, cR, cG, cB, 255);

        float r1g = r1 + gaugeSize * 0.006f;
        gaugeHelper.drawFadeArc(canvas, centerX, centerY, r1g, gaugeSize * 0.012f, -145f, 110f, 15f, cR, cG, cB, 40);

        float r2 = outerRadius - gaugeSize * 0.02f;
        float stroke2 = gaugeSize * 0.004f;
        float fade2 = 10f;

        gaugeHelper.drawFadeArc(canvas, centerX, centerY, r2, stroke2, -50f, 40f, fade2, cR, cG, cB, 255);
        gaugeHelper.drawFadeArc(canvas, centerX, centerY, r2, stroke2, 130f, 40f, fade2, cR, cG, cB, 255);
    }



    private void drawMinimalHourMarks(Canvas canvas) {
        float innerEdge = outerRadius;
        float gap = gaugeSize * 0.02f;
        float majorLen = gaugeSize * 0.04f;
        float minorLen = gaugeSize * 0.02f;

        Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < 60; i++) {
            float deg = i * 6f;
            double rad = Math.toRadians(deg - 90);
            boolean isHour = (i % 5 == 0);
            float len = isHour ? majorLen : minorLen;
            float outerR = innerEdge - gap;
            float innerR = outerR - len;

            float x1 = centerX + (float) (outerR * Math.cos(rad));
            float y1 = centerY + (float) (outerR * Math.sin(rad));
            float x2 = centerX + (float) (innerR * Math.cos(rad));
            float y2 = centerY + (float) (innerR * Math.sin(rad));

            if (isHour) {
                markPaint.setColor(isNightMode ? 0xFFFFFFFF : 0xFF000000);
                markPaint.setStrokeWidth(Math.max(1f, gaugeSize * 0.006f));
            } else {
                markPaint.setColor(isNightMode ? 0xFF4A5A6A : 0xFF99AABB);
                markPaint.setStrokeWidth(Math.max(1f, gaugeSize * 0.003f));
            }
            canvas.drawLine(x1, y1, x2, y2, markPaint);
        }
    }

    private void drawMinimalHourNumbers(Canvas canvas) {
        float innerEdge = outerRadius;
        float gap = gaugeSize * 0.02f;
        float majorLen = gaugeSize * 0.04f;
        float numR = innerEdge - gap - majorLen - gaugeSize * 0.03f;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(gaugeSize * 0.055f);
        paint.setColor(isNightMode ? 0xFFFFFFFF : 0xFF000000);

        for (int h = 1; h <= 12; h++) {
            float deg = h * 30f;
            double rad = Math.toRadians(deg - 90);
            float nx = centerX + (float) (numR * Math.cos(rad));
            float ny = centerY + (float) (numR * Math.sin(rad));
            Paint.FontMetrics fm = paint.getFontMetrics();
            float textY = ny - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(String.valueOf(h), nx, textY, paint);
        }
    }

    private void drawMinimalHourHand(Canvas canvas) {
        Calendar cal = Calendar.getInstance();
        float hourAngle = (cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f) * 30f;

        float handLen = outerRadius * 0.5f;
        float handWidth = gaugeSize * 0.02f;

        canvas.save();
        canvas.rotate(hourAngle, centerX, centerY);

        Path handPath = new Path();
        handPath.moveTo(centerX, centerY - handLen);
        handPath.lineTo(centerX - handWidth, centerY + gaugeSize * 0.02f);
        handPath.lineTo(centerX + handWidth, centerY + gaugeSize * 0.02f);
        handPath.close();

        Paint handPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handPaint.setStyle(Paint.Style.FILL);
        int highlight = isNightMode ? 0xFF8899AA : 0xFFA0ADB8;
        int shadow = isNightMode ? 0xFF445566 : 0xFF556677;
        handPaint.setShader(new LinearGradient(centerX, centerY - handLen, centerX, centerY,
                shadow, highlight, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawPath(handPath, handPaint);

        canvas.restore();
    }

    private void drawMinimalMinuteHand(Canvas canvas) {
        Calendar cal = Calendar.getInstance();
        float minuteAngle = (cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f) * 6f;

        float handLen = outerRadius * 0.7f;
        float handWidth = gaugeSize * 0.014f;

        canvas.save();
        canvas.rotate(minuteAngle, centerX, centerY);

        Path handPath = new Path();
        handPath.moveTo(centerX, centerY - handLen);
        handPath.lineTo(centerX - handWidth, centerY + gaugeSize * 0.02f);
        handPath.lineTo(centerX + handWidth, centerY + gaugeSize * 0.02f);
        handPath.close();

        Paint handPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handPaint.setStyle(Paint.Style.FILL);
        int highlight = isNightMode ? 0xFF99AABB : 0xFFBBC8D4;
        int shadow = isNightMode ? 0xFF556677 : 0xFF667788;
        handPaint.setShader(new LinearGradient(centerX, centerY - handLen, centerX, centerY,
                shadow, highlight, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawPath(handPath, handPaint);

        canvas.restore();
    }

    private void drawMinimalSecondHand(Canvas canvas) {
        Calendar cal = Calendar.getInstance();
        float secondAngle = cal.get(Calendar.SECOND) * 6f;

        float handLen = outerRadius * 0.78f;
        float tailLen = gaugeSize * 0.04f;

        canvas.save();
        canvas.rotate(secondAngle, centerX, centerY);

        Paint secPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        secPaint.setStyle(Paint.Style.STROKE);
        secPaint.setColor(0xFFFF3333);
        secPaint.setStrokeWidth(Math.max(1f, gaugeSize * 0.004f));
        secPaint.setStrokeCap(Paint.Cap.ROUND);
        secPaint.setShadowLayer(gaugeSize * 0.008f, 0, 0, 0x60FF3333);

        canvas.drawLine(centerX, centerY + tailLen, centerX, centerY - handLen, secPaint);

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xFFFF3333);
        canvas.drawCircle(centerX, centerY - handLen, gaugeSize * 0.008f, dotPaint);

        canvas.restore();
    }

}
