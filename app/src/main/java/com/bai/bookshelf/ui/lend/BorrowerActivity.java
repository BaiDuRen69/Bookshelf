package com.bai.bookshelf.ui.lend;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Borrower;
import com.bai.bookshelf.databinding.ActivityBorrowersBinding;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.ItemBorrowerBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.detail.DetailShare;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.BorrowReminder;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.StatsLend;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 借阅人档案（v5.8.18）：一个人一行，累计「借过几本 / 逾期几次 / 现在手上几本 / 按时归还率」。
 *
 * <p>统计口径与统计页「借阅人可靠性」完全同源（{@link StatsLend#lenders}），只是按人摊开并
 * 给出动作：点一行能把他没还的书一次发成借阅条（复用 v5.8.18 的借阅条画布），长按改名/删档。
 * 借出记录里存的是名字字符串，所以档案删了不影响历史记录；反过来，改过名的老记录仍写着旧名，
 * 这类「没建档但有记录」的名字也会列出来，标成「未建档」。
 */
public class BorrowerActivity extends BaseActivity {

    /** 一行档案 */
    private static final class Row {
        final String name;
        @Nullable
        final Borrower profile;
        @Nullable
        final StatsLend.Lender stats;
        final List<BorrowRecord> open = new ArrayList<>();

        Row(String name, @Nullable Borrower profile, @Nullable StatsLend.Lender stats) {
            this.name = name;
            this.profile = profile;
            this.stats = stats;
        }
    }

    private ActivityBorrowersBinding b;
    private final List<Row> rows = new ArrayList<>();
    private final Map<Long, Book> booksById = new HashMap<>();
    private Adapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityBorrowersBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsHelper.applyTopInset(b.toolbar);
        InsetsHelper.applyBottomInset(b.btnAdd);
        b.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new Adapter();
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);

        b.btnAdd.setOnClickListener(v -> showAddInput());
        load();
    }

    private void load() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Borrower> people;
            List<BorrowRecord> records;
            List<Book> books;
            try {
                people = app.db().borrowerDao().getAll();
                records = app.db().borrowRecordDao().getAll();
                books = app.db().bookDao().getAll();
            } catch (Exception e) {
                people = new ArrayList<>();
                records = new ArrayList<>();
                books = new ArrayList<>();
            }
            final List<Borrower> fPeople = people;
            final List<BorrowRecord> fRecords = records;
            final Map<String, StatsLend.Lender> byName = new HashMap<>();
            for (StatsLend.Lender lender : StatsLend.lenders(records, BorrowReminder.todayKey())) {
                byName.put(lender.name, lender);
            }
            final Map<Long, Book> fBooks = new HashMap<>();
            for (Book book : books) {
                fBooks.put(book.id, book);
            }
            app.ui(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                booksById.clear();
                booksById.putAll(fBooks);
                buildRows(fPeople, fRecords, byName);
                adapter.notifyDataSetChanged();
                b.tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                b.recycler.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void buildRows(List<Borrower> people, List<BorrowRecord> records,
                           Map<String, StatsLend.Lender> byName) {
        rows.clear();
        List<String> known = new ArrayList<>();
        for (Borrower person : people) {
            String name = person.name == null ? "" : person.name.trim();
            if (name.isEmpty() || known.contains(name)) {
                continue;
            }
            known.add(name);
            rows.add(new Row(name, person, byName.get(name)));
        }
        // 记录里有、档案里没有的名字（改过名或删过档）也要露出来
        for (String name : byName.keySet()) {
            if (!known.contains(name)) {
                rows.add(new Row(name, null, byName.get(name)));
            }
        }
        for (Row row : rows) {
            for (BorrowRecord record : records) {
                String name = record.borrower == null || record.borrower.trim().isEmpty()
                        ? "未填借阅人" : record.borrower.trim();
                if (name.equals(row.name) && record.status == BorrowRecord.STATUS_OUT) {
                    row.open.add(record);
                }
            }
        }
        // 手上还有书的排前面，其次按借出笔数
        rows.sort((x, y) -> x.open.size() != y.open.size()
                ? y.open.size() - x.open.size()
                : (y.stats == null ? 0 : y.stats.loans) - (x.stats == null ? 0 : x.stats.loans));
    }

    private String statsLine(Row row) {
        StatsLend.Lender s = row.stats;
        if (s == null || s.loans == 0) {
            return row.profile == null ? "未建档" : "还没有借出记录";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("借过 ").append(s.loans).append(" 本");
        if (s.overdue > 0) {
            sb.append(" · 逾期 ").append(s.overdue).append(" 次");
        }
        double rate = s.onTimeRate();
        if (rate >= 0) {
            sb.append(" · 按时 ").append(Math.round(rate * 100)).append("%");
        }
        if (row.profile == null) {
            sb.append(" · 未建档");
        } else if (row.profile.phone != null && !row.profile.phone.trim().isEmpty()) {
            sb.append(" · ").append(row.profile.phone.trim());
        }
        return sb.toString();
    }

    /** 点一行：列出他没还的书，可以直接发借阅条 */
    private void showOpenLoans(Row row, View anchor) {
        if (row.open.isEmpty()) {
            Toast.makeText(this, row.name + " 手上没有没还的书", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] lines = new String[row.open.size()];
        for (int i = 0; i < row.open.size(); i++) {
            BorrowRecord record = row.open.get(i);
            Book book = booksById.get(record.bookId);
            String title = book == null ? "（书已删除）"
                    : "《" + BookText.bareTitle(book.title) + "》";
            int due = BorrowReminder.parseKey(record.dueDate);
            String tail;
            if (due == 0) {
                tail = "未约定应还";
            } else if (due < BorrowReminder.todayKey()) {
                tail = "逾期 " + BorrowReminder.daysBetween(due, BorrowReminder.todayKey()) + " 天";
            } else {
                tail = "应还 " + record.dueDate;
            }
            lines[i] = title + " · " + tail;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(row.name + " 手上 " + row.open.size() + " 本")
                .setItems(lines, null)
                .setPositiveButton("发借阅条", (d, w) -> {
                    List<DetailShare.Slip> slips = new ArrayList<>();
                    int today = BorrowReminder.todayKey();
                    for (BorrowRecord record : row.open) {
                        Book book = booksById.get(record.bookId);
                        if (book == null) {
                            continue;
                        }
                        int due = BorrowReminder.parseKey(record.dueDate);
                        int overdue = due > 0 && due < today
                                ? BorrowReminder.daysBetween(due, today) : 0;
                        slips.add(new DetailShare.Slip(book, record, overdue));
                    }
                    slips.sort((x, y) -> y.overdueDays - x.overdueDays);
                    DetailShare.shareLendSlips(this, slips, anchor);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 长按：改名 / 联系方式 / 删档 */
    private void showRowOptions(Row row) {
        String[] options = {"重命名", "编辑联系方式", "删除档案"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(row.name)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showRename(row);
                    } else if (which == 1) {
                        showPhone(row);
                    } else {
                        confirmDelete(row);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showAddInput() {
        DialogInputBinding input = DialogInputBinding.inflate(getLayoutInflater());
        input.inputLayout.setHint("借阅人名字");
        new MaterialAlertDialogBuilder(this)
                .setTitle("添加借阅人")
                .setView(input.getRoot())
                .setPositiveButton("添加", (d, w) -> {
                    String name = input.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    saveProfile(null, name, "");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showRename(Row row) {
        DialogInputBinding input = DialogInputBinding.inflate(getLayoutInflater());
        input.inputLayout.setHint("新名字");
        input.edit.setText(row.name);
        input.edit.setSelection(row.name.length());
        new MaterialAlertDialogBuilder(this)
                .setTitle("重命名借阅人")
                .setView(input.getRoot())
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.edit.getText().toString().trim();
                    if (name.isEmpty() || name.equals(row.name)) {
                        return;
                    }
                    // 只改档案名；历史借出记录里仍写着旧名字，统计会按旧名另列一行
                    saveProfile(row.profile, name,
                            row.profile == null ? "" : row.profile.phone);
                    Toast.makeText(this, "已改档案名；旧借出记录仍写着「" + row.name + "」",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showPhone(Row row) {
        if (row.profile == null) {
            Toast.makeText(this, "先给他建个档案才能填联系方式", Toast.LENGTH_SHORT).show();
            return;
        }
        DialogInputBinding input = DialogInputBinding.inflate(getLayoutInflater());
        input.inputLayout.setHint("手机号 / 微信");
        input.edit.setInputType(InputType.TYPE_CLASS_TEXT);
        input.edit.setText(row.profile.phone == null ? "" : row.profile.phone);
        new MaterialAlertDialogBuilder(this)
                .setTitle("联系方式")
                .setView(input.getRoot())
                .setPositiveButton("保存", (d, w) -> saveProfile(row.profile, row.name,
                        input.edit.getText().toString().trim()))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmDelete(Row row) {
        if (row.profile == null) {
            Toast.makeText(this, "这一行只是历史记录里的名字，没有档案可删",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除档案")
                .setMessage("删除「" + row.name + "」的档案？借出记录会保留，"
                        + "之后这一行会显示成「未建档」。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    long id = row.profile.id;
                    app.io(() -> {
                        app.db().borrowerDao().deleteById(id);
                        app.ui(this::load);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void saveProfile(@Nullable Borrower existing, String name, String phone) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            Borrower borrower = existing == null ? new Borrower() : existing;
            borrower.name = name;
            borrower.phone = phone;
            if (existing == null) {
                app.db().borrowerDao().insert(borrower);
            } else {
                app.db().borrowerDao().update(borrower);
            }
            app.ui(this::load);
        });
    }

    private class Adapter extends RecyclerView.Adapter<VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new VH(ItemBorrowerBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            Row row = rows.get(position);
            holder.b.tvName.setText(row.name);
            holder.b.tvStats.setText(statsLine(row));
            holder.b.tvInitial.setText(row.name.substring(0, 1));
            holder.b.tvOut.setVisibility(row.open.isEmpty() ? View.GONE : View.VISIBLE);
            holder.b.tvOut.setText("在外 " + row.open.size());
            holder.b.getRoot().setOnClickListener(v -> showOpenLoans(row, v));
            holder.b.getRoot().setOnLongClickListener(v -> {
                showRowOptions(row);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static class VH extends RecyclerView.ViewHolder {
        final ItemBorrowerBinding b;

        VH(ItemBorrowerBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
