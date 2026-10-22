package com.bai.bookshelf.ui.location;

import androidx.appcompat.app.AlertDialog;
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
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.databinding.ActivityLocationBinding;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.ItemLocationBinding;

import java.util.ArrayList;
import java.util.List;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 来源渠道管理：用户可自主增删改（如在「自购」来源下细分 拼多多/淘宝/多抓鱼）。
 * 与地点管理页共用同一布局与列表项样式（星标按钮在本页隐藏）。
 * 注意：书籍保存的是渠道名称字符串，重命名渠道不会回写已录书籍上的旧名称。
 */
public class SourceChannelActivity extends BaseActivity {

    private ActivityLocationBinding b; // 复用地点页布局
    private final List<SourceChannel> data = new ArrayList<>();
    private ChannelAdapter adapter;
    private boolean batchMode = false; // 批量删除模式
    private final java.util.Set<Long> selectedIds = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityLocationBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setTitle("来源渠道管理");
        b.toolbar.setNavigationOnClickListener(v -> finish());
        b.btnAdd.setText("添加渠道");

        // 大类筛选（自购/借阅/赠书），选中哪个就给哪个分组加选项
        b.spFilter.setVisibility(View.VISIBLE);
        b.spFilter.setAdapter(android.widget.ArrayAdapter.createFromResource(this,
                R.array.own_type_entries, android.R.layout.simple_spinner_dropdown_item));
        b.spFilter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                reload();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // 编辑页「管理」入口可指定直接定位的大类
        int preset = getIntent().getIntExtra("ownType", -1);
        if (preset >= 0) {
            b.spFilter.setSelection(preset);
        }

        adapter = new ChannelAdapter();
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);

        b.btnAdd.setOnClickListener(v -> showNameDialog(null));
        // 批量删除模式：进入/取消/全选/删除
        b.btnBatch.setOnClickListener(v -> toggleBatchMode());
        b.btnSelCancel.setOnClickListener(v -> toggleBatchMode());
        b.btnSelAll.setOnClickListener(v -> {
            for (SourceChannel ch : data) {
                selectedIds.add(ch.id);
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

    /** 批量删除确认：已标记书籍上的渠道名快照不受影响 */
    private void confirmBatchDelete() {
        int count = selectedIds.size();
        if (count == 0) {
            Toast.makeText(this, "请先勾选要删除的渠道", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("批量删除渠道")
                .setMessage("删除已选的 " + count + " 个渠道？已标记为这些渠道的书籍不受影响（仍显示原名称）。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    java.util.Set<Long> ids = new java.util.HashSet<>(selectedIds);
                    app.io(() -> {
                        for (Long id : ids) {
                            app.db().sourceChannelDao().deleteById(id);
                        }
                        app.ui(() -> {
                            toggleBatchMode();
                            Toast.makeText(this, "已删除 " + count + " 个渠道", Toast.LENGTH_SHORT).show();
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

    /** 当前选中的来源大类（0自购/1借阅/2赠书） */
    private int currentType() {
        return b.spFilter.getSelectedItemPosition();
    }

    private void reload() {
        BookshelfApp app = BookshelfApp.get();
        int ownType = currentType();
        app.io(() -> {
            List<SourceChannel> list = app.db().sourceChannelDao().getByType(ownType);
            app.ui(() -> {
                data.clear();
                data.addAll(list);
                adapter.notifyDataSetChanged();
            });
        });
    }

    /** 添加/重命名共用的名称输入弹窗 */
    private void showNameDialog(SourceChannel editing) {
        DialogInputBinding db = DialogInputBinding.inflate(getLayoutInflater());
        EditText edit = db.edit;
        db.inputLayout.setHint("如：拼多多、淘宝、多抓鱼");
        if (editing != null) {
            edit.setText(editing.name);
            edit.setSelection(editing.name.length());
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(editing == null ? "添加渠道" : "重命名渠道")
                .setView(db.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String name = edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        if (editing == null) {
                            SourceChannel ch = new SourceChannel();
                            ch.name = name;
                            ch.ownType = currentType(); // 加到当前选中的大类分组
                            ch.sort = data.size();
                            app.db().sourceChannelDao().insert(ch);
                        } else {
                            editing.name = name;
                            app.db().sourceChannelDao().update(editing);
                        }
                        app.ui(this::reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmDelete(SourceChannel ch) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除渠道")
                .setMessage("删除「" + ch.name + "」？已标记为该渠道的书籍不受影响（仍显示原名称）。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().sourceChannelDao().deleteById(ch.id);
                        app.ui(this::reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 简单列表适配器（本页专用，星标隐藏） */
    private class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemLocationBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            SourceChannel ch = data.get(position);
            h.b.tvName.setText(ch.name);
            h.b.btnStar.setVisibility(View.GONE); // 渠道没有「默认」概念
            // 批量模式：显示勾选框、隐藏操作按钮，整行点击切换选中
            h.b.cbSelect.setVisibility(batchMode ? View.VISIBLE : View.GONE);
            int btnVisibility = batchMode ? View.GONE : View.VISIBLE;
            h.b.btnEdit.setVisibility(btnVisibility);
            h.b.btnDelete.setVisibility(btnVisibility);
            h.b.cbSelect.setOnCheckedChangeListener(null);
            h.b.cbSelect.setChecked(selectedIds.contains(ch.id));
            h.b.cbSelect.setOnCheckedChangeListener((c, checked) -> {
                if (checked) {
                    selectedIds.add(ch.id);
                } else {
                    selectedIds.remove(ch.id);
                }
                b.tvSelCount.setText("已选 " + selectedIds.size() + " 项");
            });
            h.itemView.setOnClickListener(v -> {
                if (batchMode) {
                    h.b.cbSelect.setChecked(!h.b.cbSelect.isChecked());
                }
            });
            h.b.btnEdit.setOnClickListener(v -> showNameDialog(ch));
            h.b.btnDelete.setOnClickListener(v -> confirmDelete(ch));
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
