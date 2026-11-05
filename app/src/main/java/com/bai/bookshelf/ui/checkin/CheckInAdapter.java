package com.bai.bookshelf.ui.checkin;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ItemCheckinBookBinding;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.CoverLoader;

import java.util.ArrayList;
import java.util.List;

/** 打卡列表：数据 = readStatus==READING 的书，按 updatedAt 倒序（由 Fragment 排好）。 */
public class CheckInAdapter extends RecyclerView.Adapter<CheckInAdapter.VH> {

    public static final class Row {
        public final Book book;
        public boolean checked;
        public String borrower = "";

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

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemCheckinBookBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Row row = rows.get(position);
        Book book = row.book;
        String title = BookText.displayTitle(book.title);
        CoverLoader.load(h.b.ivCover, book);
        h.b.tvTitle.setText(title);
        h.b.tvSubtitle.setText(CheckInUiState.subtitle(book.readingProgress, row.borrower));

        String label = CheckInUiState.actionLabel(row.checked, dayKey, todayKey);
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
            if (listener != null && "补卡".equals(label)) {
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
