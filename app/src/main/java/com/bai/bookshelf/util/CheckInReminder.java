package com.bai.bookshelf.util;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.ui.MainActivity;

import java.util.Calendar;

/**
 * 每日打卡提醒（第 4.4 步，默认关）：每天在用户设定的时刻（默认 21:00）
 * 发一条本地通知「今天还没打卡，N 本书在读」。闹钟/取消写法与 BorrowReminder 一致，
 * 但不改动借阅提醒的任何逻辑；通知渠道、通知 id、requestCode 全部独立。
 */
public final class CheckInReminder {

    private static final String CHANNEL_ID = "checkin_daily";
    private static final int NOTIFY_ID = 4002;
    private static final int REQUEST_CODE = 4002;
    public static final String ACTION_CHECK = "com.bai.bookshelf.action.CHECKIN_DAILY_CHECK";

    private CheckInReminder() {
    }

    /** 安排下一次提醒；开关关掉时把闹钟一并撤掉 */
    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pendingIntent(ctx);
        if (!ReminderPrefs.checkinDaily(ctx)) {
            am.cancel(pi);
            return;
        }
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, ReminderPrefs.checkinReminderHour(ctx));
        next.set(Calendar.MINUTE, ReminderPrefs.checkinReminderMinute(ctx));
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_MONTH, 1);
        }
        am.cancel(pi);
        // 不申请精确闹钟权限：Doze 下允许延迟，但不会像 setRepeating 那样整条丢失
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
    }

    private static PendingIntent pendingIntent(Context ctx) {
        Intent intent = new Intent(ctx, CheckInReminderReceiver.class).setAction(ACTION_CHECK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, flags);
    }

    /** 关掉时立刻收掉已发出的通知并取消闹钟，不只是「以后不发」 */
    public static void cancel(Context ctx) {
        Context c = ctx.getApplicationContext();
        try {
            NotificationManagerCompat.from(c).cancel(NOTIFY_ID);
        } catch (Throwable ignored) {
        }
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pendingIntent(c));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 查库并决定要不要发（io 线程 → 主线程），今天已打卡或没有在读书就静默 */
    public static void checkAndNotify(Context ctx) {
        BookshelfApp app = BookshelfApp.get();
        if (app == null || !ReminderPrefs.checkinDaily(ctx)) {
            return;
        }
        Context c = ctx.getApplicationContext();
        app.io(() -> {
            final boolean todayChecked;
            final int reading;
            try {
                todayChecked = !app.db().checkInDao()
                        .getByDay(DateKeys.todayKey()).isEmpty();
                int n = 0;
                for (Book b : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                    if (b.readStatus == Book.READING) {
                        n++;
                    }
                }
                reading = n;
            } catch (Throwable t) {
                return; // 库未就绪 / 修复中
            }
            if (!CheckInUiState.needDailyReminder(true, todayChecked, reading)) {
                return;
            }
            app.ui(() -> post(c, CheckInUiState.dailyReminderText(reading)));
        });
    }

    private static void post(Context ctx, String body) {
        if (!BorrowReminder.notificationsEnabled(ctx)) {
            return;
        }
        // 显式再查一次运行时权限：notificationsEnabled 里的检查 lint 不追踪
        if (Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(ctx);
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_CHECKIN);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(ctx, REQUEST_CODE, open, flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle("今天还没打卡")
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, builder.build());
        } catch (Throwable ignored) {
            // 没有 POST_NOTIFICATIONS 时静默跳过，设置页会引导去系统设置
        }
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "每日打卡提醒", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(channel);
    }
}
