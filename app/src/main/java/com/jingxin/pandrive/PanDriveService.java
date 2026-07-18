package com.jingxin.pandrive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.jingxin.pandrive.data.DataHub;

import com.jingxin.pandrive.util.CompatUtils;

import java.lang.reflect.Method;

/**
 * 前台服务：在Service中注册高德广播接收器
 * 确保在画中画/窗口模式下仍能接收到高德广播
 * 参考静心音乐 MusicPlayerService 的实现模式
 */
public class PanDriveService extends Service {

    private static final String TAG = "PanDriveService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "pandrive_service";

    // 内部广播：转发高德广播给Activity
    public static final String ACTION_AMAP_DATA = "com.jingxin.pandrive.AMAP_DATA";
    public static final String EXTRA_ORIGINAL_INTENT = "original_intent";

    // 退出广播
    public static final String ACTION_EXIT = "com.jingxin.pandrive.EXIT";

    // Amap broadcast
    private static final String ACTION_AUTONAVI = "AUTONAVI_STANDARD_BROADCAST_SEND";

    private BroadcastReceiver amapReceiver;
    private BroadcastReceiver exitReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "PanDriveService 创建");

        createNotificationChannel();
        safeStartForeground(this, NOTIFICATION_ID, buildNotification());

        // 在Service中注册高德广播接收器
        registerAmapReceiver();

        // 注册退出广播接收器
        registerExitReceiver();

        // 更新通知（带退出按钮）
        updateNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterAmapReceiver();
        unregisterExitReceiver();
        Log.d(TAG, "PanDriveService 销毁");
    }

    /**
     * 在Service中注册高德广播接收器
     * 使用Service Context注册，确保画中画/窗口模式下仍然能收到广播
     */
    private void registerAmapReceiver() {
        if (amapReceiver != null) return;

        amapReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_AUTONAVI.equals(intent.getAction())) return;

                int keyType = intent.getIntExtra("KEY_TYPE", -1);
                Log.d(TAG, "Service收到高德广播: KEY_TYPE=" + keyType);

                // 1. 直接触发DataHub解析（DataHub是单例，App Context生命周期）
                DataHub.getInstance(PanDriveService.this).onAmapBroadcastReceived(intent);

                // 2. 转发内部广播给Activity（如果Activity存活）
                //    使用setPackage限制为本应用，避免外部接收
                Intent forward = new Intent(ACTION_AMAP_DATA);
                forward.setPackage(getPackageName());
                // 将原始广播的关键数据复制到转发Intent
                copyAmapExtras(intent, forward);
                sendBroadcast(forward);
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_AUTONAVI);
        CompatUtils.safeRegisterReceiverExported(this, amapReceiver, filter);
        Log.d(TAG, "高德广播接收器已在Service中注册");
    }

    private void unregisterAmapReceiver() {
        if (amapReceiver != null) {
            try {
                unregisterReceiver(amapReceiver);
            } catch (Exception ignored) {}
            amapReceiver = null;
        }
    }

    /**
     * 注册退出广播接收器：通知栏"退出"按钮点击时触发
     */
    private void registerExitReceiver() {
        if (exitReceiver != null) return;
        exitReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_EXIT.equals(intent.getAction())) {
                    Log.d(TAG, "收到退出广播，停止服务");
                    stopSelf();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_EXIT);
        CompatUtils.safeRegisterReceiverExported(this, exitReceiver, filter);
    }

    private void unregisterExitReceiver() {
        if (exitReceiver != null) {
            try {
                unregisterReceiver(exitReceiver);
            } catch (Exception ignored) {}
            exitReceiver = null;
        }
    }

    /**
     * 复制高德广播的关键字段到转发Intent
     */
    private void copyAmapExtras(Intent src, Intent dst) {
        dst.putExtra("KEY_TYPE", src.getIntExtra("KEY_TYPE", -1));

        int keyType = src.getIntExtra("KEY_TYPE", -1);
        switch (keyType) {
            case 10001: // NAVI_GUIDE
                dst.putExtra("ICON", src.getIntExtra("ICON", 0));
                dst.putExtra("NEW_ICON", src.getIntExtra("NEW_ICON", 0));
                dst.putExtra("CUR_SPEED", src.getIntExtra("CUR_SPEED", 0));
                dst.putExtra("LIMITED_SPEED", src.getIntExtra("LIMITED_SPEED", -1));
                copyStringExtra(src, dst, "NEXT_ROAD_NAME");
                copyStringExtra(src, dst, "CUR_ROAD_NAME");
                dst.putExtra("ROUTE_REMAIN_TIME", src.getIntExtra("ROUTE_REMAIN_TIME", 0));
                copyStringExtra(src, dst, "ETA_TEXT");
                copyStringExtra(src, dst, "SEG_REMAIN_DIS_AUTO");
                copyStringExtra(src, dst, "ROUTE_REMAIN_DIS_AUTO");
                copyStringExtra(src, dst, "endPOIName");
                break;
            case 60073: // TRAFFIC_LIGHT
                dst.putExtra("trafficLightStatus", src.getIntExtra("trafficLightStatus", -1));
                dst.putExtra("dir", src.getIntExtra("dir", 0));
                dst.putExtra("greenLightLastSecond", src.getIntExtra("greenLightLastSecond", 0));
                dst.putExtra("redLightCountDownSeconds", src.getIntExtra("redLightCountDownSeconds", 0));
                copyStringExtra(src, dst, "lightsData");
                break;
            case 13011: // TMC
                copyStringExtra(src, dst, "EXTRA_TMC_SEGMENT");
                break;
            case 10019: // DAY_NIGHT
                dst.putExtra("EXTRA_STATE", src.getIntExtra("EXTRA_STATE", -1));
                break;
        }
    }

    private void copyStringExtra(Intent src, Intent dst, String key) {
        String value = src.getStringExtra(key);
        if (value != null) {
            dst.putExtra(key, value);
        }
    }

    // ==================== Notification ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "全景驾驶助手", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持广播接收器在后台运行");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("全景驾驶助手")
                .setContentText("正在接收导航数据")
                .setOngoing(true);

        if (Build.VERSION.SDK_INT >= 22) {
            builder.setStyle(new Notification.BigTextStyle());
        }

        // 退出按钮
        Intent exitIntent = new Intent(ACTION_EXIT);
        exitIntent.setPackage(getPackageName());
        android.app.PendingIntent exitPendingIntent = android.app.PendingIntent.getBroadcast(
                this, 0, exitIntent,
                (Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0));
        builder.addAction(0, "退出", exitPendingIntent);

        return builder.build();
    }

    /**
     * 更新通知（Service已创建后调用，确保带有退出按钮）
     */
    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    // ==================== Compat ====================

    /**
     * 兼容 startForeground 调用
     * Android 14+ (API 34) 必须使用 3 参数形式指定 foregroundServiceType
     * 使用反射调用，避免低版本 ART VerifyError
     */
    private static void safeStartForeground(Service service, int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Method method = Service.class.getMethod(
                        "startForeground", int.class, Notification.class, int.class);
                method.invoke(service, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } catch (Exception e) {
                Log.w(TAG, "反射调用 startForeground 3参数失败，回退2参数: " + e.getMessage());
                service.startForeground(id, notification);
            }
        } else {
            service.startForeground(id, notification);
        }
    }

}
