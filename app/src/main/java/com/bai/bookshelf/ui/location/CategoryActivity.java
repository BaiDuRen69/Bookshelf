package com.bai.bookshelf.ui.location;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.databinding.ActivityCategoryBinding;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.ItemCategoryBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.CategoryPropagator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 二级分类管理/选择页（v4.8）：一级/二级两级分类，操作逻辑与来源渠道管理一致。
 *
 * 两种用法（由 Intent extra 控制）：
 * - 选择模式（picker=true，编辑书籍页进入）：点二级完成选择并返回；长按一级可直接选用；
 *   底部「清除已选分类」把分类置空返回。
 * - 管理模式：浏览/增删改一、二级栏目（重命名/删除联动所有书籍，同旧版规则）。
 *
 * 选择结果存回书籍的是分类名字符串；因书籍按名字引用，任何层级内分类名全库唯一。
 */
public class CategoryActivity extends BaseActivity {

    /** 选择模式回传结果用的 extra 名 */
    public static final String EXTRA_SELECTED = "selected";

    private ActivityCategoryBinding b;
    private final List<Category> all = new ArrayList<>(); // 全部分类（含两级）
    private final List<Row> rows = new ArrayList<>();     // 展开状态摊平后的列表行
    private final Set<Long> expanded = new HashSet<>();   // 当前展开的一级分类 id
    private CategoryAdapter adapter;
    private boolean pickerMode;                           // 是否由编辑书籍页进入（选择模式）
    private String currentSelected = "";                  // 当前书籍已选分类名（高亮用）

    /** 摊平后的列表行：一级行或二级行 */
    private static class Row {
        Category cat;
        boolean isChild;
        int childCount; // 仅一级行使用
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityCategoryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());

        pickerMode = getIntent().getBooleanExtra("picker", false);
        currentSelected = getIntent().getStringExtra(EXTRA_SELECTED);
        if (currentSelected == null) {
            currentSelected = "";
        }
        // 选择模式才显示顶部提示与「清除已选分类」
        b.tvHint.setVisibility(pickerMode ? View.VISIBLE : View.GONE);
        b.btnCancelSelection.setVisibility(pickerMode ? View.VISIBLE : View.GONE);

        adapter = new CategoryAdapter();
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);

        b.btnAddTop.setOnClickListener(v -> showInputDialog(null, null));
        b.btnCancelSelection.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_SELECTED, ""));
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Category> list = app.db().categoryDao().getAll();
            app.ui(() -> {
                all.clear();
                all.addAll(list);
                rebuildRows();
            });
        });
    }

    /** 全量分类 → 摊平行列表（一级 + 已展开的一级下的二级） */
    private void rebuildRows() {
        rows.clear();
        List<Category> tops = new ArrayList<>();
        Map<Long, List<Category>> childrenMap = new HashMap<>();
        for (Category c : all) {
            if (c.parentId == null) {
                tops.add(c);
            } else {
                List<Category> list = childrenMap.get(c.parentId);
                if (list == null) {
                    list = new ArrayList<>();
                    childrenMap.put(c.parentId, list);
                }
                list.add(c);
            }
        }
        for (Category top : tops) {
            List<Category> children = childrenMap.get(top.id);
            int count = children == null ? 0 : children.size();
            Row row = new Row();
            row.cat = top;
            row.isChild = false;
            row.childCount = count;
            rows.add(row);
            if (expanded.contains(top.id) && children != null) {
                for (Category child : children) {
                    Row r = new Row();
                    r.cat = child;
                    r.isChild = true;
                    rows.add(r);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    // ======================= 增删改（与旧版联动规则一致） =======================

    /**
     * 添加/重命名共用的输入弹窗。
     *
     * @param editing     非空 = 重命名该分类；空 = 新建
     * @param parentOfNew 新建二级时传入所属一级；新建一级传 null（与 editing 互斥）
     */
    private void showInputDialog(Category editing, Category parentOfNew) {
        DialogInputBinding db = DialogInputBinding.inflate(getLayoutInflater());
        EditText edit = db.edit;
        boolean isRename = editing != null;
        if (isRename) {
            edit.setText(editing.name);
            edit.setSelection(editing.name.length());
        } else {
            edit.setHint(parentOfNew == null ? "一级分类名（如 文学）" : "二级分类名（如 小说）");
        }
        new AlertDialog.Builder(this)
                .setTitle(isRename ? "重命名分类" : (parentOfNew == null ? "添加一级分类" : "添加二级分类"))
                .setView(db.getRoot())
                .setPositiveButton(isRename ? "确定" : "创建", (d, w) -> {
                    // 逗号会破坏标签等逗号分隔场景，与旧版保持一致的清洗规则
                    String name = edit.getText().toString().trim()
                            .replace(",", "").replace("，", "");
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        // 书籍按名字引用分类，任何层级内不允许重名
                        for (Category c : app.db().categoryDao().getAll()) {
                            boolean self = isRename && c.id == editing.id;
                            if (!self && c.name.equals(name)) {
                                app.ui(() -> Toast.makeText(this,
                                        "「" + name + "」已存在，分类名不能重复", Toast.LENGTH_SHORT).show());
                                return;
                            }
                        }
                        if (isRename) {
                            String oldName = editing.name;
                            editing.name = name;
                            app.db().categoryDao().update(editing);
                            // 联动：所有书籍上的旧分类名同步换新
                            CategoryPropagator.renameCategoryEverywhere(app.db(), oldName, name);
                            if (name.equals(currentSelected)) {
                                currentSelected = name;
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
                                expanded.add(parentOfNew.id); // 新建的二级立刻可见
                            }
                        }
                        app.ui(this::reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 新建二级时的排序值：排在现有二级末尾 */
    private int nextChildSort(long parentId) {
        int max = 0;
        for (Category c : all) {
            if (c.parentId != null && c.parentId == parentId && c.sort >= max) {
                max = c.sort + 1;
            }
        }
        return max;
    }

    /** 删除分类：一级连同其下所有二级一起删，书籍上的这些分类全部清空 */
    private void confirmDelete(Category cat, boolean isChild) {
        String msg = isChild
                ? "从分类库删除二级分类「" + cat.name + "」？所有书籍上的这个分类也会一并清空。"
                : "删除一级分类「" + cat.name + "」及其下所有二级分类？\n所有书籍上的这些分类都会一并清空。";
        new AlertDialog.Builder(this)
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
                        if (cat.name.equals(currentSelected)) {
                            currentSelected = "";
                        }
                        app.ui(this::reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 选择模式：把分类名回传给编辑书籍页 */
    private void returnSelection(String name) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_SELECTED, name));
        finish();
    }

    // ======================= 列表适配器 =======================

    private class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemCategoryBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Row row = rows.get(position);
            Category cat = row.cat;

            h.b.tvName.setText(cat.name);
            h.b.tvName.setTypeface(null, row.isChild
                    ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);

            // 一级：副标题显示二级数量 / 无二级提示；二级：当前书籍已选中的显示「当前选择」
            if (row.isChild) {
                boolean selectedNow = pickerMode && cat.name.equals(currentSelected);
                h.b.tvMeta.setVisibility(selectedNow ? View.VISIBLE : View.GONE);
                h.b.tvMeta.setText("当前选择");
            } else {
                boolean hasChildren = row.childCount > 0;
                boolean expandedNow = expanded.contains(cat.id);
                h.b.tvMeta.setVisibility(View.VISIBLE);
                h.b.tvMeta.setText(hasChildren
                        ? (expandedNow ? "共 " + row.childCount + " 个二级栏目（点击收起）"
                                       : "共 " + row.childCount + " 个二级栏目（点击展开）")
                        : "暂无二级栏目（点 ＋ 添加）");
            }

            // 一级行显示箭头（展开时旋转 90°）与「＋添加二级」；二级行隐藏这两个
            h.b.ivArrow.setVisibility(row.isChild ? View.INVISIBLE : View.VISIBLE);
            h.b.ivArrow.animate().rotation(expanded.contains(cat.id) ? 90f : 0f).setDuration(120).start();
            h.b.btnAddChild.setVisibility(row.isChild ? View.GONE : View.VISIBLE);

            // 二级缩进展示层级
            h.itemView.setPadding(row.isChild ? dp(30) : dp(10),
                    dp(10), dp(10), dp(10));

            // 当前已选分类的二级整行高亮底色
            boolean highlight = pickerMode && row.isChild && cat.name.equals(currentSelected);
            h.itemView.setBackgroundResource(highlight
                    ? R.drawable.bg_category_selected : R.drawable.bg_chip);

            // 整行点击：一级展开/收起（无二级且在选择模式 → 直接选用该一级）；二级完成选择
            h.itemView.setOnClickListener(v -> {
                if (row.isChild) {
                    if (pickerMode) {
                        returnSelection(cat.name);
                    }
                    return;
                }
                if (row.childCount > 0) {
                    if (!expanded.remove(cat.id)) {
                        expanded.add(cat.id);
                    }
                    rebuildRows();
                } else if (pickerMode) {
                    // 老数据的平铺分类：没有二级，点击可直接选用该一级名
                    returnSelection(cat.name);
                } else {
                    Toast.makeText(CategoryActivity.this,
                            "该分类下暂无二级栏目，点 ＋ 添加", Toast.LENGTH_SHORT).show();
                }
            });

            // 长按一级（选择模式）：直接选用该一级分类（兼容老平铺分类的用法）
            h.itemView.setOnLongClickListener(v -> {
                if (!row.isChild && pickerMode) {
                    returnSelection(cat.name);
                    return true;
                }
                return false;
            });

            h.b.btnAddChild.setOnClickListener(v -> showInputDialog(null, cat));
            h.b.btnEdit.setOnClickListener(v -> showInputDialog(cat, null));
            h.b.btnDelete.setOnClickListener(v -> confirmDelete(cat, row.isChild));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ItemCategoryBinding b;

            VH(ItemCategoryBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
