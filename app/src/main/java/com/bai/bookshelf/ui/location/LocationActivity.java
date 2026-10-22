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
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.databinding.ActivityLocationBinding;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.ItemLocationBinding;

import java.util.ArrayList;
import java.util.List;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 存放地点管理：列表 + 添加 + 重命名 + 设为默认 + 删除。
 * 删除地点时，引用它的书籍会自动重置为「未设置」。
 */
public class LocationActivity extends BaseActivity {

    private ActivityLocationBinding b;
    private final List<Location> data = new ArrayList<>();
    private LocationAdapter adapter;
    private boolean batchMode = false; // 批量删除模式
    private final java.util.Set<Long> selectedIds = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityLocationBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new LocationAdapter();
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);

        b.btnAdd.setOnClickListener(v -> showNameDialog(null));
        // 批量删除模式：进入/取消/全选/删除
        b.btnBatch.setOnClickListener(v -> toggleBatchMode());
        b.btnSelCancel.setOnClickListener(v -> toggleBatchMode());
        b.btnSelAll.setOnClickListener(v -> {
            for (Location loc : data) {
                selectedIds.add(loc.id);
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

    /** 批量删除确认：引用这些地点的书籍重置为「未设置」 */
    private void confirmBatchDelete() {
        int count = selectedIds.size();
        if (count == 0) {
            Toast.makeText(this, "请先勾选要删除的地点", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("批量删除地点")
                .setMessage("删除已选的 " + count + " 个地点？放在这些地点的书会变为「未设置地点」。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    java.util.Set<Long> ids = new java.util.HashSet<>(selectedIds);
                    app.io(() -> {
                        for (Long id : ids) {
                            app.db().bookDao().clearLocation(id);
                            app.db().locationDao().deleteById(id);
                        }
                        app.ui(() -> {
                            toggleBatchMode();
                            Toast.makeText(this, "已删除 " + count + " 个地点", Toast.LENGTH_SHORT).show();
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
            List<Location> locs = app.db().locationDao().getAll();
            app.ui(() -> {
                data.clear();
                data.addAll(locs);
                adapter.notifyDataSetChanged();
            });
        });
    }

    /**
     * 添加/重命名共用的名称输入弹窗。
     * @param editing null=添加；否则为重命名该地点
     */
    private void showNameDialog(Location editing) {
        DialogInputBinding db = DialogInputBinding.inflate(getLayoutInflater());
        EditText edit = db.edit;
        db.inputLayout.setHint("如：书房-书架A-第2层");
        if (editing != null) {
            edit.setText(editing.name);
            edit.setSelection(editing.name.length()); // 光标移到末尾
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(editing == null ? "添加地点" : "重命名地点")
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
                            Location loc = new Location();
                            loc.name = name;
                            loc.sort = data.size();
                            app.db().locationDao().insert(loc);
                        } else {
                            editing.name = name;
                            app.db().locationDao().update(editing);
                        }
                        app.ui(this::reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 设为默认 / 取消默认（全表最多一个默认） */
    private void toggleDefault(Location loc) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            app.db().locationDao().clearDefault();
            if (!loc.isDefault) {
                app.db().locationDao().setDefault(loc.id);
            }
            app.ui(this::reload);
        });
    }

    /** 删除地点（带确认，引用书籍重置为未设置） */
    private void confirmDelete(Location loc) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除地点")
                .setMessage("删除「" + loc.name + "」？放在这里的书会变为「未设置地点」。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().bookDao().clearLocation(loc.id);
                        app.db().locationDao().deleteById(loc.id);
                        app.ui(this::reload);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 简单列表适配器（本页专用） */
    private class LocationAdapter extends RecyclerView.Adapter<LocationAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemLocationBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Location loc = data.get(position);
            h.b.tvName.setText(loc.name);
            // 批量模式：显示勾选框、隐藏操作按钮，整行点击切换选中
            h.b.cbSelect.setVisibility(batchMode ? View.VISIBLE : View.GONE);
            int btnVisibility = batchMode ? View.GONE : View.VISIBLE;
            h.b.btnStar.setVisibility(btnVisibility);
            h.b.btnEdit.setVisibility(btnVisibility);
            h.b.btnDelete.setVisibility(btnVisibility);
            h.b.cbSelect.setOnCheckedChangeListener(null);
            h.b.cbSelect.setChecked(selectedIds.contains(loc.id));
            h.b.cbSelect.setOnCheckedChangeListener((c, checked) -> {
                if (checked) {
                    selectedIds.add(loc.id);
                } else {
                    selectedIds.remove(loc.id);
                }
                b.tvSelCount.setText("已选 " + selectedIds.size() + " 项");
            });
            h.itemView.setOnClickListener(v -> {
                if (batchMode) {
                    h.b.cbSelect.setChecked(!h.b.cbSelect.isChecked());
                }
            });
            // 默认徽标与星标透明度区分当前默认项
            h.b.tvDefaultBadge.setVisibility(loc.isDefault ? View.VISIBLE : View.GONE);
            h.b.btnStar.setAlpha(loc.isDefault ? 1.0f : 0.3f);
            h.b.btnStar.setOnClickListener(v -> toggleDefault(loc));
            h.b.btnEdit.setOnClickListener(v -> showNameDialog(loc));
            h.b.btnDelete.setOnClickListener(v -> confirmDelete(loc));
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
