package com.bai.bookshelf.ui.series;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivitySeriesBinding;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.ItemSeriesBookBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.detail.BookDetailActivity;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.ui.mine.SplitActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.BookSeries;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.CoverLoader;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.StatusText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 系列详情（v4.53）：一个系列内的全部藏书，按阅读顺序 / 卷号排好。
 * 支持添加下一卷、套装分摊（写回各书购入价）、重命名系列。
 */
public class SeriesActivity extends BaseActivity {

    public static final String EXTRA_SERIES_NAME = "series_name";

    private ActivitySeriesBinding b;
    private String seriesName = "";
    private final List<Book> books = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySeriesBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsHelper.applyBottomInset(b.seriesBottomBar);

        seriesName = getIntent().getStringExtra(EXTRA_SERIES_NAME);
        if (seriesName == null) {
            seriesName = "";
        }
        seriesName = seriesName.trim();
        if (seriesName.isEmpty()) {
            finish();
            return;
        }
        b.tvSeriesRename.setOnClickListener(v -> renameSeries());
        b.btnSeriesAdd.setOnClickListener(v -> addNextVolume());
        b.btnSeriesSplit.setOnClickListener(v -> openSplit());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        final String wanted = seriesName;
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Book> all = BookSanitizer.normalizeAll(app.db().bookDao().getAll());
            List<Book> members = new ArrayList<>();
            for (Book book : all) {
                if (BookSeries.name(book).equalsIgnoreCase(wanted)) {
                    members.add(book);
                }
            }
            final List<Book> sorted = BookSeries.sorted(members);
            int readDone = 0;
            int reading = 0;
            double listPrice = 0;
            double paid = 0;
            for (Book book : sorted) {
                if (book.readStatus == Book.READ_DONE) {
                    readDone++;
                } else if (book.readStatus == Book.READING) {
                    reading++;
                }
                if (book.listPrice > 0) {
                    listPrice += book.listPrice;
                }
                if (book.purchasePrice > 0) {
                    paid += book.purchasePrice;
                }
            }
            final List<String> missing = BookSeries.missingVolumes(sorted);
            final int fReadDone = readDone;
            final int fReading = reading;
            final double fListPrice = listPrice;
            final double fPaid = paid;
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                render(sorted, fReadDone, fReading, fListPrice, fPaid, missing);
            });
        });
    }

    private void render(List<Book> list, int readDone, int reading,
                        double listPrice, double paid, List<String> missing) {
        books.clear();
        books.addAll(list);

        b.tvSeriesName.setText(seriesName);
        StringBuilder meta = new StringBuilder();
        meta.append("共 ").append(list.size()).append(" 本");
        if (!list.isEmpty()) {
            meta.append(" · 已读 ").append(readDone);
            if (reading > 0) {
                meta.append(" · 在读 ").append(reading);
            }
            if (paid > 0) {
                meta.append(" · 实付 ¥").append(money(paid));
            }
            if (listPrice > 0) {
                meta.append(" · 定价 ¥").append(money(listPrice));
            }
        }
        b.tvSeriesMeta.setText(meta);

        if (missing.isEmpty()) {
            b.tvSeriesMissing.setVisibility(View.GONE);
        } else {
            StringBuilder text = new StringBuilder("缺第 ");
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) {
                    text.append("、");
                }
                text.append(missing.get(i));
            }
            text.append(" 卷");
            b.tvSeriesMissing.setText(text);
            b.tvSeriesMissing.setVisibility(View.VISIBLE);
        }

        b.llSeriesBooks.removeAllViews();
        b.tvSeriesEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        for (Book book : list) {
            b.llSeriesBooks.addView(buildBookRow(book));
        }

        // 单本没有可分摊的，分摊按钮收起，添加按钮占满
        boolean canSplit = list.size() >= 2;
        b.btnSeriesSplit.setVisibility(canSplit ? View.VISIBLE : View.GONE);
    }

    private View buildBookRow(Book book) {
        ItemSeriesBookBinding row = ItemSeriesBookBinding.inflate(getLayoutInflater(),
                b.llSeriesBooks, false);
        String volume = BookSeries.volumeLabel(book);
        row.tvSeriesVolume.setText(volume.isEmpty() ? "未标卷号" : volume);
        row.tvSeriesTitle.setText(BookText.displayTitle(book.title));

        StringBuilder meta = new StringBuilder();
        appendPart(meta, book.author);
        appendPart(meta, book.publisher);
        if (book.purchasePrice > 0) {
            appendPart(meta, "¥" + money(book.purchasePrice));
        }
        row.tvSeriesMeta.setText(meta);
        row.tvSeriesMeta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);

        StringBuilder status = new StringBuilder(StatusText.read(book.readStatus))
                .append(" · ").append(StatusText.stock(book.stockStatus));
        if (book.readStatus == Book.READING
                && book.readingProgress > 0 && book.readingProgress < 100) {
            status.append(" · 读到 ").append(book.readingProgress).append("%");
        }
        if (book.rating > 0) {
            status.append(" · 我的评分 ").append(book.rating);
        }
        row.tvSeriesStatus.setText(status);

        CoverLoader.load(row.ivSeriesCover, book);
        row.getRoot().setOnClickListener(v -> {
            Intent intent = new Intent(this, BookDetailActivity.class);
            intent.putExtra(BookDetailActivity.EXTRA_BOOK, book);
            startActivity(intent);
        });
        return row.getRoot();
    }

    private void addNextVolume() {
        Book prefill = new Book();
        prefill.seriesName = seriesName;
        String next = BookSeries.nextVolume(books);
        if (!next.isEmpty()) {
            prefill.seriesVolume = next;
        }
        Intent intent = new Intent(this, BookEditActivity.class);
        intent.putExtra(BookEditActivity.EXTRA_PREFILL, prefill);
        startActivity(intent);
    }

    private void openSplit() {
        if (books.size() < 2) {
            return;
        }
        long[] ids = new long[books.size()];
        String[] names = new String[books.size()];
        double[] prices = new double[books.size()];
        for (int i = 0; i < books.size(); i++) {
            Book book = books.get(i);
            ids[i] = book.id;
            String volume = BookSeries.volumeLabel(book);
            names[i] = (volume.isEmpty() ? "" : volume + " · ") + BookText.bareTitle(book.title);
            prices[i] = book.listPrice;
        }
        Intent intent = new Intent(this, SplitActivity.class);
        intent.putExtra(SplitActivity.EXTRA_BOOK_IDS, ids);
        intent.putExtra(SplitActivity.EXTRA_BOOK_NAMES, names);
        intent.putExtra(SplitActivity.EXTRA_BOOK_PRICES, prices);
        intent.putExtra(SplitActivity.EXTRA_SERIES_NAME, seriesName);
        startActivity(intent);
    }

    /** 重命名系列：同步更新该系列全部藏书的系列名快照 */
    private void renameSeries() {
        DialogInputBinding ib = DialogInputBinding.inflate(getLayoutInflater());
        ib.inputLayout.setHint("系列名");
        ib.edit.setText(seriesName);
        ib.edit.setSelection(seriesName.length());
        final String oldName = seriesName;
        new MaterialAlertDialogBuilder(this)
                .setTitle("重命名系列")
                .setView(ib.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String newName = ib.edit.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(oldName)) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        List<Book> all = app.db().bookDao().getAll();
                        long now = System.currentTimeMillis();
                        int changed = 0;
                        for (Book book : all) {
                            if (BookSeries.name(book).equalsIgnoreCase(oldName)) {
                                book.seriesName = newName;
                                book.updatedAt = now;
                                book.dirty = true;
                                app.db().bookDao().update(book);
                                changed++;
                            }
                        }
                        final int fChanged = changed;
                        app.ui(() -> {
                            seriesName = newName;
                            Toast.makeText(this, "已重命名 " + fChanged + " 本",
                                    Toast.LENGTH_SHORT).show();
                            load();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void appendPart(StringBuilder sb, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(value.trim());
    }

    private static String money(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.getDefault(), "%.2f", value);
    }
}
