package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import com.jingxin.pandrive.FilePickerActivity;
import com.jingxin.pandrive.R;
import com.jingxin.pandrive.data.DataHub;
import com.jingxin.pandrive.data.WeatherHelper;
import com.jingxin.pandrive.theme.ThemeController;
import com.jingxin.pandrive.update.UpdateChecker;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 设置页 View（悬浮态下叠加在 GridBackgroundView 上）
 *
 * 逻辑与 SettingsActivity 完全一致，仅将 Activity 依赖改为 Context。
 * 非悬浮态仍使用 SettingsActivity。
 */
public class SettingsView extends ScrollView {

    private static final String[] SPEED_LABELS_FUEL = {"20", "40", "60", "80", "105", "115", "130", "130+"};
    private static final String[] SPEED_LABELS_ELEC = {"0", "20", "40", "60", "80", "105", "115", "130", "130+"};

    private EditText editBaseMileage;
    private EditText editIdleFuelRate;
    private EditText editTankCapacity;
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
    private EditText[] editLayoutLand = new EditText[6];
    private EditText[] editLayoutPort = new EditText[6];
    private float initialTotalKm;

    /** 车道背景色辅助器 */
    private LaneColorHelper laneColorHelper;

    private EditText editRefuelAmount;
    private EditText editRefuelRange;
    private View refuelSection;
    private TextView labelRefuelAmount;
    private TextView labelRefuelRange;
    private RadioGroup recentFuelWindowGroup;
    private RadioGroup recentFuelModeGroup;
    private TextView labelRecentFuelWindow;

    /** 关闭回调（移除自身 View） */
    public Runnable onClose;

    public SettingsView(Context context) {
        super(context);
        init();
    }

    public SettingsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.activity_settings, this, true);
        dataHub = DataHub.getInstance(getContext());

        // 返回按钮
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (onClose != null) onClose.run();
        });

        // 日夜模式开关
        Switch switchDayNight = findViewById(R.id.switch_day_night);
        switchDayNight.setChecked(ThemeController.getInstance(getContext()).isNightMode());
        switchDayNight.setOnCheckedChangeListener((button, isChecked) -> {
            getContext().getSharedPreferences("theme", Context.MODE_PRIVATE).edit()
                    .putBoolean("isNight", isChecked)
                    .putBoolean("amapTriggered", false)
                    .apply();
            ThemeController.getInstance(getContext()).forceSetNightMode(isChecked);
        });

        editBaseMileage = findViewById(R.id.edit_base_mileage);
        editIdleFuelRate = findViewById(R.id.edit_idle_fuel_rate);
        editTankCapacity = findViewById(R.id.edit_tank_capacity);
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
        recentFuelWindowGroup = findViewById(R.id.recent_fuel_window_group);

        int curWin = dataHub.getRecentFuelWindowSec();
        int rid = R.id.radio_recent_120;
        if (curWin == 60) rid = R.id.radio_recent_60;
        else if (curWin == 180) rid = R.id.radio_recent_180;
        else if (curWin == 240) rid = R.id.radio_recent_240;
        else if (curWin == 300) rid = R.id.radio_recent_300;
        recentFuelWindowGroup.check(rid);

        labelRecentFuelWindow = findViewById(R.id.label_recent_fuel_window);
        recentFuelModeGroup = findViewById(R.id.recent_fuel_mode_group);
        recentFuelModeGroup.check(dataHub.getRecentFuelMode() == DataHub.RECENT_MODE_DISTANCE
                ? R.id.radio_mode_distance : R.id.radio_mode_time);
        updateWindowGroupEnabled();
        recentFuelModeGroup.setOnCheckedChangeListener((group, checkedId) -> updateWindowGroupEnabled());

        int[] landIds = {R.id.edit_layout_land_0, R.id.edit_layout_land_1, R.id.edit_layout_land_2,
                R.id.edit_layout_land_3, R.id.edit_layout_land_4, R.id.edit_layout_land_5};
        int[] portIds = {R.id.edit_layout_port_0, R.id.edit_layout_port_1, R.id.edit_layout_port_2,
                R.id.edit_layout_port_3, R.id.edit_layout_port_4, R.id.edit_layout_port_5};
        float[] curLand = dataHub.getLayoutWeights(false);
        float[] curPort = dataHub.getLayoutWeights(true);
        for (int i = 0; i < 6; i++) {
            editLayoutLand[i] = findViewById(landIds[i]);
            editLayoutPort[i] = findViewById(portIds[i]);
            editLayoutLand[i].setText(String.valueOf((int) curLand[i]));
            editLayoutPort[i].setText(String.valueOf((int) curPort[i]));
        }
        findViewById(R.id.btn_layout_land_default).setOnClickListener(v -> fillLayoutDefaults(false));
        findViewById(R.id.btn_layout_port_default).setOnClickListener(v -> fillLayoutDefaults(true));

        // 车道背景色
        laneColorHelper = new LaneColorHelper(getContext(), this, () -> {
            // 悬浮态：直接刷新 GridBackgroundView 上的 LaneView
            GridBackgroundView gv = GridBackgroundView.getInstance();
            if (gv != null) {
                View lane = gv.findViewById(R.id.lane_view);
                if (lane != null) lane.invalidate();
            }
        });

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

        btnWeatherAnimation = findViewById(R.id.btn_weather_animation);
        weatherAnimationEnabled = getContext().getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
                .getBoolean("weather_animation_enabled", false);
        btnWeatherAnimation.setOnClickListener(v -> toggleWeatherAnimation());

        updateWeatherAnimationStatus();
        updateWallpaperStatus();

        initialTotalKm = dataHub.getTotalDistanceKm();
        editBaseMileage.setText(String.format("%.1f", initialTotalKm));
        editIdleFuelRate.setText(String.valueOf(dataHub.getIdleFuelRate()));

        if (dataHub.getVehicleType() == DataHub.VEHICLE_ELEC) {
            radioElec.setChecked(true);
        } else {
            radioFuel.setChecked(true);
        }
        updateLabelsForVehicleType();
        buildFuelTable();

        vehicleTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            updateLabelsForVehicleType();
            buildFuelTable();
        });

        btnRefuel.setOnClickListener(v -> showRefuelSection());
        btnSave.setOnClickListener(v -> saveAndClose());

        Button btnCancel = findViewById(R.id.btn_cancel);
        btnCancel.setOnClickListener(v -> close());

        Button btnCheckUpdate = findViewById(R.id.btn_check_update);
        btnCheckUpdate.setOnClickListener(v -> {
            // 悬浮态下无 Activity，走后台静默检查：发现新版本下载后推送通知，点击通知弹安装窗
            FloatToast.show(getContext(), "正在后台检查更新，有新版本将推送通知");
            UpdateChecker.getInstance(getContext()).checkSilentlyWithFeedback(getContext());
        });

        Button btnHelp = findViewById(R.id.btn_help);
        btnHelp.setOnClickListener(v -> {
            // 悬浮态下用 HelpView 叠加在 GridBackgroundView 上
            final GridBackgroundView gv = GridBackgroundView.getInstance();
            if (gv == null) return;
            final HelpView helpView = new HelpView(getContext());
            helpView.onClose = () -> gv.removeOverlay(helpView);
            gv.addOverlay(helpView);
        });
    }

    private void close() {
        if (onClose != null) {
            onClose.run();
        }
    }

    // ==================== 天气动画 ====================

    private void toggleWeatherAnimation() {
        weatherAnimationEnabled = !weatherAnimationEnabled;
        getContext().getSharedPreferences("wallpaper", Context.MODE_PRIVATE).edit()
                .putBoolean("weather_animation_enabled", weatherAnimationEnabled).apply();
        updateWeatherAnimationStatus();
        updateWallpaperStatus();
        FloatToast.show(getContext(), weatherAnimationEnabled ? "天气动画已开启" : "天气动画已关闭");
        refreshMainWallpaper();
        close();
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
        // 悬浮态下用 FilePickerView 叠加在 GridBackgroundView 上
        final GridBackgroundView gv = GridBackgroundView.getInstance();
        if (gv == null) return;

        final FilePickerView picker = new FilePickerView(getContext());
        picker.setPickerType(type);
        picker.onPicked = () -> {
            gv.removeOverlay(picker);
            // 选完后更新设置页壁纸状态
            if (FilePickerActivity.pendingWallpaperPath != null) {
                FilePickerActivity.pendingWallpaperPath = null;
                FilePickerActivity.pendingWallpaperType = null;
            }
            updateWallpaperStatus();
        };
        picker.onCancel = () -> gv.removeOverlay(picker);

        gv.addOverlay(picker);
    }

    private void restoreDefaultWallpapers() {
        File dir = GridBackgroundView.ensureWallpaperDir();
        if (dir == null) {
            FloatToast.show(getContext(), "无法创建壁纸目录");
            return;
        }
        if (weatherAnimationEnabled) {
            weatherAnimationEnabled = false;
            getContext().getSharedPreferences("wallpaper", Context.MODE_PRIVATE).edit()
                    .putBoolean("weather_animation_enabled", false).apply();
            updateWeatherAnimationStatus();
        }
        String[] allExts = {".jpg", ".jpeg", ".png", ".webp", ".mp4", ".3gp", ".webm"};
        for (String prefix : new String[]{"day", "night"}) {
            for (String ext : allExts) {
                File f = new File(dir, prefix + ext);
                if (f.exists()) f.delete();
            }
        }
        boolean ok = true;
        try {
            String[] defaults = {"day.webp", "night.webp"};
            for (String name : defaults) {
                File target = new File(dir, name);
                try (java.io.InputStream is = getContext().getAssets().open("default_wallpaper/" + name);
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
            FloatToast.show(getContext(), "已恢复默认壁纸");
        } else {
            FloatToast.show(getContext(), "恢复默认壁纸失败");
        }
        updateWallpaperStatus();
        refreshMainWallpaper();
        close();
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
            FloatToast.show(getContext(), ("day".equals(type) ? "白天" : "夜间") + "壁纸已清除");
        }
        updateWallpaperStatus();
        refreshMainWallpaper();
    }

    private void refreshMainWallpaper() {
        GridBackgroundView gv = GridBackgroundView.getInstance();
        if (gv == null) return;
        boolean weatherAnim = getContext().getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
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

    // ==================== 原有逻辑 ====================

    private void showRefuelSection() {
        boolean isElec = radioElec.isChecked();
        if (dataHub.getTankCapacity() > 0) {
            editTankCapacity.setText(String.valueOf((int) dataHub.getTankCapacity()));
        } else {
            editTankCapacity.setText("");
        }
        editTankCapacity.setHint(isElec ? "如75" : "如62");
        editRefuelAmount.setText("");
        editRefuelAmount.setHint(isElec ? "充电量(kWh)" : "加油量(L)");
        float currentRange = dataHub.getRemainingRange();
        editRefuelRange.setText(String.valueOf((int) currentRange));
        editRefuelRange.setHint(isElec ? "剩余续航(km)" : "剩余续航(km)");
        labelRefuelAmount.setText(isElec ? "充电量(kWh)" : "加油量(L)");
        labelRefuelRange.setText(isElec ? "剩余续航(km)" : "剩余续航(km)");
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
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 8, 0, 8);

            addFuelItem(row, i, labels, values);
            fuelEdits[i] = (EditText) row.getChildAt(1);

            if (i + 1 < count) {
                View spacer = new View(getContext());
                row.addView(spacer, new LinearLayout.LayoutParams(16, 1));
                addFuelItem(row, i + 1, labels, values);
                fuelEdits[i + 1] = (EditText) row.getChildAt(4);
            }

            fuelTableContainer.addView(row);
        }
    }

    private void updateWindowGroupEnabled() {
        boolean isTimeMode = recentFuelModeGroup.getCheckedRadioButtonId() == R.id.radio_mode_time;
        recentFuelWindowGroup.setEnabled(isTimeMode);
        labelRecentFuelWindow.setEnabled(isTimeMode);
        labelRecentFuelWindow.setAlpha(isTimeMode ? 1f : 0.4f);
        recentFuelWindowGroup.setAlpha(isTimeMode ? 1f : 0.4f);
        for (int i = 0; i < recentFuelWindowGroup.getChildCount(); i++) {
            recentFuelWindowGroup.getChildAt(i).setEnabled(isTimeMode);
        }
    }

    private void saveAndClose() {
        int vType = radioElec.isChecked() ? DataHub.VEHICLE_ELEC : DataHub.VEHICLE_FUEL;
        dataHub.setVehicleType(vType);

        try {
            float totalKm = Float.parseFloat(editBaseMileage.getText().toString().trim());
            if (Math.abs(totalKm - initialTotalKm) > 0.01f) {
                dataHub.setTotalMileage(totalKm);
            }
        } catch (NumberFormatException ignored) {}

        try {
            float rate = Float.parseFloat(editIdleFuelRate.getText().toString().trim());
            if (rate > 0) dataHub.setIdleFuelRate(rate);
        } catch (NumberFormatException ignored) {}

        int checkedWinId = recentFuelWindowGroup.getCheckedRadioButtonId();
        int winSec = 120;
        if (checkedWinId == R.id.radio_recent_60) winSec = 60;
        else if (checkedWinId == R.id.radio_recent_180) winSec = 180;
        else if (checkedWinId == R.id.radio_recent_240) winSec = 240;
        else if (checkedWinId == R.id.radio_recent_300) winSec = 300;
        if (winSec != dataHub.getRecentFuelWindowSec()) {
            dataHub.setRecentFuelWindowSec(winSec);
        }

        int modeCheckedId = recentFuelModeGroup.getCheckedRadioButtonId();
        int fuelMode = (modeCheckedId == R.id.radio_mode_distance)
                ? DataHub.RECENT_MODE_DISTANCE : DataHub.RECENT_MODE_TIME;
        if (fuelMode != dataHub.getRecentFuelMode()) {
            dataHub.setRecentFuelMode(fuelMode);
        }

        if (refuelSection.getVisibility() == View.VISIBLE) {
            try {
                float capacity = Float.parseFloat(editTankCapacity.getText().toString().trim());
                if (capacity > 0) dataHub.setTankCapacity(capacity);
            } catch (NumberFormatException ignored) {}

            try {
                float range = Float.parseFloat(editRefuelRange.getText().toString().trim());
                if (range > 0) {
                    float amount = 0f;
                    try {
                        amount = Float.parseFloat(editRefuelAmount.getText().toString().trim());
                    } catch (NumberFormatException ignored) {}

                    if (amount > 0) {
                        dataHub.setRefuelAmount(range, amount);
                    } else {
                        dataHub.calibrateRange(range);
                    }
                }
            } catch (NumberFormatException ignored) {}
        }

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

        if (!saveLayoutWeights()) return;

        FloatToast.show(getContext(), "设置已保存");
        close();
    }

    private float[] parseLayoutWeights(EditText[] edits) {
        float[] w = new float[6];
        float sum = 0;
        for (int i = 0; i < 6; i++) {
            try {
                w[i] = Float.parseFloat(edits[i].getText().toString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (w[i] < 0) return null;
            sum += w[i];
        }
        if (Math.abs(sum - 100f) > 0.01f) return null;
        return w;
    }

    private boolean saveLayoutWeights() {
        float[] land = parseLayoutWeights(editLayoutLand);
        if (land == null) {
            FloatToast.show(getContext(), "横屏布局比例合计需为100");
            return false;
        }
        float[] port = parseLayoutWeights(editLayoutPort);
        if (port == null) {
            FloatToast.show(getContext(), "竖屏布局比例合计需为100");
            return false;
        }
        dataHub.setLayoutWeights(false, land);
        dataHub.setLayoutWeights(true, port);
        return true;
    }

    private void fillLayoutDefaults(boolean isPortrait) {
        float[] def = dataHub.getDefaultLayoutWeights(isPortrait);
        EditText[] edits = isPortrait ? editLayoutPort : editLayoutLand;
        for (int i = 0; i < 6; i++) {
            edits[i].setText(String.valueOf((int) def[i]));
        }
    }

    private void addFuelItem(LinearLayout row, int index, String[] labels, float[] values) {
        TextView speedLabel = new TextView(getContext());
        speedLabel.setText(labels[index] + " km/h");
        speedLabel.setTextColor(0xFFCCCCCC);
        speedLabel.setTextSize(14);
        row.addView(speedLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        boolean isElec = radioElec.isChecked();
        int valueIndex = isElec ? index : index + 1;
        EditText edit = new EditText(getContext());
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        edit.setText(String.valueOf(values[valueIndex]));
        edit.setTextColor(0xFFFFFFFF);
        edit.setTextSize(16);
        edit.setBackgroundColor(0xFF2A2A4A);
        edit.setPadding(12, 8, 12, 8);
        row.addView(edit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }
}
