package com.jingxin.pandrive;

import android.app.Application;

import com.jingxin.pandrive.data.DataHub;
import com.jingxin.pandrive.floatwindow.LecoFloatManager;
import com.jingxin.pandrive.theme.ThemeController;

public class PanDriveApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize singletons
        ThemeController.getInstance(this);
        DataHub.getInstance(this);
        // 初始化乐酷桌面悬浮窗管理器
        LecoFloatManager.getInstance().init(this);
    }
}
