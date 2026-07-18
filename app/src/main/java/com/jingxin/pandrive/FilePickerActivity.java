package com.jingxin.pandrive;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.jingxin.pandrive.data.WeatherHelper;
import com.jingxin.pandrive.view.GridBackgroundView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用内文件浏览器 — 用于选择壁纸文件
 * 扫描目录中的图片和视频文件，网格展示缩略图
 * 在窗口/画中画模式下也能正常工作（不依赖外部文件管理器）
 */
public class FilePickerActivity extends Activity {

    public static final String EXTRA_TYPE = "picker_type";       // "day" or "night"
    public static final String EXTRA_RESULT_PATH = "result_path";

    // 窗口/分屏模式下 onActivityResult 可能不被回调（Activity 被系统重建）
    // 用静态变量暂存结果，SettingsActivity.onResume() 轮询消费
    public static String pendingWallpaperPath = null;
    public static String pendingWallpaperType = null;

    private static final String[] IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"};
    private static final String[] VIDEO_EXTS = {".mp4", ".3gp", ".webm", ".mkv"};

    private File startDir;
    private File currentDir;
    private GridView gridView;
    private FileAdapter adapter;
    private TextView pathText;
    private ExecutorService thumbnailExecutor;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_picker);

        mainHandler = new Handler(Looper.getMainLooper());
        thumbnailExecutor = Executors.newFixedThreadPool(3);

        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setImageBitmap(createBackIcon());
        pathText = findViewById(R.id.tv_path);
        gridView = findViewById(R.id.grid_files);

        // 从下载目录开始
        File downloadDir = GridBackgroundView.findDownloadDir();
        startDir = downloadDir;
        if (startDir == null) {
            startDir = android.os.Environment.getExternalStorageDirectory();
        }
        currentDir = startDir;

        btnBack.setOnClickListener(v -> {
            File parent = currentDir.getParentFile();
            if (parent == null || !parent.canRead()) {
                setResult(RESULT_CANCELED);
                finish();
            } else {
                currentDir = parent;
                loadFiles();
            }
        });

        gridView.setOnItemClickListener((parent, view, position, id) -> {
            FileItem item = adapter.getItem(position);
            if (item.isDirectory) {
                currentDir = item.file;
                loadFiles();
            } else {
                String type = getIntent().getStringExtra(EXTRA_TYPE);
                String srcPath = item.file.getAbsolutePath();
                // 立即复制为壁纸（车机多窗口模式下 onResume 不可靠，必须此刻完成复制）
                File copied = GridBackgroundView.copyWallpaperFile(srcPath, type);
                // 立即刷新主界面壁纸
                GridBackgroundView gv = GridBackgroundView.getInstance();
                if (gv != null) {
                    gv.reloadWallpaper();
                    gv.resumeWallpaper();
                }
                // 保留静态变量兼容旧逻辑（SettingsActivity 状态刷新用）
                pendingWallpaperPath = srcPath;
                pendingWallpaperType = type;
                // 车机窗口模式下 finish 后系统不会 resume SettingsActivity
                // 主动启动 MainActivity + CLEAR_TOP 清栈，让所有中间 Activity 被回收
                Intent mainIntent = new Intent(this, MainActivity.class);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(mainIntent);
                finish();
            }
        });

        loadFiles();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void loadFiles() {
        pathText.setText(currentDir.getAbsolutePath());

        File[] files = currentDir.listFiles();
        List<FileItem> items = new ArrayList<>();

        if (files != null) {
            List<File> dirs = new ArrayList<>();
            List<File> mediaFiles = new ArrayList<>();

            for (File f : files) {
                if (f.isHidden()) continue;
                if (f.isDirectory()) {
                    dirs.add(f);
                } else if (isMediaFile(f)) {
                    mediaFiles.add(f);
                }
            }

            Collections.sort(dirs, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));
            Collections.sort(mediaFiles, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));

            for (File d : dirs) items.add(new FileItem(d, true));
            for (File f : mediaFiles) items.add(new FileItem(f, false));
        }

        adapter = new FileAdapter(items);
        gridView.setAdapter(adapter);
    }

    private boolean isMediaFile(File f) {
        String name = f.getName().toLowerCase();
        for (String ext : IMAGE_EXTS) if (name.endsWith(ext)) return true;
        for (String ext : VIDEO_EXTS) if (name.endsWith(ext)) return true;
        return false;
    }

    private boolean isVideoFile(File f) {
        String name = f.getName().toLowerCase();
        for (String ext : VIDEO_EXTS) if (name.endsWith(ext)) return true;
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (thumbnailExecutor != null) {
            thumbnailExecutor.shutdownNow();
        }
    }

    // ==================== 数据模型 ====================

    private static class FileItem {
        final File file;
        final boolean isDirectory;

        FileItem(File file, boolean isDirectory) {
            this.file = file;
            this.isDirectory = isDirectory;
        }
    }

    // ==================== 适配器 ====================

    private class FileAdapter extends BaseAdapter {
        private final List<FileItem> items;
        private final LayoutInflater inflater;
        private final Bitmap folderIcon;

        FileAdapter(List<FileItem> items) {
            this.items = items;
            this.inflater = LayoutInflater.from(FilePickerActivity.this);
            this.folderIcon = createFolderIcon();
        }

        @Override
        public int getCount() { return items.size(); }

        @Override
        public FileItem getItem(int position) { return items.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_file_picker, parent, false);
            }

            FileItem item = items.get(position);
            ImageView imageView = convertView.findViewById(R.id.img_thumb);
            TextView textView = convertView.findViewById(R.id.tv_filename);
            TextView badge = convertView.findViewById(R.id.tv_badge);

            textView.setText(item.file.getName());

            if (item.isDirectory) {
                badge.setVisibility(View.GONE);
                imageView.setBackgroundColor(0xFF1A1A2E);
                imageView.setImageBitmap(folderIcon);
            } else {
                boolean isVideo = isVideoFile(item.file);
                if (isVideo) {
                    badge.setVisibility(View.VISIBLE);
                    badge.setText("视频");
                } else {
                    badge.setVisibility(View.GONE);
                }

                final String path = item.file.getAbsolutePath();
                imageView.setTag(path);
                imageView.setBackgroundColor(0xFF2A2A4A);
                imageView.setImageBitmap(null);

                thumbnailExecutor.execute(() -> {
                    Bitmap thumb = loadThumbnail(item.file);
                    if (thumb != null) {
                        final String tag = (String) imageView.getTag();
                        if (path.equals(tag)) {
                            mainHandler.post(() -> {
                                String currentTag = (String) imageView.getTag();
                                if (path.equals(currentTag)) {
                                    imageView.setBackgroundColor(0xFF000000);
                                    imageView.setImageBitmap(thumb);
                                }
                            });
                        }
                    }
                });
            }

            return convertView;
        }
    }

    // ==================== 缩略图加载 ====================

    private Bitmap loadThumbnail(File file) {
        try {
            if (isVideoFile(file)) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(file.getAbsolutePath());
                    Bitmap frame = retriever.getFrameAtTime();
                    if (frame != null) {
                        return scaleBitmap(frame, 240);
                    }
                } finally {
                    try { retriever.release(); } catch (Exception ignored) {}
                }
            } else {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                if (opts.outWidth > 0) {
                    opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, 240, 240);
                    opts.inJustDecodeBounds = false;
                    Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                    if (bmp != null) {
                        return scaleBitmap(bmp, 240);
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Bitmap scaleBitmap(Bitmap src, int maxSize) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxSize && h <= maxSize) return src;
        float scale = Math.min((float) maxSize / w, (float) maxSize / h);
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        return Bitmap.createScaledBitmap(src, nw, nh, false);
    }

    private int calculateSampleSize(int w, int h, int reqW, int reqH) {
        int sampleSize = 1;
        while (w / (sampleSize * 2) >= reqW && h / (sampleSize * 2) >= reqH) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    // ==================== 图标绘制（纯代码） ====================

    private Bitmap createBackIcon() {
        int size = 88; // 44dp @ 2x
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFF00D4E8);
        paint.setStrokeWidth(5);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        Path arrow = new Path();
        arrow.moveTo(56, 20);
        arrow.lineTo(32, 44);
        arrow.lineTo(56, 68);
        canvas.drawPath(arrow, paint);

        // horizontal line
        paint.setStrokeWidth(4);
        canvas.drawLine(32, 44, 68, 44, paint);

        return bmp;
    }

    private Bitmap createFolderIcon() {
        int size = 60;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 文件夹形状
        paint.setColor(0xFF2A4A6A);
        paint.setStyle(Paint.Style.FILL);
        RectF folderBody = new RectF(10, 18, 50, 45);
        canvas.drawRoundRect(folderBody, 4, 4, paint);

        // 文件夹标签页
        paint.setColor(0xFF3A5A7A);
        RectF tab = new RectF(10, 12, 25, 19);
        canvas.drawRoundRect(tab, 3, 3, paint);

        // 文件夹边框
        paint.setColor(0xFF00D4E8);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        canvas.drawRoundRect(folderBody, 4, 4, paint);
        canvas.drawRoundRect(tab, 3, 3, paint);

        return bmp;
    }
}
