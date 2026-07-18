package com.jingxin.pandrive.view;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jingxin.pandrive.R;
import com.jingxin.pandrive.data.DataHub;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Section 3: 辅助导航
 * 双模式自动切换:
 * - 导航模式: 宽行2视图 (转向图标 + LED距离 + 路名 + 底部栏 + 路况条)
 * - 巡航模式: 两行布局 (红绿灯独占第一行 + 车速/限速/路名第二行 + 路况条)
 *
 * 逻辑对齐原项目 高德辅助导航 FloatingWindowService
 */
public class NavigationBarView extends FrameLayout implements
        DataHub.OnNavigationListener, DataHub.OnModeListener {

    private boolean isNightMode = false;
    private int currentMode = DataHub.MODE_CRUISE;
    private DataHub dataHub;

    // Navigation mode views
    private LinearLayout naviLayout;
    private ImageView ivNaviIcon;
    private LEDDigitView ledDistanceNumber;
    private TextView tvNavigationDistance;
    private TextView tvNavigationGuide;
    private TextView tvNavigationDetail;
    private TextView tvSpeedLimit;        // navi mode speed limit
    private LinearLayout trafficLightContainer; // navi mode single light
    private TextView tvTrafficLight;
    private TrafficBarView naviTrafficBar; // navi mode traffic bar (separate from cruise)

    // Cruise mode views
    private LinearLayout cruiseLayout;
    private ImageView ivCruiseCar;
    private TextView tvCruiseSpeed;
    private TextView tvCruiseUnit;
    private TextView tvCruiseSpeedLimit;
    private View cruiseDivider;
    private TextView tvCruiseRoadName;
    private LinearLayout cruiseLightsContainer;

    public NavigationBarView(Context context) {
        super(context);
        init();
    }

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NavigationBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 透明背景，让底层网格透出
        setBackgroundColor(0x00000000);

        dataHub = DataHub.getInstance(getContext());
        dataHub.addNavigationListener(this);
        dataHub.addModeListener(this);

        setClipChildren(false);

        // Create both layouts
        createCruiseLayout();
        createNaviLayout();

        // Sync mode from DataHub (may already be MODE_NAVI)
        currentMode = dataHub.getCurrentMode();

        // Show current mode
        updateModeDisplay();
        updateColors();
    }

    // ==================== Cruise Layout ====================
    private void createCruiseLayout() {
        float density = getResources().getDisplayMetrics().density;
        cruiseLayout = new LinearLayout(getContext());
        cruiseLayout.setOrientation(LinearLayout.VERTICAL);
        cruiseLayout.setGravity(Gravity.CENTER);
        cruiseLayout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Row 1: Traffic lights
        LinearLayout lightsRow = new LinearLayout(getContext());
        lightsRow.setOrientation(LinearLayout.HORIZONTAL);
        lightsRow.setGravity(Gravity.CENTER);
        lightsRow.setPadding((int)(8 * density), (int)(2 * density), (int)(8 * density), 0);
        cruiseLightsContainer = new LinearLayout(getContext());
        cruiseLightsContainer.setOrientation(LinearLayout.HORIZONTAL);
        cruiseLightsContainer.setVisibility(GONE);
        lightsRow.addView(cruiseLightsContainer);
        cruiseLayout.addView(lightsRow, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 0f));

        // Row 2: Speed + limit + road name
        LinearLayout speedRow = new LinearLayout(getContext());
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        speedRow.setPadding((int)(8 * density), 0, (int)(8 * density), (int)(2 * density));

        ivCruiseCar = new ImageView(getContext());
        ivCruiseCar.setImageResource(R.drawable.ic_cruise_car);
        ivCruiseCar.setBackgroundResource(R.drawable.blue_rounded_card);
        int iconSize = (int)(30 * density);
        ivCruiseCar.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        ivCruiseCar.setPadding((int)(3 * density), (int)(3 * density), (int)(3 * density), (int)(3 * density));
        speedRow.addView(ivCruiseCar);

        tvCruiseSpeed = new TextView(getContext());
        tvCruiseSpeed.setTextSize(26);
        tvCruiseSpeed.setTypeface(null, Typeface.BOLD);
        tvCruiseSpeed.setIncludeFontPadding(false);
        tvCruiseSpeed.setPadding((int)(4 * density), 0, 0, 0);
        speedRow.addView(tvCruiseSpeed);

        tvCruiseUnit = new TextView(getContext());
        tvCruiseUnit.setTextSize(10);
        tvCruiseUnit.setPadding((int)(2 * density), 0, (int)(4 * density), 0);
        LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        unitParams.gravity = Gravity.BOTTOM;
        unitParams.bottomMargin = (int)(2 * density);
        tvCruiseUnit.setLayoutParams(unitParams);
        speedRow.addView(tvCruiseUnit);

        tvCruiseSpeedLimit = new TextView(getContext());
        tvCruiseSpeedLimit.setBackgroundResource(R.drawable.circle_red_bg);
        tvCruiseSpeedLimit.setTextSize(12);
        tvCruiseSpeedLimit.setTypeface(null, Typeface.BOLD);
        tvCruiseSpeedLimit.setGravity(Gravity.CENTER);
        int limitSize = (int)(28 * density);
        tvCruiseSpeedLimit.setLayoutParams(new LinearLayout.LayoutParams(limitSize, limitSize));
        tvCruiseSpeedLimit.setVisibility(GONE);
        speedRow.addView(tvCruiseSpeedLimit);

        cruiseDivider = new View(getContext());
        cruiseDivider.setBackgroundColor(0x33999999);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams((int)(1 * density), (int)(20 * density));
        divParams.setMargins((int)(6 * density), 0, (int)(6 * density), 0);
        cruiseDivider.setLayoutParams(divParams);
        speedRow.addView(cruiseDivider);

        tvCruiseRoadName = new TextView(getContext());
        tvCruiseRoadName.setTextSize(18);
        tvCruiseRoadName.setSingleLine(true);
        tvCruiseRoadName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        speedRow.addView(tvCruiseRoadName, new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams speedRowParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        speedRowParams.topMargin = (int)(10 * density);
        cruiseLayout.addView(speedRow, speedRowParams);

        // 原项目巡航模式没有TrafficBar，只保留在naviLayout中

        // Initially hidden
        cruiseLayout.setVisibility(GONE);
        addView(cruiseLayout);
    }

    // ==================== Navi Layout ====================
    private void createNaviLayout() {
        float density = getResources().getDisplayMetrics().density;
        naviLayout = new LinearLayout(getContext());
        naviLayout.setOrientation(LinearLayout.VERTICAL);
        naviLayout.setGravity(Gravity.CENTER);
        naviLayout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Main row: icon + distance + road name + bottom bar
        LinearLayout mainRow = new LinearLayout(getContext());
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER);
        // 水平居中：用 layout_gravity=center
        LinearLayout.LayoutParams mainRowParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        mainRowParams.gravity = Gravity.CENTER_HORIZONTAL;
        mainRow.setLayoutParams(mainRowParams);

        // Navi icon
        ivNaviIcon = new ImageView(getContext());
        ivNaviIcon.setBackgroundResource(R.drawable.blue_rounded_card);
        int iconS = (int)(50 * density);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconS, iconS);
        iconParams.setMarginEnd((int)(4 * density));
        ivNaviIcon.setLayoutParams(iconParams);
        ivNaviIcon.setPadding(0, 0, 0, 0);
        mainRow.addView(ivNaviIcon);

        // Text container
        LinearLayout textContainer = new LinearLayout(getContext());
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setPadding((int)(4 * density), 0, 0, 0);

        // Distance row: LED + unit
        LinearLayout distRow = new LinearLayout(getContext());
        distRow.setOrientation(LinearLayout.HORIZONTAL);
        distRow.setGravity(Gravity.BOTTOM);

        ledDistanceNumber = new LEDDigitView(getContext());
        ledDistanceNumber.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        distRow.addView(ledDistanceNumber);

        tvNavigationDistance = new TextView(getContext());
        tvNavigationDistance.setTextSize(18);
        tvNavigationDistance.setTypeface(null, Typeface.BOLD);
        distRow.addView(tvNavigationDistance);

        // Guide text (road name) — 放在距离行后面，同一行显示 "500米 进入 XX路"
        tvNavigationGuide = new TextView(getContext());
        tvNavigationGuide.setTextSize(18);
        tvNavigationGuide.setTypeface(null, Typeface.BOLD);
        tvNavigationGuide.setSingleLine(true);
        tvNavigationGuide.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvNavigationGuide.setVisibility(GONE);
        distRow.addView(tvNavigationGuide, new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams distRowParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        distRowParams.bottomMargin = (int)(10 * density);
        textContainer.addView(distRow, distRowParams);

        // Bottom bar: speed limit + traffic light + detail
        LinearLayout bottomBar = new LinearLayout(getContext());
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);

        tvSpeedLimit = new TextView(getContext());
        tvSpeedLimit.setBackgroundResource(R.drawable.circle_red_bg);
        tvSpeedLimit.setTextSize(12);
        tvSpeedLimit.setTypeface(null, Typeface.BOLD);
        tvSpeedLimit.setGravity(Gravity.CENTER);
        int limitS = (int)(22 * density);
        tvSpeedLimit.setLayoutParams(new LinearLayout.LayoutParams(limitS, limitS));
        tvSpeedLimit.setVisibility(GONE);
        bottomBar.addView(tvSpeedLimit);

        trafficLightContainer = new LinearLayout(getContext());
        trafficLightContainer.setVisibility(GONE);
        tvTrafficLight = new TextView(getContext());
        tvTrafficLight.setGravity(Gravity.CENTER);
        int lightS = (int)(20 * density);
        tvTrafficLight.setLayoutParams(new LinearLayout.LayoutParams(lightS, lightS));
        trafficLightContainer.addView(tvTrafficLight);
        bottomBar.addView(trafficLightContainer);

        tvNavigationDetail = new TextView(getContext());
        tvNavigationDetail.setTextSize(11);
        tvNavigationDetail.setTypeface(null, Typeface.BOLD);
        tvNavigationDetail.setPadding((int)(4 * density), 0, 0, 0);
        bottomBar.addView(tvNavigationDetail, new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));

        textContainer.addView(bottomBar, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        mainRow.addView(textContainer, new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));

        naviLayout.addView(mainRow);

        // Traffic bar (navi mode) — 最底部，贯通图标和文字区域
        naviTrafficBar = new TrafficBarView(getContext());
        LinearLayout.LayoutParams trafficParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, (int)(5 * density));
        trafficParams.setMargins((int)(10 * density), (int)(5 * density), (int)(10 * density), 0);
        naviTrafficBar.setLayoutParams(trafficParams);
        naviLayout.addView(naviTrafficBar);

        // Initially hidden
        naviLayout.setVisibility(GONE);
        addView(naviLayout);
    }

    // ==================== Mode switching ====================
    @Override
    public void onModeChanged(int mode) {
        if (this.currentMode != mode) {
            this.currentMode = mode;
            updateModeDisplay();
        }
    }

    private void updateModeDisplay() {
        if (currentMode == DataHub.MODE_NAVI) {
            cruiseLayout.setVisibility(GONE);
            naviLayout.setVisibility(VISIBLE);
        } else {
            naviLayout.setVisibility(GONE);
            cruiseLayout.setVisibility(VISIBLE);
        }
        refreshData();
    }

    @Override
    public void onNavigationUpdated() {
        refreshData();
    }

    private void refreshData() {
        if (currentMode == DataHub.MODE_NAVI) {
            updateNaviDisplay();
        } else {
            updateCruiseDisplay();
        }
    }

    // ==================== Navi Display (matches original FloatingWindowService) ====================
    private void updateNaviDisplay() {
        // Navi icon
        int iconRes = getNaviIconResource(dataHub.getNaviIcon());
        if (iconRes != 0) {
            ivNaviIcon.setImageResource(iconRes);
            ivNaviIcon.setVisibility(VISIBLE);
        } else {
            ivNaviIcon.setVisibility(GONE);
        }

        // Distance + Guide — matches original updateDistanceAndGuide() + setDistanceDisplay()
        String distance = dataHub.getSegRemainDis();
        String roadName = dataHub.getNextRoadName();
        updateDistanceAndGuide(distance, roadName);

        // Speed limit
        int limit = dataHub.getLimitedSpeed();
        if (limit > 0) {
            tvSpeedLimit.setText(String.valueOf(limit));
            tvSpeedLimit.setVisibility(VISIBLE);
        } else {
            tvSpeedLimit.setVisibility(GONE);
        }

        // Traffic light — matches original updateTrafficLightDisplay()
        updateTrafficLightDisplay(
                dataHub.getTrafficLightStatus(),
                dataHub.getTrafficLightDir(),
                dataHub.getGreenLightLastSecond(),
                dataHub.getRedLightCountDownSeconds());

        // Detail text — matches original updateDetailText()
        String routeRemainDis = dataHub.getRouteRemainDis();
        int remainTime = dataHub.getRemainTime();
        String etaText = dataHub.getEtaText();
        updateDetailText(routeRemainDis, remainTime, etaText);

        // TMC traffic bar (navi mode)
        int[] statuses = dataHub.getTmcStatusArray();
        int[] percents = dataHub.getTmcPercentArray();
        if (statuses != null && percents != null && statuses.length > 0) {
            naviTrafficBar.setTmcData(statuses, percents);
            // Set nav progress dot
            float navProgress = dataHub.getNavProgress();
            naviTrafficBar.setNavProgress(navProgress);
            naviTrafficBar.setVisibility(VISIBLE);
        } else {
            // 降级：无TMC数据时使用纯进度条
            updateNaviFallbackTrafficBar(routeRemainDis);
        }
    }

    /**
     * 距离+路名显示 — 匹配原项目 FloatingWindowService.updateDistanceAndGuide()
     * 矩形模式：距离行单独显示 "500米 进入"，路名在下一行
     */
    private void updateDistanceAndGuide(String distance, String roadName) {
        if (distance != null && !distance.isEmpty()) {
            // 矩形模式：分开 distance + "进入" 和 road name
            String distanceLine = distance + " 进入";
            setDistanceDisplay(distanceLine);
            if (tvNavigationGuide != null) {
                if (roadName != null && !roadName.isEmpty()) {
                    tvNavigationGuide.setText(roadName);
                    tvNavigationGuide.setVisibility(VISIBLE);
                } else {
                    tvNavigationGuide.setText("");
                    tvNavigationGuide.setVisibility(VISIBLE);
                }
            }
        } else {
            if (ledDistanceNumber != null) ledDistanceNumber.setText("");
            tvNavigationDistance.setText("");
            if (tvNavigationGuide != null) {
                tvNavigationGuide.setText(roadName != null ? roadName : "");
            }
        }
    }

    /**
     * 距离文字显示 — 匹配原项目 FloatingWindowService.setDistanceDisplay()
     * 用正则提取数字部分给LED，剩余部分给TextView（BOLD+颜色Span）
     */
    private void setDistanceDisplay(String text) {
        if (text == null || text.isEmpty()) {
            if (ledDistanceNumber != null) ledDistanceNumber.setText("");
            tvNavigationDistance.setText("");
            return;
        }

        // Regex extract leading number part
        Pattern pattern = Pattern.compile("^(\\d+(\\.\\d+)?)");
        Matcher matcher = pattern.matcher(text);
        int ledColor = 0xFFFF3030;           // 导航距离LED红色
        int unitColor = isNightMode ? 0xFFFFFFFF : 0xFF000000;  // "米 进入"保持原色

        if (matcher.find()) {
            String numberPart = matcher.group(1);
            String remainPart = text.substring(matcher.end());

            if (ledDistanceNumber != null) {
                ledDistanceNumber.setCustomColor(ledColor);
                ledDistanceNumber.setText(numberPart);
                ledDistanceNumber.setNightMode(isNightMode);
                ledDistanceNumber.setVisibility(VISIBLE);
            }
            if (!remainPart.isEmpty()) {
                SpannableString ss = new SpannableString(remainPart);
                ss.setSpan(new StyleSpan(Typeface.BOLD), 0, remainPart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new ForegroundColorSpan(unitColor), 0, remainPart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvNavigationDistance.setText(ss);
            } else {
                tvNavigationDistance.setText("");
            }
        } else {
            // No number (e.g. "现在") — show in LEDDigitView
            if (ledDistanceNumber != null) {
                ledDistanceNumber.setCustomColor(ledColor);
                ledDistanceNumber.setText(text);
                ledDistanceNumber.setNightMode(isNightMode);
                ledDistanceNumber.setVisibility(VISIBLE);
            }
            tvNavigationDistance.setText("");
        }
    }

    /**
     * 红绿灯显示 — 完全匹配原项目 FloatingWindowService.updateTrafficLightDisplay()
     * trafficLightStatus: 1=红灯, 4=绿灯, -1=过渡期
     * 统一使用 redLightCountDownSeconds 作为倒计时
     * 绿灯+有倒计时→绿圈, 红灯+有倒计时→红圈, 绿灯倒计时=0或过渡期→黄圈, 红灯倒计时=0→隐藏
     */
    private void updateTrafficLightDisplay(int trafficLightStatus, int trafficLightDir,
                                            int greenLightLastSecond, int redLightCountDownSeconds) {
        if (trafficLightStatus == 1 && redLightCountDownSeconds <= 0) {
            // 红灯倒计时归零，直接变绿灯，隐藏
            if (trafficLightContainer != null) trafficLightContainer.setVisibility(GONE);
            return;
        }

        if (trafficLightStatus == -1 && redLightCountDownSeconds <= 0) {
            // 过渡期结束，隐藏
            if (trafficLightContainer != null) trafficLightContainer.setVisibility(GONE);
            return;
        }

        // 有红绿灯数据，显示
        if (trafficLightContainer != null) trafficLightContainer.setVisibility(VISIBLE);

        if (trafficLightStatus == 4 && redLightCountDownSeconds > 0) {
            // 绿灯 + 有倒计时 → 绿圈
            if (tvTrafficLight != null) {
                tvTrafficLight.setBackgroundResource(R.drawable.green_circle);
                tvTrafficLight.setTextColor(0xFFFFFFFF);
                tvTrafficLight.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
                tvTrafficLight.setText(String.valueOf(redLightCountDownSeconds));
            }
        } else if (trafficLightStatus == 1 && redLightCountDownSeconds > 0) {
            // 红灯 + 有倒计时 → 红圈
            if (tvTrafficLight != null) {
                tvTrafficLight.setBackgroundResource(R.drawable.red_circle);
                tvTrafficLight.setTextColor(0xFFFFFFFF);
                tvTrafficLight.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
                tvTrafficLight.setText(String.valueOf(redLightCountDownSeconds));
            }
        } else {
            // 绿灯倒计时=0 或 过渡期(status=-1) → 黄圈
            if (tvTrafficLight != null) {
                tvTrafficLight.setBackgroundResource(R.drawable.yellow_circle);
                tvTrafficLight.setTextColor(0xFFFFFFFF);
                tvTrafficLight.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 7);
                if (redLightCountDownSeconds > 0) {
                    tvTrafficLight.setText(String.valueOf(redLightCountDownSeconds));
                } else {
                    tvTrafficLight.setText("注意");
                }
            }
        }
    }

    /**
     * 详情文本 — 匹配原项目 FloatingWindowService.updateDetailText()
     * 格式: routeRemainDis | remainingTime | etaText
     */
    private void updateDetailText(String routeRemainDis, int remainTimeSeconds, String etaText) {
        StringBuilder detailBuilder = new StringBuilder();
        if (routeRemainDis != null && !routeRemainDis.isEmpty()) {
            detailBuilder.append(routeRemainDis).append(" | ");
        }
        if (remainTimeSeconds > 0) {
            String timeStr = formatTime(remainTimeSeconds);
            detailBuilder.append(timeStr).append(" | ");
        }
        if (etaText != null && !etaText.isEmpty()) {
            detailBuilder.append(etaText);
        }
        if (detailBuilder.length() > 0) {
            tvNavigationDetail.setText(setNumbersColor(detailBuilder.toString()));
        } else {
            tvNavigationDetail.setText("");
        }
    }

    private String formatTime(int seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            return (seconds / 60) + "分钟";
        } else {
            int hours = seconds / 3600;
            int minutes = (seconds % 3600) / 60;
            return hours + "小时" + minutes + "分钟";
        }
    }

    /**
     * 给文本中的数字上色 — 匹配原项目 FloatingWindowService.setNumbersRed()
     */
    private SpannableString setNumbersColor(String text) {
        SpannableString spannableString = new SpannableString(text);
        int numberColor = isNightMode ? 0xFFFFFFFF : 0xFF000000;
        Pattern pattern = Pattern.compile("\\d+(:\\d+)?");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            spannableString.setSpan(
                    new ForegroundColorSpan(numberColor),
                    matcher.start(), matcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return spannableString;
    }

    // ==================== Cruise Display ====================
    private void updateCruiseDisplay() {
        // Speed
        tvCruiseSpeed.setText(String.valueOf(dataHub.getCurrentSpeed()));
        tvCruiseUnit.setText("km");

        // Speed limit
        int limit = dataHub.getEffectiveCruiseLimitedSpeed();
        if (limit > 0) {
            tvCruiseSpeedLimit.setText(String.valueOf(limit));
            tvCruiseSpeedLimit.setVisibility(VISIBLE);
        } else {
            tvCruiseSpeedLimit.setVisibility(GONE);
        }

        // Road name
        tvCruiseRoadName.setText(dataHub.getCruiseRoadName());

        // Cruise traffic lights
        String lightsData = dataHub.getCruiseLightsData();
        if (lightsData != null) {
            updateCruiseTrafficLights(lightsData);
        } else {
            cruiseLightsContainer.setVisibility(GONE);
            cruiseLightsContainer.removeAllViews();
        }

        // 原项目巡航模式没有TrafficBar
    }

    private void updateCruiseTrafficLights(String lightsDataStr) {
        try {
            JSONArray lightsArray = new JSONArray(lightsDataStr);
            int lightCount = lightsArray.length();
            if (lightCount == 0) {
                cruiseLightsContainer.setVisibility(GONE);
                cruiseLightsContainer.removeAllViews();
                return;
            }

            float density = getResources().getDisplayMetrics().density;
            int childCount = cruiseLightsContainer.getChildCount();
            if (lightCount != childCount) {
                cruiseLightsContainer.removeAllViews();
                for (int i = 0; i < lightCount; i++) {
                    try {
                        JSONObject lightObj = lightsArray.getJSONObject(i);
                        View lightView = createCruiseLightItem(lightObj, density);
                        cruiseLightsContainer.addView(lightView);
                    } catch (Exception ignored) {}
                }
            } else {
                for (int i = 0; i < lightCount; i++) {
                    try {
                        updateCruiseLightItem(cruiseLightsContainer.getChildAt(i),
                                lightsArray.getJSONObject(i));
                    } catch (Exception ignored) {}
                }
            }
            cruiseLightsContainer.setVisibility(VISIBLE);
        } catch (Exception e) {
            cruiseLightsContainer.setVisibility(GONE);
        }
    }

    private View createCruiseLightItem(JSONObject lightObj, float density) throws Exception {
        LinearLayout capsule = new LinearLayout(getContext());
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setGravity(Gravity.CENTER_VERTICAL);
        capsule.setBackgroundResource(R.drawable.bg_cruise_light_capsule);
        int padStart = (int)(4 * density);
        int padEnd = (int)(8 * density);
        capsule.setPadding(padStart, 0, padEnd, 0);
        LinearLayout.LayoutParams capsuleParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        capsuleParams.setMargins(0, 0, (int)(5 * density), 0);
        capsule.setLayoutParams(capsuleParams);

        // 灯色圆 + 方向箭头叠加 (FrameLayout 36dp × 36dp)
        int frameSize = (int)(36 * density);
        FrameLayout arrowFrame = new FrameLayout(getContext());
        arrowFrame.setLayoutParams(new LinearLayout.LayoutParams(frameSize, frameSize));

        // 灯色圆 (match_parent → 36dp)
        ImageView ivIcon = new ImageView(getContext());
        ivIcon.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ivIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivIcon.setId(android.R.id.icon);

        // 方向箭头 (20dp × 20dp, 居中, padding 2dp)
        ImageView ivArrow = new ImageView(getContext());
        int arrowSize = (int)(20 * density);
        FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(arrowSize, arrowSize, Gravity.CENTER);
        ivArrow.setLayoutParams(arrowParams);
        ivArrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int arrowPad = (int)(2 * density);
        ivArrow.setPadding(arrowPad, arrowPad, arrowPad, arrowPad);

        // 箭头在下层，灯色圆在上层 → 先add箭头，再add灯色圆
        // 但原项目XML中 ivLightIcon 在前（底层），ivLightArrow 在后（顶层）
        // 所以箭头应该在灯色圆之上 → 先add灯色圆，再add箭头
        arrowFrame.addView(ivIcon);    // 底层：灯色圆
        arrowFrame.addView(ivArrow);   // 顶层：方向箭头

        capsule.addView(arrowFrame);

        // 倒计时 (22sp, bold, #FFFFFF, includeFontPadding=false, marginStart=3dp)
        TextView tvTime = new TextView(getContext());
        tvTime.setTextSize(22);
        tvTime.setTypeface(null, Typeface.BOLD);
        tvTime.setTextColor(0xFFFFFFFF);
        tvTime.setIncludeFontPadding(false);
        tvTime.setMaxLines(1);
        tvTime.setId(android.R.id.text1);
        tvTime.setPadding((int)(3 * density), 0, 0, 0);
        capsule.addView(tvTime);

        updateCruiseLightItem(capsule, lightObj);
        return capsule;
    }

    private void updateCruiseLightItem(View lightView, JSONObject lightObj) throws Exception {
        int status = lightObj.optInt("status", -1);
        int countdown = lightObj.optInt("countdown", 0);
        int dir = lightObj.optInt("dir", 2);

        // Find views by traversing
        ImageView ivIcon = lightView.findViewById(android.R.id.icon);
        ImageView ivArrow = null;
        if (lightView instanceof LinearLayout) {
            LinearLayout capsule = (LinearLayout) lightView;
            if (capsule.getChildCount() > 0 && capsule.getChildAt(0) instanceof FrameLayout) {
                FrameLayout frame = (FrameLayout) capsule.getChildAt(0);
                // ivIcon是第0个(底层)，ivArrow是第1个(顶层)
                if (frame.getChildCount() >= 2 && frame.getChildAt(1) instanceof ImageView) {
                    ivArrow = (ImageView) frame.getChildAt(1);
                }
            }
        }

        TextView tvTime = lightView.findViewById(android.R.id.text1);

        if (ivIcon != null) {
            if (status == 1) ivIcon.setImageResource(R.drawable.cruise_green_circle);
            else if (status == 0) ivIcon.setImageResource(R.drawable.cruise_red_circle);
            else ivIcon.setImageResource(R.drawable.cruise_yellow_circle);
        }
        if (ivArrow != null) {
            if (dir == 1) ivArrow.setImageResource(R.drawable.ic_cruise_left);
            else if (dir == 3) ivArrow.setImageResource(R.drawable.ic_cruise_right);
            else ivArrow.setImageResource(R.drawable.ic_cruise_straight);
        }
        if (tvTime != null) {
            tvTime.setText(String.valueOf(countdown));
        }
    }

    // ==================== Theme ====================
    public void setNightMode(boolean isNight) {
        this.isNightMode = isNight;
        updateColors();
        // Re-apply distance display to update span colors
        if (currentMode == DataHub.MODE_NAVI) {
            String distance = dataHub.getSegRemainDis();
            String roadName = dataHub.getNextRoadName();
            updateDistanceAndGuide(distance, roadName);
            // Re-apply detail text for color update
            String routeRemainDis = dataHub.getRouteRemainDis();
            int remainTime = dataHub.getRemainTime();
            String etaText = dataHub.getEtaText();
            updateDetailText(routeRemainDis, remainTime, etaText);
        }
    }

    private void updateColors() {
        // Match original 高德辅助导航 color scheme exactly
        // Cruise mode colors
        int speedColor  = isNightMode ? 0xFFFFFFFF : 0xFF000000;
        int unitColor   = isNightMode ? 0xFFFFFFFF : 0xFF666666;
        int roadColor   = isNightMode ? 0xFFFFFFFF : 0xFF000000;
        // Navi mode colors
        int guideColor  = isNightMode ? 0xFFFFFFFF : 0xFF000000;
        int detailColor = isNightMode ? 0xFFFFFFFF : 0xFF000000;

        // Cruise layout
        if (tvCruiseSpeed != null) tvCruiseSpeed.setTextColor(speedColor);
        if (tvCruiseUnit != null) tvCruiseUnit.setTextColor(unitColor);
        if (tvCruiseRoadName != null) tvCruiseRoadName.setTextColor(roadColor);
        // Navi layout
        if (tvNavigationGuide != null) tvNavigationGuide.setTextColor(guideColor);
        if (tvNavigationDetail != null) tvNavigationDetail.setTextColor(detailColor);
        // Speed limit: always black on white circle background
        if (tvSpeedLimit != null) tvSpeedLimit.setTextColor(0xFF000000);
        // Traffic light: white text on colored circle background
        if (tvTrafficLight != null) tvTrafficLight.setTextColor(0xFFFFFFFF);
        // Divider
        if (cruiseDivider != null) cruiseDivider.setBackgroundColor(
                isNightMode ? 0x33FFFFFF : 0x33999999);
        // Sub-views
        if (ledDistanceNumber != null) ledDistanceNumber.setNightMode(isNightMode);
        if (naviTrafficBar != null) naviTrafficBar.setNightMode(isNightMode);

        // Background: transparent — show grid background underneath
    }

    // ==================== Traffic bar fallback ====================

    private void updateNaviFallbackTrafficBar(String routeRemainDisStr) {
        updateFallbackTrafficBar(naviTrafficBar, routeRemainDisStr);
    }

    private void updateFallbackTrafficBar(TrafficBarView bar, String routeRemainDisStr) {
        float routeTotalDistance = dataHub.getRouteTotalDistance();
        if (routeTotalDistance <= 0 || routeRemainDisStr == null || routeRemainDisStr.isEmpty()) {
            bar.setVisibility(GONE);
            return;
        }
        float remainKm = DataHub.parseDistanceToKm(routeRemainDisStr);
        if (remainKm < 0) {
            bar.setVisibility(GONE);
            return;
        }
        float progress = 1f - (remainKm / routeTotalDistance);
        if (progress < 0) progress = 0;
        if (progress > 1) progress = 1;
        bar.setFallbackProgress(progress);
        bar.setNavProgress(progress);
        bar.setVisibility(VISIBLE);
    }

    // ==================== Navi icon resource mapping ====================
    private int getNaviIconResource(int iconId) {
        try {
            return getContext().getResources().getIdentifier(
                    "navi_icon_" + iconId, "drawable", getContext().getPackageName());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 测试用：直接设置完整导航信息，并切换到导航模式 */
    public void setSimNaviInfo(int iconId, String segRemainDis, String nextRoadName,
                               int speedLimit, String routeRemainDis, int remainTimeSec, String etaText) {
        if (currentMode != DataHub.MODE_NAVI) {
            currentMode = DataHub.MODE_NAVI;
            updateModeDisplay();
        }
        // 图标
        int iconRes = getNaviIconResource(iconId);
        if (iconRes != 0) {
            ivNaviIcon.setImageResource(iconRes);
            ivNaviIcon.setVisibility(VISIBLE);
        } else {
            ivNaviIcon.setVisibility(GONE);
        }
        // 距离+路名
        updateDistanceAndGuide(segRemainDis, nextRoadName);
        // 限速
        if (speedLimit > 0) {
            tvSpeedLimit.setText(String.valueOf(speedLimit));
            tvSpeedLimit.setVisibility(VISIBLE);
        } else {
            tvSpeedLimit.setVisibility(GONE);
        }
        // 详情：剩余距离|时间|ETA
        updateDetailText(routeRemainDis, remainTimeSec, etaText);
        // 降级进度条
        updateNaviFallbackTrafficBar(routeRemainDis);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (dataHub != null) {
            dataHub.removeNavigationListener(this);
            dataHub.removeModeListener(this);
        }
    }
}
