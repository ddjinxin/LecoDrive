package com.jingxin.pandrive.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 天气数据助手
 * 职责：GPS坐标 → 请求Open-Meteo → 解析JSON → 返回天气信息（视频文件名+文字描述）
 * 单例，零第三方依赖
 */
public class WeatherHelper {

    private static final String TAG = "WeatherHelper";
    private static final String SP_NAME = "weather";
    private static final String SP_VIDEO_INDEX = "video_index";
    private static final String SP_LAST_LAT = "last_lat";
    private static final String SP_LAST_LON = "last_lon";

    // 单例
    private static volatile WeatherHelper instance;

    // 轮询间隔：30分钟
    private static final long FETCH_INTERVAL_MS = 30 * 60 * 1000;

    // IP定位最小间隔：5分钟（避免频繁请求）
    private static final long IP_LOCATION_INTERVAL_MS = 5 * 60 * 1000;

    // 风速阈值（km/h），超过此值切到wind视频
    private static final double WIND_SPEED_THRESHOLD = 40.0;

    // 请求超时
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;

    // IP定位超时（更短，快速失败）
    private static final int IP_CONNECT_TIMEOUT_MS = 5000;
    private static final int IP_READ_TIMEOUT_MS = 8000;

    // 视频文件名映射（索引0~5）
    private static final String[] VIDEO_FILES = {
        "sunny.mp4",   // 0
        "cloud.mp4",   // 1
        "fog.mp4",     // 2
        "rain.mp4",    // 3
        "snow.mp4",    // 4
        "wind.mp4"     // 5
    };

    // 天气数据
    private int weatherCode = -1;
    private double temperature = Double.NaN;        // °C
    private double apparentTemp = Double.NaN;       // 体感温度 °C
    private double windSpeed = Double.NaN;          // km/h
    private int windDirection = -1;                 // 度
    private int humidity = -1;                      // %
    private int currentVideoIndex = -1;             // 0~5
    private long lastFetchTime = 0;
    private boolean fetching = false;
    private boolean ipLocating = false;
    private long lastIpLocationTime = 0;
    private float cachedLat = 0f;
    private float cachedLon = 0f;

    // 回调
    public interface OnWeatherListener {
        /** 天气数据更新时回调 */
        void onWeatherUpdated(String videoFileName, String weatherLine1, String weatherLine2, String weatherLine3, String weatherLine4);
    }

    private OnWeatherListener listener;
    private Context appContext;

    private WeatherHelper() {}

    public static WeatherHelper getInstance() {
        if (instance == null) {
            synchronized (WeatherHelper.class) {
                if (instance == null) {
                    instance = new WeatherHelper();
                }
            }
        }
        return instance;
    }

    /**
     * 临时文件日志（诊断车机窗口模式问题用），输出到 /sdcard/Download/pandrive_wallpaper.log
     */
    public static void logToFile(String msg) {
        new Thread(() -> {
            try {
                File logFile = new File("/sdcard/Download/pandrive_wallpaper.log");
                java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
                String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                        .format(new java.util.Date());
                fw.write("[" + ts + "] " + msg + "\n");
                fw.close();
            } catch (Exception ignored) {}
        }).start();
    }

    /**
     * 初始化（传入Context用于访问SharedPreferences）
     * 启动时调用，同时从SP恢复上次的天气视频索引
     */
    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        cachedLat = getSharedPreferences().getFloat(SP_LAST_LAT, 0f);
        cachedLon = getSharedPreferences().getFloat(SP_LAST_LON, 0f);

        if (currentVideoIndex < 0) {
            currentVideoIndex = getSharedPreferences().getInt(SP_VIDEO_INDEX, -1);
        }

        // 确保天气视频文件就位（首次安装时从assets复制到设备存储）
        ensureWeatherVideos();
    }

    private SharedPreferences getSharedPreferences() {
        return appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public void setListener(OnWeatherListener listener) {
        this.listener = listener;
    }

    /**
     * GPS位置更新时调用，满足时间间隔则异步请求天气
     * GPS无信号(0,0)时尝试IP定位兜底
     */
    public void onLocationUpdate(double lat, double lon) {
        if (lat == 0 && lon == 0) {
            tryIpLocationFallback();
            return;
        }
        // 保存有效坐标
        saveLastCoordinates(lat, lon);
        long now = System.currentTimeMillis();
        // 首次（weatherCode<0）直接请求，之后30分钟间隔
        if (weatherCode >= 0 && !fetching && (now - lastFetchTime) < FETCH_INTERVAL_MS) {
            return;
        }
        fetchWeather(lat, lon);
    }

    /**
     * 强制刷新天气（不受时间间隔限制）
     * GPS无信号(0,0)时优先用缓存坐标，其次尝试IP定位
     */
    public void forceRefresh(double lat, double lon) {
        if (lat == 0 && lon == 0) {
            // 优先用上次缓存坐标
            float savedLat = getSharedPreferences().getFloat(SP_LAST_LAT, 0f);
            float savedLon = getSharedPreferences().getFloat(SP_LAST_LON, 0f);
            if (savedLat != 0 || savedLon != 0) {
                fetchWeather(savedLat, savedLon);
            } else {
                tryIpLocationFallback();
            }
            return;
        }
        saveLastCoordinates(lat, lon);
        fetchWeather(lat, lon);
    }

    private void fetchWeather(final double lat, final double lon) {
        if (fetching) {
            return;
        }
        fetching = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doFetch(lat, lon);
                } catch (Exception e) {
                    Log.e(TAG, "天气请求失败: " + e.getMessage());
                } finally {
                    fetching = false;
                }
            }
        }).start();
    }

    // ==================== IP定位兜底 ====================

    /**
     * GPS无信号时，通过IP地址获取大致坐标
     * 有5分钟间隔限制，避免频繁请求
     */
    private void tryIpLocationFallback() {
        if (ipLocating) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastIpLocationTime < IP_LOCATION_INTERVAL_MS) {
            return;
        }
        ipLocating = true;
        lastIpLocationTime = now;

        new Thread(() -> {
            try {
                double[] coords = fetchLocationByIp();
                if (coords != null) {
                    saveLastCoordinates(coords[0], coords[1]);
                    fetchWeather(coords[0], coords[1]);
                }
            } catch (Exception ignored) {
            } finally {
                ipLocating = false;
            }
        }).start();
    }

    /**
     * 通过 ip-api.com 获取IP对应的大致经纬度
     * @return [lat, lon] 或 null
     */
    private double[] fetchLocationByIp() throws Exception {
        String urlStr = "http://ip-api.com/json/?fields=status,lat,lon";
        URL url = new URL(urlStr);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(IP_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(IP_READ_TIMEOUT_MS);
            conn.setDoInput(true);

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.e(TAG, "IP定位API返回码: " + code);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            String status = json.optString("status", "");
            if ("success".equals(status)) {
                double lat = json.getDouble("lat");
                double lon = json.getDouble("lon");
                return new double[]{lat, lon};
            }
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    // ==================== 坐标持久化 ====================

    private void saveLastCoordinates(double lat, double lon) {
        getSharedPreferences().edit()
                .putFloat(SP_LAST_LAT, (float) lat)
                .putFloat(SP_LAST_LON, (float) lon)
                .apply();
    }

    private void doFetch(double lat, double lon) throws Exception {
        String urlStr = String.format(Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
            +         "&current=weather_code,temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,wind_direction_10m",
            lat, lon);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoInput(true);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                Log.e(TAG, "天气API返回码: " + responseCode);
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            parseWeatherResponse(sb.toString());

        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private void parseWeatherResponse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject current = root.getJSONObject("current");

        int newCode = current.getInt("weather_code");
        double newTemp = current.getDouble("temperature_2m");
        double newFeels = current.getDouble("apparent_temperature");
        double newWind = current.getDouble("wind_speed_10m");
        int newWindDir = current.getInt("wind_direction_10m");
        int newHumidity = current.optInt("relative_humidity_2m", -1);

        // 检查是否变化
        int newVideoIndex = mapWeatherToVideo(newCode, newWind);
        boolean changed = (newVideoIndex != currentVideoIndex)
                || Double.isNaN(temperature)
                || Math.abs(newTemp - temperature) > 0.5;

        weatherCode = newCode;
        temperature = newTemp;
        apparentTemp = newFeels;
        windSpeed = newWind;
        windDirection = newWindDir;
        humidity = newHumidity;
        currentVideoIndex = newVideoIndex;
        lastFetchTime = System.currentTimeMillis();

        // 持久化视频索引
        getSharedPreferences().edit().putInt(SP_VIDEO_INDEX, newVideoIndex).apply();

        Log.d(TAG, String.format(Locale.US,
            "天气更新: code=%d temp=%.1f° feels=%.1f° humidity=%d%% wind=%.1fkm/h dir=%d° video=%s",
            weatherCode, temperature, apparentTemp, humidity, windSpeed, windDirection,
            currentVideoIndex >= 0 ? VIDEO_FILES[currentVideoIndex] : "无"));

        if (changed && listener != null) {
            String videoFile = currentVideoIndex >= 0 ? VIDEO_FILES[currentVideoIndex] : null;
            String line1 = getWeatherDesc() + "  " + formatTemp(temperature);
            String line2 = "体感 " + formatTemp(apparentTemp);
            String line3 = getWindDirDesc() + " " + formatWindSpeed(windSpeed);
            String line4 = formatHumidity(humidity);
            listener.onWeatherUpdated(videoFile, line1, line2, line3, line4);
        }
    }

    // ==================== 映射逻辑 ====================

    /**
     * WMO weather_code + 风速 → 视频索引
     * 0=sunny, 1=cloud, 2=fog, 3=rain, 4=snow, 5=wind
     */
    private int mapWeatherToVideo(int code, double ws) {
        // 大风优先
        if (ws > WIND_SPEED_THRESHOLD && code >= 0 && code <= 3) {
            return 5; // wind
        }

        // 晴/主要晴
        if (code == 0 || code == 1) return 0; // sunny

        // 多云/阴天
        if (code == 2 || code == 3) return 1; // cloud

        // 雾
        if (code == 45 || code == 48) return 2; // fog

        // 雨：毛毛雨(51-57) + 普通雨(61-67) + 阵雨(80-82)
        if ((code >= 51 && code <= 57) || (code >= 61 && code <= 67) || (code >= 80 && code <= 82)) {
            return 3; // rain
        }

        // 雪：降雪(71-77) + 阵雪(85-86)
        if ((code >= 71 && code <= 77) || (code == 85 || code == 86)) {
            return 4; // snow
        }

        // 雷暴(95-99)归入雨
        if (code >= 95) return 3; // rain

        // 其他编码默认多云
        return 1; // cloud
    }

    // ==================== 天气描述 ====================

    /**
     * WMO weather_code → 中文描述
     */
    public String getWeatherDesc() {
        if (weatherCode < 0) return "";
        switch (weatherCode) {
            case 0: return "晴";
            case 1: return "晴";
            case 2: return "多云";
            case 3: return "阴";
            case 45: case 48: return "雾";
            case 51: return "小毛毛雨";
            case 53: return "毛毛雨";
            case 55: return "大毛毛雨";
            case 56: return "冻毛毛雨";
            case 57: return "强冻毛毛雨";
            case 61: return "小雨";
            case 63: return "中雨";
            case 65: return "大雨";
            case 66: return "冻雨";
            case 67: return "强冻雨";
            case 71: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 77: return "雪粒";
            case 80: return "小阵雨";
            case 81: return "阵雨";
            case 82: return "强阵雨";
            case 85: return "小阵雪";
            case 86: return "强阵雪";
            case 95: return "雷暴";
            case 96: return "雷暴+小冰雹";
            case 99: return "雷暴+大冰雹";
            default: return "多云";
        }
    }

    /**
     * 风向度数 → 中文方位
     */
    public String getWindDirDesc() {
        return degreeToDirection(windDirection);
    }

    private static String degreeToDirection(int degree) {
        if (degree < 0) return "";
        // 16方位取整
        String[] dirs = {
            "北", "北东北", "东北", "东东北",
            "东", "东东南", "东南", "南东南",
            "南", "南西南", "西南", "西西南",
            "西", "西西北", "西北", "北西北"
        };
        int index = ((degree + 11) % 360) / 23;
        if (index >= 16) index = 0;
        return dirs[index] + "风";
    }

    // ==================== 格式化工具 ====================

    private String formatTemp(double temp) {
        if (Double.isNaN(temp)) return "--°";
        return String.format(Locale.US, "%.0f°", temp);
    }

    private String formatWindSpeed(double ws) {
        if (Double.isNaN(ws)) return "--km/h";
        return String.format(Locale.US, "%.0fkm/h", ws);
    }

    private String formatHumidity(int h) {
        if (h < 0) return "--%";
        return h + "%";
    }

    // ==================== 辅助 ====================

    /**
     * 查找天气视频目录（Download/pandrive_weather）
     */
    private File findWeatherVideoDir() {
        File downloadDir = GridBackgroundViewHelper.findDownloadDir();
        if (downloadDir == null) return null;
        return new File(downloadDir, "pandrive_weather");
    }

    /**
     * 确保天气视频文件就位：
     * 首次安装时，从APK内置assets/pandrive_weather/复制6个视频到设备存储
     * 后台线程执行，不阻塞UI。幂等，可多次调用。
     */
    public void ensureWeatherVideos() {
        File downloadDir = GridBackgroundViewHelper.findDownloadDir();
        // findDownloadDir可能因存储未就绪返回null，回退到硬编码路径
        if (downloadDir == null) {
            downloadDir = new File("/sdcard/Download");
        }
        File weatherDir = new File(downloadDir, "pandrive_weather");

        // 后台线程复制（8MB视频不在主线程IO）
        new Thread(() -> {
            try {
                // 创建目录
                if (!weatherDir.exists()) {
                    if (!weatherDir.mkdirs()) {
                        return;
                    }
                }

                // 逐个检查并复制缺失的视频
                for (String videoFile : VIDEO_FILES) {
                    File f = new File(weatherDir, videoFile);
                    if (f.exists() && f.length() > 0) continue; // 已存在跳过

                    try (java.io.InputStream is = appContext.getAssets().open("pandrive_weather/" + videoFile);
                         FileOutputStream fos = new FileOutputStream(f)) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = is.read(buffer)) > 0) {
                            fos.write(buffer, 0, n);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }, "ensureWeatherVideos").start();
    }

    /**
     * 检查网络是否可用
     */
    private boolean isNetworkAvailable() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 获取当前视频文件名，无数据时返回null
     */
    public String getVideoFileName() {
        if (currentVideoIndex < 0) return null;
        return VIDEO_FILES[currentVideoIndex];
    }

    /**
     * 获取当前完整视频路径，文件不存在返回null
     */
    public String getVideoPath() {
        String fileName = getVideoFileName();
        if (fileName == null) return null;
        return GridBackgroundViewHelper.getVideoPath(fileName);
    }

    /**
     * 从缓存数据构造天气文字4行（line1~line4），无有效数据返回null。
     * 用于 Activity 重建后同步恢复 GridBackgroundView 的天气文字显示，
     * 避免等待 30 分钟轮询或异步 forceRefresh 返回期间文字空白。
     * 数据源为单例内缓存的天气字段，跨 Activity 保留。
     * 与 onWeatherUpdated 回调构造逻辑一致，网络刷新后回调会再次覆盖。
     */
    public String[] getWeatherInfoLines() {
        if (weatherCode < 0) return null;
        String line1 = getWeatherDesc() + "  " + formatTemp(temperature);
        String line2 = "体感 " + formatTemp(apparentTemp);
        String line3 = getWindDirDesc() + " " + formatWindSpeed(windSpeed);
        String line4 = formatHumidity(humidity);
        return new String[]{line1, line2, line3, line4};
    }

    public int getWeatherCode() { return weatherCode; }
    public double getTemperature() { return temperature; }
    public double getApparentTemp() { return apparentTemp; }
    public double getWindSpeed() { return windSpeed; }
    public int getWindDirection() { return windDirection; }
    public int getHumidity() { return humidity; }

    /**
     * 辅助类，避免WeatherHelper依赖View层
     */
    public static class GridBackgroundViewHelper {
        /**
         * 根据视频文件名获取完整路径
         */
        public static String getVideoPath(String fileName) {
            File downloadDir = findDownloadDir();
            if (downloadDir == null) return null;
            File weatherDir = new File(downloadDir, "pandrive_weather");
            if (!weatherDir.exists() || !weatherDir.isDirectory()) return null;
            File file = new File(weatherDir, fileName);
            if (file.exists() && file.isFile()) return file.getAbsolutePath();
            return null;
        }

        private static File findDownloadDir() {
            // 1. 优先系统API
            try {
                File sysDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                if (sysDir != null && sysDir.exists()) return sysDir;
            } catch (Exception ignored) {}

            // 2. 常见车机路径回退
            String[] fallbackPaths = {
                "/sdcard/Download",
                "/storage/emulated/0/Download",
                "/mnt/internal_sd/Download",
                "/mnt/sdcard/Download",
                "/sdcard/下载",
                "/storage/emulated/0/下载",
            };
            for (String p : fallbackPaths) {
                File f = new File(p);
                if (f.exists() && f.isDirectory()) return f;
            }

            // 3. 尝试从外部存储根目录下找Download/下载子目录
            File external = android.os.Environment.getExternalStorageDirectory();
            if (external != null && external.exists()) {
                for (String name : new String[]{"Download", "下载"}) {
                    File sub = new File(external, name);
                    if (sub.exists() && sub.isDirectory()) return sub;
                }
            }

            return null;
        }
    }
}
