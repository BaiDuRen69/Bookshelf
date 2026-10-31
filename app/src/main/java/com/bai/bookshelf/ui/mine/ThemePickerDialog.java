package com.bai.bookshelf.ui.mine;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.GridLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.DialogThemePickerBinding;
import com.bai.bookshelf.databinding.ItemThemeSwatchBinding;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 主题选择弹窗（v5.6 从 MineFragment 抽出）：
 * 3 列 × 2 行色卡，当前项加主色描边 + 对勾；选中后持久化并淡入淡出重建页面。
 */
public class ThemePickerDialog {

    private final Fragment fragment;

    public ThemePickerDialog(Fragment fragment) {
        this.fragment = fragment;
    }

    public void show() {
        Context ctx = fragment.requireContext();
        String[] names = ThemeManager.names();
        int[] colors = ThemeManager.previewColors();
        int[] papers = ThemeManager.paperColors();
        int[] paperOutlines = ThemeManager.paperOutlineColors();
        // v4.19：主题 id 来自 SharedPreferences，若历史数据越界（如版本升级后
        // 主题数量变化）会直接取 names[current] 抛 ArrayIndexOutOfBoundsException，
        // 这里先夹到合法范围，避免一点就闪退。
        int current = ThemeManager.current(ctx);
        if (current < 0 || current >= names.length) {
            current = 0;
        }

        DialogThemePickerBinding db = DialogThemePickerBinding.inflate(
                fragment.getLayoutInflater());
        db.tvThemeCurrent.setText("当前：" + names[current]);

        final int selected = current;
        final int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            ItemThemeSwatchBinding cell = ItemThemeSwatchBinding.inflate(
                    fragment.getLayoutInflater(), db.gridThemes, false);
            cell.tvName.setText(names[i]);

            // v4.30：色卡 = 纸色圆 + 主色圆点，预览更接近真实换肤
            GradientDrawable paper = new GradientDrawable();
            paper.setShape(GradientDrawable.OVAL);
            paper.setColor(papers[i]);
            paper.setStroke(dp(1), paperOutlines[i]);
            if (idx == selected) {
                paper.setStroke(dp(2), primary);
            }
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(colors[i]);
            LayerDrawable swatch = new LayerDrawable(new android.graphics.drawable.Drawable[]{paper, dot});
            swatch.setLayerInset(1, dp(11), dp(11), dp(11), dp(11));
            cell.vSwatch.setBackground(swatch);
            cell.ivCheck.setVisibility(idx == selected ? View.VISIBLE : View.GONE);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(idx / 3),
                    GridLayout.spec(idx % 3, 1f));
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            cell.getRoot().setLayoutParams(lp);

            cell.getRoot().setOnClickListener(v -> {
                ThemeManager.set(ctx, idx);
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                // v4.30：不再弹 Toast（色卡已有勾选），用淡入淡出代替硬重建的闪屏
                fragment.requireActivity().recreate();
                fragment.requireActivity().overridePendingTransition(
                        android.R.anim.fade_in, android.R.anim.fade_out);
            });
            db.gridThemes.addView(cell.getRoot());
        }

        holder[0] = new MaterialAlertDialogBuilder(ctx)
                .setTitle("主题颜色")
                .setView(db.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private int dp(int value) {
        return (int) (value * fragment.getResources().getDisplayMetrics().density);
    }
}
