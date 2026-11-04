package com.bai.bookshelf.ui.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.databinding.ActivityReminderSettingsBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.BorrowReminder;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ReminderPrefs;
import com.bai.bookshelf.util.UpdateChecker;

import java.util.ArrayList;
import java.util.List;

/**
 * 「通知与提醒」设置页（v5.8.33 重做）。
 *
 * <p>三件事：每行右侧是真开关（行本身也能点，开关只做状态显示，避免一次点击两处响应）；
 * 副标题写「关掉之后会怎样」而不是「已开启」；顶部一行摘要说清现在有几本书到期、
 * 通知到底有没有授权、闹钟还排不排——用户按开关时最想知道的就是这些。
 *
 * <p>关掉「借阅到期通知」必须马上撤掉已发出的通知与每日闹钟（不许退化成「以后不发」），
 * 重新打开则立刻把闹钟排回去并补一次核对。
 */
public class ReminderSettingsActivity extends BaseActivity {

    private ActivityReminderSettingsBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityReminderSettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());

        bindRow(b.rowNotifyBorrowDue, b.swNotifyBorrowDue, ReminderPrefs.KEY_NOTIFY_BORROW_DUE);
        bindRow(b.rowNotifyBorrowedIn, b.swNotifyBorrowedIn,
                ReminderPrefs.KEY_NOTIFY_BORROWED_IN);
        bindRow(b.rowBadgeOverdue, b.swBadgeOverdue, ReminderPrefs.KEY_BADGE_OVERDUE);
        bindRow(b.rowBannerBackupStale, b.swBannerBackupStale,
                ReminderPrefs.KEY_BANNER_BACKUP_STALE);
        bindRow(b.rowBannerBorrowDue, b.swBannerBorrowDue,
                ReminderPrefs.KEY_BANNER_BORROW_DUE);
        bindRow(b.rowWidgetEnabled, b.swWidgetEnabled, ReminderPrefs.KEY_WIDGET_ENABLED);
        // 「启动时检查应用更新」沿用 UpdateChecker 的 update_check/start_check，不另立键
        b.rowCheckUpdateOnLaunch.setOnClickListener(v -> {
            boolean now = !UpdateChecker.isStartCheckEnabled(this);
            UpdateChecker.setStartCheckEnabled(this, now);
            refresh();
            toast(now ? "已开启：打开 App 时自动检查新版本" : "已关闭：可在「应用更新」手动检查");
        });
        b.btnNotifySettings.setOnClickListener(v -> openNotificationSettings());
        refresh();
    }

    /** 行是唯一的点击目标；开关只呈现状态（clickable/focusable 已在布局里关掉，避免一次点击两处响应） */
    private void bindRow(View row,
                         com.google.android.material.materialswitch.MaterialSwitch sw,
                         String key) {
        row.setOnClickListener(v -> toggle(key, !ReminderPrefs.isOn(this, key)));
    }

    private void toggle(String key, boolean value) {
        ReminderPrefs.setOn(this, key, value);
        if (ReminderPrefs.KEY_NOTIFY_BORROW_DUE.equals(key)) {
            // 关掉：撤通知 + 撤闹钟；打开：立刻把闹钟排回去，别等下次借出或重启
            if (value) {
                BorrowReminder.schedule(this);
                BorrowReminder.checkAndNotify(this);
            } else {
                BorrowReminder.cancelDueReminder(this);
            }
        }
        refresh();
        toast(value ? ON : OFF);
    }

    private static final String ON = "已开启";
    private static final String OFF = "已关闭";

    private void refresh() {
        b.swNotifyBorrowDue.setChecked(ReminderPrefs.notifyBorrowDue(this));
        b.swNotifyBorrowedIn.setChecked(ReminderPrefs.notifyBorrowedIn(this));
        b.swBadgeOverdue.setChecked(ReminderPrefs.badgeOverdue(this));
        b.swBannerBackupStale.setChecked(ReminderPrefs.bannerBackupStale(this));
        b.swBannerBorrowDue.setChecked(ReminderPrefs.bannerBorrowDue(this));
        b.swWidgetEnabled.setChecked(ReminderPrefs.widgetEnabled(this));
        b.swCheckUpdate.setChecked(UpdateChecker.isStartCheckEnabled(this));
        b.rowNotifyBorrowDue.setContentDescription("借阅到期通知，"
                + state(ReminderPrefs.notifyBorrowDue(this)));
        b.rowBadgeOverdue.setContentDescription("逾期角标，"
                + state(ReminderPrefs.badgeOverdue(this)));
        b.rowBannerBorrowDue.setContentDescription("详情页到期条，"
                + state(ReminderPrefs.bannerBorrowDue(this)));
        refreshPermission();
        refreshSummary();
    }

    private static String state(boolean on) {
        return on ? ON : OFF;
    }

    private void refreshPermission() {
        boolean granted = BorrowReminder.notificationsEnabled(this);
        b.btnNotifySettings.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    /** 摘要：逾期 / 今天到期 / 通知授权 / 闹钟，全部来自已有查询，不新增 DAO 方法 */
    private void refreshSummary() {
        b.tvReminderSummary.setText("正在统计…");
        BookshelfApp app = BookshelfApp.get();
        if (app == null) {
            return;
        }
        app.io(() -> {
            int[] out = new int[]{0, 0};
            int[] in = new int[]{0, 0};
            try {
                List<BorrowRecord> open = app.db().borrowRecordDao().getAllOpen();
                out = BorrowReminder.countDue(open);
                List<Book> dueIn = app.db().bookDao()
                        .getBorrowedInDue(DateKeys.formatKey(DateKeys.todayKey()));
                in = BorrowReminder.countBorrowedInDue(dueIn);
            } catch (Exception ignored) {
                // 库还没就绪时摘要给个保守说法，别把页面卡住
            }
            final int dueToday = out[0];
            final int overdue = out[1];
            final int inOverdue = in[1];
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                StringBuilder sb = new StringBuilder("现在：逾期 ").append(overdue)
                        .append(" 本 · 今天到期 ").append(dueToday).append(" 本");
                if (inOverdue > 0) {
                    sb.append(" · 借入逾期 ").append(inOverdue).append(" 本");
                }
                sb.append(BorrowReminder.notificationsEnabled(this)
                        ? " · 通知已授权" : " · 通知未授权（系统里被关了）");
                sb.append(ReminderPrefs.notifyBorrowDue(this)
                        ? " · 明早 9:00 检查" : " · 闹钟已取消");
                b.tvReminderSummary.setText(sb);
            });
        });
    }

    private void openNotificationSettings() {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null));
            }
            startActivity(intent);
        } catch (Exception e) {
            toast("这台机器没有通知设置页，请到系统设置里找");
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
