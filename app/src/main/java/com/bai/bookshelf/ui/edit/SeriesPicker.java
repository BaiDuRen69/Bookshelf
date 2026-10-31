package com.bai.bookshelf.ui.edit;

import android.app.Activity;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.DialogTagsBinding;
import com.bai.bookshelf.util.BookSeries;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 系列选择器（v5.1，从 BookEditActivity 抽出）：
 * 点选已有系列 / 新建系列 / 不设系列；选中且卷号为空时带出该系列下一卷。
 */
public final class SeriesPicker {

    private SeriesPicker() {
    }

    public static void show(Activity activity, EditText nameField,
                            EditText volumeField, EditText orderField) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Book> all = app.db().bookDao().getAll();
            Map<String, List<Book>> groups = BookSeries.group(all);
            app.ui(() -> {
                if (activity.isFinishing()) {
                    return;
                }
                showDialog(activity, nameField, volumeField, orderField, groups);
            });
        });
    }

    private static void showDialog(Activity activity, EditText nameField,
                                   EditText volumeField, EditText orderField,
                                   Map<String, List<Book>> groups) {
        DialogTagsBinding db = DialogTagsBinding.inflate(activity.getLayoutInflater());
        final String[] selected = {nameField.getText().toString().trim()};
        db.tvHint.setText("点按选中/取消；「＋ 新建系列」创建新系列。"
                + "系列名可在「我的 → 系列书架」里重命名");
        final Chip[] noneChip = {null};
        final List<Chip> chips = new ArrayList<>();
        Chip clearChip = new Chip(activity);
        clearChip.setText("不设系列");
        clearChip.setCheckable(true);
        clearChip.setChecked(selected[0].isEmpty());
        clearChip.setOnClickListener(v -> {
            selected[0] = "";
            clearChip.setChecked(true);
            for (Chip other : chips) {
                other.setChecked(false);
            }
        });
        noneChip[0] = clearChip;
        db.chipGroup.addView(clearChip);
        for (Map.Entry<String, List<Book>> entry : groups.entrySet()) {
            Chip chip = new Chip(activity);
            chip.setText(entry.getValue().size() > 1
                    ? entry.getKey() + "（" + entry.getValue().size() + "）" : entry.getKey());
            chip.setCheckable(true);
            chip.setChecked(entry.getKey().equals(selected[0]));
            chip.setOnClickListener(v -> {
                if (entry.getKey().equals(selected[0])) {
                    selected[0] = "";
                    chip.setChecked(false);
                    noneChip[0].setChecked(true);
                } else {
                    selected[0] = entry.getKey();
                    for (Chip other : chips) {
                        other.setChecked(other == chip);
                    }
                    noneChip[0].setChecked(false);
                }
            });
            chips.add(chip);
            db.chipGroup.addView(chip);
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("选择系列（单选）")
                .setView(db.getRoot())
                .setPositiveButton("完成", (d, w) -> applySelection(
                        activity, nameField, volumeField, orderField, selected[0], groups))
                .setNegativeButton(R.string.action_cancel, null)
                .show();

        Chip addChip = new Chip(activity);
        addChip.setText("＋ 新建系列");
        addChip.setOnClickListener(v -> showNewSeriesInput(
                activity, nameField, dialog));
        db.chipGroup.addView(addChip);
    }

    private static void applySelection(Activity activity, EditText nameField,
                                       EditText volumeField, EditText orderField,
                                       String name, Map<String, List<Book>> groups) {
        if (name.isEmpty()) {
            nameField.setText("");
            volumeField.setText("");
            orderField.setText("");
            return;
        }
        nameField.setText(name);
        if (volumeField.getText().toString().trim().isEmpty()) {
            String next = BookSeries.nextVolume(groups.get(name));
            if (!next.isEmpty()) {
                volumeField.setText(next);
                Toast.makeText(activity, "已带出下一卷 " + next + "，可修改",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static void showNewSeriesInput(Activity activity, EditText nameField,
                                           AlertDialog parent) {
        DialogInputBinding ib = DialogInputBinding.inflate(activity.getLayoutInflater());
        ib.inputLayout.setHint("系列名（如：三体、哈利·波特）");
        String current = nameField.getText().toString().trim();
        ib.edit.setText(current);
        ib.edit.setSelection(current.length());
        new MaterialAlertDialogBuilder(activity)
                .setTitle("新建系列")
                .setView(ib.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String name = ib.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(activity, "系列名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    nameField.setText(name);
                    parent.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
