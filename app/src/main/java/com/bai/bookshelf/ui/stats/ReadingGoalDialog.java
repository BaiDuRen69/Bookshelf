package com.bai.bookshelf.ui.stats;

import android.content.Context;
import android.text.InputType;
import android.view.LayoutInflater;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Calendar;

/**
 * 年度阅读目标弹窗（v5.7 从 StatsFragment 抽出，供统计页与新人引导共用）。
 */
public final class ReadingGoalDialog {

    private ReadingGoalDialog() {
    }

    public static void show(Context context, @Nullable Runnable onSaved) {
        DialogInputBinding input = DialogInputBinding.inflate(LayoutInflater.from(context));
        input.inputLayout.setHint("今年想读完几种？");
        input.edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        int year = Calendar.getInstance().get(Calendar.YEAR);
        int goal = context.getSharedPreferences("ui_prefs", 0)
                .getInt("reading_goal", 24);
        input.edit.setText(String.valueOf(goal));
        input.edit.setSelection(input.edit.getText().length());
        new MaterialAlertDialogBuilder(context)
                .setTitle("年度阅读目标")
                .setView(input.getRoot())
                .setPositiveButton("保存", (d, w) -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.edit.getText().toString().trim());
                    } catch (Exception e) {
                        value = goal;
                    }
                    if (value < 1) {
                        value = 1;
                    }
                    context.getSharedPreferences("ui_prefs", 0).edit()
                            .putInt("reading_goal_year", year)
                            .putInt("reading_goal", value).apply();
                    Toast.makeText(context, "今年目标 " + value + " 种", Toast.LENGTH_SHORT).show();
                    if (onSaved != null) {
                        onSaved.run();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
