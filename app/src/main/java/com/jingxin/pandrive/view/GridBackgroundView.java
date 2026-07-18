package com.jingxin.pandrive.view;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import com.jingxin.pandrive.data.WeatherHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 科幻渐变背景 + 壁纸 + 天气文字
 *
 * 渲染层次：
 * 1. 壁纸/渐变背景（底层）
 *    - 视频壁纸：TextureView子View（index 0）
 *    - 图片壁纸：dispatchDraw中drawBitmap（center-crop）
 *    - 无壁纸：渐变背景
 * 2. 子View（仪表盘、导航等）
 * 3. 半透明遮罩（仅壁纸激活时）
 * 4. 天气文字（最顶层，带轻微晃动）
 */
public class GridBackgroundView extends FrameLayout {

    private static final String TAG = "GridBackgroundView";
    private static final String WALLPAPER_DIR_NAME = "pandrive_wallpaper";

    // 静态实例引用：供 SettingsActivity 直接触发刷新（车机多窗口模式下 onResume 不可靠）
    private static volatile GridBackgroundView instance = null;

    public static GridBackgroundView getInstance() {
        return instance;
    }

    private boolean isNightMode = false;

    private Paint bgPaint;
    private Paint wallpaperPaint;    // 图片壁纸
    private Paint topLabelPaint;     // 温度大字
    private Paint edgeLabelPaint;    // 车道边文字

    private int cachedW = 0, cachedH = 0;
    private LinearGradient bgShader;
    private boolean lastShaderNightMode = false;

    // 天气信息
    private String weatherDesc = "";     // 晴
    private String tempText = "";        // 25°
    private String windText = "";        // 东南风 12km/h
    private String humidityText = "";    // 湿度 65%

    // 晃动动画
    private long swayStartTime = 0;
    private float swayAmpX = 2f;   // dp
    private float swayAmpY = 1.5f; // dp
    private float swaySpeed = 0.4f;

    // 车道边缘几何缓存
    private float cachedLeftEdgeAngle = 0;
    private float cachedRightEdgeAngle = 0;
    private float[] cachedLeftEdgeMid = null;
    private float[] cachedRightEdgeMid = null;

    // ==================== 壁纸相关 ====================
    private Bitmap wallpaperBitmap = null;
    private String currentWallpaperPath = null;
    private SurfaceView wallpaperSurfaceView = null;
    private MediaPlayer wallpaperMediaPlayer = null;
    private boolean wallpaperActive = false;
    private boolean videoPrepared = false;

    // ==================== 天气动画相关 ====================
    private boolean weatherAnimationMode = false;
    private String currentWeatherVideoPath = null;

    public GridBackgroundView(Context context) {
        super(context);
        init();
    }

    public GridBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GridBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        instance = this;
        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setStyle(Paint.Style.FILL);

        wallpaperPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        // 温度大字画笔
        topLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        topLabelPaint.setStyle(Paint.Style.FILL);
        topLabelPaint.setFakeBoldText(true);
        float density = getResources().getDisplayMetrics().density;
        topLabelPaint.setTextSize(38.9f * density);

        // 车道边文字画笔
        edgeLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        edgeLabelPaint.setStyle(Paint.Style.FILL);
        edgeLabelPaint.setFakeBoldText(false);
        edgeLabelPaint.setTextSize(13f * density);

        setWillNotDraw(false);
        swayStartTime = System.currentTimeMillis();
    }

    /**
     * 计算车道梯形边缘角度和中点位置
     */
    private void computeEdgeGeometry(int w, int h) {
        float laneTop = h * 0.65f;
        float laneH = h * 0.35f;

        float nearWidth = w * 0.95f;
        float farWidth = w * 0.15f;
        float centerX = w / 2f;

        float leftTopX = centerX - farWidth / 2f;
        float leftBotX = centerX - nearWidth / 2f;

        float rightTopX = centerX + farWidth / 2f;
        float rightBotX = centerX + nearWidth / 2f;

        float leftDx = leftTopX - leftBotX;
        float leftDy = laneTop - h;
        cachedLeftEdgeAngle = (float) Math.toDegrees(Math.atan2(leftDy, leftDx));

        float rightDx = rightTopX - rightBotX;
        float rightDy = laneTop - h;
        cachedRightEdgeAngle = (float) Math.toDegrees(Math.atan2(rightDy, rightDx)) + 180f;

        float offset = 8f * getResources().getDisplayMetrics().density;
        float leftMidT = 0.55f;

        float leftMidX = leftTopX + (leftBotX - leftTopX) * leftMidT;
        float leftMidY = laneTop + laneH * leftMidT;
        leftMidX -= offset;
        cachedLeftEdgeMid = new float[]{leftMidX, leftMidY};

        float rightMidX = rightTopX + (rightBotX - rightTopX) * leftMidT;
        float rightMidY = laneTop + laneH * leftMidT;
        rightMidX += offset;
        cachedRightEdgeMid = new float[]{rightMidX, rightMidY};
    }

    // ==================== 外部接口 ====================

    public void setWeatherInfo(String line1, String line2, String line3, String line4) {
        this.weatherDesc = "";
        this.tempText = "";
        this.windText = "";
        this.humidityText = "";

        if (line1 != null && !line1.isEmpty()) {
            String[] parts = line1.split("\\s+", 2);
            if (parts.length >= 1) this.weatherDesc = parts[0];
            if (parts.length >= 2) this.tempText = parts[1];
        }
        if (line3 != null) this.windText = line3;
        if (line4 != null) this.humidityText = "湿度 " + line4;

        invalidate();
    }

    public void setNightMode(boolean isNight) {
        this.isNightMode = isNight;
        bgShader = null;
        // 日/夜切换时重新加载对应壁纸
        reloadWallpaper();
        invalidate();
    }

    // 兼容旧接口（视频切换，暂不使用）
    public void switchVideo(String videoPath) {
        // 暂不播放视频
    }

    public void release() {
        releaseWallpaperResources();
    }

    /**
     * 暂停壁纸视频播放（Activity.onPause时调用）
     */
    public void pauseWallpaper() {
        if (wallpaperMediaPlayer != null && wallpaperMediaPlayer.isPlaying()) {
            wallpaperMediaPlayer.pause();
        }
    }

    /**
     * 恢复壁纸视频播放（Activity.onResume时调用）
     */
    public void resumeWallpaper() {
        if (wallpaperMediaPlayer != null && videoPrepared) {
            try {
                wallpaperMediaPlayer.start();
            } catch (Exception e) {
                Log.w(TAG, "Resume wallpaper video failed", e);
            }
        }
    }

    /**
     * 重新加载壁纸（从磁盘扫描pandrive_wallpaper/目录）
     * 根据当前日夜模式选择day.*或night.*文件
     * 天气动画模式下，加载当前天气对应的视频
     */
    public void reloadWallpaper() {
        // 天气动画模式：优先加载天气视频
        if (weatherAnimationMode) {
            releaseWallpaperResources();
            if (currentWeatherVideoPath != null) {
                File vf = new File(currentWeatherVideoPath);
                if (vf.exists() && vf.isFile()) {
                    setupVideoWallpaper(currentWeatherVideoPath);
                }
            }
            invalidate();
            return;
        }

        File dir = getWallpaperDir();
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            // 无壁纸目录，清除壁纸
            File dd = findDownloadDir();
            releaseWallpaperResources();
            wallpaperActive = false;
            invalidate();
            return;
        }

        String prefix = isNightMode ? "night" : "day";

        // 按优先级查找：视频 > 图片
        String[] videoExts = {".mp4", ".3gp", ".webm"};
        String[] imageExts = {".jpg", ".jpeg", ".png", ".webp"};

        String videoPath = null;
        String imagePath = null;

        for (String ext : videoExts) {
            File f = new File(dir, prefix + ext);
            if (f.exists() && f.isFile() && f.length() > 0) {
                videoPath = f.getAbsolutePath();
                break;
            }
        }

        if (videoPath == null) {
            for (String ext : imageExts) {
                File f = new File(dir, prefix + ext);
                if (f.exists() && f.isFile() && f.length() > 0) {
                    imagePath = f.getAbsolutePath();
                    break;
                }
            }
        }

        String newPath = videoPath != null ? videoPath : imagePath;

        // 无壁纸，清除
        if (newPath == null) {
            if (currentWallpaperPath != null) {
                releaseWallpaperResources();
                invalidate();
            }
            return;
        }

        // 释放旧壁纸并加载新壁纸（即使路径相同也重新加载，因为文件内容可能已替换）
        releaseWallpaperResources();

        if (videoPath != null) {
            setupVideoWallpaper(videoPath);
        } else if (imagePath != null) {
            setupImageWallpaper(imagePath);
        }

        invalidate();
    }

    // ==================== 天气动画接口 ====================

    /**
     * 开启/关闭天气动画模式
     * 开启后，壁纸由天气数据驱动的视频替代（互斥）
     */
    public void setWeatherAnimationMode(boolean enabled) {
        this.weatherAnimationMode = enabled;
        if (!enabled) {
            currentWeatherVideoPath = null;
        }
        reloadWallpaper();
    }

    /**
     * 设置天气视频路径（仅天气动画模式生效）
     * 由 WeatherHelper 回调驱动，天气变化时自动切换视频
     */
    public void setWeatherVideo(String videoPath) {
        if (!weatherAnimationMode) {
            return;
        }
        if (videoPath != null && videoPath.equals(currentWeatherVideoPath)
                && wallpaperActive && currentWallpaperPath != null) {
            return; // 同一视频已在播放，跳过
        }
        currentWeatherVideoPath = videoPath;
        reloadWallpaper();
    }

    // ==================== 壁纸内部方法 ====================

    private void setupImageWallpaper(String path) {
        // 采样本尺寸，防止超大图片OOM
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);

        int targetW = getWidth();
        int targetH = getHeight();
        if (targetW <= 0) {
            targetW = getResources().getDisplayMetrics().widthPixels;
        }
        if (targetH <= 0) {
            targetH = getResources().getDisplayMetrics().heightPixels;
        }

        // 计算合适的采样率，图片长边超过目标2倍就降采样
        int sampleSize = 1;
        if (options.outWidth > 0 && options.outHeight > 0) {
            int maxDimension = Math.max(options.outWidth, options.outHeight);
            int targetMax = Math.max(targetW, targetH) * 2;
            while (maxDimension / (sampleSize * 2) >= targetMax) {
                sampleSize *= 2;
            }
        }

        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.RGB_565; // 节省内存，壁纸不需要透明通道

        wallpaperBitmap = BitmapFactory.decodeFile(path, options);
        if (wallpaperBitmap != null) {
            wallpaperActive = true;
            currentWallpaperPath = path;
            Log.d(TAG, "Image wallpaper loaded: " + path + " (" + wallpaperBitmap.getWidth() + "x" + wallpaperBitmap.getHeight() + ")");
        } else {
            Log.w(TAG, "Failed to decode image wallpaper: " + path);
            currentWallpaperPath = null;
        }
    }

    private void setupVideoWallpaper(String path) {
        wallpaperActive = true;
        currentWallpaperPath = path;
        videoPrepared = false;

        // 创建SurfaceView作为第一个子View（最底层）
        if (wallpaperSurfaceView == null) {
            wallpaperSurfaceView = new SurfaceView(getContext());
            wallpaperSurfaceView.setLayoutParams(
                    new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            // SurfaceView放在最底层，不拦截触摸事件
            wallpaperSurfaceView.setZOrderOnTop(false);
            addView(wallpaperSurfaceView, 0);
        }
        wallpaperSurfaceView.setVisibility(VISIBLE);

        wallpaperSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                startVideoPlayback(holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                updateVideoLayout();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopVideoPlayback();
            }
        });

        Log.d(TAG, "Video wallpaper setup: " + path);
    }

    private void startVideoPlayback(SurfaceHolder holder) {
        if (currentWallpaperPath == null) {
            return;
        }

        stopVideoPlayback();

        try {
            wallpaperMediaPlayer = new MediaPlayer();
            wallpaperMediaPlayer.setDisplay(holder);
            wallpaperMediaPlayer.setDataSource(currentWallpaperPath);
            wallpaperMediaPlayer.setLooping(true);
            wallpaperMediaPlayer.setVolume(0f, 0f); // 静音

            wallpaperMediaPlayer.setOnPreparedListener(mp -> {
                videoPrepared = true;
                mp.start();
                // 延迟更新layout：等待SurfaceView完成layout
                if (wallpaperSurfaceView != null) {
                    wallpaperSurfaceView.post(() -> updateVideoLayout());
                }
                Log.d(TAG, "Video wallpaper started");
            });

            wallpaperMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Video wallpaper error: what=" + what + " extra=" + extra);
                releaseWallpaperResources();
                return true;
            });

            wallpaperMediaPlayer.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "Video wallpaper prepare failed", e);
            releaseWallpaperResources();
        }
    }

    private void stopVideoPlayback() {
        if (wallpaperMediaPlayer != null) {
            try {
                wallpaperMediaPlayer.stop();
            } catch (Exception ignored) {}
            try {
                wallpaperMediaPlayer.release();
            } catch (Exception ignored) {}
            wallpaperMediaPlayer = null;
            videoPrepared = false;
        }
    }

    /**
     * 更新SurfaceView的布局参数，实现视频center-crop
     */
    private void updateVideoLayout() {
        if (wallpaperSurfaceView == null || wallpaperMediaPlayer == null) return;

        int parentW = getWidth();
        int parentH = getHeight();
        if (parentW <= 0 || parentH <= 0) return;

        int videoW = wallpaperMediaPlayer.getVideoWidth();
        int videoH = wallpaperMediaPlayer.getVideoHeight();
        if (videoW <= 0 || videoH <= 0) return;

        float scaleX = (float) parentW / videoW;
        float scaleY = (float) parentH / videoH;
        float scale = Math.max(scaleX, scaleY); // center-crop: 取大值

        int scaledW = (int) (videoW * scale);
        int scaledH = (int) (videoH * scale);

        int leftOffset = (parentW - scaledW) / 2;
        int topOffset = (parentH - scaledH) / 2;

        LayoutParams lp = (LayoutParams) wallpaperSurfaceView.getLayoutParams();
        lp.width = scaledW;
        lp.height = scaledH;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = leftOffset;
        lp.topMargin = topOffset;
        wallpaperSurfaceView.setLayoutParams(lp);

        Log.d(TAG, "Video layout: parent=" + parentW + "x" + parentH
                + " video=" + videoW + "x" + videoH
                + " scaled=" + scaledW + "x" + scaledH
                + " offset=" + leftOffset + "," + topOffset);
    }

    private void releaseWallpaperResources() {
        // 释放图片壁纸
        if (wallpaperBitmap != null) {
            wallpaperBitmap.recycle();
            wallpaperBitmap = null;
        }

        // 释放视频壁纸
        stopVideoPlayback();

        // 移除SurfaceView子View
        if (wallpaperSurfaceView != null) {
            removeView(wallpaperSurfaceView);
            wallpaperSurfaceView = null;
        }

        wallpaperActive = false;
        currentWallpaperPath = null;
        videoPrepared = false;
    }

    /**
     * center-crop绘制图片
     */
    private void drawCenterCropBitmap(Canvas canvas, Bitmap bitmap, int canvasW, int canvasH) {
        float bitmapW = bitmap.getWidth();
        float bitmapH = bitmap.getHeight();

        float scale = Math.max((float) canvasW / bitmapW, (float) canvasH / bitmapH);
        float scaledW = bitmapW * scale;
        float scaledH = bitmapH * scale;

        float left = (canvasW - scaledW) / 2f;
        float top = (canvasH - scaledH) / 2f;

        RectF dst = new RectF(left, top, left + scaledW, top + scaledH);
        canvas.drawBitmap(bitmap, null, dst, wallpaperPaint);
    }

    // ==================== 绘制 ====================

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(canvas);
            return;
        }

        // 1. 底层：壁纸图片 或 渐变背景
        //    （视频壁纸由TextureView子View渲染，不在这里绘制）
        if (wallpaperActive && wallpaperBitmap != null) {
            // 图片壁纸：center-crop绘制
            drawCenterCropBitmap(canvas, wallpaperBitmap, w, h);
        } else if (!wallpaperActive) {
            // 无壁纸：渐变背景
            if (bgShader == null || w != cachedW || h != cachedH || lastShaderNightMode != isNightMode) {
                if (isNightMode) {
                    bgShader = new LinearGradient(0, 0, 0, h, 0xFF0A2A1A, 0xFF0A1A3A, Shader.TileMode.CLAMP);
                } else {
                    bgShader = new LinearGradient(0, 0, 0, h, 0xFF808080, 0xFFF0F0F0, Shader.TileMode.CLAMP);
                }
                lastShaderNightMode = isNightMode;
            }
            bgPaint.setShader(bgShader);
            canvas.drawRect(0, 0, w, h, bgPaint);
        }
        // 视频壁纸：TextureView子View负责渲染（在super.dispatchDraw中绘制）

        // 2. 缓存尺寸
        if (w != cachedW || h != cachedH) {
            cachedW = w;
            cachedH = h;
            computeEdgeGeometry(w, h);
            // 尺寸变化时更新视频layout
            if (wallpaperActive) {
                updateVideoLayout();
            }
        }

        // 3. 渲染子View（视频TextureView在index 0 → 最底层，仪表盘等在上面）
        super.dispatchDraw(canvas);

        // 4. 天气文字
        drawWeatherLabels(canvas, w, h);
    }

    private void drawWeatherLabels(Canvas canvas, int w, int h) {
        if (weatherDesc.isEmpty() && tempText.isEmpty()
                && windText.isEmpty() && humidityText.isEmpty()) {
            return;
        }

        int alpha = 0x99;
        int baseColor = isNightMode ? 0xFFFFFF : 0x000000;
        int color = (alpha << 24) | baseColor;
        // 白天气温保持白色
        int tempColor = (alpha << 24) | 0xFFFFFF;

        float density = getResources().getDisplayMetrics().density;

        // 晃动偏移
        float elapsed = (System.currentTimeMillis() - swayStartTime) / 1000f;
        float swayOx = (float) Math.sin(elapsed * swaySpeed) * swayAmpX * density;
        float swayOy = (float) Math.cos(elapsed * swaySpeed * 0.7f + 1.3f) * swayAmpY * density;

        // ===== 1. 左下角：大字温度 + °圈下方小字天气状态 =====
        if (!tempText.isEmpty()) {
            topLabelPaint.setColor(tempColor);

            float fullWidth = topLabelPaint.measureText(tempText);
            float x = w * 0.09f + swayOx;
            float y = h - 16f * density + swayOy;

            canvas.drawText(tempText, x, y, topLabelPaint);

            if (!weatherDesc.isEmpty()) {
                float smallSize = topLabelPaint.getTextSize() * 0.3f;
                edgeLabelPaint.setTextSize(smallSize);
                edgeLabelPaint.setColor(tempColor);

                float degCharW = topLabelPaint.measureText("°");
                float degCenterX = x + fullWidth - degCharW * 0.5f;

                float smallW = edgeLabelPaint.measureText(weatherDesc);
                float smallX = degCenterX - smallW * 0.5f;
                float smallY = y;

                canvas.drawText(weatherDesc, smallX, smallY, edgeLabelPaint);
            }
        }

        // ===== 2. 左车道边：风向风速，旋转垂直于边缘 =====
        if (!windText.isEmpty() && cachedLeftEdgeMid != null) {
            edgeLabelPaint.setColor(color);
            edgeLabelPaint.setTextSize(13f * density);
            float x = cachedLeftEdgeMid[0] + swayOx;
            float y = cachedLeftEdgeMid[1] + swayOy;

            canvas.save();
            canvas.rotate(cachedLeftEdgeAngle, x, y);
            canvas.drawText(windText, x, y, edgeLabelPaint);
            canvas.restore();
        }

        // ===== 3. 右车道边中下：湿度，旋转垂直于边缘 =====
        if (!humidityText.isEmpty() && cachedRightEdgeMid != null) {
            edgeLabelPaint.setColor(color);
            edgeLabelPaint.setTextSize(13f * density);
            float x = cachedRightEdgeMid[0] + swayOx;
            float y = cachedRightEdgeMid[1] + swayOy;

            canvas.save();
            canvas.rotate(cachedRightEdgeAngle, x, y);
            canvas.drawText(humidityText, x, y, edgeLabelPaint);
            canvas.restore();
        }

        // 持续刷新驱动晃动动画
        invalidate();
    }

    // ==================== 壁纸目录工具方法 ====================

    public static File getWallpaperDir() {
        File downloadDir = findDownloadDir();
        if (downloadDir == null) return null;
        return new File(downloadDir, WALLPAPER_DIR_NAME);
    }

    /**
     * 确保壁纸目录存在，不存在则创建
     * @return 壁纸目录File，创建失败返回null
     */
    public static File ensureWallpaperDir() {
        File dir = getWallpaperDir();
        if (dir == null) return null;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir.exists() ? dir : null;
    }

    /**
     * 确保默认壁纸文件就位：
     * 首次安装时，从APK内置assets/default_wallpaper/复制 day.webp / night.webp 到壁纸目录
     * 幂等，已存在不覆盖。后台线程执行，不阻塞UI。
     */
    public static void ensureDefaultWallpapers(Context context) {
        final File dir = ensureWallpaperDir();
        if (dir == null) return;

        new Thread(() -> {
            String[] defaults = {"day.webp", "night.webp"};
            for (String name : defaults) {
                File target = new File(dir, name);
                if (target.exists() && target.length() > 0) continue; // 已存在跳过
                try (java.io.InputStream is = context.getAssets().open("default_wallpaper/" + name);
                     FileOutputStream os = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = is.read(buffer)) > 0) {
                        os.write(buffer, 0, n);
                    }
                } catch (Exception ignored) {
                }
            }
        }, "ensureDefaultWallpapers").start();
    }

    // 壁纸变更标记：FilePickerActivity 复制成功后置 true，MainActivity.onResume 消费
    public static volatile boolean wallpaperChanged = false;

    /**
     * 将选中的文件复制为壁纸（静态方法，可在任意 Activity 直接调用）
     * 车机多窗口模式下 onActivityResult/onResume 不可靠，故在选中文件时立即复制
     * @param srcPath 源文件绝对路径
     * @param type    "day" 或 "night"
     * @return 复制成功的目标 File，失败返回 null
     */
    public static File copyWallpaperFile(String srcPath, String type) {
        File srcFile = new File(srcPath);
        if (!srcFile.exists() || !srcFile.isFile()) {
            return null;
        }
        String extension = getExtensionFromName(srcFile.getName());
        File dir = ensureWallpaperDir();
        if (dir == null) {
            return null;
        }
        // 删除同前缀旧壁纸（所有支持的扩展名）
        String[] allExts = {".jpg", ".jpeg", ".png", ".webp", ".mp4", ".3gp", ".webm"};
        for (String ext : allExts) {
            File old = new File(dir, type + ext);
            if (old.exists()) old.delete();
        }
        File target = new File(dir, type + extension);
        try (FileInputStream is = new FileInputStream(srcFile);
             FileOutputStream os = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
            wallpaperChanged = true;
            return target;
        } catch (Exception e) {
            if (target.exists()) target.delete();
            return null;
        }
    }

    private static String getExtensionFromName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            String ext = fileName.substring(dot).toLowerCase();
            String[] supported = {".jpg", ".jpeg", ".png", ".webp", ".mp4", ".3gp", ".webm"};
            for (String s : supported) { if (s.equals(ext)) return s; }
        }
        return ".jpg";
    }

    // ==================== 通用工具方法 ====================

    public static File findDownloadDir() {
        try {
            File sysDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            if (sysDir != null && sysDir.exists()) return sysDir;
        } catch (Exception ignored) {}

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

        File external = android.os.Environment.getExternalStorageDirectory();
        if (external != null && external.exists()) {
            for (String name : new String[]{"Download", "下载"}) {
                File sub = new File(external, name);
                if (sub.exists() && sub.isDirectory()) return sub;
            }
        }
        return null;
    }

    public static File getWeatherVideoDir() {
        File downloadDir = findDownloadDir();
        if (downloadDir == null) return null;
        File weatherDir = new File(downloadDir, "pandrive_weather");
        if (weatherDir.exists() && weatherDir.isDirectory()) return weatherDir;
        return null;
    }

    public static String getVideoPath(String fileName) {
        File dir = getWeatherVideoDir();
        if (dir == null) return null;
        File file = new File(dir, fileName);
        if (file.exists() && file.isFile()) return file.getAbsolutePath();
        return null;
    }
}
