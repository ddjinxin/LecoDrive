package com.jingxin.pandrive.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Scroller;

import com.jingxin.pandrive.data.DataHub;
import com.tyme.solar.SolarDay;
import com.tyme.solar.SolarTerm;
import com.tyme.lunar.LunarDay;
import com.tyme.sixtycycle.SixtyCycleDay;
import com.tyme.culture.Zodiac;
import com.tyme.culture.Week;
import com.tyme.culture.Phase;
import com.tyme.culture.Taboo;
import com.tyme.holiday.LegalHoliday;
import com.tyme.festival.SolarFestival;
import com.tyme.festival.LunarFestival;

import java.util.Calendar;
import java.util.List;

/**
 * 科幻金属风翻页日历。
 * <p>
 * 上下滑动切换日期：上滑=下一天，下滑=上一天，带翻页滚动动画。
 * <p>
 * 布局从上到下：
 * - 顶部 LED 流光条 + 年月标题 + 星期
 * - 上半区：左侧农历（大字竖排）| 右侧超大公历日期数字（LED 发光）
 * - 中间分隔线
 * - 下半区：干支年·生肖 + 节气/节日
 * <p>
 * 数据来源：tyme4j 日历库。
 */
public class CalendarView extends View {

    private boolean isNightMode = false;

    private ValueAnimator shimmerAnimator;
    private float shimmerPhase = 0f;

    // 颜色
    private int colorLedOn, colorLedOnGlow;
    private int colorText, colorTextSecondary, colorTextMuted;
    private int colorPanelBg, colorBorder;
    private int colorWeekend;
    private int colorHoliday;   // 节日/节气红色

    // Paint 缓存
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panelRect = new RectF();
    private final RectF shimmerRect = new RectF();

    // ========== 日期数据 ==========
    /** 当前显示的日期偏移量（0=今天，1=明天，-1=昨天） */
    private int dayOffset = 0;
    /** 翻页动画期间的起始偏移 */
    private int animFromOffset = 0;
    /** 翻页动画期间的目标偏移 */
    private int animToOffset = 0;

    // 缓存的日历数据（按 dayOffset 对应的日期）
    private int displayYear, displayMonth, displayDay;
    private String weekdayName;
    private String lunarMonthName;
    private String lunarDayName;
    private String ganZhiYearText;
    private String zodiacText;
    private String solarTermText;
    private String festivalText;
    private String holidayText;   // 法定假日标记（休/班）
    private String phaseText;     // 月相
    private String recommendText; // 宜
       private String avoidText;     // 忌

    // 自动回今天：每分钟检查
    private final Runnable tickRunnable = this::tick;
    private static final long TICK_INTERVAL_MS = 60000;

    private float viewW, viewH;

    // ========== 翻页手势 ==========
    private final Scroller scroller;
    private final int touchSlop;
    private boolean verticalDragging = false;
    private float downX, downY;
    private float lastMoveY;
    /** 翻页动画进度 0~1 */
    private float flipProgress = 0f;
    private boolean flipAnimating = false;
    /** 手势拖拽偏移像素（正值=向下滑=上一天方向） */
    private float dragOffsetY = 0f;

    public CalendarView(Context context) {
        super(context);
        scroller = new Scroller(context);
        touchSlop = (int) (ViewConfiguration.get(context).getScaledTouchSlop() * 1.5f);
        init();
    }

    public CalendarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        scroller = new Scroller(context);
        touchSlop = (int) (ViewConfiguration.get(context).getScaledTouchSlop() * 1.5f);
        init();
    }

    public CalendarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scroller = new Scroller(context);
        touchSlop = (int) (ViewConfiguration.get(context).getScaledTouchSlop() * 1.5f);
        init();
    }

    private void init() {
        startShimmerAnimation();
        updateCalendarData();
    }

    private void startShimmerAnimation() {
        if (shimmerAnimator != null) return;
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
        shimmerAnimator.setDuration(4000);
        shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shimmerAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        shimmerAnimator.addUpdateListener(a -> {
            shimmerPhase = (float) a.getAnimatedValue();
            invalidate();
        });
        shimmerAnimator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = (int) (250 * getResources().getDisplayMetrics().density);
        }
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            height = width;
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        viewW = w;
        viewH = h;
    }

    private void updateColors() {
        DataHub dh = DataHub.getInstance(getContext());
        int calAlpha = dh.getCalBgAlpha();
        if (isNightMode) {
            colorLedOn = GaugeDrawHelper.LED_GREEN;
            colorLedOnGlow = GaugeDrawHelper.GLOW_GREEN;
            colorText = 0xFFFFFFFF;
            colorTextSecondary = 0xFFBBCDDD;
            colorTextMuted = 0xFF778899;
            colorPanelBg = (calAlpha << 24) | (dh.getCalNightBgColor() & 0x00FFFFFF);
            colorBorder = GaugeDrawHelper.STEEL_DARK;
            colorWeekend = 0xFF6688AA;
            colorHoliday = 0xFFFF4444;
        } else {
            colorLedOn = GaugeDrawHelper.LED_CYAN;
            colorLedOnGlow = GaugeDrawHelper.GLOW_CYAN;
            colorText = 0xFF1A2530;
            colorTextSecondary = 0xFF334455;
            colorTextMuted = 0xFF667788;
            colorPanelBg = (calAlpha << 24) | (dh.getCalDayBgColor() & 0x00FFFFFF);
            colorBorder = GaugeDrawHelper.STEEL_LIGHT;
            colorWeekend = 0xFF556677;
            colorHoliday = 0xFFCC2222;
        }
    }

    public void setNightMode(boolean nightMode) {
        if (this.isNightMode != nightMode) {
            this.isNightMode = nightMode;
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(tickRunnable, TICK_INTERVAL_MS);
        if (shimmerAnimator != null && !shimmerAnimator.isStarted()) {
            shimmerAnimator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(tickRunnable);
        if (shimmerAnimator != null) shimmerAnimator.cancel();
    }

    private void tick() {
        // 如果用户在看其他日期，不自动跳回今天，只刷新
        updateCalendarData();
        invalidate();
        postDelayed(tickRunnable, TICK_INTERVAL_MS);
    }

    /**
     * 根据 dayOffset 计算对应日期，更新所有日历数据。
     */
    private void updateCalendarData() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, dayOffset);

        displayYear = cal.get(Calendar.YEAR);
        displayMonth = cal.get(Calendar.MONTH) + 1;
        displayDay = cal.get(Calendar.DAY_OF_MONTH);

        SolarDay solarDay = SolarDay.fromYmd(displayYear, displayMonth, displayDay);
        LunarDay lunarDay = solarDay.getLunarDay();
        SixtyCycleDay sixtyCycleDay = solarDay.getSixtyCycleDay();

        Week week = solarDay.getWeek();
        String[] fullWeekNames = {"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};
        weekdayName = fullWeekNames[week.getIndex()];

        lunarMonthName = lunarDay.getLunarMonth().getName();
        lunarDayName = lunarDay.getName();

        ganZhiYearText = sixtyCycleDay.getYear().getName() + "年";
        Zodiac zodiac = Zodiac.fromIndex(sixtyCycleDay.getYear().getEarthBranch().getIndex());
        zodiacText = zodiac.getName();

        SolarTerm term = solarDay.getTerm();
        solarTermText = term.getName();

        festivalText = null;
        holidayText = null;
        LegalHoliday legalHoliday = solarDay.getLegalHoliday();
        if (legalHoliday != null && legalHoliday.getName() != null) {
            festivalText = legalHoliday.getName();
            holidayText = legalHoliday.isWork() ? "班" : "休";
        }
        if (festivalText == null) {
            LunarFestival lf = lunarDay.getFestival();
            if (lf != null) festivalText = lf.getName();
        }
        if (festivalText == null) {
            SolarFestival sf = solarDay.getFestival();
            if (sf != null) festivalText = sf.getName();
        }

        // 月相
        Phase phase = solarDay.getPhase();
        phaseText = phase.getName();

        // 宜忌
        List<Taboo> recommends = sixtyCycleDay.getRecommends();
        List<Taboo> avoids = sixtyCycleDay.getAvoids();
        recommendText = joinTaboos(recommends, 3);
        avoidText = joinTaboos(avoids, 3);
    }

    /**
     * 将宜忌列表拼接为逗号分隔的字符串，最多取 count 个。
     */
    private static String joinTaboos(List<Taboo> taboos, int count) {
        if (taboos == null || taboos.isEmpty()) return "";
        int n = Math.min(taboos.size(), count);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(" ");
            sb.append(taboos.get(i).getName());
        }
        return sb.toString();
    }

    // ========== 翻页手势 ==========

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                lastMoveY = event.getY();
                verticalDragging = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dy = event.getY() - downY;
                float dx = event.getX() - downX;

                if (!verticalDragging) {
                    // 检测垂直滑动意图
                    if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                        verticalDragging = true;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }

                if (verticalDragging) {
                    // dragOffsetY > 0 = 手指向下 = 显示上一天（内容向上翻）
                    // dragOffsetY < 0 = 手指向上 = 显示下一天（内容向下翻）
                    dragOffsetY = event.getY() - lastMoveY;
                    lastMoveY = event.getY();
                    // 累加到滚动偏移
                    flipProgress += dragOffsetY / viewH;
                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (verticalDragging) {
                    // 翻页判定：滑动超过半页就翻页
                    if (flipProgress > 0.5f) {
                        // 向下滑了半页以上 → 上一天
                        animateFlip(-1);
                    } else if (flipProgress < -0.5f) {
                        // 向上滑了半页以上 → 下一天
                        animateFlip(1);
                    } else {
                        // 不足半页，回弹
                        animateFlip(0);
                    }
                    verticalDragging = false;
                    return true;
                }
                break;
        }
        return false;
    }

    /**
     * 执行翻页动画。
     * @param direction 1=下一天, -1=上一天, 0=回弹到当前
     */
    private void animateFlip(int direction) {
        if (direction == 0) {
            // 回弹：从当前 flipProgress 回到 0
            float start = flipProgress;
            float end = 0f;
            ValueAnimator va = ValueAnimator.ofFloat(start, end);
            va.setDuration(200);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator());
            va.addUpdateListener(a -> {
                flipProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            va.start();
        } else {
            // 翻页：从当前 flipProgress 到 direction
            float start = flipProgress;
            float end = direction;
            int newOffset = dayOffset + direction;

            ValueAnimator va = ValueAnimator.ofFloat(start, end);
            va.setDuration(250);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator());
            va.addUpdateListener(a -> {
                flipProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    dayOffset = newOffset;
                    flipProgress = 0f;
                    updateCalendarData();
                    invalidate();
                }
            });
            va.start();
        }
    }

    @Override
    public void computeScroll() {
        // 目前用 ValueAnimator 做动画，Scroller 预留
    }

    // ========== 绘制 ==========

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateColors();

        float density = getResources().getDisplayMetrics().density;
        float padX = 10f * density;
        float padY = 8f * density;
        float cornerRadius = 8f * density;

        // 面板背景
        rectPaint.setStyle(Paint.Style.FILL);
        rectPaint.setColor(colorPanelBg);
        panelRect.set(padX, padY, viewW - padX, viewH - padY);
        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, rectPaint);

        // 金属边框
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(1.5f * density);
        rectPaint.setColor(colorBorder);
        canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, rectPaint);

        // 顶部 LED 流光条
        float topBarH = 3f * density;
        shimmerRect.set(padX + cornerRadius, padY + 2f * density,
                viewW - padX - cornerRadius, padY + 2f * density + topBarH);
        drawShimmerBar(canvas);

        // 裁剪到面板内部，使翻页滚动不溢出
        canvas.save();
        canvas.clipRect(padX, padY + topBarH + 4f * density, viewW - padX, viewH - padY);

        // 翻页偏移：flipProgress=0 时显示当前页
        // flipProgress=1 时当前页向上移出（上一天），flipProgress=-1 时向下移出（下一天）
        float offsetY = flipProgress * viewH;

        // 绘制当前页（带偏移）
        drawCalendarContent(canvas, padX, padY, viewW, viewH, density, offsetY);

        // 绘制相邻页（填补空白）
        if (flipProgress > 0.01f) {
            // 向下滑 → 下方露出上一天的页面
            drawAdjacentPage(canvas, padX, padY, viewW, viewH, density, offsetY - viewH, -1);
        } else if (flipProgress < -0.01f) {
            // 向上滑 → 上方露出下一天的页面
            drawAdjacentPage(canvas, padX, padY, viewW, viewH, density, offsetY + viewH, 1);
        }

        canvas.restore();
    }

    /**
     * 绘制当前日期内容，带 Y 偏移。
     */
    private void drawCalendarContent(Canvas canvas, float padX, float padY,
                                      float viewW, float viewH, float density, float offsetY) {
        canvas.save();
        canvas.translate(0, offsetY);
        drawPage(canvas, padX, padY, viewW, viewH, density,
                displayYear, displayMonth, displayDay,
                weekdayName, lunarMonthName, lunarDayName,
                ganZhiYearText, zodiacText, solarTermText, festivalText,
                holidayText, phaseText, recommendText, avoidText);
        canvas.restore();
    }

    /**
     * 绘制相邻日期页面（上一天或下一天），带 Y 偏移。
     * @param direction -1=上一天, 1=下一天
     */
    private void drawAdjacentPage(Canvas canvas, float padX, float padY,
                                   float viewW, float viewH, float density,
                                   float offsetY, int direction) {
        // 计算相邻日期数据
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, dayOffset + direction);
        int y = cal.get(Calendar.YEAR);
        int m = cal.get(Calendar.MONTH) + 1;
        int d = cal.get(Calendar.DAY_OF_MONTH);

        SolarDay sd = SolarDay.fromYmd(y, m, d);
        LunarDay ld = sd.getLunarDay();
        SixtyCycleDay scd = sd.getSixtyCycleDay();

        Week w = sd.getWeek();
        String[] weekNames = {"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};
        String wk = weekNames[w.getIndex()];
        String lm = ld.getLunarMonth().getName();
        String ldn = ld.getName();
        String gz = scd.getYear().getName() + "年";
        Zodiac z = Zodiac.fromIndex(scd.getYear().getEarthBranch().getIndex());
        String zo = z.getName();
        SolarTerm st = sd.getTerm();
        String stn = st.getName();

        String fest = null;
        String hol = null;
        LegalHoliday lh = sd.getLegalHoliday();
        if (lh != null && lh.getName() != null) {
            fest = lh.getName();
            hol = lh.isWork() ? "班" : "休";
        }
        if (fest == null) { LunarFestival lf = ld.getFestival(); if (lf != null) fest = lf.getName(); }
        if (fest == null) { SolarFestival sf = sd.getFestival(); if (sf != null) fest = sf.getName(); }

        // 月相
        Phase ph = sd.getPhase();
        String phn = ph.getName();

        // 宜忌
        List<Taboo> recs = scd.getRecommends();
        List<Taboo> avs = scd.getAvoids();
        String rec = joinTaboos(recs, 3);
        String avd = joinTaboos(avs, 3);

        canvas.save();
        canvas.translate(0, offsetY);
        drawPage(canvas, padX, padY, viewW, viewH, density, y, m, d, wk, lm, ldn, gz, zo, stn, fest, hol, phn, rec, avd);
        canvas.restore();
    }

    /**
     * 绘制一个完整的日历页面（无偏移，调用者负责 translate）。
     */
    private void drawPage(Canvas canvas, float padX, float padY,
                          float viewW, float viewH, float density,
                          int year, int month, int day,
                          String weekday, String lunarMonth, String lunarDay,
                          String ganZhi, String zodiac, String solarTerm, String festival,
                          String holiday, String phase, String recommend, String avoid) {

        float topBarH = 3f * density;
        float contentTop = padY + topBarH + 10f * density;
        float contentBottom = viewH - padY - 8f * density;
        float contentLeft = padX + 10f * density;
        float contentRight = viewW - padX - 10f * density;
        float contentW = contentRight - contentLeft;
        float contentH = contentBottom - contentTop;

        // ========== 顶部：年月 + 星期 ==========
        float headerH = contentH * 0.12f;
        float headerY = contentTop + headerH / 2f;

        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(contentH * 0.07f);
        textPaint.setColor(colorText);
        String yearMonth = String.format("%d年%d月", year, month);
        canvas.drawText(yearMonth, contentLeft, headerY - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(contentH * 0.06f);
        textPaint.setColor(colorTextSecondary);
        canvas.drawText(weekday, contentRight, headerY - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);

        // ========== 上半区：超大日期数字 ==========
        float dateAreaTop = contentTop + headerH + contentH * 0.02f;
        float dateAreaH = contentH * 0.48f;
        float dateCenterY = dateAreaTop + dateAreaH / 2f;

        // 左侧竖排农历（大字）
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        float lunarSize = contentH * 0.12f;
        textPaint.setTextSize(lunarSize);
        textPaint.setColor(colorText);
        float lunarX = contentLeft + contentW * 0.22f;
        float lunarY = dateCenterY - lunarSize * 0.1f;
        canvas.drawText(lunarMonth, lunarX, lunarY - lunarSize * 0.55f, textPaint);
        canvas.drawText(lunarDay, lunarX, lunarY + lunarSize * 0.55f, textPaint);

        // 分隔线
        float dividerX = contentLeft + contentW * 0.42f;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1f * density);
        linePaint.setColor(colorBorder);
        canvas.drawLine(dividerX, dateAreaTop + dateAreaH * 0.15f,
                dividerX, dateAreaTop + dateAreaH * 0.85f, linePaint);

        // 超大日期数字
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        float bigSize = Math.min(contentW * 0.35f, dateAreaH * 0.75f);
        textPaint.setTextSize(bigSize);
        textPaint.setColor(colorHoliday);
        textPaint.setShadowLayer(bigSize * 0.08f, 0, 0, colorHoliday);
        float bigNumX = contentRight - contentW * 0.28f;
        float bigNumY = dateCenterY + bigSize * 0.25f;
        canvas.drawText(String.valueOf(day), bigNumX, bigNumY, textPaint);
        textPaint.setShadowLayer(0, 0, 0, 0);

        // 节气/节日 + 休/班标记 → 大数字右下角小字
        String infoRight = festival != null ? festival : solarTerm;
        if (infoRight != null) {
            float termSize = contentH * 0.045f;
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(termSize);
            textPaint.setColor(colorHoliday);
            // 测量文字宽度，右对齐到大数字右边缘
            float termW = textPaint.measureText(infoRight);
            float termX = contentRight;
            float termY = bigNumY + termSize * 1.1f;
            if (termX - termW < contentLeft + contentW * 0.44f) {
                // 太长则右截断区域不够，缩小字号
                termSize = contentH * 0.035f;
                textPaint.setTextSize(termSize);
                termW = textPaint.measureText(infoRight);
            }
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(infoRight, termX, termY, textPaint);

            // 休/班标记在节气文字左侧
            if (holiday != null) {
                textPaint.setTextAlign(Paint.Align.RIGHT);
                textPaint.setTextSize(termSize * 0.75f);
                textPaint.setColor(holiday.equals("班") ? 0xFFFF6600 : colorHoliday);
                canvas.drawText(holiday, termX - termW - termSize * 0.2f, termY, textPaint);
            }
        }

        // ========== 中间分隔线 ==========
        float midDividerY = dateAreaTop + dateAreaH + contentH * 0.03f;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(0.5f * density);
        linePaint.setColor(colorBorder);
        canvas.drawLine(contentLeft, midDividerY, contentRight, midDividerY, linePaint);

        // ========== 下半区：两行信息 ==========
        float infoTop = midDividerY + contentH * 0.03f;
        float infoH = contentBottom - infoTop;
        float lineH = infoH / 2f;
        float smallSize = contentH * 0.06f;
        float tinySize = contentH * 0.055f;

        // 第一行：干支·生肖 | 月相
        float line1Y = infoTop + lineH * 0.5f;
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(smallSize);
        textPaint.setColor(colorTextSecondary);
        String infoLeft = ganZhi + " · " + zodiac;
        canvas.drawText(infoLeft, contentLeft, line1Y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(colorTextMuted);
        textPaint.setTextSize(tinySize);
        canvas.drawText(phase, contentRight, line1Y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);

        // 第二行：宜 | 忌
        float line2Y = infoTop + lineH * 1.5f;
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(tinySize);
        textPaint.setColor(colorTextSecondary);
        canvas.drawText("宜: " + recommend, contentLeft, line2Y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(colorTextMuted);
        canvas.drawText("忌: " + avoid, contentRight, line2Y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);
    }

    private void drawShimmerBar(Canvas canvas) {
        int ledColor = isNightMode ? GaugeDrawHelper.LED_GREEN : GaugeDrawHelper.LED_CYAN;
        float barW = shimmerRect.width();
        int segments = 60;

        shimmerPaint.setStyle(Paint.Style.STROKE);
        shimmerPaint.setStrokeWidth(shimmerRect.height());
        shimmerPaint.setStrokeCap(Paint.Cap.ROUND);

        float glowCenter = shimmerPhase * 0.85f;
        float glowWidth = 0.15f;

        for (int i = 1; i < segments; i++) {
            float frac = (float) i / (segments - 1);
            float dist = Math.abs(frac - glowCenter);
            int alpha;
            if (dist < glowWidth) {
                alpha = (int) (255 * (1f - dist / glowWidth));
            } else {
                alpha = 0x20;
            }
            int color = (alpha << 24) | (ledColor & 0x00FFFFFF);
            shimmerPaint.setColor(color);

            float x1 = shimmerRect.left + ((float) (i - 1) / (segments - 1)) * barW;
            float x2 = shimmerRect.left + frac * barW;
            canvas.drawLine(x1, shimmerRect.centerY(), x2, shimmerRect.centerY(), shimmerPaint);
        }
    }
}
