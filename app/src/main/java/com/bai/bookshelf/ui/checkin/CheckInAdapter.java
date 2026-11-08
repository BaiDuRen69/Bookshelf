package com.bai.bookshelf.ui.checkin;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ItemCheckinBookBinding;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.CoverLoader;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * 打卡列表：数据 = readStatus==READING 的书，按 updatedAt 倒序（由 Fragment 排好）。
 *
 * <p>按钮只用文字：无图标槽，三个状态共用同一个 viewType、固定宽度与 1dp 描边，
 * 只有底色/文字色不同——打卡前后按钮宽高、内边距、字号完全一致，不再跳。
 * 主题色在第一个 holder 创建时解析一次并缓存，onBind 只用缓存，不会滚动闪底色。
 */
public class CheckInAdapter extends RecyclerView.Adapter<CheckInAdapter.VH> {

    /** 未打 + 今天：filled 主色 */
    static final int TYPE_TODAY = 0;
    /** 未打 + 过去：tonal（补卡是次级动作，不该和今天一样抢眼） */
    static final int TYPE_BACKFILL = 1;
    /** 已打：outlined */
    static final int TYPE_DONE = 2;

    /** 已打卡的行封面退后一层 */
    private static final float COVER_DIM = 0.9f;

    public static final class Row {
        public Book book;
        public boolean checked;
        public String borrower = "";
        /** 当前轮的阅读天数（ReadingRecords.readDays 唯一口径） */
        public int readDays;
        /** 当前在读那一轮的 seq：打卡写入时带上，撤销时才认得出是哪一轮 */
        public int seq = 1;
        /** 该行有一次写入在途：按钮显示「处理中」并禁用，防连点重复提交 */
        public boolean pending;

        public Row(Book book, boolean checked) {
            this.book = book;
            this.checked = checked;
        }
    }

    public interface Listener {
        void onToggle(int position, Row row);

        void onBackfillLongPress(int dayKey);
    }

    private final List<Row> rows = new ArrayList<>();
    private int dayKey;
    private int todayKey;
    private Listener listener;
    private ButtonStyles styles;

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void submit(List<Row> data, int dayKey, int todayKey) {
        rows.clear();
        if (data != null) {
            rows.addAll(data);
        }
        this.dayKey = dayKey;
        this.todayKey = todayKey;
        notifyDataSetChanged();
    }

    public int indexOfBook(long bookId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).book.id == bookId) {
                return i;
            }
        }
        return -1;
    }

    /** 异步请求在途标记；按 bookId 找行，position 在提交期间可能已经变了 */
    public void setPending(long bookId, boolean pending) {
        int i = indexOfBook(bookId);
        if (i < 0 || rows.get(i).pending == pending) {
            return;
        }
        rows.get(i).pending = pending;
        notifyItemChanged(i);
    }

    /**
     * 打卡结果回填：按 bookId 找行，不依赖点击时的 position。
     *
     * @return 实际改到的行号；-1 = 该行已被别的刷新换掉，调用方应整页重取
     */
    public int applyToggleResult(long bookId, boolean checked, int readDays) {
        return applyToggleResult(bookId, checked, readDays, null);
    }

    /**
     * 写入后行内同步：{@code updated} 是该书写库后的最新对象，换掉旧引用，
     * 下一次打卡/撤销才不会拿提升前的会话状态做判断。
     */
    public int applyToggleResult(long bookId, boolean checked, int readDays, Book updated) {
        int i = indexOfBook(bookId);
        if (i < 0) {
            return -1;
        }
        Row row = rows.get(i);
        row.pending = false;
        row.checked = checked;
        row.readDays = readDays;
        if (updated != null) {
            row.book = updated;
        }
        notifyItemChanged(i);
        return i;
    }

    /** 撤销后该行已不满足显示条件（选中日早于开读日）时整行移除，不留下空壳 */
    public boolean removeRowByBook(long bookId) {
        int i = indexOfBook(bookId);
        if (i < 0) {
            return false;
        }
        rows.remove(i);
        notifyItemRemoved(i);
        return true;
    }

    public List<Row> rows() {
        return rows;
    }

    public int getDayKey() {
        return dayKey;
    }

    @Override
    public int getItemViewType(int position) {
        // 三态只换色不换 holder：换 viewType 会重建 View，切换瞬间能看出尺寸/底色跳一下
        return 0;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (styles == null) {
            styles = new ButtonStyles(parent.getContext());
        }
        ItemCheckinBookBinding b = ItemCheckinBookBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        // 只用文字：没有图标槽，三态几何完全一致
        b.btnCheckin.setIcon(null);
        b.btnCheckin.setGravity(Gravity.CENTER);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Row row = rows.get(position);
        Book book = row.book;
        String title = BookText.displayTitle(book.title);
        CoverLoader.load(h.b.ivCover, book);
        h.b.tvTitle.setText(title);
        String subtitle = CheckInUiState.subtitleDays(
                book.readingProgress, row.readDays, row.borrower);
        if (row.checked) {
            subtitle = CheckInUiState.subtitleChecked(subtitle, dayKey, todayKey);
        }
        h.b.tvSubtitle.setText(subtitle);
        h.b.coverBox.setAlpha(row.checked ? COVER_DIM : 1f);

        int state = row.checked ? TYPE_DONE
                : (dayKey == todayKey ? TYPE_TODAY : TYPE_BACKFILL);
        if (h.styleState != state) {
            applyButtonStyle(h.b.btnCheckin, state, styles);
            h.styleState = state;
        }
        String label = row.pending
                ? "处理中" : CheckInUiState.actionLabel(row.checked, dayKey, todayKey);
        final boolean backfillable = "补卡".equals(label);
        h.b.btnCheckin.setText(label);
        // 未来日期不可打卡；已打卡的记录仍允许撤销；在途期间禁用防连点
        h.b.btnCheckin.setEnabled(!row.pending
                && (CheckInUiState.actionable(dayKey, todayKey) || row.checked));
        h.b.btnCheckin.setContentDescription(row.pending
                ? "正在处理《" + title + "》的打卡"
                : CheckInUiState.actionContentDescription(title, row.checked, dayKey, todayKey));
        h.b.btnCheckin.setOnClickListener(v -> {
            if (listener != null) {
                listener.onToggle(h.getBindingAdapterPosition(), row);
            }
        });
        // 只有「补卡」挂长按；复用 holder 时显式清掉，避免上一行的手势残留
        // setOnLongClickListener(null) 仍会把 longClickable 置 true，要一并复位
        h.b.btnCheckin.setOnLongClickListener(backfillable ? v -> {
            if (listener != null) {
                listener.onBackfillLongPress(dayKey);
                return true;
            }
            return false;
        } : null);
        h.b.btnCheckin.setLongClickable(backfillable);
    }

    /**
     * 只覆盖底色 / 文字色 / 描边色；几何参数（宽、高、inset、padding、字号、
     * strokeWidth）与文字居中三态完全一致，交给布局与 holder 创建时一次定死。
     */
    private static void applyButtonStyle(MaterialButton btn, int state, ButtonStyles s) {
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, s.textSize);
        btn.setStrokeWidth(s.strokeWidth);
        btn.setStrokeColor(ColorStateList.valueOf(
                state == TYPE_DONE ? s.outlineStrong : s.transparent));
        btn.setBackgroundTintList(new ColorStateList(STATES,
                new int[]{s.disabledBg, state == TYPE_DONE ? s.transparent
                        : (state == TYPE_TODAY ? s.todayBg : s.container)}));
        btn.setTextColor(new ColorStateList(STATES,
                new int[]{s.disabledFg, state == TYPE_DONE ? s.primary
                        : (state == TYPE_TODAY ? s.onToday : s.onContainer)}));
        btn.setRippleColor(ColorStateList.valueOf(
                state == TYPE_DONE ? s.primary
                        : (state == TYPE_TODAY ? s.onToday : s.onPrimary)));
    }

    private static final int[][] STATES = {new int[]{-android.R.attr.state_enabled}, new int[]{}};

    private static int alpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** 一次解析主题色，避免 onBind 里反复 resolveColor 造成滚动底色闪 */
    private static final class ButtonStyles {
        final int primary;
        final int onPrimary;
        final int todayBg;
        final int onToday;
        final int container;
        final int onContainer;
        final int outlineStrong;
        final int transparent;
        final int disabledBg;
        final int disabledFg;
        final int strokeWidth;
        final float textSize;

        ButtonStyles(Context ctx) {
            primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
            onPrimary = ThemeManager.resolveColor(ctx,
                    com.google.android.material.R.attr.colorOnPrimary);
            todayBg = ThemeManager.resolveColor(ctx, R.attr.checkin_today_bg);
            onToday = ThemeManager.resolveColor(ctx, R.attr.checkin_on_today);
            container = ThemeManager.resolveColor(ctx, R.attr.primary_container);
            onContainer = ThemeManager.resolveColor(ctx, R.attr.on_primary_container);
            outlineStrong = ThemeManager.resolveColor(ctx, R.attr.outline_strong);
            transparent = ContextCompat.getColor(ctx, android.R.color.transparent);
            int onSurface = ThemeManager.resolveColor(ctx, R.attr.on_surface);
            disabledBg = alpha(onSurface, 0x1F);
            disabledFg = alpha(onSurface, 0x61);
            strokeWidth = ctx.getResources().getDimensionPixelSize(R.dimen.checkin_button_stroke);
            textSize = ctx.getResources().getDimension(R.dimen.text_body_small);
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemCheckinBookBinding b;
        int styleState = -1;

        VH(ItemCheckinBookBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }
    }
}
