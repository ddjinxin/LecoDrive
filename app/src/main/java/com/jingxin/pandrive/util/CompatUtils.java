package com.jingxin.pandrive.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

import java.lang.reflect.Method;

/**
 * 兼容工具类
 * 提供跨API版本的兼容方法
 */
public class CompatUtils {

    /**
     * 兼容注册导出广播接收器
     * Android 13+ (API 33) 必须使用 3 参数形式指定 RECEIVER_EXPORTED
     * 使用反射调用，避免低版本编译问题
     */
    public static void safeRegisterReceiverExported(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Method method = Context.class.getMethod(
                        "registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class);
                method.invoke(context, receiver, filter, Context.RECEIVER_EXPORTED);
            } catch (Exception e) {
                context.registerReceiver(receiver, filter);
            }
        } else {
            context.registerReceiver(receiver, filter);
        }
    }
}
