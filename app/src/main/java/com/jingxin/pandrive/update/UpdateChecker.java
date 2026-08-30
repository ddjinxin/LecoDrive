package com.jingxin.pandrive.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.jingxin.pandrive.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 应用更新检查器
 *
 * 流程：
 * 1. checkAndDownload(activity, force) 异步调 GitHub API 拿最新 Release
 * 2. 版本相同/获取失败 → 静默（手动检查时Toast提示）
 * 3. 版本不同且未被永久忽略 → 后台下载 APK
 * 4. 下载成功 → 弹窗提示安装；失败 → 静默
 * 5. 用户点"稍后" → 该版本加入忽略集合，永久不再提示
 *
 * force=true 时跳过忽略集合（手动检查场景）
 *
 * 主动推送：
 * - checkSilently()：无 Activity 场景（前台服务定时）后台检查 → 下载 →
 *   持久化待安装版本 + 通知栏推送，点击通知由 MainActivity 弹窗安装
 * - MainActivity onCreate 时检查待安装版本（上次推送未处理则弹窗）
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    // GitHub 仓库（API 和 Release 页）
    private static final String REPO_OWNER = "ddjinxin";
    private static final String REPO_NAME  = "LecoDrive";
    private static final String API_URL =
            "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";

    // Gitee 仓库（国内优先，访问速度快；Gitee 发布 release 后自动生效，否则回退 GitHub）
    private static final String GITEE_OWNER = "dandingjx";
    private static final String GITEE_NAME  = "leco-driving";
    private static final String GITEE_API_URL =
            "https://gitee.com/api/v5/repos/" + GITEE_OWNER + "/" + GITEE_NAME + "/releases/latest";

    // 国内加速镜像列表：[类型, 基址]
    // type="prefix"：前缀拼接式（base + 原始 GitHub URL）
    // type="host"：域名替换式（github.com → base）
    private static final String[][] MIRRORS = {
        {"prefix", "https://ghproxy.net/"},
        {"prefix", "https://gh-proxy.com/"},
        {"prefix", "https://mirror.ghproxy.com/"},
        {"host",   "https://kkgithub.com"},
    };
    // 测速下载的字节数（前 16KB 足以判断连通性和速度）
    private static final int SPEED_TEST_BYTES = 16 * 1024;
    private static final int SPEED_TEST_TIMEOUT = 5000;

    // APK 缓存路径
    private static final String UPDATE_DIR  = "/sdcard/Download/LecoDrive/";
    private static final String UPDATE_FILE = UPDATE_DIR + "update.apk";

    // 永久忽略版本集合存储
    private static final String SP_NAME       = "update_prefs";
    private static final String KEY_IGNORED   = "ignored_versions";
    private static final String KEY_PENDING   = "pending_version"; // 已下载待安装的版本（配合 PendingInfo）
    private static final String KEY_PENDING_VER = "pending_ver";
    private static final String KEY_PENDING_NOTES = "pending_notes";

    // 通知推送
    private static final String CHANNEL_ID = "pandrive_update";
    private static final int NOTIFY_ID_UPDATE = 2001;
    // 通知点击广播 action
    public static final String ACTION_SHOW_UPDATE = "com.jingxin.pandrive.SHOW_UPDATE";

    private static UpdateChecker instance;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isChecking = false;  // 防止并发检查
    // 进程级标记：本次进程已检查过更新（进程重启自动重置，参考静心音乐 UpdateHelper）
    private boolean launchChecked = false;

    private UpdateChecker(Context context) {
        appContext = context.getApplicationContext();
    }

    public static synchronized UpdateChecker getInstance(Context context) {
        if (instance == null) {
            instance = new UpdateChecker(context);
        }
        return instance;
    }

    // ==================== 对外接口 ====================

    /**
     * 启动时自动检查（遵守"永久忽略"列表，本次启动只检查一次）。
     */
    public void checkOnLaunch(Activity activity) {
        if (launchChecked) {
            Log.d(TAG, "本次启动已检查过更新，跳过");
            return;
        }
        launchChecked = true;
        checkAndDownload(activity, false);
    }

    /**
     * 手动触发检查（force=true，忽略"永久忽略"列表）。
     */
    public void checkManually(Activity activity) {
        checkAndDownload(activity, true);
    }

    /**
     * 后台静默检查（无 Activity 场景，如前台服务定时触发）：
     * 发现新版本 → 后台下载 → 持久化待安装版本 → 发通知栏推送。
     * 通知点击后由 MainActivity 弹窗安装。
     */
    public void checkSilently() {
        if (isChecking) {
            Log.d(TAG, "已有检查任务在运行，跳过 checkSilently");
            return;
        }
        isChecking = true;

        new Thread(() -> {
            try {
                String currentVer = getCurrentVersionName();
                if (currentVer == null) {
                    Log.e(TAG, "checkSilently: 无法获取当前版本号");
                    isChecking = false;
                    return;
                }
                ReleaseInfo info = fetchLatestRelease();
                if (info == null) {
                    Log.w(TAG, "checkSilently: 无法获取最新版本（网络问题）");
                    isChecking = false;
                    return;
                }
                String latestVer = normalizeVersion(info.tagName);
                String currentNorm = normalizeVersion(currentVer);
                if (compareVersions(currentNorm, latestVer) >= 0) {
                    Log.i(TAG, "checkSilently: 已是最新版本");
                    isChecking = false;
                    return;
                }
                if (isVersionIgnored(info.tagName)) {
                    Log.i(TAG, "checkSilently: 用户已忽略 " + info.tagName);
                    isChecking = false;
                    return;
                }
                Log.i(TAG, "checkSilently: 后台下载 APK: " + info.downloadUrl);
                boolean ok = downloadApk(info.downloadUrl);
                if (!ok) {
                    Log.e(TAG, "checkSilently: 下载失败");
                    isChecking = false;
                    return;
                }
                // 持久化待安装版本（供 MainActivity 启动/通知点击时弹窗）
                appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit()
                        .putString(KEY_PENDING_VER, info.tagName)
                        .putString(KEY_PENDING_NOTES, info.notes != null ? info.notes : "")
                        .putBoolean(KEY_PENDING, true)
                        .apply();
                isChecking = false;
                postUpdateNotification(info);
            } catch (Exception e) {
                Log.e(TAG, "checkSilently 异常: " + e.getMessage(), e);
                isChecking = false;
            }
        }).start();
    }

    /**
     * 核心流程：检查 → 下载 → 弹窗
     * @param force true 表示手动检查，忽略忽略列表
     */
    private void checkAndDownload(final Activity activity, final boolean force) {
        if (isChecking) {
            Log.d(TAG, "已有检查任务在运行，跳过");
            if (force) {
                mainHandler.post(() ->
                    Toast.makeText(activity, "正在检查更新，请稍候...", Toast.LENGTH_SHORT).show());
            }
            return;
        }
        isChecking = true;

        new Thread(() -> {
            try {
                // 1. 拿当前版本号
                String currentVer = getCurrentVersionName();
                if (currentVer == null) {
                    Log.e(TAG, "无法获取当前版本号");
                    finishCheck(activity, force, false, "无法获取当前版本号");
                    return;
                }
                Log.i(TAG, "当前版本: " + currentVer + "，开始检查 GitHub 最新版本");

                // 2. 调 GitHub API
                ReleaseInfo info = fetchLatestRelease();
                if (info == null) {
                    Log.w(TAG, "无法获取最新版本信息（网络问题）");
                    finishCheck(activity, force, false, "无法连接更新服务器");
                    return;
                }
                // 版本对比（不再持久化 last_check_ver：改用进程内存 launchChecked，进程重启自动重置）

                String latestVer = normalizeVersion(info.tagName);
                String currentNorm = normalizeVersion(currentVer);
                Log.i(TAG, "最新版本: " + info.tagName + " (规范化: " + latestVer + ")");

                // 3. 版本对比（语义化比较，1.0 == 1.0.0）
                if (compareVersions(currentNorm, latestVer) >= 0) {
                    Log.i(TAG, "已是最新版本");
                    finishCheck(activity, force, false, "已是最新版本");
                    return;
                }

                // 4. 检查永久忽略列表（force 模式跳过）
                if (!force && isVersionIgnored(info.tagName)) {
                    Log.i(TAG, "用户已忽略版本 " + info.tagName + "，不再提示");
                    finishCheck(activity, force, false, null);
                    return;
                }

                // 5. 后台静默下载
                Log.i(TAG, "开始后台下载 APK: " + info.downloadUrl);
                boolean ok = downloadApk(info.downloadUrl);
                if (!ok) {
                    Log.e(TAG, "下载失败");
                    finishCheck(activity, force, false, "下载失败，请检查网络");
                    return;
                }

                // 6. 下载成功 → 主线程弹窗
                Log.i(TAG, "下载完成，提示用户安装");
                mainHandler.post(() -> showInstallDialog(activity, info, force));

            } catch (Exception e) {
                Log.e(TAG, "检查更新异常: " + e.getMessage(), e);
                finishCheck(activity, force, false, "检查更新异常: " + e.getMessage());
            }
        }).start();
    }

    // ==================== 内部实现 ====================

    private String getCurrentVersionName() {
        try {
            return appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** 调 Gitee API（优先，国内快），失败回退 GitHub API */
    private ReleaseInfo fetchLatestRelease() {
        // 1. 先试 Gitee（国内快）
        ReleaseInfo info = fetchFromApi(GITEE_API_URL, true);
        if (info != null) {
            Log.i(TAG, "从 Gitee 获取版本信息成功");
            return info;
        }
        Log.w(TAG, "Gitee API 失败，回退 GitHub");
        // 2. 回退 GitHub
        info = fetchFromApi(API_URL, false);
        if (info != null) {
            Log.i(TAG, "从 GitHub 获取版本信息成功");
        }
        return info;
    }

    /** 通用 API 请求：isGitee=true 时不需要 Accept 头 */
    private ReleaseInfo fetchFromApi(String apiUrl, boolean isGitee) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "LecoDrive-Updater");
            if (!isGitee) {
                conn.setRequestProperty("Accept", "application/vnd.github+json");
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, (isGitee ? "Gitee" : "GitHub") + " API 响应码: " + code);
                return null;
            }
            String body = readStream(conn.getInputStream());
            return parseReleaseJson(body);
        } catch (Exception e) {
            Log.e(TAG, "fetchFromApi (" + (isGitee ? "Gitee" : "GitHub") + ") 失败: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 简单 JSON 解析（不引入第三方库，手写提取需要的3个字段） */
    private ReleaseInfo parseReleaseJson(String json) {
        try {
            String tagName   = extractString(json, "tag_name");
            String body      = extractString(json, "body");
            String downloadUrl = extractFirstAssetUrl(json);
            if (tagName == null || downloadUrl == null) return null;
            ReleaseInfo info = new ReleaseInfo();
            info.tagName   = tagName;
            info.notes     = (body != null) ? body : "";
            info.downloadUrl = downloadUrl;
            return info;
        } catch (Exception e) {
            Log.e(TAG, "parseReleaseJson 失败: " + e.getMessage());
            return null;
        }
    }

    /** 从 JSON 中提取字符串字段（简易实现，处理转义字符） */
    private String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        idx = json.indexOf(":", idx);
        if (idx < 0) return null;
        idx++;
        // 跳过空白
        while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) idx++;
        if (idx >= json.length() || json.charAt(idx) != '"') return null;
        idx++;
        StringBuilder sb = new StringBuilder();
        while (idx < json.length()) {
            char c = json.charAt(idx);
            if (c == '\\' && idx + 1 < json.length()) {
                char next = json.charAt(idx + 1);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(next); break;
                }
                idx += 2;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
                idx++;
            }
        }
        return sb.toString();
    }

    /** 从 JSON 中提取第一个 .apk asset 的 browser_download_url
     *  Gitee/GitHub 的 assets 列表中可能包含源码包(.zip/.tar.gz)，需过滤出 APK。 */
    private String extractFirstAssetUrl(String json) {
        String key = "browser_download_url";
        int searchFrom = 0;
        while (true) {
            int idx = json.indexOf(key, searchFrom);
            if (idx < 0) break;
            int valStart = json.indexOf("\"", json.indexOf(":", idx));
            if (valStart < 0) break;
            valStart++;
            int end = json.indexOf("\"", valStart);
            if (end < 0) break;
            String url = json.substring(valStart, end);
            // 优先返回 .apk 后缀的 URL，跳过 .zip/.tar.gz 源码包
            if (url.toLowerCase().endsWith(".apk")) {
                return url;
            }
            searchFrom = end + 1;
        }
        // 没找到 .apk，回退取第一个 asset URL（兼容旧逻辑）
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx = json.indexOf("\"", json.indexOf(":", idx));
        if (idx < 0) return null;
        idx++;
        int end = json.indexOf("\"", idx);
        if (end < 0) return null;
        return json.substring(idx, end);
    }

    /**
     * 下载 APK：
     * - 如果 downloadUrl 来自 Gitee，直接下载（国内无需镜像）
     * - 如果来自 GitHub，走镜像测速 + 主源回退
     */
    private boolean downloadApk(String downloadUrl) {
        // 先清理旧 APK
        File oldFile = new File(UPDATE_FILE);
        if (oldFile.exists()) oldFile.delete();

        File dir = new File(UPDATE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "创建下载目录失败: " + UPDATE_DIR);
            return false;
        }

        // Gitee 直链：国内访问快，直接下载
        if (downloadUrl.contains("gitee.com")) {
            Log.i(TAG, "从 Gitee 直链下载: " + downloadUrl);
            if (tryDownload(downloadUrl, UPDATE_FILE, 30000)) {
                Log.i(TAG, "Gitee 下载成功");
                return true;
            }
            Log.w(TAG, "Gitee 下载失败，尝试 GitHub 镜像");
            // Gitee 失败，转 GitHub 对应 Release 的 APK 直链
            String githubUrl = "https://github.com/" + REPO_OWNER + "/" + REPO_NAME +
                    "/releases/download/" + extractVersionFromUrl(downloadUrl) + "/app-release.apk";
            return downloadFromGithubMirrors(githubUrl);
        }

        // GitHub 下载：走镜像测速
        return downloadFromGithubMirrors(downloadUrl);
    }

    /** GitHub 镜像下载：并发测速选最快镜像，按速度顺序依次尝试，全失败回退主源 */
    private boolean downloadFromGithubMirrors(String originalUrl) {
        // 构造所有候选 URL（镜像 + 主源兜底）
        List<String> mirrors = new ArrayList<>();
        for (String[] m : MIRRORS) {
            mirrors.add(buildMirrorUrl(m, originalUrl));
        }

        // 并发测速，按速度从快到慢排序
        Log.i(TAG, "开始并发测速 " + mirrors.size() + " 个镜像...");
        List<SpeedResult> sorted = speedTest(mirrors);
        for (SpeedResult sr : sorted) {
            Log.i(TAG, "测速排名: " + sr.url + " → " + sr.latencyMs + "ms");
        }

        // 按测速结果尝试下载（最快的优先）
        for (SpeedResult sr : sorted) {
            Log.i(TAG, "尝试从镜像下载: " + sr.url);
            if (tryDownload(sr.url, UPDATE_FILE, 20000)) {
                Log.i(TAG, "镜像下载成功: " + sr.url);
                return true;
            }
            Log.w(TAG, "镜像下载失败，尝试下一个");
        }

        // 所有镜像都失败，回退 GitHub 主源
        Log.w(TAG, "所有镜像均失败，回退 GitHub 主源");
        return tryDownload(originalUrl, UPDATE_FILE, 20000);
    }

    /** 构造镜像 URL */
    private String buildMirrorUrl(String[] mirror, String originalUrl) {
        String type = mirror[0];
        String base = mirror[1];
        if ("prefix".equals(type)) {
            return base + originalUrl;
        } else { // host：域名替换
            return originalUrl.replace("https://github.com", base);
        }
    }

    /** 从 Gitee 下载 URL 中提取版本号（如 v1.0.6），用于构造 GitHub 对应直链 */
    private String extractVersionFromUrl(String url) {
        int idx = url.indexOf("/download/");
        if (idx < 0) return "";
        int start = idx + "/download/".length();
        int end = url.indexOf("/", start);
        if (end < 0) return "";
        return url.substring(start, end);
    }

    /**
     * 并发测速：对每个镜像发 GET 请求读前 16KB，测首段延迟。
     * @return 按延迟从低到高排序的结果列表（失败的排除）
     */
    private List<SpeedResult> speedTest(List<String> urls) {
        ExecutorService executor = Executors.newFixedThreadPool(urls.size());
        List<Future<SpeedResult>> futures = new ArrayList<>();
        for (final String url : urls) {
            futures.add(executor.submit(() -> {
                long start = System.currentTimeMillis();
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "LecoDrive-Updater");
                    conn.setConnectTimeout(SPEED_TEST_TIMEOUT);
                    conn.setReadTimeout(SPEED_TEST_TIMEOUT);
                    conn.setInstanceFollowRedirects(true);
                    int code = conn.getResponseCode();
                    if (code != 200) return new SpeedResult(url, Long.MAX_VALUE);
                    // 拒绝 HTML（镜像错误页）
                    String ct = conn.getContentType();
                    if (ct != null && ct.toLowerCase().contains("text/html")) {
                        return new SpeedResult(url, Long.MAX_VALUE);
                    }
                    // 读前 16KB 测速度
                    InputStream is = conn.getInputStream();
                    byte[] buf = new byte[8192];
                    int total = 0, n;
                    while (total < SPEED_TEST_BYTES && (n = is.read(buf)) > 0) {
                        total += n;
                    }
                    is.close();
                    if (total == 0) return new SpeedResult(url, Long.MAX_VALUE);
                    long latency = System.currentTimeMillis() - start;
                    return new SpeedResult(url, latency);
                } catch (Exception e) {
                    return new SpeedResult(url, Long.MAX_VALUE);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }));
        }
        List<SpeedResult> results = new ArrayList<>();
        for (Future<SpeedResult> f : futures) {
            try {
                SpeedResult sr = f.get();
                if (sr.latencyMs != Long.MAX_VALUE) results.add(sr);
            } catch (Exception ignored) {}
        }
        executor.shutdown();
        // 按延迟升序
        results.sort((a, b) -> Long.compare(a.latencyMs, b.latencyMs));
        return results;
    }

    private boolean tryDownload(String urlStr, String destPath, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "LecoDrive-Updater");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "下载响应码: " + code + " url=" + urlStr);
                return false;
            }
            // 校验1：Content-Type 拒绝 HTML（镜像常返回错误页/首页）
            String contentType = conn.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                Log.w(TAG, "返回内容是 HTML，非 APK（镜像错误页）: " + urlStr);
                return false;
            }
            int total = conn.getContentLength();
            InputStream is = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(destPath);
            byte[] buf = new byte[8192];
            int n;
            long downloaded = 0;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    Log.d(TAG, "下载进度: " + (downloaded * 100 / total) + "%");
                }
            }
            fos.flush();
            fos.close();
            is.close();
            // 校验2：下载完整性（total>0 时必须完全匹配）
            if (total > 0 && downloaded != total) {
                Log.e(TAG, "下载不完整: " + downloaded + "/" + total + " bytes, url=" + urlStr);
                new File(destPath).delete();
                return false;
            }
            // 校验3：APK 魔数（ZIP 格式前4字节 PK\x03\x04），彻底拦截 HTML/错误页
            if (!isApkFile(destPath)) {
                Log.e(TAG, "下载文件不是有效 APK（魔数校验失败）: " + destPath + " url=" + urlStr);
                new File(destPath).delete();
                return false;
            }
            Log.i(TAG, "下载完成: " + destPath + " (" + downloaded + " bytes)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "下载失败: " + urlStr + " - " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 校验 APK 魔数：ZIP 文件前4字节是 0x50 0x4B 0x03 0x04 (PK\x03\x04)。
     * HTML/JSON/错误页等非 APK 文件会在此被拦截。
     */
    private boolean isApkFile(String path) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(path);
            byte[] header = new byte[4];
            int read = fis.read(header);
            if (read < 4) return false;
            return header[0] == 0x50 && header[1] == 0x4B
                && header[2] == 0x03 && header[3] == 0x04;
        } catch (Exception e) {
            return false;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 弹窗提示安装 */
    private void showInstallDialog(Activity activity, ReleaseInfo info, boolean force) {
        if (activity == null || activity.isFinishing()) return;

        StringBuilder msg = new StringBuilder();
        msg.append("发现新版本: ").append(info.tagName).append("\n\n");
        if (info.notes != null && !info.notes.isEmpty()) {
            // Release Notes 最长显示500字符
            String notes = info.notes.length() > 500
                    ? info.notes.substring(0, 500) + "..." : info.notes;
            msg.append("更新内容:\n").append(notes).append("\n\n");
        }
        msg.append("已下载完成，是否立即安装?");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("应用更新");
        builder.setMessage(msg.toString());
        builder.setCancelable(false);
        builder.setPositiveButton("立即安装", (DialogInterface d, int w) -> {
            installApk(activity);
        });
        builder.setNegativeButton("稍后", (DialogInterface d, int w) -> {
            // 加入永久忽略列表
            if (!force) {
                addIgnoredVersion(info.tagName);
                Log.i(TAG, "用户忽略版本: " + info.tagName);
            }
        });
        builder.show();
    }

    /** 调起系统安装器 */
    private void installApk(Activity activity) {
        try {
            File apkFile = new File(UPDATE_FILE);
            if (!apkFile.exists()) {
                Toast.makeText(activity, "APK 文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addCategory(Intent.CATEGORY_DEFAULT);

            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ 用 FileProvider
                uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "调起安装失败: " + e.getMessage(), e);
            Toast.makeText(activity, "调起安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 待安装版本 & 通知推送 ====================

    /**
     * 启动时检查待安装版本（上次后台推送未处理），有则弹窗。
     */
    public void onPendingUpdate(Activity activity) {
        PendingUpdate pending = getPendingUpdate();
        if (pending == null) return;
        showPendingInstallDialog(activity, pending);
    }

    /**
     * 弹安装窗（已有待安装版本时，启动 / 通知点击触发）。
     */
    public void showPendingInstallDialog(Activity activity, PendingUpdate pending) {
        if (activity == null || activity.isFinishing()) return;

        StringBuilder msg = new StringBuilder();
        msg.append("发现新版本: ").append(pending.version).append("\n\n");
        if (pending.notes != null && !pending.notes.isEmpty()) {
            String notes = pending.notes.length() > 500
                    ? pending.notes.substring(0, 500) + "..." : pending.notes;
            msg.append("更新内容:\n").append(notes).append("\n\n");
        }
        msg.append("已下载完成，是否立即安装?");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("应用更新");
        builder.setMessage(msg.toString());
        builder.setCancelable(false);
        builder.setPositiveButton("立即安装", (DialogInterface d, int w) -> {
            clearPendingUpdate();
            installApk(activity);
        });
        builder.setNegativeButton("稍后", (DialogInterface d, int w) -> {
            // 稍后：加入忽略列表 + 清除待安装标记（不再推送该版本）
            addIgnoredVersion(pending.version);
            clearPendingUpdate();
            Log.i(TAG, "用户忽略版本: " + pending.version);
        });
        builder.setNeutralButton("取消", (DialogInterface d, int w) -> {
            // 取消：清除待安装标记，下次后台检查到新版本再推送
            clearPendingUpdate();
        });
        builder.show();
    }

    /**
     * 读取待安装版本。无则返回 null。
     */
    public PendingUpdate getPendingUpdate() {
        SharedPreferences sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        if (!sp.getBoolean(KEY_PENDING, false)) return null;
        PendingUpdate p = new PendingUpdate();
        p.version = sp.getString(KEY_PENDING_VER, "");
        p.notes = sp.getString(KEY_PENDING_NOTES, "");
        return p;
    }

    /**
     * 清除待安装标记（用户已安装/已忽略/已取消时调用）。
     */
    public void clearPendingUpdate() {
        appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_PENDING).remove(KEY_PENDING_VER).remove(KEY_PENDING_NOTES)
                .apply();
    }

    /**
     * 推送更新通知（点击后弹安装窗）。
     */
    private void postUpdateNotification(ReleaseInfo info) {
        NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("发现新版本时推送更新通知");
            nm.createNotificationChannel(channel);
        }

        Intent intent = new Intent(ACTION_SHOW_UPDATE);
        intent.setPackage(appContext.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(appContext, 0, intent,
                (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(appContext, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(appContext);
        }
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("发现新版本 " + info.tagName)
                .setContentText("点击查看更新内容并安装")
                .setContentIntent(pi)
                .setAutoCancel(true);
        nm.notify(NOTIFY_ID_UPDATE, builder.build());
    }

    /**
     * 应用更新通知是否已显示（用于点击广播后移除通知）。
     */
    public static void cancelUpdateNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFY_ID_UPDATE);
    }

    /** 待安装版本信息 */
    public static class PendingUpdate {
        public String version;
        public String notes;
    }

    // ==================== 永久忽略列表 ====================

    private boolean isVersionIgnored(String version) {
        Set<String> set = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_IGNORED, new HashSet<>());
        return set.contains(version);
    }

    private void addIgnoredVersion(String version) {
        SharedPreferences sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(sp.getStringSet(KEY_IGNORED, new HashSet<>()));
        set.add(version);
        sp.edit().putStringSet(KEY_IGNORED, set).apply();
    }

    // ==================== 辅助 ====================

    /** 规范化版本号：去掉 "v" 前缀，去空白 */
    private String normalizeVersion(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.toLowerCase().startsWith("v")) v = v.substring(1);
        return v;
    }

    /**
     * 语义化版本对比（支持 1.0 == 1.0.0）。
     * @return v1 < v2 返回 -1；相等返回 0；v1 > v2 返回 1
     */
    private int compareVersions(String v1, String v2) {
        if (v1 == null) v1 = "";
        if (v2 == null) v2 = "";
        String[] a = v1.split("\\.");
        String[] b = v2.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = (i < a.length && !a[i].isEmpty()) ? parseIntSafe(a[i]) : 0;
            int bi = (i < b.length && !b[i].isEmpty()) ? parseIntSafe(b[i]) : 0;
            if (ai != bi) return ai > bi ? 1 : -1;
        }
        return 0;
    }

    /** 解析整数，非数字部分取 0（兼容 1.0-beta 这种后缀） */
    private int parseIntSafe(String s) {
        try {
            // 取连续数字部分
            int end = 0;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            if (end == 0) return 0;
            return Integer.parseInt(s.substring(0, end));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 检查流程结束统一出口 */
    private void finishCheck(Activity activity, boolean force, boolean showAlways, String msg) {
        isChecking = false;
        if (force && msg != null) {
            // 手动检查时，无论成功失败都给用户一个反馈
            mainHandler.post(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
        }
        // 非手动检查时：静默（不弹任何提示，符合"后台静默"要求）
    }

    private String readStream(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        return new String(baos.toByteArray(), "UTF-8");
    }

    // ==================== 数据结构 ====================

    private static class ReleaseInfo {
        String tagName;
        String notes;
        String downloadUrl;
    }

    /** 测速结果：URL + 延迟(ms) */
    private static class SpeedResult {
        final String url;
        final long latencyMs;
        SpeedResult(String url, long latencyMs) {
            this.url = url;
            this.latencyMs = latencyMs;
        }
    }
}
