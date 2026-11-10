package com.bai.bookshelf.ui.mine;

import com.bai.bookshelf.util.Typo;
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
import com.bai.bookshelf.ui.TabRefresh;
import com.bai.bookshelf.util.BackupManager;
import com.bai.bookshelf.util.BookFlags;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.ui.scan.ScanActivity;
import com.bai.bookshelf.BuildConfig;
import com.bai.bookshelf.util.ExcelExporter;
import com.bai.bookshelf.util.Motion;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 我的页：数据概览 + 分区功能入口（藏书管理 / 数据与备份 / 设置）。
 * v2.7 起按 Material Design 设置页规范分组：分区标题具体、高频入口置顶。
 */
public class MineFragment extends Fragment implements TabRefresh {

    private FragmentMineBinding b;
    /** 需要补全的藏书数（封面/分类/存放地点任一缺失），任务清单用 */
    private int incompleteCount;

    /** 任务卡动效：只在状态真正变化时播放，首次绑定直接落终态 */
    private final boolean[] taskIconDone = new boolean[4];
    private boolean taskIconBound;
    private int taskProgressShown = -1;
    private android.animation.ValueAnimator taskProgressAnim;
    /** 四项完成后的收起只跑一次（本次视图生命周期内） */
    private boolean tasksCollapsed;

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
        // v5.8.19：书摘导出 Markdown（zip 里按书 + 按标签 + 图片）
        b.rowExportMd.setOnClickListener(v -> exportMarkdown());
        // v5.9.5 第 2 项：一键导出最新整包到任意位置
        b.rowExportZip.setOnClickListener(v -> dataActions.exportZipNow());
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
        // 上手任务清单（v5.8.1）
        b.rowTaskBackup.setOnClickListener(v -> {
            if (!BackupManager.isEnabled(requireContext())) {
                BackupManager.setEnabled(requireContext(), true);
                BackupManager.autoBackupIfEnabled(requireContext());
                Toast.makeText(requireContext(), "自动备份已开启（每天最多一次）",
                        Toast.LENGTH_SHORT).show();
            }
            dataActions.refreshRows();
            refreshTasks();
        });
        b.rowTaskFolder.setOnClickListener(v -> dataActions.pickBackupFolder());
        b.rowTaskGoal.setOnClickListener(v ->
                com.bai.bookshelf.ui.stats.ReadingGoalDialog.show(requireContext(), this::refreshTasks));
        b.rowTaskInfo.setOnClickListener(v -> startActivity(new android.content.Intent(
                requireContext(), IncompleteBooksActivity.class)));
        b.tvTaskSkipFolder.setOnClickListener(v -> {
            requireContext().getSharedPreferences("ui_prefs", 0).edit()
                    .putBoolean("task_skip_folder", true).apply();
            refreshTasks();
        });
        b.btnTaskClose.setOnClickListener(v -> {
            requireContext().getSharedPreferences("ui_prefs", 0).edit()
                    .putBoolean("task_list_dismissed", true).apply();
            refreshTasks();
        });
        dataActions.attach(b);
        dataActions.refreshRows();
        // 应用更新（v4.7）：启动检查开关 + 手动检查（v5.8.33 开关归回本组）
        b.rowUpdateToggle.setOnClickListener(v -> toggleUpdateCheck());
        b.rowCheckUpdate.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "正在检查更新…", Toast.LENGTH_SHORT).show();
            com.bai.bookshelf.util.UpdateChecker.checkManually(requireActivity());
        });
        b.rowReminderSettings.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                com.bai.bookshelf.ui.settings.ReminderSettingsActivity.class)));
        b.rowNavSettings.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                com.bai.bookshelf.ui.settings.NavSettingsActivity.class)));
        // v4.15：API 设置从弹窗改为二级页（API 配置 / 用量查询 分开）
        b.rowApi.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                ApiSettingsActivity.class)));
        b.rowSplit.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                SplitActivity.class)));
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
        Typo.text(tv, R.dimen.text_caption);
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
        renderCompanion();
        loadSummary();
        // v4.23：切回本页时同步刷新状态行。原先只刷概览计数——
        // 冷启动后自动备份在后台线程写 zip，晚于 onViewCreated 的
        // 首次计算，而 show/hide 切 Tab 不触发 onResume，
        // 「备份文件管理」副标题会一直停留成「暂无备份」。
        refreshStateRows();
    }

    /** 缺封面 / 分类 / 存放地点的藏书数（与「书籍信息补全」页同一口径） */
    private static int countIncomplete(List<Book> books) {
        int n = 0;
        for (Book bk : books) {
            if (!BookFlags.countsAsOwned(bk)) {
                continue;
            }
            if (bk.coverUrl.trim().isEmpty() || bk.category.trim().isEmpty()
                    || bk.locationId == null) {
                n++;
            }
        }
        return n;
    }

    /** 顶部任务清单（v5.8.1）：开备份 / 选文件夹 / 设目标 / 补信息，全做完卡片消失 */
    private void refreshTasks() {
        if (b == null) {
            return;
        }
        android.content.Context ctx = requireContext();
        android.content.SharedPreferences prefs =
                ctx.getSharedPreferences("ui_prefs", 0);
        boolean dismissed = prefs.getBoolean("task_list_dismissed", false);
        boolean backup = BackupManager.isEnabled(ctx);
        boolean folder = BackupManager.getTreeUri(ctx) != null
                || prefs.getBoolean("task_skip_folder", false);
        boolean goal = prefs.getBoolean("reading_goal_set", false);
        // 老用户迁移：这个版本之前设过自定义目标（不是默认 24）就算已完成
        if (!goal) {
            int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            if (prefs.getInt("reading_goal_year", 0) == year
                    && prefs.getInt("reading_goal", 24) != 24) {
                prefs.edit().putBoolean("reading_goal_set", true).apply();
                goal = true;
            }
        }
        boolean info = incompleteCount == 0;
        int done = (backup ? 1 : 0) + (folder ? 1 : 0) + (goal ? 1 : 0) + (info ? 1 : 0);
        boolean firstBind = !taskIconBound;
        animateTaskProgress(done);
        bindTask(b.ivTaskBackup, backup, 0);
        bindTask(b.ivTaskFolder, folder, 1);
        bindTask(b.ivTaskGoal, goal, 2);
        bindTask(b.ivTaskInfo, info, 3);
        b.tvTaskInfo.setText(info
                ? "封面 / 分类 / 存放地点都齐了"
                : "补全书的封面 / 分类 / 存放地点（还差 " + incompleteCount + " 种）");
        b.tvTaskSkipFolder.setVisibility(folder ? View.GONE : View.VISIBLE);
        if (dismissed) {
            tasksCollapsed = true;
            b.cardTasks.setVisibility(View.GONE);
        } else if (done >= 4) {
            // 首次打开就是已完成：直接收掉；本页停留期间刚完成才播放收起
            if (firstBind) {
                tasksCollapsed = true;
                b.cardTasks.setVisibility(View.GONE);
            } else {
                collapseTaskCard();
            }
        } else {
            b.cardTasks.setVisibility(View.VISIBLE);
        }
        taskIconBound = true;
    }

    private void bindTask(android.widget.ImageView icon, boolean done, int index) {
        boolean prev = taskIconDone[index];
        taskIconDone[index] = done;
        icon.setContentDescription(done ? "已完成" : "待完成");
        if (!taskIconBound) {
            icon.setAlpha(done ? 1f : 0.25f);
            return;
        }
        if (done == prev) {
            return;
        }
        icon.animate().cancel();
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        if (!com.bai.bookshelf.util.Motion.enabled(requireContext())) {
            icon.setAlpha(done ? 1f : 0.25f);
            return;
        }
        if (done) {
            icon.setAlpha(1f);
            icon.setScaleX(0.8f);
            icon.setScaleY(0.8f);
            icon.animate().scaleX(1f).scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                    .start();
        } else {
            icon.setAlpha(0.25f);
        }
    }

    private void animateTaskProgress(int done) {
        if (b == null) {
            return;
        }
        final TextView tv = b.tvTaskProgress;
        int from = taskProgressShown;
        if (taskProgressAnim != null && taskProgressAnim.isRunning() && from == done) {
            return;
        }
        taskProgressShown = done;
        if (from < 0 || from == done
                || !com.bai.bookshelf.util.Motion.enabled(requireContext())) {
            tv.setText(done + " / 4");
            return;
        }
        if (taskProgressAnim != null) {
            taskProgressAnim.cancel();
        }
        taskProgressAnim = android.animation.ValueAnimator.ofInt(from, done);
        taskProgressAnim.setDuration(300);
        taskProgressAnim.setInterpolator(com.bai.bookshelf.util.Motion.STANDARD);
        taskProgressAnim.addUpdateListener(a -> {
            if (b != null) {
                b.tvTaskProgress.setText(a.getAnimatedValue() + " / 4");
            }
        });
        taskProgressAnim.start();
    }

    /** 四项全完成的收起：alpha + 高度 220ms，结束再 GONE（全应用唯一动 layout 的地方） */
    private void collapseTaskCard() {
        if (tasksCollapsed || b == null) {
            return;
        }
        tasksCollapsed = true;
        final View card = b.cardTasks;
        if (!com.bai.bookshelf.util.Motion.enabled(requireContext())) {
            card.setVisibility(View.GONE);
            return;
        }
        card.post(() -> {
            if (b == null) {
                return;
            }
            int height = card.getHeight();
            if (height <= 0) {
                card.setVisibility(View.GONE);
                return;
            }
            final ViewGroup.LayoutParams lp = card.getLayoutParams();
            android.animation.ValueAnimator anim =
                    android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(220);
            anim.setInterpolator(com.bai.bookshelf.util.Motion.STANDARD);
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                lp.height = Math.round(height * (1f - f));
                card.setLayoutParams(lp);
                card.setAlpha(1f - f);
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (b == null) {
                        return;
                    }
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    card.setLayoutParams(lp);
                    card.setAlpha(1f);
                    card.setVisibility(View.GONE);
                }
            });
            anim.start();
        });
    }

    /** 概览：藏书 / 已读 / 在读 + 已读比例 + 地点/来源/标签数量（v4.24 hero 区） */
    private void loadSummary() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            int locCount = 0;
            int channelUsedCount = 0;
            int tagCount = 0;
            try {
                List<Book> books = app.db().bookDao().getAll();
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
                incompleteCount = countIncomplete(books);
            } catch (Exception ignored) {
                // 单项查询失败时保持 0，概览不阻塞其它入口
            }
            final int fLoc = locCount;
            final int fChannelUsed = channelUsedCount;
            final int fTag = tagCount;
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                b.tvHeroLocations.setText(String.valueOf(fLoc));
                b.tvHeroChannels.setText(String.valueOf(fChannelUsed));
                b.tvHeroTags.setText(String.valueOf(fTag));
                refreshTasks();
            });
        });
    }

    /** 「与小书房相伴 N 天」：首启时间在安装当天记录，所以同一天多次打开不跳变 */
    private void renderCompanion() {
        if (b == null) {
            return;
        }
        long first = BookshelfApp.firstLaunchAt();
        if (first <= 0) {
            b.tvHeroCompanion.setText("0 天");
            b.tvHeroCompanionSince.setText("首次启动时间未记录");
            return;
        }
        int days = DateKeys.daysSince(first, System.currentTimeMillis());
        b.tvHeroCompanion.setText(
                com.bai.bookshelf.ui.stats.StatsText.metric(String.valueOf(days), "天"));
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(first);
        b.tvHeroCompanionSince.setText(String.format(Locale.getDefault(),
                "自 %d年%d月%d日 起",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)));
        b.tvHeroCompanion.setContentDescription("与小书房相伴 " + days + " 天");
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

    /** 导出书摘 Markdown（v5.8.19）：生成 zip 后交给系统分享，用户自己决定存哪 / 发到哪 */
    private void exportMarkdown() {
        final android.content.Context appCtx = requireContext().getApplicationContext();
        Toast.makeText(appCtx, "正在生成书摘…", Toast.LENGTH_SHORT).show();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                java.util.List<com.bai.bookshelf.data.entity.Excerpt> excerpts =
                        app.db().excerptDao().getAll();
                if (excerpts.isEmpty()) {
                    app.ui(() -> Toast.makeText(appCtx, "还没有摘抄可导出",
                            Toast.LENGTH_SHORT).show());
                    return;
                }
                java.util.List<Book> books = app.db().bookDao().getAll();
                java.io.File dir = new java.io.File(appCtx.getFilesDir(), "share");
                if (!dir.exists() && !dir.mkdirs()) {
                    app.ui(() -> Toast.makeText(appCtx, "导出目录创建失败",
                            Toast.LENGTH_SHORT).show());
                    return;
                }
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmm",
                        java.util.Locale.US).format(new java.util.Date());
                java.io.File zip = new java.io.File(dir, "bookshelf_excerpts_" + stamp + ".zip");
                final com.bai.bookshelf.util.MarkdownExport.Result result =
                        com.bai.bookshelf.util.MarkdownExport.export(books, excerpts, zip);
                final android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        appCtx, appCtx.getPackageName() + ".fileprovider", zip);
                app.ui(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/zip");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_TEXT,
                            "小书房书摘：" + result.bookFiles + " 本 / " + result.excerptCount
                                    + " 条 / " + result.images + " 张图");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "导出书摘 Markdown"));
                    if (result.missingImages > 0) {
                        Toast.makeText(appCtx, result.missingImages
                                + " 张摘抄图片已不在本机，未打包", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                app.ui(() -> Toast.makeText(appCtx, "导出失败：" + message,
                        Toast.LENGTH_LONG).show());
            }
        });
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

    /** 开关「启动时检查应用更新」（v4.7；v5.8.32 曾挪走，v5.8.33 归回应用更新分组） */
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
                ? "冷启动时静默检查一次，有新版才提示" : "已关闭：只在手动检查时找新版本");
    }

    // ======================= 主题颜色（v2.5） =======================

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ==================== 切 Tab 内容层动效（v5.9.4） ====================

    @Override
    public void animateIn() {
        View content = b == null ? null : b.scrollMine;
        if (content == null) {
            return;
        }
        content.animate().cancel();
        content.setTranslationY(6 * getResources().getDisplayMetrics().density);
        content.animate().translationY(0f)
                .setDuration(Motion.BASE)
                .setInterpolator(Motion.STANDARD)
                .start();
    }

    @Override
    public void settleContent() {
        View content = b == null ? null : b.scrollMine;
        if (content == null) {
            return;
        }
        content.animate().cancel();
        content.setTranslationY(0f);
    }

    @Override
    public void onDestroyView() {
        if (taskProgressAnim != null) {
            taskProgressAnim.cancel();
            taskProgressAnim = null;
        }
        super.onDestroyView();
        b = null;
    }
}
