package com.bai.bookshelf.ui.mine;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityIncompleteBooksBinding;
import com.bai.bookshelf.databinding.ItemIncompleteBookBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.util.BookFlags;
import com.bai.bookshelf.util.BookSanitizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 书籍信息补全（v5.7）：列出缺少封面 / 分类 / 存放地点的书，
 * 筛选按「缺少哪些信息」分类，点条目直接进入编辑页补全。
 */
public class IncompleteBooksActivity extends BaseActivity {

    private static final String FILTER_ALL = "全部";
    private static final String FILTER_COVER = "缺封面";
    private static final String FILTER_CATEGORY = "缺分类";
    private static final String FILTER_LOCATION = "缺存放地点";
    private static final String[] FILTERS = {
            FILTER_ALL, FILTER_COVER, FILTER_CATEGORY, FILTER_LOCATION};

    private ActivityIncompleteBooksBinding b;
    private final List<Book> incomplete = new ArrayList<>();
    private final List<Book> shown = new ArrayList<>();
    private Adapter adapter;
    private boolean spinnerReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityIncompleteBooksBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        com.bai.bookshelf.util.InsetsHelper.applyBottomInset(b.recyclerIncomplete);

        b.spIncompleteType.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, FILTERS));
        spinnerReady = true;
        b.spIncompleteType.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent,
                                               View view, int position, long id) {
                        if (spinnerReady) {
                            applyFilter();
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });

        adapter = new Adapter();
        b.recyclerIncomplete.setLayoutManager(new LinearLayoutManager(this));
        b.recyclerIncomplete.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Book> books = BookSanitizer.normalizeAll(app.db().bookDao().getAll());
            List<Book> missing = new ArrayList<>();
            for (Book book : books) {
                if (!BookFlags.countsAsOwned(book)) {
                    continue; // 电子书签不再需要补全
                }
                if (!missingFields(book).isEmpty()) {
                    missing.add(book);
                }
            }
            final List<Book> fMissing = missing;
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                incomplete.clear();
                incomplete.addAll(fMissing);
                applyFilter();
            });
        });
    }

    private static List<String> missingFields(Book book) {
        List<String> list = new ArrayList<>();
        if (book.coverUrl.trim().isEmpty()) {
            list.add(FILTER_COVER);
        }
        if (book.category.trim().isEmpty()) {
            list.add(FILTER_CATEGORY);
        }
        if (book.locationId == null) {
            list.add(FILTER_LOCATION);
        }
        return list;
    }

    private void applyFilter() {
        String selected = (String) b.spIncompleteType.getSelectedItem();
        if (selected == null) {
            selected = FILTER_ALL;
        }
        shown.clear();
        for (Book book : incomplete) {
            if (FILTER_ALL.equals(selected) || missingFields(book).contains(selected)) {
                shown.add(book);
            }
        }
        adapter.notifyDataSetChanged();
        b.tvIncompleteCount.setText("共 " + shown.size() + " 种待补全");
        b.tvIncompleteEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        b.recyclerIncomplete.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        if (shown.isEmpty()) {
            b.tvIncompleteEmpty.setText(incomplete.isEmpty()
                    ? "所有书籍信息都补全了" : "当前缺少项下没有待补全的书");
        }
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemIncompleteBookBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Book book = shown.get(position);
            h.b.tvIncompleteTitle.setText(book.title);
            h.b.tvIncompleteMissing.setText("缺少：" + android.text.TextUtils.join("、",
                    missingFields(book)));
            StringBuilder meta = new StringBuilder();
            if (!book.author.trim().isEmpty()) {
                meta.append(book.author.trim());
            }
            if (!book.category.trim().isEmpty()) {
                if (meta.length() > 0) {
                    meta.append(" · ");
                }
                meta.append(book.category.trim());
            }
            h.b.tvIncompleteMeta.setText(meta);
            h.b.tvIncompleteMeta.setVisibility(meta.length() == 0
                    ? View.GONE : View.VISIBLE);
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(IncompleteBooksActivity.this,
                        BookEditActivity.class);
                intent.putExtra(BookEditActivity.EXTRA_BOOK, book);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ItemIncompleteBookBinding b;

            VH(ItemIncompleteBookBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }
}
