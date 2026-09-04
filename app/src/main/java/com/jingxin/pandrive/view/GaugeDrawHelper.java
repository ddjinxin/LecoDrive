package com.jingxin.pandrive.view;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.SweepGradient;

/**
 * 表盘绘制辅助类（实例化，避免每帧创建Paint/RectF/Matrix）
 * 各View持有GaugeDrawHelper实例，Paint/RectF/Matrix作为实例成员复用
 */
public class GaugeDrawHelper {

    // ===== 主题色常量（原散落于各表盘 View 的硬编码，统一收敛于此）=====
    // 命名基于色值本身（中性），不绑定昼夜语义，避免跨文件语义错位。
    /** LED 主色：绿 */
    public static final int LED_GREEN = 0xFF00E5A0;
    /** LED 主色：青 */
    public static final int LED_CYAN = 0xFF00D4E8;
    /** 流光/阴影青（深） */
    public static final int LED_CYAN_DARK = 0xFF00B8D4;
    /** LED 绿光晕 */
    public static final int GLOW_GREEN = 0x6000E5A0;
    /** LED 青光晕 */
    public static final int GLOW_CYAN = 0x3000D4E8;
    /** 金属亮色 */
    public static final int STEEL_LIGHT = 0xFF8899AA;
    /** 金属更亮 */
    public static final int STEEL_BRIGHT = 0xFF99AABB;
    /** 金属高光 */
    public static final int STEEL_HIGHLIGHT = 0xFFBBC8D4;
    /** 金属中间调 */
    public static final int STEEL_MID = 0xFFA0ADB8;
    /** 金属暗面 */
    public static final int STEEL_DARK = 0xFF667788;
    /** 金属暗面（更深） */
    public static final int STEEL_DARKER = 0xFF556677;
    /** 表盘中心帽高光（亮） */
    public static final int CAP_HIGHLIGHT_LIGHT = 0xFFA0ADB8;
    /** 表盘中心帽高光（暗） */
    public static final int CAP_HIGHLIGHT_DARK = 0xFF8899AA;
    /** 表盘中心帽阴影（亮） */
    public static final int CAP_SHADOW_LIGHT = 0xFF2A3540;
    /** 表盘中心帽阴影（暗） */
    public static final int CAP_SHADOW_DARK = 0xFF3A4A5A;

    private final Paint capBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF slotRect = new RectF();
    private final Paint capHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF glowRect = new RectF();

    private final RectF shimmerRect = new RectF();
    private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shimmerMatrix = new Matrix();

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();

    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF fadeRect = new RectF();

    public void drawCenterCap(Canvas canvas, float centerX, float centerY,
                               float gaugeSize, boolean isNightMode) {
        float capR = gaugeSize * 0.03f;
        int capHighlight = isNightMode ? CAP_HIGHLIGHT_DARK : CAP_HIGHLIGHT_LIGHT;
        int capShadow = isNightMode ? CAP_SHADOW_DARK : CAP_SHADOW_LIGHT;

        capBodyPaint.setStyle(Paint.Style.FILL);
        capBodyPaint.setShader(new RadialGradient(
                centerX - capR * 0.3f, centerY - capR * 0.3f, capR * 1.2f,
                capHighlight, capShadow, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, capR, capBodyPaint);

        capRingPaint.setStyle(Paint.Style.STROKE);
        capRingPaint.setColor(isNightMode ? STEEL_DARK : STEEL_LIGHT);
        capRingPaint.setStrokeWidth(Math.max(1f, gaugeSize * 0.002f));
        canvas.drawCircle(centerX, centerY, capR, capRingPaint);

        float slotWidth = capR * 1.2f;
        float slotHeight = Math.max(1f, capR * 0.18f);
        slotPaint.setStyle(Paint.Style.FILL);
        slotPaint.setColor(isNightMode ? 0xFF2A3040 : 0xFF1A2530);
        slotRect.set(
                centerX - slotWidth / 2f, centerY - slotHeight / 2f,
                centerX + slotWidth / 2f, centerY + slotHeight / 2f);
        canvas.drawRoundRect(slotRect, slotHeight / 2f, slotHeight / 2f, slotPaint);

        capHighlightPaint.setStyle(Paint.Style.FILL);
        capHighlightPaint.setColor(isNightMode ? STEEL_DARK : STEEL_HIGHLIGHT);
        canvas.drawCircle(centerX, centerY, capR * 0.2f, capHighlightPaint);
    }

    public void drawDiskGlow(Canvas canvas, float centerX, float centerY,
                              float outerEdge, float gaugeSize,
                              boolean isNightMode) {
        drawDiskGlow(canvas, centerX, centerY, outerEdge, gaugeSize, isNightMode, 0x20);
    }

    public void drawDiskGlowMinimal(Canvas canvas, float centerX, float centerY,
                                     float innerEdge, float gaugeSize,
                                     boolean isNightMode) {
        drawDiskGlow(canvas, centerX, centerY, innerEdge, gaugeSize, isNightMode, 0x15);
    }

    /** 统一的光晕绘制核心：alphaBase 控制最内层发光强度（0x20 普通 / 0x15 极简） */
    private void drawDiskGlow(Canvas canvas, float centerX, float centerY,
                              float edge, float gaugeSize,
                              boolean isNightMode, int alphaBase) {
        float capR = gaugeSize * 0.03f;
        int layers = 6;
        for (int i = 0; i < layers; i++) {
            float fraction = (float) i / (layers - 1);
            float r = edge - fraction * (edge - capR);
            int alpha = (int) (alphaBase * (1f - fraction));
            int color = isNightMode ? (alpha << 24 | 0x00E5A0) : (alpha << 24 | 0x00D4E8);
            glowPaint.setColor(color);
            float sw = (edge - capR) / layers + 1f;
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeWidth(sw);
            glowRect.set(centerX - r, centerY - r, centerX + r, centerY + r);
            canvas.drawArc(glowRect, 0, 360, false, glowPaint);
        }
    }

    public void drawShimmerArc(Canvas canvas, float centerX, float centerY,
                                float shimmerR, float gaugeSize,
                                float shimmerPhase, boolean isNightMode) {
        drawShimmerArc(canvas, centerX, centerY, shimmerR, gaugeSize, shimmerPhase, isNightMode, 0, 360, 0);
    }

    public void drawShimmerArc(Canvas canvas, float centerX, float centerY,
                                float shimmerR, float gaugeSize,
                                float shimmerPhase, boolean isNightMode,
                                float startAngle, float sweepAngle,
                                float sweepOffset) {
        shimmerRect.set(
            centerX - shimmerR, centerY - shimmerR,
            centerX + shimmerR, centerY + shimmerR);

        int shimmerColor = isNightMode ? LED_GREEN : LED_CYAN_DARK;
        int transparent = 0x00000000;

        float phaseFrac = shimmerPhase * 0.85f;
        float glowWidth = 0.1f;
        float glowCenter = phaseFrac;
        float glowStart = glowCenter - glowWidth / 2f;
        float glowEnd = glowCenter + glowWidth / 2f;

        int[] colors;
        float[] positions;
        if (glowStart >= 0 && glowEnd <= 1f) {
            colors = new int[]{ transparent, transparent, shimmerColor, transparent, transparent };
            positions = new float[]{ 0f, Math.max(0f, glowStart), glowCenter, Math.min(1f, glowEnd), 1f };
        } else {
            float wrapStart = glowStart < 0 ? glowStart + 1f : glowStart;
            float wrapEnd = glowEnd > 1f ? glowEnd - 1f : glowEnd;
            colors = new int[]{ shimmerColor, transparent, transparent, shimmerColor, transparent };
            positions = new float[]{ 0f, wrapEnd, glowCenter > 0.5f ? 1f - wrapStart : wrapEnd + 0.01f, Math.min(wrapStart, 1f), 1f };
        }

        SweepGradient shimmerGradient = new SweepGradient(centerX, centerY, colors, positions);
        shimmerMatrix.reset();
        shimmerMatrix.postRotate(sweepOffset, centerX, centerY);
        shimmerGradient.setLocalMatrix(shimmerMatrix);

        shimmerPaint.setStyle(Paint.Style.STROKE);
        shimmerPaint.setStrokeWidth(Math.max(1f, gaugeSize * 0.004f));
        shimmerPaint.setStrokeCap(Paint.Cap.ROUND);
        shimmerPaint.setShader(shimmerGradient);

        canvas.drawArc(shimmerRect, startAngle, sweepAngle, false, shimmerPaint);
    }

    public void drawOuterRing(Canvas canvas, float centerX, float centerY,
                               float outerR, int color, float gaugeSize) {
        drawOuterRing(canvas, centerX, centerY, outerR, color, gaugeSize, 0, 360);
    }

    public void drawOuterRing(Canvas canvas, float centerX, float centerY,
                               float outerR, int color, float gaugeSize,
                               float startAngle, float sweepAngle) {
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(1f, gaugeSize * 0.004f));
        ringPaint.setColor(color);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringRect.set(centerX - outerR, centerY - outerR, centerX + outerR, centerY + outerR);
        canvas.drawArc(ringRect, startAngle, sweepAngle, false, ringPaint);
    }

    public void drawFadeArc(Canvas canvas, float centerX, float centerY,
                             float radius, float strokeWidth,
                             float startAngle, float sweepAngle, float fadeRange,
                             int cR, int cG, int cB, int maxAlpha) {
        fadePaint.setStyle(Paint.Style.STROKE);
        fadePaint.setStrokeWidth(strokeWidth);
        fadePaint.setStrokeCap(Paint.Cap.BUTT);

        float step = 3f;
        fadeRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);

        for (float a = startAngle; a < startAngle + sweepAngle; a += step) {
            float midA = a + step / 2f;
            float distFromStart = midA - startAngle;
            float distFromEnd = (startAngle + sweepAngle) - midA;

            int alpha = maxAlpha;
            if (distFromStart < fadeRange) {
                alpha = (int) (maxAlpha * (distFromStart / fadeRange));
            }
            if (distFromEnd < fadeRange) {
                alpha = Math.min(alpha, (int) (maxAlpha * (distFromEnd / fadeRange)));
            }
            alpha = Math.max(0, Math.min(255, alpha));
            if (alpha <= 0) continue;

            fadePaint.setColor((alpha << 24) | (cR << 16) | (cG << 8) | cB);
            canvas.drawArc(fadeRect, a, step + 0.5f, false, fadePaint);
        }
    }
}
