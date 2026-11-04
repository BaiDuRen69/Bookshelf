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
        // v5.8.32：总开关关掉时闹钟也一并撤掉，不能只「以后不发」
        if (!ReminderPrefs.notifyBorrowDue(context)) {
            am.cancel(pi);
            return;
        }
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, 9);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_MONTH, 1);
        }
        am.cancel(pi);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, BorrowReminderReceiver.class).setAction(ACTION_CHECK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
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
            int[] borrowedIn;
            try {
                counts = countDue(app.db().borrowRecordDao().getAllOpen());
                borrowedIn = countBorrowedInDue(app.db().bookDao()
                        .getBorrowedInDue(DateKeys.formatKey(DateKeys.todayKey())));
            } catch (Throwable t) {
                android.util.Log.w("BorrowReminder", "check due failed", t);
                return;
            }
            if (counts[0] + counts[1] + borrowedIn[0] + borrowedIn[1] > 0) {
                app.ui(() -> notify(ctx, counts[0], counts[1], borrowedIn[0], borrowedIn[1]));
            }
        });
    }

    /** @return {今天到期数, 逾期数} */
    public static int[] countDue(List<BorrowRecord> open) {
        int today = DateKeys.todayKey();
        int dueToday = 0;
        int overdue = 0;
        if (open == null) {
            return new int[]{0, 0};
        }
        for (BorrowRecord r : open) {
            int key = DateKeys.parseKey(r.dueDate);
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

    /** 来源为借阅、尚未归还的书：{今天到期数, 逾期数} */
    public static int[] countBorrowedInDue(List<Book> books) {
        int today = DateKeys.todayKey();
        int dueToday = 0;
        int overdue = 0;
        if (books == null) {
            return new int[]{0, 0};
        }
        for (Book book : books) {
            int key = DateKeys.parseKey(book.borrowDueDate);
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

    public static void notify(Context ctx, int dueToday, int overdue,
                              int borrowedInToday, int borrowedInOverdue) {
        // v5.8.32：两个开关分别管「借出通知」与「借入那半句话」
        if (!ReminderPrefs.notifyBorrowDue(ctx)) {
            return;
        }
        if (!ReminderPrefs.notifyBorrowedIn(ctx)) {
            borrowedInToday = 0;
            borrowedInOverdue = 0;
        }
        if (dueToday + overdue + borrowedInToday + borrowedInOverdue <= 0) {
            return;
        }
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
            channel.setDescription("借出/借入的书到期或逾期时提醒");
            nm.createNotificationChannel(channel);
        }
        // v5.8.32：正文与底部角标口径对齐——角标只数「借出逾期」，这里第一行也是它
        StringBuilder text = new StringBuilder();
        if (overdue > 0 || dueToday > 0) {
            text.append("借出：逾期 ").append(overdue)
                    .append(" · 今天到期 ").append(dueToday);
        }
        if (borrowedInOverdue > 0 || borrowedInToday > 0) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append("借入：逾期 ").append(borrowedInOverdue)
                    .append(" · 今天到期 ").append(borrowedInToday);
        }
        String body = text.toString();
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_LOANS, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(ctx, REQUEST_CODE, open, flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle("该还书了")
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
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

    /**
     * 立刻收掉已发出的到期通知与每日闹钟（v5.8.32）：
     * 关掉「借阅到期通知」不能只是「以后不发」，旧通知留在通知栏里会继续打扰。
     */
    public static void cancelDueReminder(Context ctx) {
        Context app = ctx.getApplicationContext();
        try {
            NotificationManagerCompat.from(app).cancel(NOTIFY_ID);
        } catch (Throwable ignored) {
        }
        try {
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pendingIntent(app));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 已经没有任何逾期 / 今天到期时收掉通知（删除、归还、收回、解锁之后各调一次）。
     * 闹钟保留：后面还有别的书要到期。
     */
    public static void cancelIfClear(Context ctx) {
        BookshelfApp app = BookshelfApp.get();
        if (app == null) {
            return;
        }
        Context c = ctx.getApplicationContext();
        app.io(() -> {
            int[] counts;
            int[] borrowedIn;
            try {
                counts = countDue(app.db().borrowRecordDao().getAllOpen());
                borrowedIn = countBorrowedInDue(app.db().bookDao()
                        .getBorrowedInDue(DateKeys.formatKey(DateKeys.todayKey())));
            } catch (Throwable t) {
                return;
            }
            if (counts[0] + counts[1] + borrowedIn[0] + borrowedIn[1] == 0) {
                try {
                    NotificationManagerCompat.from(c).cancel(NOTIFY_ID);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 生成催还文案（借阅人有联系方式时附上称呼） */
    public static String reminderText(Book book, BorrowRecord record, String phone) {
        String title = com.bai.bookshelf.util.BookText.bareTitle(book == null ? "" : book.title);
        StringBuilder sb = new StringBuilder();
        sb.append("《").append(title).append("》");
        int due = DateKeys.parseKey(record.dueDate);
        int today = DateKeys.todayKey();
        if (due > 0 && due < today) {
            sb.append("已经逾期 ").append(DateKeys.daysBetween(due, today)).append(" 天");
        } else if (due == today) {
            sb.append("今天到期");
        } else if (due > 0) {
            sb.append("还有 ").append(DateKeys.daysBetween(today, due)).append(" 天到期");
        }
        sb.append("，看完方便还给我吗？谢谢！");
        return sb.toString();
    }
}
