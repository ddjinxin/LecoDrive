package com.jingxin.pandrive.view;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * 指南针绘制工具类
 * 提取自 CompassView / CompassViewMinimal 的公共逻辑
 */
public class CompassHelper {

    /**
     * 绘制指南针指针（北极红色三角 + 南极银灰三角）
     */
    public static void drawNeedle(Canvas canvas, float centerX, float centerY,
                                   float needleLen, float needleWidth,
                                   float currentAzimuth, boolean isNightMode) {
        canvas.save();
        canvas.rotate(currentAzimuth, centerX, centerY);

        Path northPath = new Path();
        northPath.moveTo(centerX, centerY - needleLen);
        northPath.lineTo(centerX - needleWidth, centerY);
        northPath.lineTo(centerX + needleWidth, centerY);
        northPath.close();
        Paint northPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        northPaint.setStyle(Paint.Style.FILL);
        LinearGradient northGrad = new LinearGradient(centerX, centerY - needleLen, centerX, centerY,
                0xFFDD4444, 0xFF882222, android.graphics.Shader.TileMode.CLAMP);
        northPaint.setShader(northGrad);
        canvas.drawPath(northPath, northPaint);

        Path southPath = new Path();
        southPath.moveTo(centerX, centerY + needleLen);
        southPath.lineTo(centerX - needleWidth, centerY);
        southPath.lineTo(centerX + needleWidth, centerY);
        southPath.close();
        Paint southPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        southPaint.setStyle(Paint.Style.FILL);
        int sHighlight = isNightMode ? 0xFF8899AA : 0xFFA0ADB8;
        int sShadow = isNightMode ? 0xFF445566 : 0xFF556677;
        LinearGradient southGrad = new LinearGradient(centerX, centerY, centerX, centerY + needleLen,
                sShadow, sHighlight, android.graphics.Shader.TileMode.CLAMP);
        southPaint.setShader(southGrad);
        canvas.drawPath(southPath, southPaint);

        canvas.restore();
    }

    /**
     * 绘制位置信息（经纬度 + 海拔）
     */
    public static void drawLocationInfo(Canvas canvas, float centerX, float infoY,
                                         float gaugeSize, boolean isNightMode,
                                         double latitude, double longitude, double altitude) {
        Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        infoPaint.setStyle(Paint.Style.FILL);
        infoPaint.setTextAlign(Paint.Align.CENTER);
        infoPaint.setTextSize(gaugeSize * 0.032f);
        infoPaint.setColor(isNightMode ? 0xFF889999 : 0xFF666666);

        if (!Double.isNaN(latitude) && !Double.isNaN(longitude)) {
            String latStr = String.format("%.4f°%s", Math.abs(latitude), latitude >= 0 ? "N" : "S");
            String lonStr = String.format("%.4f°%s", Math.abs(longitude), longitude >= 0 ? "E" : "W");
            canvas.drawText(latStr + "  " + lonStr, centerX, infoY, infoPaint);
            if (!Double.isNaN(altitude)) {
                Paint.FontMetrics fm = infoPaint.getFontMetrics();
                canvas.drawText(String.format("海拔 %.1fm", altitude), centerX, infoY - fm.ascent + gaugeSize * 0.005f, infoPaint);
            }
        } else {
            canvas.drawText("等待定位...", centerX, infoY, infoPaint);
        }
    }

    /**
     * 获取8方位中文名
     */
    public static String getDirectionName(int degree) {
        if (degree >= 337 || degree < 23) return "北";
        if (degree >= 23 && degree < 67) return "东北";
        if (degree >= 67 && degree < 113) return "东";
        if (degree >= 113 && degree < 157) return "东南";
        if (degree >= 157 && degree < 203) return "南";
        if (degree >= 203 && degree < 247) return "西南";
        if (degree >= 247 && degree < 293) return "西";
        return "西北";
    }

    /**
     * 方位平滑算法
     */
    public static float smoothAzimuth(float currentAzimuth, float newAzimuth) {
        float diff = newAzimuth - currentAzimuth;
        if (diff > 180) diff -= 360;
        if (diff < -180) diff += 360;
        return currentAzimuth + diff * 0.3f;
    }
}
