package com.bai.bookshelf.ui.edit;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Tag;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.DialogTagsBinding;
import com.bai.bookshelf.util.TagPropagator;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 微信式标签选择器（v5.7，从 BookEditActivity 抽出）：
 * 勾选/取消、新建、长按重命名或删除；标签库变更会同步全部书籍。
 */
public final class TagPicker {

    private TagPicker() {
    }

    public static void show(Activity activity, Book book, EditText tagsField) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Tag> all = app.db().tagDao().getAll();
            app.ui(() -> showDialog(activity, book, tagsField, all));
        });
    }

    private static void showDialog(Activity activity, Book book, EditText tagsField,
                                   List<Tag> all) {
        DialogTagsBinding db = DialogTagsBinding.inflate(activity.getLayoutInflater());
        final Set<String> selected = new LinkedHashSet<>();
        for (String raw : book.tags.split(",")) {
            String name = raw.trim();
            if (!name.isEmpty()) {
                selected.add(name);
            }
        }
        for (Tag tag : all) {
            db.chipGroup.addView(buildChip(activity, tag, selected));
        }
        Chip addChip = new Chip(activity);
        addChip.setText("＋ 新建标签");
        addChip.setOnClickListener(v -> showNewInput(activity, db, selected));
        db.chipGroup.addView(addChip);

        new MaterialAlertDialogBuilder(activity)
                .setTitle("选择标签")
                .setView(db.getRoot())
                .setPositiveButton("完成", (d, w) -> {
                    book.tags = android.text.TextUtils.join(",", selected);
                    tagsField.setText(android.text.TextUtils.join(", ", selected));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static Chip buildChip(Activity activity, Tag tag, Set<String> selected) {
        Chip chip = new Chip(activity);
        chip.setText(tag.name);
        chip.setCheckable(true);
        chip.setChecked(selected.contains(tag.name));
        chip.setOnCheckedChangeListener((c, isChecked) -> {
            String name = c.getText().toString();
            if (isChecked) {
                selected.add(name);
            } else {
                selected.remove(name);
            }
        });
        chip.setOnLongClickListener(v -> {
            showOptions(activity, tag, chip, selected);
            return true;
        });
        return chip;
    }

    private static void showOptions(Activity activity, Tag tag, Chip chip, Set<String> selected) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("标签「" + tag.name + "」")
                .setItems(new String[]{"重命名", "删除"}, (d, which) -> {
                    if (which == 0) {
                        showRenameInput(activity, tag, chip, selected);
                    } else {
                        confirmDelete(activity, tag, chip, selected);
                    }
                })
                .show();
    }

    private static void showRenameInput(Activity activity, Tag tag, Chip chip,
                                        Set<String> selected) {
        DialogInputBinding ib = DialogInputBinding.inflate(activity.getLayoutInflater());
        ib.edit.setText(tag.name);
        ib.edit.setSelection(tag.name.length());
        new MaterialAlertDialogBuilder(activity)
                .setTitle("重命名标签")
                .setView(ib.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String newName = ib.edit.getText().toString().trim()
                            .replace(",", "").replace("，", "");
                    if (newName.isEmpty() || newName.equals(tag.name)) {
                        return;
                    }
                    String oldName = tag.name;
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        tag.name = newName;
                        app.db().tagDao().update(tag);
                        TagPropagator.renameTagEverywhere(app.db(), oldName, newName);
                        app.ui(() -> {
                            chip.setText(newName);
                            if (selected.remove(oldName)) {
                                selected.add(newName);
                            }
                            Toast.makeText(activity, "已重命名为「" + newName + "」",
                                    Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void confirmDelete(Activity activity, Tag tag, Chip chip,
                                      Set<String> selected) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("删除标签")
                .setMessage("从标签库删除「" + tag.name + "」？所有书籍上的这个标签也会一并移除。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().tagDao().deleteById(tag.id);
                        TagPropagator.removeTagEverywhere(app.db(), tag.name);
                        app.ui(() -> {
                            selected.remove(tag.name);
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

    private static void showNewInput(Activity activity, DialogTagsBinding db,
                                     Set<String> selected) {
        DialogInputBinding ib = DialogInputBinding.inflate(activity.getLayoutInflater());
        ib.inputLayout.setHint("标签名（不能含逗号）");
        new MaterialAlertDialogBuilder(activity)
                .setTitle("新建标签")
                .setView(ib.getRoot())
                .setPositiveButton("创建", (d, w) -> {
                    String name = ib.edit.getText().toString().trim()
                            .replace(",", "").replace("，", "");
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        Tag tag = new Tag();
                        tag.name = name;
                        tag.sort = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                        long id = app.db().tagDao().insert(tag);
                        tag.id = id;
                        app.ui(() -> {
                            selected.add(name);
                            db.chipGroup.addView(buildChip(activity, tag, selected),
                                    db.chipGroup.getChildCount() - 1);
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
