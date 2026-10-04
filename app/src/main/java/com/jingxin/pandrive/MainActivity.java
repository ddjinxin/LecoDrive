package com.jingxin.pandrive;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.util.DisplayMetrics;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.FrameLayout;

import com.jingxin.pandrive.data.DataHub;
import com.jingxin.pandrive.data.WeatherHelper;
import com.jingxin.pandrive.floatwindow.FloatLayoutHelper;
import com.jingxin.pandrive.floatwindow.LecoFloatManager;
import com.jingxin.pandrive.gl.Car3DRenderer;
import com.jingxin.pandrive.gl.GlTextureRenderer;
import com.jingxin.pandrive.theme.ThemeController;
import com.jingxin.pandrive.view.ClockView;
import com.jingxin.pandrive.view.CompassView;
import com.jingxin.pandrive.view.CompassViewMinimal;
import com.jingxin.pandrive.view.CalendarView;
import com.jingxin.pandrive.view.DashboardView;
import com.jingxin.pandrive.view.DateTimeView;
import com.jingxin.pandrive.view.GridBackgroundView;
import com.jingxin.pandrive.view.ICompassView;
import com.jingxin.pandrive.view.LaneView;
import com.jingxin.pandrive.view.MileageView;
import com.jingxin.pandrive.view.NavigationBarView;
import com.jingxin.pandrive.view.SettingsView;
import com.jingxin.pandrive.view.SpeedometerView;
import com.jingxin.pandrive.update.UpdateChecker;

import com.jingxin.pandrive.view.MainPagerView;

public class MainActivity extends Activity implements
        ThemeController.OnThemeChangeListener,
        DataHub.OnSpeedListener,
        DataHub.OnDirectionListener,
        DataHub.OnNavigationListener,
        DataHub.OnModeListener,
        DataHub.OnMileageListener,
        DataHub.OnFuelListener,
        DataHub.OnLocationListener,
        DataHub.OnVehicleStatusListener {

    private static final int REQ_NOTIFICATION = 1;
    private static final int REQ_LOCATION = 2;
    private static final int REQ_STORAGE = 3;
    private static final int REQ_ALL_FILES = 4;
    private static final int REQ_OVERLAY = 5;

    // 悬浮布局刷新广播接收器
    private android.content.BroadcastReceiver floatLayoutReceiver;

    private DateTimeView dateTimeView;
    private SpeedometerView speedometerView;
    private DashboardView dashboardView;
    private CompassView compassView;
    private CompassViewMinimal compassViewMinimal;
    private ICompassView activeCompassView;
    private int compassStyle = 0; // 0=metal, 1=minimal
    private NavigationBarView navigationBarView;
    private LaneView laneView;
    private ClockView clockView;
    private CalendarView calendarView;
    private MileageView mileageView;
    private GridBackgroundView gridBackgroundView;
    private TextureView textureView;
    private Car3DRenderer car3DRenderer;
    private GlTextureRenderer glTextureRenderer;
    private android.widget.ImageView themeButton;
    private SettingsView settingsView; // 悬浮态下的设置页 View
    private MainPagerView mainPager;
    private com.jingxin.pandrive.lincoln.LincolnPageView lincolnPage;

    private ThemeController themeController;
    private DataHub dataHub;
    private WeatherHelper weatherHelper;

    // 3D touch state
    private float lastTouchX, lastTouchY;
    private float lastPinchDistance = 0f;
    private boolean isPinching = false;

    // 当前是否处于窗口/画中画模式
    private boolean isInWindowMode = false;
    // 乐酷桌面检查未通过时跳过生命周期
    private boolean checkFailed = false;
    // 模型切换防抖时间戳
    private long lastModelSwitchTime = 0;

    // ==================== 悬浮态 findViewById 重定向 ====================

    /**
     * 悬浮态下内容 View 被剥离到覆盖窗口，Activity.findViewById 找不到子 View。
     * 重写后悬浮态自动从 LecoFloatManager 的覆盖窗口容器中查找，非悬浮态走原逻辑。
     */
    @Override
    public <T extends View> T findViewById(int id) {
        LecoFloatManager fm = LecoFloatManager.getInstance();
        if (fm.isCurrentFloatingActivity(this)) {
            View v = fm.findViewById(id);
            if (v != null) return (T) v;
        }
        return super.findViewById(id);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 根据当前模式动态设置全屏或窗口标志
        applyFullscreenMode();

        setContentView(R.layout.activity_main);

        themeController = ThemeController.getInstance(this);
        dataHub = DataHub.getInstance(this);
        weatherHelper = WeatherHelper.getInstance();
        weatherHelper.init(this);

        // Bind views
        dateTimeView = findViewById(R.id.section_datetime);
        dashboardView = findViewById(R.id.dashboard_view);
        speedometerView = findViewById(R.id.speedometer_view);
        compassView = findViewById(R.id.compass_view);
        compassViewMinimal = findViewById(R.id.compass_view_minimal);
        activeCompassView = compassView;
        navigationBarView = findViewById(R.id.section_navigation);
        laneView = findViewById(R.id.lane_view);
        clockView = findViewById(R.id.clock_view);
        calendarView = findViewById(R.id.calendar_view);
        mileageView = findViewById(R.id.mileage_view);
        gridBackgroundView = findViewById(R.id.grid_background);
        themeButton = findViewById(R.id.theme_button);
        textureView = findViewById(R.id.texture_view);
        mainPager = findViewById(R.id.main_pager);
        // 林肯页默认关闭：开关关闭时不在容器内，从 pager 的保留引用取实例
        lincolnPage = mainPager != null ? mainPager.getLincolnPageView() : null;

        // 根据横竖屏调整布局比例
        applyLayoutWeights();

        setupGL();

        // Setup touch listeners
        setupTouchListeners();

        // Register listeners
        themeController.addListener(this);
        dataHub.addSpeedListener(this);
        dataHub.addDirectionListener(this);
        dataHub.addNavigationListener(this);
        dataHub.addModeListener(this);
        dataHub.addMileageListener(this);
        dataHub.addFuelListener(this);
        dataHub.addLocationListener(this);
        dataHub.addVehicleStatusListener(this);

        // 天气回调：收到天气数据后更新视频背景和文字
        weatherHelper.setListener(new WeatherHelper.OnWeatherListener() {
            @Override
            public void onWeatherUpdated(String videoFileName, String weatherLine1, String weatherLine2, String weatherLine3, String weatherLine4) {
                runOnUiThread(() -> {
                    if (gridBackgroundView != null) {
                        // 更新天气文字
                        gridBackgroundView.setWeatherInfo(weatherLine1, weatherLine2, weatherLine3, weatherLine4);
                        // 天气动画模式：根据天气切换视频
                        if (videoFileName != null) {
                            String videoPath = GridBackgroundView.getVideoPath(videoFileName);
                            gridBackgroundView.setWeatherVideo(videoPath);
                        }
                    } else {
                    }
                });
            }
        });

        // Push initial values to views
        onMileageChanged(dataHub.getTripDistanceKm(), dataHub.getTodayDistanceKm(), dataHub.getTotalDistanceKm());
        onFuelChanged(dataHub.getFuelConsumption(), dataHub.getRecentConsumption(), dataHub.getRemainingRange(), dataHub.getRemainingPercent());

        // 天气动画模式初始化
        boolean weatherAnimEnabled = getSharedPreferences("wallpaper", MODE_PRIVATE)
                .getBoolean("weather_animation_enabled", false);
        gridBackgroundView.setWeatherAnimationMode(weatherAnimEnabled);

        // 林肯页翻页回调：林肯页激活时隐藏设置按钮+天气文字，回首页恢复
        if (mainPager != null) {
            mainPager.setOnPageChangeListener(page -> {
                boolean lincoln = page == 1;
                if (themeButton != null) {
                    themeButton.setVisibility(lincoln ? View.GONE : View.VISIBLE);
                }
                if (gridBackgroundView != null) {
                    gridBackgroundView.setSkipWeatherLabels(lincoln);
                }
                // 林肯页首次激活时按实际尺寸缩放字号+定位标签
                if (lincoln && lincolnPage != null) {
                    lincolnPage.post(() -> {
                        lincolnPage.scaleTextByRegion();
                        lincolnPage.updateData(dataHub.getVehicleStatus());
                    });
                    // 翻到林肯页即查询一次最新快照（数据静止无推送时也能拿到）
                    PanDriveService.queryVehicleData(MainActivity.this);
                }
            });
        }

        // 应用林肯页开关（关闭时移除、开启时加回，onFinishInflate 已初始化过默认关闭）
        if (mainPager != null) {
            mainPager.applyLincolnEnabledFromMain();
        }

        // 启动时用已有坐标请求天气，GPS未定位时不请求（等GPS回调驱动）
        {
            double lat0 = dataHub.getLatitude();
            double lon0 = dataHub.getLongitude();
            if (lat0 != 0 || lon0 != 0) {
                weatherHelper.forceRefresh(lat0, lon0);
            }
        }

        // Chain permission checks: notification -> storage -> location
        checkPermissionsChain();

        // 注册悬浮布局刷新广播接收器（非悬浮模式下不触发，不影响原有逻辑）
        registerFloatLayoutReceiver();

        // 启动时检查是否有已下载待安装的更新（上次后台推送未处理）
        // 如果是通过通知点击启动的（intent 带 ACTION_INSTALL_UPDATE），也在 onNewIntent 中处理
        handleUpdateIntent(getIntent());
        UpdateChecker.getInstance(this).onPendingUpdate(this);
    }

    /**
     * 注册悬浮布局刷新广播接收器。
     * 收到广播时按悬浮区域尺寸重新应用权重并刷新所有 View。
     * 非悬浮模式下不会收到此广播，完全不影响原有逻辑。
     */
    private void registerFloatLayoutReceiver() {
        floatLayoutReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (LecoFloatManager.ACTION_FLOAT_LAYOUT_REFRESH.equals(intent.getAction())) {
                    if (LecoFloatManager.getInstance().isFloating()) {
                        // 悬浮态：按悬浮区域宽高比重新应用布局权重
                        applyLayoutWeightsForFloat();
                        forceRefreshAllViews();
                    }
                    // 林肯页按页面（悬浮区域）实际尺寸全量重算（全屏/悬浮切换、区域变化都触发）
                    if (lincolnPage != null) lincolnPage.post(() -> lincolnPage.relayoutAll());
                }
            }
        };
        IntentFilter filter = new IntentFilter(LecoFloatManager.ACTION_FLOAT_LAYOUT_REFRESH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(floatLayoutReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(floatLayoutReceiver, filter);
        }
    }

    /**
     * 处理更新通知点击 Intent（通知点击直接启动 Activity，携带 ACTION_INSTALL_UPDATE）。
     */
    private void handleUpdateIntent(Intent intent) {
        if (intent != null && UpdateChecker.ACTION_INSTALL_UPDATE.equals(intent.getAction())) {
            UpdateChecker.cancelUpdateNotification(this);
            showPendingUpdateDialog();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 通知点击时 Activity 已在后台，singleTop 不会重建，走 onNewIntent
        handleUpdateIntent(intent);
    }

    /**
     * 有待安装版本时弹安装窗（启动时 / 通知点击时）。
     */
    private void showPendingUpdateDialog() {
        if (checkFailed) return;
        UpdateChecker checker = UpdateChecker.getInstance(this);
        UpdateChecker.PendingUpdate pending = checker.getPendingUpdate();
        if (pending == null) return;
        checker.showPendingInstallDialog(this, pending);
    }

    private void setupGL() {
        textureView.setOpaque(false);  // 透明背景，让下面的车道线可见
        textureView.setClickable(true);   // 确保TextureView能消费触摸事件（窗口模式下必须）
        textureView.setFocusable(true);   // 确保可以获得焦点
        car3DRenderer = new Car3DRenderer(this);
        glTextureRenderer = new GlTextureRenderer(car3DRenderer);
        car3DRenderer.setRenderRequester(glTextureRenderer);
        car3DRenderer.setSimSpeedListener(speed -> {
            if (speedometerView != null) {
                speedometerView.setSpeed(speed);
            }
            if (laneView != null) {
                laneView.onSpeedChanged(speed, speed);
            }
        });
        car3DRenderer.setSimNaviListener((iconId, remainMeters, roadName,
                                           segRemainDis, nextRoadName, speedLimit,
                                           routeRemainDis, remainTimeSec, etaText) -> {
            runOnUiThread(() -> {
                if (navigationBarView != null) {
                    navigationBarView.setSimNaviInfo(iconId, segRemainDis, nextRoadName,
                            speedLimit, routeRemainDis, remainTimeSec, etaText);
                }
            });
        });
        glTextureRenderer.setTextureView(textureView);
    }

    private void setupTouchListeners() {
        // 3D car touch: drag to rotate (only when speed=0), pinch to scale always
        textureView.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX(0);
                    lastTouchY = event.getY(0);
                    isPinching = false;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // Second finger down = start pinch
                    if (event.getPointerCount() == 2) {
                        isPinching = true;
                        lastPinchDistance = getPinchDistance(event);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    isPinching = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isPinching && event.getPointerCount() >= 2) {
                        float dist = getPinchDistance(event);
                        if (lastPinchDistance > 0) {
                            float scale = dist / lastPinchDistance;
                            car3DRenderer.onTouchScale(scale);
                        }
                        lastPinchDistance = dist;
                    } else if (!isPinching && event.getPointerCount() == 1 && car3DRenderer.canDrag()) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        car3DRenderer.onTouchDrag(dx, dy);
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    car3DRenderer.onTouchUp();
                    isPinching = false;
                    break;
            }
            return true;
        });

        // Speedometer: single tap toggles style
        speedometerView.setOnClickListener(v -> speedometerView.toggleStyle());
        speedometerView.setClickable(true);

        // Compass: single tap toggles style (在 dashboard 内部)
        View compassContainer = findViewById(R.id.compass_container);
        compassContainer.setOnClickListener(v -> toggleCompassStyle());
        compassContainer.setClickable(true);

        // Clock: single tap toggles style (在 dashboard 内部)
        View clockContainer = findViewById(R.id.clock_container);
        clockContainer.setOnClickListener(v -> clockView.toggleStyle());
        clockContainer.setClickable(true);

        // Navigation: single tap switch random model (500ms防抖)
        navigationBarView.setOnClickListener(v -> {
            if (System.currentTimeMillis() - lastModelSwitchTime < 500) return;
            lastModelSwitchTime = System.currentTimeMillis();
            car3DRenderer.switchToRandomModel();
        });
        navigationBarView.setClickable(true);

        // Theme button: click opens settings
        themeButton.setOnClickListener(v -> {
            if (LecoFloatManager.getInstance().isFloating()) {
                showFloatSettings();
            } else {
                startActivity(new Intent(this, SettingsActivity.class));
            }
        });
    }

    // ==================== Chain permission checks ====================

    private void checkPermissionsChain() {
        // Step 1: Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
                return; // wait for callback
            }
        }
        // Step 2: storage permission
        checkStoragePermission();
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "请授予文件访问权限以读取3D模型", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQ_ALL_FILES);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, REQ_ALL_FILES);
                    } catch (Exception e2) {
                        Toast.makeText(this, "无法打开权限设置页面", Toast.LENGTH_LONG).show();
                    }
                }
                return; // wait for onActivityResult
            }
        } else {
            // Android 10 and below: traditional storage permission
            if (Build.VERSION.SDK_INT >= 23) {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, REQ_STORAGE);
                    return; // wait for callback
                }
            }
        }
        // Step 3: location permission
        checkLocationPermission();
    }

    private void checkLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION);
            return; // wait for callback
        }
        // Step 4: overlay permission (for Leco float mode)
        checkOverlayPermission();
    }

    /**
     * 悬浮窗权限请求（乐酷桌面悬浮模式需要）。
     * 非悬浮模式不需要此权限，但提前请求以便乐酷发 showmap 时直接可用。
     */
    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限以支持乐酷桌面悬浮显示", Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_OVERLAY);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开悬浮窗权限设置页面", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION) {
            // Notification done, continue chain
            checkStoragePermission();
        } else if (requestCode == REQ_STORAGE) {
            // Storage result
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.READ_EXTERNAL_STORAGE.equals(permissions[i])
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    tryRetryLoadModel();
                    // 授权成功后重试读取备份文件，恢复用户设置
                    dataHub.retryLoadFromBackup();
                }
            }
            checkLocationPermission();
        } else if (requestCode == REQ_LOCATION) {
            // Location done, continue to overlay permission
            checkOverlayPermission();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ALL_FILES) {
            // Returned from MANAGE_EXTERNAL_STORAGE settings
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                tryRetryLoadModel();
                // 授权成功后重试读取备份文件，恢复用户设置
                dataHub.retryLoadFromBackup();
            } else {
                Toast.makeText(this, "未授予文件访问权限，3D模型无法加载", Toast.LENGTH_LONG).show();
            }
            // Continue chain regardless
            checkLocationPermission();
        } else if (requestCode == REQ_OVERLAY) {
            // Returned from overlay permission settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未授予悬浮窗权限，乐酷桌面悬浮模式不可用", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ==================== Lifecycle ====================

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 悬浮态下系统配置变化不影响悬浮窗（尺寸由乐酷广播控制），只刷新布局
        if (LecoFloatManager.getInstance().isFloating()) {
            applyLayoutWeightsForFloat();
            forceRefreshAllViews();
            return;
        }
        // ===== 以下为非悬浮态原有逻辑，完全不变 =====
        // 窗口大小变化时先切换全屏/窗口模式，再调整布局比例，最后刷新View
        applyFullscreenMode();
        applyLayoutWeights();
        forceRefreshAllViews();
    }

    /**
     * 根据横竖屏从DataHub读取布局比例，应用到5个区域
     * 顺序：日期时间/仪表盘/指南针/导航/车道线，合计=100
     * 悬浮态下按悬浮区域宽高比判断横竖屏，非悬浮态用 Configuration.orientation（原逻辑不变）
     */
    private void applyLayoutWeights() {
        if (LecoFloatManager.getInstance().isFloating()) {
            applyLayoutWeightsForFloat();
            return;
        }
        // ===== 以下为非悬浮态原有逻辑，完全不变 =====
        boolean isPortrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;

        float[] weights = dataHub.getLayoutWeights(isPortrait);

        View sectionDatetime = findViewById(R.id.section_datetime);
        View speedometer = findViewById(R.id.dashboard_view);
        View sectionCompassClock = findViewById(R.id.section_vehicle_info);
        View sectionNav = findViewById(R.id.section_navigation);
        View sectionAdjust = findViewById(R.id.section_adjust);
        View sectionCar3d = findViewById(R.id.section_car3d);

        boolean car3dVisible = weights[5] > 0;
        setVerticalWeight(sectionDatetime, weights[0]);
        setVerticalWeight(speedometer, weights[1]);
        setVerticalWeight(sectionCompassClock, weights[2]);
        setVerticalWeight(sectionNav, weights[3]);
        setVerticalWeight(sectionAdjust, weights[4]);
        setVerticalWeight(sectionCar3d, weights[5]);

        // 3D车模区域特殊处理：GONE会杀死GL线程，改用height=0 + GL线程pause
        if (!car3dVisible && glTextureRenderer != null) {
            glTextureRenderer.onPause();
        } else if (car3dVisible && glTextureRenderer != null) {
            glTextureRenderer.onResume();
        }

        // 通知背景重算天气文字位置
        GridBackgroundView bgv = findViewById(R.id.grid_background);
        if (bgv != null) bgv.refreshEdgeGeometry();

        // 同步 MileageView 宽度与 DashboardView 一致（布局完成后 post 读取实际宽度）
        syncMileageWidth();
    }

    /**
     * 将 MileageView 的宽度设置为与 DashboardView 实际宽度一致，
     * 保证车辆信息滚轮与上方仪表盘等宽对齐。
     */
    private void syncMileageWidth() {
        if (dashboardView == null || mileageView == null) return;
        dashboardView.post(() -> {
            int dw = dashboardView.getWidth();
            if (dw > 0) {
                mileageView.setTargetWidth(dw);
            }
        });
    }

    /**
     * weight=0 时设为 GONE（触发 onDetachedFromWindow 停动画/监听），
     * weight>0 时恢复 VISIBLE + height=0 + weight 分配。
     * 3D车模区域例外：GONE会杀死GL线程丢失纹理，改用 height=0 + GL线程 pause。
     */
    private void setVerticalWeight(View view, float weight) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
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

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
        // 系统多窗口模式切换时更新全屏标志
        applyFullscreenMode();
    }

    /**
     * 根据当前窗口模式动态应用全屏/窗口标志
     */
    private boolean isLeKuLauncherInstalled() {
        try {
            getPackageManager().getPackageInfo("com.lecoauto", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void applyFullscreenMode() {
        boolean windowMode = detectWindowMode();

        if (windowMode == isInWindowMode) {
            return;
        }
        isInWindowMode = windowMode;

        if (windowMode) {
            // 窗口/画中画模式：清除全屏标志
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 19) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
        } else {
            // 全屏模式：设置沉浸模式
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 19) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }
    }

    /**
     * 检测当前是否处于窗口/画中画模式
     */
    private boolean detectWindowMode() {
        if (Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode()) {
            return true;
        }
        try {
            DisplayMetrics screenMetrics = new DisplayMetrics();
            DisplayMetrics windowMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(screenMetrics);
            getWindowManager().getDefaultDisplay().getMetrics(windowMetrics);
            if (windowMetrics.widthPixels < screenMetrics.widthPixels * 0.9f
                    || windowMetrics.heightPixels < screenMetrics.heightPixels * 0.9f) {
                return true;
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * 窗口大小变化后强制刷新所有View
     */
    private void forceRefreshAllViews() {
        View rootView;
        if (LecoFloatManager.getInstance().isFloating()) {
            // 悬浮态：从覆盖窗口查找根 View
            rootView = LecoFloatManager.getInstance().findViewById(R.id.grid_background);
        } else {
            // 非悬浮态：原逻辑
            rootView = getWindow().getDecorView().findViewById(android.R.id.content);
        }
        if (rootView != null) {
            rootView.requestLayout();
        }
        if (gridBackgroundView != null) gridBackgroundView.invalidate();
        if (dateTimeView != null) dateTimeView.invalidate();
        if (dashboardView != null) dashboardView.invalidate();
        if (speedometerView != null) speedometerView.invalidate();
        if (compassView != null) compassView.invalidate();
        if (compassViewMinimal != null) compassViewMinimal.invalidate();
        if (clockView != null) clockView.invalidate();
        if (mileageView != null) mileageView.invalidate();
        if (laneView != null) laneView.invalidate();
        if (navigationBarView != null) navigationBarView.requestLayout();
        if (textureView != null && car3DRenderer != null) {
            car3DRenderer.requestRender();
        }
    }

    /**
     * 悬浮态下在悬浮窗口内显示设置页（叠加在 GridBackgroundView 上）
     */
    private void showFloatSettings() {
        if (settingsView != null) return; // 已显示
        settingsView = new SettingsView(this);
        settingsView.onClose = () -> {
            if (gridBackgroundView != null && settingsView != null) {
                gridBackgroundView.removeOverlay(settingsView);
            }
            settingsView = null;
            // 保存后重新应用布局（设置页可能修改了布局比例/车型等）
            applyLayoutWeights();
        };
        if (gridBackgroundView != null) {
            gridBackgroundView.addOverlay(settingsView);
        }
    }

    /**
     * 悬浮态下按悬浮区域尺寸重新应用五区域布局权重。
     * 非悬浮态不调用此方法。
     */
    private void applyLayoutWeightsForFloat() {
        View rootView = gridBackgroundView;
        if (rootView == null) {
            rootView = LecoFloatManager.getInstance().findViewById(R.id.grid_background);
        }
        if (rootView != null) {
            FloatLayoutHelper.applyLayoutWeightsForFloat(rootView, dataHub);
            // 3D车模区域 GL 线程联动
            boolean isLandscape = FloatLayoutHelper.isLandscapeMode(rootView);
            float[] weights = dataHub.getLayoutWeights(!isLandscape);
            if (weights[5] <= 0 && glTextureRenderer != null) {
                glTextureRenderer.onPause();
            } else if (weights[5] > 0 && glTextureRenderer != null) {
                glTextureRenderer.onResume();
            }
            if (rootView instanceof GridBackgroundView) {
                ((GridBackgroundView) rootView).refreshEdgeGeometry();
            }
            syncMileageWidth();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkFailed) return;
        // 日夜模式监听无论悬浮态都需注册（悬浮态下高德广播自动切换日夜模式）
        themeController.registerAmapReceiver();
        // 悬浮态：Activity 被推回后台后又被拉回，LecoFloatManager 会 moveTaskToBack。
        // 此处跳过重复初始化，View 已在悬浮窗口中正常运行。
        if (LecoFloatManager.getInstance().isFloating()) {
            return;
        }
        // ===== 以下为非悬浮态原有逻辑，完全不变 =====
        applyLayoutWeights();
        dataHub.registerSensors();
        dataHub.registerLocation();
        dataHub.startFuelSampling();

        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                tryRetryLoadModel();
            }
        }

        // 启动时用已有坐标主动请求天气（避免等GPS回调）
        double lat = dataHub.getLatitude();
        double lon = dataHub.getLongitude();
        if (lat != 0 || lon != 0) {
            weatherHelper.forceRefresh(lat, lon);
        }

        // 加载壁纸
        if (gridBackgroundView != null) {
            // 确保天气视频已从assets复制到设备存储（首次安装/存储刚就绪时）
            weatherHelper.ensureWeatherVideos();

            // 同步恢复天气文字：车机多窗口模式下切换天气动画后 MainActivity 可能被重建，
            // 新的 GridBackgroundView 实例文字变量为空；此处从 WeatherHelper 单例缓存
            // 立即恢复上次天气文字，避免等待 30 分钟轮询或异步 forceRefresh 返回。
            // 华为手机等不重建的场景下此操作幂等无副作用，网络刷新后回调会再次覆盖。
            String[] weatherLines = weatherHelper.getWeatherInfoLines();
            if (weatherLines != null) {
                gridBackgroundView.setWeatherInfo(weatherLines[0], weatherLines[1],
                        weatherLines[2], weatherLines[3]);
            }

            // 刷新天气动画模式（可能从设置页变更）
            boolean weatherAnimEnabled = getSharedPreferences("wallpaper", MODE_PRIVATE)
                    .getBoolean("weather_animation_enabled", false);
            gridBackgroundView.setWeatherAnimationMode(weatherAnimEnabled);

            // 天气动画开启时，主动用当前天气视频加载（回调只在天气变化时触发，重开开关不会再次触发）
            if (weatherAnimEnabled) {
                String videoFile = weatherHelper.getVideoFileName();
                if (videoFile != null) {
                    String videoPath = GridBackgroundView.getVideoPath(videoFile);
                    gridBackgroundView.setWeatherVideo(videoPath);
                }
            }

            // 检查壁纸是否在 FilePickerActivity 中被更换（多窗口模式下立即复制）
            if (GridBackgroundView.wallpaperChanged) {
                GridBackgroundView.wallpaperChanged = false;
            }
            gridBackgroundView.reloadWallpaper();
            gridBackgroundView.resumeWallpaper();
        }

        // 启动时检查应用更新（内部已做"本次启动只查一次"去重）
        UpdateChecker.getInstance(this).checkOnLaunch(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (checkFailed) return;
        // 悬浮态下不暂停壁纸（View 在悬浮窗口中继续运行）
        if (LecoFloatManager.getInstance().isFloating()) return;
        // ===== 以下为非悬浮态原有逻辑，完全不变 =====
        // GL渲染的暂停/恢复改由onStop/onStart控制
        dataHub.unregisterSensors();
        // 退出时把油耗tick累加值+所有设置同步到备份文件
        dataHub.persistAll();
        if (gridBackgroundView != null) gridBackgroundView.pauseWallpaper();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (checkFailed) return;
        // 悬浮态下 GL 线程保持运行，不重复 resume
        if (glTextureRenderer != null && !LecoFloatManager.getInstance().isFloating()) {
            glTextureRenderer.onResume();
        }
        Intent serviceIntent = new Intent(this, PanDriveService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (checkFailed) return;
        // 兜底再保存一次，确保能耗累加器不丢
        dataHub.persistAll();
        // 悬浮态下 GL 线程保持运行，不暂停（Activity 被 onStop 但悬浮窗仍需渲染）
        if (glTextureRenderer != null && !LecoFloatManager.getInstance().isFloating()) {
            glTextureRenderer.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (checkFailed) return;
        // 注销悬浮布局刷新广播接收器
        if (floatLayoutReceiver != null) {
            try { unregisterReceiver(floatLayoutReceiver); } catch (Exception ignored) {}
            floatLayoutReceiver = null;
        }
        themeController.removeListener(this);
        dataHub.removeSpeedListener(this);
        dataHub.removeDirectionListener(this);
        dataHub.removeNavigationListener(this);
        dataHub.removeModeListener(this);
        dataHub.removeMileageListener(this);
        dataHub.removeFuelListener(this);
        dataHub.removeLocationListener(this);
        dataHub.unregisterSensors();
        dataHub.stopFuelSampling();
        dataHub.unregisterLocation();
        dataHub.persistAll();
        themeController.unregisterAmapReceiver();
        if (gridBackgroundView != null) gridBackgroundView.release();
        // 不在此处 stopService：前台服务通过通知栏"退出"按钮由用户主动停止
    }

    // ==================== Theme & Data callbacks ====================

    @Override
    public void onThemeChanged(boolean isNight) {
        if (dateTimeView != null) dateTimeView.setNightMode(isNight);
        if (speedometerView != null) speedometerView.setNightMode(isNight);
        if (compassView != null) compassView.setNightMode(isNight);
        if (compassViewMinimal != null) compassViewMinimal.setNightMode(isNight);
        if (navigationBarView != null) navigationBarView.setNightMode(isNight);
        if (laneView != null) laneView.setNightMode(isNight);
        if (clockView != null) clockView.setNightMode(isNight);
        if (calendarView != null) calendarView.setNightMode(isNight);
        if (mileageView != null) mileageView.setNightMode(isNight);
        if (mileageView != null) mileageView.setVehicleType(dataHub.getVehicleType());
        if (mileageView != null) mileageView.setRollerAlpha(dataHub.getRollerAlpha());
        if (gridBackgroundView != null) gridBackgroundView.setNightMode(isNight);
        if (lincolnPage != null) lincolnPage.setNightMode(isNight);
        updateThemeButtonIcon(isNight);
    }

    @Override
    public void onSpeedChanged(int speed, int limitedSpeed) {
        if (speedometerView != null) {
            speedometerView.setSpeed(speed);
            speedometerView.setLimitedSpeed(limitedSpeed);
        }
        if (car3DRenderer != null) {
            car3DRenderer.setSpeed(speed);
        }
    }

    @Override
    public void onDirectionChanged(float azimuth) {
        if (activeCompassView != null) {
            activeCompassView.setAzimuth(azimuth);
        }
        if (car3DRenderer != null) {
            car3DRenderer.setAzimuth(azimuth);
        }
    }

    @Override
    public void onNavigationUpdated() {
        if (car3DRenderer != null && dataHub != null) {
            if (dataHub.getCurrentMode() == DataHub.MODE_NAVI) {
                car3DRenderer.setNaviIcon(dataHub.getNaviIcon(), dataHub.getSegRemainDisMeters());
            } else {
                car3DRenderer.setNaviIcon(0, -1f);
            }
        }
    }

    @Override
    public void onModeChanged(int mode) {
        if (car3DRenderer != null) {
            if (mode == DataHub.MODE_NAVI) {
                car3DRenderer.setNaviIcon(dataHub.getNaviIcon(), dataHub.getSegRemainDisMeters());
            } else {
                car3DRenderer.setNaviIcon(0, -1f);
            }
        }
    }

    // ==================== Compass style toggle ====================

    private void toggleCompassStyle() {
        if (compassStyle == 0) {
            compassStyle = 1;
            compassView.setVisibility(View.GONE);
            compassViewMinimal.setVisibility(View.VISIBLE);
            activeCompassView = compassViewMinimal;
            compassViewMinimal.setAzimuth(compassView.currentAzimuth);
            compassViewMinimal.setNightMode(themeController.isNightMode());
        } else {
            compassStyle = 0;
            compassViewMinimal.setVisibility(View.GONE);
            compassView.setVisibility(View.VISIBLE);
            activeCompassView = compassView;
            compassView.setAzimuth(compassViewMinimal.currentAzimuth);
            compassView.setNightMode(themeController.isNightMode());
        }
    }

    // ==================== 3D model retry ====================

    private void tryRetryLoadModel() {
        if (car3DRenderer != null && car3DRenderer.hasPendingLoad()) {
            car3DRenderer.retryLoadModel();
            car3DRenderer.requestRender();
        }
    }

    private float getPinchDistance(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ==================== Mileage callback ====================

    @Override
    public void onMileageChanged(float tripKm, float todayKm, float totalKm) {
        if (mileageView != null) {
            // 车机信息卡片开关打开且收到过车机广播时，累计里程取车机真实值
            if (mainPager != null && MainPagerView.isLincolnEnabled(this) && dataHub.hasVehicleData()) {
                float odo = dataHub.getVehicleStatus().odometer;
                if (odo >= 0 && odo != 1.6777215E7f) {
                    totalKm = odo;
                }
            }
            mileageView.updateMileage(tripKm, todayKm, totalKm);
        }
    }

    @Override
    public void onFuelChanged(float overallFuelLPer100km, float recentFuelLPer100km, float remainingRangeKm, float remainingPercent) {
        if (mileageView != null) {
            // 车机信息卡片开关打开且收到过车机广播时，综合油耗/剩余油量百分比/剩余续航取车机真实值
            if (mainPager != null && MainPagerView.isLincolnEnabled(this) && dataHub.hasVehicleData()) {
                DataHub.VehicleStatus vs = dataHub.getVehicleStatus();
                float fuelPct = vs.fuelPct;
                if (fuelPct >= 0 && fuelPct <= 100) {
                    remainingPercent = fuelPct;
                }
                if (vs.range >= 0) {
                    remainingRangeKm = vs.range;
                }
                float vOverall = dataHub.getVehicleOverallFuel();
                if (vOverall > 0) {
                    overallFuelLPer100km = vOverall;
                }
            }
            mileageView.updateFuel(overallFuelLPer100km, recentFuelLPer100km, remainingRangeKm, remainingPercent);
        }
    }

    @Override
    public void onLocationChanged(double latitude, double longitude) {
        if (weatherHelper != null) {
            weatherHelper.onLocationUpdate(latitude, longitude);
        }
    }

    // ==================== Vehicle status callback（林肯车机信息广播） ====================

    @Override
    public void onVehicleStatusChanged(DataHub.VehicleStatus status) {
        // 广播在主线程分发（PanDriveService 主线程 onReceive），直接刷新林肯页
        if (lincolnPage != null) {
            lincolnPage.updateData(status);
        }
        // 车机信息卡片开关打开时，重推里程/油量给首页滚轮
        // （累计里程/剩余油量改取车机值，广播到达即刷新，不必等乐酷内部 tick）
        if (mainPager != null && MainPagerView.isLincolnEnabled(this)) {
            dataHub.refreshMileageAndFuel();
        }
    }

    // ==================== Manual theme toggle ====================

    private void updateThemeButtonIcon(boolean isNight) {
        if (themeButton == null) return;
        int color = isNight ? 0xFFFFFFFF : 0xFF000000;
        themeButton.setColorFilter(color);
    }

}
