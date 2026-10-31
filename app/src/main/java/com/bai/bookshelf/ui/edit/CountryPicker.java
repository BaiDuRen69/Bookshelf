package com.bai.bookshelf.ui.edit;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Country;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.DialogTagsBinding;
import com.bai.bookshelf.util.BookSanitizer;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 国家/地区选择器（v5.7，从 BookEditActivity 抽出）：
 * 单选已有项 / 新建 / 长按重命名或删除；重命名与删除会同步全部书籍。
 */
public final class CountryPicker {

    private CountryPicker() {
    }

    public static void show(Activity activity, EditText field) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Country> all = app.db().countryDao().getAll();
            app.ui(() -> showDialog(activity, field, all));
        });
    }

    private static void showDialog(Activity activity, EditText field, List<Country> all) {
        DialogTagsBinding db = DialogTagsBinding.inflate(activity.getLayoutInflater());
        final String[] selected = {field.getText().toString().trim()};
        final List<Chip> chips = new ArrayList<>();
        for (Country country : all) {
            Chip chip = buildChip(activity, country, selected, chips);
            chips.add(chip);
            db.chipGroup.addView(chip);
        }
        Chip addChip = new Chip(activity);
        addChip.setText("＋ 新建国家/地区");
        addChip.setOnClickListener(v -> showNewInput(activity, db, selected, chips));
        db.chipGroup.addView(addChip);

        new MaterialAlertDialogBuilder(activity)
                .setTitle("选择国家/地区（单选）")
                .setView(db.getRoot())
                .setPositiveButton("完成", (d, w) -> field.setText(selected[0]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static Chip buildChip(Activity activity, Country country,
                                  String[] selected, List<Chip> chips) {
        Chip chip = new Chip(activity);
        chip.setText(country.name);
        chip.setCheckable(true);
        chip.setChecked(country.name.equals(selected[0]));
        chip.setOnClickListener(v -> {
            if (country.name.equals(selected[0])) {
                selected[0] = "";
                chip.setChecked(false);
            } else {
                selected[0] = country.name;
                for (Chip other : chips) {
                    other.setChecked(other == chip);
                }
            }
        });
        chip.setOnLongClickListener(v -> {
            showOptions(activity, country, chip, selected);
            return true;
        });
        return chip;
    }

    private static void showOptions(Activity activity, Country country,
                                    Chip chip, String[] selected) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("国家/地区「" + country.name + "」")
                .setItems(new String[]{"重命名", "删除"}, (d, which) -> {
                    if (which == 0) {
                        showRenameInput(activity, country, chip, selected);
                    } else {
                        confirmDelete(activity, country, chip, selected);
                    }
                })
                .show();
    }

    private static void showRenameInput(Activity activity, Country country,
                                        Chip chip, String[] selected) {
        DialogInputBinding ib = DialogInputBinding.inflate(activity.getLayoutInflater());
        ib.edit.setText(country.name);
        ib.edit.setSelection(country.name.length());
        new MaterialAlertDialogBuilder(activity)
                .setTitle("重命名国家/地区")
                .setView(ib.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String newName = ib.edit.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(country.name)) {
                        return;
                    }
                    String oldName = country.name;
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        country.name = newName;
                        app.db().countryDao().update(country);
                        for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                            if (oldName.equals(book.country)) {
                                book.country = newName;
                                app.db().bookDao().update(book);
                            }
                        }
                        app.ui(() -> {
                            chip.setText(newName);
                            if (selected[0].equals(oldName)) {
                                selected[0] = newName;
                            }
                            Toast.makeText(activity, "已重命名为「" + newName + "」",
                                    Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void confirmDelete(Activity activity, Country country,
                                      Chip chip, String[] selected) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("删除国家/地区")
                .setMessage("从国家库删除「" + country.name
                        + "」？所有书籍上的这个国家/地区也会一并清空。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().countryDao().deleteById(country.id);
                        for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                            if (country.name.equals(book.country)) {
                                book.country = "";
                                app.db().bookDao().update(book);
                            }
                        }
                        app.ui(() -> {
                            if (selected[0].equals(country.name)) {
                                selected[0] = "";
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

    private static void showNewInput(Activity activity, DialogTagsBinding db,
                                     String[] selected, List<Chip> chips) {
        DialogInputBinding ib = DialogInputBinding.inflate(activity.getLayoutInflater());
        ib.inputLayout.setHint("国家/地区名（如 中国 / 日本）");
        new MaterialAlertDialogBuilder(activity)
                .setTitle("新建国家/地区")
                .setView(ib.getRoot())
                .setPositiveButton("创建", (d, w) -> {
                    String name = ib.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        Country country = new Country();
                        country.name = name;
                        country.sort = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                        long id = app.db().countryDao().insert(country);
                        country.id = id;
                        app.ui(() -> {
                            selected[0] = name;
                            Chip chip = buildChip(activity, country, selected, chips);
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
