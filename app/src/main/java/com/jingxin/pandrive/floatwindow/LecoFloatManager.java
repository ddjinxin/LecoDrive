package com.jingxin.pandrive.floatwindow;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 乐酷桌面悬浮窗管理器（单例）。
 * <p>
 * 参照静心音乐 LecoFloatManager 实现，接收乐酷桌面 showmap/closemap 广播，
 * 将 Activity 的内容 View 剥离到 WindowManager 覆盖窗口中显示。
 * <p>
 * 乐酷协议关键点（不可修改）：
 * <ul>
 *   <li>广播 Action: com.autonavi.plus.showmap / com.autonavi.plus.closemap</li>
 *   <li>参数 x/y = 悬浮区域左上角屏幕坐标，w/h = 右下角坐标（非纯宽高）</li>
 *   <li>实际宽度 = floatRect.width() - floatRect.left (= w - x)</li>
 *   <li>实际高度 = floatRect.height() - floatRect.top (= h - y)</li>
 *   <li>r = 圆角半径(px)，0 为直角</li>
 * </ul>
 */
public class LecoFloatManager {

    private static final String TAG = "LecoFloatManager";

    public static final String ACTION_SHOW_MAP = "com.autonavi.plus.showmap";
    public static final String ACTION_CLOSE_MAP = "com.autonavi.plus.closemap";

    // 通知 MainActivity 按悬浮区域尺寸刷新布局
    public static final String ACTION_FLOAT_LAYOUT_REFRESH =
            "com.jingxin.pandrive.FLOAT_LAYOUT_REFRESH";

    private static volatile LecoFloatManager instance = null;

    private Application application;
    private WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 覆盖窗口相关
    private FrameLayout windowContainer;
    private WindowManager.LayoutParams windowParams;
    private Rect floatRect = new Rect();
    private float floatCornerRadius = 0f;

    // 被剥离的 View 及其原始父容器信息
    private View floatContentView;
    private ViewGroup originalParent;
    private ViewGroup.LayoutParams originalLayoutParams;
    // 悬浮期间的占位 View：放入原 Activity 窗口防止窗口变空
    // （乐酷桌面检测到应用窗口无内容会发 closemap，导致死循环）
    private View placeholderView;

    // 状态标志
    private final java.util.concurrent.atomic.AtomicBoolean canFloat = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean isFloating = new java.util.concurrent.atomic.AtomicBoolean(false);

    // 当前悬浮的 Activity
    private Activity currentFloatingActivity;

    // 广播接收器
    private FloatReceiver floatReceiver;
    private boolean receiverRegistered = false;

    // Activity 生命周期回调
    private FloatLifecycle floatLifecycle;

    private LecoFloatManager() {
    }

    public static LecoFloatManager getInstance() {
        if (instance == null) {
            synchronized (LecoFloatManager.class) {
                if (instance == null) {
                    instance = new LecoFloatManager();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化：注册广播 + Activity 生命周期监听。
     * 在 Application.onCreate() 中调用。
     */
    public void init(Application app) {
        this.application = app;
        this.windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        registerReceiver();
        floatLifecycle = new FloatLifecycle();
        app.registerActivityLifecycleCallbacks(floatLifecycle);
    }

    // ==================== 广播注册 ====================

    private void registerReceiver() {
        if (receiverRegistered) return;
        floatReceiver = new FloatReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHOW_MAP);
        filter.addAction(ACTION_CLOSE_MAP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(floatReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            application.registerReceiver(floatReceiver, filter);
        }
        receiverRegistered = true;
    }

    // ==================== 状态查询 API ====================

    public boolean isFloating() {
        return isFloating.get();
    }

    public boolean canFloat() {
        return canFloat.get();
    }

    /**
     * 主动退出悬浮态，还原 View 到全屏。
     * 用于悬浮态下需要打开新 Activity（如设置页）时先退出悬浮。
     */
    public void exitFloat() {
        canFloat.set(false);
        boolean wasFloating = isFloating.get();
        restoreCurrentActivity();
        removeFloatWindow();
        if (wasFloating) {
            mainHandler.post(this::sendLayoutRefresh);
        }
    }

    /**
     * 获取悬浮区域实际宽度（px）。
     * 乐酷协议：width() - left = w - x。
     */
    public int getFloatWidth() {
        if (floatRect.isEmpty()) return 0;
        return floatRect.width() - floatRect.left;
    }

    /**
     * 获取悬浮区域实际高度（px）。
     * 乐酷协议：height() - top = h - y。
     */
    public int getFloatHeight() {
        if (floatRect.isEmpty()) return 0;
        return floatRect.height() - floatRect.top;
    }

    public float getFloatCornerRadius() {
        return floatCornerRadius;
    }

    public boolean isCurrentFloatingActivity(Activity activity) {
        return isFloating.get() && currentFloatingActivity == activity;
    }

    /**
     * 从覆盖窗口容器中查找 View（悬浮态 findViewById 重定向用）。
     */
    public View findViewById(int id) {
        if (windowContainer != null) {
            return windowContainer.findViewById(id);
        }
        return null;
    }

    // ==================== 广播处理 ====================

    private class FloatReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_SHOW_MAP.equals(action)) {
                handleShowMap(intent);
            } else if (ACTION_CLOSE_MAP.equals(action)) {
                handleCloseMap();
            } else {
                // Unknown action
            }
        }
    }

    private void handleShowMap(Intent intent) {
        int x = intent.getIntExtra("x", 0);
        int y = intent.getIntExtra("y", 0);
        int w = intent.getIntExtra("w", 0);
        int h = intent.getIntExtra("h", 0);
        float r = intent.getFloatExtra("r", 0f);

        floatRect = new Rect(x, y, x + w, y + h);
        floatCornerRadius = r;
        canFloat.set(true);

        if (isFloating.get()) {
            // 已悬浮，只更新窗口尺寸
            updateFloatWindowSize();
        } else {
            // 首次进入悬浮
            if (currentFloatingActivity != null) {
                floatActivity(currentFloatingActivity);
            } else {
                Log.w(TAG, "showmap: currentFloatingActivity 为 null，无法进入悬浮");
            }
        }
    }

    private void handleCloseMap() {
        canFloat.set(false);
        boolean wasFloating = isFloating.get();
        restoreCurrentActivity();
        removeFloatWindow();
        if (wasFloating) {
            // 通知 MainActivity 刷新布局（从悬浮态回到全屏）
            mainHandler.post(this::sendLayoutRefresh);
        }
    }

    // ==================== 核心：进入/退出悬浮 ====================

    /**
     * 将 Activity 的内容 View 剥离到 WindowManager 覆盖窗口。
     * <p>
     * 剥离的是 android.R.id.content 下的直接子 View（GridBackgroundView），
     * 而不是整个 ContentView，这样 Activity 窗口结构保留，不会触发 onSaveInstanceState。
     */
    private void floatActivity(Activity activity) {
        if (isFloating.get()) return;
        if (activity == null || activity.isFinishing()) return;

        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(application)) {
            Log.w(TAG, "无悬浮窗权限，无法进入悬浮态");
            return;
        }

        // 防御：清理上次悬浮可能残留的窗口（windowToken 仍在但状态标记已重置等异常情况）
        if (windowContainer != null && windowContainer.getWindowToken() == null) {
            // 窗口已脱离 WindowManager 但引用还在，重置
            isFloating.set(false);
            windowContainer = null;
            windowParams = null;
        }
        if (windowContainer != null && windowContainer.getWindowToken() != null) {
            // 异常残留：先移除
            try {
                windowManager.removeViewImmediate(windowContainer);
            } catch (Exception ignored) {}
            isFloating.set(false);
            windowContainer = null;
            windowParams = null;
        }

        // 1. 找到内容 View（GridBackgroundView 是 android.R.id.content 的直接子 View）
        ViewGroup contentViewParent = activity.findViewById(android.R.id.content);
        if (contentViewParent == null || contentViewParent.getChildCount() == 0) {
            Log.w(TAG, "无法找到内容 View");
            return;
        }

        View contentView = contentViewParent.getChildAt(0);
        if (contentView == null) {
            Log.w(TAG, "内容 View 为空");
            return;
        }

        // 2. 从原父容器移除，保存原始信息
        originalParent = contentViewParent;
        originalLayoutParams = contentView.getLayoutParams();
        contentViewParent.removeView(contentView);
        floatContentView = contentView;

        // 2.5 放入占位 View，保持 Activity 窗口非空
        // （否则乐酷桌面检测应用窗口无内容→发 closemap→死循环）
        placeholderView = new View(application);
        placeholderView.setBackgroundColor(0xFF000000); // 不透明黑色背景
        contentViewParent.addView(placeholderView, originalLayoutParams);

        // 3. 创建覆盖窗口容器（用 Application Context，不依赖 Activity 生命周期）
        windowContainer = new FrameLayout(application);
        // 消费系统 Insets，避免状态栏偏移
        windowContainer.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return WindowInsets.CONSUMED;
            }
            return insets.consumeSystemWindowInsets();
        });
        // 容器本身不消费触摸，避免挡住乐酷桌面的拖动调整手柄
        windowContainer.setClickable(false);
        windowContainer.setFocusable(false);

        // 4. 计算悬浮区域尺寸（乐酷协议）
        int floatW = getFloatWidth();
        int floatH = getFloatHeight();
        if (floatW <= 0 || floatH <= 0) {
            Log.w(TAG, "悬浮区域尺寸异常: w=" + floatW + " h=" + floatH);
            // 回退到屏幕尺寸
            Resources res = application.getResources();
            floatW = res.getDisplayMetrics().widthPixels;
            floatH = res.getDisplayMetrics().heightPixels;
        }

        // 5. 将内容 View 加入覆盖窗口容器
        windowContainer.addView(contentView, new FrameLayout.LayoutParams(floatW, floatH));

        // 6. 应用圆角
        applyFloatCornerRadius();

        // 7. 创建窗口参数并添加到 WindowManager
        windowParams = createLayoutParams(floatRect);
        try {
            windowManager.addView(windowContainer, windowParams);
        } catch (Exception e) {
            Log.e(TAG, "addView 失败", e);
            // 回退：将 View 还原
            windowContainer.removeView(contentView);
            originalParent.addView(contentView, originalLayoutParams);
            floatContentView = null;
            windowContainer = null;
            return;
        }

        // 8. 强制重布局
        forceRelayout(contentView, floatW, floatH);

        // 9. 更新状态
        currentFloatingActivity = activity;
        isFloating.set(true);

        // 10. 发送布局刷新广播，通知 MainActivity 按悬浮区域尺寸重新应用权重
        mainHandler.postDelayed(this::sendLayoutRefresh, 100);
    }

    /**
     * 将内容 View 从覆盖窗口还原到原 Activity。
     */
    private void restoreCurrentActivity() {
        if (!isFloating.get()) return;
        if (floatContentView == null || windowContainer == null) {
            isFloating.set(false);
            return;
        }

        // 1. 从覆盖窗口容器移除
        windowContainer.removeView(floatContentView);

        // 2. 移除占位 View，将内容 View 还原到原父容器
        if (originalParent != null && originalParent.getWindowToken() != null) {
            // 先移除占位 View
            if (placeholderView != null && placeholderView.getParent() == originalParent) {
                originalParent.removeView(placeholderView);
            }
            originalParent.addView(floatContentView, originalLayoutParams);
            // post 到下一帧强制全屏布局
            View cv = floatContentView;
            originalParent.post(() -> {
                if (originalParent.getWidth() > 0) {
                    forceRelayout(cv, originalParent.getWidth(), originalParent.getHeight());
                }
            });
        }
        placeholderView = null;

        // 3. 清理引用（注意：不清空 currentFloatingActivity，以便后续 showmap 能重新悬浮）
        floatContentView = null;
        isFloating.set(false);
    }

    /**
     * 移除覆盖窗口。
     */
    private void removeFloatWindow() {
        if (windowContainer != null) {
            try {
                windowManager.removeViewImmediate(windowContainer);
            } catch (Exception e) {
                Log.e(TAG, "removeViewImmediate 失败", e);
            }
        }
        if (windowContainer != null) {
            windowContainer.setOutlineProvider(null);
            windowContainer.setClipToOutline(false);
        }
        windowContainer = null;
        windowParams = null;
        floatCornerRadius = 0f;
    }

    /**
     * 强制移除悬浮窗兜底方法（无视所有状态标记，直接清理）。
     * 供 PanDriveService.onDestroy 等外部兜底调用，防止进程结束后窗口残留。
     */
    public void forceRemoveFloatWindow() {
        if (windowContainer != null) {
            try {
                windowManager.removeViewImmediate(windowContainer);
            } catch (Exception e) {
                Log.e(TAG, "forceRemoveFloatWindow: removeViewImmediate 失败", e);
            }
        }
        windowContainer = null;
        windowParams = null;
        floatContentView = null;
        placeholderView = null;
        floatCornerRadius = 0f;
        isFloating.set(false);
        canFloat.set(false);
    }

    // ==================== 窗口尺寸更新 ====================

    /**
     * 已悬浮时，乐酷重复发 showmap 更新区域尺寸。
     */
    private void updateFloatWindowSize() {
        if (!isFloating.get() || windowContainer == null || windowParams == null) {
            Log.w(TAG, "updateFloatWindowSize: 条件不满足"
                    + " isFloating=" + isFloating.get()
                    + " windowContainer=" + (windowContainer != null)
                    + " windowParams=" + (windowParams != null));
            return;
        }
        // 检查窗口 token 是否有效（系统可能已移除窗口）
        if (windowContainer.getWindowToken() == null) {
            Log.w(TAG, "updateFloatWindowSize: 窗口 token 已失效，尝试重新添加");
            isFloating.set(false);
            if (currentFloatingActivity != null) {
                floatActivity(currentFloatingActivity);
            } else {
                Log.w(TAG, "updateFloatWindowSize: token 失效且 currentFloatingActivity 为 null");
            }
            return;
        }

        int newW = getFloatWidth();
        int newH = getFloatHeight();
        if (newW <= 0 || newH <= 0) return;

        // 更新窗口参数（复用同一 params 对象）
        windowParams.x = floatRect.left;
        windowParams.y = floatRect.top;
        windowParams.width = newW;
        windowParams.height = newH;

        try {
            windowManager.updateViewLayout(windowContainer, windowParams);
        } catch (Exception e) {
            Log.e(TAG, "updateViewLayout 失败，尝试重新添加窗口", e);
            // updateViewLayout 失败，可能是窗口已被系统移除，尝试重新添加
            try {
                windowManager.removeViewImmediate(windowContainer);
            } catch (Exception ignored) {}
            try {
                windowManager.addView(windowContainer, windowParams);
            } catch (Exception e2) {
                Log.e(TAG, "重新 addView 也失败", e2);
                return;
            }
        }

        // 更新内容 View 的 LayoutParams（复用已有对象，而非新建）
        if (floatContentView != null) {
            ViewGroup.LayoutParams lp = floatContentView.getLayoutParams();
            if (lp != null) {
                lp.width = newW;
                lp.height = newH;
                floatContentView.setLayoutParams(lp);
            }
            forceRelayout(floatContentView, newW, newH);
        }

        // 重新应用圆角
        applyFloatCornerRadius();

        // 通知 MainActivity 刷新布局
        sendLayoutRefresh();
    }

    // ==================== 窗口参数 ====================

    private WindowManager.LayoutParams createLayoutParams(Rect rect) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        // 乐酷协议：实际宽高 = w - x / h - y
        params.width = rect.width() - rect.left;
        params.height = rect.height() - rect.top;
        params.x = rect.left;
        params.y = rect.top;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.format = PixelFormat.RGBA_8888;

        // 窗口类型
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }

        // flags：悬浮区域内可交互，区域外触摸穿透（FLAG_NOT_TOUCH_MODAL）
        // 不加 FLAG_NOT_FOCUSABLE（与静心音乐参考一致），避免影响悬浮区域内按钮点击
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        return params;
    }

    // ==================== 圆角 ====================

    private void applyFloatCornerRadius() {
        if (windowContainer == null) return;
        if (floatCornerRadius > 0f) {
            windowContainer.setClipToOutline(true);
            windowContainer.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), floatCornerRadius);
                }
            });
            windowContainer.invalidate();
        } else {
            windowContainer.setClipToOutline(false);
            windowContainer.setOutlineProvider(null);
            windowContainer.invalidate();
        }
    }

    // ==================== 强制重布局 ====================

    private void forceRelayout(View view, int width, int height) {
        if (view == null || width <= 0 || height <= 0) return;
        int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        view.measure(wSpec, hSpec);
        view.layout(0, 0, width, height);
        view.forceLayout();
        if (windowContainer != null) windowContainer.requestLayout();
    }

    // ==================== 布局刷新广播 ====================

    private void sendLayoutRefresh() {
        Intent intent = new Intent(ACTION_FLOAT_LAYOUT_REFRESH);
        intent.setPackage(application.getPackageName());
        application.sendBroadcast(intent);
    }

    // ==================== Activity 生命周期监听 ====================

    private class FloatLifecycle implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            if (activity instanceof com.jingxin.pandrive.MainActivity) {
                currentFloatingActivity = activity;
                if (canFloat.get() && !isFloating.get()) {
                    // 乐酷请求悬浮且尚未悬浮 → 进入悬浮
                    floatActivity(activity);
                } else if (isFloating.get()) {
                    // 已悬浮，MainActivity 被拉回前台 → 推回后台，保持悬浮
                    activity.moveTaskToBack(true);
                }
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {
        }

    @Override
    public void onActivityDestroyed(Activity activity) {
            if (activity == currentFloatingActivity) {
                // Activity 销毁（横竖屏切换/内存压力），清理悬浮窗口
                // 新 Activity 重建后 onActivityResumed 会重新悬浮
                restoreCurrentActivity();
                removeFloatWindow();
            } else if (isFloating.get()) {
                // 当前悬浮的 Activity 已不在，兜底清理
                Log.w(TAG, "onActivityDestroyed: 非当前悬浮Activity但仍在悬浮态，兜底清理");
                isFloating.set(false);
                floatContentView = null;
                removeFloatWindow();
            }
        }
    }
}
