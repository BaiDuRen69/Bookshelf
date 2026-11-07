package com.bai.bookshelf.ui.checkin;

import android.content.Context;
import android.content.res.ColorStateList;
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
 * <p>按钮三态用 viewType 区分，样式在 onCreateViewHolder 里一次定死——
 * 在 onBindViewHolder 里 setStyle 会让每个回收来的按钮重读一遍主题属性，
 * 滚动时能看到底色闪。三态共用同一高度与 minWidth，切换时行高不跳。
 */
public class CheckInAdapter extends RecyclerView.Adapter<CheckInAdapter.VH> {

    /** 未打 + 今天：filled 主色 */
    static final int TYPE_TODAY = 0;
    /** 未打 + 过去：tonal（补卡是次级动作，不该和今天一样抢眼） */
    static final int TYPE_BACKFILL = 1;
    /** 已打：outlined + 勾选图标 */
    static final int TYPE_DONE = 2;

    /** 已打卡的行封面退后一层 */
    private static final float COVER_DIM = 0.9f;
    /**
     * actionLabel 里那个手打的「✓」是老按钮没图标时的替代物；
     * 现在勾选交给 app:icon，文字再留一个勾就是两个勾叠着像错版。
     * 只在这里剥，CheckInUiState 的文案与单测都不动（读屏走 contentDescription，不受影响）。
     */
    private static final String DONE_GLYPH = " ✓";

    public static final class Row {
        public final Book book;
        public boolean checked;
        public String borrower = "";
        /** 「精确」/「模糊」；空 = 不标口径（老行为） */
        public String modeLabel = "";
        /** 当前在读那一轮的 seq：打卡写入时带上，撤销时才认得出是哪一轮 */
        public int seq = 1;

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

    /** 只重绑被点的那一行，不整表重建 */
    public void updateRow(int position, boolean checked) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        rows.get(position).checked = checked;
        notifyItemChanged(position);
    }

    public List<Row> rows() {
        return rows;
    }

    public int getDayKey() {
        return dayKey;
    }

    @Override
    public int getItemViewType(int position) {
        Row row = rows.get(position);
        if (row.checked) {
            return TYPE_DONE;
        }
        return dayKey == todayKey ? TYPE_TODAY : TYPE_BACKFILL;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemCheckinBookBinding b = ItemCheckinBookBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        applyButtonStyle(b.btnCheckin, viewType);
        return new VH(b);
    }

    /**
     * 三态外观：filled（今天可打）/ tonal（补卡）/ outlined + 勾选（已完成）。
     *
     * <p>Material 1.12 的 MaterialButton 没有 setStyle（javap 确认），样式只能在
     * 布局里定一次；所以基样式仍是 Widget.Bookshelf.Button（形状、字号、字距都来自它），
     * 这里按状态只覆盖底色 / 文字色 / 描边 / 图标。放在 onCreateViewHolder 是因为
     * viewType 一变 RecyclerView 就会新建 holder，绑定里再改会看到底色闪。
     */
    private static void applyButtonStyle(MaterialButton btn, int viewType) {
        Context ctx = btn.getContext();
        if (viewType == TYPE_DONE) {
            btn.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, android.R.color.transparent)));
            btn.setStrokeWidth(px(ctx, R.dimen.checkin_button_stroke));
            btn.setStrokeColor(tint(ctx, R.attr.outline_strong));
            btn.setTextColor(tint(ctx, ThemeManager.ATTR_COLOR_PRIMARY));
            // 基样式是 filled，它的 ripple 与 icon 都按「实心深底」取色；底色被抽掉后
            // 不改这两处，按下会闪一层白、勾也是白的看不见。
            btn.setRippleColor(tint(ctx, ThemeManager.ATTR_COLOR_PRIMARY));
            btn.setIconTint(tint(ctx, ThemeManager.ATTR_COLOR_PRIMARY));
            btn.setIconResource(R.drawable.ic_check_line);
            btn.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            btn.setIconSize(px(ctx, R.dimen.checkin_button_icon));
            return;
        }
        btn.setStrokeWidth(0);
        btn.setIcon(null);
        int bg = viewType == TYPE_TODAY
                ? ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY)
                : ThemeManager.resolveColor(ctx, R.attr.primary_container);
        int fg = viewType == TYPE_TODAY
                ? ThemeManager.resolveColor(ctx, com.google.android.material.R.attr.colorOnPrimary)
                : ThemeManager.resolveColor(ctx, R.attr.on_primary_container);
        int onSurface = ThemeManager.resolveColor(ctx, R.attr.on_surface);
        // 未来日仍是「不可点」，底色/文字按 M3 的禁用强度退成 on_surface 12% / 38%，
        // 否则单色 ColorStateList 会让禁用按钮看着和可点一模一样。
        btn.setBackgroundTintList(new ColorStateList(STATES,
                new int[]{alpha(onSurface, 0x1F), bg}));
        btn.setTextColor(new ColorStateList(STATES,
                new int[]{alpha(onSurface, 0x61), fg}));
    }

    private static final int[][] STATES = {new int[]{-android.R.attr.state_enabled}, new int[]{}};

    private static int alpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static ColorStateList tint(Context ctx, int attrRes) {
        return ColorStateList.valueOf(ThemeManager.resolveColor(ctx, attrRes));
    }

    private static int px(Context ctx, int dimenRes) {
        return ctx.getResources().getDimensionPixelSize(dimenRes);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Row row = rows.get(position);
        Book book = row.book;
        String title = BookText.displayTitle(book.title);
        CoverLoader.load(h.b.ivCover, book);
        h.b.tvTitle.setText(title);
        String subtitle = CheckInUiState.subtitle(
                book.readingProgress, row.borrower, row.modeLabel);
        if (row.checked) {
            subtitle = CheckInUiState.subtitleChecked(subtitle, dayKey, todayKey);
        }
        h.b.tvSubtitle.setText(subtitle);
        h.b.coverBox.setAlpha(row.checked ? COVER_DIM : 1f);

        String label = CheckInUiState.actionLabel(row.checked, dayKey, todayKey);
        if (h.getItemViewType() == TYPE_DONE) {
            label = label.replace(DONE_GLYPH, "");
        }
        final boolean backfillable = "补卡".equals(label);
        h.b.btnCheckin.setText(label);
        // 未来日期不可打卡；已打卡的记录仍允许撤销
        h.b.btnCheckin.setEnabled(CheckInUiState.actionable(dayKey, todayKey) || row.checked);
        h.b.btnCheckin.setContentDescription(CheckInUiState.actionContentDescription(
                title, row.checked, dayKey, todayKey));
        h.b.btnCheckin.setOnClickListener(v -> {
            if (listener != null) {
                listener.onToggle(h.getBindingAdapterPosition(), row);
            }
        });
        h.b.btnCheckin.setOnLongClickListener(v -> {
            if (listener != null && backfillable) {
                listener.onBackfillLongPress(dayKey);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemCheckinBookBinding b;

        VH(ItemCheckinBookBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }
    }
}
