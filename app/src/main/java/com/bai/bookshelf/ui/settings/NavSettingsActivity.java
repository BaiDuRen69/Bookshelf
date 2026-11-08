package com.bai.bookshelf.ui.settings;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.ActivityNavSettingsBinding;
import com.bai.bookshelf.databinding.ItemNavOrderRowBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.NavOrder;
import com.bai.bookshelf.util.NavPrefs;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 「底栏与启动页」设置（v5.11）：调顺序、隐藏不用的 Tab、选启动页。
 *
 * <p>「我的」是应用内唯一的设置入口，永远不能隐藏：开关置灰 + NavOrder 读取时剔除，两层防线。
 * 每次操作立即写 NavPrefs 并重建列表，取消勾选不需要额外的保存按钮。
 */
public class NavSettingsActivity extends BaseActivity {

    private ActivityNavSettingsBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityNavSettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        b.rowStartTab.setOnClickListener(v -> pickStartTab());
        b.btnReset.setOnClickListener(v -> {
            NavPrefs.reset(this);
            rebuild();
            toast("已恢复默认");
        });
        rebuild();
    }

    private void rebuild() {
        b.navRows.removeAllViews();
        int[] order = NavPrefs.order(this);
        Set<Integer> hidden = NavPrefs.hidden(this);
        for (int i = 0; i < order.length; i++) {
            final int position = i;
            final int tab = order[i];
            ItemNavOrderRowBinding row = ItemNavOrderRowBinding.inflate(
                    getLayoutInflater(), b.navRows, false);
            row.ivTabIcon.setImageResource(iconOf(tab));
            row.tvTabName.setText(NavOrder.label(tab));
            final boolean pinned = tab == NavOrder.TAB_MINE;
            row.tvTabHint.setVisibility(pinned ? View.VISIBLE : View.GONE);
            row.swTabVisible.setChecked(NavOrder.visible(tab, hidden));
            row.swTabVisible.setEnabled(!pinned);
            row.swTabVisible.setOnCheckedChangeListener((btn, checked) -> {
                if (pinned) {
                    return;
                }
                int startBefore = NavPrefs.startTab(this);
                Set<Integer> now = NavPrefs.hidden(this);
                if (checked) {
                    now.remove(tab);
                } else {
                    now.add(tab);
                }
                NavPrefs.setHidden(this, now);
                // 隐藏了当前启动页：把 KEY_START 真正改写，而不是只在读取时兜底
                if (!checked && tab == startBefore) {
                    int fallback = NavOrder.firstVisible(NavPrefs.order(this), now);
                    NavPrefs.setStartTab(this, fallback);
                    toast("启动页已改为 " + NavOrder.label(fallback));
                }
                rebuild();
            });

            row.btnUp.setEnabled(position > 0);
            row.btnDown.setEnabled(position < order.length - 1);
            row.btnUp.setAlpha(position > 0 ? 1f : 0.3f);
            row.btnDown.setAlpha(position < order.length - 1 ? 1f : 0.3f);
            row.btnUp.setOnClickListener(v -> move(position, -1));
            row.btnDown.setOnClickListener(v -> move(position, 1));

            row.getRoot().setContentDescription(NavOrder.label(tab)
                    + (pinned ? "，始终显示"
                    : (row.swTabVisible.isChecked() ? "，已显示" : "，已隐藏")));
            b.navRows.addView(row.getRoot());
        }
        b.tvStartTab.setText(NavOrder.label(NavPrefs.startTab(this)));
    }

    private void move(int position, int delta) {
        int[] order = NavPrefs.order(this);
        int target = position + delta;
        if (target < 0 || target >= order.length) {
            return;
        }
        int tmp = order[position];
        order[position] = order[target];
        order[target] = tmp;
        NavPrefs.setOrder(this, order);
        rebuild();
    }

    private void pickStartTab() {
        int[] order = NavPrefs.order(this);
        Set<Integer> hidden = NavPrefs.hidden(this);
        List<Integer> visible = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int tab : order) {
            if (NavOrder.visible(tab, hidden)) {
                visible.add(tab);
                labels.add(NavOrder.label(tab));
            }
        }
        final int checked = Math.max(0, visible.indexOf(NavPrefs.startTab(this)));
        new MaterialAlertDialogBuilder(this)
                .setTitle("启动页")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    NavPrefs.setStartTab(this, visible.get(which));
                    b.tvStartTab.setText(NavOrder.label(visible.get(which)));
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static int iconOf(int tab) {
        switch (tab) {
            case NavOrder.TAB_STATS:
                return R.drawable.ic_chart_line;
            case NavOrder.TAB_CHECKIN:
                return R.drawable.ic_checkin_line;
            case NavOrder.TAB_MINE:
                return R.drawable.ic_person_line;
            case NavOrder.TAB_SHELF:
            default:
                return R.drawable.ic_book_line;
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
