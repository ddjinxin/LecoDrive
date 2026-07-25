package com.jingxin.pandrive.view;

import android.content.Context;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Mileage & fuel display: two-line layout, slides between 4 modes
 */
public class MileageView extends View {

    private static final int MODE_TRIP = 0;
    private static final int MODE_TODAY = 1;
    private static final int MODE_TOTAL = 2;
    private static final int MODE_OVERALL = 3;   // 综合油耗
    private static final int MODE_RECENT = 4;    // 近期油耗
    private static final int MODE_RANGE = 5;
    private static final int MODE_PERCENT = 6;
    private static final int MODE_COUNT = 7;
    private static final String[] MODE_LABELS = {"实时行程", "今日里程", "累计里程", "", "", "剩余续航", "剩余"};
    private boolean isElectric = false;

    private float tripKm = 0f;
    private float todayKm = 0f;
    private float totalKm = 0f;
    private float overallFuelLPer100km = 0f;  // 综合油耗
    private float recentFuelLPer100km = 0f;   // 近期油耗
    private float rangeKm = 0f;
    private float remainingPercent = 0f;

    // Cycle state
    private int currentMode = MODE_TRIP;
    private int prevMode = MODE_TRIP;
    private long lastCycleTime = 0;
    private static final long CYCLE_INTERVAL_MS = 4000;
    private static final long SLIDE_DURATION_MS = 500;

    private boolean isNightMode = false;

    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private static final float ANGLE_PER_ITEM = 360f / MODE_COUNT;  // 每项在圆柱上的间隔角度

    private final Paint ledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Handler cycleHandler = new Handler(Looper.getMainLooper());
    private final Runnable cycleRunnable = new Runnable() {
        @Override
        public void run() {
            prevMode = currentMode;
            currentMode = (currentMode + 1) % MODE_COUNT;
            lastCycleTime = System.currentTimeMillis();
            invalidate();
            cycleHandler.postDelayed(this, CYCLE_INTERVAL_MS);
        }
    };

    public MileageView(Context context) { super(context); init(); }
    public MileageView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public MileageView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        ledPaint.setStyle(Paint.Style.FILL);
        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        lastCycleTime = System.currentTimeMillis();
        cycleHandler.postDelayed(cycleRunnable, CYCLE_INTERVAL_MS);
    }

    public void setNightMode(boolean night) { isNightMode = night; invalidate(); }
    public void setVehicleType(int type) { isElectric = (type == 1); invalidate(); }

    public void updateMileage(float trip, float today, float total) {
        this.tripKm = trip; this.todayKm = today; this.totalKm = total; invalidate();
    }

    public void updateFuel(float overallFuel, float recentFuel, float range, float percent) {
        this.overallFuelLPer100km = overallFuel;
        this.recentFuelLPer100km = recentFuel;
        this.rangeKm = range;
        this.remainingPercent = percent;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) { return false; }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cycleHandler.removeCallbacks(cycleRunnable);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        long now = System.currentTimeMillis();
        long elapsed = now - lastCycleTime;
        float progress = 0f;
        boolean isAnimating = elapsed < SLIDE_DURATION_MS;
        if (isAnimating) {
            progress = (float) elapsed / SLIDE_DURATION_MS;
            progress = 1f - (1f - progress) * (1f - progress); // ease-out
        }

        float rotation = -progress * ANGLE_PER_ITEM;  // 负号：由下往上滚动

        // 持续微摆动：让滚轮始终有"活的"转动感（±1°，周期2.5s）
        float idleAngle = isAnimating ? 0f : (float) Math.sin(now / 2500.0 * Math.PI) * 1f;
        // 总旋转角度：动画时为 rotation，静止时为 idleAngle
        float totalRotation = isAnimating ? rotation : idleAngle;
        invalidate(); // 持续重绘以保持微摆动和动画

        // Colors
        int activeColor = isNightMode ? 0xFF00E5A0 : 0xFF00D4E8;
        int activeGlow = isNightMode ? 0xFF009966 : 0xFF00B8D4;
        int labelColor = isNightMode ? 0xFFFFFFFF : 0xFF000000;

        float cx = w / 2f;
        // 滚轮中心在标签下方，数字区域居中
        float rollerCenterY = h * 0.6f;

        // 滚轮宽度：只占中间区域，不盖住左右指南针和时钟
        float rollerW = w * 0.20f;
        float rollerLeft = cx - rollerW / 2f;
        float rollerRight = cx + rollerW / 2f;

        // 标签固定在顶部，不随滚轮旋转
        drawLabel(canvas, w, h, currentMode, labelColor);

        // 滚轮可见区域：正面数字完整 + 上下相邻项各露出约1/5
        float digitH = h * 0.21f;
        float rollerVisibleH = h * 0.40f;
        float rollerTop = rollerCenterY - rollerVisibleH / 2f;
        float rollerBottom = rollerCenterY + rollerVisibleH / 2f;

        // 画银灰色金属圆柱滚轮背景（渐变高光随旋转移动）
        drawRollerBackground(canvas, rollerLeft, rollerTop, rollerRight, rollerBottom, totalRotation);

        // 裁剪到滚轮区域，上下项超出部分被物理裁掉（模拟圆柱背面遮挡）
        canvas.save();
        canvas.clipRect(rollerLeft, rollerTop, rollerRight, rollerBottom);

        // 画圆柱面纹理线（随旋转透视移动，让滚轮本身可见转动）
        float radius = h * 0.30f;
        drawCylinderTexture(canvas, cx, rollerCenterY, rollerLeft, rollerRight, totalRotation, radius, h);

        if (isAnimating) {
            // 动画中：prevMode(旧)从正面转下方背面，currentMode(新)从上方转到正面（由下往上）
            drawRollerItem(canvas, prevMode, rotation, cx, rollerCenterY, w, h, activeColor, activeGlow, labelColor, rollerLeft, rollerRight);
            drawRollerItem(canvas, currentMode, ANGLE_PER_ITEM + rotation, cx, rollerCenterY, w, h, activeColor, activeGlow, labelColor, rollerLeft, rollerRight);
            int nextMode = (currentMode + 1) % MODE_COUNT;
            drawRollerItem(canvas, nextMode, 2f * ANGLE_PER_ITEM + rotation, cx, rollerCenterY, w, h, activeColor, activeGlow, labelColor, rollerLeft, rollerRight);
        } else {
            // 静止：当前项在正面完整显示，上下相邻项部分露出（绕到圆柱背面被裁掉）
            // 加上微摆动 idleAngle，让滚轮始终有物理转动感
            int upMode = (currentMode - 1 + MODE_COUNT) % MODE_COUNT;
            int downMode = (currentMode + 1) % MODE_COUNT;
            drawRollerItem(canvas, upMode, -ANGLE_PER_ITEM + idleAngle, cx, rollerCenterY, w, h, activeColor, activeGlow, labelColor, rollerLeft, rollerRight);
            drawRollerItem(canvas, currentMode, idleAngle, cx, rollerCenterY, w, h, activeColor, activeGlow, labelColor, rollerLeft, rollerRight);
            drawRollerItem(canvas, downMode, ANGLE_PER_ITEM + idleAngle, cx, rollerCenterY, w, h, activeColor, activeGlow, labelColor, rollerLeft, rollerRight);
        }

        canvas.restore();

        // 滚轮上下边缘阴影遮罩，增强金属边界感
        drawRollerEdges(canvas, rollerLeft, rollerTop, rollerRight, rollerBottom);
    }

    /** 绘制固定标签（不参与滚轮旋转） */
    private void drawLabel(Canvas canvas, int w, int h, int mode, int labelColor) {
        String label;
        if (mode == MODE_OVERALL) {
            label = isElectric ? "综合电耗" : "综合油耗";
        } else if (mode == MODE_RECENT) {
            label = isElectric ? "近期电耗" : "近期油耗";
        } else if (mode == MODE_RANGE) {
            label = "剩余续航";
        } else if (mode == MODE_PERCENT) {
            label = isElectric ? "剩余电量" : "剩余油量";
        } else {
            label = MODE_LABELS[mode];
        }
        labelPaint.setTextSize(h * 0.093f);
        labelPaint.setColor(labelColor);
        labelPaint.setAlpha(255);
        Paint.FontMetrics labelFm = labelPaint.getFontMetrics();
        float labelBaseline = h * 0.3f - (labelFm.ascent + labelFm.descent) / 2f;
        canvas.drawText(label, w / 2f, labelBaseline, labelPaint);
    }

    /**
     * 在圆柱面上绘制一个数据项。
     * 不使用透明度渐变，靠 clipRect 物理裁剪上下项超出滚轮的区域。
     * @param angle 该项在圆柱上的角度：0°=正面，正值=上方，负值=下方，≥90°=背面不绘制
     * @param rcy 滚轮旋转中心Y坐标
     * @param rollerLeft/rollerRight 滚轮左右边界，用于画凹槽线
     */
    private void drawRollerItem(Canvas canvas, int mode, float angle,
                                float cx, float rcy, int w, int h,
                                int activeColor, int activeGlow, int labelColor,
                                float rollerLeft, float rollerRight) {
        // 归一化到 [-180, 180]
        while (angle > 180f) angle -= 360f;
        while (angle < -180f) angle += 360f;
        float absAngle = Math.abs(angle);

        // 背面（≥90°）跳过
        if (absAngle >= 90f) return;

        // 不用透明度渐变，保持完整可见，靠 clipRect 裁剪
        int alpha = 255;

        // 圆柱半径：决定上下项的间距
        float radius = h * 0.30f;
        // 该项在圆柱面上的 Y 偏移（正=上方，负=下方）
        float yOnCylinder = (float) Math.sin(angle * Math.PI / 180f) * radius;

        // 先平移到圆柱面对应位置，再以该项中心为 pivot 做 rotateX
        canvas.save();
        canvas.translate(0, yOnCylinder);
        camera.save();
        camera.rotateX(angle);
        camera.getMatrix(matrix);
        camera.restore();
        float pivotY = rcy;
        matrix.preTranslate(-cx, -pivotY);
        matrix.postTranslate(cx, pivotY);
        canvas.concat(matrix);

        drawDigits(canvas, w, h, rcy, mode, alpha, activeColor, activeGlow, labelColor, rollerLeft, rollerRight);
        canvas.restore();
    }

    /** 画银灰色金属圆柱滚轮背景，高光位置随旋转角度移动 */
    private void drawRollerBackground(Canvas canvas, float left, float top, float right, float bottom, float rotation) {
        // 高光中心随旋转角度偏移：模拟圆柱转动时光照位置变化
        float highlightOffset = (float) Math.sin(rotation * Math.PI / 180f) * 0.12f; // ±12%
        float highlightCenter = 0.5f + highlightOffset;

        // 与速度仪表盘一致的银灰色金属配色
        // 日间：高光 0xFFA0ADB8 → 中间色 0xFF556070 → 暗面 0xFF2A3540
        // 夜间：高光 0xFFBBC8D4 → 中间色 0xFF8899AA → 暗面 0xFF4A5A6A
        int colorHighlight = isNightMode ? 0xFFBBC8D4 : 0xFFA0ADB8;
        int colorMid = isNightMode ? 0xFF8899AA : 0xFF556070;
        int colorShadow = isNightMode ? 0xFF4A5A6A : 0xFF2A3540;

        // 圆柱面渐变：暗面→中间色→高光→中间色→暗面（模拟圆柱凸面光照）
        float h0 = Math.max(0f, highlightCenter - 0.30f);
        float h1 = Math.max(0f, highlightCenter - 0.15f);
        float h2 = highlightCenter;
        float h3 = Math.min(1f, highlightCenter + 0.15f);
        float h4 = Math.min(1f, highlightCenter + 0.30f);

        int[] colors = {colorShadow, colorMid, colorHighlight, colorMid, colorShadow};
        LinearGradient gradient = new LinearGradient(0, top, 0, bottom, colors,
                new float[]{h0, h1, h2, h3, h4}, Shader.TileMode.CLAMP);

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setShader(gradient);

        float cornerRadius = (bottom - top) * 0.15f;
        RectF rect = new RectF(left, top, right, bottom);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint);

        // 金属边框（外圈亮银色，与仪表盘一致）
        bgPaint.setShader(null);
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(1.5f);
        bgPaint.setColor(isNightMode ? 0xFF99AABB : 0xFF8899AA);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint);
    }

    /** 画圆柱面纹理线：多条等距凹槽线随旋转角度做透视移动，让滚轮本身可见转动 */
    private void drawCylinderTexture(Canvas canvas, float cx, float rcy,
                                     float left, float right, float rotation, float radius, int h) {
        Paint texPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        texPaint.setStyle(Paint.Style.STROKE);

        // 圆柱面上等距分布14条纹理线，间隔角度
        int lineCount = 14;
        float angleStep = 360f / lineCount;

        for (int i = 0; i < lineCount; i++) {
            float angle = i * angleStep + rotation;
            // 归一化到 [-180, 180]
            while (angle > 180f) angle -= 360f;
            while (angle < -180f) angle += 360f;
            float absAngle = Math.abs(angle);

            // 背面跳过
            if (absAngle >= 90f) continue;

            // Y 位置：随角度在圆柱面上移动
            float y = rcy + (float) Math.sin(angle * Math.PI / 180f) * radius;
            // 透视压缩：越靠边缘越窄
            float cosVal = (float) Math.cos(absAngle * Math.PI / 180f);
            // 透明度：正面最暗清晰，边缘渐淡
            int alpha = (int)(100f * cosVal * cosVal);
            if (alpha < 6) continue;

            texPaint.setColor(isNightMode ? 0xFFBBC8D4 : 0xFFA0ADB8);
            texPaint.setAlpha(alpha);
            texPaint.setStrokeWidth(1f);
            canvas.drawLine(left + 1, y, right - 1, y, texPaint);
        }
    }

    /** 滚轮上下边缘阴影遮罩，增强圆柱边界感 */
    private void drawRollerEdges(Canvas canvas, float left, float top, float right, float bottom) {
        Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float edgeH = (bottom - top) * 0.15f;

        // 上边缘暗影
        LinearGradient topShade = new LinearGradient(0, top, 0, top + edgeH,
                new int[]{0x55000000, 0x00000000}, new float[]{0, 1f}, Shader.TileMode.CLAMP);
        edgePaint.setShader(topShade);
        edgePaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(left, top, right, top + edgeH, edgePaint);

        // 下边缘暗影
        LinearGradient bottomShade = new LinearGradient(0, bottom - edgeH, 0, bottom,
                new int[]{0x00000000, 0x55000000}, new float[]{0, 1f}, Shader.TileMode.CLAMP);
        edgePaint.setShader(bottomShade);
        canvas.drawRect(left, bottom - edgeH, right, bottom, edgePaint);
    }

    /** 只绘制 LED 数字（不画标签），标签已由 drawLabel 固定绘制
     *  凹槽模式：去小数点/逗号，每个数字等宽占一个竖向凹槽，最后一位小数红色
     *  @param rcy 滚轮中心Y坐标，数字以此为基准垂直居中
     *  @param rollerLeft/rollerRight 滚轮边界，用于凹槽宽度计算 */
    private void drawDigits(Canvas canvas, int w, int h, float rcy, int mode, int alpha,
                             int activeColor, int activeGlow, int labelColor,
                             float rollerLeft, float rollerRight) {
        float value;
        boolean hasDecimal;  // 是否有小数位（决定最后一位是否红色）

        // 统一格式化：有小数的模式用 %.1f，无小数的用 %.0f
        if (mode == MODE_OVERALL) {
            value = overallFuelLPer100km;
            hasDecimal = true;
        } else if (mode == MODE_RECENT) {
            value = recentFuelLPer100km;
            hasDecimal = true;
        } else if (mode == MODE_RANGE) {
            value = rangeKm;
            hasDecimal = false;
        } else if (mode == MODE_PERCENT) {
            value = remainingPercent;
            hasDecimal = false;
        } else {
            switch (mode) {
                case MODE_TODAY: value = todayKm; break;
                case MODE_TOTAL: value = totalKm; break;
                default: value = tripKm; break;
            }
            hasDecimal = true;
        }

        // 格式化后去掉小数点和逗号，得到纯数字字符串
        String rawStr = hasDecimal ? String.format("%.1f", value) : String.format("%.0f", value);
        String numStr = rawStr.replace(".", "").replace(",", "");

        // 余量<=10%时全红
        if (mode == MODE_PERCENT && remainingPercent <= 10f) {
            activeColor = 0xFFFF4444;
            activeGlow = 0xFFCC0000;
        }

        // 凹槽布局：滚轮内宽 ÷ 位数 = 每个凹槽宽度
        float slotPadding = (rollerRight - rollerLeft) * 0.04f;  // 两侧留白
        float slotAreaW = (rollerRight - rollerLeft) - slotPadding * 2f;
        int digitCount = numStr.length();
        float slotW = slotAreaW / digitCount;
        float slotGap = slotW * 0.08f;  // 凹槽间距
        float slotInnerW = slotW - slotGap;

        // 数字尺寸：根据凹槽宽度自适应，高度不变
        float digitH = h * 0.21f;
        float digitW = Math.min(slotInnerW * 0.7f, digitH * 0.5f);  // 数字宽不超过槽宽70%
        float segThick = digitH * 0.14f;
        float segGap = digitH * 0.06f;

        float slotTop = rcy - digitH / 2f - digitH * 0.15f;
        float slotH = digitH * 1.3f;
        float ledTop = rcy - digitH / 2f;

        // 绘制每个凹槽 + 数字
        for (int i = 0; i < digitCount; i++) {
            char c = numStr.charAt(i);
            float slotX = rollerLeft + slotPadding + i * slotW;
            float slotCenterX = slotX + slotW / 2f;

            // 画凹槽背景（凹陷效果）
            drawSlot(canvas, slotX + slotGap / 2f, slotTop, slotX + slotW - slotGap / 2f, slotTop + slotH);

            // 最后一位是小数位时红色
            boolean isLastDecimal = hasDecimal && (i == digitCount - 1);
            int digitColor = isLastDecimal ? 0xFFFF4444 : activeColor;
            int digitGlow = isLastDecimal ? 0xFFCC0000 : activeGlow;

            // 数字居中在凹槽内
            float digitX = slotCenterX - digitW / 2f;
            if (c >= '0' && c <= '9') {
                LEDDigitHelper.drawLEDDigit(canvas, digitX, ledTop, digitW, digitH, segThick, segGap,
                        c - '0', digitColor, digitGlow, alpha);
            }
        }
    }

    /** 画凹槽背景：凹陷效果（上亮下暗渐变 + 内边框） */
    private void drawSlot(Canvas canvas, float left, float top, float right, float bottom) {
        Paint slotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // 凹陷渐变：上暗下亮（模拟上方被遮挡、下方有反光）
        int colorTop = isNightMode ? 0xFF15151A : 0xFF2A3540;
        int colorBottom = isNightMode ? 0xFF2A2A32 : 0xFF4A5A6A;
        LinearGradient slotGradient = new LinearGradient(0, top, 0, bottom,
                new int[]{colorTop, colorBottom}, null, Shader.TileMode.CLAMP);
        slotPaint.setShader(slotGradient);
        slotPaint.setStyle(Paint.Style.FILL);

        float cornerRadius = (bottom - top) * 0.12f;
        RectF rect = new RectF(left, top, right, bottom);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, slotPaint);

        // 内边框
        slotPaint.setShader(null);
        slotPaint.setStyle(Paint.Style.STROKE);
        slotPaint.setStrokeWidth(1f);
        slotPaint.setColor(isNightMode ? 0xFF3A3A44 : 0xFF556070);
        slotPaint.setAlpha(120);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, slotPaint);
    }
}
