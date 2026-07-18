package com.jingxin.pandrive;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.jingxin.pandrive.data.DataHub;
import com.jingxin.pandrive.data.WeatherHelper;
import com.jingxin.pandrive.view.GridBackgroundView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class SettingsActivity extends Activity {

    private static final int REQ_DAY_WALLPAPER = 10;
    private static final int REQ_NIGHT_WALLPAPER = 11;

    private EditText editBaseMileage;
    private EditText editIdleFuelRate;
    private LinearLayout fuelTableContainer;
    private EditText[] fuelEdits;
    private DataHub dataHub;
    private RadioGroup vehicleTypeGroup;
    private RadioButton radioFuel;
    private RadioButton radioElec;
    private TextView labelIdleRate;
    private TextView labelEnergyTable;
    private TextView labelDayWallpaperStatus;
    private TextView labelNightWallpaperStatus;
    private Button btnWeatherAnimation;
    private boolean weatherAnimationEnabled;

    // 油车：不含0km/h（8行），电车：含0km/h（9行）
    private static final String[] SPEED_LABELS_FUEL = {"20", "40", "60", "80", "105", "115", "130", "130+"};
    private static final String[] SPEED_LABELS_ELEC = {"0", "20", "40", "60", "80", "105", "115", "130", "130+"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        dataHub = DataHub.getInstance(this);

        editBaseMileage = findViewById(R.id.edit_base_mileage);
        editIdleFuelRate = findViewById(R.id.edit_idle_fuel_rate);
        fuelTableContainer = findViewById(R.id.fuel_table_container);
        Button btnSave = findViewById(R.id.btn_save);
        Button btnRefuel = findViewById(R.id.btn_refuel);
        vehicleTypeGroup = findViewById(R.id.vehicle_type_group);
        radioFuel = findViewById(R.id.radio_fuel);
        radioElec = findViewById(R.id.radio_elec);
        labelIdleRate = findViewById(R.id.label_idle_rate);
        labelEnergyTable = findViewById(R.id.label_energy_table);
        refuelSection = findViewById(R.id.refuel_section);
        editRefuelAmount = findViewById(R.id.edit_refuel_amount);
        editRefuelRange = findViewById(R.id.edit_refuel_range);
        labelRefuelAmount = findViewById(R.id.label_refuel_amount);
        labelRefuelRange = findViewById(R.id.label_refuel_range);

        // 壁纸设置
        Button btnDayWallpaper = findViewById(R.id.btn_day_wallpaper);
        Button btnNightWallpaper = findViewById(R.id.btn_night_wallpaper);
        Button btnDefaultWallpaper = findViewById(R.id.btn_default_wallpaper);
        labelDayWallpaperStatus = findViewById(R.id.label_day_wallpaper_status);
        labelNightWallpaperStatus = findViewById(R.id.label_night_wallpaper_status);

        btnDayWallpaper.setOnClickListener(v -> openWallpaperPicker("day"));
        btnNightWallpaper.setOnClickListener(v -> openWallpaperPicker("night"));
        btnDayWallpaper.setOnLongClickListener(v -> { clearWallpaper("day"); return true; });
        btnNightWallpaper.setOnLongClickListener(v -> { clearWallpaper("night"); return true; });
        btnDefaultWallpaper.setOnClickListener(v -> restoreDefaultWallpapers());

        // 天气动画按钮
        btnWeatherAnimation = findViewById(R.id.btn_weather_animation);
        weatherAnimationEnabled = getSharedPreferences("wallpaper", MODE_PRIVATE)
                .getBoolean("weather_animation_enabled", false);
        btnWeatherAnimation.setOnClickListener(v -> toggleWeatherAnimation());

        updateWeatherAnimationStatus();
        updateWallpaperStatus();

        // Load current values
        editBaseMileage.setText(String.valueOf(dataHub.getTotalDistanceKm()));
        editIdleFuelRate.setText(String.valueOf(dataHub.getIdleFuelRate()));

        // Set vehicle type radio
        if (dataHub.getVehicleType() == DataHub.VEHICLE_ELEC) {
            radioElec.setChecked(true);
        } else {
            radioFuel.setChecked(true);
        }
        updateLabelsForVehicleType();
        buildFuelTable();

        // Vehicle type change listener：切换车型时重建表格
        vehicleTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            updateLabelsForVehicleType();
            buildFuelTable();
        });

        // Refuel button: 弹出加油对话框
        btnRefuel.setOnClickListener(v -> showRefuelSection());

        // Save button
        btnSave.setOnClickListener(v -> saveAndFinish());

        // Cancel button
        Button btnCancel = findViewById(R.id.btn_cancel);
        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 壁纸文件已在 FilePickerActivity 选中时直接复制并即时刷新主界面
        // 车机多窗口模式下 onActivityResult 可能不回调，这里兜底：有pending就退出设置页
        if (FilePickerActivity.pendingWallpaperPath != null) {
            FilePickerActivity.pendingWallpaperPath = null;
            FilePickerActivity.pendingWallpaperType = null;
            updateWallpaperStatus();
            finish();
        }
    }

    private EditText editRefuelAmount;
    private EditText editRefuelRange;
    private View refuelSection;
    private TextView labelRefuelAmount;
    private TextView labelRefuelRange;

    // ==================== 天气动画 ====================

    private void toggleWeatherAnimation() {
        weatherAnimationEnabled = !weatherAnimationEnabled;
        getSharedPreferences("wallpaper", MODE_PRIVATE).edit()
                .putBoolean("weather_animation_enabled", weatherAnimationEnabled).apply();
        updateWeatherAnimationStatus();
        updateWallpaperStatus();
        Toast.makeText(this, weatherAnimationEnabled ? "天气动画已开启" : "天气动画已关闭",
                Toast.LENGTH_SHORT).show();
        // 立即刷新主界面壁纸并返回（车机多窗口模式下 onResume 不可靠）
        refreshMainWallpaper();
        finish();
    }

    private void updateWeatherAnimationStatus() {
        if (weatherAnimationEnabled) {
            btnWeatherAnimation.setText("天气 ✓");
            btnWeatherAnimation.setBackgroundColor(0xFF00B8D4);
        } else {
            btnWeatherAnimation.setText("天气");
            btnWeatherAnimation.setBackgroundColor(0xFF2A4A6A);
        }
    }

    // ==================== 壁纸选择 ====================

    private void openWallpaperPicker(String type) {
        Intent intent = new Intent(this, FilePickerActivity.class);
        intent.putExtra(FilePickerActivity.EXTRA_TYPE, type);
        int requestCode = "day".equals(type) ? REQ_DAY_WALLPAPER : REQ_NIGHT_WALLPAPER;
        startActivityForResult(intent, requestCode);
    }

    /**
     * 恢复默认壁纸：从APK内置assets/default_wallpaper/重新复制day.webp+night.webp到壁纸目录
     * 复制前先清除所有 day.* / night.* 文件，避免用户自定义壁纸抢占优先级
     */
    private void restoreDefaultWallpapers() {
        File dir = GridBackgroundView.ensureWallpaperDir();
        if (dir == null) {
            Toast.makeText(this, "无法创建壁纸目录", Toast.LENGTH_SHORT).show();
            return;
        }
        // 如果开启天气动画，先关闭，让用户能立即看到默认壁纸
        if (weatherAnimationEnabled) {
            weatherAnimationEnabled = false;
            getSharedPreferences("wallpaper", MODE_PRIVATE).edit()
                    .putBoolean("weather_animation_enabled", false).apply();
            updateWeatherAnimationStatus();
        }
        // 清除所有 day.* / night.* 旧壁纸
        String[] allExts = {".jpg", ".jpeg", ".png", ".webp", ".mp4", ".3gp", ".webm"};
        for (String prefix : new String[]{"day", "night"}) {
            for (String ext : allExts) {
                File f = new File(dir, prefix + ext);
                if (f.exists()) f.delete();
            }
        }
        // 从assets复制默认壁纸
        boolean ok = true;
        try {
            String[] defaults = {"day.webp", "night.webp"};
            for (String name : defaults) {
                File target = new File(dir, name);
                try (java.io.InputStream is = getAssets().open("default_wallpaper/" + name);
                     FileOutputStream os = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = is.read(buffer)) > 0) {
                        os.write(buffer, 0, n);
                    }
                }
            }
            GridBackgroundView.wallpaperChanged = true;
        } catch (Exception e) {
            ok = false;
        }
        if (ok) {
            Toast.makeText(this, "已恢复默认壁纸", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "恢复默认壁纸失败", Toast.LENGTH_SHORT).show();
        }
        updateWallpaperStatus();
        // 立即刷新主界面壁纸并返回
        refreshMainWallpaper();
        finish();
    }

    private void clearWallpaper(String type) {
        File dir = GridBackgroundView.getWallpaperDir();
        if (dir == null || !dir.exists()) return;
        boolean deleted = false;
        String[] exts = {".jpg", ".jpeg", ".png", ".webp", ".mp4", ".3gp", ".webm"};
        for (String ext : exts) {
            File f = new File(dir, type + ext);
            if (f.exists() && f.isFile()) { f.delete(); deleted = true; }
        }
        if (deleted) {
            Toast.makeText(this, ("day".equals(type) ? "白天" : "夜间") + "壁纸已清除", Toast.LENGTH_SHORT).show();
        }
        updateWallpaperStatus();
        // 立即刷新主界面壁纸
        refreshMainWallpaper();
    }

    /**
     * 立即刷新主界面壁纸（车机多窗口模式下 onResume 不可靠）
     * 通过静态实例引用直接操作 MainActivity 的 GridBackgroundView
     */
    private void refreshMainWallpaper() {
        GridBackgroundView gv = GridBackgroundView.getInstance();
        if (gv == null) {
            return;
        }
        boolean weatherAnim = getSharedPreferences("wallpaper", MODE_PRIVATE)
                .getBoolean("weather_animation_enabled", false);
        gv.setWeatherAnimationMode(weatherAnim);
        if (weatherAnim) {
            WeatherHelper wh = WeatherHelper.getInstance();
            String videoFile = wh.getVideoFileName();
            if (videoFile != null) {
                String videoPath = GridBackgroundView.getVideoPath(videoFile);
                gv.setWeatherVideo(videoPath);
            }
        } else {
            gv.reloadWallpaper();
        }
        gv.resumeWallpaper();
    }

    private void updateWallpaperStatus() {
        if (weatherAnimationEnabled) {
            labelDayWallpaperStatus.setText("天气动画模式");
            labelNightWallpaperStatus.setText("天气动画模式");
            return;
        }
        File dir = GridBackgroundView.getWallpaperDir();
        labelDayWallpaperStatus.setText(getWallpaperStatusText(dir, "day"));
        labelNightWallpaperStatus.setText(getWallpaperStatusText(dir, "night"));
    }

    private String getWallpaperStatusText(File dir, String prefix) {
        if (dir == null || !dir.exists()) return "未设置";
        String[] videoExts = {".mp4", ".3gp", ".webm"};
        String[] imageExts = {".jpg", ".jpeg", ".png", ".webp"};
        for (String ext : videoExts) {
            File f = new File(dir, prefix + ext);
            if (f.exists() && f.isFile() && f.length() > 0) return "视频 " + formatFileSize(f.length());
        }
        for (String ext : imageExts) {
            File f = new File(dir, prefix + ext);
            if (f.exists() && f.isFile() && f.length() > 0) return "图片 " + formatFileSize(f.length());
        }
        return "未设置";
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 壁纸文件已在 FilePickerActivity 选中时直接复制并即时刷新主界面
        // 收到结果后直接退出设置页返回主界面
        if ((requestCode == REQ_DAY_WALLPAPER || requestCode == REQ_NIGHT_WALLPAPER)
                && resultCode == RESULT_OK) {
            updateWallpaperStatus();
            finish();
        }
    }

    private void copyWallpaperFromPath(String srcPath, String type) {
        File srcFile = new File(srcPath);
        String extension = getExtensionFromName(srcFile.getName());
        File dir = GridBackgroundView.ensureWallpaperDir();
        if (dir == null) {
            Toast.makeText(this, "无法创建壁纸目录", Toast.LENGTH_SHORT).show();
            return;
        }
        // 删除同前缀旧壁纸
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
            String label = "day".equals(type) ? "白天" : "夜间";
            Toast.makeText(this, label + "壁纸设置成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制壁纸文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            if (target.exists()) target.delete();
        }
        updateWallpaperStatus();
    }

    private String getExtensionFromName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            String ext = fileName.substring(dot).toLowerCase();
            String[] supported = {".jpg", ".jpeg", ".png", ".webp", ".mp4", ".3gp", ".webm"};
            for (String s : supported) { if (s.equals(ext)) return s; }
        }
        return ".jpg";
    }

    // ==================== 原有逻辑 ====================

    private void showRefuelSection() {
        boolean isElec = radioElec.isChecked();
        editRefuelAmount.setText("");
        editRefuelAmount.setHint(isElec ? "充电量(kWh)" : "加油量(L)");
        editRefuelRange.setText("80");
        editRefuelRange.setHint(isElec ? "当前车表续航(km)" : "当前车表续航(km)");
        labelRefuelAmount.setText(isElec ? "充电量(kWh)" : "加油量(L)");
        labelRefuelRange.setText(isElec ? "当前车表续航(km)" : "当前车表续航(km)");
        refuelSection.setVisibility(View.VISIBLE);
    }

    private void updateLabelsForVehicleType() {
        boolean isElec = radioElec.isChecked();
        labelIdleRate.setText(isElec ? "怠速电耗(kW)" : "怠速油耗(L/h)");
        labelEnergyTable.setText(isElec ? "电耗设置 (kWh/100km)" : "油耗设置 (L/100km)");
    }

    private void buildFuelTable() {
        fuelTableContainer.removeAllViews();
        boolean isElec = radioElec.isChecked();
        String[] labels = isElec ? SPEED_LABELS_ELEC : SPEED_LABELS_FUEL;
        float[] values = dataHub.getFuelValues();
        int count = labels.length;
        fuelEdits = new EditText[count];

        for (int i = 0; i < count; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 8, 0, 8);

            // Left item
            addFuelItem(row, i, labels, values);
            fuelEdits[i] = (EditText) row.getChildAt(1);

            // Right item (if exists)
            if (i + 1 < count) {
                View spacer = new View(this);
                row.addView(spacer, new LinearLayout.LayoutParams(16, 1));

                addFuelItem(row, i + 1, labels, values);
                fuelEdits[i + 1] = (EditText) row.getChildAt(4);
            }

            fuelTableContainer.addView(row);
        }
    }

    private void saveAndFinish() {
        // Save vehicle type
        int vType = radioElec.isChecked() ? DataHub.VEHICLE_ELEC : DataHub.VEHICLE_FUEL;
        dataHub.setVehicleType(vType);

        // Save base mileage
        try {
            float baseKm = Float.parseFloat(editBaseMileage.getText().toString().trim());
            dataHub.setBaseMileage(baseKm);
        } catch (NumberFormatException ignored) {}

        // Save idle energy rate
        try {
            float rate = Float.parseFloat(editIdleFuelRate.getText().toString().trim());
            if (rate > 0) dataHub.setIdleFuelRate(rate);
        } catch (NumberFormatException ignored) {}

        // Save refuel data (if refuel section is visible = user clicked refuel)
        if (refuelSection.getVisibility() == View.VISIBLE) {
            try {
                float range = Float.parseFloat(editRefuelRange.getText().toString().trim());
                if (range > 0) {
                    float amount = 0f;
                    try {
                        amount = Float.parseFloat(editRefuelAmount.getText().toString().trim());
                    } catch (NumberFormatException ignored) {}

                    if (amount > 0) {
                        // 加油+续航：先设续航基准值，再加油
                        dataHub.setRefuelRemainingRange(range);
                        dataHub.setRefuelAmount(amount);
                    } else {
                        // 仅修正续航（不加油）
                        dataHub.calibrateRange(range);
                    }
                }
            } catch (NumberFormatException ignored) {}
        }

        // Save energy values (map back to full array)
        boolean isElec = vType == DataHub.VEHICLE_ELEC;
        float[] oldValues = dataHub.getFuelValues();
        float[] newValues = new float[oldValues.length];

        if (isElec) {
            for (int i = 0; i < fuelEdits.length && i < newValues.length; i++) {
                try {
                    newValues[i] = Float.parseFloat(fuelEdits[i].getText().toString().trim());
                } catch (NumberFormatException e) {
                    newValues[i] = oldValues[i];
                }
            }
        } else {
            newValues[0] = oldValues[0];
            for (int i = 0; i < fuelEdits.length && i + 1 < newValues.length; i++) {
                try {
                    newValues[i + 1] = Float.parseFloat(fuelEdits[i].getText().toString().trim());
                } catch (NumberFormatException e) {
                    newValues[i + 1] = oldValues[i + 1];
                }
            }
        }
        dataHub.setFuelValues(newValues);

        setResult(RESULT_OK);
        finish();
    }

    private void addFuelItem(LinearLayout row, int index, String[] labels, float[] values) {
        TextView speedLabel = new TextView(this);
        speedLabel.setText(labels[index] + " km/h");
        speedLabel.setTextColor(0xFFCCCCCC);
        speedLabel.setTextSize(14);
        row.addView(speedLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        boolean isElec = radioElec.isChecked();
        int valueIndex = isElec ? index : index + 1;
        EditText edit = new EditText(this);
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        edit.setText(String.valueOf(values[valueIndex]));
        edit.setTextColor(0xFFFFFFFF);
        edit.setTextSize(16);
        edit.setBackgroundColor(0xFF2A2A4A);
        edit.setPadding(12, 8, 12, 8);
        row.addView(edit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }
}
