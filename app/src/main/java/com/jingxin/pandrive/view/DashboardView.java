package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.Scroller;

/**
 * 仪表盘三页滑动容器。
 * <p>
 * 内部横向排列三个等宽页面（速度仪表盘 / 指南针 / 圆形时钟），
 * 每页宽度 = min(容器宽, 容器高)（即圆形表盘实际尺寸），居中排列。
 * 通过左右滑动在页面间切换，滑动距离短，手感轻快。
 * 底部绘制页面指示点（LED 风格），提示可滑动方向。
 * <p>
 * 事件分发策略：
 * - 手指按下（DOWN）不拦截，放行给子视图，保留子视图「点击切换样式」能力；
 * - 检测到水平滑动超过阈值时父容器拦截，转为页面切换；
 * - 垂直方向滑动不拦截，放行给子视图。
 * <p>
 * 零第三方依赖，悬浮态随 GridBackgroundView 整体剥离，天然兼容。
 */
public class DashboardView extends FrameLayout {

    private static final int SCROLL_DURATION = 280;
    private static final float SLOP_FACTOR = 1.5f;

    private final Scroller scroller = new Scroller(getContext());
    private final int touchSlop;

    private int currentPage = 0;
    private boolean dragging = false;
    private float downX, downY;
    private float lastMoveX;

    /** 每页宽度（= min(容器宽, 容器高)，圆形表盘实际尺寸） */
    private int pageWidth = 0;

    /** 底部指示点预留高度 */
    private int indicatorHeight = 0;

    private OnPageChangeListener onPageChangeListener;

    // ====== 页面指示点 ======
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public interface OnPageChangeListener {
        void onPageChanged(int page);
    }

    public DashboardView(Context context) {
        this(context, null);
    }

    public DashboardView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DashboardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = (int) (ViewConfiguration.get(context).getScaledTouchSlop() * SLOP_FACTOR);
        setClickable(false);
        // 设置少量底部内边距给指示点留空间（在 dispatchDraw 里画，不占用布局空间）
        setWillNotDraw(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        float density = getResources().getDisplayMetrics().density;
        // 底部指示点预留高度（点直径 + 上边距 + 下边距）
        indicatorHeight = (int) (16f * density);

        // 每页宽度 = min(宽, 高)，即圆形表盘实际尺寸
        pageWidth = Math.min(width, height);

        // 子视图高度 = 容器高度 - 底部指示点区域
        int childHeight = height - indicatorHeight;

        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            child.measure(
                    MeasureSpec.makeMeasureSpec(pageWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        }
        // DashboardView 自身宽度收窄为 pageWidth，一次只显示一个仪表，左右居中
        setMeasuredDimension(pageWidth, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int height = bottom - top;
        if (pageWidth <= 0) {
            pageWidth = right - left;
        }
        int childHeight = height - indicatorHeight;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            int l = i * pageWidth;
            child.layout(l, 0, l + pageWidth, childHeight);
        }
        if (pageWidth > 0) {
            scrollTo(currentPage * pageWidth, 0);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        // 指示点画在固定位置，不受 scrollX 影响
        canvas.save();
        canvas.translate(getScrollX(), 0);
        drawPageIndicator(canvas);
        canvas.restore();
    }

    /**
     * 在容器底部绘制页面指示点（LED 风格）。
     * 当前页：青色发光大点；其余页：暗灰色小点。
     */
    private void drawPageIndicator(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        int pageCount = getChildCount();
        if (pageCount <= 1) return;

        float density = getResources().getDisplayMetrics().density;

        // 指示点参数
        float dotRadius = 2.5f * density;       // 未选中点半径
        float activeRadius = 4f * density;      // 选中点半径
        float glowRadius = 7f * density;        // 选中点光晕半径
        float dotSpacing = 14f * density;       // 点间距
        float bottomMargin = 8f * density;     // 距底部边距（含与仪表的上间距）

        // 总宽度 = 点间距 * (页数 - 1)
        float totalW = dotSpacing * (pageCount - 1);
        float startX = (w - totalW) / 2f;
        float centerY = h - bottomMargin - dotRadius;

        for (int i = 0; i < pageCount; i++) {
            float cx = startX + i * dotSpacing;

            if (i == currentPage) {
                // 选中点：青色
                int coreColor = GaugeDrawHelper.LED_GREEN;

                // 核心
                dotPaint.setColor(coreColor);
                dotPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, centerY, activeRadius, dotPaint);
            } else {
                // 未选中点：暗灰色
                dotPaint.setColor(0xFF4A5A6A);
                dotPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, centerY, dotRadius, dotPaint);
            }
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                lastMoveX = ev.getX();
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    return true;
                }
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    dragging = true;
                    lastMoveX = ev.getX();
                    cancelChildTouch(ev);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void cancelChildTouch(MotionEvent ev) {
        MotionEvent cancel = MotionEvent.obtain(ev);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        View child = getChildAt(currentPage);
        if (child != null) {
            child.dispatchTouchEvent(cancel);
        }
        cancel.recycle();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    float dx = event.getX() - lastMoveX;
                    lastMoveX = event.getX();
                    scrollBy(-(int) dx, 0);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    settlePage();
                    dragging = false;
                    return true;
                }
                break;
        }
        return false;
    }

    private void settlePage() {
        if (pageWidth <= 0) return;
        int scrollX = getScrollX();
        int nearest = Math.round(scrollX / (float) pageWidth);
        setPage(nearest, true);
    }

    public void setPage(int page, boolean smooth) {
        int count = getChildCount();
        if (count == 0 || pageWidth <= 0) return;
        int target = Math.max(0, Math.min(page, count - 1));
        int targetX = target * pageWidth;
        if (target == currentPage && getScrollX() == targetX) {
            return;
        }
        currentPage = target;
        if (smooth) {
            scroller.startScroll(getScrollX(), 0, targetX - getScrollX(), 0, SCROLL_DURATION);
            invalidate();
        } else {
            scrollTo(targetX, 0);
        }
        invalidate();
        if (onPageChangeListener != null) {
            onPageChangeListener.onPageChanged(target);
        }
    }

    public void nextPage() {
        setPage(currentPage + 1, true);
    }

    public void prevPage() {
        setPage(currentPage - 1, true);
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setOnPageChangeListener(OnPageChangeListener listener) {
        this.onPageChangeListener = listener;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), scroller.getCurrY());
            postInvalidate();
        }
    }
}