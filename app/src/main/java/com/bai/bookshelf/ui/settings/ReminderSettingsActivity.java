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

import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

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
        bindRow(b.rowNotifyCheckinDaily, b.swNotifyCheckinDaily,
                ReminderPrefs.KEY_NOTIFY_CHECKIN_DAILY);
        bindRow(b.rowNotifyBorrowedIn, b.swNotifyBorrowedIn,
                ReminderPrefs.KEY_NOTIFY_BORROWED_IN);
        bindRow(b.rowBadgeOverdue, b.swBadgeOverdue, ReminderPrefs.KEY_BADGE_OVERDUE);
        bindRow(b.rowBannerBackupStale, b.swBannerBackupStale,
                ReminderPrefs.KEY_BANNER_BACKUP_STALE);
        bindRow(b.rowBannerBorrowDue, b.swBannerBorrowDue,
                ReminderPrefs.KEY_BANNER_BORROW_DUE);
        bindRow(b.rowWidgetEnabled, b.swWidgetEnabled, ReminderPrefs.KEY_WIDGET_ENABLED);
        // v5.8.33：这里管的是「发现新版本要不要提示我」= UpdateChecker 的 update_check/never 取反，
        // 与更新弹窗里的「永不提示」同一个键；「启动时检查」留在「我的 → 应用更新」分组里
        b.rowPromptNewVersion.setOnClickListener(v -> {
            boolean nowOn = !UpdateChecker.isNeverPrompt(this);
            UpdateChecker.setNeverPrompt(this, !nowOn);
            refresh();
            toast(nowOn ? "已关闭：发现新版本不再弹窗，可手动检查"
                    : "已开启：发现新版本时提示我");
        });
        b.btnNotifySettings.setOnClickListener(v -> openNotificationSettings());
        b.rowCheckinReminderTime.setOnClickListener(v -> pickCheckinTime());
        refresh();
    }

    /** 行是唯一的点击目标；开关只呈现状态（clickable/focusable 已在布局里关掉，避免一次点击两处响应） */
    private void bindRow(View row,
                         com.google.android.material.materialswitch.MaterialSwitch sw,
                         String key) {
        row.setOnClickListener(v -> {
            // 系统通知未授权时，打卡提醒这行不切开关，直接引导去系统设置
            if (ReminderPrefs.KEY_NOTIFY_CHECKIN_DAILY.equals(key)
                    && !BorrowReminder.notificationsEnabled(this)) {
                openNotificationSettings();
                return;
            }
            toggle(key, !ReminderPrefs.isOn(this, key));
        });
    }

    private void toggle(String key, boolean value) {
        ReminderPrefs.setOn(this, key, value);
        if (ReminderPrefs.KEY_NOTIFY_CHECKIN_DAILY.equals(key)) {
            // 开：立刻按设定时刻排闹钟；关：撤通知 + 撤闹钟
            if (value) {
                com.bai.bookshelf.util.CheckInReminder.schedule(this);
            } else {
                com.bai.bookshelf.util.CheckInReminder.cancel(this);
            }
        }
        if (ReminderPrefs.KEY_NOTIFY_BORROW_DUE.equals(key)) {
            // 关掉：撤通知 + 撤闹钟；打开：立刻把闹钟排回去，别等下次借出或重启
            if (value) {
                BorrowReminder.schedule(this);
                BorrowReminder.checkAndNotify(this);
            } else {
                BorrowReminder.cancelDueReminder(this);
            }
        }
        if (ReminderPrefs.KEY_WIDGET_ENABLED.equals(key) && value) {
            // 重新打开后立刻补一次内容，不然停在关闭时的旧画面
            com.bai.bookshelf.ui.widget.ReadingWidgetProvider.refresh(this);
        }
        refresh();
        toast(value ? ON : OFF);
    }

    private static final String ON = "已开启";
    private static final String OFF = "已关闭";

    private void refresh() {
        b.swNotifyBorrowDue.setChecked(ReminderPrefs.notifyBorrowDue(this));
        b.swNotifyCheckinDaily.setChecked(ReminderPrefs.checkinDaily(this));
        b.swNotifyBorrowedIn.setChecked(ReminderPrefs.notifyBorrowedIn(this));
        b.swBadgeOverdue.setChecked(ReminderPrefs.badgeOverdue(this));
        b.swBannerBackupStale.setChecked(ReminderPrefs.bannerBackupStale(this));
        b.swBannerBorrowDue.setChecked(ReminderPrefs.bannerBorrowDue(this));
        b.swWidgetEnabled.setChecked(ReminderPrefs.widgetEnabled(this));
        b.swPromptNewVersion.setChecked(!UpdateChecker.isNeverPrompt(this));
        b.rowNotifyBorrowDue.setContentDescription("借阅到期通知，"
                + state(ReminderPrefs.notifyBorrowDue(this)));
        b.rowNotifyCheckinDaily.setContentDescription("每日打卡提醒，"
                + state(ReminderPrefs.checkinDaily(this)));
        b.rowBadgeOverdue.setContentDescription("逾期角标，"
                + state(ReminderPrefs.badgeOverdue(this)));
        b.rowBannerBorrowDue.setContentDescription("详情页到期条，"
                + state(ReminderPrefs.bannerBorrowDue(this)));
        b.rowPromptNewVersion.setContentDescription("发现新版本时提示我，"
                + state(!UpdateChecker.isNeverPrompt(this)));
        String time = ReminderPrefs.checkinReminderTimeText(this);
        b.tvCheckinReminderTime.setText(time);
        b.tvCheckinReminderTimeNote.setText("每天 " + time + " · 到点提醒当天还没打卡");
        b.tvCheckinDailyNote.setText("每天 " + time + " 提醒当天还没打卡；关掉后通知立即撤下、"
                + "闹钟取消。没有系统通知权限时需先去系统设置开启");
        refreshPermission();
        refreshSummary();
    }

    /** 24 小时制时刻选择：确定后立刻重排闹钟，不用等下次重启 */
    private void pickCheckinTime() {
        if (!ReminderPrefs.checkinDaily(this)) {
            toast("先打开「每日打卡提醒」，再设置时间");
            return;
        }
        if (!BorrowReminder.notificationsEnabled(this)) {
            openNotificationSettings();
            return;
        }
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(ReminderPrefs.checkinReminderHour(this))
                .setMinute(ReminderPrefs.checkinReminderMinute(this))
                .setTitleText("每日打卡提醒时间")
                .build();
        picker.addOnPositiveButtonClickListener(v -> {
            ReminderPrefs.setCheckinReminderTime(this, picker.getHour(), picker.getMinute());
            com.bai.bookshelf.util.CheckInReminder.schedule(this);
            refresh();
            toast("提醒时间已改为 " + ReminderPrefs.checkinReminderTimeText(this));
        });
        picker.show(getSupportFragmentManager(), "checkin_reminder_time");
    }

    private static String state(boolean on) {
        return on ? ON : OFF;
    }

    private void refreshPermission() {
        boolean granted = BorrowReminder.notificationsEnabled(this);
        b.btnNotifySettings.setVisibility(granted ? View.GONE : View.VISIBLE);
        // 第 4.4 步：未授权时打卡提醒置灰；行仍可点，bindRow 会引导去系统设置
        b.rowNotifyCheckinDaily.setAlpha(granted ? 1f : 0.5f);
        b.swNotifyCheckinDaily.setEnabled(granted);
        b.swNotifyCheckinDaily.setChecked(granted && ReminderPrefs.checkinDaily(this));
        // 提醒时间跟随开关：关掉（或系统通知被撤）时整行置灰
        boolean timeEnabled = granted && ReminderPrefs.checkinDaily(this);
        b.rowCheckinReminderTime.setAlpha(timeEnabled ? 1f : 0.5f);
        b.rowCheckinReminderTime.setContentDescription("提醒时间，"
                + ReminderPrefs.checkinReminderTimeText(this)
                + (timeEnabled ? "" : "，每日打卡提醒未开启"));
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
