package com.bai.bookshelf.ui.stats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 迷你条形图（「我的阅读」页用）：一行等宽槽位，每槽一根柱子 + 底部标签。
 *
 * <p>v4.20 重写，解决三个问题：
 * <ol>
 *   <li><b>刻度不统一</b>——此前每张图各自按自己的最大值归一化，
 *       导致「上半月的 4 本」和「下半月的 2 本」画得一样高、切换月份后同一数值高度也会变。
 *       现在支持传入<b>共享刻度</b>（{@link #setData(List, List, int)}），
 *       同一批图表用同一个最大值，跨半行/跨月份/跨年份都能直接目测比较。</li>
 *   <li><b>缺少参考线</b>——参照微信读书的「阅读时长分布」，加了 4 条横向虚线
 *       （0 / 1/3 / 2/3 / 满量程）并在右侧标注刻度值。</li>
 *   <li><b>x 轴标签过密</b>——柱数多时自动抽稀（12 柱全显，30 柱只显 1/5/10/15/20/25/30）。</li>
 * </ol>
 *
 * <p>v4.21：增加<b>点击柱子</b>能力（{@link #setOnBarClickListener}），
 * 点某根柱子即回调其下标，用于和「全部记录」底部面板联动高亮。
 *
 * <p>数据量小（个人藏书，最多 31 根柱），直接 onDraw 手绘，不引第三方图表库。
 */
public class MiniBarChartView extends View {

    /** 刻度线条数（含 0 基线），与微信读书一致为 4 条 */
    private static final int GRID_LINES = 4;

    private List<String> labels = new ArrayList<>();
    private List<Integer> values = new ArrayList<>();
    /** 共享刻度：>=0 时使用该最大值（而非各自的最大值）；-1 表示自动 */
    private int sharedMax = -1;
    /** 刻度值单位，如「本」；为空则只显示数字 */
    private String unit = "";
    /** 柱子点击回调（下标 = labels/values 的下标）；null 表示不可点 */
    private OnBarClickListener onBarClickListener;
    /** 当前高亮的柱子下标；-1 表示不高亮 */
    private int highlightedIndex = -1;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scalePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    /** 柱子的渐变着色器缓存（避免每次 onDraw 新建 LinearGradient） */
    private LinearGradient barGradient = null;
    private int barGradientKey = 0;
    /** 虚线效果缓存（避免每次 onDraw 新建 DashPathEffect） */
    private DashPathEffect dashEffect = null;
    /** 高亮柱的着色器（半透明主色底衬） */
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 高亮柱的底衬矩形（复用，避免 onDraw 里 new） */
    private final RectF highlightRect = new RectF();
    /** 每根柱子的横向范围（绘制时记录，用于点击命中）；下标与 values 一致 */
    private float[] hitLeft = new float[16];
    private float[] hitRight = new float[16];
    private int hitCount = 0;

    /** 柱子点击回调 */
    public interface OnBarClickListener {
        /**
         * @param index 柱子下标（与 setData 的 values 下标一致）
         * @param value 该柱的数值
         */
        void onBarClick(int index, int value);
    }

    public MiniBarChartView(Context context) {
        this(context, null);
    }

    public MiniBarChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        textPaint.setTextSize(sp(9));
        textPaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(sp(10));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Math.max(1f, dp(0.5f)));
        scalePaint.setTextSize(sp(10));
        scalePaint.setTextAlign(Paint.Align.RIGHT);
        setClickable(true);
    }

    /** 设置数据（各图各自按自身最大值归一化） */
    public void setData(List<String> labels, List<Integer> values) {
        setData(labels, values, -1);
    }

    /**
     * 设置数据。
     *
     * @param sharedMax 共享刻度上限（>=0 生效）。多张图传同一个值时，
     *                  柱子高度才具备跨图可比性；传 -1 表示各自自适应。
     */
    public void setData(List<String> labels, List<Integer> values, int sharedMax) {
        this.labels = labels != null ? labels : new ArrayList<>();
        this.values = values != null ? values : new ArrayList<>();
        this.sharedMax = sharedMax;
        requestLayout();
        invalidate();
    }

    /** 刻度值单位（如「本」），为空则只显示数字 */
    public void setUnit(String unit) {
        this.unit = unit != null ? unit : "";
        invalidate();
    }

    /** 设置柱子点击回调（null 表示不可点） */
    public void setOnBarClickListener(OnBarClickListener listener) {
        this.onBarClickListener = listener;
    }

    /** 高亮某根柱子（-1 取消高亮） */
    public void setHighlightedIndex(int index) {
        if (this.highlightedIndex != index) {
            this.highlightedIndex = index;
            invalidate();
        }
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
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
        int n = values.size();
        if (n == 0 || labels.size() != n) {
            drawEmpty(canvas, w, h);
            return;
        }

        int rawMax = sharedMax >= 0 ? sharedMax : maxOf(values);
        if (rawMax <= 0) {
            if (sharedMax >= 0) {
                // 共享刻度场景：即使本图全为 0 也要画出网格，保持与其它图对齐
                rawMax = 1;
            } else {
                drawEmpty(canvas, w, h);
                return;
            }
        }

        // ---- 刻度：把最大值向上取整成「好看的」步长，保证虚线落在整数上 ----
        // 分成 3 段（0 / step / 2*step / 3*step 共 4 条线），最上面那条正好落在图表顶端，
        // 这样柱高可以按刻度直接目测；step 随数据量级变化（0/2/4/6 本 → 0/4/8/12 本 → …）。
        int step = niceStep((int) Math.ceil(rawMax / 3.0));
        int scaleMax = step * 3;

        // ---- 版面 ----
        float gutterRight = dp(34);   // 右侧刻度值留白
        float padTop = sp(14);        // 柱顶数值
        float padBottom = sp(14);     // 底部标签
        float plotW = w - gutterRight;
        float chartH = h - padTop - padBottom;
        if (plotW <= 0 || chartH <= 0) {
            return;
        }
        float baseline = h - padBottom;

        int primary = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        int onSurfaceVariant = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        int outline = ThemeManager.resolveColor(getContext(), R.attr.outline);

        // ---- 横向虚线 + 右侧刻度值 ----
        gridPaint.setColor(outline);
        if (dashEffect == null) {
            dashEffect = new DashPathEffect(new float[]{dp(3), dp(3)}, 0);
        }
        gridPaint.setPathEffect(dashEffect);
        scalePaint.setColor(onSurfaceVariant);
        textPaint.setColor(onSurfaceVariant);
        valuePaint.setColor(primary);

        for (int i = 0; i < GRID_LINES; i++) {
            int v = step * i;
            float y = baseline - chartH * v / scaleMax;
            canvas.drawLine(0, y, plotW, y, gridPaint);
            canvas.drawText(formatScale(v), w - dp(2), y + sp(3.5f), scalePaint);
        }

        // ---- 柱子 ----
        float slot = plotW / n;
        float barW = Math.min(slot * 0.6f, dp(20));
        boolean showValue = slot >= dp(13) && n <= 22;

        // 渐变着色器按「主色 + 上下边界」缓存，避免每次 onDraw 都 new 一个 LinearGradient
        int gradientKey = primary * 31 + Math.round(padTop) * 7 + Math.round(baseline);
        if (barGradient == null || gradientKey != barGradientKey) {
            barGradient = new LinearGradient(0, padTop, 0, baseline,
                    ThemeManager.primaryAlpha(getContext(), 190), primary, Shader.TileMode.CLAMP);
            barGradientKey = gradientKey;
        }
        barPaint.setShader(barGradient);
        highlightPaint.setColor(ThemeManager.primaryAlpha(getContext(), 40));

        for (int i = 0; i < n; i++) {
            int v = values.get(i) == null ? 0 : values.get(i);
            float cx = slot * i + slot / 2f;

            // 记录点击命中的槽位（整槽范围，便于手指点击）
            // 用 float 数组而非 List<RectF>，避免 onDraw 里分配对象
            ensureHitCapacity(n);
            hitLeft[hitCount] = slot * i;
            hitRight[hitCount] = slot * (i + 1);
            hitCount++;

            // 高亮：被选中的那根柱子加一层浅色底衬
            if (i == highlightedIndex) {
                highlightRect.set(cx - barW / 2f - dp(2), padTop - sp(4),
                        cx + barW / 2f + dp(2), baseline);
                canvas.drawRoundRect(highlightRect, dp(6), dp(6), highlightPaint);
            }

            if (v > 0) {
                float barH = chartH * v / scaleMax;
                float top = baseline - barH;
                float radius = Math.min(barW / 2f, dp(6));
                barRect.set(cx - barW / 2f, top, cx + barW / 2f, baseline);
                canvas.drawRoundRect(barRect, radius, radius, barPaint);
                if (showValue) {
                    canvas.drawText(String.valueOf(v), cx, top - sp(3), valuePaint);
                }
            }
            if (labels.get(i) != null) {
                // x 轴标签按需抽稀，避免密集重叠
                if (shouldShowLabel(i, n)) {
                    canvas.drawText(labels.get(i), cx, h - sp(3), textPaint);
                }
            }
        }
        barPaint.setShader(null);
    }

    /** 无数据提示 */
    private void drawEmpty(Canvas canvas, int w, int h) {
        textPaint.setTextSize(sp(12));
        textPaint.setColor(ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant));
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("暂无数据", w / 2f, h / 2f - sp(4), textPaint);
        textPaint.setTextSize(sp(9));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP && onBarClickListener != null) {
            int idx = hitTest(event.getX());
            if (idx >= 0) {
                int v = values.get(idx) == null ? 0 : values.get(idx);
                onBarClickListener.onBarClick(idx, v);
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

    /** 命中测试：返回命中槽位的下标，未命中返回 -1 */
    private int hitTest(float x) {
        for (int i = 0; i < hitCount; i++) {
            if (x >= hitLeft[i] && x <= hitRight[i]) {
                return i;
            }
        }
        return -1;
    }

    /** 确保命中槽位数组容量足够（按需扩容，避免每次绘制分配） */
    private void ensureHitCapacity(int n) {
        if (hitLeft.length >= n) {
            return;
        }
        int cap = Math.max(n, hitLeft.length * 2);
        float[] left = new float[cap];
        float[] right = new float[cap];
        System.arraycopy(hitLeft, 0, left, 0, hitCount);
        System.arraycopy(hitRight, 0, right, 0, hitCount);
        hitLeft = left;
        hitRight = right;
    }

    /**
     * x 轴标签抽稀规则：每 step 个显示一个，并保证最后一个一定显示。
     * 12 柱时 step=1（全显），16 柱时 step=2（1/3/5/…/15），30 柱时 step=5。
     */
    private boolean shouldShowLabel(int index, int n) {
        int step = labelStep(n);
        if (index == n - 1) {
            return true;
        }
        return index % step == 0;
    }

    /** 标签步长：目标最多 12 个标签，步长取好看的值 */
    private int labelStep(int n) {
        if (n <= 12) {
            return 1;
        }
        int raw = (int) Math.ceil(n / 12.0);
        if (raw <= 2) {
            return 2;
        }
        if (raw <= 5) {
            return 5;
        }
        if (raw <= 10) {
            return 10;
        }
        return 20;
    }

    /** 刻度值文案：如「4本」 */
    private String formatScale(int v) {
        return unit.isEmpty() ? String.valueOf(v) : (v + unit);
    }

    private int maxOf(List<Integer> list) {
        int max = 0;
        for (Integer v : list) {
            if (v != null && v > max) {
                max = v;
            }
        }
        return max;
    }

    /**
     * 把任意步长向上取整成「好看的」刻度步长（1/2/3/4/5/10/20/25/50/100…），
     * 这样虚线落在整数刻度上，读数不别扭。
     */
    private static int niceStep(int raw) {
        if (raw <= 1) {
            return 1;
        }
        int[] candidates = {1, 2, 3, 4, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000};
        for (int c : candidates) {
            if (c >= raw) {
                return c;
            }
        }
        int v = candidates[candidates.length - 1];
        while (v < raw) {
            v *= 2;
        }
        return v;
    }
}
