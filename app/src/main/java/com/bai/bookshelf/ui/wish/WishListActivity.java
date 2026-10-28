package com.bai.bookshelf.ui.wish;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.WishBook;
import com.bai.bookshelf.databinding.ActivityWishListBinding;
import com.bai.bookshelf.databinding.DialogWishBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 想读清单（v4.48）：把还没买/没借到的书先记下来。
 * 买到后「转为藏书」把书名/作者/ISBN/心理价带进录入页，并删除这条想读。
 */
public class WishListActivity extends BaseActivity {

    private static final int REQ_CONVERT = 4801;

    private ActivityWishListBinding b;
    private DialogWishBinding dialog;
    private final List<WishBook> items = new ArrayList<>();
    /** 正在「转为藏书」的那条想读；录入页真的保存成功后（RESULT_OK）才删除 */
    private WishBook converting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityWishListBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        View bottom = (View) b.btnAddWish.getParent();
        InsetsHelper.applyBottomInset(bottom);
        b.btnAddWish.setOnClickListener(v -> showWishDialog(null));
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(); // 从录入页返回后刷新（转为藏书会删掉这条）
    }

    private void load() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<WishBook> list;
            try {
                list = app.db().wishBookDao().getAll();
            } catch (Exception e) {
                list = new ArrayList<>();
            }
            final List<WishBook> fList = list;
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                items.clear();
                items.addAll(fList);
                render();
            });
        });
    }

    private void render() {
        b.llWish.removeAllViews();
        boolean empty = items.isEmpty();
        b.llWishEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            return;
        }
        for (WishBook wish : items) {
            b.llWish.addView(buildRow(wish));
        }
    }

    private View buildRow(WishBook wish) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> showWishDialog(wish));
        card.setOnLongClickListener(v -> {
            confirmDelete(wish);
            return true;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(wish.title);
        title.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface));
        title.setTextSize(16);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView priority = new TextView(this);
        priority.setText(priorityText(wish.priority));
        priority.setTextSize(11);
        priority.setTextColor(ThemeManager.resolveColor(this,
                wish.priority == WishBook.PRIORITY_HIGH
                        ? ThemeManager.ATTR_COLOR_PRIMARY : R.attr.on_surface_variant));
        priority.setBackgroundResource(R.drawable.bg_filter_pill);
        priority.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.setMarginStart(dp(8));
        header.addView(priority, plp);

        StringBuilder meta = new StringBuilder();
        if (!wish.author.isEmpty()) {
            meta.append(wish.author);
        }
        if (!wish.isbn.isEmpty()) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append(wish.isbn);
        }
        if (wish.expectedPrice > 0) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append("心理价 ¥").append(fmt(wish.expectedPrice));
        }
        if (meta.length() > 0) {
            TextView tvMeta = new TextView(this);
            tvMeta.setText(meta);
            tvMeta.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface_variant));
            tvMeta.setTextSize(13);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(4);
            card.addView(tvMeta, mlp);
        }
        if (!wish.note.isEmpty()) {
            TextView tvNote = new TextView(this);
            tvNote.setText(wish.note);
            tvNote.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface_variant));
            tvNote.setTextSize(13);
            tvNote.setMaxLines(2);
            tvNote.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(4);
            card.addView(tvNote, nlp);
        }
        return card;
    }

    private void showWishDialog(WishBook existing) {
        dialog = DialogWishBinding.inflate(getLayoutInflater());
        dialog.cgWishPriority.check(priorityToChipId(
                existing == null ? WishBook.PRIORITY_MID : existing.priority));

        if (existing != null) {
            dialog.etWishTitle.setText(existing.title);
            dialog.etWishAuthor.setText(existing.author);
            dialog.etWishIsbn.setText(existing.isbn);
            dialog.etWishPrice.setText(existing.expectedPrice > 0 ? fmt(existing.expectedPrice) : "");
            dialog.etWishNote.setText(existing.note);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? "添加想读" : "编辑想读")
                .setView(dialog.getRoot())
                .setPositiveButton("保存", null)
                .setNegativeButton(R.string.action_cancel, null);
        if (existing != null) {
            builder.setNeutralButton("转为藏书", (d, w) -> convertToBook(existing));
        }
        androidx.appcompat.app.AlertDialog alert = builder.show();
        // 保存按钮不直接关窗：书名空时留在弹窗里，避免刚填的内容被清掉
        alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (saveWish(existing)) {
                        alert.dismiss();
                    }
                });
    }

    private boolean saveWish(WishBook existing) {
        String title = dialog.etWishTitle.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "书名不能为空", Toast.LENGTH_SHORT).show();
            return false;
        }
        final WishBook wish = existing == null ? new WishBook() : existing;
        wish.title = title;
        wish.author = dialog.etWishAuthor.getText().toString().trim();
        wish.isbn = dialog.etWishIsbn.getText().toString().trim();
        wish.priority = chipIdToPriority(dialog.cgWishPriority.getCheckedChipId());
        try {
            wish.expectedPrice = Double.parseDouble(
                    dialog.etWishPrice.getText().toString().trim());
        } catch (Exception e) {
            wish.expectedPrice = 0;
        }
        wish.note = dialog.etWishNote.getText().toString().trim();
        long now = System.currentTimeMillis();
        wish.updatedAt = now;
        if (existing == null) {
            wish.createdAt = now;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            if (existing == null) {
                app.db().wishBookDao().insert(wish);
            } else {
                app.db().wishBookDao().update(wish);
            }
            app.ui(this::load);
        });
        return true;
    }

    private void confirmDelete(WishBook wish) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除想读")
                .setMessage("把《" + wish.title + "》从想读清单里删掉？")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().wishBookDao().deleteById(wish.id);
                        app.ui(this::load);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 买到/借到：把信息带进录入页；等录入页真的保存成功后再删掉这条想读 */
    private void convertToBook(WishBook wish) {
        converting = wish;
        Book prefill = new Book();
        prefill.title = wish.title;
        prefill.author = wish.author;
        prefill.isbn = wish.isbn;
        prefill.listPrice = wish.expectedPrice;
        android.content.Intent intent = new android.content.Intent(this, BookEditActivity.class);
        intent.putExtra(BookEditActivity.EXTRA_PREFILL, prefill);
        startActivityForResult(intent, REQ_CONVERT);
        Toast.makeText(this, "已带入录入页，保存后这条想读会自动移除",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CONVERT) {
            return;
        }
        WishBook wish = converting;
        converting = null;
        if (wish == null || resultCode != RESULT_OK) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            app.db().wishBookDao().deleteById(wish.id);
            app.ui(() -> {
                Toast.makeText(this, "已加入书架，想读清单里已移除这条", Toast.LENGTH_SHORT).show();
                load();
            });
        });
    }

    private static int priorityToChipId(int priority) {
        if (priority == WishBook.PRIORITY_LOW) {
            return R.id.chip_priority_low;
        }
        if (priority == WishBook.PRIORITY_HIGH) {
            return R.id.chip_priority_high;
        }
        return R.id.chip_priority_mid;
    }

    private static int chipIdToPriority(int chipId) {
        if (chipId == R.id.chip_priority_low) {
            return WishBook.PRIORITY_LOW;
        }
        if (chipId == R.id.chip_priority_high) {
            return WishBook.PRIORITY_HIGH;
        }
        return WishBook.PRIORITY_MID;
    }

    private static String priorityText(int priority) {
        if (priority == WishBook.PRIORITY_HIGH) {
            return "高优先";
        }
        if (priority == WishBook.PRIORITY_LOW) {
            return "低优先";
        }
        return "中优先";
    }

    private static String fmt(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.getDefault(), "%.2f", value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
