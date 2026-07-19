package com.jingxin.pandrive;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.util.DisplayMetrics;
import android.widget.Toast;

import com.jingxin.pandrive.data.DataHub;
import com.jingxin.pandrive.data.WeatherHelper;
import com.jingxin.pandrive.gl.Car3DRenderer;
import com.jingxin.pandrive.gl.GlTextureRenderer;
import com.jingxin.pandrive.theme.ThemeController;
import com.jingxin.pandrive.view.ClockView;
import com.jingxin.pandrive.view.CompassView;
import com.jingxin.pandrive.view.CompassViewMinimal;
import com.jingxin.pandrive.view.DateTimeView;
import com.jingxin.pandrive.view.GridBackgroundView;
import com.jingxin.pandrive.view.ICompassView;
import com.jingxin.pandrive.view.LaneView;
import com.jingxin.pandrive.view.MileageView;
import com.jingxin.pandrive.view.NavigationBarView;
import com.jingxin.pandrive.view.SpeedometerView;
import com.jingxin.pandrive.update.UpdateChecker;

public class MainActivity extends Activity implements
        ThemeController.OnThemeChangeListener,
        DataHub.OnSpeedListener,
        DataHub.OnDirectionListener,
        DataHub.OnNavigationListener,
        DataHub.OnModeListener,
        DataHub.OnMileageListener,
        DataHub.OnFuelListener,
        DataHub.OnLocationListener {

    private static final int REQ_NOTIFICATION = 1;
    private static final int REQ_LOCATION = 2;
    private static final int REQ_STORAGE = 3;
    private static final int REQ_ALL_FILES = 4;

    private DateTimeView dateTimeView;
    private SpeedometerView speedometerView;
    private CompassView compassView;
    private CompassViewMinimal compassViewMinimal;
    private ICompassView activeCompassView;
    private int compassStyle = 0; // 0=metal, 1=minimal
    private NavigationBarView navigationBarView;
    private LaneView laneView;
    private ClockView clockView;
    private MileageView mileageView;
    private GridBackgroundView gridBackgroundView;
    private TextureView textureView;
    private Car3DRenderer car3DRenderer;
    private GlTextureRenderer glTextureRenderer;
    private android.widget.ImageView themeButton;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 根据当前模式动态设置全屏或窗口标志
        applyFullscreenMode();

        setContentView(R.layout.activity_main);

        // 检查乐酷桌面是否已安装
        if (!isLeKuLauncherInstalled()) {
            checkFailed = true;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("无法启动")
                    .setMessage("乐酷驾驶助手是乐酷桌面配套应用，请先安装乐酷桌面，https://lecoauto.com")
                    .setPositiveButton("确定", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
            return;
        }

        themeController = ThemeController.getInstance(this);
        dataHub = DataHub.getInstance(this);
        weatherHelper = WeatherHelper.getInstance();
        weatherHelper.init(this);

        // Bind views
        dateTimeView = findViewById(R.id.section_datetime);
        speedometerView = findViewById(R.id.speedometer_view);
        compassView = findViewById(R.id.compass_view);
        compassViewMinimal = findViewById(R.id.compass_view_minimal);
        activeCompassView = compassView;
        navigationBarView = findViewById(R.id.section_navigation);
        laneView = findViewById(R.id.lane_view);
        clockView = findViewById(R.id.clock_view);
        mileageView = findViewById(R.id.mileage_view);
        gridBackgroundView = findViewById(R.id.grid_background);
        themeButton = findViewById(R.id.theme_button);
        textureView = findViewById(R.id.texture_view);

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
        onFuelChanged(dataHub.getFuelConsumption(), dataHub.getRemainingRange(), dataHub.getRemainingPercent());

        // 天气动画模式初始化
        boolean weatherAnimEnabled = getSharedPreferences("wallpaper", MODE_PRIVATE)
                .getBoolean("weather_animation_enabled", false);
        gridBackgroundView.setWeatherAnimationMode(weatherAnimEnabled);

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

        // Compass: single tap toggles style
        View compassContainer = findViewById(R.id.compass_container);
        compassContainer.setOnClickListener(v -> toggleCompassStyle());
        compassContainer.setClickable(true);

        // Clock: single tap toggles style
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

        // Theme button: single tap toggles style, long press opens settings
        themeButton.setOnClickListener(v -> toggleTheme());
        themeButton.setOnLongClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
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
            // End of chain
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
        }
    }

    // ==================== Lifecycle ====================

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 窗口大小变化时先切换全屏/窗口模式，再刷新View
        applyFullscreenMode();
        forceRefreshAllViews();
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
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo("com.lecoauto", 0);
            android.util.Log.d("LeKuCheck", "getPackageInfo returned: " + info.packageName);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            android.util.Log.d("LeKuCheck", "NameNotFoundException: com.lecoauto not found");
            return false;
        } catch (Exception e) {
            android.util.Log.d("LeKuCheck", "Other exception: " + e.getClass().getName() + " " + e.getMessage());
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
        View rootView = getWindow().getDecorView().findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.requestLayout();
        }
        if (gridBackgroundView != null) gridBackgroundView.invalidate();
        if (dateTimeView != null) dateTimeView.invalidate();
        if (speedometerView != null) speedometerView.invalidate();
        if (compassView != null) compassView.invalidate();
        if (compassViewMinimal != null) compassViewMinimal.invalidate();
        if (clockView != null) clockView.invalidate();
        if (mileageView != null) mileageView.invalidate();
        if (laneView != null) laneView.invalidate();
        if (navigationBarView != null) navigationBarView.requestLayout();
        if (textureView != null) car3DRenderer.requestRender();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkFailed) return;
        dataHub.registerSensors();
        dataHub.registerLocation();
        dataHub.startFuelSampling();
        themeController.registerAmapReceiver();

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
        if (glTextureRenderer != null) {
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
        if (glTextureRenderer != null) {
            glTextureRenderer.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (checkFailed) return;
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
        if (mileageView != null) mileageView.setNightMode(isNight);
        if (mileageView != null) mileageView.setVehicleType(dataHub.getVehicleType());
        if (gridBackgroundView != null) gridBackgroundView.setNightMode(isNight);
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
            mileageView.updateMileage(tripKm, todayKm, totalKm);
        }
    }

    @Override
    public void onFuelChanged(float fuelLPer100km, float remainingRangeKm, float remainingPercent) {
        if (mileageView != null) {
            mileageView.updateFuel(fuelLPer100km, remainingRangeKm, remainingPercent);
        }
    }

    @Override
    public void onLocationChanged(double latitude, double longitude) {
        if (weatherHelper != null) {
            weatherHelper.onLocationUpdate(latitude, longitude);
        }
    }

    // ==================== Manual theme toggle ====================

    private void toggleTheme() {
        boolean isNight = !themeController.isNightMode();
        getSharedPreferences("theme", MODE_PRIVATE).edit()
                .putBoolean("isNight", isNight)
                .putBoolean("amapTriggered", false)
                .apply();
        themeController.forceSetNightMode(isNight);
        Toast.makeText(this, isNight ? "夜间模式" : "白天模式",
                Toast.LENGTH_SHORT).show();
    }

    private void updateThemeButtonIcon(boolean isNight) {
        if (themeButton == null) return;
        int color = isNight ? 0xFFFFFFFF : 0xFF000000;
        themeButton.setColorFilter(color);
    }

}
