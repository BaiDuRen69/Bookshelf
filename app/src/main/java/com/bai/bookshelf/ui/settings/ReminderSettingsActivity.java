package com.bai.bookshelf.ui.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.ActivityReminderSettingsBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.BorrowReminder;
import com.bai.bookshelf.util.ReminderPrefs;
import com.bai.bookshelf.util.UpdateChecker;

/**
 * 「提醒与角标」设置页（v5.8.32）：六个开关收在一页，全部默认开（= 本轮之前的行为）。
 *
 * <p>关掉借阅到期通知时不只「以后不发」：立刻 cancel 已发出的通知并撤掉每日闹钟，
 * 否则用户按了开关却还会被旧通知打扰。
 */
public class ReminderSettingsActivity extends BaseActivity {

    private ActivityReminderSettingsBinding b;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityReminderSettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());

        b.rowNotifyBorrowDue.setOnClickListener(v -> toggle(ReminderPrefs.KEY_NOTIFY_BORROW_DUE,
                !ReminderPrefs.notifyBorrowDue(this)));
        b.rowNotifyBorrowedIn.setOnClickListener(v -> toggle(ReminderPrefs.KEY_NOTIFY_BORROWED_IN,
                !ReminderPrefs.notifyBorrowedIn(this)));
        b.rowBadgeOverdue.setOnClickListener(v -> toggle(ReminderPrefs.KEY_BADGE_OVERDUE,
                !ReminderPrefs.badgeOverdue(this)));
        b.rowBannerBackupStale.setOnClickListener(v -> toggle(ReminderPrefs.KEY_BANNER_BACKUP_STALE,
                !ReminderPrefs.bannerBackupStale(this)));
        b.rowWidgetEnabled.setOnClickListener(v -> toggle(ReminderPrefs.KEY_WIDGET_ENABLED,
                !ReminderPrefs.widgetEnabled(this)));
        // 这个开关沿用 UpdateChecker 的 update_check/start_check，不再另立键
        b.rowCheckUpdateOnLaunch.setOnClickListener(v -> {
            boolean now = !UpdateChecker.isStartCheckEnabled(this);
            UpdateChecker.setStartCheckEnabled(this, now);
            refresh();
            toast(now ? "已开启：打开 App 时自动检查新版本" : "已关闭：可手动检查更新");
        });
        b.btnNotifySettings.setOnClickListener(v -> openNotificationSettings());
        refresh();
    }

    private void toggle(String key, boolean value) {
        ReminderPrefs.setOn(this, key, value);
        if (ReminderPrefs.KEY_NOTIFY_BORROW_DUE.equals(key)) {
            // 关掉要立刻收掉通知与闹钟；重新打开要马上把闹钟排回去，
            // 否则要等到下一次借出/启动才恢复提醒
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
        b.tvNotifyBorrowDueState.setText(state(ReminderPrefs.notifyBorrowDue(this),
                "借出的书到期或逾期时发通知"));
        b.tvNotifyBorrowedInState.setText(state(ReminderPrefs.notifyBorrowedIn(this),
                "从别人那借的书到期时也提醒"));
        b.tvBadgeOverdueState.setText(state(ReminderPrefs.badgeOverdue(this),
                "底部统计图标上显示逾期本数"));
        b.tvBannerBackupStaleState.setText(state(ReminderPrefs.bannerBackupStale(this),
                "「我的」页超过 30 天没备份时提示"));
        b.tvWidgetEnabledState.setText(state(ReminderPrefs.widgetEnabled(this),
                "桌面小组件跟随数据刷新"));
        b.tvCheckUpdateState.setText(state(UpdateChecker.isStartCheckEnabled(this),
                "打开 App 时自动检查新版本"));
        refreshPermission();
    }

    private static String state(boolean on, String desc) {
        return desc + "\n" + (on ? ON : OFF);
    }

    private void refreshPermission() {
        boolean granted = BorrowReminder.notificationsEnabled(this);
        b.tvNotifyPermissionState.setText(granted
                ? "系统通知已允许，到期提醒会正常送达。"
                : "系统通知被关掉了——即使这里的开关是开的，通知也送不到。"
                        + "（Android 13 起通知是运行时权限）");
        b.btnNotifySettings.setVisibility(granted ? android.view.View.GONE
                : android.view.View.VISIBLE);
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
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show();
    }
}
