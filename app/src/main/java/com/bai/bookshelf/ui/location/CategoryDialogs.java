package com.bai.bookshelf.ui.location;

import android.widget.EditText;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.CategoryPropagator;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 分类管理的新建/重命名/删除弹窗（v5.6 从 CategoryActivity 抽出）：
 * 重名检查、重命名联动所有书籍、删除一级时级联删除其下二级。
 */
public class CategoryDialogs {

    private final BaseActivity activity;
    private final Supplier<List<Category>> all;
    private final Supplier<Set<Long>> expanded;
    private final Supplier<String> currentSelected;
    private final Consumer<String> setCurrentSelected;
    private final Runnable reload;

    public CategoryDialogs(BaseActivity activity,
                           Supplier<List<Category>> all,
                           Supplier<Set<Long>> expanded,
                           Supplier<String> currentSelected,
                           Consumer<String> setCurrentSelected,
                           Runnable reload) {
        this.activity = activity;
        this.all = all;
        this.expanded = expanded;
        this.currentSelected = currentSelected;
        this.setCurrentSelected = setCurrentSelected;
        this.reload = reload;
    }

    /**
     * @param editing     非空 = 重命名该分类；空 = 新建
     * @param parentOfNew 新建二级时传入所属一级；新建一级传 null（与 editing 互斥）
     */
    public void showInput(Category editing, Category parentOfNew) {
        DialogInputBinding db = DialogInputBinding.inflate(activity.getLayoutInflater());
        EditText edit = db.edit;
        boolean isRename = editing != null;
        if (isRename) {
            edit.setText(editing.name);
            edit.setSelection(editing.name.length());
        } else {
            db.inputLayout.setHint(parentOfNew == null
                    ? "一级分类名（如 文学）" : "二级分类名（如 小说）");
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(isRename ? "重命名分类"
                        : (parentOfNew == null ? "添加一级分类" : "添加二级分类"))
                .setView(db.getRoot())
                .setPositiveButton(isRename ? "确定" : "创建", (d, w) -> {
                    // 逗号会破坏标签等逗号分隔场景，与旧版保持一致的清洗规则
                    String name = edit.getText().toString().trim()
                            .replace(",", "").replace("，", "");
                    if (name.isEmpty()) {
                        Toast.makeText(activity, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        // 书籍按名字引用分类，任何层级内不允许重名
                        for (Category c : app.db().categoryDao().getAll()) {
                            boolean self = isRename && c.id == editing.id;
                            if (!self && c.name.equals(name)) {
                                app.ui(() -> Toast.makeText(activity,
                                        "「" + name + "」已存在，分类名不能重复",
                                        Toast.LENGTH_SHORT).show());
                                return;
                            }
                        }
                        if (isRename) {
                            String oldName = editing.name;
                            editing.name = name;
                            app.db().categoryDao().update(editing);
                            // 联动：所有书籍上的旧分类名同步换新
                            CategoryPropagator.renameCategoryEverywhere(app.db(), oldName, name);
                            if (name.equals(currentSelected.get())) {
                                setCurrentSelected.accept(name);
                            }
                        } else {
                            Category cat = new Category();
                            cat.name = name;
                            cat.sort = parentOfNew == null
                                    ? (int) (System.currentTimeMillis() % Integer.MAX_VALUE)
                                    : nextChildSort(parentOfNew.id);
                            cat.parentId = parentOfNew == null ? null : parentOfNew.id;
                            app.db().categoryDao().insert(cat);
                            if (parentOfNew != null) {
                                expanded.get().add(parentOfNew.id); // 新建的二级立刻可见
                            }
                        }
                        app.ui(reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 新建二级时的排序值：排在现有二级末尾 */
    private int nextChildSort(long parentId) {
        int max = 0;
        for (Category c : all.get()) {
            if (c.parentId != null && c.parentId == parentId && c.sort >= max) {
                max = c.sort + 1;
            }
        }
        return max;
    }

    /** 删除分类：一级连同其下所有二级一起删，书籍上的这些分类全部清空 */
    public void confirmDelete(Category cat, boolean isChild) {
        String msg = isChild
                ? "从分类库删除二级分类「" + cat.name + "」？所有书籍上的这个分类也会一并清空。"
                : "删除一级分类「" + cat.name + "」及其下所有二级分类？\n所有书籍上的这些分类都会一并清空。";
        new MaterialAlertDialogBuilder(activity)
                .setTitle("删除分类")
                .setMessage(msg)
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        if (isChild) {
                            app.db().categoryDao().deleteById(cat.id);
                            CategoryPropagator.removeCategoryEverywhere(app.db(), cat.name);
                        } else {
                            // 先删二级（联动清空书籍），再删一级本身
                            for (Category c : app.db().categoryDao().getAll()) {
                                if (c.parentId != null && c.parentId == cat.id) {
                                    app.db().categoryDao().deleteById(c.id);
                                    CategoryPropagator.removeCategoryEverywhere(app.db(), c.name);
                                }
                            }
                            app.db().categoryDao().deleteById(cat.id);
                            CategoryPropagator.removeCategoryEverywhere(app.db(), cat.name);
                        }
                        if (cat.name.equals(currentSelected.get())) {
                            setCurrentSelected.accept("");
                        }
                        app.ui(reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
