package com.bai.bookshelf.ui.edit;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Publisher;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.DialogTagsBinding;
import com.bai.bookshelf.util.BookSanitizer;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 出版社选择器（v5.7，从 BookEditActivity 抽出）：
 * 单选已有出版社 / 新建 / 长按重命名或删除；重命名与删除会同步全部书籍。
 */
public final class PublisherPicker {

    private PublisherPicker() {
    }

    public static void show(Activity activity, EditText field) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Publisher> all = app.db().publisherDao().getAll();
            app.ui(() -> showDialog(activity, field, all));
        });
    }

    private static void showDialog(Activity activity, EditText field, List<Publisher> all) {
        DialogTagsBinding db = DialogTagsBinding.inflate(activity.getLayoutInflater());
        final String[] selected = {field.getText().toString().trim()};
        final List<Chip> chips = new ArrayList<>();
        for (Publisher publisher : all) {
            Chip chip = buildChip(activity, field, publisher, selected, chips);
            chips.add(chip);
            db.chipGroup.addView(chip);
        }
        Chip addChip = new Chip(activity);
        addChip.setText("＋ 新建出版社");
        addChip.setOnClickListener(v -> showNewInput(activity, field, db, selected, chips));
        db.chipGroup.addView(addChip);

        new MaterialAlertDialogBuilder(activity)
                .setTitle("选择出版社（单选）")
                .setView(db.getRoot())
                .setPositiveButton("完成", (d, w) -> field.setText(selected[0]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static Chip buildChip(Activity activity, EditText field, Publisher publisher,
                                  String[] selected, List<Chip> chips) {
        Chip chip = new Chip(activity);
        chip.setText(publisher.name);
        chip.setCheckable(true);
        chip.setChecked(publisher.name.equals(selected[0]));
        chip.setOnClickListener(v -> {
            if (publisher.name.equals(selected[0])) {
                selected[0] = "";
                chip.setChecked(false);
            } else {
                selected[0] = publisher.name;
                for (Chip other : chips) {
                    other.setChecked(other == chip);
                }
            }
        });
        chip.setOnLongClickListener(v -> {
            showOptions(activity, field, publisher, chip, selected, chips);
            return true;
        });
        return chip;
    }

    private static void showOptions(Activity activity, EditText field, Publisher publisher,
                                    Chip chip, String[] selected, List<Chip> chips) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("出版社「" + publisher.name + "」")
                .setItems(new String[]{"重命名", "删除"}, (d, which) -> {
                    if (which == 0) {
                        showRenameInput(activity, field, publisher, chip, selected);
                    } else {
                        confirmDelete(activity, field, publisher, chip, selected);
                    }
                })
                .show();
    }

    private static void showRenameInput(Activity activity, EditText field, Publisher publisher,
                                        Chip chip, String[] selected) {
        DialogInputBinding ib = DialogInputBinding.inflate(activity.getLayoutInflater());
        ib.edit.setText(publisher.name);
        ib.edit.setSelection(publisher.name.length());
        new MaterialAlertDialogBuilder(activity)
                .setTitle("重命名出版社")
                .setView(ib.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String newName = ib.edit.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(publisher.name)) {
                        return;
                    }
                    String oldName = publisher.name;
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        publisher.name = newName;
                        app.db().publisherDao().update(publisher);
                        for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                            if (oldName.equals(book.publisher)) {
                                book.publisher = newName;
                                app.db().bookDao().update(book);
                            }
                        }
                        app.ui(() -> {
                            chip.setText(newName);
                            if (selected[0].equals(oldName)) {
                                selected[0] = newName;
                            }
                            if (oldName.equals(field.getText().toString().trim())) {
                                field.setText(newName);
                            }
                            Toast.makeText(activity, "已重命名为「" + newName + "」",
                                    Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void confirmDelete(Activity activity, EditText field, Publisher publisher,
                                      Chip chip, String[] selected) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("删除出版社")
                .setMessage("从出版社库删除「" + publisher.name + "」？所有书籍上的这个出版社也会一并清空。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().publisherDao().deleteById(publisher.id);
                        for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                            if (publisher.name.equals(book.publisher)) {
                                book.publisher = "";
                                app.db().bookDao().update(book);
                            }
                        }
                        app.ui(() -> {
                            if (selected[0].equals(publisher.name)) {
                                selected[0] = "";
                            }
                            if (publisher.name.equals(field.getText().toString().trim())) {
                                field.setText("");
                            }
                            ViewGroup parent = (ViewGroup) chip.getParent();
                            if (parent != null) {
                                parent.removeView(chip);
                            }
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void showNewInput(Activity activity, EditText field, DialogTagsBinding db,
                                     String[] selected, List<Chip> chips) {
        DialogInputBinding ib = DialogInputBinding.inflate(activity.getLayoutInflater());
        ib.inputLayout.setHint("出版社名（如 人民文学出版社）");
        new MaterialAlertDialogBuilder(activity)
                .setTitle("新建出版社")
                .setView(ib.getRoot())
                .setPositiveButton("创建", (d, w) -> {
                    String name = ib.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        if (app.db().publisherDao().getByName(name) != null) {
                            app.ui(() -> Toast.makeText(activity, "该出版社已存在",
                                    Toast.LENGTH_SHORT).show());
                            return;
                        }
                        Publisher publisher = new Publisher();
                        publisher.name = name;
                        publisher.sort = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                        long id = app.db().publisherDao().insert(publisher);
                        publisher.id = id;
                        app.ui(() -> {
                            selected[0] = name;
                            Chip chip = buildChip(activity, field, publisher, selected, chips);
                            chips.add(chip);
                            for (Chip other : chips) {
                                other.setChecked(other == chip);
                            }
                            db.chipGroup.addView(chip, db.chipGroup.getChildCount() - 1);
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
