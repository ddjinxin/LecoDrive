package com.jingxin.pandrive.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.jingxin.pandrive.data.DataHub;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一日夜模式控制器
 * 由高德Amap KEY_TYPE 10019广播自动驱动
 * 不再独立注册广播接收器，通过DataHub统一接收10019后回调
 */
public class ThemeController implements DataHub.OnDayNightListener {

    private static final String TAG = "ThemeController";
    private static final String PREFS_NAME = "theme";
    private static final String KEY_IS_NIGHT = "isNight";

    private static ThemeController instance;
    private final Context appContext;
    private boolean isNightMode = false;
    private final List<OnThemeChangeListener> listeners = new ArrayList<>();

    public interface OnThemeChangeListener {
        void onThemeChanged(boolean isNight);
    }

    private ThemeController(Context context) {
        appContext = context.getApplicationContext();
        // Restore last saved state
        isNightMode = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_IS_NIGHT, false);
    }

    public static synchronized ThemeController getInstance(Context context) {
        if (instance == null) {
            instance = new ThemeController(context);
        }
        return instance;
    }

    public boolean isNightMode() {
        return isNightMode;
    }

    public void addListener(OnThemeChangeListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        // Immediately notify new listener of current state
        listener.onThemeChanged(isNightMode);
    }

    public void removeListener(OnThemeChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * 注册为DataHub的日夜模式监听器
     * 不再独立注册广播接收器，避免同一ACTION多个接收器在车机上丢失广播
     */
    public void registerAmapReceiver() {
        DataHub.getInstance(appContext).addDayNightListener(this);
        Log.d(TAG, "已注册为DataHub日夜模式监听器");
    }

    /**
     * 取消注册
     */
    public void unregisterAmapReceiver() {
        DataHub.getInstance(appContext).removeDayNightListener(this);
    }

    /**
     * DataHub收到10019广播后的回调
     */
    @Override
    public void onDayNightChanged(boolean isNight) {
        setNightMode(isNight);
    }

    /**
     * 强制切换日夜模式（手动按钮调用，跳过去重判断）
     */
    public void forceSetNightMode(boolean isNight) {
        this.isNightMode = !isNight;  // 先设为相反值，确保setNightMode能触发
        setNightMode(isNight);
    }

    private void setNightMode(boolean isNight) {
        if (this.isNightMode == isNight) return;
        this.isNightMode = isNight;

        // Persist
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_IS_NIGHT, isNight).apply();

        Log.d(TAG, "日夜模式切换: " + (isNight ? "夜间" : "白天"));

        // Notify all listeners
        for (OnThemeChangeListener listener : listeners) {
            listener.onThemeChanged(isNight);
        }
    }
}
