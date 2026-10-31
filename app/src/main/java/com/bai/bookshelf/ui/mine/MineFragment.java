package com.bai.bookshelf.ui.mine;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.databinding.FragmentMineBinding;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.ui.scan.ScanActivity;
import com.bai.bookshelf.BuildConfig;
import com.bai.bookshelf.util.ExcelExporter;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 我的页：数据概览 + 分区功能入口（藏书管理 / 数据与备份 / 设置）。
 * v2.7 起按 Material Design 设置页规范分组：分区标题具体、高频入口置顶。
 */
public class MineFragment extends Fragment {

    private FragmentMineBinding b;

    /** 导出 Excel 的目标文件夹选择器（SAF） */
    private androidx.activity.result.ActivityResultLauncher<Uri> exportTreeLauncher;
    /** 备份与导入（v5.6 抽到 MineDataActions） */
    private MineDataActions dataActions;
    /** 主题选择弹窗（v5.6 抽到 ThemePickerDialog） */
    private ThemePickerDialog themePicker;

    // ===== v4.51 拍照识字（v5.6 抽到 MineOcrImport） =====
    private MineOcrImport ocrImport;
    /** 反馈/点赞助入口（v5.6 抽到 MineSupportActions） */
    private MineSupportActions supportActions;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 必须在 onCreate 中注册 ActivityResultLauncher；字段初始化方式在某些 ROM 下会报 unregistered
        exportTreeLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri != null && isAdded()) {
                        exportToFolder(uri);
                    }
                });
        dataActions = new MineDataActions(this);
        themePicker = new ThemePickerDialog(this);
        ocrImport = new MineOcrImport(this);
        supportActions = new MineSupportActions(this);
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
        b.rowOcr.setOnClickListener(v -> ocrImport.showSourceDialog());
        b.rowManage.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), LibraryManageActivity.class)));
        b.rowExport.setOnClickListener(v -> exportExcel());
        b.rowImport.setOnClickListener(v -> dataActions.confirmImport());
        b.rowWish.setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(),
                        com.bai.bookshelf.ui.wish.WishListActivity.class)));
        b.rowSeries.setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(),
                        com.bai.bookshelf.ui.series.SeriesListActivity.class)));
        b.rowInventory.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(requireContext(),
                    com.bai.bookshelf.ui.scan.ScanActivity.class);
            intent.putExtra(com.bai.bookshelf.ui.scan.ScanActivity.EXTRA_INVENTORY, true);
            startActivity(intent);
        });
        b.rowAutoBackup.setOnClickListener(v -> dataActions.toggleAutoBackup());
        b.rowBackupDir.setOnClickListener(v -> dataActions.pickBackupFolder());
        b.rowBackupFiles.setOnClickListener(v -> dataActions.showBackupFiles());
        b.rowBackupReminder.setOnClickListener(v -> dataActions.manualBackup());
        b.btnBackupReminderClose.setOnClickListener(v -> dataActions.snoozeBackupReminder());
        dataActions.attach(b);
        dataActions.refreshRows();
        // 应用更新（v4.7）：启动检查开关 + 手动检查
        b.rowUpdateToggle.setOnClickListener(v -> toggleUpdateCheck());
        b.rowCheckUpdate.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "正在检查更新…", Toast.LENGTH_SHORT).show();
            com.bai.bookshelf.util.UpdateChecker.checkManually(requireActivity());
        });
        // v4.15：API 设置从弹窗改为二级页（API 配置 / 用量查询 分开）
        b.rowApi.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                ApiSettingsActivity.class)));
        b.rowIncomplete.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                IncompleteBooksActivity.class)));
        b.rowAppLock.setOnClickListener(v -> toggleAppLock());
        b.rowAbout.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AboutActivity.class)));
        b.rowTheme.setOnClickListener(v -> themePicker.show());
        b.rowFeedbackEmail.setOnClickListener(v -> supportActions.sendFeedbackEmail());
        b.rowFeedbackGithub.setOnClickListener(v -> supportActions.openGitHubIssues());
        b.rowGithub.setOnClickListener(v -> supportActions.openGitHubRepo());
        b.rowReward.setOnClickListener(v -> supportActions.showSupportDialog());
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
        // v5.7：说清应用锁的边界——它只是界面锁，数据库文件并未加密
        b.tvAppLockState.setText(com.bai.bookshelf.util.AppLock.isEnabled(requireContext())
                ? "当前：已开启（只锁界面，藏书数据未加密）"
                : "未开启（只锁界面，藏书数据未加密）");
        b.tvAboutState.setText("v" + BuildConfig.VERSION_NAME + " · 作者 BaiDuRen");
        // v4.19：只有真的崩过才显示「崩溃日志」入口
        boolean has = com.bai.bookshelf.util.CrashHandler.hasLog(requireContext());
        b.rowCrashLog.setVisibility(has ? android.view.View.VISIBLE : android.view.View.GONE);
        b.dividerCrash.setVisibility(has ? android.view.View.VISIBLE : android.view.View.GONE);
        dataActions.refreshRows();
    }

    /** 应用锁开关（v4.54）：用手机已有的指纹 / 面容 / 锁屏密码验证，不另设应用密码 */
    private void toggleAppLock() {
        android.content.Context ctx = requireContext();
        if (com.bai.bookshelf.util.AppLock.isEnabled(ctx)) {
            new MaterialAlertDialogBuilder(ctx)
                    .setTitle("关闭应用锁")
                    .setMessage("关闭后，进入小书房将不再需要指纹或密码。")
                    .setPositiveButton("关闭", (d, w) -> {
                        com.bai.bookshelf.util.AppLock.setEnabled(ctx, false);
                        refreshStateRows();
                        Toast.makeText(ctx, "应用锁已关闭", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        if (!com.bai.bookshelf.util.AppLock.canAuthenticate(ctx)) {
            new MaterialAlertDialogBuilder(ctx)
                    .setTitle("先给手机设置锁屏")
                    .setMessage("应用锁使用手机的指纹 / 面容或锁屏密码验证。"
                            + "请先在系统设置里设置一种锁屏方式。")
                    .setPositiveButton("去设置", (d, w) -> {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_SECURITY_SETTINGS));
                        } catch (Exception e) {
                            Toast.makeText(ctx, "无法打开系统设置", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        com.bai.bookshelf.util.AppLock.setEnabled(ctx, true);
        refreshStateRows();
        String how = com.bai.bookshelf.util.AppLock.hasBiometric(ctx)
                ? "指纹 / 面容或手机密码" : "手机锁屏密码";
        Toast.makeText(ctx, "应用锁已开启：离开 1 分钟后回来需用" + how + "解锁",
                Toast.LENGTH_LONG).show();
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
        tv.setTextColor(ThemeManager.resolveColor(requireContext(), R.attr.on_surface));
        tv.setPadding(dp(20), dp(16), dp(20), dp(16));
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("崩溃日志")
                .setView(sv)
                .setPositiveButton("复制", (d, w) -> supportActions.copyToClipboard(log, "崩溃日志已复制"))
                .setNeutralButton("清空", (d, w) -> {
                    com.bai.bookshelf.util.CrashHandler.clearLog(requireContext());
                    refreshStateRows();
                    Toast.makeText(requireContext(), "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.action_cancel, null)
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
        // v4.23：切回本页时同步刷新状态行。原先只刷概览计数——
        // 冷启动后自动备份在后台线程写 zip，晚于 onViewCreated 的
        // 首次计算，而 show/hide 切 Tab 不触发 onResume，
        // 「备份文件管理」副标题会一直停留成「暂无备份」。
        refreshStateRows();
    }

    /** 概览：藏书 / 已读 / 在读 + 已读比例 + 地点/来源/标签数量（v4.24 hero 区） */
    private void loadSummary() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            int bookCount = 0;
            int readCount = 0;
            int readingCount = 0;
            int locCount = 0;
            int channelUsedCount = 0;
            int tagCount = 0;
            int copyCount = 0;
            try {
                List<Book> books = app.db().bookDao().getAll();
                // v5.8 口径：种数排除「已归还借阅（电子书签）」，册数按逐本副本累加
                bookCount = com.bai.bookshelf.util.BookFlags.countOwned(books);
                copyCount = com.bai.bookshelf.util.LibraryStats.countOwnedCopies(books,
                        com.bai.bookshelf.util.LibraryStats.groupCopies(
                                app.db().bookCopyDao().getAll()));
                for (Book bk : books) {
                    if (bk.readStatus == 2) {
                        readCount++;
                    } else if (bk.readStatus == 1) {
                        readingCount++;
                    }
                }
                locCount = app.db().locationDao().count();
                tagCount = app.db().tagDao().count();
                // 渠道统计藏书里真正用到的来源（避免字典有 12 个被误读成用了 12 个）
                java.util.Set<String> used = new java.util.HashSet<>();
                for (Book bk : books) {
                    String ch = bk.sourceChannel == null ? "" : bk.sourceChannel.trim();
                    if (!ch.isEmpty()) {
                        used.add(ch);
                    }
                }
                channelUsedCount = used.size();
            } catch (Exception ignored) {
                // 单项查询失败时保持 0，概览不阻塞其它入口
            }
            final int fBooks = bookCount;
            final int fCopies = copyCount;
            final int fRead = readCount;
            final int fReading = readingCount;
            final int fLoc = locCount;
            final int fChannelUsed = channelUsedCount;
            final int fTag = tagCount;
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                b.tvHeroSummary.setText("藏书 " + fBooks + " 种"
                        + (fCopies == fBooks ? "" : " · " + fCopies + " 册")
                        + " · 已读 " + fRead + " · 在读 " + fReading);
                b.tvHeroLocations.setText(String.valueOf(fLoc));
                b.tvHeroChannels.setText(String.valueOf(fChannelUsed));
                b.tvHeroTags.setText(String.valueOf(fTag));
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

    // ======================= 主题颜色（v2.5） =======================

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
