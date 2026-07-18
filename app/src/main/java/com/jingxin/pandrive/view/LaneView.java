package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.jingxin.pandrive.data.DataHub;

/**
 * Section 4 下层: 透视车道线 + 道路
 * 三条线: 左车道线 / 中线(白色虚线) / 右车道线
 * 虚线随车速向下滚动，模拟行驶效果
 * 3D车模型在上层GLSurfaceView中叠加
 */
public class LaneView extends View implements DataHub.OnSpeedListener {

    private boolean isNightMode = false;
    private int currentSpeed = 0;

    // Animation
    private float dashOffset = 0f;
    private float shimmerOffset = 0f;
    private long lastFrameTime = 0;
    private static final float PIXELS_PER_KMH_PER_SEC = 3.0f;
    private static final int FALLBACK_SPEED = 60;
    private static final int BASE_SPEED = 60;

    // Dash parameters
    private float dashLength = 40f;
    private float gapLength = 30f;

    // Paints
    private Paint roadPaint;
    private Paint bgPaint;
    private Paint edgeLinePaint;
    private Paint laneLinePaint;
    private Paint centerLinePaint;
    private Paint shimmerPaint;

    // Reused objects to avoid per-frame allocation
    private final Path roadPath = new Path();
    private int cachedRoadW = -1;
    private int cachedRoadH = -1;
    private LinearGradient cachedRoadGradient;
    private int cachedRoadTopColor;
    private int cachedRoadBottomColor;

    public LaneView(Context context) {
        super(context);
        init();
    }

    public LaneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LaneView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setBackgroundColor(0x00000000);

        float density = getResources().getDisplayMetrics().density;
        dashLength *= density;
        gapLength *= density;

        roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        roadPaint.setStyle(Paint.Style.FILL);

        edgeLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        edgeLinePaint.setStyle(Paint.Style.STROKE);
        edgeLinePaint.setStrokeWidth(1f * density);

        laneLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        laneLinePaint.setStyle(Paint.Style.STROKE);
        laneLinePaint.setStrokeWidth(0.75f * density);

        centerLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerLinePaint.setStyle(Paint.Style.STROKE);
        centerLinePaint.setStrokeWidth(0.75f * density);

        shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shimmerPaint.setStyle(Paint.Style.STROKE);

        updateColors();
        DataHub.getInstance(getContext()).addSpeedListener(this);
    }

    public void setNightMode(boolean isNight) {
        this.isNightMode = isNight;
        updateColors();
        invalidate();
    }

    private void updateColors() {
        if (isNightMode) {
            edgeLinePaint.setColor(0xFFFFAA00);
            edgeLinePaint.setShadowLayer(6f, 0, 0, 0x40FFAA00);
            laneLinePaint.setColor(0xFFFFFFFF);
            laneLinePaint.setShadowLayer(4f, 0, 0, 0x3000E5A0);
            centerLinePaint.setColor(0xB3FFFFFF);
            centerLinePaint.setShadowLayer(4f, 0, 0, 0x3000E5A0);
        } else {
            edgeLinePaint.setColor(0xFFCCCCCC);
            edgeLinePaint.setShadowLayer(0, 0, 0, 0);
            laneLinePaint.setColor(0xFFDDDDDD);
            laneLinePaint.setShadowLayer(0, 0, 0, 0);
            centerLinePaint.setColor(0xB3CCCCCC);
            centerLinePaint.setShadowLayer(0, 0, 0, 0);
        }
        cachedRoadW = -1;
    }

    @Override
    public void onSpeedChanged(int speed, int limitedSpeed) {
        this.currentSpeed = speed;
        if (speed > 0) invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        long now = System.nanoTime();
        int effectiveSpeed = currentSpeed;
        if (lastFrameTime > 0) {
            float dt = (now - lastFrameTime) / 1e9f;
            if (effectiveSpeed > 0) {
                dashOffset += effectiveSpeed * PIXELS_PER_KMH_PER_SEC * dt;
            }
            float shimmerSpeed = 30f * 6.0f * dt * 0.6f;
            shimmerOffset += shimmerSpeed;
        }
        lastFrameTime = now;

        float cycle = dashLength + gapLength;
        if (dashOffset > cycle * 100) dashOffset -= cycle * 100;

        drawRoad(canvas, w, h);
        drawEdgeLines(canvas, w, h);

        if (isNightMode) {
            float nearWidth = w * 0.95f;
            float farWidth = w * 0.15f;
            float centerX = w / 2f;
            drawEdgeShimmerLine(canvas, centerX - farWidth / 2, centerX - nearWidth / 2, h);
            drawEdgeShimmerLine(canvas, centerX + farWidth / 2, centerX + nearWidth / 2, h);
        }

        drawDashedLaneLine(canvas, w, h, 0.25f, laneLinePaint);
        drawDashedLaneLine(canvas, w, h, 0.5f, centerLinePaint);
        drawDashedLaneLine(canvas, w, h, 0.75f, laneLinePaint);

        invalidate();
    }

    private void drawRoad(Canvas canvas, int w, int h) {
        float nearWidth = w * 0.95f;
        float farWidth = w * 0.15f;
        float centerX = w / 2f;

        roadPath.reset();
        roadPath.moveTo(centerX - nearWidth / 2, h);
        roadPath.lineTo(centerX + nearWidth / 2, h);
        roadPath.lineTo(centerX + farWidth / 2, 0);
        roadPath.lineTo(centerX - farWidth / 2, 0);
        roadPath.close();

        int topColor = isNightMode ? 0xFF050810 : 0xFF2A2D30;
        int bottomColor = isNightMode ? 0xFF0A0F18 : 0xFF3A3D42;

        if (cachedRoadGradient == null || cachedRoadW != w || cachedRoadH != h
                || cachedRoadTopColor != topColor || cachedRoadBottomColor != bottomColor) {
            cachedRoadGradient = new LinearGradient(0, 0, 0, h, topColor, bottomColor, Shader.TileMode.CLAMP);
            cachedRoadW = w;
            cachedRoadH = h;
            cachedRoadTopColor = topColor;
            cachedRoadBottomColor = bottomColor;
        }
        roadPaint.setShader(cachedRoadGradient);
        canvas.drawPath(roadPath, roadPaint);
    }

    private void drawEdgeLines(Canvas canvas, int w, int h) {
        float nearWidth = w * 0.95f;
        float farWidth = w * 0.15f;
        float centerX = w / 2f;

        float leftNearX = centerX - nearWidth / 2;
        float leftFarX = centerX - farWidth / 2;
        canvas.drawLine(leftFarX, 0, leftNearX, h, edgeLinePaint);

        float rightNearX = centerX + nearWidth / 2;
        float rightFarX = centerX + farWidth / 2;
        canvas.drawLine(rightFarX, 0, rightNearX, h, edgeLinePaint);
    }

    private void drawEdgeShimmerLine(Canvas canvas, float farX, float nearX, int h) {
        float density = getResources().getDisplayMetrics().density;
        float shimmerCycle = h * 1.5f;
        float segLen = h * 0.12f;
        float pos = shimmerOffset % shimmerCycle;

        for (int i = 0; i < 2; i++) {
            float offset = i * shimmerCycle / 2f;
            float yHead = (pos + offset) % shimmerCycle;
            float yTail = yHead - segLen;

            if (yHead < 0 && yTail < 0) continue;
            if (yTail > h) continue;

            float drawYHead = Math.min(Math.max(yHead, 0), h);
            float drawYTail = Math.max(Math.max(yTail, 0), 0);

            if (drawYHead - drawYTail < 1f) continue;

            float xHead = farX + (nearX - farX) * (drawYHead / h);
            float xTail = farX + (nearX - farX) * (drawYTail / h);

            int shimmerColor = isNightMode ? 0xFFFFDD44 : 0xFF00D4E8;
            int shimmerFade = isNightMode ? 0x00FFDD44 : 0x0000D4E8;

            shimmerPaint.setShader(new LinearGradient(xTail, drawYTail, xHead, drawYHead,
                    shimmerFade, shimmerColor, Shader.TileMode.CLAMP));
            shimmerPaint.setStrokeWidth(1.5f * density);
            shimmerPaint.setShadowLayer(6f * density, 0, 0,
                    isNightMode ? 0x50FFDD44 : 0x5000D4E8);

            canvas.drawLine(xTail, drawYTail, xHead, drawYHead, shimmerPaint);
        }
        shimmerPaint.setShader(null);
        shimmerPaint.clearShadowLayer();
    }

    private void drawDashedLaneLine(Canvas canvas, int w, int h, float fraction, Paint paint) {
        float centerX = w / 2f;
        float nearWidth = w * 0.95f;
        float farWidth = w * 0.15f;
        float cycle = dashLength + gapLength;

        int numSegments = 30;
        float segH = (float) h / numSegments;

        for (int i = 0; i < numSegments; i++) {
            float yBot = h - i * segH;
            float yTop = h - (i + 1) * segH;

            float tBot = (float) i / numSegments;
            float tTop = (float) (i + 1) / numSegments;
            float widthBot = farWidth + (nearWidth - farWidth) * (1 - tBot);
            float widthTop = farWidth + (nearWidth - farWidth) * (1 - tTop);

            float xBot = centerX - widthBot / 2 + widthBot * fraction;
            float xTop = centerX - widthTop / 2 + widthTop * fraction;

            float distFromBottom = i * segH + dashOffset;
            float phase = distFromBottom % cycle;

            if (phase < dashLength) {
                canvas.drawLine(xTop, yTop, xBot, yBot, paint);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        DataHub.getInstance(getContext()).removeSpeedListener(this);
    }
}
