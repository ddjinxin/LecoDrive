package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 路况光柱 — 完全对齐原项目 高德辅助导航 TrafficBarView
 * 替代原有进度条，显示前方路况（畅通/缓行/拥堵/严重拥堵）彩色段
 * 支持导航进度小圆点，跟随导航进度从左往右移动
 *
 * 数据来源：高德广播 KEY_TYPE 13011 的 EXTRA_TMC_SEGMENT
 * tmc_status: 0=终点, 1=畅通, 2=缓行, 3=拥堵, 4=严重拥堵, 10=已走过
 */
public class TrafficBarView extends View {

    // 日间颜色
    private static final int COLOR_SMOOTH_DAY = 0xFF4CAF50;
    private static final int COLOR_FAST_DAY = 0xFF1B5E20;
    private static final int COLOR_SLOW_DAY = 0xFFFFC107;
    private static final int COLOR_CONGESTED_DAY = 0xFFFF5722;
    private static final int COLOR_SEVERE_DAY = 0xFF8B0000;
    private static final int COLOR_PASSED_DAY = 0xFFD0D0D0;
    private static final int COLOR_UNKNOWN_DAY = 0xFFBDBDBD;
    private static final int COLOR_BG_DAY = 0xFFE0E0E0;

    // 夜间颜色
    private static final int COLOR_SMOOTH_NIGHT = 0xFF2E7D32;
    private static final int COLOR_FAST_NIGHT = 0xFF1B5E20;
    private static final int COLOR_SLOW_NIGHT = 0xFFFF8F00;
    private static final int COLOR_CONGESTED_NIGHT = 0xFFD84315;
    private static final int COLOR_SEVERE_NIGHT = 0xFF6D0000;
    private static final int COLOR_PASSED_NIGHT = 0xFF4A4A4A;
    private static final int COLOR_UNKNOWN_NIGHT = 0xFF3A3A3A;
    private static final int COLOR_BG_NIGHT = 0xFF3A3A3A;

    // 降级模式（无TMC数据时）的进度条颜色
    private static final int COLOR_PROGRESS_FG_DAY = 0xFF4CAF50;
    private static final int COLOR_PROGRESS_FG_NIGHT = 0xFF2E7D32;

    // 导航进度小圆点颜色
    private static final int COLOR_DOT_DAY = 0xFFFFFFFF;
    private static final int COLOR_DOT_NIGHT = 0xFFFFFFFF;
    private static final int COLOR_DOT_STROKE_DAY = 0xFF333333;
    private static final int COLOR_DOT_STROKE_NIGHT = 0xFF000000;

    // 路段数据
    private List<SegmentData> segments = new ArrayList<>();
    private boolean isNightMode = false;

    // 降级模式：纯进度条
    private float fallbackProgress = -1f;  // -1表示不使用降级模式

    // 导航进度（0.0~1.0，-1表示不显示圆点）
    private float navProgress = -1f;

    // 绘制工具
    private Paint segmentPaint;
    private Paint dotFillPaint;
    private Paint dotStrokePaint;
    private RectF drawRect = new RectF();
    private float cornerRadius = 2f;
    private float density;

    /**
     * 路段数据内部类
     */
    public static class SegmentData {
        public int status;
        public int percent;

        public SegmentData(int status, int percent) {
            this.status = status;
            this.percent = percent;
        }
    }

    // TMC status constants (matching TmcSegmentInfo)
    private static final int STATUS_UNKNOWN = 0;
    private static final int STATUS_SMOOTH = 1;
    private static final int STATUS_SLOW = 2;
    private static final int STATUS_CONGESTED = 3;
    private static final int STATUS_SEVERE = 4;
    private static final int STATUS_FAST = 5;
    private static final int STATUS_PASSED = 10;

    public TrafficBarView(Context context) {
        super(context);
        init();
    }

    public TrafficBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TrafficBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        segmentPaint.setStyle(Paint.Style.FILL);

        dotFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotFillPaint.setStyle(Paint.Style.FILL);

        dotStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotStrokePaint.setStyle(Paint.Style.STROKE);
        dotStrokePaint.setStrokeWidth(1f);

        density = getResources().getDisplayMetrics().density;
        cornerRadius = 2f * density;
    }

    /**
     * 设置TMC路况段数据
     */
    public void setTmcData(int[] statusArray, int[] percentArray) {
        segments.clear();
        fallbackProgress = -1f;
        if (statusArray != null && percentArray != null && statusArray.length == percentArray.length) {
            for (int i = 0; i < statusArray.length; i++) {
                segments.add(new SegmentData(statusArray[i], percentArray[i]));
            }
        }
        invalidate();
    }

    /**
     * 设置降级模式进度（无TMC数据时回退到普通进度条）
     * @param progress 进度值 0.0~1.0，-1表示隐藏
     */
    public void setFallbackProgress(float progress) {
        if (progress < 0) {
            segments.clear();
            fallbackProgress = -1f;
        } else {
            segments.clear();
            fallbackProgress = Math.max(0f, Math.min(1f, progress));
        }
        invalidate();
    }

    /**
     * 设置导航进度（圆点位置）
     * @param progress 进度值 0.0~1.0，-1表示不显示圆点
     */
    public void setNavProgress(float progress) {
        if (progress < 0) {
            navProgress = -1f;
        } else {
            navProgress = Math.max(0f, Math.min(1f, progress));
        }
        invalidate();
    }

    /**
     * 设置昼夜模式
     */
    public void setNightMode(boolean night) {
        if (this.isNightMode != night) {
            this.isNightMode = night;
            invalidate();
        }
    }

    /**
     * 清空数据
     */
    public void clearData() {
        segments.clear();
        fallbackProgress = -1f;
        navProgress = -1f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 先画背景
        segmentPaint.setColor(isNightMode ? COLOR_BG_NIGHT : COLOR_BG_DAY);
        drawRect.set(0, 0, w, h);
        canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, segmentPaint);

        if (!segments.isEmpty()) {
            drawTrafficBar(canvas, w, h);
        } else if (fallbackProgress >= 0) {
            drawFallbackProgress(canvas, w, h);
        }

        // 绘制导航进度小圆点（叠在路况条上面）
        drawNavDot(canvas, w, h);
    }

    /**
     * 绘制路况光柱（TMC模式）
     */
    private void drawTrafficBar(Canvas canvas, int w, int h) {
        float x = 0;

        for (int i = 0; i < segments.size(); i++) {
            SegmentData seg = segments.get(i);
            if (seg.percent <= 0) {
                continue;
            }

            float segWidth;
            if (i == segments.size() - 1) {
                segWidth = w - x;
            } else {
                segWidth = w * seg.percent / 100f;
            }

            if (segWidth <= 0) continue;

            segmentPaint.setColor(getStatusColor(seg.status));

            float leftRadius = (x <= 0.5f) ? cornerRadius : 0f;
            float rightRadius = (x + segWidth >= w - 0.5f) ? cornerRadius : 0f;

            drawRect.set(x, 0, x + segWidth, h);
            canvas.drawRoundRect(drawRect,
                Math.max(leftRadius, rightRadius),
                Math.max(leftRadius, rightRadius),
                segmentPaint);

            if (leftRadius > 0 && rightRadius == 0) {
                drawRect.set(x + leftRadius, 0, x + segWidth, h);
                canvas.drawRect(drawRect, segmentPaint);
            } else if (leftRadius == 0 && rightRadius > 0) {
                drawRect.set(x, 0, x + segWidth - rightRadius, h);
                canvas.drawRect(drawRect, segmentPaint);
            }

            x += segWidth;
        }
    }

    /**
     * 绘制降级进度条（无TMC数据时）
     */
    private void drawFallbackProgress(Canvas canvas, int w, int h) {
        int fgWidth = (int)(w * fallbackProgress);
        if (fgWidth > 0) {
            segmentPaint.setColor(isNightMode ? COLOR_PROGRESS_FG_NIGHT : COLOR_PROGRESS_FG_DAY);

            float rightRadius = (fgWidth >= w - 0.5f) ? cornerRadius : 0f;
            drawRect.set(0, 0, fgWidth, h);
            canvas.drawRoundRect(drawRect, cornerRadius, rightRadius, segmentPaint);

            if (rightRadius == 0 && fgWidth > cornerRadius) {
                drawRect.set(0, 0, fgWidth - cornerRadius, h);
                canvas.drawRect(drawRect, segmentPaint);
            }
        }
    }

    /**
     * 绘制导航进度小圆点
     * 白色实心圆 + 深色描边，叠在路况条上
     */
    private void drawNavDot(Canvas canvas, int w, int h) {
        if (navProgress < 0) return;

        float dotX = w * navProgress;
        float dotY = h / 2f;
        float dotRadius = 3f * density;

        if (dotX < dotRadius) dotX = dotRadius;
        if (dotX > w - dotRadius) dotX = w - dotRadius;

        dotStrokePaint.setColor(isNightMode ? COLOR_DOT_STROKE_NIGHT : COLOR_DOT_STROKE_DAY);
        canvas.drawCircle(dotX, dotY, dotRadius, dotStrokePaint);

        dotFillPaint.setColor(isNightMode ? COLOR_DOT_NIGHT : COLOR_DOT_DAY);
        canvas.drawCircle(dotX, dotY, dotRadius - 0.5f * density, dotFillPaint);
    }

    /**
     * 根据拥堵状态获取颜色
     */
    private int getStatusColor(int status) {
        if (isNightMode) {
            switch (status) {
                case STATUS_SMOOTH:     return COLOR_SMOOTH_NIGHT;
                case STATUS_FAST:       return COLOR_FAST_NIGHT;
                case STATUS_SLOW:       return COLOR_SLOW_NIGHT;
                case STATUS_CONGESTED:  return COLOR_CONGESTED_NIGHT;
                case STATUS_SEVERE:     return COLOR_SEVERE_NIGHT;
                case STATUS_PASSED:     return COLOR_PASSED_NIGHT;
                case STATUS_UNKNOWN:    return COLOR_UNKNOWN_NIGHT;
                default:               return COLOR_UNKNOWN_NIGHT;
            }
        } else {
            switch (status) {
                case STATUS_SMOOTH:     return COLOR_SMOOTH_DAY;
                case STATUS_FAST:       return COLOR_FAST_DAY;
                case STATUS_SLOW:       return COLOR_SLOW_DAY;
                case STATUS_CONGESTED:  return COLOR_CONGESTED_DAY;
                case STATUS_SEVERE:     return COLOR_SEVERE_DAY;
                case STATUS_PASSED:     return COLOR_PASSED_DAY;
                case STATUS_UNKNOWN:    return COLOR_UNKNOWN_DAY;
                default:               return COLOR_UNKNOWN_DAY;
            }
        }
    }
}
