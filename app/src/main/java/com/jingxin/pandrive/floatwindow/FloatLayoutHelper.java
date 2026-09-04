package com.jingxin.pandrive.floatwindow;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.jingxin.pandrive.R;
import com.jingxin.pandrive.data.DataHub;

/**
 * 悬浮态布局适配工具。
 * <p>
 * 悬浮态下不能用 Configuration.orientation 判断横竖屏，
 * 需要按悬浮区域实际宽高比判断。
 */
public class FloatLayoutHelper {

    /**
     * 按悬浮区域宽高比判断是否横屏。
     * 优先级：rootLayout 实际尺寸 > LecoFloatManager 悬浮区域 > 屏幕尺寸。
     */
    public static boolean isLandscapeMode(View rootLayout) {
        int width = 0;
        int height = 0;

        // 1. 优先取根视图实际测量尺寸
        if (rootLayout != null && rootLayout.getWidth() > 0 && rootLayout.getHeight() > 0) {
            width = rootLayout.getWidth();
            height = rootLayout.getHeight();
        }

        // 2. 回退到悬浮区域尺寸
        if (width <= 0 || height <= 0) {
            LecoFloatManager fm = LecoFloatManager.getInstance();
            if (fm.canFloat()) {
                width = fm.getFloatWidth();
                height = fm.getFloatHeight();
            }
        }

        // 3. 最终回退到屏幕尺寸
        if (width <= 0 || height <= 0) {
            if (rootLayout != null) {
                Resources res = rootLayout.getResources();
                width = res.getDisplayMetrics().widthPixels;
                height = res.getDisplayMetrics().heightPixels;
            }
        }

        return width > height * 1.1f;
    }

    /**
     * 按悬浮区域尺寸重新应用五区域权重。
     *
     * @param rootLayout 悬浮的根 View（GridBackgroundView）
     * @param dataHub    数据中心
     */
    public static void applyLayoutWeightsForFloat(View rootLayout, DataHub dataHub) {
        if (rootLayout == null || dataHub == null) return;

        boolean isLandscape = isLandscapeMode(rootLayout);
        float[] weights = dataHub.getLayoutWeights(!isLandscape);

        // rootLayout 是 GridBackgroundView，第一个子 View 是 LinearLayout
        if (!(rootLayout instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) rootLayout;
        if (root.getChildCount() == 0) return;

        View linearLayout = root.getChildAt(0);
        if (!(linearLayout instanceof ViewGroup)) return;
        ViewGroup sections = (ViewGroup) linearLayout;

        for (int i = 0; i < sections.getChildCount() && i < weights.length; i++) {
            View child = sections.getChildAt(i);
            setVerticalWeight(child, weights[i]);
        }
    }

    private static void setVerticalWeight(View view, float weight) {
        if (view == null) return;
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (!(lp instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
        if (weight <= 0) {
            // 3D车模区域不用GONE（避免TextureView detach杀GL线程），仅height=0
            if (view.getId() == R.id.section_car3d) {
                params.height = 0;
                params.weight = 0;
            } else {
                view.setVisibility(View.GONE);
                params.weight = 0;
            }
        } else {
            view.setVisibility(View.VISIBLE);
            params.height = 0;
            params.weight = weight;
        }
        view.setLayoutParams(params);
    }
}
