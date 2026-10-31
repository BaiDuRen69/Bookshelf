package com.bai.bookshelf.ui.edit;

import android.app.Activity;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.Consumer;

/**
 * 编辑页同页新增地点 / 来源选项（v5.7，从 BookEditActivity 抽出）。
 */
public final class AddOptionDialogs {

    private AddOptionDialogs() {
    }

    public static void showAddLocation(Activity activity, int nextSort, Consumer<Long> onAdded) {
        DialogInputBinding db = DialogInputBinding.inflate(activity.getLayoutInflater());
        db.inputLayout.setHint("如：书房-书架A-第2层");
        new MaterialAlertDialogBuilder(activity)
                .setTitle("添加地点")
                .setView(db.getRoot())
                .setPositiveButton("添加", (d, w) -> {
                    String name = db.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        Location location = new Location();
                        location.name = name;
                        location.sort = nextSort;
                        long id = app.db().locationDao().insert(location);
                        app.ui(() -> onAdded.accept(id));
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    public static void showAddChannel(Activity activity, int ownType, String categoryLabel,
                                      int nextSort, Consumer<String> onAdded) {
        String[] hints = {"拼多多、淘宝", "市图书馆", "鉴书团"};
        DialogInputBinding db = DialogInputBinding.inflate(activity.getLayoutInflater());
        db.inputLayout.setHint("如：" + hints[Math.max(0, Math.min(ownType, hints.length - 1))]);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("添加" + categoryLabel)
                .setView(db.getRoot())
                .setPositiveButton("添加", (d, w) -> {
                    String name = db.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        SourceChannel channel = new SourceChannel();
                        channel.name = name;
                        channel.ownType = ownType;
                        channel.sort = nextSort;
                        app.db().sourceChannelDao().insert(channel);
                        app.ui(() -> onAdded.accept(name));
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
