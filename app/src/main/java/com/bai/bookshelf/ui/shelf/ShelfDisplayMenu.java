package com.bai.bookshelf.ui.shelf;

import android.app.Activity;

import com.bai.bookshelf.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.IntConsumer;

/**
 * 书架排序 / 视图菜单（v5.6，从 ShelfFragment 抽出）：
 * 只负责弹窗与选择，状态更新交给调用方。
 */
public final class ShelfDisplayMenu {

    private ShelfDisplayMenu() {
    }

    public static void showSort(Activity activity, int current, IntConsumer onPick) {
        String[] names = activity.getResources().getStringArray(R.array.sort_entries);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("排序方式")
                .setSingleChoiceItems(names, Math.max(0, Math.min(current, names.length - 1)),
                        (dialog, which) -> {
                            dialog.dismiss();
                            onPick.accept(which);
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public static void showView(Activity activity, int current, IntConsumer onPick) {
        String[] views = {"列表", "封面", "文字"};
        new MaterialAlertDialogBuilder(activity)
                .setTitle("视图")
                .setSingleChoiceItems(views, Math.max(0, Math.min(current, views.length - 1)),
                        (dialog, which) -> {
                            dialog.dismiss();
                            onPick.accept(which);
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
