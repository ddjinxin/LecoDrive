package com.jingxin.pandrive.data;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import com.jingxin.pandrive.util.CompatUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 统一数据中心
 * 接收Amap广播、传感器、GPS数据，分发到各View
 */
public class DataHub {

    private static final String TAG = "DataHub";

    // Amap broadcast
    private static final String ACTION_AUTONAVI = "AUTONAVI_STANDARD_BROADCAST_SEND";

    // KEY_TYPE constants
    private static final int KEY_TYPE_NAVI_GUIDE = 10001;
    private static final int KEY_TYPE_TRAFFIC_LIGHT = 60073;
    private static final int KEY_TYPE_TMC = 13011;
    private static final int KEY_TYPE_DAY_NIGHT = 10019;

    // Day/Night state constants
    private static final int EXTRA_STATE_DAY = 37;
    private static final int EXTRA_STATE_NIGHT = 38;

    // Day/Night anti-debounce
    private long lastDayNightTime = 0;
    private static final long DAY_NIGHT_DEBOUNCE_MS = 500;

    // Mode constants
    public static final int MODE_CRUISE = 0;
    public static final int MODE_NAVI = 1;

    // Navigation timeout
    private static final long NAVI_TIMEOUT_MS = 15000;

    // Current data state
    private int currentMode = MODE_CRUISE;
    private int currentSpeed = 0;       // 高德广播速度(km/h)，无高德时为0
    private float gpsSpeed = 0f;        // GPS速度(m/s)，由LocationListener更新
    private int limitedSpeed = -1;
    private float azimuth = 0f;
    private double latitude = 0;
    private double longitude = 0;
    private double altitude = 0;

    // Navigation mode data
    private String nextRoadName = "";
    private String curRoadName = "";
    private int remainTime = 0;
    private String etaText = "";
    private String segRemainDis = "";
    private float segRemainDisMeters = -1f;   // 段剩余距离（米），用于3D车旋转距离判断
    private String routeRemainDis = "";
    private String endPOIName = "";
    private int naviIcon = 0;
    private float routeTotalDistance = -1f;
    private float lastRouteRemainDistance = -1f;

    // Cruise mode data
    private String cruiseRoadName = "未知道路";
    private String cruiseLightsData = null;
    private int cruiseLimitedSpeed = -1;

    // Traffic light (navi mode)
    private int trafficLightStatus = -1;
    private int trafficLightDir = 0;
    private int greenLightLastSecond = 0;
    private int redLightCountDownSeconds = 0;

    // TMC — parsed from EXTRA_TMC_SEGMENT JSON string
    private int[] tmcStatusArray = null;
    private int[] tmcPercentArray = null;
    private int tmcTotalDistance = 0;
    private int tmcResidualDistance = 0;
    private int tmcFinishDistance = 0;

    // Sensors
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor magneticField;
    private boolean hasMagneticSensor = false;
    private float[] gravityValues;
    private float[] geomagneticValues;

    // GPS
    private LocationManager locationManager;
    private boolean hasBearing = false;

    // Mileage tracking (all in meters)
    private float tripDistance = 0f;      // 单次里程
    private float todayDistance = 0f;     // 今日里程
    private float totalDistance = 0f;     // GPS累计里程
    private float baseMileage = 0f;      // 存量里程（km，用户设置）
    private double prevLat = 0;
    private double prevLon = 0;
    private boolean hasPrevLocation = false;
    private String todayDate = "";        // 用于判断日期变更归零

    // ===== 外部备份文件（重装/清数据后从该文件恢复所有设置）=====
    // 文件位置: /sdcard/Download/LecoDrive/settings.json
    // 首次不存在时用出厂默认值新建；之后每次修改覆盖写回
    private static final String BACKUP_DIR = "/sdcard/Download/LecoDrive/";
    private static final String BACKUP_FILE = BACKUP_DIR + "settings.json";

    // ===== 出厂初始值（仅首次安装/首次运行时使用，之后以备份文件为准）=====
    private static final float DEFAULT_IDLE_FUEL_RATE = 1.2f;
    private static final float DEFAULT_REFUEL_REMAINING_RANGE = 80f;
    private static final float[] DEFAULT_FUEL_SPEED_THRESHOLDS = {0, 20, 40, 60, 80, 105, 115, 130, 999};
    private static final float[] DEFAULT_FUEL_VALUES = {20f, 12f, 11f, 10f, 9.5f, 8f, 7.5f, 9.5f, 11f};

    private static final String MILEAGE_PREFS = "mileage_prefs";
    private static final String SETTINGS_PREFS = "pandrive_settings";
    private long lastPersistTime = 0;

    /** 获取设置SharedPreferences的Editor，避免重复写 getSharedPreferences().edit() */
    private SharedPreferences.Editor editSettings() {
        return appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit();
    }

    /** 获取里程SharedPreferences的Editor */
    private SharedPreferences.Editor editMileage() {
        return appContext.getSharedPreferences(MILEAGE_PREFS, Context.MODE_PRIVATE).edit();
    }

    // Fuel consumption: 距离加权累计法
    public static final int VEHICLE_FUEL = 0;    // 油车
    public static final int VEHICLE_ELEC = 1;    // 电车
    private int vehicleType;                // 车型（启动时由 loadSettings() 初始化）
    private float driveFuelUsed;            // 行驶累计能耗量(L 或 kWh)，不含怠速
    private float idleFuelUsed;             // 怠速累计能耗量(L 或 kWh)，与距离无关
    private float fuelCalcKm;               // 累计行驶距离(km)
    private float idleFuelRate;             // 怠速能耗率(L/h 或 kW)
    private float fuelConsumption = 0f;     // 当前综合油耗(仅行驶效率，L/100km)
    private float lastRefuelAmount;         // 加油后的总油量(L或kWh)：旧剩余 + 新加的
    private float fuelUsedAtRefuel;         // 加油时的总消耗起点(driveFuelUsed+idleFuelUsed)
    private float refuelRemainingRange;     // 加油时车辆显示的续航(km)
    private static final long FUEL_TICK_MS = 1000;  // 每1秒计算一次
    private static final long FUEL_UI_INTERVAL_MS = 60000;  // 每60秒刷新UI
    private long lastFuelTickTime = 0;
    private long lastFuelUITime = 0;
    private boolean fuelFirstTick = true;
    private final Handler fuelTickHandler = new Handler(Looper.getMainLooper());
    private final Runnable fuelTickRunnable = new Runnable() {
        @Override
        public void run() {
            tickFuelCalc();
        }
    };

    // Configurable fuel-speed table: key = max speed of range, value = L/100km
    // 数组长度始终为9，元素值由 loadSettings() 初始化
    private float[] fuelSpeedThresholds = new float[9];
    private float[] fuelValues = new float[9];

    // Navi timeout
    private final Handler naviTimeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable naviTimeoutRunnable;

    // Listeners
    public interface OnSpeedListener { void onSpeedChanged(int speed, int limitedSpeed); }
    public interface OnDirectionListener { void onDirectionChanged(float azimuth); }
    public interface OnNavigationListener { void onNavigationUpdated(); }
    public interface OnModeListener { void onModeChanged(int mode); }
    public interface OnDayNightListener { void onDayNightChanged(boolean isNight); }
    public interface OnMileageListener { void onMileageChanged(float tripKm, float todayKm, float totalKm); }
    public interface OnFuelListener { void onFuelChanged(float fuelLPer100km, float remainingRangeKm, float remainingPercent); }
    public interface OnLocationListener { void onLocationChanged(double latitude, double longitude); }

    private final List<OnSpeedListener> speedListeners = new ArrayList<>();
    private final List<OnDirectionListener> directionListeners = new ArrayList<>();
    private final List<OnNavigationListener> navigationListeners = new ArrayList<>();
    private final List<OnModeListener> modeListeners = new ArrayList<>();
    private final List<OnDayNightListener> dayNightListeners = new ArrayList<>();
    private final List<OnMileageListener> mileageListeners = new ArrayList<>();
    private final List<OnFuelListener> fuelListeners = new ArrayList<>();
    private final List<OnLocationListener> locationListeners = new ArrayList<>();

    private static DataHub instance;
    private final Context appContext;
    private BroadcastReceiver amapDataReceiver;

    private DataHub(Context context) {
        appContext = context.getApplicationContext();
        loadMileage();
        loadSettings();
    }

    public static synchronized DataHub getInstance(Context context) {
        if (instance == null) {
            instance = new DataHub(context);
        }
        return instance;
    }

    // --- Listener management ---
    public void addSpeedListener(OnSpeedListener l) { if (!speedListeners.contains(l)) speedListeners.add(l); }
    public void removeSpeedListener(OnSpeedListener l) { speedListeners.remove(l); }
    public void addDirectionListener(OnDirectionListener l) { if (!directionListeners.contains(l)) directionListeners.add(l); }
    public void removeDirectionListener(OnDirectionListener l) { directionListeners.remove(l); }
    public void addNavigationListener(OnNavigationListener l) { if (!navigationListeners.contains(l)) navigationListeners.add(l); }
    public void removeNavigationListener(OnNavigationListener l) { navigationListeners.remove(l); }
    public void addModeListener(OnModeListener l) { if (!modeListeners.contains(l)) modeListeners.add(l); }
    public void removeModeListener(OnModeListener l) { modeListeners.remove(l); }
    public void addDayNightListener(OnDayNightListener l) { if (!dayNightListeners.contains(l)) dayNightListeners.add(l); }
    public void removeDayNightListener(OnDayNightListener l) { dayNightListeners.remove(l); }
    public void addMileageListener(OnMileageListener l) { if (!mileageListeners.contains(l)) mileageListeners.add(l); }
    public void removeMileageListener(OnMileageListener l) { mileageListeners.remove(l); }
    public void addFuelListener(OnFuelListener l) { if (!fuelListeners.contains(l)) fuelListeners.add(l); }
    public void removeFuelListener(OnFuelListener l) { fuelListeners.remove(l); }
    public void addLocationListener(OnLocationListener l) { if (!locationListeners.contains(l)) locationListeners.add(l); }
    public void removeLocationListener(OnLocationListener l) { locationListeners.remove(l); }

    // --- Getters ---
    public int getCurrentMode() { return currentMode; }
    public int getCurrentSpeed() { return currentSpeed; }
    public int getLimitedSpeed() { return limitedSpeed; }
    public float getAzimuth() { return azimuth; }
    public String getNextRoadName() { return nextRoadName; }
    public String getCurRoadName() { return curRoadName; }
    public int getRemainTime() { return remainTime; }
    public String getEtaText() { return etaText; }
    public String getSegRemainDis() { return segRemainDis; }
    public float getSegRemainDisMeters() { return segRemainDisMeters; }
    public String getRouteRemainDis() { return routeRemainDis; }
    public String getEndPOIName() { return endPOIName; }
    public int getNaviIcon() { return naviIcon; }
    public String getCruiseRoadName() { return cruiseRoadName; }
    public String getCruiseLightsData() { return cruiseLightsData; }
    public int getCruiseLimitedSpeed() { return cruiseLimitedSpeed; }
    public int getTrafficLightStatus() { return trafficLightStatus; }
    public int getTrafficLightDir() { return trafficLightDir; }
    public int getGreenLightLastSecond() { return greenLightLastSecond; }
    public int getRedLightCountDownSeconds() { return redLightCountDownSeconds; }
    public int[] getTmcStatusArray() { return tmcStatusArray; }
    public int[] getTmcPercentArray() { return tmcPercentArray; }
    public int getTmcTotalDistance() { return tmcTotalDistance; }
    public int getTmcResidualDistance() { return tmcResidualDistance; }
    public int getTmcFinishDistance() { return tmcFinishDistance; }

    /**
     * Get navigation progress (0.0~1.0), matching original AmapAutoBroadcastReceiver.getNavProgress()
     * Prioritizes TMC-based progress, falls back to route distance ratio
     */
    public float getNavProgress() {
        if (tmcTotalDistance > 0 && tmcFinishDistance >= 0) {
            return (float) tmcFinishDistance / (float) tmcTotalDistance;
        }
        if (routeTotalDistance > 0 && lastRouteRemainDistance >= 0) {
            float progress = 1f - (lastRouteRemainDistance / routeTotalDistance);
            if (progress < 0) progress = 0;
            if (progress > 1) progress = 1;
            return progress;
        }
        return -1f;
    }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public double getAltitude() { return altitude; }
    public float getRouteTotalDistance() { return routeTotalDistance; }

    // Mileage getters (all in km)
    public float getTripDistanceKm() { return tripDistance / 1000f; }
    public float getTodayDistanceKm() { return todayDistance / 1000f; }
    public float getTotalDistanceKm() { return totalDistance / 1000f + baseMileage; }

    public void resetTripDistance() {
        tripDistance = 0f;
        persistMileage();
        notifyMileageChanged();
    }

    // --- Base mileage (存量里程, in km) ---
    public float getBaseMileage() { return baseMileage; }
    public void setBaseMileage(float km) {
        baseMileage = km;
        totalDistance = 0f;
        todayDistance = 0f;
        tripDistance = 0f;
        hasPrevLocation = false;
        editSettings().putFloat("base_mileage", baseMileage).apply();
        persistBackup();
        persistMileage();
        notifyMileageChanged();
    }

    // --- Configurable fuel table ---
    public float[] getFuelSpeedThresholds() { return fuelSpeedThresholds; }
    public float[] getFuelValues() { return fuelValues; }
    public void setFuelValues(float[] values) {
        fuelValues = values;
        SharedPreferences.Editor editor = editSettings();
        for (int i = 0; i < fuelValues.length; i++) {
            editor.putFloat("fuel_v" + i, fuelValues[i]);
        }
        editor.apply();
        persistBackup();
    }
    public int getFuelTableSize() { return fuelValues.length; }

    /** Haversine formula: returns distance in meters */
    private static float haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (float) (R * c);
    }

    private void loadMileage() {
        SharedPreferences prefs = appContext.getSharedPreferences(MILEAGE_PREFS, Context.MODE_PRIVATE);
        todayDistance = prefs.getFloat("today_distance", 0f);
        totalDistance = prefs.getFloat("total_distance", 0f);
        todayDate = prefs.getString("today_date", "");
        tripDistance = 0f;  // 启动时清零，代表本次行程
        // Check date change: if stored date != today, reset today distance
        String currentDate = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        if (!currentDate.equals(todayDate)) {
            todayDistance = 0f;
            todayDate = currentDate;
        }
    }

    private void persistMileage() {
        editMileage()
                .putFloat("trip_distance", tripDistance)
                .putFloat("today_distance", todayDistance)
                .putFloat("total_distance", totalDistance)
                .putString("today_date", todayDate)
                .apply();
    }

    /**
     * 加载设置：优先从外部备份文件读取，备份不存在时用出厂默认值新建一份。
     * 所有字段不再依赖 SharedPreferences 的硬编码默认值——备份文件即唯一权威来源。
     * SharedPreferences 仅作内存读缓存：备份文件加载完成后同步写入 SP。
     *
     * 安全机制：读取失败时（如启动时还没拿到存储权限）只用默认值填充内存，
     * 绝不覆盖备份文件——保留磁盘原文件等授权后重试读取。
     */
    private void loadSettings() {
        File backup = new File(BACKUP_FILE);
        if (backup.exists() && loadFromBackup(backup)) {
            // 备份文件加载成功 → 同步到 SP 缓存
            persistSettingsToSP();
            return;
        }
        // 读不到（文件不存在 / 无权限 / JSON损坏）→ 只用默认值入内存，不覆盖文件
        initDefaultSettings();
        persistSettingsToSP();
        // 注意：这里不调 persistBackup()，避免无权限时把原备份文件冲掉
        Log.w(TAG, "备份文件读取失败或不存在，暂用默认值;授权后会重试读取");
    }

    /**
     * 授权成功后调用：重新尝试从备份文件读取设置。
     * 如果读到，覆盖内存字段 + 同步 SP + 通知监听器刷新 UI。
     * 读不到（确实没有备份文件）则生成一份默认备份。
     * @return true 表示确实从备份恢复了用户数据
     */
    public boolean retryLoadFromBackup() {
        File backup = new File(BACKUP_FILE);
        if (backup.exists() && loadFromBackup(backup)) {
            persistSettingsToSP();
            notifyFuelChanged();
            notifyMileageChanged();
            Log.i(TAG, "授权后重试读取备份成功，已恢复用户设置");
            return true;
        }
        // 确实没有备份文件 → 生成一份默认备份（此时权限已拿到，可以安全写入）
        persistBackup();
        Log.i(TAG, "授权后重试: 无备份文件，已生成默认备份");
        return false;
    }

    /** 从备份文件读取所有设置到内存字段 */
    private boolean loadFromBackup(File backup) {
        try {
            JSONObject root = new JSONObject(readFile(backup));
            baseMileage          = (float) root.optDouble("base_mileage", 0f);
            idleFuelRate         = (float) root.optDouble("idle_fuel_rate", DEFAULT_IDLE_FUEL_RATE);
            vehicleType          = root.optInt("vehicle_type", VEHICLE_FUEL);
            lastRefuelAmount     = (float) root.optDouble("last_refuel_amount", 0f);
            fuelUsedAtRefuel     = (float) root.optDouble("fuel_used_at_refuel", 0f);
            refuelRemainingRange = (float) root.optDouble("refuel_remaining_range", DEFAULT_REFUEL_REMAINING_RANGE);
            driveFuelUsed        = (float) root.optDouble("drive_fuel_used", 0f);
            idleFuelUsed         = (float) root.optDouble("idle_fuel_used", 0f);
            fuelCalcKm           = (float) root.optDouble("fuel_calc_km", 0f);
            JSONArray thresholds = root.optJSONArray("fuel_speed_thresholds");
            JSONArray values     = root.optJSONArray("fuel_values");
            if (thresholds != null && values != null && values.length() == 9) {
                for (int i = 0; i < 9; i++) {
                    fuelSpeedThresholds[i] = (float) thresholds.optDouble(i);
                    fuelValues[i]          = (float) values.optDouble(i);
                }
            } else {
                System.arraycopy(DEFAULT_FUEL_SPEED_THRESHOLDS, 0, fuelSpeedThresholds, 0, 9);
                System.arraycopy(DEFAULT_FUEL_VALUES, 0, fuelValues, 0, 9);
            }
            Log.i(TAG, "已从备份文件加载设置: " + BACKUP_FILE);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "读取备份文件失败，改用默认值: " + e.getMessage());
            return false;
        }
    }

    /** 用出厂默认值初始化所有字段（仅内存，不写文件。首次运行写文件由调用方决定） */
    private void initDefaultSettings() {
        baseMileage          = 0f;
        idleFuelRate         = DEFAULT_IDLE_FUEL_RATE;
        vehicleType          = VEHICLE_FUEL;
        System.arraycopy(DEFAULT_FUEL_SPEED_THRESHOLDS, 0, fuelSpeedThresholds, 0, 9);
        System.arraycopy(DEFAULT_FUEL_VALUES, 0, fuelValues, 0, 9);
        lastRefuelAmount     = 0f;
        fuelUsedAtRefuel     = 0f;
        refuelRemainingRange = DEFAULT_REFUEL_REMAINING_RANGE;
        driveFuelUsed        = 0f;
        idleFuelUsed         = 0f;
        fuelCalcKm           = 0f;
        Log.i(TAG, "已用出厂默认值初始化内存字段");
    }

    /** 将所有字段写入 SharedPreferences（作为读缓存） */
    private void persistSettingsToSP() {
        SharedPreferences.Editor e = editSettings();
        e.putFloat("base_mileage", baseMileage);
        e.putFloat("idle_fuel_rate", idleFuelRate);
        e.putInt("vehicle_type", vehicleType);
        for (int i = 0; i < fuelValues.length; i++) e.putFloat("fuel_v" + i, fuelValues[i]);
        e.putFloat("last_refuel_amount", lastRefuelAmount);
        e.putFloat("fuel_used_at_refuel", fuelUsedAtRefuel);
        e.putFloat("refuel_remaining_range", refuelRemainingRange);
        e.putFloat("drive_fuel_used", driveFuelUsed);
        e.putFloat("idle_fuel_used", idleFuelUsed);
        e.putFloat("fuel_calc_km", fuelCalcKm);
        e.apply();
    }

    /** 将所有字段写入备份文件（覆盖写）。失败仅打日志不抛异常 */
    public void persistBackup() {
        try {
            File dir = new File(BACKUP_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "创建备份目录失败: " + BACKUP_DIR);
                return;
            }
            JSONObject root = new JSONObject();
            root.put("base_mileage", baseMileage);
            root.put("idle_fuel_rate", idleFuelRate);
            root.put("vehicle_type", vehicleType);
            JSONArray thresholds = new JSONArray();
            JSONArray values = new JSONArray();
            for (int i = 0; i < fuelValues.length; i++) {
                thresholds.put((double) fuelSpeedThresholds[i]);
                values.put((double) fuelValues[i]);
            }
            root.put("fuel_speed_thresholds", thresholds);
            root.put("fuel_values", values);
            root.put("last_refuel_amount", lastRefuelAmount);
            root.put("fuel_used_at_refuel", fuelUsedAtRefuel);
            root.put("refuel_remaining_range", refuelRemainingRange);
            root.put("drive_fuel_used", driveFuelUsed);
            root.put("idle_fuel_used", idleFuelUsed);
            root.put("fuel_calc_km", fuelCalcKm);
            writeFile(new File(BACKUP_FILE), root.toString());
        } catch (Exception e) {
            Log.e(TAG, "写入备份文件失败: " + e.getMessage());
        }
    }

    /**
     * 应用退出时调用：将所有运行时累加器（油耗tick等）持久化到 SP + 备份文件 + 里程 SP。
     * 兜底作用，确保油耗在退出时不丢失。
     */
    public void persistAll() {
        persistSettingsToSP();
        persistBackup();
        persistMileage();
    }

    // 简单的文件读写工具
    private String readFile(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = fis.read(buf)) > 0) baos.write(buf, 0, n);
            return new String(baos.toByteArray(), "UTF-8");
        }
    }

    private void writeFile(File f, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        }
    }

    private void notifyMileageChanged() {
        float tripKm = tripDistance / 1000f;
        float todayKm = todayDistance / 1000f;
        float totalKm = totalDistance / 1000f + baseMileage;
        for (OnMileageListener l : mileageListeners) l.onMileageChanged(tripKm, todayKm, totalKm);
    }

    public float getFuelConsumption() { return fuelConsumption; }
    public float getIdleFuelRate() { return idleFuelRate; }
    public float getLastRefuelAmount() { return lastRefuelAmount; }
    /** 剩余油量 = 加油总量 - (行驶消耗 + 怠速消耗 - 加油时起点) */
    public float getRemainingEnergy() {
        float totalUsed = driveFuelUsed + idleFuelUsed;
        float usedSinceRefuel = totalUsed - fuelUsedAtRefuel;
        float remaining = lastRefuelAmount - usedSinceRefuel;
        return remaining > 0 ? remaining : 0f;
    }
    /** 续航里程 = 剩余油量 ÷ 当前油耗 × 100 (km)
     *  油耗为0时（还没行驶过），用默认油耗估算
     */
    public float getRemainingRange() {
        if (lastRefuelAmount <= 0) return 0f;
        float effectiveConsumption = fuelConsumption > 0.01f ? fuelConsumption : getDefaultFuelConsumption();
        return getRemainingEnergy() / effectiveConsumption * 100f;
    }
    /** 剩余能量百分比 = 剩余 / 总量 × 100 */
    public float getRemainingPercent() {
        if (lastRefuelAmount <= 0) return 0f;
        return getRemainingEnergy() / lastRefuelAmount * 100f;
    }
    public float getRefuelRemainingRange() { return refuelRemainingRange; }
    public void setRefuelRemainingRange(float km) {
        refuelRemainingRange = km;
        editSettings().putFloat("refuel_remaining_range", refuelRemainingRange).apply();
        persistBackup();
    }
    /** 加油修正：用输入的续航里程反推剩余油量
     *  反推油耗优先级：综合油耗 > 当前速度查表 > 默认值
     */
    public void setRefuelAmount(float liters) {
        float effectiveConsumption = getEffectiveFuelConsumption();
        float remainingLiters = refuelRemainingRange * effectiveConsumption / 100f;
        lastRefuelAmount = remainingLiters + liters;  // 新总量 = 旧剩余 + 新加的
        fuelUsedAtRefuel = driveFuelUsed + idleFuelUsed;
        editSettings()
                .putFloat("last_refuel_amount", lastRefuelAmount)
                .putFloat("fuel_used_at_refuel", fuelUsedAtRefuel)
                .apply();
        persistBackup();
        notifyFuelChanged();
    }

    /** 仅修正续航（不加油）：直接用输入的续航反推剩余油量作为新基准 */
    public void calibrateRange(float rangeKm) {
        float effectiveConsumption = getEffectiveFuelConsumption();
        lastRefuelAmount = rangeKm * effectiveConsumption / 100f;
        fuelUsedAtRefuel = driveFuelUsed + idleFuelUsed;
        refuelRemainingRange = rangeKm;
        editSettings()
                .putFloat("last_refuel_amount", lastRefuelAmount)
                .putFloat("fuel_used_at_refuel", fuelUsedAtRefuel)
                .putFloat("refuel_remaining_range", refuelRemainingRange)
                .apply();
        persistBackup();
        notifyFuelChanged();
    }

    /** 取有效油耗：优先综合油耗，其次GPS速度查表，最后默认值 */
    private float getEffectiveFuelConsumption() {
        if (fuelConsumption > 0.01f) return fuelConsumption;
        int speed = Math.round(gpsSpeed * 3.6f);
        if (speed > 0) return lookupFuelBySpeed(speed);
        return getDefaultFuelConsumption();
    }

    /** 默认值：油耗表中间段平均（速度也为0时的兜底） */
    private float getDefaultFuelConsumption() {
        if (fuelValues.length == 0) return 10f;
        // 跳过怠速(0km/h=20L)和极速(999=11L)，取中间段平均
        float sum = 0;
        int count = 0;
        for (int i = 1; i < fuelValues.length - 1; i++) {
            sum += fuelValues[i];
            count++;
        }
        return count > 0 ? sum / count : 10f;
    }
    public int getVehicleType() { return vehicleType; }
    public void setVehicleType(int type) {
        vehicleType = type;
        editSettings().putInt("vehicle_type", vehicleType).apply();
        persistBackup();
        notifyFuelChanged();
    }

    public void startFuelSampling() {
        // 先移除旧的回调，防止onResume多次调用时累积重复链导致tick越来越快
        fuelTickHandler.removeCallbacks(fuelTickRunnable);
        fuelFirstTick = true;
        lastFuelUITime = System.currentTimeMillis();
        fuelTickHandler.postDelayed(fuelTickRunnable, FUEL_TICK_MS);
    }

    public void stopFuelSampling() {
        fuelTickHandler.removeCallbacks(fuelTickRunnable);
    }

    /** 每1秒tick一次：怠速和行驶分别累加
     *  油耗/续航速度直接用GPS，不依赖高德
     */
    private void tickFuelCalc() {
        long now = System.currentTimeMillis();
        if (fuelFirstTick) {
            lastFuelTickTime = now;
            fuelFirstTick = false;
            fuelTickHandler.postDelayed(fuelTickRunnable, FUEL_TICK_MS);
            return;
        }
        float dtHours = (now - lastFuelTickTime) / 3600000f;
        lastFuelTickTime = now;

        // 油耗计算直接用GPS速度（m/s → km/h）
        int speed = Math.round(gpsSpeed * 3.6f);

        // 怠速判断：油车速度<2km/h走怠速，电车速度<1走怠速
        boolean isIdle = (vehicleType == VEHICLE_FUEL) ? (speed < 2) : (speed < 1);

        if (isIdle) {
            // 怠速：累加时间 × 怠速能耗率（与距离无关）
            idleFuelUsed += dtHours * idleFuelRate;
        } else {
            // 行驶：累加距离 × 查表油耗 / 100
            float distKm = speed * dtHours;  // km = km/h × h
            float rate = lookupFuelBySpeed(speed);
            driveFuelUsed += distKm * rate / 100f;
            fuelCalcKm += distKm;
        }

        // 综合油耗 = 纯行驶效率（只看行驶消耗和行驶距离，不含怠速）
        if (fuelCalcKm > 0.01f) {
            fuelConsumption = driveFuelUsed / fuelCalcKm * 100f;
        } else {
            fuelConsumption = 0f;  // 还没行驶过，暂无油耗
        }

        // 每60秒刷新UI并持久化能耗数据，首次有行驶距离时立即刷新
        if (now - lastFuelUITime >= FUEL_UI_INTERVAL_MS || (fuelCalcKm > 0 && fuelCalcKm < 0.05f)) {
            lastFuelUITime = now;
            persistFuelData();
            notifyFuelChanged();
        }

        fuelTickHandler.postDelayed(fuelTickRunnable, FUEL_TICK_MS);
    }

    public void setIdleFuelRate(float rate) {
        idleFuelRate = rate;
        editSettings().putFloat("idle_fuel_rate", idleFuelRate).apply();
        persistBackup();
    }

    /** 速度→油耗查表 (L/100km)，使用可配置的速度-油耗映射 */
    private float lookupFuelBySpeed(int speed) {
        for (int i = 0; i < fuelSpeedThresholds.length; i++) {
            if (speed <= fuelSpeedThresholds[i]) return fuelValues[i];
        }
        return fuelValues[fuelValues.length - 1];
    }

    private void persistFuelData() {
        editSettings()
                .putFloat("drive_fuel_used", driveFuelUsed)
                .putFloat("idle_fuel_used", idleFuelUsed)
                .putFloat("fuel_calc_km", fuelCalcKm)
                .apply();
        persistBackup();  // 每60秒同步到备份文件，断电最多丢60秒累加数据
    }

    private void notifyFuelChanged() {
        float range = getRemainingRange();
        float percent = getRemainingPercent();
        for (OnFuelListener l : fuelListeners) l.onFuelChanged(fuelConsumption, range, percent);
    }

    /**
     * Parse distance string like "68公里", "500米" to kilometers
     */
    public static float parseDistanceToKm(String distanceStr) {
        if (distanceStr == null || distanceStr.isEmpty()) return -1f;
        try {
            distanceStr = distanceStr.trim();
            if (distanceStr.contains("公里") || distanceStr.contains("千米")) {
                String num = distanceStr.replaceAll("[^0-9.]", "");
                return Float.parseFloat(num);
            } else if (distanceStr.contains("米")) {
                String num = distanceStr.replaceAll("[^0-9.]", "");
                return Float.parseFloat(num) / 1000f;
            } else {
                String num = distanceStr.replaceAll("[^0-9.]", "");
                if (!num.isEmpty()) {
                    return Float.parseFloat(num) / 1000f;
                }
            }
        } catch (NumberFormatException e) { }
        return -1f;
    }

    /**
     * Get effective cruise limited speed (cruise first, navi fallback)
     */
    public int getEffectiveCruiseLimitedSpeed() {
        if (cruiseLimitedSpeed > 0) return cruiseLimitedSpeed;
        if (limitedSpeed > 0) return limitedSpeed;
        return -1;
    }

    // --- Amap receiver ---
    public void registerAmapReceiver() {
        if (amapDataReceiver != null) return;
        amapDataReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_AUTONAVI.equals(intent.getAction())) return;
                int keyType = intent.getIntExtra("KEY_TYPE", -1);
                Log.d(TAG, "收到高德广播: KEY_TYPE=" + keyType);
                dispatchAmapBroadcast(intent);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_AUTONAVI);
        CompatUtils.safeRegisterReceiverExported(appContext, amapDataReceiver, filter);
    }

    public void unregisterAmapReceiver() {
        if (amapDataReceiver != null) {
            try { appContext.unregisterReceiver(amapDataReceiver); } catch (Exception ignored) {}
            amapDataReceiver = null;
        }
    }

    /**
     * 由PanDriveService调用：Service收到高德广播后，直接传给DataHub解析
     * 这样即使Activity在画中画/窗口模式下，广播仍由Service接收并转发
     */
    public void onAmapBroadcastReceived(Intent intent) {
        dispatchAmapBroadcast(intent);
    }

    /**
     * 统一分发高德广播：无论来自本地注册还是Service转发，都走同一条解析路径
     */
    private void dispatchAmapBroadcast(Intent intent) {
        int keyType = intent.getIntExtra("KEY_TYPE", -1);
        Log.d(TAG, "分发高德广播: KEY_TYPE=" + keyType);
        switch (keyType) {
            case KEY_TYPE_NAVI_GUIDE:
                parseNaviGuideInfo(intent);
                break;
            case KEY_TYPE_TRAFFIC_LIGHT:
                parseTrafficLightInfo(intent);
                break;
            case KEY_TYPE_TMC:
                parseTmcInfo(intent);
                break;
            case KEY_TYPE_DAY_NIGHT:
                parseDayNightInfo(intent);
                break;
        }
    }

    private void parseNaviGuideInfo(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return;

        int newIcon = extras.getInt("NEW_ICON", 0);
        int icon = extras.getInt("ICON", 0);
        int effectiveIcon = newIcon != 0 ? newIcon : icon;

        Log.d(TAG, "NaviGuide: ICON=" + icon + " NEW_ICON=" + newIcon
                + " effectiveIcon=" + effectiveIcon
                + " CUR_SPEED=" + extras.getInt("CUR_SPEED", -1)
                + " LIMITED_SPEED=" + extras.getInt("LIMITED_SPEED", -1)
                + " SEG_REMAIN_DIS=" + extras.getString("SEG_REMAIN_DIS_AUTO", "")
                + " NEXT_ROAD=" + extras.getString("NEXT_ROAD_NAME", "")
                + " CUR_ROAD=" + extras.getString("CUR_ROAD_NAME", "")
                + " mode=" + (effectiveIcon != 0 ? "NAVI" : "CRUISE"));

        // Reset navi timeout
        resetNaviTimeout();

        if (effectiveIcon != 0) {
            // Navigation mode (has turn icon)
            if (currentMode != MODE_NAVI) {
                currentMode = MODE_NAVI;
                notifyModeChanged();
            }
            naviIcon = effectiveIcon;
            // Only update non-null/non-default fields (preserve previous values)
            String nextRoad = extras.getString("NEXT_ROAD_NAME");
            if (nextRoad != null && !nextRoad.isEmpty()) nextRoadName = nextRoad;
            String curRoad = extras.getString("CUR_ROAD_NAME");
            if (curRoad != null && !curRoad.isEmpty()) curRoadName = curRoad;
            int rTime = extras.getInt("ROUTE_REMAIN_TIME", 0);
            if (rTime > 0) remainTime = rTime;
            String eta = extras.getString("ETA_TEXT");
            if (eta != null && !eta.isEmpty()) etaText = eta;
            String segDis = extras.getString("SEG_REMAIN_DIS_AUTO");
            if (segDis != null && !segDis.isEmpty()) {
                segRemainDis = segDis;
                float km = parseDistanceToKm(segDis);
                segRemainDisMeters = (km >= 0) ? km * 1000f : -1f;
            }
            String routeDis = extras.getString("ROUTE_REMAIN_DIS_AUTO");
            if (routeDis != null && !routeDis.isEmpty()) {
                routeRemainDis = routeDis;
                // Calculate routeTotalDistance, matching original project logic
                float remainKm = parseDistanceToKm(routeRemainDis);
                if (remainKm >= 0) {
                    if (routeTotalDistance < 0) {
                        routeTotalDistance = remainKm;
                    } else if (lastRouteRemainDistance > 0 && remainKm > lastRouteRemainDistance * 1.3f) {
                        routeTotalDistance = remainKm;
                    }
                    lastRouteRemainDistance = remainKm;
                }
            }
            String dest = extras.getString("endPOIName");
            if (dest != null && !dest.isEmpty()) endPOIName = dest;
            int lSpeed = extras.getInt("LIMITED_SPEED", -1);
            if (lSpeed > 0) limitedSpeed = lSpeed;
            // Speed also available in navi mode
            currentSpeed = extras.getInt("CUR_SPEED", 0);
            notifySpeedChanged();
            notifyNavigationUpdated();
        } else if (currentMode == MODE_NAVI) {
            // ICON==0 but already in NAVI mode → stay NAVI (don't downgrade)
            // Still update speed
            currentSpeed = extras.getInt("CUR_SPEED", 0);
            notifySpeedChanged();
        } else {
            // Cruise mode
            if (currentMode != MODE_CRUISE) {
                currentMode = MODE_CRUISE;
                notifyModeChanged();
            }
            currentSpeed = extras.getInt("CUR_SPEED", 0);
            cruiseRoadName = extras.getString("CUR_ROAD_NAME", "未知道路");
            cruiseLimitedSpeed = extras.getInt("LIMITED_SPEED", -1);
            if (cruiseLimitedSpeed <= 0) cruiseLimitedSpeed = -1;
            limitedSpeed = cruiseLimitedSpeed;
            notifySpeedChanged();
            notifyNavigationUpdated();
        }
    }

    private void parseTrafficLightInfo(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return;
        resetNaviTimeout();

        int lightStatus = extras.getInt("trafficLightStatus", -1);
        String lightsData = extras.getString("lightsData");
        Log.d(TAG, "TrafficLight: status=" + lightStatus + " lightsData=" + lightsData
                + " mode=" + currentMode);

        if (currentMode == MODE_NAVI) {
            trafficLightStatus = extras.getInt("trafficLightStatus", -1);
            trafficLightDir = extras.getInt("dir", 0);
            greenLightLastSecond = extras.getInt("greenLightLastSecond", 0);
            redLightCountDownSeconds = extras.getInt("redLightCountDownSeconds", 0);
        } else {
            String data = extras.getString("lightsData");
            cruiseLightsData = (data != null && !data.isEmpty()) ? data : null;
        }
        notifyNavigationUpdated();
    }

    /**
     * Parse TMC segment info from EXTRA_TMC_SEGMENT JSON string
     * Matches original AmapAutoBroadcastReceiver.parseTmcSegmentInfo() exactly
     *
     * JSON structure:
     * {
     *   "tmc_segment_enabled": true/false,
     *   "total_distance": int,
     *   "residual_distance": int,
     *   "finish_distance": int,
     *   "tmc_info": [
     *     { "tmc_status": "1", "tmc_segment_percent": "30", ... },
     *     ...
     *   ]
     * }
     *
     * Note: tmc_status and tmc_segment_percent are STRING values in JSON,
     * need parseIntSafe() to convert.
     */
    private void parseTmcInfo(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return;

        String tmcJson = extras.getString("EXTRA_TMC_SEGMENT");
        if (tmcJson == null || tmcJson.isEmpty()) return;

        try {
            JSONObject root = new JSONObject(tmcJson);
            boolean enabled = root.optBoolean("tmc_segment_enabled", false);
            if (!enabled) return;

            tmcTotalDistance = root.optInt("total_distance", 0);
            tmcResidualDistance = root.optInt("residual_distance", 0);
            tmcFinishDistance = root.optInt("finish_distance", 0);

            JSONArray tmcArray = root.optJSONArray("tmc_info");
            if (tmcArray == null || tmcArray.length() == 0) return;

            List<int[]> segmentList = new ArrayList<>();
            for (int i = 0; i < tmcArray.length(); i++) {
                JSONObject seg = tmcArray.getJSONObject(i);
                int status = parseIntSafe(seg.optString("tmc_status", "0"));
                int percent = parseIntSafe(seg.optString("tmc_segment_percent", "0"));
                segmentList.add(new int[]{status, percent});
            }

            // Convert to parallel arrays for TrafficBarView.setTmcData()
            tmcStatusArray = new int[segmentList.size()];
            tmcPercentArray = new int[segmentList.size()];
            for (int i = 0; i < segmentList.size(); i++) {
                tmcStatusArray[i] = segmentList.get(i)[0];
                tmcPercentArray[i] = segmentList.get(i)[1];
            }

            Log.d(TAG, "TMC parsed: " + segmentList.size() + " segments, total="
                    + tmcTotalDistance + "m, finish=" + tmcFinishDistance + "m");

        } catch (Exception e) {
            Log.e(TAG, "TMC parse error: " + e.getMessage());
            tmcStatusArray = null;
            tmcPercentArray = null;
        }
        notifyNavigationUpdated();
    }

    private static int parseIntSafe(String str) {
        try {
            return Integer.parseInt(str.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // --- Navi timeout ---
    private void resetNaviTimeout() {
        if (naviTimeoutRunnable != null) {
            naviTimeoutHandler.removeCallbacks(naviTimeoutRunnable);
        }
        if (currentMode == MODE_NAVI) {
            naviTimeoutRunnable = () -> {
                currentMode = MODE_CRUISE;
                notifyModeChanged();
            };
            naviTimeoutHandler.postDelayed(naviTimeoutRunnable, NAVI_TIMEOUT_MS);
        }
    }



    // --- Sensor management for compass ---
    public void registerSensors() {
        if (sensorManager == null) {
            sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        }
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        hasMagneticSensor = (magneticField != null);

        if (accelerometer != null) {
            sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        if (magneticField != null) {
            sensorManager.registerListener(sensorListener, magneticField, SensorManager.SENSOR_DELAY_UI);
        }
    }

    public void unregisterSensors() {
        if (sensorManager != null) {
            try { sensorManager.unregisterListener(sensorListener); } catch (Exception ignored) {}
        }
    }

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                gravityValues = event.values.clone();
            } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                geomagneticValues = event.values.clone();
            }
            if (gravityValues != null && geomagneticValues != null) {
                float[] R = new float[9];
                float[] I = new float[9];
                if (SensorManager.getRotationMatrix(R, I, gravityValues, geomagneticValues)) {
                    float[] orientation = new float[3];
                    SensorManager.getOrientation(R, orientation);
                    azimuth = (float) Math.toDegrees(orientation[0]);
                    if (azimuth < 0) azimuth += 360f;
                    notifyDirectionChanged();
                }
            }
        }
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    // --- GPS for compass fallback ---
    public void registerLocation() {
        if (locationManager == null) {
            locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, locationListener, Looper.getMainLooper());
        } catch (Exception ignored) {}  // SecurityException or IllegalArgumentException if provider doesn't exist
        try {
            if (locationManager.getProvider(LocationManager.NETWORK_PROVIDER) != null) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 1, locationListener, Looper.getMainLooper());
            }
        } catch (Exception ignored) {}
    }

    public void unregisterLocation() {
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }
        persistMileage();
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            latitude = location.getLatitude();
            longitude = location.getLongitude();
            altitude = location.getAltitude();
            // 通知位置监听器（天气等）
            notifyLocationChanged();
            // 更新GPS速度
            if (location.hasSpeed()) {
                gpsSpeed = location.getSpeed(); // m/s
            } else {
                gpsSpeed = 0f;
            }
            // GPS bearing fallback when no magnetic sensor
            if (!hasMagneticSensor && location.hasBearing()) {
                azimuth = location.getBearing();
                notifyDirectionChanged();
            }
            // Mileage tracking
            float accuracy = location.getAccuracy();
            float speed = location.hasSpeed() ? location.getSpeed() : 0f; // m/s
            // Filter: skip low accuracy (>20m) and near-stationary (<2km/h = 0.56m/s)
            if (accuracy > 0 && accuracy <= 20f && speed >= 0.56f) {
                if (hasPrevLocation) {
                    float dist = haversine(prevLat, prevLon, latitude, longitude);
                    // Skip GPS jumps (>200m in 1-2s is unrealistic)
                    if (dist > 0f && dist < 200f) {
                        tripDistance += dist;
                        todayDistance += dist;
                        totalDistance += dist;
                        // Persist every 30 seconds
                        long now = System.currentTimeMillis();
                        if (now - lastPersistTime > 30000) {
                            persistMileage();
                            lastPersistTime = now;
                        }
                        notifyMileageChanged();
                    }
                }
                prevLat = latitude;
                prevLon = longitude;
                hasPrevLocation = true;
            }
        }
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override
        public void onProviderEnabled(String provider) {}
        @Override
        public void onProviderDisabled(String provider) {}
    };

    // --- Notify helpers ---
    private void notifySpeedChanged() {
        for (OnSpeedListener l : speedListeners) l.onSpeedChanged(currentSpeed, limitedSpeed);
    }
    private void notifyDirectionChanged() {
        for (OnDirectionListener l : directionListeners) l.onDirectionChanged(azimuth);
    }
    private void notifyNavigationUpdated() {
        for (OnNavigationListener l : navigationListeners) l.onNavigationUpdated();
    }
    private void notifyModeChanged() {
        for (OnModeListener l : modeListeners) l.onModeChanged(currentMode);
    }
    private void notifyDayNightChanged(boolean isNight) {
        for (OnDayNightListener l : dayNightListeners) l.onDayNightChanged(isNight);
    }
    private void notifyLocationChanged() {
        for (OnLocationListener l : locationListeners) l.onLocationChanged(latitude, longitude);
    }

    /**
     * Parse day/night info from Amap KEY_TYPE 10019 broadcast
     * EXTRA_STATE=37=day, 38=night
     */
    private void parseDayNightInfo(Intent intent) {
        Bundle extras = intent.getExtras();
        int extraState = intent.getIntExtra("EXTRA_STATE", -1);

        // Log all extras for diagnosis
        if (extras != null) {
            StringBuilder sb = new StringBuilder();
            for (String key : extras.keySet()) {
                sb.append(key).append("=").append(extras.get(key)).append(" ");
            }
            Log.d(TAG, "10019广播所有字段: " + sb.toString().trim());
        }

        if (extraState != EXTRA_STATE_DAY && extraState != EXTRA_STATE_NIGHT) {
            Log.w(TAG, "10019 EXTRA_STATE=" + extraState + " 不匹配日/夜值(37/38)");
            return;
        }

        // Anti-debounce
        long now = System.currentTimeMillis();
        if (now - lastDayNightTime < DAY_NIGHT_DEBOUNCE_MS) return;
        lastDayNightTime = now;

        boolean isNight = (extraState == EXTRA_STATE_NIGHT);
        Log.d(TAG, "高德日夜模式: " + (isNight ? "夜间" : "白天"));
        notifyDayNightChanged(isNight);
    }

}
