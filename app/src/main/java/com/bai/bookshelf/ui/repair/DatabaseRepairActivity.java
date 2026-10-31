package com.bai.bookshelf.ui.repair;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.databinding.ActivityDatabaseRepairBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.MainActivity;
import com.bai.bookshelf.util.DbRepair;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 数据库修复页（v5.8）：启动时打开数据库失败后由 {@link BookshelfApp} 拉起。
 *
 * <p>页面本身不碰数据库查询，只用文件操作，所以坏库状态下也能正常显示。
 */
public class DatabaseRepairActivity extends BaseActivity {

    private ActivityDatabaseRepairBinding b;

    private final ActivityResultLauncher<String[]> pickZip = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    confirmZip(uri);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityDatabaseRepairBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finishAffinity());
        b.tvError.setText(DbRepair.message());
        b.btnRestoreCopy.setOnClickListener(v -> confirmCopy());
        b.btnRestoreZip.setOnClickListener(v -> pickZip.launch(new String[]{"*/*"}));
        b.btnRetry.setOnClickListener(v -> retryOpen());
        bindCopyState();
    }

    private void bindCopyState() {
        String label = DbRepair.latestCopyLabel(this);
        StringBuilder sb = new StringBuilder(label == null
                ? "没有找到升级前副本（只有数据库版本变化时才会自动留一份）。"
                : "最近一份副本：" + label
                + "\n恢复后数据库会回到升级前的状态，再重新尝试升级一次。");
        if (DbRepair.isPending(this)) {
            String reason = DbRepair.pendingReason(this);
            sb.append("\n\n上次恢复没有走完：")
                    .append(reason.isEmpty() ? "进程在换文件时被中断" : reason);
        }
        java.io.File broken = DbRepair.lastBrokenFile(this);
        if (broken != null) {
            sb.append("\n被换下来的原库已保留：").append(broken.getName())
                    .append("（可在「我的 → 备份文件管理」里导出）");
        }
        b.tvCopyState.setText(sb);
        b.btnRestoreCopy.setEnabled(label != null);
    }

    private void confirmCopy() {
        String label = DbRepair.latestCopyLabel(this);
        if (label == null) {
            Toast.makeText(this, "没有可用的升级前副本", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("用升级前副本恢复？")
                .setMessage("副本：" + label
                        + "\n\n会把数据库写回升级前的状态，然后重新尝试升级。"
                        + "如果升级本身有问题，会再次回到这一页。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (d, w) -> doRestoreCopy())
                .show();
    }

    private void doRestoreCopy() {
        final BookshelfApp app = BookshelfApp.get();
        b.btnRestoreCopy.setEnabled(false);
        Toast.makeText(this, "正在恢复…", Toast.LENGTH_SHORT).show();
        app.bg(() -> {
            try {
                DbRepair.restoreFromPreMigration(this);
                app.ui(() -> {
                    Toast.makeText(this, "已恢复到升级前，正在重新打开…", Toast.LENGTH_LONG).show();
                    goMain();
                });
            } catch (Throwable t) {
                String msg = DbRepair.describe(t);
                app.ui(() -> {
                    b.btnRestoreCopy.setEnabled(true);
                    b.tvError.setText(msg);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("副本也没能恢复")
                            .setMessage(msg + "\n\n建议改用下面的「从 zip 备份恢复」。")
                            .setPositiveButton("知道了", null)
                            .show();
                });
            }
        });
    }

    private void confirmZip(Uri uri) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("用这个备份覆盖当前数据？")
                .setMessage("会放弃现在的坏库（改名留在应用里，不删），"
                        + "再按备份包重建一个全新的数据库。当前没备份进去的改动会丢。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (d, w) -> doRestoreZip(uri))
                .show();
    }

    private void doRestoreZip(Uri uri) {
        final BookshelfApp app = BookshelfApp.get();
        b.btnRestoreZip.setEnabled(false);
        Toast.makeText(this, "正在恢复…", Toast.LENGTH_SHORT).show();
        app.bg(() -> {
            try {
                int books = DbRepair.restoreFromZip(this, uri);
                app.ui(() -> {
                    Toast.makeText(this, "已从备份恢复 " + books + " 种", Toast.LENGTH_LONG).show();
                    goMain();
                });
            } catch (Throwable t) {
                String msg = DbRepair.describe(t);
                app.ui(() -> {
                    b.btnRestoreZip.setEnabled(true);
                    b.tvError.setText(msg);
                    Toast.makeText(this, "恢复失败：" + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void retryOpen() {
        final BookshelfApp app = BookshelfApp.get();
        b.btnRetry.setEnabled(false);
        app.bg(() -> {
            try {
                app.closeDatabase();
                app.reopenDatabase();
                app.db().getOpenHelper().getWritableDatabase();
                DbRepair.clear(this);
                app.ui(this::goMain);
            } catch (Throwable t) {
                DbRepair.markBroken(t);
                String msg = DbRepair.message();
                app.ui(() -> {
                    b.btnRetry.setEnabled(true);
                    b.tvError.setText(msg);
                    Toast.makeText(this, "还是打不开", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** 库修好之后重新进主界面：清掉原来的任务栈，让所有页面按新库重新加载 */
    private void goMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
