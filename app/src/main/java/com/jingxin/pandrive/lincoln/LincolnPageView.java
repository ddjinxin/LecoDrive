package com.jingxin.pandrive.lincoln;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jingxin.pandrive.data.DataHub;

/**
 * 林肯车况页：布局与视觉 1:1 参照 LinCarInfo 首页——
 * 顶部横幅（档位/续航/总里程/燃油/机油）+ 流光线 + 车图区（胎压/门窗/后备箱圆形标签
 * + 红点虚线标注 + 速度 + 转向灯）+ 底部空调卡（主驾温度/PM2.5/副驾温度）。
 * <p>
 * 与林肯原版的差异（乐酷机制适配）：
 * - 日夜切换走 setNightMode(boolean)（乐酷代码切换），不用 values-night 资源目录；
 * - 背景：透明，共享乐酷壁纸（GridBackgroundView 根层绘制）；
 * - 数据源：DataHub.VehicleStatus（林肯车机信息广播快照）；
 * - 尺寸驱动：onSizeChanged 触发 relayoutAll 全量重算（悬浮态尺寸由乐酷悬浮区域
 *   控制，Configuration 不可信，以页面实际宽高为准，对应原版 onConfigurationChanged）。
 * <p>
 * 竖向比例与林肯原版一致：横幅 10 / 车图区 80 / 空调卡 10。
 */
public class LincolnPageView extends FrameLayout {

    // ===== 双日夜色板（乐酷机制：代码常量 + setNightMode 切换） =====
    // 索引：0=白天 1=夜间（与林肯 values-notnight/values 资源色一致）
    private static final int[] C_BG_PAGE     = {0xFFF5F7FA, 0xFF060A14};   // 未用（透明共享壁纸）
    private static final int[] C_BG_BANNER   = {0x66E8ECF1, 0x660B1424};   // 横幅底（半透，透出壁纸）
    private static final int[] C_DIVIDER     = {0xFFD5DCE4, 0xFF1E3048};
    private static final int[] C_TEXT_BRIGHT = {0xFF1A2B3C, 0xFFEAF6FF};
    private static final int[] C_TEXT_LABEL  = {0xFF6B7A8D, 0xFF5C7A99};
    private static final int[] C_STATE_OK    = {0xFF0077C2, 0xFF00FF9D};
    private static final int[] C_STATE_ALERT = {0xFFD32F2F, 0xFFFF5A5A};
    private static final int[] C_ZONE_BG     = {0x99FFFFFF, 0x0D00E5FF};   // 空调区底
    private static final int[] C_ZONE_STROKE = {0x66C8D2DC, 0x2400E5FF};
    private static final int[] C_CARD_BG     = {0x99FFFFFF, 0x0D00E5FF};   // 空调卡底

    private int modeIdx = 1;   // 默认夜间

    // ===== 子 View =====
    private LinearLayout banner;
    private LinearLayout acCard;   // 空调卡引用（字号横竖屏适配用）
    private TextView tvGear, tvRange, tvOdo, tvFuel, tvOil;
    private FrameLayout carZone;
    private ImageView ivCar;
    private TextView tvSpeed;
    private TurnSignalView turnLeft, turnRight;
    private HvacToastView hvacToast;
    private TagLineOverlay tagLines;
    private DoorCircleView tagLFDw, tagRFDw, tagLRDw, tagRRDw, tagTrunk;
    private TireCircleView tagLFTire, tagRFTire, tagLRTire, tagRRTire;
    private TextView tvTempDriver, tvPm, tvTempPassenger;

    /** 数据新鲜度提示（无广播时车图区中央显示） */
    private TextView tvNoData;

    // ===== HVAC toast 上次值（变化才提示，照抄林肯 showHvacToastIfNeeded） =====
    private Integer lastCcm, lastFanLevel, lastAc, lastRecycle;

    public LincolnPageView(Context context) {
        super(context);
        init();
    }

    public LincolnPageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        buildContent();
    }

    private int dip(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** 当前日夜索引 */
    private int c(int[] dual) { return dual[modeIdx]; }

    // ==================== 布局构建（代码构建，1:1 参照林肯 activity_main.xml） ====================

    private void buildContent() {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0x00000000);   // 透明，共享乐酷壁纸
        addView(root, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ---- 顶部横幅 10% ----
        banner = new LinearLayout(getContext());
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(android.view.Gravity.CENTER);
        banner.setBackgroundColor(c(C_BG_BANNER));
        root.addView(banner, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 10f));

        tvGear = addBannerItem(banner, "P", "档位", c(C_STATE_ALERT));
        addDivider(banner);
        tvRange = addBannerItem(banner, "503", "续航里程", c(C_TEXT_BRIGHT));
        addDivider(banner);
        tvOdo = addBannerItem(banner, "74,595", "总里程", c(C_STATE_OK));
        addDivider(banner);
        tvFuel = addBannerItem(banner, "81%", "燃油", c(C_TEXT_BRIGHT));
        addDivider(banner);
        tvOil = addBannerItem(banner, "75%", "机油寿命", c(C_TEXT_BRIGHT));

        // ---- 流光线 ----
        FlowLineView flow = new FlowLineView(getContext());
        root.addView(flow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dip(3)));

        // ---- 车图区 80% ----
        carZone = new FrameLayout(getContext());
        root.addView(carZone, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 80f));

        ivCar = new ImageView(getContext());
        ivCar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ivCar.setAdjustViewBounds(true);
        ivCar.setImageResource(modeIdx == 1
                ? com.jingxin.pandrive.R.drawable.car_top
                : com.jingxin.pandrive.R.drawable.car_top_day);
        FrameLayout.LayoutParams carLp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER);
        carLp.setMargins(dip(36), dip(30), dip(36), dip(30));
        carZone.addView(ivCar, carLp);

        tvSpeed = new TextView(getContext());
        tvSpeed.setText("0");
        tvSpeed.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        tvSpeed.setTextColor(c(C_TEXT_BRIGHT));
        tvSpeed.setGravity(android.view.Gravity.CENTER);
        tvSpeed.setShadowLayer(14, 0, 0, 0xFF00E5FF);
        FrameLayout.LayoutParams speedLp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        speedLp.topMargin = dip(15);
        carZone.addView(tvSpeed, speedLp);

        turnLeft = new TurnSignalView(getContext());
        turnRight = new TurnSignalView(getContext());
        carZone.addView(turnLeft, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        carZone.addView(turnRight, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        hvacToast = new HvacToastView(getContext());
        carZone.addView(hvacToast, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        tagLines = new TagLineOverlay(getContext(), null);
        carZone.addView(tagLines, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        tagLFDw = new DoorCircleView(getContext());
        tagRFDw = new DoorCircleView(getContext());
        tagLRDw = new DoorCircleView(getContext());
        tagRRDw = new DoorCircleView(getContext());
        tagTrunk = new DoorCircleView(getContext());
        tagLFTire = new TireCircleView(getContext());
        tagRFTire = new TireCircleView(getContext());
        tagLRTire = new TireCircleView(getContext());
        tagRRTire = new TireCircleView(getContext());
        for (DoorCircleView dw : new DoorCircleView[]{tagLFDw, tagRFDw, tagLRDw, tagRRDw, tagTrunk}) {
            carZone.addView(dw, new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        for (TireCircleView tire : new TireCircleView[]{tagLFTire, tagRFTire, tagLRTire, tagRRTire}) {
            carZone.addView(tire, new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }

        // 无数据提示（数据断流 10 秒后显示在车图上方）
        tvNoData = new TextView(getContext());
        tvNoData.setText("等待林肯车机信息数据…");
        tvNoData.setTextColor(c(C_TEXT_LABEL));
        tvNoData.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvNoData.setGravity(android.view.Gravity.CENTER);
        tvNoData.setVisibility(View.GONE);
        carZone.addView(tvNoData, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));

        // ---- 空调卡 10% ----
        LinearLayout acCard = new LinearLayout(getContext());
        acCard.setOrientation(LinearLayout.VERTICAL);
        acCard.setGravity(android.view.Gravity.CENTER);
        acCard.setBackgroundColor(c(C_CARD_BG));
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 10f);
        acLp.bottomMargin = dip(10);
        acLp.leftMargin = dip(0);
        acLp.rightMargin = dip(0);
        acCard.setPadding(dip(10), 0, dip(10), 0);
        root.addView(acCard, acLp);
        this.acCard = acCard;

        LinearLayout acRow = new LinearLayout(getContext());
        acRow.setOrientation(LinearLayout.HORIZONTAL);
        acCard.addView(acRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        tvTempDriver = addAcZone(acRow, "主驾", "23.0℃");
        addAcGap(acRow);
        tvPm = addAcZone(acRow, "车内PM2.5", "4");
        addAcGap(acRow);
        tvTempPassenger = addAcZone(acRow, "副驾", "23.0℃");

        // 布局完成后定位车图标签（林肯三 pass 定位算法，尺寸变化时由 relayoutAll 重挂）
        layoutCarTags();
    }

    /** 车图标签三 pass 定位（1:1 移植林肯 layoutCarTags），尺寸变化后需重新挂载 */
    private void layoutCarTags() {
        if (carZone == null) return;
        carZone.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    int pass = 0;
                    @Override
                    public void onGlobalLayout() {
                        try {
                            boolean needRelayout = positionCarTags();
                            if (needRelayout && pass < 2) {
                                pass++;
                                carZone.requestLayout();
                            } else {
                                carZone.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            }
                        } catch (Throwable t) {
                            carZone.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
                });
    }

    // ==================== 尺寸变化全量重算（对应林肯 onConfigurationChanged 链） ====================

    /** 页面尺寸变化：全屏↔悬浮、悬浮区域变化、横竖屏变化均会触发。
     *  悬浮态下 Configuration 反映的是全屏而非悬浮窗，故以页面实际宽高为准。 */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0 || (w == oldw && h == oldh)) return;
        post(this::relayoutAll);
    }

    /** 判定横屏：页面实际宽高比（悬浮态不可信 Configuration，用页面自身尺寸） */
    private boolean isLandscape() {
        int w = getWidth(), h = getHeight();
        return w > h * 1.2f;
    }

    /** 全量重算（1:1 对应林肯 onConfigurationChanged 的调用顺序：
     *  applyFontScale + scaleBannerByHeight + scaleAcCardByHeight + layoutCarTags） */
    public void relayoutAll() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        try {
            // 1. 圆形标签基准缩放（对应 applyFontScale 的 circleScale 部分）：
            //    原版基准=页面长边dp/540（横屏取宽、竖屏取高），clamp 0.5~1
            //    关键：scale 变化必须配 requestLayout，否则 View 用旧宽度短路测量，
            //    圆形后续被 setLayoutParams 放大重测却无重定位机会 → 左侧贴车/右侧外扩
            float density = getResources().getDisplayMetrics().density;
            float refDp = (isLandscape() ? getWidth() : getHeight()) / density;
            float circleScale = Math.min(refDp / 540f, 1f);
            circleScale = Math.max(circleScale, 0.5f);
            for (TireCircleView t : new TireCircleView[]{tagLFTire, tagRFTire, tagLRTire, tagRRTire}) {
                if (t.setDpScale(circleScale)) t.requestLayout();
            }
            for (DoorCircleView d : new DoorCircleView[]{tagLFDw, tagRFDw, tagLRDw, tagRRDw, tagTrunk}) {
                if (d.setDpScale(circleScale)) d.requestLayout();
            }

            // 2. banner 字号（对应 scaleBannerByHeight：横竖统一按高度，竖屏加宽度溢出检查）
            if (banner != null && banner.getHeight() > 0) {
                int bh = banner.getHeight();
                float valSize = bh * 0.42f;
                float lblSize = bh * 0.15f;
                scaleBannerText(banner, valSize, lblSize);
                if (!isLandscape()) fitBannerWidth(banner, banner.getWidth());
            }

            // 3. 空调卡字号（1:1 对应 scaleAcCardByHeight 横竖屏两套算法）
            scaleAcCardByHeight();

            // 4. 车图标签三 pass 定位（含速度/转向灯/空调提示联动）
            layoutCarTags();

            // 5. 用当前数据刷新标注点颜色
            linkTagDots(DataHub.getInstance(getContext()).getVehicleStatus());
            invalidate();
        } catch (Throwable ignored) {
        }
    }

    /** 横幅一项：竖排 [数值 / 标签]，返回数值 TextView */
    private TextView addBannerItem(LinearLayout parent, String value, String label, int color) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        boxLp.leftMargin = dip(6);
        boxLp.rightMargin = dip(6);
        parent.addView(box, boxLp);

        TextView valueTv = new TextView(getContext());
        valueTv.setText(value);
        valueTv.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        valueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        valueTv.setTextColor(color);
        box.addView(valueTv, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        TextView labelTv = new TextView(getContext());
        labelTv.setText(label);
        labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7f);
        labelTv.setTextColor(c(C_TEXT_LABEL));
        labelTv.setLetterSpacing(0.3f);
        box.addView(labelTv, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        return valueTv;
    }

    private void addDivider(LinearLayout parent) {
        View d = new View(getContext());
        d.setBackgroundColor(c(C_DIVIDER));
        d.setTag("lincoln_divider");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dip(1), dip(24));
        parent.addView(d, lp);
    }

    /** 空调区：竖排 [标签 / 数值]，返回数值 TextView */
    private TextView addAcZone(LinearLayout parent, String label, String value) {
        LinearLayout zone = new LinearLayout(getContext());
        zone.setOrientation(LinearLayout.VERTICAL);
        zone.setGravity(android.view.Gravity.CENTER);
        zone.setPadding(0, dip(5), 0, dip(6));
        zone.setBackgroundColor(c(C_ZONE_BG));
        parent.addView(zone, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        TextView labelTv = new TextView(getContext());
        labelTv.setText(label);
        labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7f);
        labelTv.setTextColor(c(C_TEXT_LABEL));
        zone.addView(labelTv, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        TextView valueTv = new TextView(getContext());
        valueTv.setText(value);
        valueTv.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        valueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        valueTv.setTextColor(c(C_TEXT_BRIGHT));
        zone.addView(valueTv, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return valueTv;
    }

    private void addAcGap(LinearLayout parent) {
        parent.addView(new View(getContext()), new LinearLayout.LayoutParams(dip(8), 1));
    }

    // ==================== 日夜切换 ====================

    /** 乐酷日夜切换：全部子 View 一次性切换色板/车图 */
    public void setNightMode(boolean night) {
        modeIdx = night ? 1 : 0;

        banner.setBackgroundColor(c(C_BG_BANNER));
        tvGear.setTextColor(c(C_STATE_ALERT));
        tvRange.setTextColor(c(C_TEXT_BRIGHT));
        tvOdo.setTextColor(c(C_STATE_OK));
        tvFuel.setTextColor(c(C_TEXT_BRIGHT));
        tvOil.setTextColor(c(C_TEXT_BRIGHT));
        tvSpeed.setTextColor(c(C_TEXT_BRIGHT));
        tvSpeed.setShadowLayer(14, 0, 0, night ? 0xFF00E5FF : 0x00000000);
        tvTempDriver.setTextColor(c(C_TEXT_BRIGHT));
        tvTempPassenger.setTextColor(c(C_TEXT_BRIGHT));
        tvPm.setTextColor(pmColor(-1));   // 由数据刷新时再按等级着色
        tvNoData.setTextColor(c(C_TEXT_LABEL));

        // 分隔线/空调区底：遍历刷新
        refreshDecorColors(banner);
        refreshAcZoneColors(carZone);

        ivCar.setImageResource(night
                ? com.jingxin.pandrive.R.drawable.car_top
                : com.jingxin.pandrive.R.drawable.car_top_day);

        // TurnSignalView 颜色日夜统一（金色 0xFFFFB300），无 setNightMode 接口
        hvacToast.setNightMode(night);
        tagLines.setNightMode(night);
        tagLFDw.setNightMode(night);
        tagRFDw.setNightMode(night);
        tagLRDw.setNightMode(night);
        tagRRDw.setNightMode(night);
        tagTrunk.setNightMode(night);
        tagLFTire.setNightMode(night);
        tagRFTire.setNightMode(night);
        tagLRTire.setNightMode(night);
        tagRRTire.setNightMode(night);

        // 用当前快照重新着色（PM 等级色、状态色）
        updateData(DataHub.getInstance(getContext()).getVehicleStatus());
        invalidate();
    }

    /** 刷新横幅分隔线颜色 */
    private void refreshDecorColors(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if ("lincoln_divider".equals(child.getTag())) {
                child.setBackgroundColor(c(C_DIVIDER));
            }
        }
    }

    /** 刷新空调卡 zone 底色（acCard → row → zone 三层遍历） */
    private void refreshAcZoneColors(ViewGroup root) {
        ViewGroup content = (ViewGroup) getChildAt(0);
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof LinearLayout && ((LinearLayout) child).getOrientation() == LinearLayout.VERTICAL
                    && child != banner && !(child instanceof FlowLineView)) {
                // acCard
                for (int j = 0; j < ((ViewGroup) child).getChildCount(); j++) {
                    View row = ((ViewGroup) child).getChildAt(j);
                    if (row instanceof LinearLayout) {
                        for (int k = 0; k < ((ViewGroup) row).getChildCount(); k++) {
                            View zone = ((ViewGroup) row).getChildAt(k);
                            if (zone instanceof LinearLayout) {
                                zone.setBackgroundColor(c(C_ZONE_BG));
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== 数据刷新（照抄林肯 updateDashboard，数据源换 VehicleStatus） ====================

    /** 收到车况快照刷新全页。必须在主线程调用 */
    public void updateData(DataHub.VehicleStatus s) {
        if (s == null) return;

        // ---- 横幅 ----
        tvGear.setText(s.gear < 0 ? "--" : gearStr(s.gear));
        tvRange.setText(s.range < 0 ? "--" : String.format("%.0f", Math.max(s.range, 0)));
        tvRangeUnitLikeSet(s.mileageUnit);
        if (s.odometer < 0 || s.odometer >= 1.6777215E7f) {
            tvOdo.setText("--");
        } else {
            String unit = (s.mileageUnit == null || s.mileageUnit.isEmpty()) ? "km" : s.mileageUnit;
            tvOdo.setText(setUnitSmall(String.format("%.0f", s.odometer) + " " + unit, " " + unit));
        }
        tvFuel.setText(s.fuelPct < 0 ? "--" : setUnitSmall(String.format("%.0f%%", Math.max(s.fuelPct, 0)), "%"));
        tvOil.setText(s.oilLife < 0 ? "--" : setUnitSmall(s.oilLife + "%", "%"));

        // ---- 速度 ----
        tvSpeed.setText(s.speed < 0 ? "--" : String.format("%.0f", Math.max(s.speed, 0)));

        // ---- 转向灯 ----
        turnLeft.update(s.turnLeft, true);
        turnRight.update(s.turnRight, false);

        // ---- 四轮胎压 ----
        updateTireCircle(tagLFTire, s.tirePressure[0], s.tireState[0], s.tireUnit);
        updateTireCircle(tagRFTire, s.tirePressure[1], s.tireState[1], s.tireUnit);
        updateTireCircle(tagLRTire, s.tirePressure[2], s.tireState[2], s.tireUnit);
        updateTireCircle(tagRRTire, s.tirePressure[3], s.tireState[3], s.tireUnit);

        // ---- 门窗 ----
        updateDoorCircle(tagLFDw, s.doorLB, s.winDriver);
        updateDoorCircle(tagRFDw, s.doorRB, s.winPassenger);
        updateDoorCircle(tagLRDw, s.doorLR, s.winLeftRear);
        updateDoorCircle(tagRRDw, s.doorRR, s.winRightRear);
        tagTrunk.updateTrunk(s.trunkIn > 0 || s.trunkOut > 0);

        // ---- 空调卡 ----
        tvTempDriver.setText(s.tempDriver == Float.MIN_VALUE ? "--"
                : setUnitSmall(String.format("%.1f", s.tempDriver) + "℃", "℃"));
        // 单区车机：副驾跟主驾
        float tp = s.tempPassenger == Float.MIN_VALUE ? s.tempDriver : s.tempPassenger;
        tvTempPassenger.setText(tp == Float.MIN_VALUE ? "--" : setUnitSmall(String.format("%.1f", tp) + "℃", "℃"));
        tvPm.setText(s.pm25 < 0 ? "--" : setUnitSmall(s.pm25 + " μg/m³", " μg/m³"));
        tvPm.setTextColor(pmColor(s.pm25));

        // ---- HVAC toast（变化才提示） ----
        showHvacToastIfNeeded(s);

        // ---- 断流提示 ----
        tvNoData.setVisibility(s.isFresh(android.os.SystemClock.elapsedRealtime())
                ? View.GONE : View.VISIBLE);

        // ---- 标注点颜色同步 ----
        linkTagDots(s);
    }

    /** 续航/总里程单位：缩小字号拼在数值后（与林肯 tvRangeUnit 视觉一致） */
    private void tvRangeUnitLikeSet(String unit) {
        if (unit == null || unit.isEmpty()) unit = "km";
        // updateData 先设了纯数值，这里追加缩小单位
        String val = tvRange.getText().toString();
        tvRange.setText(setUnitSmall(val + " " + unit, " " + unit));
    }

    /** 数值+单位缩小字号（照抄林肯 setUnitSmall） */
    private SpannableString setUnitSmall(String fullText, String unit) {
        SpannableString span = new SpannableString(fullText);
        int idx = fullText.lastIndexOf(unit);
        if (idx > 0) {
            span.setSpan(new RelativeSizeSpan(0.5f), idx, idx + unit.length(),
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    /** 小数点及之后的小数部分缩小到 50%（如 "74595.0" → 整数正常 + ".0" 缩小） */
    private SpannableString setDecimalSmall(String fullText) {
        SpannableString span = new SpannableString(fullText);
        int dotIdx = fullText.indexOf('.');
        if (dotIdx >= 0) {
            span.setSpan(new RelativeSizeSpan(0.5f), dotIdx, fullText.length(),
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    /** 胎压圆形标签刷新（照抄林肯 updateTireCircle，数据从数组取） */
    private void updateTireCircle(TireCircleView v, float pressure, int state, String unit) {
        if (pressure < 0) {
            v.update("--", "--", v.colorInvalid(), false);
            return;
        }
        int color;
        boolean alert;
        if (pressure >= 65533f) {
            color = v.colorInvalid(); alert = false;   // 无效值灰
        } else if (state > 250) {
            color = v.colorAlert(); alert = true;      // 异常红
        } else if (state > 150 || state > 75) {
            color = v.colorWarn(); alert = false;      // 高压/低压琥珀
        } else {
            color = v.colorOk(); alert = false;         // 正常绿
        }
        v.update(pressureNum(pressure, unit),
                unit == null || unit.isEmpty() ? "--" : unit, color, alert);
    }

    /** 胎压数值换算（照抄林肯 pressureNum：bar×0.01 / psi×0.14504 / kPa 十位取整） */
    private String pressureNum(float raw, String unit) {
        if (raw >= 65533f) return "--";
        if ("bar".equals(unit)) {
            float f = raw;
            if (f % 5.0f == 0.0f) f += 1.0f;
            return String.format("%.2f", f * 0.01f);
        }
        if ("psi".equals(unit)) return String.format("%.0f", raw * 0.14504f);
        if ("kPa".equals(unit)) {
            int v = (int) raw;
            int f2 = v % 10;
            if (f2 <= 2) return String.valueOf(v / 10 * 10);
            if (f2 >= 8) return String.valueOf((v / 10 + 1) * 10);
            return String.valueOf(v / 10 * 10 + 5);
        }
        return String.valueOf((int) raw);
    }

    /** 门窗圆形刷新（照抄林肯 updateDoorCircle：1=关，其余有效值=未关） */
    private void updateDoorCircle(DoorCircleView v, int door, int win) {
        boolean doorOpen = door > 0;
        boolean winOpen = win >= 0 && win != 1;
        v.update(doorOpen, winOpen);
    }

    /** HVAC toast 变化检测（林肯原版首次不提示；乐酷悬浮态改为首次也提示，
     *  应用刚进悬浮/翻页时用户需要立即知道当前空调状态） */
    private void showHvacToastIfNeeded(DataHub.VehicleStatus s) {
        if (s.acPower >= 0) {
            if (lastCcm == null || lastCcm != s.acPower) {
                hvacToast.show(s.acPower == 1 ? "空调开" : "空调关");
                lastCcm = s.acPower;
            }
        }
        if (s.fanLevel >= 0) {
            if (lastFanLevel == null || lastFanLevel != s.fanLevel) {
                if (lastFanLevel != null && s.fanLevel > 0) {
                    hvacToast.show("风量" + s.fanLevel);
                }
                lastFanLevel = s.fanLevel;
            }
        }
        if (s.acOn >= 0) {
            if (lastAc == null || lastAc != s.acOn) {
                hvacToast.show(s.acOn == 1 ? "制冷开" : "制冷关");
                lastAc = s.acOn;
            }
        }
        if (s.acRecycle >= 0) {
            if (lastRecycle == null || lastRecycle != s.acRecycle) {
                hvacToast.show(s.acRecycle == 1 ? "内循环开" : "内循环开");
                lastRecycle = s.acRecycle;
            }
        }
    }

    /** PM2.5 等级色（照抄林肯 pmColor/pmLevel：优绿/良青/中黄/差橙/差红/严重红/无效灰） */
    private int pmColor(int pm) {
        int lv = pmLevel(pm);
        switch (lv) {
            case 1: return modeIdx == 1 ? 0xFF00FF9D : 0xFF0077C2;
            case 2: return modeIdx == 1 ? 0xFF00E5FF : 0xFF0077C2;
            case 3: return modeIdx == 1 ? 0xFFFFB020 : 0xFFC77800;
            case 4: return modeIdx == 1 ? 0xFFFF8A3D : 0xFFC62828;
            case 5: return modeIdx == 1 ? 0xFFFF5A5A : 0xFFD32F2F;
            case 6: return modeIdx == 1 ? 0xFFFF2E2E : 0xFFB71C1C;
            default: return modeIdx == 1 ? 0xFF5C7A99 : 0xFF8A97A5;
        }
    }

    private static int pmLevel(int pm) {
        if (pm < 0) return 0;
        if (pm <= 35) return 1;
        if (pm <= 75) return 2;
        if (pm <= 115) return 3;
        if (pm <= 150) return 4;
        if (pm <= 250) return 5;
        return pm <= 500 ? 6 : 0;
    }

    /** 挡位映射（照抄林肯 gearStr：百度自定义枚举） */
    private static String gearStr(int v) {
        switch (v) {
            case 0: return "P";
            case 1: return "N";
            case 2: return "R";
            case 3: return "D";
            case 4: return "D2";
            case 5: return "D3";
            case 6: return "D4";
            case 7: return "D5";
            case 8: return "D6";
            case 9: return "D7";
            case 10: return "D8";
            default: return "值=" + v;
        }
    }

    // ==================== 车图标签定位（1:1 移植林肯 positionCarTags/carImgRect/linkTagDots） ====================

    /** 车图区触摸是否落在车图矩形内（整页容器的放行判定用） */
    public boolean isTouchInCarZone(float xInPage, float yInPage) {
        if (carZone == null || getWidth() == 0) return false;
        // 页面在容器中的左边缘 = 本页索引 × 页宽，触摸点换算到本页局部坐标
        float localX = xInPage - getLeft();
        float localY = yInPage - getTop();
        // 车图区在 root 内的顶部偏移 = 横幅高 + 流线高
        int carZoneTop = carZone.getTop();
        int carZoneLeft = carZone.getLeft();
        return localX >= carZoneLeft && localX <= carZoneLeft + carZone.getWidth()
                && localY >= carZoneTop && localY <= carZoneTop + carZone.getHeight();
    }

    /**
     * 车图区标签定位 + 圆形宽度溢出检测（1:1 移植林肯 positionCarTags）。
     * @return true 如果因宽度不足缩小了圆形尺寸，调用方需 requestLayout 重新测量。
     */
    private boolean positionCarTags() {
        int zw = carZone.getWidth(), zh = carZone.getHeight();
        if (zw <= 0 || zh <= 0) return false;

        float tireEdgeX = zw * 0.14f;       // 胎压标签离左/右边缘 14%
        float dwEdgeX = zw * 0.06f;          // 门窗标签离左/右边缘 6%

        // 对称轴：前后门窗标注点中点 y=52.5%（相对车图 fitH）
        float[] r = carImgRect(zw, zh);
        float axisY = r[1] + r[3] * 0.525f;

        // —— 宽度溢出检测（车体轮胎外缘 21.9% 为实际边界）——
        float carBodyLeft = r[0] + r[2] * 0.219f;
        float carBodyRight = r[0] + r[2] * 0.781f;
        float dwBaseDp = 90f;
        float density = getResources().getDisplayMetrics().density;
        float dwAvail = carBodyLeft - dwEdgeX;
        float widthScale = dwAvail / (dwBaseDp * density);
        widthScale = Math.max(widthScale, 0.3f);

        // 高度缩放因子（1:1 移植：页面长边 dp/540，横屏取宽竖屏取高，悬浮态用页面实际尺寸）
        float refDp = (isLandscape() ? getWidth() : getHeight()) / density;
        float heightScale = Math.min(refDp / 540f, 1f);
        heightScale = Math.max(heightScale, 0.5f);

        float finalScale = Math.min(heightScale, widthScale);
        boolean needRelayout = applyCircleScaleIfNeeded(finalScale);

        // —— 前胎压标签：跟随车图顶部 ——
        float tireTopY = r[1] - dip(6);
        placeCornerTag(tagLFTire, tireEdgeX, tireTopY, true, false);
        placeCornerTag(tagRFTire, zw - tireEdgeX, tireTopY, false, false);

        // —— 左侧链：前胎压中心 → 镜像后胎压中心 → 等间距门窗 ——
        float cFL = tireTopY + tagLFTire.getHeight() / 2f;
        float cRL = 2f * axisY - cFL;
        centerTagAt(tagLRTire, tireEdgeX, cRL, true);
        float stepL = (cRL - cFL) / 3f;
        centerTagAt(tagLFDw, dwEdgeX, cFL + stepL, true);
        centerTagAt(tagLRDw, dwEdgeX, cFL + stepL * 2f, true);

        // —— 右侧链 ——
        float cFR = tireTopY + tagRFTire.getHeight() / 2f;
        float cRR = 2f * axisY - cFR;
        centerTagAt(tagRRTire, zw - tireEdgeX, cRR, false);
        float stepR = (cRR - cFR) / 3f;
        centerTagAt(tagRFDw, zw - dwEdgeX, cFR + stepR, false);
        centerTagAt(tagRRDw, zw - dwEdgeX, cFR + stepR * 2f, false);

        // —— 后备箱：水平对齐车图中心，垂直对齐后胎压中心 ——
        if (tagTrunk != null) {
            int w = tagTrunk.getWidth(), h = tagTrunk.getHeight();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tagTrunk.getLayoutParams();
            float carCenterX = r[0] + r[2] / 2f;
            lp.leftMargin = Math.round(carCenterX - w / 2f);
            lp.topMargin = Math.round(cRL - h / 2f);
            tagTrunk.setLayoutParams(lp);
            tagTrunk.bringToFront();
        }
        for (View tag : new View[]{tagLFTire, tagRFTire, tagLRTire, tagRRTire,
                tagLFDw, tagRFDw, tagLRDw, tagRRDw}) {
            if (tag != null) tag.bringToFront();
        }

        // —— 速度字号：banner 底边到车体顶边实际间距动态计算 ——
        if (tvSpeed != null) {
            float carBodyTop = r[1] + r[3] * 0.114f;
            float gap = dip(15);
            float availH = carBodyTop - gap - gap;
            if (availH > 0) {
                float textSize = availH / 1.25f;
                float maxTextSize = 60f * getResources().getDisplayMetrics().density;
                textSize = Math.min(textSize, maxTextSize);
                tvSpeed.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
                tvSpeed.setHeight(Math.round(textSize * 1.25f));
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvSpeed.getLayoutParams();
                lp.topMargin = Math.round(gap);
                tvSpeed.setLayoutParams(lp);
            }
        }

        // —— 转向灯定位：速度左右两侧 ——
        if (turnLeft != null && turnRight != null && tvSpeed != null) {
            float carBodyTop = r[1] + r[3] * 0.114f;
            float gap = dip(15);
            float availH = carBodyTop - gap - gap;
            float arrowSize = availH > 0 ? availH * 0.8f : dip(30);
            float maxArrow = 60f * getResources().getDisplayMetrics().density * 1.25f * 0.8f;
            arrowSize = Math.min(arrowSize, maxArrow);
            int sizePx = Math.round(arrowSize);
            float speedTopY = gap;
            tvSpeed.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int speedW = tvSpeed.getMeasuredWidth();
            float speedCenterX = zw / 2f;
            float offset = speedW / 2f + dip(8);
            FrameLayout.LayoutParams lpL = (FrameLayout.LayoutParams) turnLeft.getLayoutParams();
            lpL.width = sizePx;
            lpL.height = sizePx;
            lpL.leftMargin = Math.round(speedCenterX - offset - sizePx);
            lpL.topMargin = Math.round(speedTopY - sizePx * 0.15f);
            turnLeft.setLayoutParams(lpL);
            FrameLayout.LayoutParams lpR = (FrameLayout.LayoutParams) turnRight.getLayoutParams();
            lpR.width = sizePx;
            lpR.height = sizePx;
            lpR.leftMargin = Math.round(speedCenterX + offset);
            lpR.topMargin = Math.round(speedTopY - sizePx * 0.15f);
            turnRight.setLayoutParams(lpR);
            turnLeft.bringToFront();
            turnRight.bringToFront();
        }

        // —— 空调提示定位：车体底部到 carZone 底部 60% ——
        if (hvacToast != null) {
            float carBodyBottom = r[1] + r[3] * 0.887f;
            float zoneBottom = zh;
            float centerAreaH = zoneBottom - carBodyBottom;
            float toastH = centerAreaH * 0.6f;
            if (toastH > 0) {
                float textSize = toastH / 1.25f;
                hvacToast.setMaxTextSize(textSize);
                FrameLayout.LayoutParams lpToast = (FrameLayout.LayoutParams) hvacToast.getLayoutParams();
                lpToast.width = zw;
                lpToast.height = Math.round(toastH);
                lpToast.leftMargin = 0;
                lpToast.topMargin = Math.round(zoneBottom - toastH);
                hvacToast.setLayoutParams(lpToast);
                hvacToast.bringToFront();
            }
        }

        // —— 无数据提示：车图区中央 ——
        if (tvNoData != null) tvNoData.bringToFront();

        // —— 红点虚线（需要当前数据） ——
        linkTagDots(DataHub.getInstance(getContext()).getVehicleStatus());
        return needRelayout;
    }

    /** 按 finalScale 更新圆形标签 dpScale，仅缩小时更新（1:1 移植） */
    private boolean applyCircleScaleIfNeeded(float finalScale) {
        boolean changed = false;
        if (finalScale < tagLFTire.getDpScale()) { tagLFTire.setDpScale(finalScale); changed = true; }
        if (finalScale < tagRFTire.getDpScale()) { tagRFTire.setDpScale(finalScale); changed = true; }
        if (finalScale < tagLRTire.getDpScale()) { tagLRTire.setDpScale(finalScale); changed = true; }
        if (finalScale < tagRRTire.getDpScale()) { tagRRTire.setDpScale(finalScale); changed = true; }
        if (finalScale < tagLFDw.getDpScale()) { tagLFDw.setDpScale(finalScale); changed = true; }
        if (finalScale < tagRFDw.getDpScale()) { tagRFDw.setDpScale(finalScale); changed = true; }
        if (finalScale < tagLRDw.getDpScale()) { tagLRDw.setDpScale(finalScale); changed = true; }
        if (finalScale < tagRRDw.getDpScale()) { tagRRDw.setDpScale(finalScale); changed = true; }
        if (finalScale < tagTrunk.getDpScale()) { tagTrunk.setDpScale(finalScale); changed = true; }
        return changed;
    }

    /** 车图 fitCenter 显示矩形（1000:1333 比例），1:1 移植林肯 carImgRect。
     *  本页 margin=30+6 与林肯一致（代码构建时已设 margins 36/30，此处同步） */
    private float[] carImgRect(int zw, int zh) {
        float ratio = 1333f / 1000f;
        int mL = dip(30) + dip(6), mR = dip(30) + dip(6);
        int mT = dip(24) + dip(6), mB = dip(24) + dip(6);
        int availW = Math.max(1, zw - mL - mR);
        int availH = Math.max(1, zh - mT - mB);
        float fitW, fitH;
        if (availW * ratio >= availH) { fitH = availH; fitW = fitH / ratio; }
        else { fitW = availW; fitH = fitW * ratio; }
        float left = (zw - fitW) / 2f, top = (zh - fitH) / 2f;
        return new float[]{left, top, fitW, fitH};
    }

    /** 9 个标注点 + 虚线（1:1 移植林肯 linkTagDots，数据源换 VehicleStatus） */
    private void linkTagDots(DataHub.VehicleStatus s) {
        if (tagLines == null || s == null) return;
        int zw = carZone.getWidth(), zh = carZone.getHeight();
        float[] r = carImgRect(zw, zh);
        float left = r[0], top = r[1], fitW = r[2], fitH = r[3];
        float bodyL = left + fitW * 0.269f, bodyR = left + fitW * 0.731f;
        float tireFrontY = top + fitH * 0.290f, tireRearY = top + fitH * 0.732f;
        float tireLX = left + fitW * 0.240f, tireRX = left + fitW * 0.758f;
        float doorFrontY = top + fitH * 0.460f, doorRearY = top + fitH * 0.590f;
        float tailX = left + fitW * 0.50f, tailY = top + fitH * 0.860f;

        tagLines.clear();
        tagLines.setLink(tireLX, tireFrontY, tagLFTire, tireAlert(s, 0), true);
        tagLines.setLink(tireRX, tireFrontY, tagRFTire, tireAlert(s, 1), true);
        tagLines.setLink(tireLX, tireRearY, tagLRTire, tireAlert(s, 2), true);
        tagLines.setLink(tireRX, tireRearY, tagRRTire, tireAlert(s, 3), true);
        tagLines.setLink(bodyL, doorFrontY, tagLFDw, doorAlert(s.doorLB, s.winDriver));
        tagLines.setLink(bodyR, doorFrontY, tagRFDw, doorAlert(s.doorRB, s.winPassenger));
        tagLines.setLink(bodyL, doorRearY, tagLRDw, doorAlert(s.doorLR, s.winLeftRear));
        tagLines.setLink(bodyR, doorRearY, tagRRDw, doorAlert(s.doorRR, s.winRightRear));
        tagLines.setLink(tailX, tailY, tagTrunk, s.trunkIn > 0 || s.trunkOut > 0);
        tagLines.apply();
    }

    /** 胎压异常判定（照抄林肯）：<200kPa 且非无效值 */
    private boolean tireAlert(DataHub.VehicleStatus s, int idx) {
        float p = s.tirePressure[idx];
        return p >= 0 && p < 200f && p < 65533f;
    }

    /** 门窗异常判定（照抄林肯）：门开或窗未关 */
    private boolean doorAlert(int door, int win) {
        return door > 0 || (win >= 0 && win != 1);
    }

    /** 标签垂直居中于 centerY（1:1 移植林肯 centerTagAt） */
    private void centerTagAt(View tag, float x, float centerY, boolean atLeft) {
        if (tag == null) return;
        int w = tag.getWidth(), h = tag.getHeight();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tag.getLayoutParams();
        lp.leftMargin = Math.round(atLeft ? x : x - w);
        lp.topMargin = Math.round(centerY - h / 2f);
        tag.setLayoutParams(lp);
    }

    /** 角标签定位（1:1 移植林肯 placeCornerTag） */
    private void placeCornerTag(View tag, float x, float y, boolean atLeft, boolean anchorBottom) {
        if (tag == null) return;
        int w = tag.getWidth(), h = tag.getHeight();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tag.getLayoutParams();
        if (atLeft) {
            lp.leftMargin = Math.round(x);
        } else {
            lp.leftMargin = Math.round(x - w);
        }
        if (anchorBottom) {
            lp.topMargin = Math.round(y - h);
        } else {
            lp.topMargin = Math.round(y);
        }
        tag.setLayoutParams(lp);
    }

    /** 旧入口（MainActivity 翻页回调调用）→ 全量重算 */
    public void scaleTextByRegion() {
        relayoutAll();
    }

    /** 递归设横幅/空调内 TextView 字号（照抄林肯 scaleBannerText：数值/标签两档） */
    private void scaleBannerText(View v, float valSize, float lblSize) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            boolean isValue = tv == tvGear || tv == tvRange || tv == tvOdo
                    || tv == tvFuel || tv == tvOil || tv == tvTempDriver
                    || tv == tvTempPassenger || tv == tvPm;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, isValue ? valSize : lblSize);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scaleBannerText(g.getChildAt(i), valSize, lblSize);
        }
    }

    /** 竖屏 banner 宽度自适应（1:1 移植林肯 fitBannerWidth）：
     *  内容总宽超出可用宽度时等比缩小字号与左右间距 */
    private void fitBannerWidth(View bannerView, int bannerWidth) {
        if (!(bannerView instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) bannerView;
        int contentWidth = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            contentWidth += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
        }
        if (contentWidth <= bannerWidth || contentWidth <= 0) return;
        float ratio = (float) bannerWidth / contentWidth;
        scaleBannerTextByRatio(bannerView, ratio);
        scaleBannerMarginByRatio(group, ratio);
    }

    private void scaleBannerTextByRatio(View v, float ratio) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize() * ratio);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scaleBannerTextByRatio(g.getChildAt(i), ratio);
        }
    }

    private void scaleBannerMarginByRatio(ViewGroup group, float ratio) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            lp.leftMargin = Math.round(lp.leftMargin * ratio);
            lp.rightMargin = Math.round(lp.rightMargin * ratio);
            child.setLayoutParams(lp);
            if (child instanceof ViewGroup) {
                scaleBannerMarginByRatio((ViewGroup) child, ratio);
            }
        }
    }

    /** 空调卡字号（1:1 移植林肯 scaleAcCardByHeight 横竖屏两套算法）。
     *  悬浮态修正：行高用字体实测值（字号估算 1.25 偏小导致小窗裁切），
     *  横屏固定 8dp padding 按 zone 高比例封顶（小窗时 32px padding 吃掉一半高度）。
     *  横屏：压 padding + 按 zone 实际高度两行字号 70%/30%；
     *  竖屏：按卡片总高 45%/15% + 宽度溢出检查。 */
    private void scaleAcCardByHeight() {
        if (acCard == null || acCard.getHeight() <= 0) return;
        int ah = acCard.getHeight();
        if (isLandscape()) {
            // 横屏：压缩外层 padding 释放空间（与原版一致）
            acCard.setPadding(dip(10), dip(1), dip(10), dip(1));
            LinearLayout.LayoutParams acLp = (LinearLayout.LayoutParams) acCard.getLayoutParams();
            acLp.bottomMargin = dip(3);
            acCard.setLayoutParams(acLp);
            ViewGroup cardRow = (ViewGroup) acCard.getChildAt(0);
            if (cardRow != null) {
                // zone 保持竖排（标签上、数值下），padding 换横屏规格（小窗按高度比例封顶）
                View first0 = cardRow.getChildCount() > 0 ? cardRow.getChildAt(0) : null;
                int zoneH0 = first0 instanceof LinearLayout ? first0.getHeight() : 0;
                int padV = dip(8);
                if (zoneH0 > 0) padV = Math.min(dip(8), Math.round(zoneH0 * 0.10f));
                for (int i = 0; i < cardRow.getChildCount(); i++) {
                    View zoneV = cardRow.getChildAt(i);
                    if (zoneV instanceof LinearLayout) {
                        LinearLayout zone = (LinearLayout) zoneV;
                        zone.setOrientation(LinearLayout.VERTICAL);
                        zone.setPadding(dip(3), padV, dip(3), padV);
                    }
                }
                // 按第一个 zone 实际高度设两行字号（行高系数用字体实测，不按 1.25 估算）
                View first = cardRow.getChildCount() > 0 ? cardRow.getChildAt(0) : null;
                if (first instanceof LinearLayout) {
                    int zoneH = first.getHeight();
                    if (zoneH > 0) {
                        int usable = zoneH - padV * 2;
                        float lineFactor = measureLineFactor();
                        float textTotal = usable / lineFactor;
                        float valSize = textTotal * 0.7f;
                        float lblSize = textTotal * 0.3f;
                        scaleBannerText(acCard, valSize, lblSize);
                    }
                }
            }
        } else {
            // 竖屏：以 zone 实际高度为准（悬浮竖窗卡片极矮，按卡总高45%会溢出裁切）。
            //  zone 高 = 卡高 - bottomMargin 后的行高；扣上下 padding，按实测行高系数分配字号
            ViewGroup cardRowP = acCard.getChildCount() > 0
                    && acCard.getChildAt(0) instanceof ViewGroup
                    ? (ViewGroup) acCard.getChildAt(0) : null;
            View firstP = cardRowP != null && cardRowP.getChildCount() > 0
                    ? cardRowP.getChildAt(0) : null;
            int zoneH = firstP instanceof LinearLayout ? firstP.getHeight() : 0;
            LinearLayout.LayoutParams lpP = (LinearLayout.LayoutParams) acCard.getLayoutParams();
            int padTop = dip(5), padBottom = dip(6);
            if (zoneH > 0) {
                // 竖屏 padding 保持原版 5/6dp，但小窗时按 zone 高比例封顶
                padTop = Math.min(dip(5), Math.round(zoneH * 0.08f));
                padBottom = Math.min(dip(6), Math.round(zoneH * 0.10f));
                float usable = zoneH - padTop - padBottom;
                float lineFactor = measureLineFactor();
                float textTotal = usable / lineFactor;
                float valSize = textTotal * 0.75f;
                float lblSize = textTotal * 0.25f;
                scaleBannerText(acCard, valSize, lblSize);
                // zone padding 应用到全部三格
                if (cardRowP != null) {
                    for (int i = 0; i < cardRowP.getChildCount(); i++) {
                        View z = cardRowP.getChildAt(i);
                        if (z instanceof LinearLayout) {
                            ((LinearLayout) z).setPadding(0, padTop, 0, padBottom);
                        }
                    }
                }
            } else {
                // zone 未测量时兜底：按卡总高
                float lineFactor = measureLineFactor();
                float textTotal = (ah - dip(11)) / lineFactor;
                float valSize = textTotal * 0.75f;
                float lblSize = textTotal * 0.25f;
                scaleBannerText(acCard, valSize, lblSize);
            }
            fitAcCardWidth(acCard);
        }
    }

    /** 实测 monospace 粗体行高系数（渲染高度/字号）：一次测量复用，
     *  避免按 1.25 估算导致悬浮小窗字号偏大、数值行底部被裁切 */
    private float measureLineFactor() {
        Paint p = new Paint();
        p.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        Paint.FontMetrics fm = p.getFontMetrics();
        float factor = (fm.bottom - fm.top) / p.getTextSize();
        return Math.max(factor, 1.1f);
    }

    /** 空调卡宽度溢出检查（1:1 移植林肯 fitAcCardWidth）：
     *  各 zone 内最宽文字超出可用宽度时等比缩小该 zone 字号 */
    private void fitAcCardWidth(View card) {
        if (!(card instanceof ViewGroup)) return;
        ViewGroup cardOuter = (ViewGroup) card;
        if (cardOuter.getChildCount() == 0) return;
        View row = cardOuter.getChildAt(0);
        if (!(row instanceof ViewGroup)) return;
        ViewGroup cardRow = (ViewGroup) row;
        for (int i = 0; i < cardRow.getChildCount(); i++) {
            View child = cardRow.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            ViewGroup zone = (ViewGroup) child;
            int zoneW = zone.getWidth() - zone.getPaddingLeft() - zone.getPaddingRight();
            if (zoneW <= 0) continue;
            int maxTextW = 0;
            for (int j = 0; j < zone.getChildCount(); j++) {
                View tv = zone.getChildAt(j);
                if (tv instanceof TextView) {
                    tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    maxTextW = Math.max(maxTextW, ((TextView) tv).getMeasuredWidth());
                }
            }
            if (maxTextW > zoneW && maxTextW > 0) {
                float ratio = (float) zoneW / maxTextW;
                scaleBannerTextByRatio(zone, ratio);
            }
        }
    }
}
