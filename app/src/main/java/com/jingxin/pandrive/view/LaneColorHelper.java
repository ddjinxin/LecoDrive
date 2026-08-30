package com.jingxin.pandrive.view;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.jingxin.pandrive.R;
import com.jingxin.pandrive.data.DataHub;

/**
 * 车道背景色设置辅助类 — SettingsActivity 和 SettingsView 共用
 * 使用 HSV 色相条（ColorBar）选色，与静心音乐歌词高亮颜色选择器一致
 */
public class LaneColorHelper {

    public interface OnColorsChanged {
        void onColorsChanged();
    }

    private final Context context;
    private final DataHub dataHub;
    private final OnColorsChanged callback;

    private ColorBar barNightTop, barNightBottom, barDayTop, barDayBottom;
    private SeekBar seekAlpha;
    private TextView labelAlphaValue;

    // 标签文字颜色实时跟随选中色
    private TextView tvNightTopLabel, tvNightBottomLabel, tvDayTopLabel, tvDayBottomLabel;

    /**
     * @param context  Activity 或 View 的 Context
     * @param root     activity_settings.xml 的根 View（findViewById 作用的 View）
     * @param cb       颜色变化回调（用于触发刷新主界面）
     */
    public LaneColorHelper(Context context, View root, OnColorsChanged cb) {
        this.context = context;
        this.dataHub = DataHub.getInstance(context);
        this.callback = cb;

        barNightTop = root.findViewById(R.id.bar_lane_night_top);
        barNightBottom = root.findViewById(R.id.bar_lane_night_bottom);
        barDayTop = root.findViewById(R.id.bar_lane_day_top);
        barDayBottom = root.findViewById(R.id.bar_lane_day_bottom);
        seekAlpha = root.findViewById(R.id.seekbar_lane_alpha);
        labelAlphaValue = root.findViewById(R.id.label_lane_alpha_value);

        tvNightTopLabel = root.findViewById(R.id.tv_lane_night_top_label);
        tvNightBottomLabel = root.findViewById(R.id.tv_lane_night_bottom_label);
        tvDayTopLabel = root.findViewById(R.id.tv_lane_day_top_label);
        tvDayBottomLabel = root.findViewById(R.id.tv_lane_day_bottom_label);

        // 初始化色条位置
        barNightTop.setColor(dataHub.getLaneNightTopColor());
        barNightBottom.setColor(dataHub.getLaneNightBottomColor());
        barDayTop.setColor(dataHub.getLaneDayTopColor());
        barDayBottom.setColor(dataHub.getLaneDayBottomColor());

        // 标签文字颜色 = 当前选中色
        tvNightTopLabel.setTextColor(dataHub.getLaneNightTopColor());
        tvNightBottomLabel.setTextColor(dataHub.getLaneNightBottomColor());
        tvDayTopLabel.setTextColor(dataHub.getLaneDayTopColor());
        tvDayBottomLabel.setTextColor(dataHub.getLaneDayBottomColor());

        // 色条回调：滑动时实时写入 + 更新标签颜色 + 刷新主界面
        barNightTop.setOnColorChangeListener(c -> {
            dataHub.setLaneColors(c, null, null, null, -1);
            tvNightTopLabel.setTextColor(c);
            refreshLane();
        });
        barNightBottom.setOnColorChangeListener(c -> {
            dataHub.setLaneColors(null, c, null, null, -1);
            tvNightBottomLabel.setTextColor(c);
            refreshLane();
        });
        barDayTop.setOnColorChangeListener(c -> {
            dataHub.setLaneColors(null, null, c, null, -1);
            tvDayTopLabel.setTextColor(c);
            refreshLane();
        });
        barDayBottom.setOnColorChangeListener(c -> {
            dataHub.setLaneColors(null, null, null, c, -1);
            tvDayBottomLabel.setTextColor(c);
            refreshLane();
        });

        // 透明度滑条
        seekAlpha.setProgress(dataHub.getLaneAlpha());
        labelAlphaValue.setText(String.valueOf(dataHub.getLaneAlpha()));
        seekAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                labelAlphaValue.setText(String.valueOf(progress));
                dataHub.setLaneColors(null, null, null, null, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                refreshLane();
            }
        });

        // 单项默认按钮
        root.findViewById(R.id.btn_lane_night_top_reset).setOnClickListener(v -> {
            int def = 0xFF050810;
            dataHub.setLaneColors(def, null, null, null, -1);
            barNightTop.setColor(def);
            tvNightTopLabel.setTextColor(def);
            refreshLane();
        });
        root.findViewById(R.id.btn_lane_night_bottom_reset).setOnClickListener(v -> {
            int def = 0xFF0A0F18;
            dataHub.setLaneColors(null, def, null, null, -1);
            barNightBottom.setColor(def);
            tvNightBottomLabel.setTextColor(def);
            refreshLane();
        });
        root.findViewById(R.id.btn_lane_day_top_reset).setOnClickListener(v -> {
            int def = 0xFF2A2D30;
            dataHub.setLaneColors(null, null, def, null, -1);
            barDayTop.setColor(def);
            tvDayTopLabel.setTextColor(def);
            refreshLane();
        });
        root.findViewById(R.id.btn_lane_day_bottom_reset).setOnClickListener(v -> {
            int def = 0xFF3A3D42;
            dataHub.setLaneColors(null, null, null, def, -1);
            barDayBottom.setColor(def);
            tvDayBottomLabel.setTextColor(def);
            refreshLane();
        });

        // 全部恢复默认
        root.findViewById(R.id.btn_lane_color_reset).setOnClickListener(v -> {
            dataHub.resetLaneColors();
            barNightTop.setColor(dataHub.getLaneNightTopColor());
            barNightBottom.setColor(dataHub.getLaneNightBottomColor());
            barDayTop.setColor(dataHub.getLaneDayTopColor());
            barDayBottom.setColor(dataHub.getLaneDayBottomColor());
            tvNightTopLabel.setTextColor(dataHub.getLaneNightTopColor());
            tvNightBottomLabel.setTextColor(dataHub.getLaneNightBottomColor());
            tvDayTopLabel.setTextColor(dataHub.getLaneDayTopColor());
            tvDayBottomLabel.setTextColor(dataHub.getLaneDayBottomColor());
            seekAlpha.setProgress(dataHub.getLaneAlpha());
            labelAlphaValue.setText(String.valueOf(dataHub.getLaneAlpha()));
            refreshLane();
        });
    }

    /** 刷新主界面 LaneView */
    private void refreshLane() {
        if (callback != null) callback.onColorsChanged();
    }
}
