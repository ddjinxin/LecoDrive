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

/**
 * 科幻金属风指南针自定义View
 * 
 * 布局：
 * - 外圈：金属渐变弧线（和速度仪表盘一致）+ 弧线端盖 + LED刻度点
 * - 内部：方位文字(N/E/S/W) + 指针 + 中心螺丝帽 + 角度 + 经纬度
 * 
 * 表盘固定，指针旋转，LED点跟随指针位置发光
 */
public class CompassView extends View implements ICompassView {

    private final GaugeDrawHelper gaugeHelper = new GaugeDrawHelper();

    // 仪表盘尺寸
    private float viewWidth, viewHeight;
    private float gaugeSize;
    private float centerX, centerY;
    private float outerRadius;    // 弧线半径
    private float arcStrokeWidth; // 弧线粗细

    // 方向数据
    public float currentAzimuth = 0f;

    // 位置数据
    private double latitude = Double.NaN;
    private double longitude = Double.NaN;
    private double altitude = Double.NaN;

    // 昼夜模式
    private boolean isNightMode = false;

    // Paint缓存标记
    private boolean paintsDirty = true;

    // 流光线动画
    private ValueAnimator shimmerAnimator;
    private float shimmerPhase = 0f;

    // 颜色变量（昼夜切换时更新）
    private int colorLedOn, colorLedOnGlow, colorLedOff, colorLedDanger;
    private int colorOuterRing, colorTickNumber;
    private int colorArcBg, colorArcHighlight, colorArcShadow;

    public CompassView(Context context) { super(context); startShimmerAnimation(); }
    public CompassView(Context context, AttributeSet attrs) { super(context, attrs); startShimmerAnimation(); }
    public CompassView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); startShimmerAnimation(); }

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
        float density = getResources().getDisplayMetrics().density;
        // 未指定时用默认值
        if (widthMode == MeasureSpec.UNSPECIFIED) {
            width = (int) (250 * density);
        }
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            height = width;
        }
        // 取宽高较小值，确保正方形且不超出容器
        int size = Math.min(width, height);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        viewWidth = w;
        viewHeight = h;
        gaugeSize = w;
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
            colorLedDanger = 0xFFFF3333;
            colorTickNumber = 0xFFFFFFFF;
            colorArcBg = 0xFF8899AA;
            colorArcHighlight = 0xFFBBC8D4;
            colorArcShadow = 0xFF4A5A6A;
            colorOuterRing = 0xFF99AABB;
        } else {
            colorLedOn = 0xFF00D4E8;
            colorLedOnGlow = 0x3000D4E8;
            colorLedOff = 0xFF3A4050;
            colorLedDanger = 0xFFFF3333;
            colorTickNumber = 0xFF000000;
            colorArcBg = 0xFF556070;
            colorArcHighlight = 0xFFA0ADB8;
            colorArcShadow = 0xFF2A3540;
            colorOuterRing = 0xFF8899AA;
        }
        paintsDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateColors();

        // 1. 外圈亮银描边
        drawOuterRing(canvas);
        // 1.5 流光线
        drawShimmerArc(canvas);
        // 2. 金属渐变弧线（完整360度）
        drawMetallicArc(canvas);
        // 3. 圆盘内部发光渐变
        drawDiskGlow(canvas);
        // 5. LED刻度点（指针指到的才亮）
        drawLEDTicks(canvas);
        // 6. 方位文字
        drawDirections(canvas);
         // 7. 指针
         drawNeedle(canvas);
         // 8. 中心螺丝帽
         drawCenterCap(canvas);
         // 9. 位置信息
         drawLocationInfo(canvas);
    }

    // ========== 绘制方法 ==========

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
                colorArcHighlight,  // 0° 起点：高光
                colorArcBg,         // 中段：中间色
                colorArcShadow,     // 1/3处：暗面
                colorArcBg,         // 中段：中间色
                colorArcHighlight,  // 2/3处：高光
                colorArcShadow,     // 末段：暗面
                colorArcHighlight   // 回到起点
            },
            new float[]{
                0f,
                0.1f,
                0.3f,
                0.5f,
                0.7f,
                0.9f,
                1f
            }
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
        float majorPointR = arcHalfWidth * 0.25f;
        float minorPointR = arcHalfWidth * 0.15f;
        float majorGlowR = arcHalfWidth * 0.65f;
        float minorGlowR = arcHalfWidth * 0.45f;

        // 指针当前指向的角度（0度=北，顺时针增加）
        int needleDeg = Math.round(currentAzimuth) % 360;
        if (needleDeg < 0) needleDeg += 360;

        // 每隔10度一个LED点，跳过方位文字位置（每45度）
        for (int deg = 0; deg < 360; deg += 10) {
            if (deg % 45 == 0) continue; // 跳过方位文字位置

            boolean isMajor = (deg % 30 == 0);
            float pointR = isMajor ? majorPointR : minorPointR;
            float glowR = isMajor ? majorGlowR : minorGlowR;

            // 计算指针与这个点的夹角差
            int diff = Math.abs(needleDeg - deg);
            if (diff > 180) diff = 360 - diff;
            // 指针指向±5度范围内的点亮
            boolean isActive = (diff <= 5);

            double rad = Math.toRadians(deg - 90);
            float px = centerX + (float) (outerRadius * Math.cos(rad));
            float py = centerY + (float) (outerRadius * Math.sin(rad));

            Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            pointPaint.setStyle(Paint.Style.FILL);

            if (isActive) {
                // 发光光晕
                Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                glowPaint.setStyle(Paint.Style.FILL);
                glowPaint.setShader(new android.graphics.RadialGradient(
                        px, py, glowR,
                        colorLedOn, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
                canvas.drawCircle(px, py, glowR, glowPaint);

                pointPaint.setColor(colorLedOn);
            } else {
                pointPaint.setColor(colorLedOff);
            }
            canvas.drawCircle(px, py, pointR, pointPaint);
        }
    }

    private void drawDirections(Canvas canvas) {
        String[] dirs = {"N", "E", "S", "W"};
        int[] degs = {0, 90, 180, 270};
        // 字母放在弧线内框内侧
        float innerEdge = outerRadius - arcStrokeWidth / 2f;
        float gap = gaugeSize * 0.05f;
        float letterR = innerEdge - gap;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(gaugeSize * 0.07f);

        for (int i = 0; i < 4; i++) {
            double rad = Math.toRadians(degs[i] - 90);
            float letterX = centerX + (float) (letterR * Math.cos(rad));
            float letterY = centerY + (float) (letterR * Math.sin(rad));

            // N 红色，其他跟随昼夜模式
            if (i == 0) {
                paint.setColor(0xFFCC3333);
            } else {
                paint.setColor(colorTickNumber);
            }

            Paint.FontMetrics fm = paint.getFontMetrics();
            float textY = letterY - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(dirs[i], letterX, textY, paint);
        }
    }

    private void drawNeedle(Canvas canvas) {
        float innerEdge = outerRadius - arcStrokeWidth / 2f;
        float capR = gaugeSize * 0.03f;
        float needleOuterR = innerEdge - gaugeSize * 0.05f;
        float needleLen = needleOuterR - capR - gaugeSize * 0.02f;
        float needleWidth = gaugeSize * 0.025f;
        CompassHelper.drawNeedle(canvas, centerX, centerY, needleLen, needleWidth, currentAzimuth, isNightMode);
    }

    private void drawCenterCap(Canvas canvas) {
        gaugeHelper.drawCenterCap(canvas, centerX, centerY, gaugeSize, isNightMode);
    }


    private void drawLocationInfo(Canvas canvas) {
        float infoY = centerY + outerRadius * 0.6f;
        CompassHelper.drawLocationInfo(canvas, centerX, infoY, gaugeSize, isNightMode, latitude, longitude, altitude);
    }

    // ========== 工具方法 ==========

    private String getDirectionName(int degree) {
        return CompassHelper.getDirectionName(degree);
    }

    // ========== 公共接口 ==========

    public void setAzimuth(float azimuth) {
        currentAzimuth = CompassHelper.smoothAzimuth(currentAzimuth, azimuth);
        invalidate();
    }

    public void setLocation(double lat, double lon, double alt) {
        this.latitude = lat;
        this.longitude = lon;
        this.altitude = alt;
        invalidate();
    }

    public void setNightMode(boolean nightMode) {
        if (this.isNightMode != nightMode) {
            this.isNightMode = nightMode;
            paintsDirty = true;
            invalidate();
        }
    }

    @Override
    public boolean isDegreeArea(float x, float y) {
        // 风格0：点击方位角度数字区域判定（中心区域）
        float dx = x - centerX;
        float dy = y - centerY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        return dist < gaugeSize * 0.15f;
    }
}
