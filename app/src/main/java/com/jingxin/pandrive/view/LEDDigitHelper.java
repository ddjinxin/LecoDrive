package com.jingxin.pandrive.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * LED七段数码管绘制工具类
 * 提取自 LEDDigitView / MileageView / SpeedometerView / DateTimeView 的公共逻辑
 */
public class LEDDigitHelper {

    // 七段映射: [上, 左上, 右上, 中, 左下, 右下, 下]
    public static final int[][] SEGMENT_MAP = {
        {1,1,1,0,1,1,1}, // 0
        {0,0,1,0,0,1,0}, // 1
        {1,0,1,1,1,0,1}, // 2
        {1,0,1,1,0,1,1}, // 3
        {0,1,1,1,0,1,0}, // 4
        {1,1,0,1,0,1,1}, // 5
        {1,1,0,1,1,1,1}, // 6
        {1,0,1,0,0,1,0}, // 7
        {1,1,1,1,1,1,1}, // 8
        {1,1,1,1,0,1,1}, // 9
    };

    /**
     * 绘制单个LED七段数码管数字（六边形斜切角段）
     * 段编号: 0=上, 1=左上, 2=右上, 3=中, 4=左下, 5=右下, 6=下
     *
     * @param canvas     画布
     * @param x          左上角x
     * @param y          左上角y
     * @param w          数字宽度
     * @param h          数字高度
     * @param thick      段粗细
     * @param gap        段与段间隙
     * @param digit      数字0-9
     * @param activeColor 激活色
     * @param activeGlow  光晕色
     * @param alpha       透明度(0-255), 255=不透明
     */
    public static void drawLEDDigit(Canvas canvas, float x, float y, float w, float h,
                                     float thick, float gap, int digit,
                                     int activeColor, int activeGlow, int alpha) {
        int[] segs = SEGMENT_MAP[digit];
        float half = thick / 2f;
        float innerH = h - 2 * gap;
        float halfH = innerH / 2f;

        // 数字"1"只点亮右侧两段竖线，视觉上远窄于其他数字
        // 缩窄绘制宽度并居中，使视觉大小与其他数字一致
        float drawW = w;
        float drawX = x;
        if (digit == 1) {
            drawW = w * 0.55f;
            drawX = x + (w - drawW) / 2f;
        }

        float[][][] segPaths = {
            {{drawX+gap+half,y},{drawX+drawW-gap-half,y},{drawX+drawW-gap,y+half},{drawX+drawW-gap-half,y+thick},{drawX+gap+half,y+thick},{drawX+gap,y+half}},
            {{drawX+half,y+gap+half},{drawX+thick,y+gap+half+half},{drawX+thick,y+gap+halfH-half},{drawX+half,y+gap+halfH},{drawX,y+gap+halfH-half},{drawX,y+gap+half+half}},
            {{drawX+drawW-thick+half,y+gap+half},{drawX+drawW,y+gap+half+half},{drawX+drawW,y+gap+halfH-half},{drawX+drawW-half,y+gap+halfH},{drawX+drawW-thick,y+gap+halfH-half},{drawX+drawW-thick,y+gap+half+half}},
            {{drawX+gap+half,y+gap+halfH},{drawX+drawW-gap-half,y+gap+halfH},{drawX+drawW-gap,y+gap+halfH+half},{drawX+drawW-gap-half,y+gap+halfH+thick},{drawX+gap+half,y+gap+halfH+thick},{drawX+gap,y+gap+halfH+half}},
            {{drawX+half,y+gap+halfH+half},{drawX+thick,y+gap+halfH+half+half},{drawX+thick,y+h-gap-half},{drawX+half,y+h-gap},{drawX,y+h-gap-half},{drawX,y+gap+halfH+half+half}},
            {{drawX+drawW-thick+half,y+gap+halfH+half},{drawX+drawW,y+gap+halfH+half+half},{drawX+drawW,y+h-gap-half},{drawX+drawW-half,y+h-gap},{drawX+drawW-thick,y+h-gap-half},{drawX+drawW-thick,y+gap+halfH+half+half}},
            {{drawX+gap+half,y+h-thick},{drawX+drawW-gap-half,y+h-thick},{drawX+drawW-gap,y+h-half},{drawX+drawW-gap-half,y+h},{drawX+gap+half,y+h},{drawX+gap,y+h-half}},
        };

        Paint segPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        segPaint.setStyle(Paint.Style.FILL);
        Path segPath = new Path();
        for (int i = 0; i < 7; i++) {
            segPath.reset();
            float[] p = segPaths[i][0];
            segPath.moveTo(p[0], p[1]);
            for (int j = 1; j < 6; j++) {
                p = segPaths[i][j];
                segPath.lineTo(p[0], p[1]);
            }
            segPath.close();
            if (segs[i] == 1) {
                segPaint.setColor(activeColor);
                segPaint.setShadowLayer(thick * 1.5f, 0, 0, activeGlow);
                segPaint.setAlpha(alpha);
                canvas.drawPath(segPath, segPaint);
                segPaint.clearShadowLayer();
            }
        }
    }

    /**
     * 简化版：不透明(alpha=255)
     */
    public static void drawLEDDigit(Canvas canvas, float x, float y, float w, float h,
                                     float thick, float gap, int digit,
                                     int activeColor, int activeGlow) {
        drawLEDDigit(canvas, x, y, w, h, thick, gap, digit, activeColor, activeGlow, 255);
    }
}
