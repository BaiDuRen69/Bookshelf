package com.bai.bookshelf.ui.stats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.bai.bookshelf.util.LibraryStats;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 分类树状图（v5.8）：面积 = 种数，一眼看出「书房由什么构成」。
 *
 * <p>用 squarified 布局而不是逐条切分：逐条切分会让后面的小块变成细长条，
 * 既读不出面积对比，也放不下标签。这里每次沿短边铺一行，新加一块会让最差
 * 长宽比变差时就收行换边，十几到几十块时观感最稳。
 *
 * <p>点哪一块、要不要下钻二级由宿主决定，本控件只回调下标。
 */
public class CategoryTreemapView extends View {

    /** 点中某一块（下标与 {@link #setData} 的列表一致） */
    public interface OnCellClickListener {
        void onCellClick(int index);
    }

    /** 小于这个尺寸就不塞标签，留纯色块 */
    private static final int MIN_LABEL_W_DP = 56;
    private static final int MIN_LABEL_H_DP = 34;
    /** 块与块之间的留白（px 由密度换算） */
    private static final float GAP_DP = 1.5f;
    /** 标签缩到原字号的一半还放不下就留白，不写认不出的一两个字 */
    private static final float MIN_LABEL_SCALE = 0.5f;

    private final List<LibraryStats.Group> data = new ArrayList<>();
    private final List<Integer> fillColors = new ArrayList<>();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float gap;
    private final int minLabelW;
    private final int minLabelH;
    private float baseTitleSize;
    private float baseSubSize;
    private int textColor;

    private RectF[] cached;
    private int cachedW = -1;
    private int cachedH = -1;
    private int highlighted = -1;
    private OnCellClickListener listener;

    public CategoryTreemapView(Context context) {
        this(context, null);
    }

    public CategoryTreemapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        // Paint 只认 px。字号跟系统字体走，但封顶 1.3 倍：2.0 倍字体时「文学」两个字
        // 就比格子还宽，整张图会退化成「文…」「历…」，不如让下面的进度列表去承载数字
        float sp = Math.min(context.getResources().getDisplayMetrics().scaledDensity,
                1.3f * density);
        gap = GAP_DP * density;
        minLabelW = (int) (MIN_LABEL_W_DP * density);
        minLabelH = (int) (MIN_LABEL_H_DP * density);
        titlePaint.setTextSize(13 * sp);
        subPaint.setTextSize(11 * sp);
        titlePaint.setFakeBoldText(true);
        baseTitleSize = titlePaint.getTextSize();
        baseSubSize = subPaint.getTextSize();
    }

    public void setOnCellClickListener(OnCellClickListener l) {
        this.listener = l;
    }

    /** 高亮某一块（下钻后回显当前看的是哪一类；-1 表示不高亮） */
    public void setHighlighted(int index) {
        highlighted = index;
        invalidate();
    }

    /** 换数据：重算布局与配色，并刷新无障碍摘要 */
    public void setData(List<LibraryStats.Group> groups) {
        data.clear();
        if (groups != null) {
            data.addAll(groups);
        }
        buildColors();
        cached = null;
        cachedW = -1;
        requestLayout();
        invalidate();
        refreshAccessibility();
    }

    /** 供宿主在说明文字里带上口径 */
    public String metricHint() {
        return String.format(Locale.getDefault(), "面积 = 种数 · 共 %d 类", data.size());
    }

    private void buildColors() {
        fillColors.clear();
        int primary = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        textColor = ThemeManager.resolveColor(getContext(),
                com.google.android.material.R.attr.colorOnPrimary);
        int n = Math.max(1, data.size());
        for (int i = 0; i < data.size(); i++) {
            // 同一色相按块序渐淡：比随机配色更容易读出「这些是一组」
            int alpha = 235 - i * (150 / n);
            fillColors.add(Color.argb(alpha, Color.red(primary), Color.green(primary),
                    Color.blue(primary)));
        }
    }

    private void refreshAccessibility() {
        if (data.isEmpty()) {
            setContentDescription("分类树状图，暂无数据");
            return;
        }
        StringBuilder sb = new StringBuilder("分类树状图，面积表示种数，共 ")
                .append(data.size()).append(" 类：");
        int n = Math.min(data.size(), 8);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append("，");
            }
            sb.append(data.get(i).name).append(' ').append(data.get(i).titles).append(" 种");
        }
        if (data.size() > n) {
            sb.append("，等 ").append(data.size()).append(" 类");
        }
        setContentDescription(sb);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (data.isEmpty() || w <= 0 || h <= 0) {
            return;
        }
        RectF[] laid = layoutIfNeeded(w, h);
        for (int i = 0; i < data.size() && i < laid.length; i++) {
            RectF r = laid[i];
            if (r == null || r.right <= r.left || r.bottom <= r.top) {
                continue;
            }
            fillPaint.setColor(fillColors.get(i));
            fillPaint.setAlpha(highlighted >= 0 && highlighted != i ? 80 : 255);
            canvas.drawRoundRect(r, 6, 6, fillPaint);
            if (r.width() < minLabelW || r.height() < minLabelH) {
                continue;
            }
            float pad = 8;
            LibraryStats.Group g = data.get(i);
            String sub = g.titles + " 种 · " + g.copies + " 册";
            titlePaint.setColor(textColor);
            titlePaint.setAlpha(255);
            subPaint.setColor(textColor);
            subPaint.setAlpha(210);
            float scale = 1f;
            while (scale > MIN_LABEL_SCALE && !labelFits(g.name, sub, r, pad, scale)) {
                scale *= 0.85f;
            }
            if (labelFits(g.name, sub, r, pad, scale)) {
                titlePaint.setTextSize(baseTitleSize * scale);
                subPaint.setTextSize(baseSubSize * scale);
                float baseY = r.top + pad + titlePaint.getTextSize();
                canvas.drawText(ellipsize(g.name, r.width() - pad * 2, titlePaint),
                        r.left + pad, baseY, titlePaint);
                canvas.drawText(ellipsize(sub, r.width() - pad * 2, subPaint),
                        r.left + pad, baseY + subPaint.getTextSize() * 1.35f, subPaint);
                titlePaint.setTextSize(baseTitleSize);
                subPaint.setTextSize(baseSubSize);
            }
        }
    }

    /** 两行标签放不放得进这一块：宽度、高度都要够 */
    private boolean labelFits(String name, String sub, RectF r, float pad, float scale) {
        float avail = r.width() - pad * 2;
        titlePaint.setTextSize(baseTitleSize * scale);
        subPaint.setTextSize(baseSubSize * scale);
        boolean ok = titlePaint.measureText(name) <= avail
                && subPaint.measureText(sub) <= avail
                && titlePaint.getTextSize() + subPaint.getTextSize() * 1.8f <= r.height() - pad * 2;
        titlePaint.setTextSize(baseTitleSize);
        subPaint.setTextSize(baseSubSize);
        return ok;
    }

    private String ellipsize(String text, float width, Paint paint) {
        if (paint.measureText(text) <= width) {
            return text;
        }
        float dots = paint.measureText("…");
        int end = text.length();
        while (end > 0 && paint.measureText(text, 0, end) + dots > width) {
            end--;
        }
        return end > 0 ? text.substring(0, end) + "…" : "";
    }

    // ==================== squarified 布局 ====================

    /** 一个待布局的块：值 + 在列表里的下标 */
    private static class Item {
        final float value;
        final int index;

        Item(float value, int index) {
            this.value = value;
            this.index = index;
        }
    }

    private RectF[] layoutIfNeeded(int w, int h) {
        if (cached != null && cachedW == w && cachedH == h) {
            return cached;
        }
        cached = squarify(w, h);
        cachedW = w;
        cachedH = h;
        return cached;
    }

    private RectF[] squarify(int w, int h) {
        RectF[] out = new RectF[data.size()];
        List<Item> items = new ArrayList<>();
        float total = 0;
        for (int i = 0; i < data.size(); i++) {
            float v = Math.max(0.001f, data.get(i).titles);
            items.add(new Item(v, i));
            total += v;
        }
        if (total <= 0) {
            return out;
        }
        items.sort((a, b) -> Float.compare(b.value, a.value));
        // 每单位值对应的面积固定，剩余矩形随之收缩，行面积 = 行值和 × scale
        float scale = (float) w * h / total;
        float x = getPaddingLeft();
        float y = getPaddingTop();
        float rw = w;
        float rh = h;
        int i = 0;
        while (i < items.size()) {
            float side = Math.min(rw, rh);
            List<Item> row = new ArrayList<>();
            float rowSum = 0;
            float worst = Float.MAX_VALUE;
            int j = i;
            while (j < items.size()) {
                Item next = items.get(j);
                float testSum = rowSum + next.value;
                float candidate = worstRatio(row, next, testSum, side, scale);
                if (!row.isEmpty() && candidate > worst) {
                    break;
                }
                row.add(next);
                rowSum = testSum;
                worst = candidate;
                j++;
            }
            if (row.isEmpty() || rowSum <= 0 || side <= 0) {
                break;
            }
            float strip = rowSum * scale / side;
            boolean alongWidth = rw >= rh;
            float cursor = 0;
            for (Item it : row) {
                float len = (it.value / rowSum) * side;
                RectF r = new RectF();
                if (alongWidth) {
                    r.set(x + cursor, y, x + cursor + len - gap, y + strip - gap);
                } else {
                    r.set(x, y + cursor, x + strip - gap, y + cursor + len - gap);
                }
                out[it.index] = r;
                cursor += len;
            }
            if (alongWidth) {
                y += strip;
                rh -= strip;
            } else {
                x += strip;
                rw -= strip;
            }
            i = j;
        }
        return out;
    }

    /** 一行里最差的长宽比（越小越方） */
    private static float worstRatio(List<Item> row, Item next, float rowSum, float side, float scale) {
        if (rowSum <= 0 || side <= 0) {
            return Float.MAX_VALUE;
        }
        float thickness = rowSum * scale / side;
        if (thickness <= 0) {
            return Float.MAX_VALUE;
        }
        float worst = 0;
        for (Item it : row) {
            worst = Math.max(worst, ratioOf(it, rowSum, side, thickness));
        }
        return Math.max(worst, ratioOf(next, rowSum, side, thickness));
    }

    private static float ratioOf(Item it, float rowSum, float side, float thickness) {
        float len = (it.value / rowSum) * side;
        if (len <= 0) {
            return Float.MAX_VALUE;
        }
        return Math.max(thickness / len, len / thickness);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || listener == null || cached == null) {
            return super.onTouchEvent(event);
        }
        for (int i = 0; i < cached.length; i++) {
            if (cached[i] != null && cached[i].contains(event.getX(), event.getY())) {
                performClick();
                listener.onCellClick(i);
                return true;
            }
        }
        return super.onTouchEvent(event);
    }
}
