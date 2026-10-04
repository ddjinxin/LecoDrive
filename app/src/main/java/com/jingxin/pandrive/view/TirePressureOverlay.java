package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;

import com.jingxin.pandrive.data.DataHub;

/**
 * 首页3D车外围胎压数值 Overlay（方案B：道路梯形锚点定位）
 *
 * 不依赖 GL 线程坐标，锚点用与 LaneView 相同的道路透视梯形几何计算：
 * 左右车道边缘从视口底部(near)收拢到顶部(far)，y 处道路半宽
 * = (farWidth + (nearWidth-farWidth)*(1-y/h)) / 2。
 *
 * 前排(车头方向, 远处)两个值、后排(车尾方向, 近处)两个值，各压进道路梯形内，
 * 视觉上恰好落在 3D 车外围的轮胎位。车恒在道路中央，故不受车型/旋转/缩放影响。
 *
 * 纯数值 + 单位 + 状态色 + 透视文字（前排小、后排大），换算/状态色逻辑照抄林肯页。
 */
public class TirePressureOverlay extends View implements DataHub.OnVehicleStatusListener {

    /** 前后排纵向位置（相对高度比例，与 LaneView 透视对应） */
    private static final float FRONT_Y = 0.30f;   // 车头方向（远处）
    private static final float REAR_Y  = 0.70f;   // 车尾方向（近处）
    /** 前后排在道路内的横向偏移（相对该 y 处道路半宽的比例，1.0=贴边） */
    private static final float FRONT_X_FRACTION = 0.60f;
    private static final float REAR_X_FRACTION  = 0.68f;
    /** 前排文字透视缩放（远处小） */
    private static final float FRONT_TEXT_SCALE = 0.6f;

    // ===== 双日/夜色板（照抄 TireCircleView 双色机制） =====
    private static final int[] COLOR_OK      = {0xFF0077C2, 0xFF00FF9D};
    private static final int[] COLOR_INVALID = {0xFF8A97A5, 0xFF5C7A99};
    private static final int[] COLOR_WARN    = {0xFFC77800, 0xFFFFB020};
    private static final int[] COLOR_ALERT   = {0xFFD32F2F, 0xFFFF5A5A};

    private boolean isNightMode = false;
    private int modeIdx = 0;

    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] tirePressure = {-1f, -1f, -1f, -1f};
    private int[] tireState = {-1, -1, -1, -1};
    private String tireUnit = "";

    private float density = 1f;

    // ===== 车头偏航角（导航/巡航车头左右偏转，4个胎压值跟随旋转） =====
    private float yawDeg = 0f;

    // ===== 车辆动画期间的抖动动画 =====
    private static final float SHAKE_AMP_DP = 1.5f;   // 抖动幅度 dp
    private static final long SHAKE_PERIOD_MS = 400L; // 抖动周期
    private ValueAnimator shakeAnim;
    private float shakePhase = 0f;   // 0→1 正弦波相位
    private boolean shaking = false;

    public TirePressureOverlay(Context context) { this(context, null); }
    public TirePressureOverlay(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public TirePressureOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        density = getResources().getDisplayMetrics().density;
        valuePaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        unitPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setNightMode(boolean night) {
        if (isNightMode == night) return;
        isNightMode = night;
        modeIdx = night ? 1 : 0;
        invalidate();
    }

    // ==================== 数据驱动 ====================

    @Override
    public void onVehicleStatusChanged(DataHub.VehicleStatus s) {
        boolean changed = false;
        for (int i = 0; i < 4; i++) {
            if (tirePressure[i] != s.tirePressure[i] || tireState[i] != s.tireState[i]) {
                tirePressure[i] = s.tirePressure[i];
                tireState[i] = s.tireState[i];
                changed = true;
            }
        }
        String u = s.tireUnit == null ? "" : s.tireUnit;
        if (!tireUnit.equals(u)) { tireUnit = u; changed = true; }
        if (changed) invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        DataHub.getInstance(getContext()).addVehicleStatusListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        DataHub.getInstance(getContext()).removeVehicleStatusListener(this);
        stopShake();
        super.onDetachedFromWindow();
    }

    /** 设置车头偏航角（正值=左偏，负值=右偏），4个胎压值绕道路中心跟随旋转 */
    public void setYaw(float yaw) {
        if (Math.abs(yaw - yawDeg) < 0.5f) return;
        yawDeg = yaw;
        invalidate();
    }

    /** 车辆动画状态切换：true 开始抖动，false 停止 */
    public void setShake(boolean shake) {
        if (shaking == shake) return;
        shaking = shake;
        if (shake) startShake(); else stopShake();
    }

    private void startShake() {
        if (shakeAnim != null && shakeAnim.isRunning()) return;
        shakeAnim = ValueAnimator.ofFloat(0f, 1f);
        shakeAnim.setDuration(SHAKE_PERIOD_MS);
        shakeAnim.setRepeatCount(ValueAnimator.INFINITE);
        shakeAnim.setInterpolator(new LinearInterpolator());
        shakeAnim.addUpdateListener(an -> {
            shakePhase = (float) an.getAnimatedValue();
            invalidate();
        });
        shakeAnim.start();
    }

    private void stopShake() {
        if (shakeAnim != null) {
            shakeAnim.cancel();
            shakeAnim = null;
        }
        shakePhase = 0f;
        invalidate();
    }

    // ==================== 绘制 ====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float centerX = w / 2f;
        float nearWidth = w * 0.95f;
        float farWidth = w * 0.15f;

        // 计算前后排在 y 处的道路半宽
        float frontHalf = roadHalf(nearWidth, farWidth, FRONT_Y);
        float rearHalf = roadHalf(nearWidth, farWidth, REAR_Y);

        // 4 个锚点原始坐标
        float[][] pts = {
            {centerX - frontHalf * FRONT_X_FRACTION, FRONT_Y * h},  // 左前
            {centerX + frontHalf * FRONT_X_FRACTION, FRONT_Y * h},  // 右前
            {centerX - rearHalf  * REAR_X_FRACTION,  REAR_Y  * h},  // 左后
            {centerX + rearHalf  * REAR_X_FRACTION,  REAR_Y  * h},  // 右后
        };

        // 偏航角绕道路中心（centerX, h/2）做 2D 旋转，让胎压值跟随车头偏转
        // Canvas Y轴朝下，+角度是顺时针；GL车头左转(naviCurrentRotY>0)视觉是逆时针，
        // 故取 -yawDeg 使胎压值与车头同向旋转
        if (Math.abs(yawDeg) > 0.5f) {
            float pivotX = centerX;
            float pivotY = h * 0.5f;
            double rad = Math.toRadians(-yawDeg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            for (int i = 0; i < 4; i++) {
                float dx = pts[i][0] - pivotX;
                float dy = pts[i][1] - pivotY;
                pts[i][0] = pivotX + dx * cos - dy * sin;
                pts[i][1] = pivotY + dx * sin + dy * cos;
            }
        }

        drawSlot(canvas, 0, pts[0][0], pts[0][1], true);
        drawSlot(canvas, 1, pts[1][0], pts[1][1], true);
        drawSlot(canvas, 2, pts[2][0], pts[2][1], false);
        drawSlot(canvas, 3, pts[3][0], pts[3][1], false);
    }

    private float roadHalf(float nearWidth, float farWidth, float yFraction) {
        // y=0(顶部)是 far(窄)，y=h(底部)是 near(宽)；半宽随 y 从顶部向底部线性增大（上窄下宽）
        return (farWidth + (nearWidth - farWidth) * yFraction) / 2f;
    }

    private void drawSlot(Canvas canvas, int idx, float cx, float cy, boolean isFront) {
        float p = tirePressure[idx];
        int state = tireState[idx];
        String unit = tireUnit.isEmpty() ? "--" : tireUnit;

        int color; boolean alert;
        if (p < 0) {
            drawLabel(canvas, cx, cy, "--", unit, colorInvalid(), isFront);
            return;
        } else if (p >= 65533f) {
            color = colorInvalid(); alert = false;
        } else if (state > 250) {
            color = colorAlert(); alert = true;
        } else if (state > 150) {
            color = colorWarn(); alert = false;
        } else if (state > 75) {
            color = colorWarn(); alert = false;
        } else {
            color = colorOk(); alert = false;
        }
        drawLabel(canvas, cx, cy, pressureNum(p, unit), unit, color, isFront);
    }

    private void drawLabel(Canvas canvas, float cx, float cy, String value, String unit, int color, boolean isFront) {
        float scale = isFront ? FRONT_TEXT_SCALE : 1.0f;
        float d = density;
        float valSize = 15f * d * scale * 1.3f;   // 字号整体放大30%
        float unitSize = valSize * 0.4f;

        // 日夜均用白色文字；夜间保留状态色 LED 发光
        valuePaint.setColor(0xFFFFFFFF);
        valuePaint.setTextSize(valSize);
        unitPaint.setColor(0xFFFFFFFF);
        unitPaint.setTextSize(unitSize);
        if (isNightMode) {
            valuePaint.setShadowLayer(4f * d, 0, 0, color);
            unitPaint.setShadowLayer(2f * d, 0, 0, color);
        } else {
            valuePaint.clearShadowLayer();
            unitPaint.clearShadowLayer();
        }

        // 车辆动画期间抖动：正弦波 × 振幅（水平+垂直，垂直幅度减半）
        float shakeX = 0f, shakeY = 0f;
        if (shaking) {
            double wave = Math.sin(shakePhase * 2f * Math.PI);
            shakeX = (float) wave * SHAKE_AMP_DP * d;
            shakeY = (float) Math.cos(shakePhase * 2f * Math.PI) * SHAKE_AMP_DP * d * 0.5f;
        }

        float valH = valuePaint.getFontMetrics().bottom - valuePaint.getFontMetrics().top;
        float unitH = unitPaint.getFontMetrics().bottom - unitPaint.getFontMetrics().top;
        float totalH = valH + unitH + 2f * d;
        float topY = cy - totalH / 2f;
        canvas.drawText(value, cx + shakeX, topY - valuePaint.getFontMetrics().top + shakeY, valuePaint);
        canvas.drawText(unit, cx + shakeX, topY + valH + 2f * d - unitPaint.getFontMetrics().top + shakeY, unitPaint);
    }

    // ==================== 颜色 / 换算（照抄林肯页） ====================

    public int colorOk() { return COLOR_OK[modeIdx]; }
    public int colorInvalid() { return COLOR_INVALID[modeIdx]; }
    public int colorWarn() { return COLOR_WARN[modeIdx]; }
    public int colorAlert() { return COLOR_ALERT[modeIdx]; }

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
}