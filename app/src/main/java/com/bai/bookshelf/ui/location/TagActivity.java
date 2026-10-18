package com.bai.bookshelf.ui.location;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bai.bookshelf.ui.BaseActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Tag;
import com.bai.bookshelf.databinding.ActivityLocationBinding;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.ItemLocationBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签管理：标签库的统一维护（重命名/删除/新增）。
 * 与地点/渠道管理页共用布局（大类筛选与星标在本页隐藏）。
 * 注意：书籍保存的是标签名，重命名/删除标签不会回写已录书籍。
 */
public class TagActivity extends BaseActivity {

    private ActivityLocationBinding b; // 复用地点页布局
    private final List<Tag> data = new ArrayList<>();
    private TagAdapter adapter;
    private boolean batchMode = false; // 批量删除模式
    private final java.util.Set<Long> selectedIds = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityLocationBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setTitle("标签管理");
        b.toolbar.setNavigationOnClickListener(v -> finish());
        b.btnAdd.setText("添加标签");
        // 本页不需要大类筛选
        b.spFilter.setVisibility(View.GONE);

        adapter = new TagAdapter();
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);

        b.btnAdd.setOnClickListener(v -> showNameDialog(null));
        // 批量删除模式：进入/取消/全选/删除
        b.btnBatch.setOnClickListener(v -> toggleBatchMode());
        b.btnSelCancel.setOnClickListener(v -> toggleBatchMode());
        b.btnSelAll.setOnClickListener(v -> {
            for (Tag tag : data) {
                selectedIds.add(tag.id);
            }
            refreshBatchUI();
        });
        b.btnSelDelete.setOnClickListener(v -> confirmBatchDelete());
    }

    /** 进入/退出批量删除模式 */
    private void toggleBatchMode() {
        batchMode = !batchMode;
        selectedIds.clear();
        refreshBatchUI();
    }

    /** 同步批量模式的界面状态（操作条、底部按钮、列表勾选框） */
    private void refreshBatchUI() {
        b.selectionBar.setVisibility(batchMode ? View.VISIBLE : View.GONE);
        b.bottomBar.setVisibility(batchMode ? View.GONE : View.VISIBLE);
        b.tvSelCount.setText("已选 " + selectedIds.size() + " 项");
        adapter.notifyDataSetChanged();
    }

    /** 批量删除确认：这些标签会从所有书籍上一并摘掉 */
    private void confirmBatchDelete() {
        int count = selectedIds.size();
        if (count == 0) {
            Toast.makeText(this, "请先勾选要删除的标签", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("批量删除标签")
                .setMessage("删除已选的 " + count + " 个标签？所有书籍上的这些标签也会一并移除。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    // 先按 id 收集标签名（联动要用），再逐个删除
                    java.util.List<Tag> targets = new ArrayList<>();
                    for (Tag tag : data) {
                        if (selectedIds.contains(tag.id)) {
                            targets.add(tag);
                        }
                    }
                    app.io(() -> {
                        for (Tag tag : targets) {
                            app.db().tagDao().deleteById(tag.id);
                            // 联动：把该标签从所有书籍上摘掉
                            com.bai.bookshelf.util.TagPropagator.removeTagEverywhere(app.db(), tag.name);
                        }
                        app.ui(() -> {
                            toggleBatchMode();
                            Toast.makeText(this, "已删除 " + count + " 个标签", Toast.LENGTH_SHORT).show();
                            reload();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Tag> list = app.db().tagDao().getAll();
            app.ui(() -> {
                data.clear();
                data.addAll(list);
                adapter.notifyDataSetChanged();
            });
        });
    }

    /** 添加/重命名共用的名称输入弹窗 */
    private void showNameDialog(Tag editing) {
        DialogInputBinding db = DialogInputBinding.inflate(getLayoutInflater());
        EditText edit = db.edit;
        edit.setHint("如：科幻、想读、镇架之宝");
        if (editing != null) {
            edit.setText(editing.name);
            edit.setSelection(editing.name.length());
        }
        new AlertDialog.Builder(this)
                .setTitle(editing == null ? "添加标签" : "重命名标签")
                .setView(db.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String name = edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (name.contains(",") || name.contains("，")) {
                        Toast.makeText(this, "标签名不能包含逗号", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        if (editing == null) {
                            Tag tag = new Tag();
                            tag.name = name;
                            tag.sort = data.size();
                            app.db().tagDao().insert(tag);
                        } else {
                            String oldName = editing.name;
                            editing.name = name;
                            app.db().tagDao().update(editing);
                            // 联动：所有书籍上的旧标签名同步换新
                            com.bai.bookshelf.util.TagPropagator.renameTagEverywhere(
                                    app.db(), oldName, name);
                        }
                        app.ui(this::reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmDelete(Tag tag) {
        new AlertDialog.Builder(this)
                .setTitle("删除标签")
                .setMessage("删除「" + tag.name + "」？所有书籍上的这个标签也会一并移除。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().tagDao().deleteById(tag.id);
                        // 联动：把该标签从所有书籍上摘掉
                        com.bai.bookshelf.util.TagPropagator.removeTagEverywhere(app.db(), tag.name);
                        app.ui(this::reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 简单列表适配器（本页专用，星标隐藏） */
    private class TagAdapter extends RecyclerView.Adapter<TagAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemLocationBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Tag tag = data.get(position);
            h.b.tvName.setText(tag.name);
            h.b.btnStar.setVisibility(View.GONE); // 标签没有「默认」概念
            // 批量模式：显示勾选框、隐藏操作按钮，整行点击切换选中
            h.b.cbSelect.setVisibility(batchMode ? View.VISIBLE : View.GONE);
            int btnVisibility = batchMode ? View.GONE : View.VISIBLE;
            h.b.btnEdit.setVisibility(btnVisibility);
            h.b.btnDelete.setVisibility(btnVisibility);
            h.b.cbSelect.setOnCheckedChangeListener(null);
            h.b.cbSelect.setChecked(selectedIds.contains(tag.id));
            h.b.cbSelect.setOnCheckedChangeListener((c, checked) -> {
                if (checked) {
                    selectedIds.add(tag.id);
                } else {
                    selectedIds.remove(tag.id);
                }
                b.tvSelCount.setText("已选 " + selectedIds.size() + " 项");
            });
            h.itemView.setOnClickListener(v -> {
                if (batchMode) {
                    h.b.cbSelect.setChecked(!h.b.cbSelect.isChecked());
                }
            });
            h.b.btnEdit.setOnClickListener(v -> showNameDialog(tag));
            h.b.btnDelete.setOnClickListener(v -> confirmDelete(tag));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ItemLocationBinding b;

            VH(ItemLocationBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }
}
