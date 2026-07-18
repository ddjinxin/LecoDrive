package com.jingxin.pandrive.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/**
 * 速度仪表盘自定义View
 * 
 * 布局结构：
 * ┌──────────────────┐
 * │    弧形表盘       │  ← gaugeArea (正方形区域)
 * │  刻度+数字+指针   │
 * │                  │
 * ├──────────────────┤
 * │    120           │  ← 速度数字 (在弧线开口下方)
 * │    km/h          │  ← 单位
 * │    Ⓜ60          │  ← 限速标记
 * └──────────────────┘
 * 
 * 表盘参数：
 * - 范围: 0 ~ 240 km/h
 * - 弧度: 270° (底部90°开口)
 * - 起始角度: 135° (Canvas坐标系, 0 km/h位置)
 * - 结束角度: 405° (即45°, 240 km/h位置)
 */
public class SpeedometerView extends View {

    private final GaugeDrawHelper gaugeHelper = new GaugeDrawHelper();

    // 仪表盘参数
    private static final int MAX_SPEED = 240;
    private static final int MAJOR_TICK_INTERVAL = 20;
    private static final int MINOR_TICK_INTERVAL = 10;
    private static final float START_ANGLE = 135f;
    private static final float SWEEP_ANGLE = 270f;

    // 指针动画时长
    private static final long ANIM_DURATION = 300L;

    // ====== 配色常量 ======
    // 夜间模式
    private static final int COLOR_NIGHT_ARC_BG = 0xFF8899AA;
    private static final int COLOR_NIGHT_ARC_BG_END = 0xFF708090;
    private static final int COLOR_NIGHT_ARC_HIGHLIGHT = 0xFFBBC8D4;
    private static final int COLOR_NIGHT_ARC_SHADOW = 0xFF4A5A6A;
    private static final int COLOR_NIGHT_OUTER_RING = 0xFF99AABB;
    private static final int COLOR_NIGHT_MAJOR_TICK = 0xFFD0D0D0;
    private static final int COLOR_NIGHT_MINOR_TICK = 0xFF707070;
    private static final int COLOR_NIGHT_NEEDLE = 0xFFFF6B35;
    private static final int COLOR_NIGHT_NEEDLE_CENTER = 0xFF4A4A4A;
    private static final int COLOR_NIGHT_NEEDLE_HIGHLIGHT = 0xFF6A6A6A;
    private static final int COLOR_NIGHT_UNIT_TEXT = 0xFF999999;
    private static final int COLOR_NIGHT_LED_OFF = 0xFF1A2030;
    private static final int COLOR_NIGHT_LED_ON = 0xFF00E5A0;
    private static final int COLOR_NIGHT_LED_ON_GLOW = 0x6000E5A0;
    private static final int COLOR_NIGHT_DANGER = 0xFFFF4444;
    private static final int COLOR_NIGHT_DANGER_GLOW = 0x60FF4444;
    private static final int COLOR_NIGHT_EMBOSS_HIGHLIGHT = 0xFF667788;
    private static final int COLOR_NIGHT_BOTTOM_TEXT = 0xFF667788;
    private static final int COLOR_NIGHT_INACTIVE_BAR = 0xFF4A5A6A;
    private static final int COLOR_NIGHT_LED_DIGIT_GLOW = 0xFF009966;
    private static final int COLOR_NIGHT_ACTIVE_BAR_GLOW = 0x5000E5A0;
    private static final int COLOR_NIGHT_NEEDLE_GLOW = 0x4000E5A0;
    private static final int COLOR_NIGHT_LED_ON_FADE = 0x0000E5A0;

    // 日间模式
    private static final int COLOR_DAY_ARC_BG = 0xFF556070;
    private static final int COLOR_DAY_ARC_BG_END = 0xFF445060;
    private static final int COLOR_DAY_ARC_HIGHLIGHT = 0xFFA0ADB8;
    private static final int COLOR_DAY_ARC_SHADOW = 0xFF2A3540;
    private static final int COLOR_DAY_OUTER_RING = 0xFF8899AA;
    private static final int COLOR_DAY_MAJOR_TICK = 0xFF333333;
    private static final int COLOR_DAY_MINOR_TICK = 0xFF888888;
    private static final int COLOR_DAY_NEEDLE = 0xFFD32F2F;
    private static final int COLOR_DAY_NEEDLE_CENTER = 0xFF9E9E9E;
    private static final int COLOR_DAY_NEEDLE_HIGHLIGHT = 0xFFBDBDBD;
    private static final int COLOR_DAY_SPEED_TEXT = 0xFF222222;
    private static final int COLOR_DAY_UNIT_TEXT = 0xFF888888;
    private static final int COLOR_DAY_LED_OFF = 0xFF3A4050;
    private static final int COLOR_DAY_LED_ON = 0xFF00D4E8;
    private static final int COLOR_DAY_LED_ON_GLOW = 0x3000D4E8;
    private static final int COLOR_DAY_DANGER = 0xFFD32F2F;
    private static final int COLOR_DAY_DANGER_GLOW = 0x30D32F2F;
    private static final int COLOR_DAY_EMBOSS_SHADOW = 0x66000000;
    private static final int COLOR_DAY_EMBOSS_HIGHLIGHT = 0x33FFFFFF;
    private static final int COLOR_DAY_BOTTOM_TEXT = 0xFF888888;
    private static final int COLOR_DAY_INACTIVE_BAR = 0xFF5A6A7A;
    private static final int COLOR_DAY_LED_DIGIT_GLOW = 0xFF00B8D4;
    private static final int COLOR_DAY_ACTIVE_BAR_GLOW = 0x5000D4E8;
    private static final int COLOR_DAY_NEEDLE_GLOW = 0x4000D4E8;
    private static final int COLOR_DAY_LED_ON_FADE = 0x0000D4E8;

    // 共用色
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_BLACK = 0xFF000000;
    private static final int COLOR_OVERRUN_RED = 0xFFFF3333;
    private static final int COLOR_OVERRUN_BAR_GLOW = 0x50FF3333;
    private static final int COLOR_OVERRUN_NEEDLE_GLOW = 0x40FF3333;
    private static final int COLOR_OVERSPEED_LED_GLOW = 0xFFFF0000;
    private static final int COLOR_OVERSPEED_TEXT_GLOW = 0xFFCC0000;
    private static final int COLOR_DANGER_FADE = 0x00FF4444;

    // ====== 尺寸比例常量 ======
    // 布局
    private static final float CONTENT_TOP_RATIO = 0.08f;
    private static final float CENTER_Y_RATIO = 0.47f;
    private static final float ARC_RADIUS_RATIO = 0.36f;
    private static final float ARC_STROKE_RATIO = 0.0486f;
    private static final float SHIMMER_OFFSET_RATIO = 0.008f;

    // 金属风格(风格0)
    private static final float SPEED_AREA_HALF_WIDTH_RATIO = 0.3f;
    private static final float SPEED_AREA_TOP_RATIO = 0.1f;
    private static final float SPEED_CENTER_TOP_RATIO = 0.12f;
    private static final float SPEED_CENTER_BOTTOM_RATIO = 0.08f;
    private static final float TICK_NUMBER_OFFSET_RATIO = 0.07f;
    private static final float TICK_TEXT_SIZE_RATIO = 0.045f;
    private static final float EMBOSS_OFFSET_RATIO = 0.004f;
    private static final float SMALL_EMBOSS_RATIO = 0.003f;
    private static final float NEEDLE_LENGTH_OFFSET_RATIO = 0.06f;
    private static final float NEEDLE_TAIL_RATIO = 0.04f;
    private static final float NEEDLE_HALF_WIDTH_RATIO = 0.008f;
    private static final float UNIT_TEXT_SIZE_RATIO = 0.028f;
    private static final float LED_DIGIT_HEIGHT_RATIO = 0.07f;
    private static final float LED_GAP_RATIO = 0.008f;
    private static final float LIMIT_INDICATOR_RADIUS_RATIO = 0.034f;
    private static final float LIMIT_TEXT_SIZE_RATIO = 0.036f;
    private static final float LIMIT_NUMBER_RADIUS_RATIO = 0.045f;

    // 极简风格(风格1)
    private static final float MINIMAL_RING1_OFFSET_RATIO = 0.02f;
    private static final float MINIMAL_GLOW_OFFSET_RATIO = 0.006f;
    private static final float MINIMAL_LINE_WIDTH_RATIO = 0.005f;
    private static final float MINIMAL_GLOW_STROKE_RATIO = 0.012f;
    private static final float MINIMAL_THIN_LINE_RATIO = 0.004f;
    private static final float MINIMAL_BAR_WIDTH_RATIO = 0.035f;
    private static final float MINIMAL_TICK_TEXT_RATIO = 0.035f;
    private static final float MINIMAL_SPEED_TEXT_RATIO = 0.12f;
    private static final float MINIMAL_BOTTOM_TEXT_RATIO = 0.035f;
    private static final float MINIMAL_BAR_OFFSET_RATIO = 0.08f;
    private static final float MINIMAL_BAR_LENGTH_RATIO = 0.05f;
    private static final float MINIMAL_NUMBER_GAP_RATIO = 0.015f;
    private static final float MINIMAL_NEEDLE_GAP_RATIO = 0.005f;
    private static final float MINIMAL_NEEDLE_LENGTH_RATIO = 0.06f;
    private static final float MINIMAL_NEEDLE_BASE_RATIO = 0.008f;
    private static final float MINIMAL_INNER_ARC_GAP_RATIO = 0.008f;
    private static final float MINIMAL_SHADOW_RATIO = 0.012f;
    private static final float MINIMAL_INDICATOR_RADIUS_RATIO = 0.034f;
    private static final float MINIMAL_LIMIT_TEXT_RATIO = 0.036f;
    private static final float MINIMAL_SPEED_OFFSET_RATIO = 0.02f;

    // 当前状态
    private int currentSpeed = 0;
    private int targetSpeed = 0;
    private int limitedSpeed = -1;
    private boolean isNightMode = false;
    private int style = 0; // 0=科幻金属, 1=极简科技

    // 动画
    private ValueAnimator needleAnimator;

    // 流光线动画
    private ValueAnimator shimmerAnimator;
    private float shimmerPhase = 0f; // 0~1 循环

    // Paint 对象 - 风格0
    private Paint arcPaint;          // 主弧线（金属渐变）
    private Paint ledOffPaint;       // LED未激活
    private Paint ledOnPaint;        // LED激活
    private Paint ledGlowPaint;      // LED光晕
    private Paint ledDangerPaint;    // LED超速
    private Paint ledDangerGlowPaint;// LED超速光晕
    private Paint tickNumberPaint;
    private Paint needlePaint;
    private Paint needleCenterPaint;
    private Paint speedTextPaint;
    private Paint unitTextPaint;
    private Paint limitRingPaint;
    private Paint limitTextPaint;
    private Paint capPaint;          // 端盖金属渐变
    private Paint tickGlowPaint;     // LED刻度光晕（复用Paint，每帧setShader）

    // Paint 对象 - 风格1
    private Paint ring1Paint;             // 第一圈细线
    private Paint ring1GlowPaint;         // 第一圈外发光
    private Paint ring2Paint;             // 第二圈弧线
    private Paint minimalBarPaint;        // 色块
    private Paint minimalActiveBarPaint;  // 色块激活部分
    private Paint minimalInnerArcPaint;   // 已激活区间内侧弧
    private Paint minimalTickNumberPaint; // 刻度数字
    private Paint minimalNeedlePaint;     // 极简指针
    private Paint minimalSpeedPaint;      // 速度数字
    private Paint minimalBottomPaint;     // 底部km/h
    private Paint minimalLimitRingPaint;  // 风格1限速环
    private Paint minimalLimitNumPaint;   // 风格1限速数字

    // 可复用 RectF 对象
    private final RectF arcRect = new RectF();
    private final RectF ring1Rect = new RectF();
    private final RectF ring1GlowRect = new RectF();
    private final RectF ring2Rect = new RectF();
    private final RectF minimalBarRect = new RectF();
    private final RectF minimalInnerArcRect = new RectF();

    // 可复用 Path 对象
    private final Path needlePath = new Path();
    private final Path minimalNeedlePath = new Path();

    // 端盖缓存Shader（位置固定，随布局重建）
    private Shader capShader0;
    private Shader capShader240;

    // 尺寸
    private float density;
    private int viewWidth;
    private int viewHeight;
    private float gaugeSize;      // 仪表盘正方形区域边长 (= viewWidth)
    private float drawOffsetY = 0f;  // 垂直偏移，用于内容填满容器
    private float centerX, centerY; // 弧形中心
    private float arcRadius;       // 弧线半径
    private float arcStrokeWidth;   // 弧线粗细

    // 颜色
    private int colorArcBg, colorArcBgEnd;
    private int colorArcHighlight;    // 金属弧高光色
    private int colorArcShadow;       // 金属弧暗面色
    private int colorOuterRing;       // 外圈亮银色
    private int colorMajorTick, colorMinorTick;
    private int colorTickNumber;
    private int colorNeedle, colorNeedleCenter, colorNeedleHighlight;
    private int colorSpeedText, colorUnitText;
    private int colorDanger, colorLimitRing, colorLimitText;
    private int colorLedOff;              // LED未激活色
    private int colorLedOn;               // LED激活色（蓝色）
    private int colorLedOnGlow;           // LED激活光晕色（半透明蓝）
    private int colorLedDanger;           // LED超速色
    private int colorLedDangerGlow;       // LED超速光晕色

    public SpeedometerView(Context context) {
        super(context);
        init();
    }

    public SpeedometerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpeedometerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        updateColors();
        startShimmerAnimation();
    }

    /**
     * 流光线动画：相位0~1循环，驱动重绘
     */
    private void startShimmerAnimation() {
        if (shimmerAnimator != null) return;
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
        shimmerAnimator.setDuration(3000);
        shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shimmerAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        shimmerAnimator.addUpdateListener(a -> {
            shimmerPhase = (float) a.getAnimatedValue();
            invalidate();
        });
        shimmerAnimator.start();
    }

    private void updateColors() {
        if (isNightMode) {
            colorArcBg = COLOR_NIGHT_ARC_BG;
            colorArcBgEnd = COLOR_NIGHT_ARC_BG_END;
            colorArcHighlight = COLOR_NIGHT_ARC_HIGHLIGHT;
            colorArcShadow = COLOR_NIGHT_ARC_SHADOW;
            colorOuterRing = COLOR_NIGHT_OUTER_RING;
            colorMajorTick = COLOR_NIGHT_MAJOR_TICK;
            colorMinorTick = COLOR_NIGHT_MINOR_TICK;
            colorTickNumber = COLOR_WHITE;
            colorNeedle = COLOR_NIGHT_NEEDLE;
            colorNeedleCenter = COLOR_NIGHT_NEEDLE_CENTER;
            colorNeedleHighlight = COLOR_NIGHT_NEEDLE_HIGHLIGHT;
            colorSpeedText = COLOR_WHITE;
            colorUnitText = COLOR_NIGHT_UNIT_TEXT;
            colorDanger = COLOR_NIGHT_DANGER;
            colorLimitRing = COLOR_NIGHT_DANGER;
            colorLimitText = COLOR_WHITE;
            colorLedOff = COLOR_NIGHT_LED_OFF;
            colorLedOn = COLOR_NIGHT_LED_ON;
            colorLedOnGlow = COLOR_NIGHT_LED_ON_GLOW;
            colorLedDanger = COLOR_NIGHT_DANGER;
            colorLedDangerGlow = COLOR_NIGHT_DANGER_GLOW;
        } else {
            colorArcBg = COLOR_DAY_ARC_BG;
            colorArcBgEnd = COLOR_DAY_ARC_BG_END;
            colorArcHighlight = COLOR_DAY_ARC_HIGHLIGHT;
            colorArcShadow = COLOR_DAY_ARC_SHADOW;
            colorOuterRing = COLOR_DAY_OUTER_RING;
            colorMajorTick = COLOR_DAY_MAJOR_TICK;
            colorMinorTick = COLOR_DAY_MINOR_TICK;
            colorTickNumber = COLOR_BLACK;
            colorNeedle = COLOR_DAY_NEEDLE;
            colorNeedleCenter = COLOR_DAY_NEEDLE_CENTER;
            colorNeedleHighlight = COLOR_DAY_NEEDLE_HIGHLIGHT;
            colorSpeedText = COLOR_DAY_SPEED_TEXT;
            colorUnitText = COLOR_DAY_UNIT_TEXT;
            colorDanger = COLOR_DAY_DANGER;
            colorLimitRing = COLOR_DAY_DANGER;
            colorLimitText = COLOR_WHITE;
            colorLedOff = COLOR_DAY_LED_OFF;
            colorLedOn = COLOR_DAY_LED_ON;
            colorLedOnGlow = COLOR_DAY_LED_ON_GLOW;
            colorLedDanger = COLOR_DAY_DANGER;
            colorLedDangerGlow = COLOR_DAY_DANGER_GLOW;
        }
        invalidateAllPaints();
    }

    private void invalidateAllPaints() {
        arcPaint = null;
        ledOffPaint = null;
        ledOnPaint = null;
        ledGlowPaint = null;
        ledDangerPaint = null;
        ledDangerGlowPaint = null;
        tickNumberPaint = null;
        needlePaint = null;
        needleCenterPaint = null;
        speedTextPaint = null;
        unitTextPaint = null;
        limitRingPaint = null;
        limitTextPaint = null;
        capPaint = null;
        tickGlowPaint = null;
        ring1Paint = null;
        ring1GlowPaint = null;
        ring2Paint = null;
        minimalBarPaint = null;
        minimalActiveBarPaint = null;
        minimalInnerArcPaint = null;
        minimalTickNumberPaint = null;
        minimalNeedlePaint = null;
        minimalSpeedPaint = null;
        minimalBottomPaint = null;
        minimalLimitRingPaint = null;
        minimalLimitNumPaint = null;
        capShader0 = null;
        capShader240 = null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (widthMode == MeasureSpec.AT_MOST || widthMode == MeasureSpec.UNSPECIFIED) {
            width = (int) (250 * density);
        }
        if (heightMode == MeasureSpec.AT_MOST || heightMode == MeasureSpec.UNSPECIFIED) {
            height = width;
        }
        // 填满容器空间，绘制时根据gaugeSize自适应
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        viewWidth = w;
        viewHeight = h;
        // 仪表盘内容约占 gaugeSize 的 70%（从 0.08 到 0.77）
        // 放大 gaugeSize 使内容填满容器高度，同时不超过宽度
        float gaugeForHeight = h / 0.69f;
        float gaugeForWidth = w * 0.42f / 0.36f;  // arcRadius < w*0.42 确保不溢出
        gaugeSize = Math.min(gaugeForHeight, gaugeForWidth);
        // 弧心水平居中
        centerX = w / 2f;
        // 内容顶部约 0.08*gaugeSize，向上平移使其对齐视图顶部
        float contentTop = gaugeSize * CONTENT_TOP_RATIO;
        drawOffsetY = -contentTop;
        centerY = gaugeSize * CENTER_Y_RATIO;
        // 弧线半径: 占宽度的36%
        arcRadius = gaugeSize * ARC_RADIUS_RATIO;
        // 弧线粗细
        arcStrokeWidth = gaugeSize * ARC_STROKE_RATIO;
        invalidateAllPaints();
    }

    public void setSpeed(int speed) {
        if (speed < 0) speed = 0;
        if (speed > MAX_SPEED) speed = MAX_SPEED;
        targetSpeed = speed;

        if (needleAnimator != null && needleAnimator.isRunning()) {
            needleAnimator.cancel();
        }
        needleAnimator = ValueAnimator.ofInt(currentSpeed, targetSpeed);
        needleAnimator.setDuration(ANIM_DURATION);
        needleAnimator.addUpdateListener(animation -> {
            currentSpeed = (int) animation.getAnimatedValue();
            invalidate();
        });
        needleAnimator.start();
    }

    public void setLimitedSpeed(int limit) {
        this.limitedSpeed = limit;
        invalidate();
    }

    public void setNightMode(boolean nightMode) {
        if (this.isNightMode != nightMode) {
            this.isNightMode = nightMode;
            updateColors();
            invalidate();
        }
    }

    public void toggleStyle() {
        style = (style + 1) % 2;
        invalidate();
    }

    /**
     * 获取弧线底部左端点X坐标（135°端点，相对于View左边缘）
     */
    public float getArcLeftX() {
        // 如果尚未布局完成，arcRadius=0，用比例预计算
        if (arcRadius <= 0) {
            return viewWidth * 0.5f - viewWidth * ARC_RADIUS_RATIO * (float) Math.cos(Math.toRadians(45));
        }
        return centerX - arcRadius * (float) Math.cos(Math.toRadians(45));
    }

    /**
     * 获取弧线底部右端点X坐标（405°=45°端点，相对于View左边缘）
     */
    public float getArcRightX() {
        if (arcRadius <= 0) {
            return viewWidth * 0.5f + viewWidth * ARC_RADIUS_RATIO * (float) Math.cos(Math.toRadians(45));
        }
        return centerX + arcRadius * (float) Math.cos(Math.toRadians(45));
    }

    public int getStyle() {
        return style;
    }

    /**
     * 判断点击坐标是否在速度数字区域（弧线开口下方的中央区域）
     * 用于区分：点速度区域→切换昼夜模式，点其他区域→切换风格
     */
    public boolean isSpeedArea(float x, float y) {
        float left = centerX - gaugeSize * SPEED_AREA_HALF_WIDTH_RATIO;
        float right = centerX + gaugeSize * SPEED_AREA_HALF_WIDTH_RATIO;

        if (style == 0) {
            // 风格0：速度数字(LED+km/h)在弧线开口下方、弧心偏下到底部
            // LED和km/h实际在arcOuterBottom上方，所以要从centerY稍下开始
            float top = centerY + gaugeSize * SPEED_AREA_TOP_RATIO;
            float bottom = gaugeSize;
            return x >= left && x <= right && y >= top && y <= bottom;
        } else {
            // 风格1：速度数字在圆心位置
            float centerTop = centerY - gaugeSize * SPEED_CENTER_TOP_RATIO;
            float centerBottom = centerY + gaugeSize * SPEED_CENTER_BOTTOM_RATIO;
            return x >= left && x <= right && y >= centerTop && y <= centerBottom;
        }
    }

    private float speedToAngle(int speed) {
        return START_ANGLE + ((float) speed / MAX_SPEED) * SWEEP_ANGLE;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ensurePaints();

        // 应用垂直偏移使内容填满容器
        canvas.save();
        canvas.translate(0, drawOffsetY);

        if (style == 0) {
            // 风格0：科幻金属（原有）
            float sweepOffset = (START_ANGLE - 270f + 360f) % 360f;
            float outerR = arcRadius + arcStrokeWidth * 0.6f;
            gaugeHelper.drawOuterRing(canvas, centerX, centerY, outerR, colorOuterRing, gaugeSize, START_ANGLE, SWEEP_ANGLE);
            float shimmerR = outerR + gaugeSize * SHIMMER_OFFSET_RATIO;
            gaugeHelper.drawShimmerArc(canvas, centerX, centerY, shimmerR, gaugeSize, shimmerPhase, isNightMode, START_ANGLE, SWEEP_ANGLE, sweepOffset);
            drawArc(canvas);
            drawEndCaps(canvas);
            drawTicks(canvas);
            drawTickNumbers(canvas);
            drawNeedle(canvas);
            gaugeHelper.drawCenterCap(canvas, centerX, centerY, gaugeSize, isNightMode);
            drawSpeedText(canvas);
            drawLimitIndicator(canvas);
        } else {
            // 风格1：极简科技
            drawMinimalArc(canvas);
            drawMinimalTickBars(canvas);
            drawMinimalTickNumbers(canvas);
            drawMinimalNeedle(canvas);
            drawMinimalCenter(canvas);
            drawMinimalSpeedText(canvas);
        }

        canvas.restore();
    }

    private void ensurePaints() {
        if (arcPaint != null) return;

        // 主弧线（金属渐变：沿弧线方向 高光→暗面→高光）
        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(arcStrokeWidth);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        // SweepGradient 从12点方向顺时针，需要旋转偏移到弧线起点
        // 弧线起始角135°= Canvas角度, SweepGradient以12点(270°CW)为0°
        // 偏移 = 135°- 270°+ 360° = 225° 对应 SweepGradient的0°
        float sweepOffset = (START_ANGLE - 270f + 360f) % 360f;
        SweepGradient metalGradient = new SweepGradient(
            centerX, centerY,
            new int[]{
                colorArcHighlight,  // 0° 起点：高光
                colorArcBg,         // 中段：中间色
                colorArcShadow,     // 1/3处：暗面
                colorArcBg,         // 中段：中间色
                colorArcHighlight,  // 2/3处：高光
                colorArcShadow,     // 末段：暗面
                colorArcHighlight   // 回到起点
            },
            new float[]{
                0f,
                0.1f,
                0.3f,
                0.5f,
                0.7f,
                0.9f,
                1f
            }
        );
        arcPaint.setShader(metalGradient);

        // LED未激活
        ledOffPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ledOffPaint.setStyle(Paint.Style.FILL);
        ledOffPaint.setColor(colorLedOff);

        // LED激活（蓝色）
        ledOnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ledOnPaint.setStyle(Paint.Style.FILL);
        ledOnPaint.setColor(colorLedOn);

        // LED激活光晕
        ledGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ledGlowPaint.setStyle(Paint.Style.FILL);
        ledGlowPaint.setColor(colorLedOnGlow);

        // LED超速（红色）
        ledDangerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ledDangerPaint.setStyle(Paint.Style.FILL);
        ledDangerPaint.setColor(colorLedDanger);

        // LED超速光晕
        ledDangerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ledDangerGlowPaint.setStyle(Paint.Style.FILL);
        ledDangerGlowPaint.setColor(colorLedDangerGlow);

        // 刻度数字
        tickNumberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tickNumberPaint.setStyle(Paint.Style.FILL);
        tickNumberPaint.setColor(colorTickNumber);
        tickNumberPaint.setTextAlign(Paint.Align.CENTER);

        // 指针
        needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        needlePaint.setStyle(Paint.Style.FILL);
        needlePaint.setColor(colorNeedle);

        // 中心圆
        needleCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        needleCenterPaint.setStyle(Paint.Style.FILL);
        needleCenterPaint.setColor(colorNeedleCenter);

        // 速度文字
        speedTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        speedTextPaint.setStyle(Paint.Style.FILL);
        speedTextPaint.setTextAlign(Paint.Align.CENTER);
        speedTextPaint.setFakeBoldText(true);

        // 单位文字
        unitTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        unitTextPaint.setStyle(Paint.Style.FILL);
        unitTextPaint.setColor(colorUnitText);
        unitTextPaint.setTextAlign(Paint.Align.CENTER);

        // 限速环
        limitRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        limitRingPaint.setStyle(Paint.Style.FILL);
        limitRingPaint.setColor(colorLimitRing);

        // 限速数字
        limitTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        limitTextPaint.setStyle(Paint.Style.FILL);
        limitTextPaint.setColor(colorLimitText);
        limitTextPaint.setTextAlign(Paint.Align.CENTER);
        limitTextPaint.setFakeBoldText(true);

        // 端盖金属渐变Paint+Shader（位置固定，随布局重建）
        capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        capPaint.setStyle(Paint.Style.FILL);
        float capRadius = arcStrokeWidth / 2f;
        int capHighlight = isNightMode ? COLOR_NIGHT_ARC_BG : COLOR_DAY_ARC_HIGHLIGHT;
        int capShadow = isNightMode ? COLOR_NIGHT_ARC_SHADOW : COLOR_DAY_ARC_SHADOW;
        double rad0 = Math.toRadians(START_ANGLE);
        float x0 = centerX + (float) (arcRadius * Math.cos(rad0));
        float y0 = centerY + (float) (arcRadius * Math.sin(rad0));
        double rad240 = Math.toRadians(START_ANGLE + SWEEP_ANGLE);
        float x240 = centerX + (float) (arcRadius * Math.cos(rad240));
        float y240 = centerY + (float) (arcRadius * Math.sin(rad240));
        capShader0 = new RadialGradient(
            x0 - capRadius * 0.3f, y0 - capRadius * 0.3f, capRadius * 1.2f,
            capHighlight, capShadow, Shader.TileMode.CLAMP);
        capShader240 = new RadialGradient(
            x240 - capRadius * 0.3f, y240 - capRadius * 0.3f, capRadius * 1.2f,
            capHighlight, capShadow, Shader.TileMode.CLAMP);

        // LED刻度光晕Paint（复用，每帧setShader替换Gradient）
        tickGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tickGlowPaint.setStyle(Paint.Style.FILL);

        // ====== 风格1 Paint初始化 ======

        // 第一圈细线
        ring1Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring1Paint.setStyle(Paint.Style.STROKE);
        ring1Paint.setStrokeWidth(gaugeSize * MINIMAL_LINE_WIDTH_RATIO);
        ring1Paint.setStrokeCap(Paint.Cap.BUTT);

        // 第一圈外发光
        ring1GlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring1GlowPaint.setStyle(Paint.Style.STROKE);
        ring1GlowPaint.setStrokeWidth(gaugeSize * MINIMAL_GLOW_STROKE_RATIO);
        ring1GlowPaint.setStrokeCap(Paint.Cap.BUTT);

        // 第二圈弧线
        ring2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring2Paint.setStyle(Paint.Style.STROKE);
        ring2Paint.setStrokeWidth(gaugeSize * MINIMAL_THIN_LINE_RATIO);
        ring2Paint.setStrokeCap(Paint.Cap.BUTT);

        // 色块Paint
        minimalBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minimalBarPaint.setStyle(Paint.Style.STROKE);
        minimalBarPaint.setStrokeWidth(gaugeSize * MINIMAL_BAR_WIDTH_RATIO);
        minimalBarPaint.setStrokeCap(Paint.Cap.BUTT);

        // 色块激活部分Paint
        minimalActiveBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minimalActiveBarPaint.setStyle(Paint.Style.STROKE);
        minimalActiveBarPaint.setStrokeWidth(gaugeSize * MINIMAL_BAR_WIDTH_RATIO);
        minimalActiveBarPaint.setStrokeCap(Paint.Cap.BUTT);

        // 已激活区间内侧弧Paint
        minimalInnerArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minimalInnerArcPaint.setStyle(Paint.Style.STROKE);
        minimalInnerArcPaint.setStrokeWidth(gaugeSize * MINIMAL_THIN_LINE_RATIO);
        minimalInnerArcPaint.setStrokeCap(Paint.Cap.ROUND);

        // 风格1刻度数字
        minimalTickNumberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minimalTickNumberPaint.setStyle(Paint.Style.FILL);
        minimalTickNumberPaint.setTextAlign(Paint.Align.CENTER);
        minimalTickNumberPaint.setTextSize(gaugeSize * MINIMAL_TICK_TEXT_RATIO);
        minimalTickNumberPaint.setColor(isNightMode ? COLOR_WHITE : COLOR_BLACK);
        minimalTickNumberPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        // 极简指针
        minimalNeedlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minimalNeedlePaint.setStyle(Paint.Style.FILL);

        // 速度数字
        minimalSpeedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minimalSpeedPaint.setStyle(Paint.Style.FILL);
        minimalSpeedPaint.setTextAlign(Paint.Align.CENTER);
        minimalSpeedPaint.setTextSize(gaugeSize * MINIMAL_SPEED_TEXT_RATIO);
        minimalSpeedPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        minimalSpeedPaint.setFakeBoldText(true);

        // 底部km/h
        minimalBottomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minimalBottomPaint.setStyle(Paint.Style.FILL);
        minimalBottomPaint.setTextAlign(Paint.Align.CENTER);
        minimalBottomPaint.setTextSize(gaugeSize * MINIMAL_BOTTOM_TEXT_RATIO);
        minimalBottomPaint.setColor(isNightMode ? COLOR_NIGHT_BOTTOM_TEXT : COLOR_DAY_BOTTOM_TEXT);

        // 风格1限速环
        minimalLimitRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minimalLimitRingPaint.setStyle(Paint.Style.FILL);
        minimalLimitRingPaint.setColor(COLOR_OVERRUN_RED);

        // 风格1限速数字
        minimalLimitNumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minimalLimitNumPaint.setStyle(Paint.Style.FILL);
        minimalLimitNumPaint.setColor(COLOR_WHITE);
        minimalLimitNumPaint.setTextAlign(Paint.Align.CENTER);
        minimalLimitNumPaint.setTextSize(gaugeSize * MINIMAL_LIMIT_TEXT_RATIO);
        minimalLimitNumPaint.setFakeBoldText(true);
    }

    /**
     * 2. 绘制金属渐变弧线 (270°弧)
     */
    private void drawArc(Canvas canvas) {
        arcRect.set(centerX - arcRadius, centerY - arcRadius,
            centerX + arcRadius, centerY + arcRadius);
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, arcPaint);
    }

    /**
     * 2.5 弧线端盖：在弧线两端画金属半圆封口
     * 模拟金属管截面，有立体感
     */
    private void drawEndCaps(Canvas canvas) {
        float capRadius = arcStrokeWidth / 2f;

        // 0 km/h 端 (START_ANGLE = 135°)
        double rad0 = Math.toRadians(START_ANGLE);
        float x0 = centerX + (float) (arcRadius * Math.cos(rad0));
        float y0 = centerY + (float) (arcRadius * Math.sin(rad0));

        // 240 km/h 端 (START_ANGLE + SWEEP_ANGLE = 405° = 45°)
        double rad240 = Math.toRadians(START_ANGLE + SWEEP_ANGLE);
        float x240 = centerX + (float) (arcRadius * Math.cos(rad240));
        float y240 = centerY + (float) (arcRadius * Math.sin(rad240));

        // 0端盖
        capPaint.setShader(capShader0);
        canvas.drawCircle(x0, y0, capRadius, capPaint);

        // 240端盖
        capPaint.setShader(capShader240);
        canvas.drawCircle(x240, y240, capRadius, capPaint);
    }

    /**
     * 2.6 绘制弧线内侧发光背景带
     * 沿弧线内侧扩散一条柔和的半透明光带，从弧线内框向中心方向淡出
     * 日间：半透明青色，夜间：半透明绿色
     */
    /**
     * 3. 绘制LED发光刻度点
     * 每个刻度位置画一个圆形LED点，带光晕效果
     * - 未激活：暗灰色凹陷点
     * - 已激活（速度经过此处）：亮蓝色点 + 径向渐变光晕
     * - 超速段：红色点 + 红色光晕
     * 光晕使用RadialGradient：中心亮→边缘完全透明
     */
    private void drawTicks(Canvas canvas) {
        // LED点放在弧线中心，光晕不超出弧线内外边缘
        float tickRadius = arcRadius;
        // 弧线半宽 = arcStrokeWidth/2，留15%空隙，光晕最多占70%半宽
        float arcHalfWidth = arcStrokeWidth / 2f;
        float majorGlowR = arcHalfWidth * 0.65f;    // 大刻度光晕半径（留35%空隙）
        float minorGlowR = arcHalfWidth * 0.45f;    // 小刻度光晕半径（留55%空隙）
        float majorPointR = arcHalfWidth * 0.25f;   // 大刻度点半径
        float minorPointR = arcHalfWidth * 0.15f;   // 小刻度点半径

        for (int speed = 0; speed <= MAX_SPEED; speed += MINOR_TICK_INTERVAL) {
            float angle = speedToAngle(speed);
            double rad = Math.toRadians(angle);

            float px = centerX + (float) (tickRadius * Math.cos(rad));
            float py = centerY + (float) (tickRadius * Math.sin(rad));

            boolean isMajor = (speed % MAJOR_TICK_INTERVAL == 0);
            float pointR = isMajor ? majorPointR : minorPointR;
            float glowR = isMajor ? majorGlowR : minorGlowR;

            boolean isActivated = (speed <= currentSpeed);
            boolean isOverLimit = (limitedSpeed > 0 && speed >= limitedSpeed);

            if (isActivated && isOverLimit) {
                tickGlowPaint.setShader(new RadialGradient(
                    px, py, glowR,
                    colorLedDanger, COLOR_DANGER_FADE, Shader.TileMode.CLAMP));
                canvas.drawCircle(px, py, glowR, tickGlowPaint);
                canvas.drawCircle(px, py, pointR, ledDangerPaint);
            } else if (isActivated) {
                tickGlowPaint.setShader(new RadialGradient(
                    px, py, glowR,
                    colorLedOn, COLOR_NIGHT_LED_ON_FADE, Shader.TileMode.CLAMP));
                canvas.drawCircle(px, py, glowR, tickGlowPaint);
                canvas.drawCircle(px, py, pointR, ledOnPaint);
            } else {
                canvas.drawCircle(px, py, pointR, ledOffPaint);
            }
        }
    }

    /**
     * 6. 绘制刻度数字
     * 数字在LED点内侧, 字体大小随仪表盘动态缩放
     */
    private void drawTickNumbers(Canvas canvas) {
        // 数字半径: LED点内侧偏移
        float numberRadius = arcRadius - gaugeSize * TICK_NUMBER_OFFSET_RATIO;
        // 字体大小: 仪表盘宽度的3% (小巧不拥挤)
        float textSize = gaugeSize * TICK_TEXT_SIZE_RATIO;
        // 浮雕偏移量 (右下阴影 + 左上高光)
        float embossOffset = gaugeSize * EMBOSS_OFFSET_RATIO;

        tickNumberPaint.setTextSize(textSize);

        for (int speed = 0; speed <= MAX_SPEED; speed += MAJOR_TICK_INTERVAL) {
            float angle = speedToAngle(speed);
            double rad = Math.toRadians(angle);

            float x = centerX + (float) (numberRadius * Math.cos(rad));
            float y = centerY + (float) (numberRadius * Math.sin(rad));

            Paint.FontMetrics fm = tickNumberPaint.getFontMetrics();
            float textY = y - (fm.ascent + fm.descent) / 2f;

            // 1) 阴影层：右下偏移，深色
            tickNumberPaint.setColor(isNightMode ? COLOR_BLACK : COLOR_DAY_EMBOSS_SHADOW);
            tickNumberPaint.clearShadowLayer();
            canvas.drawText(String.valueOf(speed), x + embossOffset, textY + embossOffset, tickNumberPaint);

            // 2) 高光层：左上偏移，亮色
            tickNumberPaint.setColor(isNightMode ? COLOR_NIGHT_EMBOSS_HIGHLIGHT : COLOR_DAY_EMBOSS_HIGHLIGHT);
            canvas.drawText(String.valueOf(speed), x - embossOffset, textY - embossOffset, tickNumberPaint);

            // 3) 主体层：正常颜色
            tickNumberPaint.setColor(colorTickNumber);
            canvas.drawText(String.valueOf(speed), x, textY, tickNumberPaint);
        }
    }

    /**
     * 7. 绘制指针
     * 细长三角形, 从中心延伸到弧线附近
     */
    private void drawNeedle(Canvas canvas) {
        float angle = speedToAngle(currentSpeed);
        double rad = Math.toRadians(angle);

        // 指针长度: 到弧线内侧再留一点间距
        float needleLength = arcRadius - gaugeSize * NEEDLE_LENGTH_OFFSET_RATIO;
        float tailLength = gaugeSize * NEEDLE_TAIL_RATIO;                 // 尾部短延伸
        float halfWidth = gaugeSize * NEEDLE_HALF_WIDTH_RATIO;                 // 指针半宽

        float tipX = centerX + (float) (needleLength * Math.cos(rad));
        float tipY = centerY + (float) (needleLength * Math.sin(rad));
        float tailX = centerX - (float) (tailLength * Math.cos(rad));
        float tailY = centerY - (float) (tailLength * Math.sin(rad));

        // 垂直方向（指针宽度方向）
        float perpX = (float) Math.cos(rad + Math.PI / 2);
        float perpY = (float) Math.sin(rad + Math.PI / 2);

        needlePath.reset();
        needlePath.moveTo(tipX, tipY);
        needlePath.lineTo(centerX + perpX * halfWidth, centerY + perpY * halfWidth);
        needlePath.lineTo(tailX, tailY);
        needlePath.lineTo(centerX - perpX * halfWidth, centerY - perpY * halfWidth);
        needlePath.close();

        canvas.drawPath(needlePath, needlePaint);
    }

    /**
     * 9. 绘制底部速度文字
     * 速度数字使用LED七段数码管风格, km/h底边与弧线最外框最低点对齐
     */

    private void drawSpeedText(Canvas canvas) {
        // 弧线外框最低点
        float arcEndpointY = centerY + arcRadius * (float) Math.sin(Math.toRadians(45));
        float arcOuterBottom = arcEndpointY + arcStrokeWidth * 0.6f;

        // km/h 单位: 底边(descent)与弧线最低点对齐
        float unitTextSize = gaugeSize * UNIT_TEXT_SIZE_RATIO;
        unitTextPaint.setTextSize(unitTextSize);
        Paint.FontMetrics unitFm = unitTextPaint.getFontMetrics();
        float unitTextY = arcOuterBottom - unitFm.descent;

        // LED数码管参数
        float digitH = gaugeSize * LED_DIGIT_HEIGHT_RATIO;   // 单个数字高度
        float digitW = digitH * 0.5f;        // 单个数字宽度
        float segThick = digitH * 0.14f;     // 段粗细
        float segGap = digitH * 0.06f;       // 段与段间隙
        float digitGap = digitW * 0.3f;      // 数字间距

        String speedStr = String.valueOf(currentSpeed);
        int digitCount = speedStr.length();
        float totalWidth = digitCount * digitW + (digitCount - 1) * digitGap;

        // LED数字底边与km/h顶部间隙
        float gap = gaugeSize * LED_GAP_RATIO;
        float ledBottom = unitTextY + unitFm.ascent - gap;
        float ledTop = ledBottom - digitH;

        // 整体水平居中
        float startX = centerX - totalWidth / 2f;

        // 颜色：正常/超速
        boolean isOverspeed = limitedSpeed > 0 && currentSpeed > limitedSpeed;
        int activeColor = isOverspeed ? COLOR_OVERRUN_RED : (isNightMode ? COLOR_NIGHT_LED_ON : COLOR_DAY_LED_ON);
        int activeGlow = isOverspeed ? COLOR_OVERSPEED_LED_GLOW : (isNightMode ? COLOR_NIGHT_LED_DIGIT_GLOW : COLOR_DAY_LED_DIGIT_GLOW);

        // 逐位绘制LED数码管
        for (int i = 0; i < digitCount; i++) {
            int digit = speedStr.charAt(i) - '0';
            float dx = startX + i * (digitW + digitGap);
            LEDDigitHelper.drawLEDDigit(canvas, dx, ledTop, digitW, digitH, segThick, segGap, digit, activeColor, activeGlow, 255);
        }

        // km/h 加浮雕绘制
        float embossOffset = gaugeSize * SMALL_EMBOSS_RATIO;
        // 阴影层
        unitTextPaint.setColor(isNightMode ? COLOR_BLACK : COLOR_DAY_EMBOSS_SHADOW);
        unitTextPaint.clearShadowLayer();
        canvas.drawText("km/h", centerX + embossOffset, unitTextY + embossOffset, unitTextPaint);
        // 高光层
        unitTextPaint.setColor(isNightMode ? COLOR_NIGHT_EMBOSS_HIGHLIGHT : COLOR_DAY_EMBOSS_HIGHLIGHT);
        canvas.drawText("km/h", centerX - embossOffset, unitTextY - embossOffset, unitTextPaint);
        // 主体层
        unitTextPaint.setColor(colorUnitText);
        canvas.drawText("km/h", centerX, unitTextY, unitTextPaint);
    }

    /**
     * 10. 绘制限速圆指示器 (在中心点与200刻度第一个数字"2"的中间)
     */
    private void drawLimitIndicator(Canvas canvas) {
        if (limitedSpeed <= 0) return;

        // 200刻度数字位置
        float numberRadius = arcRadius - gaugeSize * LIMIT_NUMBER_RADIUS_RATIO;
        float angle200 = speedToAngle(200);
        double rad200 = Math.toRadians(angle200);
        float num200X = centerX + (float) (numberRadius * Math.cos(rad200));
        float num200Y = centerY + (float) (numberRadius * Math.sin(rad200));

        // 200的第一个"2"的大致位置：数字"200"的左半部分，取数字左边约1/3处
        float numTextSize = gaugeSize * TICK_TEXT_SIZE_RATIO;
        float charWidth = numTextSize * 0.6f;  // 单个数字宽度约0.6倍字号
        float first2X = num200X - charWidth;   // 第一个"2"的位置偏左
        float first2Y = num200Y;

        // 限速标志位置：中心点与第一个"2"的中间
        float indicatorX = (centerX + first2X) / 2f;
        float indicatorY = (centerY + first2Y) / 2f;

        float indicatorR = gaugeSize * LIMIT_INDICATOR_RADIUS_RATIO;

        // 红色圆
        canvas.drawCircle(indicatorX, indicatorY, indicatorR, limitRingPaint);

        // 白色数字
        float limitNumSize = gaugeSize * LIMIT_TEXT_SIZE_RATIO;
        limitTextPaint.setTextSize(limitNumSize);
        Paint.FontMetrics fm = limitTextPaint.getFontMetrics();
        float textY = indicatorY - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(String.valueOf(limitedSpeed), indicatorX, textY, limitTextPaint);
    }

    // ========== 风格1：极简科技（严格按sd.png结构） ==========

    /**
     * 风格1：从外到内依次绘制
     * 1. 第一圈：渐变发光细线
     * 2. 第二圈：渐变基底线
     * 3. 第三圈：色块刻度（默认灰，速度到的叠加青/绿）
     * 4. 已激活区间内侧渐变半弧
     * 5. 刻度数字
     * 6. 极短小指针
     * 7. 中心径向渐变深色圆 + 速度数字 + 底部信息
     */
    private void drawMinimalArc(Canvas canvas) {
        float r1 = arcRadius + gaugeSize * MINIMAL_RING1_OFFSET_RATIO;  // 第一圈（最外）

        // ====== 第一圈：渐变发光细线 ======
        // 左侧/下部亮，到120km/h位置基本透明，右侧消失
        // 用多段弧线手动控制alpha，避免SweepGradient方向不准
        int c1R = isNightMode ? 0x00 : 0x00;
        int c1G = isNightMode ? 0xE5 : 0xD4;
        int c1B = isNightMode ? 0xA0 : 0xE8;

        // 分段绘制，每段10度，根据所在角度调整alpha
        float r1Step = 3f; // 每段3度
        ring1Rect.set(centerX - r1, centerY - r1, centerX + r1, centerY + r1);
        for (float a = START_ANGLE; a < START_ANGLE + SWEEP_ANGLE; a += r1Step) {
            // 计算这段弧中点的速度值
            float midA = a + r1Step / 2f;
            float midSpeed = ((midA - START_ANGLE) / SWEEP_ANGLE) * MAX_SPEED;
            
            // alpha计算：0-10渐隐，10-100全亮，100-130渐隐，130+透明
            int alpha;
            if (midSpeed <= 10) {
                alpha = (int) (255 * (midSpeed / 10f));
            } else if (midSpeed <= 100) {
                alpha = 255;
            } else if (midSpeed <= 130) {
                alpha = (int) (255 * (1f - (midSpeed - 100) / 30f));
            } else {
                alpha = 0;
            }
            
            if (alpha <= 0) continue;
            
            ring1Paint.setColor((alpha << 24) | (c1R << 16) | (c1G << 8) | c1B);
            canvas.drawArc(ring1Rect, a, r1Step + 0.5f, false, ring1Paint);
        }

        // 第一圈外发光光晕（同样的渐隐逻辑）
        float r1g = r1 + gaugeSize * MINIMAL_GLOW_OFFSET_RATIO;
        ring1GlowRect.set(centerX - r1g, centerY - r1g, centerX + r1g, centerY + r1g);
        for (float a = START_ANGLE; a < START_ANGLE + SWEEP_ANGLE; a += r1Step * 2) {
            float midA = a + r1Step;
            float midSpeed = ((midA - START_ANGLE) / SWEEP_ANGLE) * MAX_SPEED;
            
            int alpha;
            if (midSpeed <= 10) {
                alpha = (int) (40 * (midSpeed / 10f));
            } else if (midSpeed <= 100) {
                alpha = 40;
            } else if (midSpeed <= 130) {
                alpha = (int) (40 * (1f - (midSpeed - 100) / 30f));
            } else {
                alpha = 0;
            }
            
            if (alpha <= 0) continue;
            
            ring1GlowPaint.setColor((alpha << 24) | (c1R << 16) | (c1G << 8) | c1B);
            canvas.drawArc(ring1GlowRect, a, r1Step * 2 + 0.5f, false, ring1GlowPaint);
        }

        // ====== 第二圈：紧邻色块的青/绿渐隐弧线 ======
        // 只画20-100和140-220两段，两端渐隐
        float barR = arcRadius - gaugeSize * MINIMAL_BAR_OFFSET_RATIO;
        float r2adj = barR + (r1 - barR) / 3f; // 外圈线与色块之间距离的1/3处
        int c2R2 = isNightMode ? 0x00 : 0x00;
        int c2G2 = isNightMode ? 0xE5 : 0xD4;
        int c2B2 = isNightMode ? 0xA0 : 0xE8;

        ring2Rect.set(centerX - r2adj, centerY - r2adj, centerX + r2adj, centerY + r2adj);

        // 两段弧线：20-100, 140-220
        float[][] segments = {{20, 100}, {140, 220}};
        int fadeRange = 10; // 渐隐范围km/h

        for (float[] seg : segments) {
            float segStart = seg[0];
            float segEnd = seg[1];
            for (float a = speedToAngle((int) segStart) - 1; a < speedToAngle((int) segEnd) + 1; a += r1Step) {
                float midA = a + r1Step / 2f;
                float midSpeed = ((midA - START_ANGLE) / SWEEP_ANGLE) * MAX_SPEED;
                if (midSpeed < segStart || midSpeed > segEnd) continue;

                int alpha = 255;
                // 起端渐入
                if (midSpeed < segStart + fadeRange) {
                    alpha = (int) (255 * ((midSpeed - segStart) / fadeRange));
                }
                // 末端渐出
                if (midSpeed > segEnd - fadeRange) {
                    alpha = Math.min(alpha, (int) (255 * ((segEnd - midSpeed) / fadeRange)));
                }
                alpha = Math.max(0, Math.min(255, alpha));
                if (alpha <= 0) continue;

                ring2Paint.setColor((alpha << 24) | (c2R2 << 16) | (c2G2 << 8) | c2B2);
                canvas.drawArc(ring2Rect, a, r1Step + 0.5f, false, ring2Paint);
            }
        }
    }

    private void drawMinimalTickBars(Canvas canvas) {
        // 每个色块是一段弧线，不是小竖条
        float barR = arcRadius - gaugeSize * MINIMAL_BAR_OFFSET_RATIO;  // 色块所在弧的半径
        float gapAngle = 1.2f;                        // 色块之间的间隙角度（度）

        int tickCount = MAX_SPEED / MINOR_TICK_INTERVAL; // 24个色块

        // 每个色块占据的角度
        float totalAnglePerTick = SWEEP_ANGLE / tickCount; // 11.25度
        float barAngle = totalAnglePerTick - gapAngle;     // 色块实际弧度

        minimalBarRect.set(centerX - barR, centerY - barR, centerX + barR, centerY + barR);

        for (int i = 0; i <= tickCount; i++) {
            int speed = i * MINOR_TICK_INTERVAL;
            float startAngle = speedToAngle(speed) - barAngle / 2f;

            boolean isOverLimit = (limitedSpeed > 0 && speed >= limitedSpeed);
            int nextSpeed = speed + MINOR_TICK_INTERVAL;

            minimalBarPaint.clearShadowLayer();

            int barColor;
            int glowColor = 0;

            if (currentSpeed >= nextSpeed) {
                // 整块已过：全亮
                if (isOverLimit) {
                    barColor = COLOR_OVERRUN_RED;
                    glowColor = COLOR_OVERRUN_BAR_GLOW;
                } else {
                    barColor = isNightMode ? COLOR_NIGHT_LED_ON : COLOR_DAY_LED_ON;
                    glowColor = isNightMode ? COLOR_NIGHT_ACTIVE_BAR_GLOW : COLOR_DAY_ACTIVE_BAR_GLOW;
                }
                if (glowColor != 0) {
                    minimalBarPaint.setShadowLayer(gaugeSize * MINIMAL_SHADOW_RATIO, 0, 0, glowColor);
                }
                minimalBarPaint.setColor(barColor);
                canvas.drawArc(minimalBarRect, startAngle, barAngle, false, minimalBarPaint);
            } else if (currentSpeed > speed) {
                // 当前所在块：先画灰色全块，再叠加亮色部分
                barColor = isNightMode ? COLOR_NIGHT_INACTIVE_BAR : COLOR_DAY_INACTIVE_BAR;
                minimalBarPaint.setColor(barColor);
                canvas.drawArc(minimalBarRect, startAngle, barAngle, false, minimalBarPaint);

                // 计算亮色部分的角度
                float speedAngle = speedToAngle(currentSpeed);
                float barMidAngle = speedToAngle(speed);
                float fractionAngle = speedAngle - barMidAngle;
                fractionAngle = Math.max(0, Math.min(fractionAngle, barAngle));
                if (fractionAngle > 0) {
                    minimalActiveBarPaint.clearShadowLayer();
                    boolean partialOverLimit = (limitedSpeed > 0 && currentSpeed >= limitedSpeed);
                    if (partialOverLimit) {
                        minimalActiveBarPaint.setColor(COLOR_OVERRUN_RED);
                        minimalActiveBarPaint.setShadowLayer(gaugeSize * MINIMAL_SHADOW_RATIO, 0, 0, COLOR_OVERRUN_BAR_GLOW);
                    } else {
                        minimalActiveBarPaint.setColor(isNightMode ? COLOR_NIGHT_LED_ON : COLOR_DAY_LED_ON);
                        minimalActiveBarPaint.setShadowLayer(gaugeSize * MINIMAL_SHADOW_RATIO, 0, 0, isNightMode ? COLOR_NIGHT_ACTIVE_BAR_GLOW : COLOR_DAY_ACTIVE_BAR_GLOW);
                    }
                    canvas.drawArc(minimalBarRect, startAngle, fractionAngle, false, minimalActiveBarPaint);
                }
            } else {
                // 未到的块：灰色
                barColor = isNightMode ? COLOR_NIGHT_INACTIVE_BAR : COLOR_DAY_INACTIVE_BAR;
                minimalBarPaint.setColor(barColor);
                canvas.drawArc(minimalBarRect, startAngle, barAngle, false, minimalBarPaint);
            }
        }

        // ====== 已激活区间内侧渐变半弧 ======
        if (currentSpeed > 0) {
            float barStrokeW = gaugeSize * MINIMAL_BAR_WIDTH_RATIO;
            float innerArcR = barR - barStrokeW / 2f - gaugeSize * MINIMAL_INNER_ARC_GAP_RATIO;
            float endAngle = speedToAngle(currentSpeed);
            float sweepLen = endAngle - START_ANGLE;

            int arcColor = isNightMode ? COLOR_NIGHT_LED_ON : COLOR_DAY_LED_ON;
            int arcFade = isNightMode ? COLOR_NIGHT_LED_ON_FADE : COLOR_DAY_LED_ON_FADE;
            double startRad = Math.toRadians(START_ANGLE);
            double endRad = Math.toRadians(endAngle);
            float sx = centerX + (float) (innerArcR * Math.cos(startRad));
            float sy = centerY + (float) (innerArcR * Math.sin(startRad));
            float ex = centerX + (float) (innerArcR * Math.cos(endRad));
            float ey = centerY + (float) (innerArcR * Math.sin(endRad));
            minimalInnerArcPaint.setShader(new LinearGradient(sx, sy, ex, ey, arcColor, arcFade, Shader.TileMode.CLAMP));

            minimalInnerArcRect.set(centerX - innerArcR, centerY - innerArcR,
                    centerX + innerArcR, centerY + innerArcR);
            canvas.drawArc(minimalInnerArcRect, START_ANGLE, sweepLen, false, minimalInnerArcPaint);
        }
    }

    private void drawMinimalTickNumbers(Canvas canvas) {
        // 数字在色块内侧
        float barOuterR = arcRadius - gaugeSize * MINIMAL_BAR_OFFSET_RATIO;
        float barLength = gaugeSize * MINIMAL_BAR_LENGTH_RATIO;
        float numberRadius = barOuterR - barLength - gaugeSize * MINIMAL_NUMBER_GAP_RATIO;

        for (int speed = 0; speed <= MAX_SPEED; speed += MAJOR_TICK_INTERVAL) {
            float angle = speedToAngle(speed);
            double rad = Math.toRadians(angle);

            float x = centerX + (float) (numberRadius * Math.cos(rad));
            float y = centerY + (float) (numberRadius * Math.sin(rad));

            Paint.FontMetrics fm = minimalTickNumberPaint.getFontMetrics();
            float textY = y - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(String.valueOf(speed), x, textY, minimalTickNumberPaint);
        }
    }

    private void drawMinimalNeedle(Canvas canvas) {
        float angle = speedToAngle(currentSpeed);
        double rad = Math.toRadians(angle);

        // 极短指针：在色块内侧和数字之间
        float barOuterR = arcRadius - gaugeSize * MINIMAL_BAR_OFFSET_RATIO;
        float barLength = gaugeSize * MINIMAL_BAR_LENGTH_RATIO;
        float needleOuterR = barOuterR - barLength - gaugeSize * MINIMAL_NEEDLE_GAP_RATIO;
        float needleInnerR = needleOuterR - gaugeSize * MINIMAL_NEEDLE_LENGTH_RATIO;
        float baseW = gaugeSize * MINIMAL_NEEDLE_BASE_RATIO;  // 底部宽度

        float tipX = centerX + (float) (needleOuterR * Math.cos(rad));
        float tipY = centerY + (float) (needleOuterR * Math.sin(rad));
        float baseX = centerX + (float) (needleInnerR * Math.cos(rad));
        float baseY = centerY + (float) (needleInnerR * Math.sin(rad));

        float perpX = (float) Math.cos(rad + Math.PI / 2);
        float perpY = (float) Math.sin(rad + Math.PI / 2);

        minimalNeedlePath.reset();
        minimalNeedlePath.moveTo(tipX, tipY);
        minimalNeedlePath.lineTo(baseX + perpX * baseW, baseY + perpY * baseW);
        minimalNeedlePath.lineTo(baseX - perpX * baseW, baseY - perpY * baseW);
        minimalNeedlePath.close();

        minimalNeedlePaint.clearShadowLayer();
        boolean isOverspeed = limitedSpeed > 0 && currentSpeed > limitedSpeed;
        if (isOverspeed) {
            minimalNeedlePaint.setColor(COLOR_OVERRUN_RED);
            minimalNeedlePaint.setShadowLayer(gaugeSize * MINIMAL_NEEDLE_BASE_RATIO, 0, 0, COLOR_OVERRUN_NEEDLE_GLOW);
        } else {
            minimalNeedlePaint.setColor(isNightMode ? COLOR_NIGHT_LED_ON : COLOR_DAY_LED_ON);
            minimalNeedlePaint.setShadowLayer(gaugeSize * MINIMAL_NEEDLE_BASE_RATIO, 0, 0, isNightMode ? COLOR_NIGHT_NEEDLE_GLOW : COLOR_DAY_NEEDLE_GLOW);
        }
        canvas.drawPath(minimalNeedlePath, minimalNeedlePaint);
    }

    private void drawMinimalCenter(Canvas canvas) {
        // 无底色圆
    }

    private void drawMinimalSpeedText(Canvas canvas) {
        boolean isOverspeed = limitedSpeed > 0 && currentSpeed > limitedSpeed;

        // 速度数字
        minimalSpeedPaint.clearShadowLayer();
        if (isOverspeed) {
            minimalSpeedPaint.setColor(COLOR_OVERRUN_RED);
            minimalSpeedPaint.setShadowLayer(gaugeSize * MINIMAL_SPEED_OFFSET_RATIO, 0, 0, COLOR_OVERSPEED_TEXT_GLOW);
        } else {
            minimalSpeedPaint.setColor(isNightMode ? COLOR_WHITE : COLOR_BLACK);
        }

        Paint.FontMetrics fm = minimalSpeedPaint.getFontMetrics();
        float speedY = centerY - (fm.ascent + fm.descent) / 2f - gaugeSize * MINIMAL_SPEED_OFFSET_RATIO;
        canvas.drawText(String.valueOf(currentSpeed), centerX, speedY, minimalSpeedPaint);

        // 底部km/h
        float bottomY = speedY + fm.descent + gaugeSize * MINIMAL_SPEED_OFFSET_RATIO;
        Paint.FontMetrics bfm = minimalBottomPaint.getFontMetrics();
        bottomY -= bfm.ascent;

        if (limitedSpeed > 0) {
            float barOuterR = arcRadius - gaugeSize * MINIMAL_BAR_OFFSET_RATIO;
            float barLength = gaugeSize * MINIMAL_BAR_LENGTH_RATIO;
            float numberRadius = barOuterR - barLength - gaugeSize * MINIMAL_NUMBER_GAP_RATIO;
            float numberY = centerY + numberRadius * (float) Math.sin(Math.toRadians(45));
            float indicatorR = gaugeSize * MINIMAL_INDICATOR_RADIUS_RATIO;
            float indicatorY = numberY;
            canvas.drawCircle(centerX, indicatorY, indicatorR, minimalLimitRingPaint);

            Paint.FontMetrics lfm = minimalLimitNumPaint.getFontMetrics();
            float limitTextY = indicatorY - (lfm.ascent + lfm.descent) / 2f;
            canvas.drawText(String.valueOf(limitedSpeed), centerX, limitTextY, minimalLimitNumPaint);
        }
    }
}
