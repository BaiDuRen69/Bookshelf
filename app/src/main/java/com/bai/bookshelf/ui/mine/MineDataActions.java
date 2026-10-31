package com.bai.bookshelf.ui.mine;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.databinding.FragmentMineBinding;
import com.bai.bookshelf.util.BackupInspector;
import com.bai.bookshelf.util.BackupManager;
import com.bai.bookshelf.util.ExcelImporter;
import com.bai.bookshelf.util.ZipRestore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「我的」页的数据与备份（v5.6 从 MineFragment 抽出）：
 * 自动备份开关、备份文件夹、手动备份、备份文件的分享/删除，
 * 以及 xlsx 合并导入与 zip 整包恢复。
 */
public class MineDataActions {

    private final Fragment fragment;
    /** 视图绑定（onCreateView 后由页面挂上；视图销毁时可置空） */
    private FragmentMineBinding b;

    /** 选择 xlsx/zip 文件后的导入入口（在 onCreate 中注册） */
    private final ActivityResultLauncher<String[]> pickImportLauncher;
    /** 选择备份文件夹（SAF） */
    private final ActivityResultLauncher<Uri> backupTreeLauncher;

    public MineDataActions(Fragment fragment) {
        this.fragment = fragment;
        this.pickImportLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null && fragment.isAdded()) {
                        importFile(uri);
                    }
                });
        this.backupTreeLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    fragment.requireContext().getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    BackupManager.setTree(fragment.requireContext(), uri, uri.getLastPathSegment());
                    refreshRows();
                    Toast.makeText(fragment.requireContext(),
                            "备份文件夹已设置（自动备份将在此保留一份副本）",
                            Toast.LENGTH_SHORT).show();
                });
    }

    /** 视图就绪后挂上绑定，供状态行刷新使用 */
    public void attach(FragmentMineBinding b) {
        this.b = b;
    }

    /** 选择备份文件夹 */
    public void pickBackupFolder() {
        backupTreeLauncher.launch(null);
    }

    /** 开关自动备份（默认存应用内部存储，SAF 文件夹只是额外副本） */
    public void toggleAutoBackup() {
        boolean nowEnabled = !BackupManager.isEnabled(fragment.requireContext());
        BackupManager.setEnabled(fragment.requireContext(), nowEnabled);
        refreshRows();
        Toast.makeText(fragment.requireContext(),
                nowEnabled ? "已开启：每次打开 App 时备份（每天最多一次）"
                        : "已关闭自动备份",
                Toast.LENGTH_SHORT).show();
    }

    /** 刷新自动备份/备份文件夹/备份文件三行的状态文案 */
    public void refreshRows() {
        if (b == null) {
            return;
        }
        Context ctx = fragment.requireContext();
        b.tvAutoBackupState.setText(BackupManager.isEnabled(ctx)
                ? "已开启（每天最多一次）" : "已关闭");
        String dirName = BackupManager.getTreeName(ctx);
        String dirState;
        if (dirName.isEmpty()) {
            dirState = "未选择";
        } else if (BackupManager.safBroken(ctx)) {
            // 设了文件夹却写不进去：必须说出来，否则用户以为外部副本一直在更新
            long failAt = BackupManager.safLastFailAt(ctx);
            dirState = "外部副本自 " + friendlyTime(failAt) + " 起未更新，点此重新授权";
        } else if (BackupManager.safLastOkAt(ctx) > 0) {
            dirState = dirName + " · 外部副本 " + friendlyTime(BackupManager.safLastOkAt(ctx));
        } else {
            dirState = dirName;
        }
        b.tvBackupDirName.setText(dirState);
        boolean stale = BackupManager.staleReminderNeeded(ctx);
        b.rowBackupReminder.setVisibility(stale ? View.VISIBLE : View.GONE);
        b.dividerBackupReminder.setVisibility(stale ? View.VISIBLE : View.GONE);
        if (stale) {
            b.tvBackupReminderState.setText("最新备份是 "
                    + BackupManager.latestBackupAgeDays(ctx) + " 天前，点此立即备份");
        }
        // v4.45：备份区直接给「上次备份时间 · 大小 · 份数」，让用户对备份有底
        // v5.7：能读出 meta 就顺带告诉用户那份备份里有几本、是哪个版本生成的
        List<File> files = BackupManager.listInternalBackups(ctx);
        if (files.isEmpty()) {
            b.tvBackupFilesState.setText("还没有备份（点此立即备份）");
        } else {
            File latest = files.get(files.size() - 1);
            String detail = BackupManager.describeBackup(latest);
            b.tvBackupFilesState.setText("上次 " + friendlyTime(latest.lastModified())
                    + " · " + humanSize(latest.length()) + " · 共 " + files.size() + " 份"
                    + (detail == null ? "" : "\n" + detail + " · 点此查看/验证"));
        }
    }

    /** 关掉「备份太久没更新」提醒（30 天内不再提示） */
    public void snoozeBackupReminder() {
        BackupManager.snoozeStaleReminder(fragment.requireContext());
        refreshRows();
    }

    /** 备份时间的人话展示：今天/昨天 时:分，更早则 月日 时:分 */
    private static String friendlyTime(long time) {
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(time);
        Calendar now = Calendar.getInstance();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        boolean today = target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        if (today) {
            return "今天 " + hm.format(target.getTime());
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        boolean yesterday = target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        SimpleDateFormat md = new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault());
        return (yesterday ? "昨天 " : "") + (yesterday ? "" : md.format(target.getTime()));
    }

    /** 文件大小自适应单位（<1MB 用 KB，否则用 MB） */
    private static String humanSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024f);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f);
    }

    /** 立即备份（v4.45）：后台生成 zip，完成后刷新状态并提示大小 */
    public void manualBackup() {
        final Context appCtx = fragment.requireContext().getApplicationContext();
        Toast.makeText(appCtx, "正在备份…", Toast.LENGTH_SHORT).show();
        BookshelfApp.get().bg(() -> {
            BackupManager.Outcome o = BackupManager.backupNow(appCtx);
            BookshelfApp.get().ui(() -> {
                if (!fragment.isAdded()) {
                    return;
                }
                refreshRows();
                String msg;
                if (o.meta != null) {
                    msg = "备份完成：" + o.meta.books + " 种 · " + humanSize(o.bytes);
                    if (o.safFailed) {
                        msg += "\n外部副本没写进去（" + o.safError + "），点备份文件夹行重新授权";
                    }
                } else if (o.empty) {
                    msg = "还没有藏书，无需备份";
                } else {
                    msg = "备份失败，请稍后再试";
                }
                Toast.makeText(fragment.requireContext(), msg, Toast.LENGTH_LONG).show();
            });
        });
    }

    /**
     * 验证备份（v5.7 数据安全）：把内部存储里每一份 zip 整包读一遍，
     * 比对 meta 里的 SHA-256 校验和，并列出各份的版本与册数。
     * 整包解压耗时以秒计，走 bg 线程。
     */
    public void verifyBackups() {
        final Context appCtx = fragment.requireContext().getApplicationContext();
        Toast.makeText(appCtx, "正在校验备份…", Toast.LENGTH_SHORT).show();
        BookshelfApp.get().bg(() -> {
            List<BackupManager.Verified> list = BackupManager.verifyInternal(appCtx);
            int copies = com.bai.bookshelf.util.PreMigrationBackup.count(appCtx);
            BookshelfApp.get().ui(() -> {
                if (!fragment.isAdded()) {
                    return;
                }
                new MaterialAlertDialogBuilder(fragment.requireContext())
                        .setTitle("备份校验结果")
                        .setMessage(buildVerifyText(list, copies))
                        .setNeutralButton("立即备份", (d, w) -> manualBackup())
                        .setPositiveButton(R.string.action_ok, null)
                        .show();
            });
        });
    }

    private String buildVerifyText(List<BackupManager.Verified> list, int copies) {
        if (list.isEmpty()) {
            return "还没有备份文件。\n\n先点「立即备份」生成一份，再回来校验。";
        }
        StringBuilder sb = new StringBuilder();
        int bad = 0;
        for (int i = list.size() - 1; i >= 0; i--) { // 新→旧
            BackupManager.Verified v = list.get(i);
            sb.append(friendlyTime(v.file.lastModified())).append(" · ")
                    .append(humanSize(v.file.length())).append("：");
            if (v.unreadable || v.info == null) {
                sb.append("读不出来（文件已损坏？）");
                bad++;
            } else if (!v.info.ok()) {
                sb.append(v.info.reject);
                bad++;
            } else {
                sb.append(v.info.verifyText());
                if (v.info.books >= 0) {
                    sb.append(" · ").append(v.info.books).append(" 种");
                }
            }
            sb.append('\n');
        }
        sb.append(bad == 0 ? "\n全部备份完整可用。" : "\n有 " + bad + " 份有问题，建议立即重新备份。");
        sb.append("\n\n数据库未加密，应用锁只锁界面；重要数据请定期把备份分享到应用外。");
        if (copies > 0) {
            sb.append("\n升级前自动留的原始数据库副本：").append(copies).append(" 份。");
        }
        return sb.toString();
    }

    /**
     * 备份文件管理（v4.23）：列出内部备份 zip，点某个文件再选分享 / 删除。
     * 分享走 FileProvider（backups/ 已加入 file_paths.xml），可发微信 / 网盘 / 邮件。
     */
    public void showBackupFiles() {
        List<File> files = BackupManager.listInternalBackups(fragment.requireContext());
        if (files.isEmpty()) {
            new MaterialAlertDialogBuilder(fragment.requireContext())
                    .setTitle("备份文件管理")
                    .setMessage("还没有备份文件。\n\n开启「自动备份」后，每次打开 App 时备份一次"
                            + "（每天最多一次），在应用内保留最近 "
                            + "5 份备份；也可以在这里把备份 zip 分享给自己（微信 / 网盘）。\n\n"
                            + "⚠ 卸载应用会清空这里的备份，重要数据请定期分享到应用外。")
                    .setNeutralButton("立即备份", (d, w) -> manualBackup())
                    .setPositiveButton(R.string.action_ok, null)
                    .show();
            return;
        }
        // 列表项：新→旧展示（用户最关心最近的备份）
        Collections.reverse(files);
        String[] labels = new String[files.size()];
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            String meta = BackupManager.describeBackup(f); // 只读 meta，不解压
            labels[i] = String.format(Locale.getDefault(), "%s（%s%s）",
                    df.format(new Date(f.lastModified())), humanSize(f.length()),
                    meta == null ? " · 旧版备份" : " · " + meta);
        }
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("备份文件（新 → 旧）")
                .setNeutralButton("立即备份", (d, w) -> manualBackup())
                .setItems(labels, (d, which) -> showFileActions(files.get(which)))
                .setPositiveButton("验证备份", (d, w) -> verifyBackups())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showFileActions(File f) {
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(f.getName())
                .setItems(new String[]{"分享到应用外…", "删除此备份"}, (d, which) -> {
                    if (which == 0) {
                        shareBackup(f);
                    } else {
                        confirmDelete(f);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 通过系统分享面板把备份 zip 发出去（微信文件传输助手 / 网盘 / 邮件均可） */
    private void shareBackup(File f) {
        try {
            Uri uri = FileProvider.getUriForFile(fragment.requireContext(),
                    fragment.requireContext().getPackageName() + ".fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fragment.startActivity(Intent.createChooser(send, "分享备份文件"));
        } catch (Exception e) {
            Toast.makeText(fragment.requireContext(), "分享失败：" + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 删除一份备份（二次确认；删后刷新副标题） */
    private void confirmDelete(File f) {
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("删除备份")
                .setMessage("确定删除 " + f.getName() + " 吗？此操作不可恢复。")
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    BackupManager.deleteInternalBackup(f);
                    refreshRows();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 导入前确认：说明规则，避免误操作 */
    public void confirmImport() {
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("导入备份")
                .setMessage("请选择备份文件：\n\n"
                        + "· xlsx（导出 Excel 生成）：按 ISBN 合并导入，已存在的书会跳过；\n"
                        + "· zip（自动备份生成，v2.8）：整包恢复，会清空当前所有数据（含图片）后按备份重写。\n\n"
                        + "v5.7 起的 zip 带版本与校验和：恢复前会先预览并校验，"
                        + "文件损坏或来自更高版本时拒绝恢复。")
                .setPositiveButton("选择文件", (d, w) -> {
                    try {
                        pickImportLauncher.launch(new String[]{"*/*"});
                    } catch (Exception e) {
                        Toast.makeText(fragment.requireContext(),
                                "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 按扩展名分流：zip 走整包恢复（先确认），其余按 xlsx 合并导入 */
    private void importFile(Uri uri) {
        String name = queryFileName(uri);
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            confirmZipRestore(uri);
            return;
        }
        importXlsx(uri);
    }

    /** 查询所选文件的显示名（判断 xlsx / zip 用） */
    private String queryFileName(Uri uri) {
        try (android.database.Cursor c = fragment.requireContext().getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * zip 整包恢复前的预览与确认（会清空当前数据，必须显式确认）。
     *
     * <p>v5.7：先把整个包读进内存做 SHA-256 校验，并比对备份版本；
     * 校验不通过或备份来自更高版本时不给「确认恢复」按钮。
     */
    private void confirmZipRestore(Uri uri) {
        final Context appCtx = fragment.requireContext().getApplicationContext();
        Toast.makeText(appCtx, "正在校验备份…", Toast.LENGTH_SHORT).show();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            BackupInspector.Info info;
            try {
                info = BackupInspector.inspect(appCtx, uri);
            } catch (Exception e) {
                info = null;
            }
            final BackupInspector.Info result = info;
            String name = "";
            long fileSize = -1;
            try (android.database.Cursor c = appCtx.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME,
                            android.provider.OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    name = c.getString(0);
                    fileSize = c.getLong(1);
                }
            } catch (Exception ignored) {
            }
            final String fileName = name;
            final long size = fileSize;
            app.ui(() -> {
                if (!fragment.isAdded()) {
                    return;
                }
                showRestoreDialog(uri, result, fileName, size);
            });
        });
    }

    private void showRestoreDialog(Uri uri, BackupInspector.Info info,
                                   String fileName, long fileSize) {
        StringBuilder msg = new StringBuilder();
        if (!fileName.isEmpty()) {
            msg.append("文件：").append(fileName);
            if (fileSize > 0) {
                msg.append("（").append(humanSize(fileSize)).append("）");
            }
            msg.append('\n');
        }
        if (info == null) {
            msg.append("读不出这个文件，确认它是小书房生成的 zip 备份了吗？");
            new MaterialAlertDialogBuilder(fragment.requireContext())
                    .setTitle("恢复 zip 备份")
                    .setMessage(msg)
                    .setPositiveButton("换一份", (d, w) -> confirmImport())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        msg.append(info.preview()).append("\n\n");
        if (!info.ok()) {
            msg.append("已拒绝恢复：").append(info.reject)
                    .append("\n\n建议先做一次「立即备份」，再换一份文件试试。");
            new MaterialAlertDialogBuilder(fragment.requireContext())
                    .setTitle("这份备份不能恢复")
                    .setMessage(msg)
                    .setNeutralButton("立即备份", (d, w) -> manualBackup())
                    .setPositiveButton(R.string.action_ok, null)
                    .show();
            return;
        }
        if (info.legacy()) {
            msg.append("这是 v5.7 之前生成的备份，包里没有校验和，只能按内容恢复。\n\n");
        }
        msg.append("将清空当前所有藏书、借出记录与媒体文件，并按备份内容整体恢复。\n\n")
                .append("确定继续吗？（建议先备份一次当前数据）");
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("恢复 zip 备份")
                .setMessage(msg)
                .setPositiveButton("确认恢复", (d, w) -> restoreZip(uri))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 后台线程执行 zip 恢复，完成后弹结果统计 */
    private void restoreZip(Uri uri) {
        // 提前捕获应用上下文，避免异步回调里 requireContext() 因视图销毁而崩
        final Context appCtx = fragment.requireContext().getApplicationContext();
        Toast.makeText(appCtx, "恢复中…", Toast.LENGTH_SHORT).show();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                ZipRestore.Result result = ZipRestore.restore(appCtx, uri, app.db());
                String msg = "已恢复 " + result.books + " 种藏书\n恢复媒体文件 " + result.media + " 个"
                        + (result.legacy ? "\n\n（旧版备份，未能校验完整性）" : "");
                app.ui(() -> {
                    if (!fragment.isAdded()) {
                        return;
                    }
                    new MaterialAlertDialogBuilder(fragment.requireContext())
                            .setTitle("恢复完成")
                            .setMessage(msg)
                            .setPositiveButton("知道了", null)
                            .show();
                });
            } catch (Exception e) {
                app.ui(() -> new MaterialAlertDialogBuilder(appCtx)
                        .setTitle("恢复失败")
                        .setMessage(e.getMessage() == null ? "文件无法读取" : e.getMessage())
                        .setPositiveButton(R.string.action_ok, null)
                        .show());
            }
        });
    }

    private void importXlsx(Uri uri) {
        // 提前捕获应用上下文，避免异步回调里 requireContext() 因视图销毁而崩
        final Context appCtx = fragment.requireContext().getApplicationContext();
        Toast.makeText(appCtx, "导入中…", Toast.LENGTH_SHORT).show();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                ExcelImporter.Result result = ExcelImporter.importBooks(appCtx, uri, app.db());
                String msg = "新增 " + result.added + " 种\n跳过 " + result.skipped
                        + " 条（ISBN 已存在或书名为空）";
                if (result.fixed > 0) {
                    msg += "\n修正 " + result.fixed + " 处（无法识别的数字/状态已按默认值导入）";
                }
                String finalMsg = msg;
                app.ui(() -> {
                    if (!fragment.isAdded()) {
                        return;
                    }
                    new MaterialAlertDialogBuilder(fragment.requireContext())
                            .setTitle("导入完成")
                            .setMessage(finalMsg)
                            .setPositiveButton("知道了", null)
                            .show();
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(appCtx,
                        "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }
}
