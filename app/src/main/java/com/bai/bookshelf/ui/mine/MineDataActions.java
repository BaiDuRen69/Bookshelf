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
import com.bai.bookshelf.util.BorrowReminder;
import com.bai.bookshelf.util.CheckInReminder;
import com.bai.bookshelf.util.ExcelImporter;
import com.bai.bookshelf.util.ZipBackup;
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
    /** 选「导出到…」的目标位置（SAF 创建文档） */
    private final ActivityResultLauncher<String> exportZipLauncher;
    /** 非 null 表示导出这份现成的内部备份；null 表示当场重新打包 */
    private File pendingExportFile;
    /** 导出重入锁 */
    private boolean exporting;

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
                            R.string.mine_folder_set,
                            Toast.LENGTH_SHORT).show();
                });
        this.exportZipLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"),
                uri -> {
                    if (uri != null && fragment.isAdded()) {
                        exportZipTo(uri);
                    } else {
                        pendingExportFile = null;
                    }
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
                nowEnabled ? R.string.mine_auto_on_toast : R.string.mine_auto_off_toast,
                Toast.LENGTH_SHORT).show();
    }

    /** 刷新自动备份/备份文件夹/备份文件三行的状态文案 */
    public void refreshRows() {
        if (b == null) {
            return;
        }
        Context ctx = fragment.requireContext();
        b.tvAutoBackupState.setText(ctx.getString(BackupManager.isEnabled(ctx)
                ? R.string.mine_auto_on : R.string.mine_auto_off));
        String dirName = BackupManager.getTreeName(ctx);
        String dirState;
        if (dirName.isEmpty()) {
            dirState = ctx.getString(R.string.mine_dir_none);
        } else if (BackupManager.safBroken(ctx)) {
            // 设了文件夹却写不进去：必须说出来，否则用户以为外部副本一直在更新
            long failAt = BackupManager.safLastFailAt(ctx);
            dirState = ctx.getString(R.string.mine_dir_broken, friendlyTime(ctx, failAt));
        } else if (BackupManager.safLastOkAt(ctx) > 0) {
            dirState = ctx.getString(R.string.mine_dir_ok, dirName,
                    friendlyTime(ctx, BackupManager.safLastOkAt(ctx)));
        } else {
            dirState = dirName;
        }
        b.tvBackupDirName.setText(dirState);
        boolean stale = com.bai.bookshelf.util.ReminderPrefs.bannerBackupStale(ctx)
                && BackupManager.staleReminderNeeded(ctx);
        b.rowBackupReminder.setVisibility(stale ? View.VISIBLE : View.GONE);
        b.dividerBackupReminder.setVisibility(stale ? View.VISIBLE : View.GONE);
        if (stale) {
            b.tvBackupReminderState.setText(ctx.getString(R.string.mine_dir_stale,
                    BackupManager.latestBackupAgeDays(ctx)));
        }
        // v4.45：备份区直接给「上次备份时间 · 大小 · 份数」，让用户对备份有底
        // v5.7：能读出 meta 就顺带告诉用户那份备份里有几本、是哪个版本生成的
        List<File> files = BackupManager.listInternalBackups(ctx);
        if (files.isEmpty()) {
            b.tvBackupFilesState.setText(R.string.mine_files_none);
        } else {
            File latest = files.get(files.size() - 1);
            String detail = BackupManager.describeBackup(latest);
            b.tvBackupFilesState.setText(detail == null
                    ? ctx.getString(R.string.mine_files_state,
                            friendlyTime(ctx, latest.lastModified()),
                            humanSize(latest.length()), files.size())
                    : ctx.getString(R.string.mine_files_state_detail,
                            friendlyTime(ctx, latest.lastModified()),
                            humanSize(latest.length()), files.size(), detail));
        }
        // 4.3：失败追加在最后一行——「上次 …」描述的是那个能读出来的文件，掩盖不了刚发生的失败
        b.tvBackupFilesState.setText(b.tvBackupFilesState.getText() + failureNote(ctx));
    }

    /** 关掉「备份太久没更新」提醒（30 天内不再提示） */
    public void snoozeBackupReminder() {
        BackupManager.snoozeStaleReminder(fragment.requireContext());
        refreshRows();
    }

    /** 备份时间的人话展示：今天/昨天 时:分，更早则 月日 时:分 */
    private static String friendlyTime(Context ctx, long time) {
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(time);
        Calendar now = Calendar.getInstance();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        boolean today = target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        if (today) {
            return ctx.getString(R.string.mine_time_today, hm.format(target.getTime()));
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        boolean yesterday = target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        if (yesterday) {
            // 「昨天」后面本来就带一个空格，拼进「上次 昨天  · 」会多出一个空格；
            // 这是改前就有的显示，文案迁移阶段原样保留。
            return ctx.getString(R.string.mine_time_yesterday);
        }
        SimpleDateFormat md = new SimpleDateFormat(
                ctx.getString(R.string.mine_time_pattern), Locale.getDefault());
        return md.format(target.getTime());
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
        Toast.makeText(appCtx, R.string.mine_backup_running, Toast.LENGTH_SHORT).show();
        BookshelfApp.get().bg(() -> {
            BackupManager.Outcome o = BackupManager.backupNow(appCtx);
            BookshelfApp.get().ui(() -> {
                if (!fragment.isAdded()) {
                    return;
                }
                refreshRows();
                String msg;
                if (o.meta != null) {
                    msg = appCtx.getString(R.string.mine_backup_done,
                            com.bai.bookshelf.util.Units.titles(o.meta.books),
                            humanSize(o.bytes));
                    if (o.safFailed) {
                        msg += appCtx.getString(R.string.mine_backup_saf_failed, o.safError);
                    }
                } else if (o.empty) {
                    msg = appCtx.getString(R.string.mine_backup_empty);
                } else {
                    msg = appCtx.getString(R.string.mine_backup_failed);
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
        Toast.makeText(appCtx, R.string.mine_verify_running, Toast.LENGTH_SHORT).show();
        BookshelfApp.get().bg(() -> {
            List<BackupManager.Verified> list = BackupManager.verifyInternal(appCtx);
            int copies = com.bai.bookshelf.util.PreMigrationBackup.count(appCtx);
            BookshelfApp.get().ui(() -> {
                if (!fragment.isAdded()) {
                    return;
                }
                new MaterialAlertDialogBuilder(fragment.requireContext())
                        .setTitle(R.string.mine_verify_title)
                        .setMessage(buildVerifyText(appCtx, list, copies))
                        .setNeutralButton(R.string.mine_backup_now, (d, w) -> manualBackup())
                        .setPositiveButton(R.string.action_ok, null)
                        .show();
            });
        });
    }

    private String buildVerifyText(Context ctx, List<BackupManager.Verified> list, int copies) {
        if (list.isEmpty()) {
            return ctx.getString(R.string.mine_verify_empty);
        }
        StringBuilder sb = new StringBuilder();
        int bad = 0;
        for (int i = list.size() - 1; i >= 0; i--) { // 新→旧
            BackupManager.Verified v = list.get(i);
            String when = friendlyTime(ctx, v.file.lastModified());
            String size = humanSize(v.file.length());
            if (v.unreadable || v.info == null) {
                sb.append(ctx.getString(R.string.mine_verify_line, when, size,
                        ctx.getString(R.string.mine_verify_unreadable)));
                bad++;
            } else if (!v.info.ok()) {
                sb.append(ctx.getString(R.string.mine_verify_line, when, size, v.info.reject));
                bad++;
            } else if (v.info.books >= 0) {
                sb.append(ctx.getString(R.string.mine_verify_line_books, when, size,
                        v.info.verifyText(), com.bai.bookshelf.util.Units.titles(v.info.books)));
            } else {
                sb.append(ctx.getString(R.string.mine_verify_line, when, size,
                        v.info.verifyText()));
            }
            sb.append('\n');
        }
        sb.append(bad == 0 ? ctx.getString(R.string.mine_verify_all_ok)
                : ctx.getString(R.string.mine_verify_bad, bad));
        sb.append(ctx.getString(R.string.mine_verify_privacy));
        if (copies > 0) {
            sb.append(ctx.getString(R.string.mine_verify_premigration, copies));
        }
        return sb.toString();
    }

    /**
     * 最近一次备份 / 恢复失败的告警行（4.3）。成功不用重复——行首的「上次 …」说的就是它；
     * 失败原先只落在 logcat，界面反而一直显示得像刚备份过。
     */
    private static String failureNote(Context ctx) {
        StringBuilder sb = new StringBuilder();
        appendFailure(ctx, sb, ctx.getString(R.string.mine_what_backup),
                BackupManager.lastBackup(ctx));
        appendFailure(ctx, sb, ctx.getString(R.string.mine_what_restore),
                BackupManager.lastRestore(ctx));
        return sb.toString();
    }

    private static void appendFailure(Context ctx, StringBuilder sb, String what,
                                      BackupManager.LastResult r) {
        if (r.at == 0 || r.ok) {
            return;
        }
        String msg = truncate(r.msg, 46);
        sb.append(msg.isEmpty()
                ? ctx.getString(R.string.mine_fail_line, what, friendlyTime(ctx, r.at))
                : ctx.getString(R.string.mine_fail_line_msg, what, friendlyTime(ctx, r.at), msg));
    }

    /** 过长的失败原因截断到 limit 个字符，超出补省略号 */
    private static String truncate(String s, int limit) {
        return s.length() > limit ? s.substring(0, limit) + "…" : s;
    }

    /**
     * 最近一次备份 / 恢复的结果（4.3）。原先自动备份失败只有 logcat 里有，
     * 界面只会说「上次备份是 X 天前」——成败都得让用户看见。
     */
    private String lastResultText(Context ctx) {
        return formatResult(ctx, ctx.getString(R.string.mine_last_backup),
                BackupManager.lastBackup(ctx))
                + "\n" + formatResult(ctx, ctx.getString(R.string.mine_last_restore),
                BackupManager.lastRestore(ctx)) + "\n";
    }

    private static String formatResult(Context ctx, String label, BackupManager.LastResult r) {
        if (r.at == 0) {
            return ctx.getString(R.string.mine_result_none, label);
        }
        String when = friendlyTime(ctx, r.at);
        String msg = truncate(r.msg, 80);
        if (msg.isEmpty()) {
            return ctx.getString(r.ok ? R.string.mine_result_ok : R.string.mine_result_fail,
                    label, when);
        }
        return ctx.getString(r.ok ? R.string.mine_result_ok_msg : R.string.mine_result_fail_msg,
                label, when, msg);
    }

    /**
     * 备份文件管理（v4.23）：列出内部备份 zip，点某个文件再选分享 / 删除。
     * 分享走 FileProvider（backups/ 已加入 file_paths.xml），可发微信 / 网盘 / 邮件。
     */
    public void showBackupFiles() {
        final Context appCtx = fragment.requireContext().getApplicationContext();
        List<File> files = BackupManager.listInternalBackups(fragment.requireContext());
        List<File> broken = com.bai.bookshelf.util.DbRepair
                .listBrokenFiles(fragment.requireContext());
        String results = lastResultText(appCtx);
        if (files.isEmpty() && broken.isEmpty()) {
            new MaterialAlertDialogBuilder(fragment.requireContext())
                    .setTitle(R.string.mine_files_title)
                    .setMessage(appCtx.getString(R.string.mine_files_empty_msg, results))
                    .setNeutralButton(R.string.mine_backup_now, (d, w) -> manualBackup())
                    .setPositiveButton(R.string.action_ok, null)
                    .show();
            return;
        }
        // 列表项：新→旧展示（用户最关心最近的备份），损坏原件排在最后
        Collections.reverse(files);
        List<File> all = new java.util.ArrayList<>(files);
        List<String> labels = new java.util.ArrayList<>();
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (File f : files) {
            String meta = BackupManager.describeBackup(f); // 只读 meta，不解压
            String when = df.format(new Date(f.lastModified()));
            labels.add(meta == null
                    ? appCtx.getString(R.string.mine_file_row_old, when, humanSize(f.length()))
                    : appCtx.getString(R.string.mine_file_row, when, humanSize(f.length()), meta));
        }
        for (File f : broken) {
            all.add(f);
            labels.add(appCtx.getString(R.string.mine_file_row_broken,
                    df.format(new Date(f.lastModified())), humanSize(f.length())));
        }
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.mine_files_list_title)
                .setNeutralButton(R.string.mine_backup_now, (d, w) -> manualBackup())
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    File f = all.get(which);
                    if (f.getName().contains(com.bai.bookshelf.util.DbRepair.BROKEN_SUFFIX)) {
                        showBrokenActions(f);
                    } else {
                        showFileActions(f);
                    }
                })
                .setPositiveButton(R.string.mine_verify_btn, (d, w) -> verifyBackups())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 被恢复流程换下来的原数据库：可以导出留存，也可以删掉腾空间 */
    private void showBrokenActions(File f) {
        Context ctx = fragment.requireContext();
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(f.getName())
                .setMessage(R.string.mine_broken_msg)
                .setItems(new String[]{ctx.getString(R.string.mine_broken_share),
                        ctx.getString(R.string.mine_broken_delete)}, (d, which) -> {
                    if (which == 0) {
                        shareBroken(f);
                    } else {
                        confirmDeleteBroken(f);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** FileProvider 不暴露 databases/ 目录，先复制到 files/share/ 再分享 */
    private void shareBroken(File f) {
        try {
            File dir = new File(fragment.requireContext().getFilesDir(), "share");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File out = new File(dir, f.getName() + ".db");
            try (java.io.FileInputStream in = new java.io.FileInputStream(f);
                 java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = in.read(buf)) > 0) {
                    os.write(buf, 0, len);
                }
            }
            Uri uri = FileProvider.getUriForFile(fragment.requireContext(),
                    fragment.requireContext().getPackageName() + ".fileprovider", out);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/octet-stream");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fragment.startActivity(Intent.createChooser(send,
                    fragment.requireContext().getString(R.string.mine_broken_chooser)));
        } catch (Exception e) {
            Toast.makeText(fragment.requireContext(),
                    fragment.requireContext().getString(R.string.mine_export_fail, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteBroken(File f) {
        Context ctx = fragment.requireContext();
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.mine_broken_delete_title)
                .setMessage(R.string.mine_broken_delete_msg)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    Toast.makeText(ctx, R.string.mine_deleted, Toast.LENGTH_SHORT).show();
                    refreshRows();
                })
                .show();
    }

    private void showFileActions(File f) {
        Context ctx = fragment.requireContext();
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(f.getName())
                .setItems(new String[]{ctx.getString(R.string.mine_file_share),
                        ctx.getString(R.string.mine_file_export),
                        ctx.getString(R.string.mine_file_delete)}, (d, which) -> {
                    if (which == 0) {
                        shareBackup(f);
                    } else if (which == 1) {
                        exportExisting(f);
                    } else {
                        confirmDelete(f);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 「导出备份 zip」：先选位置，再当场重新打包一份最新整包 */
    public void exportZipNow() {
        if (exporting) {
            return;
        }
        pendingExportFile = null;
        exportZipLauncher.launch(BackupManager.suggestExportFileName());
    }

    /** 将已有的内部备份另存到用户选的位置（内容不重新生成） */
    private void exportExisting(File f) {
        if (exporting) {
            return;
        }
        pendingExportFile = f;
        exportZipLauncher.launch(f.getName());
    }

    /** 打包（或直接取已有备份）后写进 SAF _uri，全程不申请存储权限 */
    private void exportZipTo(Uri uri) {
        if (exporting) {
            return;
        }
        exporting = true;
        final Context appCtx = fragment.requireContext().getApplicationContext();
        final File source = pendingExportFile;
        pendingExportFile = null;
        final String name = displayName(appCtx, uri);
        Toast.makeText(appCtx, R.string.mine_packing, Toast.LENGTH_SHORT).show();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            File tmp = null;
            String fail = null;
            long bytes = 0;
            try {
                File src = source;
                if (src == null) {
                    tmp = File.createTempFile("export_", ".zip", appCtx.getCacheDir());
                    ZipBackup.createTo(appCtx, tmp);
                    src = tmp;
                }
                bytes = src.length();
                try (java.io.InputStream in = new java.io.FileInputStream(src);
                     java.io.OutputStream out =
                             appCtx.getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) {
                        throw new java.io.IOException(
                                appCtx.getString(R.string.mine_write_denied));
                    }
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                }
            } catch (Throwable e) {
                fail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                if (tmp != null) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
            final String error = fail;
            final long size = bytes;
            app.ui(() -> {
                exporting = false;
                if (!fragment.isAdded()) {
                    return;
                }
                if (error != null) {
                    new MaterialAlertDialogBuilder(fragment.requireContext())
                            .setTitle(R.string.mine_export_fail_title)
                            .setMessage(error)
                            .setPositiveButton(R.string.action_ok, null)
                            .show();
                    return;
                }
                new MaterialAlertDialogBuilder(fragment.requireContext())
                        .setTitle(R.string.mine_export_done_title)
                        .setMessage(appCtx.getString(R.string.mine_export_done_msg,
                                name, humanSize(size)))
                        .setPositiveButton(R.string.action_ok, null)
                        .show();
            });
        });
    }

    /** SAF 的 uri 里拿显示名（lastPathSegment 可能是 "primary:Download/…"，不适合直接给用户看） */
    private static String displayName(Context ctx, Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? uri.toString() : last;
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
            fragment.startActivity(Intent.createChooser(send,
                    fragment.requireContext().getString(R.string.mine_share_chooser)));
        } catch (Exception e) {
            Toast.makeText(fragment.requireContext(),
                    fragment.requireContext().getString(R.string.mine_share_fail, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 删除一份备份（二次确认；删后刷新副标题） */
    private void confirmDelete(File f) {
        Context ctx = fragment.requireContext();
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.mine_delete_title)
                .setMessage(ctx.getString(R.string.mine_delete_msg, f.getName()))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    BackupManager.deleteInternalBackup(f);
                    refreshRows();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 导入前确认：说明规则，避免误操作 */
    public void confirmImport() {
        Context ctx = fragment.requireContext();
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.mine_import_title)
                .setMessage(R.string.mine_import_msg)
                .setPositiveButton(R.string.mine_import_pick, (d, w) -> {
                    try {
                        pickImportLauncher.launch(new String[]{"*/*"});
                    } catch (Exception e) {
                        Toast.makeText(ctx,
                                ctx.getString(R.string.mine_picker_fail, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
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
        Toast.makeText(appCtx, R.string.mine_verify_running, Toast.LENGTH_SHORT).show();
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
        Context ctx = fragment.requireContext();
        StringBuilder msg = new StringBuilder();
        if (!fileName.isEmpty()) {
            msg.append(fileSize > 0
                    ? ctx.getString(R.string.mine_restore_file_size, fileName,
                            humanSize(fileSize))
                    : ctx.getString(R.string.mine_restore_file_only, fileName));
        }
        if (info == null) {
            msg.append(ctx.getString(R.string.mine_restore_unreadable));
            new MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.mine_restore_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.mine_restore_pick_other, (d, w) -> confirmImport())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        msg.append(info.preview()).append("\n\n");
        if (!info.ok()) {
            msg.append(ctx.getString(R.string.mine_restore_reject, info.reject));
            new MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.mine_restore_reject_title)
                    .setMessage(msg)
                    .setNeutralButton(R.string.mine_backup_now, (d, w) -> manualBackup())
                    .setPositiveButton(R.string.action_ok, null)
                    .show();
            return;
        }
        if (info.legacy()) {
            msg.append(ctx.getString(R.string.mine_restore_legacy_note));
        }
        // v5.9.5 第 1 项：说清设置里哪些随包迁移，别让人以为备份=全量
        msg.append(ctx.getString(R.string.mine_restore_warn));
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.mine_restore_title)
                .setMessage(msg)
                .setPositiveButton(R.string.mine_restore_confirm, (d, w) -> restoreZip(uri))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 后台线程执行 zip 恢复，完成后弹结果统计 */
    private void restoreZip(Uri uri) {
        // 提前捕获应用上下文，避免异步回调里 requireContext() 因视图销毁而崩
        final Context appCtx = fragment.requireContext().getApplicationContext();
        Toast.makeText(appCtx, R.string.mine_restoring, Toast.LENGTH_SHORT).show();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                ZipRestore.Result result = ZipRestore.restore(appCtx, uri, app.db());
                final String msg = appCtx.getString(R.string.mine_restored_msg,
                        com.bai.bookshelf.util.Units.titles(result.books), result.media)
                        + (result.legacy ? appCtx.getString(R.string.mine_restored_legacy) : "")
                        + (result.settingsApplied
                                ? appCtx.getString(R.string.mine_restored_settings) : "");
                BackupManager.recordRestoreResult(appCtx, true,
                        appCtx.getString(R.string.mine_restored_record,
                                com.bai.bookshelf.util.Units.titles(result.books), result.media));
                // 设置里的提醒开关/时间刚被改过，闹钟与小组件按新状态重排
                BorrowReminder.refresh(appCtx);
                CheckInReminder.schedule(appCtx);
                com.bai.bookshelf.ui.widget.ReadingWidgetProvider.refresh(appCtx);
                app.ui(() -> {
                    if (!fragment.isAdded()) {
                        return;
                    }
                    // 主题/底栏要 recreate 才生效；不杀进程——一次性迁移任务不该重跑
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
                            fragment.requireContext())
                            .setTitle(R.string.mine_restored_title)
                            .setMessage(msg);
                    if (result.settingsApplied) {
                        builder.setPositiveButton(R.string.mine_relaunch, (d, w) ->
                                fragment.requireActivity().recreate());
                        builder.setNegativeButton(R.string.mine_got_it, null);
                    } else {
                        builder.setPositiveButton(R.string.mine_got_it, null);
                    }
                    builder.show();
                });
            } catch (Exception e) {
                BackupManager.recordRestoreResult(appCtx, false,
                        appCtx.getString(R.string.mine_restore_fail_record,
                                e.getMessage() == null ? e.getClass().getSimpleName()
                                        : e.getMessage()));
                app.ui(() -> new MaterialAlertDialogBuilder(appCtx)
                        .setTitle(R.string.mine_restore_fail_title)
                        .setMessage(e.getMessage() == null
                                ? appCtx.getString(R.string.mine_restore_fail_unreadable)
                                : e.getMessage())
                        .setPositiveButton(R.string.action_ok, null)
                        .show());
            }
        });
    }

    private void importXlsx(Uri uri) {
        // 提前捕获应用上下文，避免异步回调里 requireContext() 因视图销毁而崩
        final Context appCtx = fragment.requireContext().getApplicationContext();
        Toast.makeText(appCtx, R.string.mine_importing, Toast.LENGTH_SHORT).show();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                ExcelImporter.Result result = ExcelImporter.importBooks(appCtx, uri, app.db());
                String msg = appCtx.getString(R.string.mine_imported_msg,
                        com.bai.bookshelf.util.Units.titles(result.added), result.skipped);
                if (result.fixed > 0) {
                    msg += appCtx.getString(R.string.mine_imported_fixed, result.fixed);
                }
                String finalMsg = msg;
                app.ui(() -> {
                    if (!fragment.isAdded()) {
                        return;
                    }
                    new MaterialAlertDialogBuilder(fragment.requireContext())
                            .setTitle(R.string.mine_imported_title)
                            .setMessage(finalMsg)
                            .setPositiveButton(R.string.mine_got_it, null)
                            .show();
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(appCtx,
                        appCtx.getString(R.string.mine_import_fail, e.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }
}
