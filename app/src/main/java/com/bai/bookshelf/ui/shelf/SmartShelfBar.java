package com.bai.bookshelf.ui.shelf;

import com.bai.bookshelf.util.Typo;
import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.util.SmartShelf;
import com.bai.bookshelf.util.ThemeManager;

import java.util.List;
import java.util.Map;

/**
 * 书架智能入口条（v5.3，从 ShelfFragment 抽出）：
 * 建一次胶囊，数量/选中态由 {@link #update} 刷新；点击回调交给书架执行筛选。
 */
public final class SmartShelfBar {

    public interface OnSelected {
        void onSelected(int index);
    }

    private final Activity activity;
    private final LinearLayout container;
    private final HorizontalScrollView scroll;
    private final OnSelected listener;
    private final TextView[] chips = new TextView[SmartShelf.NAMES.length];
    /** v5.7：状态栏只保留「借出中 / 超期 / 借阅中」；电子书签走藏书状态筛选 */
    private static final int[] VISIBLE = {
            SmartShelf.LENT, SmartShelf.OVERDUE, SmartShelf.BORROWED_IN};
    private int selected = -1;
    /** 默认收起：有可用入口时由筛选行的「智能」胶囊手动展开 */
    private boolean expanded;
    private boolean anyVisible;
    /** 上一帧各 chip 的选中态：只有真正切换时才播 120ms 反馈 */
    private final boolean[] activeShown = new boolean[com.bai.bookshelf.util.SmartShelf.NAMES.length];
    private boolean activeShownReady;

    public SmartShelfBar(Activity activity, LinearLayout container,
                         HorizontalScrollView scroll, OnSelected listener) {
        this.activity = activity;
        this.container = container;
        this.scroll = scroll;
        this.listener = listener;
    }

    public int selected() {
        return selected;
    }

    public void clearSelection() {
        selected = -1;
    }

    public void setup() {
        container.removeAllViews();
        scroll.setVisibility(View.GONE);
        int textColor = ThemeManager.resolveColor(activity, R.attr.on_surface);
        for (int index : VISIBLE) {
            TextView chip = new TextView(activity);
            Typo.text(chip, R.dimen.text_caption_large);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setTextColor(textColor);
            chip.setBackgroundResource(R.drawable.bg_filter_pill);
            chip.setClickable(true);
            chip.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            lp.setMarginEnd(dp(6));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                selected = selected == index ? -1 : index;
                listener.onSelected(selected);
            });
            container.addView(chip);
            chips[index] = chip;
        }
    }

    /** 刷新数量与选中态；返回是否还有可展示的智能入口 */
    public boolean update(List<Book> books, Map<Long, BorrowRecord> openLoans, int todayKey) {
        int[] counts = SmartShelf.counts(books, openLoans, todayKey);
        int primary = ThemeManager.resolveColor(activity, ThemeManager.ATTR_COLOR_PRIMARY);
        int normal = ThemeManager.resolveColor(activity, R.attr.on_surface);
        boolean any = false;
        for (int i : VISIBLE) {
            TextView chip = chips[i];
            if (chip == null) {
                continue;
            }
            boolean show = counts[i] > 0 || selected == i;
            chip.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) {
                continue;
            }
            any = true;
            boolean active = selected == i;
            chip.setText(SmartShelf.NAMES[i] + " " + counts[i]);
            if (activeShownReady && activeShown[i] != active) {
                com.bai.bookshelf.util.PillMotion.toggle(chip, active);
            } else {
                chip.setBackgroundResource(active
                        ? R.drawable.bg_filter_pill_selected : R.drawable.bg_filter_pill);
            }
            activeShown[i] = active;
            chip.setTextColor(active ? primary : normal);
        }
        activeShownReady = true;
        anyVisible = any;
        applyVisibility();
        return any;
    }

    public boolean expanded() {
        return expanded;
    }

    /** 展开/收起智能入口行 */
    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
        applyVisibility();
    }

    private void applyVisibility() {
        scroll.setVisibility(expanded && anyVisible ? View.VISIBLE : View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
