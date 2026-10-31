package com.bai.bookshelf.ui.wish;

import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.WishBook;
import com.bai.bookshelf.databinding.DialogWishBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.WishBookMatcher;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 想读的添加/编辑弹窗（v5.6 从 WishListActivity 抽出）：
 * 书名/作者/ISBN/心理价/优先级/备注，连续添加保存后清空并回到书名，
 * 新增前按书名+作者（或 ISBN）查重；编辑时可转为藏书。
 */
public class WishEditDialog {

    public interface Host {
        List<WishBook> items();

        void onConvert(WishBook wish);

        /** 保存成功后刷新列表 */
        void onSaved();

        /** 这本书是否已在书架（仅提示，不阻止添加） */
        Book findInLibrary(WishBook wish);
    }

    private final BaseActivity activity;
    private final Host host;
    private DialogWishBinding dialog;

    public WishEditDialog(BaseActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    public void show(WishBook existing, boolean continuous) {
        dialog = DialogWishBinding.inflate(activity.getLayoutInflater());
        dialog.cgWishPriority.check(priorityToChipId(
                existing == null ? WishBook.PRIORITY_MID : existing.priority));

        if (existing != null) {
            dialog.etWishTitle.setText(existing.title);
            dialog.etWishAuthor.setText(existing.author);
            dialog.etWishNote.setText(existing.note);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(existing == null
                        ? (continuous ? "连续添加想读" : "添加想读") : "编辑想读")
                .setView(dialog.getRoot())
                .setPositiveButton("保存", null)
                .setNegativeButton(R.string.action_cancel, null);
        if (existing != null) {
            builder.setNeutralButton("转为藏书", (d, w) -> host.onConvert(existing));
        }
        AlertDialog alert = builder.show();
        // 保存按钮不直接关窗：书名空/重复时留在弹窗里，避免刚填的内容被清掉
        alert.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    WishBook saved = save(existing);
                    if (saved == null) {
                        return;
                    }
                    if (continuous && existing == null) {
                        clearForm();
                        Toast.makeText(activity, "已添加，可继续添加下一条",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        alert.dismiss();
                    }
                });
    }

    /** 连续添加：保存成功后清空输入，焦点回到书名 */
    private void clearForm() {
        if (dialog == null) {
            return;
        }
        dialog.etWishTitle.setText("");
        dialog.etWishAuthor.setText("");
        dialog.etWishNote.setText("");
        dialog.cgWishPriority.check(priorityToChipId(WishBook.PRIORITY_MID));
        dialog.etWishTitle.requestFocus();
        dialog.etWishTitle.post(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(dialog.etWishTitle,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    /**
     * 保存想读；成功返回保存后的条目，失败（书名空/重复）返回 null。
     * 新增时会先放进内存列表，保证「连续添加」下一次查重能看到刚保存的书。
     */
    private WishBook save(WishBook existing) {
        String title = dialog.etWishTitle.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(activity, "书名不能为空", Toast.LENGTH_SHORT).show();
            return null;
        }
        WishBook draft = new WishBook();
        draft.id = existing == null ? 0 : existing.id;
        draft.title = title;
        draft.author = dialog.etWishAuthor.getText().toString().trim();
        // v5.7：ISBN / 心理价不再在编辑页出现；编辑时保留库里旧值，新增留空
        draft.isbn = existing == null ? "" : existing.isbn;
        draft.expectedPrice = existing == null ? 0 : existing.expectedPrice;
        draft.priority = chipIdToPriority(dialog.cgWishPriority.getCheckedChipId());
        draft.note = dialog.etWishNote.getText().toString().trim();

        WishBook duplicate = WishBookMatcher.findDuplicate(host.items(), draft);
        if (duplicate != null) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("已在想读清单")
                    .setMessage("想读清单里已有《" + duplicate.title
                            + "》，请先删除或编辑重复的那一条。")
                    .setPositiveButton("知道了", null)
                    .show();
            return null;
        }

        long now = System.currentTimeMillis();
        draft.updatedAt = now;
        BookshelfApp app = BookshelfApp.get();
        if (existing == null) {
            // v5.7：在书架里已有同名书时只提示，不阻止（可能是不同版本）
            final boolean inLibrary = host.findInLibrary(draft) != null;
            draft.createdAt = now;
            // 手动排序用：新条目排在已有条目之后（已有 sort 通常是 0..n）
            draft.sort = (int) (now / 1000L);
            final WishBook saved = draft;
            host.items().add(0, saved); // 立即进入内存列表，连续添加时下一本可查重
            app.io(() -> {
                app.db().wishBookDao().insert(saved);
                app.ui(() -> {
                    if (inLibrary) {
                        Toast.makeText(activity,
                                "提示：这本书可能已在书架中（不同版本不受限制）",
                                Toast.LENGTH_LONG).show();
                    }
                    host.onSaved();
                });
            });
            return saved;
        }
        existing.title = draft.title;
        existing.author = draft.author;
        existing.priority = draft.priority;
        existing.note = draft.note;
        existing.updatedAt = now;
        final WishBook target = existing;
        app.io(() -> {
            app.db().wishBookDao().update(target);
            app.ui(host::onSaved);
        });
        return existing;
    }

    private static int priorityToChipId(int priority) {
        if (priority == WishBook.PRIORITY_HIGH) {
            return R.id.chip_priority_high;
        }
        if (priority == WishBook.PRIORITY_LOW) {
            return R.id.chip_priority_low;
        }
        return R.id.chip_priority_mid;
    }

    private static int chipIdToPriority(int chipId) {
        if (chipId == R.id.chip_priority_high) {
            return WishBook.PRIORITY_HIGH;
        }
        if (chipId == R.id.chip_priority_low) {
            return WishBook.PRIORITY_LOW;
        }
        return WishBook.PRIORITY_MID;
    }

}
