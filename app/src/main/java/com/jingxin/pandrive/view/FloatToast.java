package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.jingxin.pandrive.floatwindow.LecoFloatManager;

/**
 * 悬浮态感知的 Toast 替代方案。
 *
 * 悬浮态下系统 Toast 被悬浮窗遮挡，改用叠加在 GridBackgroundView 上的浮层 TextView。
 * 非悬浮态走系统 Toast。
 */
public class FloatToast {

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void show(Context context, String text) {
        show(context, text, Toast.LENGTH_SHORT);
    }

    public static void show(Context context, String text, int duration) {
        if (LecoFloatManager.getInstance().isFloating()) {
            showFloatOverlay(context, text, duration);
        } else {
            Toast.makeText(context, text, duration).show();
        }
    }

    private static void showFloatOverlay(Context context, String text, int duration) {
        GridBackgroundView gv = GridBackgroundView.getInstance();
        if (gv == null) {
            Toast.makeText(context, text, duration).show();
            return;
        }

        // 如果已有浮层提示，先移除
        View existing = gv.findViewWithTag("float_toast");
        if (existing != null) {
            gv.removeView(existing);
        }

        // 容器
        FrameLayout container = new FrameLayout(context);
        container.setTag("float_toast");

        // 文本
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(48, 28, 48, 28);

        // 圆角半透明深色背景
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6000000);
        bg.setCornerRadius(20);
        tv.setBackground(bg);

        FrameLayout.LayoutParams tvLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tvLp.gravity = Gravity.CENTER;
        container.addView(tv, tvLp);

        // 添加到 GridBackgroundView 顶部
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        gv.addView(container, lp);

        // 自动消失
        int delay = (duration == Toast.LENGTH_LONG) ? 3500 : 2000;
        mainHandler.postDelayed(() -> {
            if (container.getParent() == gv) {
                gv.removeView(container);
            }
        }, delay);
    }
}
