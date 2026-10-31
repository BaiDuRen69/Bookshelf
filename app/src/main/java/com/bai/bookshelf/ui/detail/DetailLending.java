package com.bai.bookshelf.ui.detail;

import android.net.Uri;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.DialogLendBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.StatusText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 详情页的借出 ⇄ 收回流程：单本底栏切换、逐本行内按钮、借出登记弹窗
 * （常用借阅人 Chip、应还日期、借出备注与图片）以及收回后的状态回写。
 */
public class DetailLending {

    /** 宿主提供当前书籍数据与页面刷新入口 */
    public interface Host {
        Book book();

        List<BorrowRecord> openRecords();

        List<BookCopy> copies();

        void reload();

        void refreshReminders();

        void ensureNotifyPermission();
    }

    private final BaseActivity activity;
    private final Host host;

    private int lendCopyIndex = 1;
    // 借出登记弹窗中临时收集的备注图片
    private final List<NoteImage> pendingImages = new ArrayList<>();
    private final Set<String> addedPaths = new HashSet<>();
    private final Set<String> removedPaths = new HashSet<>();
    private DialogLendBinding currentDialog;
    private final ActivityResultLauncher<String> pickImageLauncher;

    public DetailLending(BaseActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.pickImageLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) {
                        addNoteImage(uri);
                    }
                });
    }

    /** 单本书底部的借出 ⇄ 收回 切换（操作第 1 本/主记录） */
    public void toggleLend() {
        if (sourceReturned()) {
            Toast.makeText(activity, "已归还给借阅来源，不能再借出", Toast.LENGTH_SHORT).show();
            return;
        }
        BorrowRecord open = DetailCopyInfo.openRecordForCopy(host.openRecords(), 1);
        if (host.book().stockStatus == Book.STOCK_LENT || open != null) {
            returnCopy(1, open);
        } else {
            showLendDialog(1);
        }
    }

    /** 收回第 index 本：关闭该本的借出记录 + 状态改回在库 */
    public void returnCopy(int index, BorrowRecord record) {
        BookshelfApp app = BookshelfApp.get();
        Book book = host.book();
        app.io(() -> {
            if (record != null) {
                record.status = BorrowRecord.STATUS_RETURNED;
                record.returnDate = StatusText.today();
                app.db().borrowRecordDao().update(record);
            }
            if (index == 1) {
                book.stockStatus = Book.STOCK_IN;
                // 借阅来源已归还且没有新的借阅：收回后回到「电子书签」
                if (com.bai.bookshelf.util.BookFlags.isBorrowSource(book)
                        && !com.bai.bookshelf.util.BorrowSourceRecords.hasOpen(book)
                        && book.borrowReturnDate != null
                        && !book.borrowReturnDate.trim().isEmpty()) {
                    book.stockStatus = Book.STOCK_BOOKMARK;
                }
                book.updatedAt = System.currentTimeMillis();
                app.db().bookDao().update(book);
            } else {
                BookCopy c = findSavedCopy(index);
                if (c != null) {
                    c.stockStatus = Book.STOCK_IN;
                    app.db().bookCopyDao().update(c);
                }
            }
            app.ui(() -> {
                Toast.makeText(activity, "第 " + index + " 册已收回", Toast.LENGTH_SHORT).show();
                host.reload();
                host.refreshReminders();
            });
        });
    }

    /** 借出登记对话框：常用借阅人 Chip 单选（可新建/长按管理）+ 借出备注 + 备注图片 */
    public void showLendDialog(int copyIndex) {
        if (sourceReturned()) {
            Toast.makeText(activity, "已归还给借阅来源，不能再借出", Toast.LENGTH_SHORT).show();
            return;
        }
        lendCopyIndex = copyIndex;
        pendingImages.clear();
        addedPaths.clear();
        removedPaths.clear();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<com.bai.bookshelf.data.entity.Borrower> all = app.db().borrowerDao().getAll();
            app.ui(() -> showLendDialogWithBorrowers(all));
        });
    }

    /** 来源为借阅且已归还：不能再借出 */
    private boolean sourceReturned() {
        return com.bai.bookshelf.util.BookFlags.isReturnedBorrow(host.book());
    }

    /** 在副本表里找第 index 本（没录过则返回 null，借出时会新增一行） */
    private BookCopy findSavedCopy(int index) {
        for (BookCopy c : host.copies()) {
            if (c.copyIndex == index) {
                return c;
            }
        }
        return null;
    }

    private void showLendDialogWithBorrowers(List<com.bai.bookshelf.data.entity.Borrower> all) {
        currentDialog = DialogLendBinding.inflate(activity.getLayoutInflater());
        final DialogLendBinding db = currentDialog;
        BorrowRecord openNow = DetailCopyInfo.openRecordForCopy(host.openRecords(), lendCopyIndex);

        // 单选：当前选中的借阅人（借出记录可能带入上次借用人）
        String[] selected = {openNow != null ? openNow.borrower : ""};
        List<com.google.android.material.chip.Chip> chips = new ArrayList<>();
        for (com.bai.bookshelf.data.entity.Borrower borrower : all) {
            com.google.android.material.chip.Chip chip = buildBorrowerChip(borrower, selected, chips);
            chips.add(chip);
            db.chipGroup.addView(chip);
        }
        // 末尾的「＋ 新建借阅人」Chip
        com.google.android.material.chip.Chip addChip = new com.google.android.material.chip.Chip(activity);
        addChip.setText("＋ 新建借阅人");
        addChip.setOnClickListener(v -> showNewBorrowerInput(db, selected, chips));
        db.chipGroup.addView(addChip);

        // v4.42：选中借阅人时显示其联系方式（长按 Chip 可补充）
        Runnable refreshContact = () -> {
            String phone = "";
            for (com.bai.bookshelf.data.entity.Borrower borrower : all) {
                if (borrower.name.equals(selected[0])) {
                    phone = borrower.phone == null ? "" : borrower.phone;
                    break;
                }
            }
            if (selected[0].isEmpty()) {
                db.tvBorrowerContact.setText("未选择借阅人");
            } else if (phone.isEmpty()) {
                db.tvBorrowerContact.setText("联系方式：未填写（长按借阅人可补充）");
            } else {
                db.tvBorrowerContact.setText("联系方式：" + phone);
            }
        };
        refreshContact.run();
        db.chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> refreshContact.run());

        // 借出备注图片
        db.btnAddNoteImage.setOnClickListener(v -> {
            try {
                pickImageLauncher.launch("image/*");
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开相册：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        renderNoteThumbs(db);

        // v4.42：应还日期（默认 30 天后，可清空；到期后由本地通知提醒）
        final String[] dueDate = {defaultDueDate()};
        Runnable refreshDue = () -> db.tvDueDate.setText(dueDate[0].isEmpty()
                ? "应还日期：未设置"
                : "应还日期：" + dueDate[0]);
        refreshDue.run();
        db.tvDueDate.setOnClickListener(v -> com.bai.bookshelf.ui.widget.CalendarPicker.showSingle(
                activity, "应还日期", com.bai.bookshelf.util.BorrowReminder.parseKey(dueDate[0]),
                key -> {
                    dueDate[0] = com.bai.bookshelf.util.BorrowReminder.formatKey(key);
                    refreshDue.run();
                },
                () -> {
                    dueDate[0] = "";
                    refreshDue.run();
                }));

        new MaterialAlertDialogBuilder(activity)
                .setTitle("借出登记（第 " + lendCopyIndex + " 册）")
                .setView(db.getRoot())
                .setPositiveButton("借出", (d, w) ->
                        doLend(lendCopyIndex, selected[0], db.etNote.getText().toString().trim(),
                                dueDate[0]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 默认应还日期：今天 + 30 天 */
    private String defaultDueDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_MONTH, 30);
        return com.bai.bookshelf.util.BorrowReminder.formatKey(
                c.get(java.util.Calendar.YEAR) * 10000
                        + (c.get(java.util.Calendar.MONTH) + 1) * 100
                        + c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /** 把相册图落盘并加入借出登记弹窗的临时图片列表 */
    private void addNoteImage(Uri uri) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                File dir = new File(activity.getFilesDir(), "note_images");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File out = new File(dir, "note_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                }
                app.ui(() -> {
                    NoteImage img = new NoteImage();
                    img.bookId = host.book().id;
                    img.path = out.getAbsolutePath();
                    pendingImages.add(img);
                    addedPaths.add(img.path);
                    if (currentDialog != null) {
                        renderNoteThumbs(currentDialog);
                    }
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(activity, "图片读取失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 渲染借出登记弹窗的备注图片缩略图 */
    private void renderNoteThumbs(DialogLendBinding db) {
        db.containerNoteThumbs.removeAllViews();
        int size = (int) (72 * activity.getResources().getDisplayMetrics().density);
        int gap = (int) (8 * activity.getResources().getDisplayMetrics().density);
        for (NoteImage img : pendingImages) {
            ImageView iv = new ImageView(activity);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            com.bumptech.glide.Glide.with(activity).load(new File(img.path))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> showNotePreview(img, db));
            db.containerNoteThumbs.addView(iv);
        }
        boolean has = !pendingImages.isEmpty();
        db.scrollNoteThumbs.setVisibility(has ? android.view.View.VISIBLE : android.view.View.GONE);
        db.btnAddNoteImage.setText(has
                ? "＋ 继续添加备注图片（" + pendingImages.size() + " 张）"
                : "＋ 添加备注图片");
    }

    private void showNotePreview(NoteImage img, DialogLendBinding db) {
        // v4.18: 图片放进圆角卡片底（dialog_image_preview）
        com.bai.bookshelf.databinding.DialogImagePreviewBinding pb =
                com.bai.bookshelf.databinding.DialogImagePreviewBinding.inflate(
                        activity.getLayoutInflater());
        com.bumptech.glide.Glide.with(activity).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("备注图片")
                .setView(pb.getRoot())
                .setPositiveButton("关闭", null)
                .setNegativeButton("移除", (d, w) -> {
                    pendingImages.remove(img);
                    if (!addedPaths.contains(img.path)) {
                        removedPaths.add(img.path);
                    }
                    renderNoteThumbs(db);
                })
                .show();
    }

    /** 借阅人 Chip：点击单选互斥，长按重命名/删除（与分类选择器同套交互） */
    private com.google.android.material.chip.Chip buildBorrowerChip(
            com.bai.bookshelf.data.entity.Borrower borrower, String[] selected,
            List<com.google.android.material.chip.Chip> chips) {
        com.google.android.material.chip.Chip chip =
                new com.google.android.material.chip.Chip(activity);
        chip.setText(borrower.name);
        chip.setCheckable(true);
        chip.setChecked(borrower.name.equals(selected[0]));
        chip.setOnClickListener(v -> {
            if (borrower.name.equals(selected[0])) {
                selected[0] = "";
                chip.setChecked(false);
            } else {
                selected[0] = borrower.name;
                for (com.google.android.material.chip.Chip other : chips) {
                    other.setChecked(other == chip);
                }
            }
        });
        chip.setOnLongClickListener(v -> {
            showBorrowerOptions(borrower, chip, selected);
            return true;
        });
        return chip;
    }

    /** 借阅人长按菜单：重命名 / 删除 */
    private void showBorrowerOptions(com.bai.bookshelf.data.entity.Borrower borrower,
                                     com.google.android.material.chip.Chip chip,
                                     String[] selected) {
        String[] options = {"重命名", "编辑联系方式", "删除"};
        new MaterialAlertDialogBuilder(activity)
                .setTitle("借阅人「" + borrower.name + "」")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showRenameBorrowerInput(borrower, chip, selected);
                    } else if (which == 1) {
                        showBorrowerPhoneInput(borrower);
                    } else {
                        confirmDeleteBorrower(borrower, chip, selected);
                    }
                })
                .show();
    }

    /** 重命名借阅人：改库 + 改 Chip 文本（历史借出记录里的名字保留原样） */
    private void showRenameBorrowerInput(com.bai.bookshelf.data.entity.Borrower borrower,
                                         com.google.android.material.chip.Chip chip,
                                         String[] selected) {
        DialogInputBinding ib = DialogInputBinding.inflate(activity.getLayoutInflater());
        ib.edit.setText(borrower.name);
        ib.edit.setSelection(borrower.name.length());
        new MaterialAlertDialogBuilder(activity)
                .setTitle("重命名借阅人")
                .setView(ib.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String newName = ib.edit.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(borrower.name)) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        borrower.name = newName;
                        app.db().borrowerDao().update(borrower);
                        app.ui(() -> {
                            chip.setText(newName);
                            Toast.makeText(activity, "已重命名为「" + newName + "」",
                                    Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 编辑借阅人联系方式（v4.42）：催还时可直接照着联系 */
    private void showBorrowerPhoneInput(com.bai.bookshelf.data.entity.Borrower borrower) {
        DialogInputBinding ib = DialogInputBinding.inflate(activity.getLayoutInflater());
        ib.inputLayout.setHint("电话 / 微信等联系方式");
        ib.edit.setText(borrower.phone == null ? "" : borrower.phone);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("「" + borrower.name + "」的联系方式")
                .setView(ib.getRoot())
                .setPositiveButton("保存", (d, w) -> {
                    String phone = ib.edit.getText().toString().trim();
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        borrower.phone = phone;
                        app.db().borrowerDao().update(borrower);
                        app.ui(() -> Toast.makeText(activity,
                                phone.isEmpty() ? "已清空联系方式" : "联系方式已保存",
                                Toast.LENGTH_SHORT).show());
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 删除借阅人：确认后从库中删除并移除 Chip */
    private void confirmDeleteBorrower(com.bai.bookshelf.data.entity.Borrower borrower,
                                       com.google.android.material.chip.Chip chip,
                                       String[] selected) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("删除借阅人")
                .setMessage("从常用借阅人里删除「" + borrower.name + "」？历史借出记录不受影响。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().borrowerDao().deleteById(borrower.id);
                        app.ui(() -> {
                            if (selected[0].equals(borrower.name)) {
                                selected[0] = "";
                            }
                            android.view.ViewGroup parent =
                                    (android.view.ViewGroup) chip.getParent();
                            if (parent != null) {
                                parent.removeView(chip);
                            }
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 新建借阅人：入库 → 生成一个默认选中的 Chip 插到「＋」之前 */
    private void showNewBorrowerInput(DialogLendBinding db, String[] selected,
                                      List<com.google.android.material.chip.Chip> chips) {
        DialogInputBinding ib = DialogInputBinding.inflate(activity.getLayoutInflater());
        ib.inputLayout.setHint("姓名或称呼（如 小王、表姐）");
        new MaterialAlertDialogBuilder(activity)
                .setTitle("新建借阅人")
                .setView(ib.getRoot())
                .setPositiveButton("创建", (d, w) -> {
                    String name = ib.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        com.bai.bookshelf.data.entity.Borrower borrower =
                                new com.bai.bookshelf.data.entity.Borrower();
                        borrower.name = name;
                        borrower.sort = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                        long id = app.db().borrowerDao().insert(borrower);
                        borrower.id = id;
                        app.ui(() -> {
                            selected[0] = name; // 新借阅人默认选中
                            com.google.android.material.chip.Chip chip =
                                    buildBorrowerChip(borrower, selected, chips);
                            chips.add(chip);
                            for (com.google.android.material.chip.Chip other : chips) {
                                other.setChecked(other == chip);
                            }
                            db.chipGroup.addView(chip, db.chipGroup.getChildCount() - 1);
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 登记借出某一本：该书这一本已借出时不能再次借出（需先归还） */
    private void doLend(int copyIndex, String borrower, String note, String dueDate) {
        if (borrower.isEmpty()) {
            Toast.makeText(activity, "请选择或新建借阅人", Toast.LENGTH_SHORT).show();
            return;
        }
        if (DetailCopyInfo.openRecordForCopy(host.openRecords(), copyIndex) != null) {
            Toast.makeText(activity, "第 " + copyIndex + " 册已借出，收回后才能再次借出",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        Book book = host.book();
        app.io(() -> {
            BorrowRecord record = new BorrowRecord();
            record.bookId = book.id;
            record.borrower = borrower;
            record.lendDate = StatusText.today();
            record.dueDate = dueDate == null ? "" : dueDate;
            record.note = note;
            record.copyIndex = copyIndex;
            long recordId = app.db().borrowRecordDao().insert(record);
            // 保存借出备注图片
            if (!pendingImages.isEmpty() && recordId > 0) {
                for (int i = 0; i < pendingImages.size(); i++) {
                    NoteImage img = pendingImages.get(i);
                    img.bookId = book.id;
                    img.ownerKey = "borrow:" + recordId;
                    img.sort = i;
                }
                app.db().noteImageDao().insertAll(pendingImages);
                cleanupRemovedImageFiles();
            } else {
                cleanupRemovedImageFiles();
            }
            if (copyIndex == 1) {
                // 第 1 本的状态写在主记录上
                book.stockStatus = Book.STOCK_LENT;
                book.updatedAt = System.currentTimeMillis();
                app.db().bookDao().update(book);
            } else {
                BookCopy c = findSavedCopy(copyIndex);
                if (c == null) {
                    // 这一本此前没录过详情，先建一行最小副本再标借出
                    c = new BookCopy();
                    c.bookId = book.id;
                    c.copyIndex = copyIndex;
                    c.stockStatus = Book.STOCK_LENT;
                    c.ownType = Book.OWN_BUY;
                    long id = app.db().bookCopyDao().insert(c);
                    c.id = id;
                } else {
                    c.stockStatus = Book.STOCK_LENT;
                    app.db().bookCopyDao().update(c);
                }
            }
            app.ui(() -> {
                Toast.makeText(activity, "第 " + copyIndex + " 册已借出给 " + borrower,
                        Toast.LENGTH_SHORT).show();
                host.reload();
                // v4.42：有应还日期就确保通知权限并重排提醒
                if (dueDate != null && !dueDate.isEmpty()) {
                    host.ensureNotifyPermission();
                    host.refreshReminders();
                }
            });
        });
    }

    /** 借出登记保存后：清掉本次新加却未保留的临时文件 */
    private void cleanupRemovedImageFiles() {
        for (String p : addedPaths) {
            boolean kept = false;
            for (NoteImage img : pendingImages) {
                if (img.path.equals(p)) {
                    kept = true;
                    break;
                }
            }
            if (!kept) {
                new File(p).delete();
            }
        }
        for (String p : removedPaths) {
            new File(p).delete();
        }
        addedPaths.clear();
        removedPaths.clear();
    }
}
