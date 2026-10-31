package com.bai.bookshelf.ui.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.util.BookFlags;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ReadingGoal;

import java.util.Calendar;
import java.util.List;

/**
 * 桌面小组件 2×2（v5.8.8）：在读一本 + 年度目标进度 + 今日到期。
 *
 * <p>数据直接从 Room 查（小组件进程就是应用进程），刷新时机：系统 onUpdate（半小时）、
 * 应用启动、以及应用回到前台时主动 refresh。点击整块打开小书房。
 */
public class ReadingWidgetProvider extends AppWidgetProvider {

    /** 小组件要展示的一屏内容（抽成纯数据，方便真机测试直接断言） */
    public static class Snapshot {
        public String reading;      // 「在读：xxx」或「书架空着」
        public String goal;         // 「今年 12 / 24 种」
        public int progress;        // 0~100
        public String due;          // 「今天到期 1 本」
        public boolean dueVisible;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        final Context appCtx = context.getApplicationContext();
        BookshelfApp.get().io(() -> {
            Snapshot s = snapshot(appCtx);
            RemoteViews views = build(appCtx, s);
            for (int id : ids) {
                manager.updateAppWidget(id, views);
            }
        });
    }

    /** 应用侧调用：数据变化后刷新所有已添加的小组件 */
    public static void refresh(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(context, ReadingWidgetProvider.class));
            if (ids == null || ids.length == 0) {
                return;
            }
            BookshelfApp app = BookshelfApp.get();
            app.io(() -> {
                Snapshot s = snapshot(context);
                RemoteViews views = build(context, s);
                for (int id : ids) {
                    manager.updateAppWidget(id, views);
                }
            });
        } catch (Exception ignored) {
            // 小组件刷新失败不影响主流程
        }
    }

    /** 读库组装一屏数据（不碰 UI，可直接在真机测试里断言） */
    public static Snapshot snapshot(Context ctx) {
        Snapshot s = new Snapshot();
        BookshelfApp app = BookshelfApp.get();
        List<Book> books = BookSanitizer.normalizeAll(app.db().bookDao().getAll());
        Book reading = null;
        int finishedThisYear = 0;
        int year = Calendar.getInstance().get(Calendar.YEAR);
        for (Book b : books) {
            if (b.readStatus == Book.READING) {
                if (reading == null || b.updatedAt > reading.updatedAt) {
                    reading = b;
                }
            }
            if (b.readStatus != Book.READ_DROPPED && !b.readingFinishDate.isEmpty()
                    && DateKeys.parseKey(b.readingFinishDate) / 10000 == year) {
                finishedThisYear++;
            }
        }
        s.reading = reading == null
                ? "书架空着，去加一本吧"
                : "在读：" + BookText.displayTitle(reading.title);
        int goal = ReadingGoal.current(ctx);
        s.goal = "今年 " + finishedThisYear + " / " + goal + " 种";
        s.progress = Math.min(100, finishedThisYear * 100 / Math.max(1, goal));

        int today = DateKeys.todayKey();
        int dueToday = 0;
        try {
            for (BorrowRecord r : app.db().borrowRecordDao().getAllOpen()) {
                if (DateKeys.parseKey(r.dueDate) == today) {
                    dueToday++;
                }
            }
        } catch (Exception ignored) {
            // 借阅表异常时只隐藏这一行
        }
        s.dueVisible = dueToday > 0;
        s.due = "今天到期 " + dueToday + " 本";
        return s;
    }

    /** 组装 RemoteViews（点击整块打开 App） */
    public static RemoteViews build(Context ctx, Snapshot s) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_reading);
        views.setTextViewText(R.id.widget_reading, s.reading);
        views.setTextViewText(R.id.widget_goal, s.goal);
        views.setProgressBar(R.id.widget_progress, 100, s.progress, false);
        views.setViewVisibility(R.id.widget_due, s.dueVisible ? android.view.View.VISIBLE
                : android.view.View.GONE);
        if (s.dueVisible) {
            views.setTextViewText(R.id.widget_due, s.due);
        }
        Intent open = new Intent(ctx, com.bai.bookshelf.ui.MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        return views;
    }
}
