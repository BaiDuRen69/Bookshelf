package com.bai.bookshelf.ui.mine;

import androidx.appcompat.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.bai.bookshelf.api.ApiPrefs;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.databinding.DialogRewardQrBinding;
import com.bai.bookshelf.databinding.DialogSupportBinding;
import com.bai.bookshelf.databinding.FragmentMineBinding;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.ui.location.LocationActivity;
import com.bai.bookshelf.ui.scan.ScanActivity;
import com.bai.bookshelf.util.BackupManager;
import com.bai.bookshelf.BuildConfig;
import com.bai.bookshelf.util.ExcelExporter;
import com.bai.bookshelf.util.ExcelImporter;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.util.ZipRestore;
import com.bumptech.glide.Glide;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 我的页：数据概览 + 分区功能入口（藏书管理 / 数据与备份 / 设置）。
 * v2.7 起按 Material Design 设置页规范分组：分区标题具体、高频入口置顶。
 */
public class MineFragment extends Fragment {

    private FragmentMineBinding b;

    /** 选择 xlsx 文件后的导入入口。在 onCreate 中注册，避免某些 ROM/Fragment 生命周期下报 unregistered。 */
    private androidx.activity.result.ActivityResultLauncher<String[]> pickExcelLauncher;

    /** 选择备份文件夹（SAF），选中后申请长期授权并保存 */
    private androidx.activity.result.ActivityResultLauncher<Uri> pickTreeLauncher;

    /** 导出 Excel 的目标文件夹选择器（SAF） */
    private androidx.activity.result.ActivityResultLauncher<Uri> exportTreeLauncher;

    /** 保存收款码到相册前的存储权限申请（API 28 及以下需要，在 onCreate 中注册） */
    private androidx.activity.result.ActivityResultLauncher<String> writePermLauncher;

    /** 待保存的收款码（权限回调后继续保存） */
    private int pendingSaveResId;
    private String pendingSaveName;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 必须在 onCreate 中注册 ActivityResultLauncher；字段初始化方式在某些 ROM 下会报 unregistered
        pickExcelLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null && isAdded()) {
                        importExcel(uri);
                    }
                });
        pickTreeLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    requireContext().getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    BackupManager.setTree(requireContext(), uri, uri.getLastPathSegment());
                    refreshBackupRows();
                    Toast.makeText(requireContext(), "备份文件夹已设置（自动备份将在此保留一份副本）",
                            Toast.LENGTH_SHORT).show();
                });
        exportTreeLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri != null && isAdded()) {
                        exportToFolder(uri);
                    }
                });
        // 存储权限（仅 API 28 及以下保存收款码到相册时需要）
        writePermLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        doSaveImageToGallery(pendingSaveResId, pendingSaveName);
                    } else {
                        Toast.makeText(requireContext(), "未获得存储权限，无法保存图片",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentMineBinding.inflate(inflater, container, false);
        // v4.18：Android 15+ 强制 edge-to-edge，给顶部栏加状态栏内边距，
        // 避免「我的」标题被状态栏遮挡
        InsetsHelper.applyTopInset(b.toolbar);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        b.rowScan.setOnClickListener(v -> startActivity(new Intent(requireContext(), ScanActivity.class)));
        b.rowLocation.setOnClickListener(v -> startActivity(new Intent(requireContext(), LocationActivity.class)));
        b.rowSource.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                com.bai.bookshelf.ui.location.SourceChannelActivity.class)));
        b.rowTag.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                com.bai.bookshelf.ui.location.TagActivity.class)));
        b.rowCategory.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                com.bai.bookshelf.ui.location.CategoryActivity.class)));
        b.rowExport.setOnClickListener(v -> exportExcel());
        b.rowImport.setOnClickListener(v -> confirmImport());
        b.rowAutoBackup.setOnClickListener(v -> toggleAutoBackup());
        b.rowBackupDir.setOnClickListener(v -> pickTreeLauncher.launch(null));
        refreshBackupRows();
        // 应用更新（v4.7）：启动检查开关 + 手动检查
        b.rowUpdateToggle.setOnClickListener(v -> toggleUpdateCheck());
        b.rowCheckUpdate.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "正在检查更新…", Toast.LENGTH_SHORT).show();
            com.bai.bookshelf.util.UpdateChecker.checkManually(requireActivity());
        });
        // v4.15：API 设置从弹窗改为二级页（API 配置 / 用量查询 分开）
        b.rowApi.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                ApiSettingsActivity.class)));
        b.rowSplit.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                com.bai.bookshelf.ui.mine.SplitActivity.class)));
        b.rowAbout.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AboutActivity.class)));
        b.rowTheme.setOnClickListener(v -> showThemePicker());
        b.rowFeedbackEmail.setOnClickListener(v -> sendFeedbackEmail());
        b.rowFeedbackGithub.setOnClickListener(v -> openGitHubIssues());
        b.rowGithub.setOnClickListener(v -> openGitHubRepo());
        b.rowReward.setOnClickListener(v -> showSupportDialog());
        b.rowCrashLog.setOnClickListener(v -> showCrashLog());
        refreshUpdateToggleRow();
        refreshStateRows();
    }

    /** 刷新各行副标题中的实时状态（主题、关于版本、备份） */
    private void refreshStateRows() {
        if (b == null) {
            return;
        }
        // v4.19：主题 id 越界保护（历史数据异常时不让数组下标越界直接崩）
        String[] themeNames = ThemeManager.names();
        int cur = ThemeManager.current(requireContext());
        if (cur < 0 || cur >= themeNames.length) {
            cur = 0;
        }
        b.tvThemeState.setText("当前：" + themeNames[cur]);
        b.tvAboutState.setText("v" + BuildConfig.VERSION_NAME + " · 作者 BaiDuRen");
        // v4.19：只有真的崩过才显示「崩溃日志」入口
        boolean has = com.bai.bookshelf.util.CrashHandler.hasLog(requireContext());
        b.rowCrashLog.setVisibility(has ? android.view.View.VISIBLE : android.view.View.GONE);
        refreshBackupRows();
    }

    /**
     * 展示最近一次崩溃日志（v4.19）。
     *
     * <p>用户反馈「很多地方一点就闪退」时，系统弹窗拿不到堆栈。这里把
     * {@link com.bai.bookshelf.util.CrashHandler} 记下的完整堆栈显示出来，
     * 并提供「复制」按钮，方便用户直接粘给开发者。
     */
    private void showCrashLog() {
        String log = com.bai.bookshelf.util.CrashHandler.readLog(requireContext());
        if (log == null || log.trim().isEmpty()) {
            Toast.makeText(requireContext(), "暂无崩溃记录", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(requireContext());
        TextView tv = new TextView(requireContext());
        tv.setText(log);
        tv.setTextSize(11);
        tv.setTextColor(ContextCompat.getColor(requireContext(), com.bai.bookshelf.R.color.text_main));
        tv.setPadding(dp(20), dp(16), dp(20), dp(16));
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("崩溃日志")
                .setView(sv)
                .setPositiveButton("复制", (d, w) -> copyToClipboard(log, "崩溃日志已复制"))
                .setNeutralButton("清空", (d, w) -> {
                    com.bai.bookshelf.util.CrashHandler.clearLog(requireContext());
                    refreshStateRows();
                    Toast.makeText(requireContext(), "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(com.bai.bookshelf.R.string.action_cancel, null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        onVisible();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        // v4.1：MainActivity 切 Tab 改为 show/hide 复用实例，切回「我的」页时
        // 由这里刷新概览计数（备份状态等在返回本页的 onResume 里已覆盖）
        if (!hidden) {
            onVisible();
        }
    }

    private void onVisible() {
        loadSummary();
    }

    /** 概览：藏书数 / 地点数 */
    private void loadSummary() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            int bookCount = app.db().bookDao().count();
            int locCount = app.db().locationDao().count();
            app.ui(() -> {
                if (b != null) {
                    b.tvSummary.setText("藏书 " + bookCount + " 册 · 地点 " + locCount + " 个\n"
                            + "数据保存在本机，记得定期导出 Excel 备份");
                }
            });
        });
    }

    /** 导出 Excel：先让用户选择导出文件夹，再在后台生成并写入该文件夹 */
    private void exportExcel() {
        try {
            exportTreeLauncher.launch(null);
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "无法打开文件夹选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 在用户选好的文件夹里生成 xlsx（一次性授权，写入即完成） */
    private void exportToFolder(Uri treeUri) {
        // 提前捕获应用上下文，避免异步回调里 requireContext() 因视图销毁而崩
        android.content.Context appCtx = requireContext().getApplicationContext();
        // v3.1：网络封面需要先下载，耗时视藏书量而定，提示里说明清楚避免以为卡死
        Toast.makeText(appCtx, "正在导出（含封面下载，请稍候）…", Toast.LENGTH_LONG).show();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                List<Book> books = com.bai.bookshelf.util.BookSanitizer
                        .normalizeAll(app.db().bookDao().getAll());
                Map<Long, String> locNames = new HashMap<>();
                for (Location loc : app.db().locationDao().getAll()) {
                    locNames.put(loc.id, loc.name);
                }
                ExcelExporter.ExportResult result =
                        ExcelExporter.exportToTree(appCtx, treeUri, books, locNames);
                int total = books.size();
                int imgs = result.imageCount;
                app.ui(() -> {
                    String coverTip;
                    if (imgs == 0) {
                        coverTip = "\n未嵌入封面（藏书没有可用的封面图）";
                    } else if (imgs < total) {
                        coverTip = "\n已嵌入封面 " + imgs + " / " + total
                                + " 张（部分封面下载失败，不影响其余数据）";
                    } else {
                        coverTip = "\n已嵌入封面 " + imgs + " 张";
                    }
                    Toast.makeText(appCtx, "已导出到所选文件夹：\n"
                            + result.fileName + coverTip, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(appCtx,
                        "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * 开关自动备份（v2.8 起备份改为 zip 整包，默认存应用内部存储，不再强制选文件夹）。
     * 额外选一个 SAF 文件夹只是为了在外部多留一份副本。
     */
    private void toggleAutoBackup() {
        boolean nowEnabled = !BackupManager.isEnabled(requireContext());
        BackupManager.setEnabled(requireContext(), nowEnabled);
        refreshBackupRows();
        Toast.makeText(requireContext(),
                nowEnabled ? "已开启：每次打开 App 自动备份（zip 整包，存于应用内部存储）" : "已关闭自动备份",
                Toast.LENGTH_SHORT).show();
    }

    /** 刷新自动备份/备份文件夹两行的状态文案 */
    private void refreshBackupRows() {
        if (b == null) {
            return;
        }
        b.tvAutoBackupState.setText(BackupManager.isEnabled(requireContext())
                ? "已开启（每次启动时备份）" : "已关闭");
        String dirName = BackupManager.getTreeName(requireContext());
        b.tvBackupDirName.setText(dirName.isEmpty() ? "未选择" : dirName);
    }

    /** 开关「启动时检查应用更新」（v4.7） */
    private void toggleUpdateCheck() {
        boolean nowEnabled = !com.bai.bookshelf.util.UpdateChecker
                .isStartCheckEnabled(requireContext());
        com.bai.bookshelf.util.UpdateChecker.setStartCheckEnabled(requireContext(), nowEnabled);
        refreshUpdateToggleRow();
        Toast.makeText(requireContext(),
                nowEnabled ? "已开启：打开 App 时自动检查新版本" : "已关闭：不再自动检查，可手动检查",
                Toast.LENGTH_SHORT).show();
    }

    /** 刷新「启动时检查应用更新」行副标题 */
    private void refreshUpdateToggleRow() {
        if (b == null) {
            return;
        }
        b.tvUpdateToggleState.setText(com.bai.bookshelf.util.UpdateChecker
                .isStartCheckEnabled(requireContext())
                ? "已开启（发现新版本时提醒）" : "已关闭");
    }

    /** 导入前确认：说明规则，避免误操作 */
    private void confirmImport() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("导入备份")
                .setMessage("请选择备份文件：\n\n"
                        + "· xlsx（导出 Excel 生成）：按 ISBN 合并导入，已存在的书会跳过；\n"
                        + "· zip（自动备份生成，v2.8）：整包恢复，会清空当前所有数据（含图片）后按备份重写。")
                .setPositiveButton("选择文件", (d, w) -> {
                    try {
                        pickExcelLauncher.launch(new String[]{"*/*"});
                    } catch (Exception e) {
                        Toast.makeText(requireContext(),
                                "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 解析并导入 xlsx，完成后弹结果统计 */
    private void importExcel(Uri uri) {
        // v2.8：.zip 走整包恢复（先向用户确认替换语义），其余按 xlsx 合并导入
        String name = queryFileName(uri);
        if (name != null && name.toLowerCase().endsWith(".zip")) {
            confirmZipRestore(uri);
            return;
        }
        importExcelXlsx(uri);
    }

    /** 查询所选文件的显示名（判断 xlsx / zip 用） */
    private String queryFileName(Uri uri) {
        try (android.database.Cursor c = requireContext().getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** zip 整包恢复前的二次确认（会清空当前数据，必须显式确认） */
    private void confirmZipRestore(Uri uri) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("恢复 zip 备份")
                .setMessage("将清空当前所有藏书、借出记录与媒体文件，并按备份内容整体恢复。\n\n确定继续吗？（建议先导出一次当前数据）")
                .setPositiveButton("确认恢复", (d, w) -> restoreZip(uri))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 后台线程执行 zip 恢复，完成后弹结果统计 */
    private void restoreZip(Uri uri) {
        // 提前捕获应用上下文，避免异步回调里 requireContext() 因视图销毁而崩
        android.content.Context appCtx = requireContext().getApplicationContext();
        Toast.makeText(appCtx, "恢复中…", Toast.LENGTH_SHORT).show();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                ZipRestore.Result result = ZipRestore.restore(appCtx, uri, app.db());
                String msg = "已恢复 " + result.books + " 本书\n恢复媒体文件 " + result.media + " 个";
                app.ui(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("恢复完成")
                            .setMessage(msg)
                            .setPositiveButton("知道了", null)
                            .show();
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(appCtx,
                        "恢复失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void importExcelXlsx(Uri uri) {
        // 提前捕获应用上下文，避免异步回调里 requireContext() 因视图销毁而崩
        android.content.Context appCtx = requireContext().getApplicationContext();
        Toast.makeText(appCtx, "导入中…", Toast.LENGTH_SHORT).show();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                ExcelImporter.Result result = ExcelImporter.importBooks(appCtx, uri, app.db());
                String msg = "新增 " + result.added + " 本\n跳过 " + result.skipped
                        + " 条（ISBN 已存在或书名为空）";
                if (result.fixed > 0) {
                    msg += "\n修正 " + result.fixed + " 处（无法识别的数字/状态已按默认值导入）";
                }
                String finalMsg = msg;
                app.ui(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    new MaterialAlertDialogBuilder(requireContext())
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

    // ======================= 主题颜色（v2.5） =======================

    /** 主题选择弹窗：色点 + 名称 + 当前勾选，选中后保存并重建界面生效 */
    private void showThemePicker() {
        android.content.Context ctx = requireContext();
        String[] names = ThemeManager.names();
        int[] colors = ThemeManager.previewColors();
        // v4.19：主题 id 来自 SharedPreferences，若历史数据越界（如版本升级后
        // 主题数量变化）会直接取 names[current] 抛 ArrayIndexOutOfBoundsException，
        // 这里先夹到合法范围，避免一点就闪退。
        int current = ThemeManager.current(ctx);
        if (current < 0 || current >= names.length) {
            current = 0;
        }
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        // v4.18：与其它弹窗统一左右留白（对齐对话框标题的 24dp）
        int pad = dp(24);
        root.setPadding(pad, dp(4), pad, dp(4));

        final androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView row = new TextView(ctx);
            row.setText(names[i]);
            row.setTextSize(15);
            row.setTextColor(ContextCompat.getColor(ctx, com.bai.bookshelf.R.color.text_main));
            row.setPadding(dp(14), dp(14), dp(14), dp(14));
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            // 主题色圆点
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(colors[i]);
            dot.setSize(dp(18), dp(18));
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
            row.setCompoundDrawablePadding(dp(12));
            // v4.18：改为描边卡片底（bg_dialog_row），与其它弹窗入口行一致
            row.setBackgroundResource(com.bai.bookshelf.R.drawable.bg_dialog_row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> {
                ThemeManager.set(ctx, idx);
                Toast.makeText(ctx, "已切换主题：" + names[idx], Toast.LENGTH_SHORT).show();
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                requireActivity().recreate(); // 重建当前界面使新主题生效
            });
            root.addView(row);
        }
        // v4.18：当前主题标记放在标题里，避免在选项文字后拼接符号
        holder[0] = new MaterialAlertDialogBuilder(ctx)
                .setTitle("主题颜色（当前：" + names[current] + "）")
                .setView(root)
                .setNegativeButton(com.bai.bookshelf.R.string.action_cancel, null)
                .show();
    }

    // ======================= 问题反馈（v4.6） =======================

    /** 反馈邮箱与 GitHub Issues 提交页（与 README「反馈」一节保持一致，改动需同步两处） */
    private static final String FEEDBACK_EMAIL = "baiduren69@outlook.com";
    private static final String FEEDBACK_ISSUES_URL = "https://github.com/BaiDuRen69/Bookshelf/issues/new";
    /** 项目主页（v4.15：「我的 GitHub」行跳转） */
    private static final String GITHUB_REPO_URL = "https://github.com/BaiDuRen69/Bookshelf";

    /** 拉起系统邮件应用（主题自动带上版本号）；无邮件应用时降级为复制邮箱 */
    private void sendFeedbackEmail() {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:" + FEEDBACK_EMAIL));
        intent.putExtra(Intent.EXTRA_SUBJECT, "小书房 App 反馈 v" + BuildConfig.VERSION_NAME);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "未找到邮件应用，已改为复制邮箱地址",
                    Toast.LENGTH_LONG).show();
            copyToClipboard(FEEDBACK_EMAIL, "已复制邮箱地址");
        }
    }

    /** 浏览器打开 GitHub Issues 页；无浏览器时提示手动访问并复制链接 */
    private void openGitHubIssues() {
        openInBrowser(FEEDBACK_ISSUES_URL);
    }

    /** 浏览器打开项目 GitHub 主页（v4.15「我的 GitHub」行） */
    private void openGitHubRepo() {
        openInBrowser(GITHUB_REPO_URL);
    }

    /** 浏览器打开链接；无浏览器时提示手动访问并复制链接 */
    private void openInBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "未找到浏览器，请手动访问（链接已复制）：\n" + url,
                    Toast.LENGTH_LONG).show();
            copyToClipboard(url, "已复制链接");
        }
    }

    /** 复制文本到剪贴板并提示 */
    private void copyToClipboard(String text, String toast) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) requireContext()
                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("bookshelf_feedback", text));
        }
        Toast.makeText(requireContext(), toast, Toast.LENGTH_SHORT).show();
    }

    // ======================= 打赏作者（v2.5） =======================

    /** 「支持作者」弹窗：支付宝 / 微信 两个入口 */
    private void showSupportDialog() {
        DialogSupportBinding db = DialogSupportBinding.inflate(getLayoutInflater());
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(db.getRoot())
                .setNegativeButton(com.bai.bookshelf.R.string.action_cancel, null)
                .show();
        db.rowAlipay.setOnClickListener(v -> {
            dialog.dismiss();
            showRewardQrDialog(com.bai.bookshelf.R.drawable.alipay_qr, "支付宝");
        });
        db.rowWechat.setOnClickListener(v -> {
            dialog.dismiss();
            showRewardQrDialog(com.bai.bookshelf.R.drawable.wechat_qr, "微信");
        });
    }

    /** 收款码大图弹窗：展示图片 + 「保存」按钮（保存到相册） */
    private void showRewardQrDialog(int imgRes, String name) {
        DialogRewardQrBinding db = DialogRewardQrBinding.inflate(getLayoutInflater());
        Glide.with(this).load(imgRes).into(db.ivQr);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(name + "收款码")
                .setView(db.getRoot())
                .setNegativeButton(com.bai.bookshelf.R.string.action_cancel, null)
                .show();
        db.btnSave.setOnClickListener(v -> saveImageToGallery(imgRes, name));
    }

    /** 保存收款码到相册（Pictures/小书房）；API 28 及以下先申请存储权限 */
    private void saveImageToGallery(int imgRes, String name) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSaveResId = imgRes;
            pendingSaveName = name;
            writePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        doSaveImageToGallery(imgRes, name);
    }

    /** 通过 MediaStore 把收款码写入相册 */
    private void doSaveImageToGallery(int imgRes, String name) {
        android.content.Context appCtx = requireContext().getApplicationContext();
        try {
            Bitmap bmp = BitmapFactory.decodeResource(getResources(), imgRes);
            String fileName = "小书房打赏_" + name + "_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".png";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            ContentResolver cr = appCtx.getContentResolver();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/小书房");
            } else {
                File dir = new File(Environment.getExternalStorageDirectory(), "Pictures/小书房");
                if (!dir.exists() && !dir.mkdirs()) {
                    Toast.makeText(appCtx, "保存失败：无法创建目录", Toast.LENGTH_LONG).show();
                    return;
                }
                values.put(MediaStore.Images.Media.DATA, new File(dir, fileName).getAbsolutePath());
            }
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Toast.makeText(appCtx, "保存失败：相册不可用", Toast.LENGTH_LONG).show();
                return;
            }
            try (OutputStream os = cr.openOutputStream(uri)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            Toast.makeText(appCtx, "已保存到相册：Pictures/小书房", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(appCtx, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
