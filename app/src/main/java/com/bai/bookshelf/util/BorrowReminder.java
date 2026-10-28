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
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.ui.MainActivity;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 借阅到期提醒（v4.42）。
 *
 * <p>每天 9:00 由 {@link AlarmManager} 拉起 {@link BorrowReminderReceiver}：
 * 查一遍未归还记录，把「今天到期」和「已逾期」的数量合成一条本地通知。
 * 没有通知权限（Android 13+ 用户拒绝 / 系统关闭）时静默跳过——
 * 书架的「超期」智能入口会给出同等信息的应用内角标，不依赖通知。
 */
public final class BorrowReminder {

    private static final String CHANNEL_ID = "borrow_due";
    private static final int NOTIFY_ID = 4001;
    private static final int REQUEST_CODE = 4001;
    public static final String ACTION_CHECK = "com.bai.bookshelf.action.BORROW_DUE_CHECK";

    private BorrowReminder() {
    }

    /** 安排下一次检查（每天 9:00；已过点则顺延到明天） */
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pendingIntent(context);
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, 9);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_MONTH, 1);
        }
        am.cancel(pi);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, BorrowReminderReceiver.class).setAction(ACTION_CHECK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }

    /** 查库并发通知（io 线程 → 主线程） */
    public static void checkAndNotify(Context context) {
        BookshelfApp app = BookshelfApp.get();
        if (app == null) {
            return;
        }
        Context ctx = context.getApplicationContext();
        app.io(() -> {
            int[] counts;
            try {
                counts = countDue(app.db().borrowRecordDao().getAllOpen());
            } catch (Throwable t) {
                android.util.Log.w("BorrowReminder", "check due failed", t);
                return;
            }
            if (counts[0] + counts[1] > 0) {
                app.ui(() -> notify(ctx, counts[0], counts[1]));
            }
        });
    }

    /** @return {今天到期数, 逾期数} */
    public static int[] countDue(List<BorrowRecord> open) {
        int today = todayKey();
        int dueToday = 0;
        int overdue = 0;
        if (open == null) {
            return new int[]{0, 0};
        }
        for (BorrowRecord r : open) {
            int key = parseKey(r.dueDate);
            if (key == 0) {
                continue;
            }
            if (key == today) {
                dueToday++;
            } else if (key < today) {
                overdue++;
            }
        }
        return new int[]{dueToday, overdue};
    }

    public static void notify(Context ctx, int dueToday, int overdue) {
        if (!notificationsEnabled(ctx)) {
            return;
        }
        // 显式再查一次运行时权限：notificationsEnabled 里的检查 lint 不追踪
        if (Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "借阅到期提醒", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("借出的书今天到期或已经逾期时提醒");
            nm.createNotificationChannel(channel);
        }
        StringBuilder text = new StringBuilder();
        if (overdue > 0) {
            text.append(overdue).append(" 本已逾期");
        }
        if (dueToday > 0) {
            if (text.length() > 0) {
                text.append("，");
            }
            text.append(dueToday).append(" 本今天到期");
        }
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(ctx, REQUEST_CODE, open, flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle("该还书了")
                .setContentText(text.toString())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text.toString()))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, builder.build());
        } catch (Throwable t) {
            android.util.Log.w("BorrowReminder", "post notification failed", t);
        }
    }

    public static boolean notificationsEnabled(Context ctx) {
        // Android 13+ POST_NOTIFICATIONS 是运行时权限，areNotificationsEnabled 不足以表达
        if (Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled();
    }

    /** 借出/归还后立刻刷新提醒与闹钟（有到期日的记录才发） */
    public static void refresh(Context ctx) {
        schedule(ctx);
        checkAndNotify(ctx);
    }

    /** 生成催还文案（借阅人有联系方式时附上称呼） */
    public static String reminderText(Book book, BorrowRecord record, String phone) {
        String title = com.bai.bookshelf.util.BookText.bareTitle(book == null ? "" : book.title);
        StringBuilder sb = new StringBuilder();
        sb.append("《").append(title).append("》");
        int due = parseKey(record.dueDate);
        int today = todayKey();
        if (due > 0 && due < today) {
            sb.append("已经逾期 ").append(daysBetween(due, today)).append(" 天");
        } else if (due == today) {
            sb.append("今天到期");
        } else if (due > 0) {
            sb.append("还有 ").append(daysBetween(today, due)).append(" 天到期");
        }
        sb.append("，看完方便还给我吗？谢谢！");
        return sb.toString();
    }

    // ==================== 日期工具（yyyyMMdd 整数键） ====================

    public static int todayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    /** "yyyy-MM-dd" → yyyyMMdd；解析失败返回 0 */
    public static int parseKey(String date) {
        return DateKeys.parseKey(date);
    }

    public static String formatKey(int key) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                key / 10000, key / 100 % 100, key % 100);
    }

    public static int daysBetween(int fromKey, int toKey) {
        Calendar a = Calendar.getInstance();
        a.set(fromKey / 10000, fromKey / 100 % 100 - 1, fromKey % 100, 0, 0, 0);
        Calendar b = Calendar.getInstance();
        b.set(toKey / 10000, toKey / 100 % 100 - 1, toKey % 100, 0, 0, 0);
        return (int) Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
    }
}
