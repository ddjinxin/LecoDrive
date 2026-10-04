package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Scroller;
import android.widget.FrameLayout;

import com.jingxin.pandrive.R;
import com.jingxin.pandrive.data.DataHub;
import com.jingxin.pandrive.lincoln.LincolnPageView;
import com.jingxin.pandrive.PanDriveService;

/**
 * 全屏水平分页容器：第 0 页=首页（日期/仪表/导航/3D），第 1 页=林肯车况页。
 * <p>
 * 左滑切到林肯页，右滑回首页。背景由 GridBackgroundView 绘制（本容器透明），
 * 翻页时壁纸固定、仅内容层平移。
 * <p>
 * 事件分发（地理分区拦截）：
 * - 按下点落在 DashboardView（仪表区自带 4 页滑动）内 → 全程不拦截，仪表区自治；
 * - 按下点落在林肯页车图区内 → 全程不拦截（林肯页无整页子手势，安全）；
 * - 其余区域 → 检测到水平拖拽超过阈值时父容器拦截，执行整页切换；
 * - 垂直滑动不拦截。
 * <p>
 * 页宽 = 容器宽（悬浮态下即悬浮窗宽，整页翻页）。
 * 底部绘制页面指示点（LED 风格），提示可滑动方向。
 * <p>
 * 零第三方依赖，悬浮态随 GridBackgroundView 整体剥离，天然兼容。
 */
public class MainPagerView extends FrameLayout {

    private static final int SCROLL_DURATION = 300;
    private static final float SLOP_FACTOR = 1.2f;

    // ====== 林肯页开关（SP 持久化，默认关闭） ======
    private static final String PREFS_LINCOLN = "lincoln";
    private static final String KEY_CARD_ENABLED = "card_enabled";

    /** 静态实例引用（仿 GridBackgroundView.getInstance 模式，供设置页即时刷新） */
    private static MainPagerView instance;

    private final Scroller scroller = new Scroller(getContext());
    private final int touchSlop;

    private int currentPage = 0;
    private boolean dragging = false;
    private float downX, downY;
    private float lastMoveX;
    /** 按下点是否落在放行区（仪表区/林肯车图区），是则整页滑动全程让位 */
    private boolean downInFreeZone = false;

    /** 每页宽度（= 容器宽） */
    private int pageWidth = 0;

    /** 林肯页引用（开关增删时复用，避免重建） */
    private LincolnPageView lincolnPageView;

    private OnPageChangeListener onPageChangeListener;

    // ====== 页面指示点 ======
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 指示点绘制边距（dp） */
    private static final float DOT_MARGIN_DP = 10f;

    public interface OnPageChangeListener {
        void onPageChanged(int page);
    }

    public MainPagerView(Context context) {
        this(context, null);
    }

    public MainPagerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MainPagerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = (int) (ViewConfiguration.get(context).getScaledTouchSlop() * SLOP_FACTOR);
        setWillNotDraw(false);
        instance = this;
    }

    public static MainPagerView getInstance() {
        return instance;
    }

    /** 读取林肯页开关（默认关闭） */
    public static boolean isLincolnEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS_LINCOLN, Context.MODE_PRIVATE)
                .getBoolean(KEY_CARD_ENABLED, false);
    }

    /** 写入开关并即时刷新主界面分页容器（设置页双套绑定共用入口） */
    public static void setLincolnEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS_LINCOLN, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CARD_ENABLED, enabled).apply();
        MainPagerView pager = getInstance();
        if (pager != null) pager.applyLincolnEnabled();
        // 开关切换后重推里程/油量给滚轮（车机优先替换逻辑即时生效，
        // 不必等下一次广播；并主动查询一次车况快照，开启时拿到当前值更快）
        DataHub.getInstance(ctx).refreshMileageAndFuel();
        PanDriveService.queryVehicleData(ctx);
    }

    /** 按开关状态增删林肯页：关=移除+回首页；开=加回末尾 */
    private void applyLincolnEnabled() {
        boolean enabled = isLincolnEnabled(getContext());
        if (enabled) {
            if (lincolnPageView != null && indexOfChild(lincolnPageView) < 0) {
                addView(lincolnPageView);
                requestLayout();
                invalidate();
            }
        } else {
            if (lincolnPageView != null && indexOfChild(lincolnPageView) >= 0) {
                removeView(lincolnPageView);
                setPage(0, false);   // 回首页并回调（恢复设置按钮+天气文字）
                requestLayout();
                invalidate();
            }
        }
    }

    /** MainActivity 启动时调用（公开入口）：先同步引用再按开关增删 */
    public void applyLincolnEnabledFromMain() {
        if (lincolnPageView == null) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child instanceof LincolnPageView) {
                    lincolnPageView = (LincolnPageView) child;
                }
            }
        }
        applyLincolnEnabled();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof LincolnPageView) {
                lincolnPageView = (LincolnPageView) child;
            }
        }
        // 默认关闭：XML inflate 后立即移除林肯页
        if (!isLincolnEnabled(getContext()) && lincolnPageView != null) {
            removeView(lincolnPageView);
        }
    }

    public void setOnPageChangeListener(OnPageChangeListener l) {
        onPageChangeListener = l;
    }

    /** 林肯页实例引用（开关关闭时被移出容器，引用仍保留，重新开启时复用） */
    public LincolnPageView getLincolnPageView() {
        return lincolnPageView;
    }

    /** 当前页索引（0=首页 1=林肯页） */
    public int getCurrentPage() {
        return currentPage;
    }

    /** 是否处于林肯页 */
    public boolean isOnLincolnPage() {
        return currentPage == 1;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        pageWidth = width;

        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            child.measure(
                    MeasureSpec.makeMeasureSpec(pageWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int height = bottom - top;
        if (pageWidth <= 0) {
            pageWidth = right - left;
        }
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            int l = i * pageWidth;
            child.layout(l, 0, l + pageWidth, height);
        }
        if (pageWidth > 0) {
            scrollTo(currentPage * pageWidth, 0);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        // 指示点画在右下角固定位置（不受 scrollX 影响）
        drawPageIndicator(canvas);
    }

    /** 右下角页面指示点：当前页青色发光大点，另一页暗灰小点 */
    private void drawPageIndicator(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        int pageCount = getChildCount();
        if (pageCount <= 1) return;

        float density = getResources().getDisplayMetrics().density;
        float dotRadius = 2.5f * density;       // 未选中点半径
        float activeRadius = 4f * density;      // 选中点半径
        float spacing = 12f * density;          // 点间距
        float margin = DOT_MARGIN_DP * density; // 距右下角边距

        // 两点靠右下角排布
        float x1 = w - margin - activeRadius;
        float x0 = x1 - spacing;
        float cy = h - margin - activeRadius;

        dotPaint.setStyle(Paint.Style.FILL);
        // 0 点（首页）
        dotPaint.setColor(currentPage == 0 ? GaugeDrawHelper.LED_GREEN : 0xFF4A5A6A);
        canvas.drawCircle(x0, cy, currentPage == 0 ? activeRadius : dotRadius, dotPaint);
        // 1 点（林肯页）
        dotPaint.setColor(currentPage == 1 ? GaugeDrawHelper.LED_GREEN : 0xFF4A5A6A);
        canvas.drawCircle(x1, cy, currentPage == 1 ? activeRadius : dotRadius, dotPaint);
    }

    /** 判断按下点是否落在放行区：仪表区（自带横向翻页）或林肯页车图区 */
    private boolean isInFreeZone(float x, float y) {
        // 坐标转为本容器局部坐标（ev 坐标已含 scrollX）
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof LincolnPageView) {
                if (((LincolnPageView) child).isTouchInCarZone(x, y)) {
                    return true;
                }
            }
        }
        // 仪表区是 home_page 的子孙 View，用边界判定（instanceof 扫直接子 View 匹配不到）
        View dashboard = findViewById(R.id.dashboard_view);
        if (dashboard != null && dashboard.getVisibility() == View.VISIBLE
                && x >= dashboard.getLeft() && x <= dashboard.getRight()
                && y >= dashboard.getTop() && y <= dashboard.getBottom()) {
            return true;
        }
        // 3D 车模区域：拖拽旋转/双指缩放手势自治，父容器不拦截
        View car3d = findViewById(R.id.section_car3d);
        if (car3d != null && car3d.getVisibility() == View.VISIBLE
                && x >= car3d.getLeft() && x <= car3d.getRight()
                && y >= car3d.getTop() && y <= car3d.getBottom()) {
            return true;
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                lastMoveX = ev.getX();
                dragging = false;
                downInFreeZone = isInFreeZone(downX, downY);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (downInFreeZone) return false;   // 放行区：仪表/林肯车图自治
                if (dragging) return true;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    dragging = true;
                    lastMoveX = ev.getX();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                lastMoveX = event.getX();
                dragging = false;
                downInFreeZone = isInFreeZone(downX, downY);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    if (downInFreeZone) return false;
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        dragging = true;
                        lastMoveX = event.getX();
                    }
                }
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

    /** 切换页面。page: 0=首页 1=林肯页 */
    public void setPage(int page, boolean smooth) {
        int count = getChildCount();
        if (page < 0) page = 0;
        if (page > count - 1) page = count - 1;
        currentPage = page;
        if (pageWidth <= 0) return;
        int target = currentPage * pageWidth;
        if (smooth) {
            scroller.startScroll(getScrollX(), 0, target - getScrollX(), 0, SCROLL_DURATION);
            invalidate();
        } else {
            scrollTo(target, 0);
        }
        if (onPageChangeListener != null) {
            onPageChangeListener.onPageChanged(currentPage);
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), scroller.getCurrY());
            postInvalidate();
        }
    }
}
