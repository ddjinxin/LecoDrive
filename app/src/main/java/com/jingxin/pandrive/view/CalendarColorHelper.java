package com.jingxin.pandrive.view;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.jingxin.pandrive.R;
import com.jingxin.pandrive.data.DataHub;

/**
 * 日历背景色设置辅助类 — SettingsActivity 和 SettingsView 共用
 * 结构与 LaneColorHelper 一致，仅 2 条 ColorBar（夜间/白天）+ 1 条透明度 SeekBar
 */
public class CalendarColorHelper {

    public interface OnColorsChanged {
        void onColorsChanged();
    }

    private final DataHub dataHub;
    private final OnColorsChanged callback;

    private ColorBar barNightBg, barDayBg;
    private SeekBar seekAlpha;
    private TextView labelAlphaValue;
    private TextView tvNightBgLabel, tvDayBgLabel;

    public CalendarColorHelper(Context context, View root, OnColorsChanged cb) {
        this.dataHub = DataHub.getInstance(context);
        this.callback = cb;

        barNightBg = root.findViewById(R.id.bar_cal_night_bg);
        barDayBg = root.findViewById(R.id.bar_cal_day_bg);
        seekAlpha = root.findViewById(R.id.seekbar_cal_alpha);
        labelAlphaValue = root.findViewById(R.id.label_cal_alpha_value);

        tvNightBgLabel = root.findViewById(R.id.tv_cal_night_bg_label);
        tvDayBgLabel = root.findViewById(R.id.tv_cal_day_bg_label);

        // 初始化色条位置
        barNightBg.setColor(dataHub.getCalNightBgColor());
        barDayBg.setColor(dataHub.getCalDayBgColor());

        // 标签文字颜色 = 当前选中色
        tvNightBgLabel.setTextColor(dataHub.getCalNightBgColor());
        tvDayBgLabel.setTextColor(dataHub.getCalDayBgColor());

        // 色条回调
        barNightBg.setOnColorChangeListener(c -> {
            dataHub.setCalColors(c, null, -1);
            tvNightBgLabel.setTextColor(c);
            refresh();
        });
        barDayBg.setOnColorChangeListener(c -> {
            dataHub.setCalColors(null, c, -1);
            tvDayBgLabel.setTextColor(c);
            refresh();
        });

        // 透明度滑条
        seekAlpha.setProgress(dataHub.getCalBgAlpha());
        labelAlphaValue.setText(String.valueOf(dataHub.getCalBgAlpha()));
        seekAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                labelAlphaValue.setText(String.valueOf(progress));
                dataHub.setCalColors(null, null, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { refresh(); }
        });

        // 单项默认按钮
        root.findViewById(R.id.btn_cal_night_bg_reset).setOnClickListener(v -> {
            int def = 0xFF0A0F14;
            dataHub.setCalColors(def, null, -1);
            barNightBg.setColor(def);
            tvNightBgLabel.setTextColor(def);
            refresh();
        });
        root.findViewById(R.id.btn_cal_day_bg_reset).setOnClickListener(v -> {
            int def = 0xFFF0F4F8;
            dataHub.setCalColors(null, def, -1);
            barDayBg.setColor(def);
            tvDayBgLabel.setTextColor(def);
            refresh();
        });

        // 全部恢复默认
        root.findViewById(R.id.btn_cal_color_reset).setOnClickListener(v -> {
            dataHub.resetCalColors();
            barNightBg.setColor(dataHub.getCalNightBgColor());
            barDayBg.setColor(dataHub.getCalDayBgColor());
            tvNightBgLabel.setTextColor(dataHub.getCalNightBgColor());
            tvDayBgLabel.setTextColor(dataHub.getCalDayBgColor());
            seekAlpha.setProgress(dataHub.getCalBgAlpha());
            labelAlphaValue.setText(String.valueOf(dataHub.getCalBgAlpha()));
            refresh();
        });
    }

    private void refresh() {
        if (callback != null) callback.onColorsChanged();
    }
}
