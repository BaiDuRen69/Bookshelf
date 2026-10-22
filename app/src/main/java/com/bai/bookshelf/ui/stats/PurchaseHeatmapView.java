package com.bai.bookshelf.ui.stats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.ThemeManager;

import java.util.Calendar;

/**
 * 购买日历热力图（「我的阅读」页用，v4.20）。
 *
 * <p>参照微信读书「阅读日历」的样式：颜色深浅表示「当天买了几本」。
 * 与微信读书不同的是，这里的度量是<b>购买书籍数量</b>而不是阅读时长。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>月模式</b>（{@link #setMonthData}）：整月网格，周一开头，
 *       每个格子是一个色块，日期数字叠在色块正中；点击某天回调具体买了多少本。</li>
 *   <li><b>年模式</b>（{@link #setYearData}）：全年格子按周排列（类似 GitHub 热力图），
 *       紧凑展示，顶部标注月份。</li>
 * </ul>
 *
 * <p>色阶：0 本为浅灰底，1/2/3/4+ 本依次加深（跟随主题主色）。
 *
 * <p>命中测试直接用绘制时记录的矩形，保证「点哪儿就是哪儿」。
 */
public class PurchaseHeatmapView extends View {

    /** 单元格点击回调 */
    public interface OnDayClickListener {
        /**
         * @param year   年
         * @param month0 月（0-based）
         * @param day    日（1-based）
         * @param count  当天购买本数
         */
        void onDayClick(int year, int month0, int day, int count);
    }

    private static final String[] WEEK_HEADS = {"一", "二", "三", "四", "五", "六", "日"};

    /** 单元格色阶（相对主色的透明度）：0 本走浅灰底 */
    private static final int[] LEVEL_ALPHA = {0, 70, 120, 175, 255};

    private boolean yearMode = false;

    // ===== 月模式数据 =====
    private int monthYear = 0;
    private int month0 = 0;
    private int monthDays = 0;
    /** 每天购买本数，下标 0 = 1 号 */
    private int[] monthCounts = new int[0];
    /** 当月第一天下标（周一起始，0=周一） */
    private int firstDayOffset = 0;

    // ===== 年模式数据 =====
    private int yearValue = 0;
    /** 全年每天购买本数，下标 0 = 1 月 1 日 */
    private int[] yearCounts = new int[0];

    private OnDayClickListener onDayClick;

    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint monthLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * 绘制时记录每个可点击单元格的位置，用于精确命中测试。
     *
     * <p>用可增长的数组而不是 List，避免每次 onDraw 都 new 几十个对象
     * （Android 会在 draw 期间做 GC 抖动）。
     */
    private RectF[] hitRects = new RectF[64];
    /** 与 hitRects 一一对应的天序号（月模式=日序 1..31，年模式=全年天序 0-based） */
    private int[] hitDays = new int[64];
    /** 本次绘制实际使用的单元格数 */
    private int hitCount = 0;

    private float cellSize = 0;
    private float cellGap = 0;
    private float headHeight = 0;
    private float rowGap = 0;

    public PurchaseHeatmapView(Context context) {
        this(context, null);
    }

    public PurchaseHeatmapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        numberPaint.setTextAlign(Paint.Align.CENTER);
        headPaint.setTextAlign(Paint.Align.CENTER);
        monthLabelPaint.setTextAlign(Paint.Align.LEFT);
        setClickable(true);
    }

    // ==================== 对外 API ====================

    /** 设置为年模式并刷新（不改变当前数据） */
    public void setYearMode(boolean yearMode) {
        if (this.yearMode != yearMode) {
            this.yearMode = yearMode;
            requestLayout();
            invalidate();
        }
    }

    /**
     * 月模式数据。
     *
     * @param year    年
     * @param month0  月（0-based）
     * @param counts  每天购买本数，下标 0 = 1 号（长度应等于当月天数）
     */
    public void setMonthData(int year, int month0, int[] counts) {
        this.yearMode = false;
        this.monthYear = year;
        this.month0 = month0;
        this.monthCounts = counts != null ? counts : new int[0];
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month0, 1);
        this.monthDays = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        // Calendar.SUNDAY=1..SATURDAY=7 → 转成周一为 0 的偏移
        int dow = c.get(Calendar.DAY_OF_WEEK);
        this.firstDayOffset = (dow + 5) % 7;
        requestLayout();
        invalidate();
    }

    /**
     * 年模式数据。
     *
     * @param year   年
     * @param counts 全年每天购买本数，下标 0 = 1 月 1 日（长度 365 或 366）
     */
    public void setYearData(int year, int[] counts) {
        this.yearMode = true;
        this.yearValue = year;
        this.yearCounts = counts != null ? counts : new int[0];
        requestLayout();
        invalidate();
    }

    public void setOnDayClickListener(OnDayClickListener l) {
        this.onDayClick = l;
    }

    // ==================== 测量与绘制 ====================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (yearMode) {
            // 年模式固定 12dp 格子，总宽可能超过屏幕 → 交给外层 HorizontalScrollView 横向滚动
            setMeasuredDimension(yearContentWidth(), measureYear(0));
            return;
        }
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = measureMonth(w);
        setMeasuredDimension(w, h);
    }

    private int measureMonth(int w) {
        if (w <= 0) {
            return 0;
        }
        cellGap = dp(4);
        cellSize = (w - cellGap * 6) / 7f;
        headHeight = sp(14);
        rowGap = dp(8);
        int weeks = (firstDayOffset + monthDays + 6) / 7;
        float h = headHeight + weeks * cellSize + (weeks - 1) * rowGap + dp(2);
        return (int) Math.ceil(h);
    }

    private int measureYear(int w) {
        // 全年按周排列：7 行 × N 列
        int weeks = yearWeeks();
        cellGap = dp(3);
        // 左侧留出月份标签的空间
        float labelW = dp(28);
        // 年视图的格子固定 12dp：53 列如果压到屏幕宽里每格只有 5dp，根本点不中。
        // 固定尺寸后整张图比屏幕宽，由外层 HorizontalScrollView 横向滚动。
        cellSize = dp(12);
        headHeight = sp(13);
        rowGap = cellGap;
        float h = headHeight + 7 * cellSize + 6 * rowGap + dp(2);
        return (int) Math.ceil(h);
    }

    /** 年模式下的内容总宽度（供外层 HorizontalScrollView 决定滚动范围） */
    public int yearContentWidth() {
        int weeks = yearWeeks();
        float labelW = dp(28);
        return (int) Math.ceil(labelW + weeks * (dp(12) + dp(3)) - dp(3));
    }

    /** 当年需要多少列（周）：首日偏移 + 全年天数，向上取整到整周 */
    private int yearWeeks() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(yearValue, 0, 1);
        int firstOffset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int len = c.getActualMaximum(Calendar.DAY_OF_YEAR);
        return (firstOffset + len + 6) / 7;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        hitCount = 0;
        if (yearMode) {
            drawYear(canvas, w);
        } else {
            drawMonth(canvas, w);
        }
    }

    /** 记录一个可点击单元格（数组按需扩容，重复绘制不产生新对象） */
    private void addHit(float left, float top, float right, float bottom, int day) {
        if (hitCount >= hitRects.length) {
            int newLen = hitRects.length * 2;
            RectF[] newRects = new RectF[newLen];
            int[] newDays = new int[newLen];
            System.arraycopy(hitRects, 0, newRects, 0, hitRects.length);
            System.arraycopy(hitDays, 0, newDays, 0, hitDays.length);
            hitRects = newRects;
            hitDays = newDays;
        }
        if (hitRects[hitCount] == null) {
            hitRects[hitCount] = new RectF();
        }
        hitRects[hitCount].set(left, top, right, bottom);
        hitDays[hitCount] = day;
        hitCount++;
    }

    // ---------------- 月模式 ----------------

    private void drawMonth(Canvas canvas, int w) {
        if (monthDays <= 0) {
            return;
        }
        int primary = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        int onSurfaceVariant = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        int surfaceDim = ThemeManager.resolveColor(getContext(), R.attr.surface_dim);

        cellGap = dp(4);
        cellSize = (w - cellGap * 6) / 7f;
        headHeight = sp(14);
        rowGap = dp(8);
        int weeks = (firstDayOffset + monthDays + 6) / 7;

        // 星期表头
        headPaint.setColor(onSurfaceVariant);
        headPaint.setTextSize(sp(10));
        for (int i = 0; i < 7; i++) {
            float cx = cellGap + cellSize * i + cellSize / 2f;
            canvas.drawText(WEEK_HEADS[i], cx, headHeight - sp(3), headPaint);
        }

        // 日期数字叠在色块正中
        numberPaint.setTextSize(sp(10));
        RectF rect = new RectF();
        for (int day = 1; day <= monthDays; day++) {
            int idx = firstDayOffset + day - 1;
            int col = idx % 7;
            int row = idx / 7;
            float left = cellGap + cellSize * col;
            float top = headHeight + row * (cellSize + rowGap);
            rect.set(left, top, left + cellSize, top + cellSize);

            int cnt = (day - 1 < monthCounts.length && monthCounts[day - 1] > 0)
                    ? monthCounts[day - 1] : 0;
            cellPaint.setColor(colorFor(cnt, primary, surfaceDim));
            canvas.drawRoundRect(rect, dp(6), dp(6), cellPaint);

            // 深色格子用白字，浅色格子用次级色字，保证对比度
            numberPaint.setColor(cnt >= 3 ? 0xFFFFFFFF : onSurfaceVariant);
            float baseline = top + cellSize / 2f - (numberPaint.ascent() + numberPaint.descent()) / 2f;
            canvas.drawText(String.valueOf(day), left + cellSize / 2f, baseline, numberPaint);

            addHit(left, top, left + cellSize, top + cellSize, day);
        }
    }

    // ---------------- 年模式 ----------------

    private void drawYear(Canvas canvas, int w) {
        if (yearCounts.length == 0) {
            return;
        }
        int primary = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        int onSurfaceVariant = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        int surfaceDim = ThemeManager.resolveColor(getContext(), R.attr.surface_dim);

        cellGap = dp(3);
        int weeks = yearWeeks();
        float labelW = dp(28);
        cellSize = dp(12);
        headHeight = sp(13);
        rowGap = cellGap;

        // 年初第一天下标（周一为 0）
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(yearValue, 0, 1);
        int firstOffset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;

        // 顶部月份标签：每月第一个格子处标注，与上一个标签至少间隔 4 周
        monthLabelPaint.setColor(onSurfaceVariant);
        monthLabelPaint.setTextSize(sp(8));
        int lastLabelCol = -99;
        for (int m = 0; m < 12; m++) {
            int dayIdx = dayIndexOfFirstOfMonth(yearValue, m);
            if (dayIdx < 0) {
                continue;
            }
            int col = (firstOffset + dayIdx) / 7;
            if (col >= weeks) {
                continue;
            }
            if (col - lastLabelCol < 4) {
                continue;
            }
            float lx = labelW + col * (cellSize + cellGap);
            if (lx + dp(16) < w) {
                canvas.drawText((m + 1) + "月", lx, headHeight - sp(2), monthLabelPaint);
                lastLabelCol = col;
            }
        }

        RectF rect = new RectF();
        for (int i = 0; i < yearCounts.length; i++) {
            int idx = firstOffset + i;
            int col = idx / 7;
            int row = idx % 7;
            if (col >= weeks) {
                break;
            }
            int cnt = yearCounts[i] > 0 ? yearCounts[i] : 0;
            float left = labelW + col * (cellSize + cellGap);
            float top = headHeight + row * (cellSize + rowGap);
            rect.set(left, top, left + cellSize, top + cellSize);
            cellPaint.setColor(colorFor(cnt, primary, surfaceDim));
            canvas.drawRoundRect(rect, dp(2), dp(2), cellPaint);

            addHit(left, top, left + cellSize, top + cellSize, i);
        }
    }
    /** 指定年月的 1 号是全年第几天（0-based），找不到返回 -1 */
    private static int dayIndexOfFirstOfMonth(int year, int month0) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, 0, 1);
        int len = c.getActualMaximum(Calendar.DAY_OF_YEAR);
        Calendar m = Calendar.getInstance();
        m.clear();
        m.set(year, month0, 1);
        int idx = m.get(Calendar.DAY_OF_YEAR) - 1;
        return (idx >= 0 && idx < len) ? idx : -1;
    }

    /** 按购买本数取色阶 */
    private int colorFor(int count, int primary, int surfaceDim) {
        if (count <= 0) {
            return surfaceDim;
        }
        int level = count >= 4 ? 4 : count;
        int alpha = LEVEL_ALPHA[level];
        return (alpha << 24) | (primary & 0x00FFFFFF);
    }

    // ==================== 点击 ====================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            int idx = hitTest(event.getX(), event.getY());
            if (idx >= 0 && onDayClick != null) {
                int dayRef = hitDays[idx];
                if (yearMode) {
                    Calendar c = Calendar.getInstance();
                    c.clear();
                    c.set(yearValue, 0, 1);
                    c.add(Calendar.DAY_OF_YEAR, dayRef);
                    int cnt = dayRef < yearCounts.length ? yearCounts[dayRef] : 0;
                    onDayClick.onDayClick(yearValue, c.get(Calendar.MONTH),
                            c.get(Calendar.DAY_OF_MONTH), cnt);
                } else {
                    int cnt = (dayRef - 1 < monthCounts.length && monthCounts[dayRef - 1] > 0)
                            ? monthCounts[dayRef - 1] : 0;
                    onDayClick.onDayClick(monthYear, month0, dayRef, cnt);
                }
                performClick();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** 命中测试：返回命中格子的下标，未命中返回 -1（外扩 1dp 容差，照顾手指点击） */
    private int hitTest(float x, float y) {
        float slop = dp(1);
        for (int i = 0; i < hitCount; i++) {
            RectF r = hitRects[i];
            if (r == null) {
                continue;
            }
            if (x >= r.left - slop && x <= r.right + slop
                    && y >= r.top - slop && y <= r.bottom + slop) {
                return i;
            }
        }
        return -1;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
