package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 极简风格指南针（风格1）
 * 
 * 布局：
 * - 三段渐隐弧线（最外圈右侧一段，第二圈左右对称两段）
 * - 方位文字(N/E/S/W)
 * - 指针 + 中心螺丝帽
 * - 角度 + 方位中文 + 经纬度
 */
public class CompassViewMinimal extends View implements ICompassView {

    private final GaugeDrawHelper gaugeHelper = new GaugeDrawHelper();

    private float viewWidth, viewHeight;
    private float gaugeSize;
    private float centerX, centerY;
    private float outerRadius;

    public float currentAzimuth = 0f;
    private double latitude = Double.NaN;
    private double longitude = Double.NaN;
    private double altitude = Double.NaN;
    private boolean isNightMode = false;

    public CompassViewMinimal(Context context) { super(context); }
    public CompassViewMinimal(Context context, AttributeSet attrs) { super(context, attrs); }
    public CompassViewMinimal(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        float density = getResources().getDisplayMetrics().density;
        if (widthMode == MeasureSpec.UNSPECIFIED) {
            width = (int) (250 * density);
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
        gaugeSize = w;
        centerX = gaugeSize / 2f;
        centerY = gaugeSize * 0.5f;
        outerRadius = gaugeSize * 0.36f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. 圆盘内部微光
        drawDiskGlow(canvas);
        // 2. 三段渐隐弧线
        drawFadeArcs(canvas);
        // 3. 方位文字
        drawDirections(canvas);
        // 4. 指针
        drawNeedle(canvas);
        // 5. 中心螺丝帽
        drawCenterCap(canvas);
        // 6. 位置信息
        drawLocationInfo(canvas);
    }

    // ========== 渐隐弧线 ==========

    private void drawFadeArcs(Canvas canvas) {
        int cR = 0x00;
        int cG = isNightMode ? 0xE5 : 0xD4;
        int cB = isNightMode ? 0xA0 : 0xE8;

        float r1 = outerRadius + gaugeSize * 0.02f;
        float stroke1 = gaugeSize * 0.005f;
        gaugeHelper.drawFadeArc(canvas, centerX, centerY, r1, stroke1, -55f, 110f, 15f, cR, cG, cB, 255);

        float r1g = r1 + gaugeSize * 0.006f;
        gaugeHelper.drawFadeArc(canvas, centerX, centerY, r1g, gaugeSize * 0.012f, -55f, 110f, 15f, cR, cG, cB, 40);

        float r2 = outerRadius - gaugeSize * 0.02f;
        float stroke2 = gaugeSize * 0.004f;
        float fade2 = 10f;

        gaugeHelper.drawFadeArc(canvas, centerX, centerY, r2, stroke2, -50f, 40f, fade2, cR, cG, cB, 255);
        gaugeHelper.drawFadeArc(canvas, centerX, centerY, r2, stroke2, 130f, 40f, fade2, cR, cG, cB, 255);
    }



    // ========== 圆盘内部微光 ==========

    private void drawDiskGlow(Canvas canvas) {
        gaugeHelper.drawDiskGlowMinimal(canvas, centerX, centerY, outerRadius, gaugeSize, isNightMode);
    }

    // ========== 方位文字 ==========

    private void drawDirections(Canvas canvas) {
        String[] dirs = {"N", "E", "S", "W"};
        int[] degs = {0, 90, 180, 270};
        float letterR = outerRadius - gaugeSize * 0.05f;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(gaugeSize * 0.07f);

        for (int i = 0; i < 4; i++) {
            double rad = Math.toRadians(degs[i] - 90);
            float letterX = centerX + (float) (letterR * Math.cos(rad));
            float letterY = centerY + (float) (letterR * Math.sin(rad));

            if (i == 0) {
                paint.setColor(0xFFCC3333);
            } else {
                paint.setColor(isNightMode ? 0xFFFFFFFF : 0xFF000000);
            }

            Paint.FontMetrics fm = paint.getFontMetrics();
            float textY = letterY - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(dirs[i], letterX, textY, paint);
        }
    }

    // ========== 指针 ==========

    private void drawNeedle(Canvas canvas) {
        float capR = gaugeSize * 0.03f;
        float needleOuterR = outerRadius - gaugeSize * 0.12f;
        float needleLen = needleOuterR - capR - gaugeSize * 0.02f;
        float needleWidth = gaugeSize * 0.025f;
        CompassHelper.drawNeedle(canvas, centerX, centerY, needleLen, needleWidth, currentAzimuth, isNightMode);
    }

    // ========== 中心螺丝帽 ==========

    private void drawCenterCap(Canvas canvas) {
        gaugeHelper.drawCenterCap(canvas, centerX, centerY, gaugeSize, isNightMode);
    }

    // ========== 角度显示 ==========


    // ========== 位置信息 ==========

    private void drawLocationInfo(Canvas canvas) {
        float infoY = centerY + outerRadius * 0.6f;
        CompassHelper.drawLocationInfo(canvas, centerX, infoY, gaugeSize, isNightMode, latitude, longitude, altitude);
    }

    // ========== 工具方法 ==========

    private String getDirectionName(int degree) {
        return CompassHelper.getDirectionName(degree);
    }

    // ========== 公共接口 ==========

    @Override
    public void setAzimuth(float azimuth) {
        currentAzimuth = CompassHelper.smoothAzimuth(currentAzimuth, azimuth);
        invalidate();
    }

    @Override
    public void setLocation(double lat, double lon, double alt) {
        this.latitude = lat;
        this.longitude = lon;
        this.altitude = alt;
        invalidate();
    }

    @Override
    public void setNightMode(boolean nightMode) {
        if (this.isNightMode != nightMode) {
            this.isNightMode = nightMode;
            invalidate();
        }
    }

    @Override
    public boolean isDegreeArea(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        return dist < gaugeSize * 0.15f;
    }
}
