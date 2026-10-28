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
 * 购买日历热力图（「购书统计」页用，v4.20；v4.22 大幅扩展）。
 *
 * <p>参照微信读书「阅读日历」的样式：颜色深浅表示「当天买了几本」。
 * 与微信读书不同的是，这里的度量是<b>购买书籍数量</b>而不是阅读时长。
 *
 * <p>三种模式：
 * <ul>
 *   <li><b>月模式</b>（{@link #setMonthData}）：整月网格，周一开头，
 *       每个格子是一个色块；点击某天回调具体买了多少本。
 *       {@link #setLabelOutside} 打开后日期数字画在色块<b>上方</b>（微信读书样式）。</li>
 *   <li><b>年模式</b>（{@link #setYearData}）：全年格子按周排列（类似 GitHub 热力图），
 *       紧凑展示，顶部标注月份；宽于屏幕时由外层 HorizontalScrollView 横向滚动。</li>
 *   <li><b>迷你年模式</b>（{@link #setMiniYearData}）：一年 12 个月缩略成
 *       4×3 的小方阵，每个月一个 7 列小网格，用于「按年」视图里一眼看完整年。</li>
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

    /** 迷你年模式里点击某个月份的回调 */
    public interface OnMonthClickListener {
        /**
         * @param year   年
         * @param month0 月（0-based）
         */
        void onMonthClick(int year, int month0);
    }

    private static final String[] WEEK_HEADS = {"一", "二", "三", "四", "五", "六", "日"};

    /** 单元格色阶（相对主色的透明度）：0 本走浅灰底 */
    private static final int[] LEVEL_ALPHA = {0, 70, 120, 175, 255};

    private boolean yearMode = false;
    /** 迷你年模式（12 个月小方阵） */
    private boolean miniYearMode = false;

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

    // ===== 迷你年模式数据 =====
    private int miniYearValue = 0;
    /** 12 个月每天的购买数：miniMonths[月][日-1] */
    private int[][] miniMonths = new int[12][];

    private OnDayClickListener onDayClick;
    private OnMonthClickListener onMonthClick;

    // ===== 选中高亮（v4.22）=====
    /** 选中的日期；-1 表示未选中 */
    private int selectedYear = -1;
    private int selectedMonth = -1;
    private int selectedDay = -1;

    // ===== 呈现样式（v4.22）=====
    /** 是否绘制星期表头（面板里表头是固定的，块内不再重复画） */
    private boolean showHeader = true;
    /** 只画星期表头（面板顶部固定那一行用） */
    private boolean headerOnly = false;
    /** 日期数字画在色块「上方」（微信读书样式），否则画在色块正中 */
    private boolean labelOutside = false;
    /** 是否绘制日期数字（迷你月网格里省略，只留色块） */
    private boolean showNumbers = true;

    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint monthLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 选中日的描边（蓝框） */
    private final Paint selectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * 绘制时记录每个可点击单元格的位置，用于精确命中测试。
     *
     * <p>用可增长的数组而不是 List，避免每次 onDraw 都 new 几十个对象
     * （Android 会在 draw 期间做 GC 抖动）。
     */
    private RectF[] hitRects = new RectF[64];
    /**
     * 与 hitRects 一一对应的天序号。
     * 月模式=日序 1..31；年模式=全年天序 0-based；
     * 迷你年模式=「月*100+日」编码。
     */
    private int[] hitDays = new int[64];
    /** 本次绘制实际使用的单元格数 */
    private int hitCount = 0;

    /** 迷你年模式：每个月小网格的标签位置（用于点击月份） */
    private final RectF[] miniMonthRects = new RectF[12];
    private final int[] miniMonthIds = new int[12];
    private int miniMonthCount = 0;

    private float cellSize = 0;
    private float cellGap = 0;
    private float headHeight = 0;
    private float rowGap = 0;
    /** labelOutside 模式下，每个数字行的高度 */
    private float labelHeight = 0;

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
            this.miniYearMode = false;
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
        this.miniYearMode = false;
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
        this.miniYearMode = false;
        this.yearValue = year;
        this.yearCounts = counts != null ? counts : new int[0];
        requestLayout();
        invalidate();
    }

    /**
     * 迷你年模式数据：一年 12 个月缩略成 4×3 小方阵。
     *
     * @param year   年
     * @param months 12 个月每天的购买数，months[月][日-1]
     */
    public void setMiniYearData(int year, int[][] months) {
        this.yearMode = false;
        this.miniYearMode = true;
        this.miniYearValue = year;
        this.miniMonths = months != null ? months : new int[12][];
        requestLayout();
        invalidate();
    }

    public void setOnDayClickListener(OnDayClickListener l) {
        this.onDayClick = l;
    }

    public void setOnMonthClickListener(OnMonthClickListener l) {
        this.onMonthClick = l;
    }

    /**
     * 设置选中高亮的日期（v4.22）。
     *
     * <p>选中日会画一圈主题色描边（参照微信读书：点中的那天带一圈蓝框）。
     * 月模式下只有年月匹配的格子才描边；年模式下匹配到具体的那一天才描边。
     *
     * @param year  年；传 -1 表示取消选中
     * @param month0 月（0-based）
     * @param day   日（1-based）
     */
    public void setSelectedDay(int year, int month0, int day) {
        if (selectedYear == year && selectedMonth == month0 && selectedDay == day) {
            return;
        }
        this.selectedYear = year;
        this.selectedMonth = month0;
        this.selectedDay = day;
        invalidate();
    }

    // ==================== 呈现样式（v4.22） ====================

    /**
     * 设置为「只画星期表头」模式。
     *
     * <p>微信读书的日历面板顶部有一行固定的「一 二 三 四 五 六 日」，
     * 下方是连续多个月的网格，星期表头不随内容滚动。
     * 本模式让表头可以独立成一个 View，与下方网格列宽保持一致。
     */
    public void setHeaderOnly(boolean headerOnly) {
        if (this.headerOnly != headerOnly) {
            this.headerOnly = headerOnly;
            requestLayout();
            invalidate();
        }
    }

    /** 是否绘制星期表头 */
    public void setShowHeader(boolean showHeader) {
        if (this.showHeader != showHeader) {
            this.showHeader = showHeader;
            requestLayout();
            invalidate();
        }
    }

    /**
     * 日期数字画在色块上方（微信读书样式）。
     *
     * <p>微信读书的月历是「数字在上、色块在下」的两行结构，
     * 而不是把数字叠在色块里，这样色块的颜色深浅更容易辨认。
     */
    public void setLabelOutside(boolean labelOutside) {
        if (this.labelOutside != labelOutside) {
            this.labelOutside = labelOutside;
            requestLayout();
            invalidate();
        }
    }

    /** 是否绘制日期数字（迷你月网格里省略，只保留色块） */
    public void setShowNumbers(boolean showNumbers) {
        if (this.showNumbers != showNumbers) {
            this.showNumbers = showNumbers;
            invalidate();
        }
    }

    // ==================== 测量与绘制 ====================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (miniYearMode) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(w, measureMiniYear(w));
            return;
        }
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
        labelHeight = labelOutside ? sp(13) : 0f;
        if (headerOnly) {
            return (int) Math.ceil(headHeight);
        }
        int weeks = (firstDayOffset + monthDays + 6) / 7;
        float h = (showHeader ? headHeight : 0)
                + weeks * (labelHeight + cellSize) + (weeks - 1) * rowGap + dp(2);
        return (int) Math.ceil(h);
    }

    private int measureYear(int w) {
        // 全年按周排列：7 行 × N 列
        int weeks = yearWeeks();
        cellGap = dp(3);
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

    // ---------------- 迷你年模式测量 ----------------

    private static final int MINI_COLS = 4;
    private static final int MINI_ROWS = 3;
    /** 单个月份小网格最多 6 周（2 月闰年也可能 6 周） */
    private static final int MINI_WEEKS = 6;

    private float miniMonthW = 0;
    private float miniCell = 0;
    private float miniCellGap = 0;
    private float miniLabelH = 0;
    private float miniRowGap = 0;
    private float miniColGap = 0;
    private float miniMonthH = 0;

    private int measureMiniYear(int w) {
        if (w <= 0) {
            return 0;
        }
        miniColGap = dp(8);
        miniRowGap = dp(12);
        miniCellGap = dp(1.5f);
        miniLabelH = sp(10);
        miniMonthW = (w - miniColGap * (MINI_COLS - 1)) / MINI_COLS;
        miniCell = (miniMonthW - miniCellGap * 6) / 7f;
        miniMonthH = miniLabelH + MINI_WEEKS * miniCell + (MINI_WEEKS - 1) * miniCellGap;
        return (int) Math.ceil(MINI_ROWS * miniMonthH + miniRowGap * (MINI_ROWS - 1) + dp(2));
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
        miniMonthCount = 0;
        if (miniYearMode) {
            drawMiniYear(canvas, w);
        } else if (yearMode) {
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
        int primary = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        int onSurfaceVariant = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        int surfaceDim = ThemeManager.resolveColor(getContext(), R.attr.surface_dim);

        cellGap = dp(4);
        cellSize = (w - cellGap * 6) / 7f;
        headHeight = sp(14);
        rowGap = dp(8);
        labelHeight = labelOutside ? sp(13) : 0f;

        // 星期表头（即使没有月数据也要画 —— 面板顶部的固定表头就是这种模式）
        if (showHeader) {
            headPaint.setColor(onSurfaceVariant);
            headPaint.setTextSize(sp(10));
            for (int i = 0; i < 7; i++) {
                float cx = cellGap + cellSize * i + cellSize / 2f;
                canvas.drawText(WEEK_HEADS[i], cx, headHeight - sp(3), headPaint);
            }
        }
        if (headerOnly || monthDays <= 0) {
            return;
        }

        float top0 = showHeader ? headHeight : 0;
        RectF rect = new RectF();
        for (int day = 1; day <= monthDays; day++) {
            int idx = firstDayOffset + day - 1;
            int col = idx % 7;
            int row = idx / 7;
            float left = cellGap + cellSize * col;
            float rowTop = top0 + row * (labelHeight + cellSize + rowGap);
            float cellTop = rowTop + labelHeight;

            int cnt = (day - 1 < monthCounts.length && monthCounts[day - 1] > 0)
                    ? monthCounts[day - 1] : 0;
            rect.set(left, cellTop, left + cellSize, cellTop + cellSize);
            cellPaint.setColor(colorFor(cnt, primary, surfaceDim));
            canvas.drawRoundRect(rect, dp(6), dp(6), cellPaint);

            boolean selected = selectedDay == day && selectedMonth == month0
                    && selectedYear == monthYear;

            // v4.22：选中日描边（蓝框）
            if (selected) {
                selectPaint.setColor(primary);
                selectPaint.setStyle(Paint.Style.STROKE);
                selectPaint.setStrokeWidth(dp(1.5f));
                canvas.drawRoundRect(rect, dp(6), dp(6), selectPaint);
            }

            if (showNumbers) {
                numberPaint.setTextSize(sp(10));
                if (labelOutside) {
                    // 数字画在色块上方（微信读书样式）
                    numberPaint.setColor(selected ? primary : onSurfaceVariant);
                    float base = rowTop + labelHeight - sp(3);
                    canvas.drawText(String.valueOf(day), left + cellSize / 2f, base, numberPaint);
                } else {
                    // 深色格子用白字，浅色格子用次级色字，保证对比度
                    numberPaint.setColor(cnt >= 3 ? 0xFFFFFFFF : onSurfaceVariant);
                    float baseline = cellTop + cellSize / 2f
                            - (numberPaint.ascent() + numberPaint.descent()) / 2f;
                    canvas.drawText(String.valueOf(day), left + cellSize / 2f, baseline, numberPaint);
                }
            }

            addHit(left, cellTop, left + cellSize, cellTop + cellSize, day);
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
        // v4.22：年模式下把选中日期换算成「当年第几天」，用于描边
        int selectedIndex = -1;
        if (selectedYear == yearValue && selectedDay > 0) {
            Calendar sc = Calendar.getInstance();
            sc.clear();
            sc.set(selectedYear, selectedMonth, selectedDay);
            selectedIndex = sc.get(Calendar.DAY_OF_YEAR) - 1;
        }
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

            if (i == selectedIndex) {
                selectPaint.setColor(primary);
                selectPaint.setStyle(Paint.Style.STROKE);
                selectPaint.setStrokeWidth(dp(1.2f));
                canvas.drawRoundRect(rect, dp(2), dp(2), selectPaint);
            }

            addHit(left, top, left + cellSize, top + cellSize, i);
        }
    }

    // ---------------- 迷你年模式 ----------------

    /**
     * 迷你年：一年 12 个月排成 4×3 的小方阵。
     *
     * <p>每个月是一个 7 列的小网格（只画色块，不画数字），
     * 点月份标签可以切到该月的详细视图。
     */
    private void drawMiniYear(Canvas canvas, int w) {
        int primary = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        int onSurfaceVariant = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        int surfaceDim = ThemeManager.resolveColor(getContext(), R.attr.surface_dim);

        monthLabelPaint.setColor(onSurfaceVariant);
        monthLabelPaint.setTextSize(sp(9));
        cellPaint.setColor(surfaceDim);

        RectF rect = new RectF();
        for (int m = 0; m < 12; m++) {
            int col = m % MINI_COLS;
            int row = m / MINI_COLS;
            float mx = col * (miniMonthW + miniColGap);
            float my = row * (miniMonthH + miniRowGap);

            // 月份标签（可点击）
            String label = (m + 1) + "月";
            float tw = monthLabelPaint.measureText(label);
            canvas.drawText(label, mx, my + miniLabelH - sp(2), monthLabelPaint);
            if (miniMonthCount < miniMonthRects.length) {
                if (miniMonthRects[miniMonthCount] == null) {
                    miniMonthRects[miniMonthCount] = new RectF();
                }
                // ⚠ 标签文字本身很窄（≈20dp），手指很难点中。
                //   热区向右扩展到该月块的一半、向下略微溢出，
                //   保证「点月份标题」这个手势可靠触发。
                miniMonthRects[miniMonthCount].set(mx - dp(2), my,
                        mx + Math.max(tw, miniMonthW * 0.5f) + dp(2),
                        my + miniLabelH + dp(4));
                miniMonthIds[miniMonthCount] = m;
                miniMonthCount++;
            }

            int[] counts = m < miniMonths.length ? miniMonths[m] : null;
            if (counts == null) {
                continue;
            }
            // 该月第一天是周几（周一起始）
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(miniYearValue, m, 1);
            int firstOffset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            int days = counts.length;
            for (int day = 1; day <= days; day++) {
                int idx = firstOffset + day - 1;
                int cc = idx % 7;
                int rr = idx / 7;
                if (rr >= MINI_WEEKS) {
                    break;
                }
                float left = mx + cc * (miniCell + miniCellGap);
                float top = my + miniLabelH + rr * (miniCell + miniCellGap);
                rect.set(left, top, left + miniCell, top + miniCell);
                int cnt = counts[day - 1] > 0 ? counts[day - 1] : 0;
                cellPaint.setColor(colorFor(cnt, primary, surfaceDim));
                canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), cellPaint);

                if (selectedYear == miniYearValue && selectedMonth == m && selectedDay == day) {
                    selectPaint.setColor(primary);
                    selectPaint.setStyle(Paint.Style.STROKE);
                    selectPaint.setStrokeWidth(dp(1.2f));
                    canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), selectPaint);
                }
                addHit(left, top, left + miniCell, top + miniCell, m * 100 + day);
            }
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
            if (miniYearMode) {
                // 先判月份标签，再判具体某天
                int mi = hitMiniMonth(event.getX(), event.getY());
                if (mi >= 0 && onMonthClick != null) {
                    onMonthClick.onMonthClick(miniYearValue, miniMonthIds[mi]);
                    performClick();
                    return true;
                }
            }
            int idx = hitTest(event.getX(), event.getY());
            if (idx >= 0 && onDayClick != null) {
                int dayRef = hitDays[idx];
                if (miniYearMode) {
                    int m = dayRef / 100;
                    int day = dayRef % 100;
                    int[] counts = m < miniMonths.length ? miniMonths[m] : null;
                    int cnt = (counts != null && day - 1 < counts.length && counts[day - 1] > 0)
                            ? counts[day - 1] : 0;
                    onDayClick.onDayClick(miniYearValue, m, day, cnt);
                } else if (yearMode) {
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

    /** 命中月份标签：返回 miniMonthIds 的下标，未命中返回 -1 */
    private int hitMiniMonth(float x, float y) {
        for (int i = 0; i < miniMonthCount; i++) {
            RectF r = miniMonthRects[i];
            if (r != null && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
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
