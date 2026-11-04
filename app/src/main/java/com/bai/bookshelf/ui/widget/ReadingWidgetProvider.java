package com.bai.bookshelf.ui.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ReadingGoal;
import com.bai.bookshelf.util.ThemeManager;

import java.util.Calendar;
import java.util.List;

/**
 * 桌面小组件 2×2（v5.8.8）：在读一本 + 年度目标进度 + 到期提醒。
 *
 * <p>数据直接从 Room 查（小组件进程就是应用进程），刷新时机：系统 onUpdate（半小时）、
 * 应用启动、切换主题、以及应用回到前台时主动 refresh。点击整块打开小书房。
 *
 * <p>v5.8.14：RemoteViews 不能引用 {@code ?attr}，构建时用 {@link ThemeManager} 按当前
 * 主题解析颜色再 {@code setTextColor}；背景按主题换 drawable，进度条画成 Bitmap 塞进
 * ImageView（ProgressBar 的 progressTint 不在 RemoteViews 白名单里）。
 */
public class ReadingWidgetProvider extends AppWidgetProvider {

    /** 小组件要展示的一屏内容（抽成纯数据，方便真机测试直接断言） */
    public static class Snapshot {
        public String reading;      // 「在读：xxx」或「书架空着」
        public String goal;         // 「今年 12 / 24 种」
        public int progress;        // 0~100
        public String overdue;      // 「逾期 2 本」
        public boolean overdueVisible;
        public String due;          // 「今日到期 1 本」
        public boolean dueVisible;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        final Context appCtx = context.getApplicationContext();
        BookshelfApp.get().io(() -> {
            try {
                Snapshot s = snapshot(appCtx);
                RemoteViews views = build(appCtx, s);
                for (int id : ids) {
                    manager.updateAppWidget(id, views);
                }
            } catch (Exception ignored) {
                // 小组件刷新失败不影响主流程
            }
        });
    }

    /** 应用侧调用：数据变化后刷新所有已添加的小组件 */
    public static void refresh(Context context) {
        // v5.8.32：关掉开关后小组件停在最后一次刷新内容（不删除、不再跟着变）
        if (!com.bai.bookshelf.util.ReminderPrefs.widgetEnabled(context)) {
            return;
        }
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(context, ReadingWidgetProvider.class));
            if (ids == null || ids.length == 0) {
                return;
            }
            BookshelfApp app = BookshelfApp.get();
            app.io(() -> {
                try {
                    Snapshot s = snapshot(context);
                    RemoteViews views = build(context, s);
                    for (int id : ids) {
                        manager.updateAppWidget(id, views);
                    }
                } catch (Exception ignored) {
                    // 小组件刷新失败不影响主流程
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
        int overdue = 0;
        int dueToday = 0;
        try {
            for (BorrowRecord r : app.db().borrowRecordDao().getAllOpen()) {
                int due = DateKeys.parseKey(r.dueDate);
                if (due <= 0) {
                    continue;
                }
                if (due < today) {
                    overdue++;
                } else if (due == today) {
                    dueToday++;
                }
            }
        } catch (Exception ignored) {
            // 借阅表异常时只隐藏到期行
        }
        s.overdueVisible = overdue > 0;
        s.overdue = "逾期 " + overdue + " 本";
        s.dueVisible = dueToday > 0;
        s.due = "今日到期 " + dueToday + " 本";
        return s;
    }

    /** 组装 RemoteViews（点击整块打开 App） */
    public static RemoteViews build(Context ctx, Snapshot s) {
        int theme = ThemeManager.current(ctx);
        Context themed = new ContextThemeWrapper(ctx, ThemeManager.styleRes(theme));
        int primary = ThemeManager.resolveColor(themed, ThemeManager.ATTR_COLOR_PRIMARY);
        int text = ThemeManager.resolveColor(themed, R.attr.on_surface);
        int sub = ThemeManager.resolveColor(themed, R.attr.on_surface_variant);
        int track = ThemeManager.resolveColor(themed, R.attr.outline);
        int error = ContextCompat.getColor(ctx, theme == ThemeManager.THEME_NIGHT
                ? R.color.widget_error_night : R.color.widget_error);

        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_reading);
        views.setInt(R.id.widget_root, "setBackgroundResource", backgroundRes(theme));
        views.setTextColor(R.id.widget_brand, primary);
        views.setTextColor(R.id.widget_reading, text);
        views.setTextColor(R.id.widget_goal, sub);
        views.setTextColor(R.id.widget_percent, primary);
        views.setTextColor(R.id.widget_due, error);
        views.setTextColor(R.id.widget_due_today, sub);

        views.setTextViewText(R.id.widget_reading, s.reading);
        views.setTextViewText(R.id.widget_goal, s.goal);
        views.setTextViewText(R.id.widget_percent, s.progress + "%");
        views.setImageViewBitmap(R.id.widget_progress, progressBitmap(s.progress, track, primary));

        views.setViewVisibility(R.id.widget_due,
                s.overdueVisible ? View.VISIBLE : View.GONE);
        if (s.overdueVisible) {
            views.setTextViewText(R.id.widget_due, s.overdue);
        }
        views.setViewVisibility(R.id.widget_due_today,
                s.dueVisible ? View.VISIBLE : View.GONE);
        if (s.dueVisible) {
            views.setTextViewText(R.id.widget_due_today, s.due);
        }

        Intent open = new Intent(ctx, com.bai.bookshelf.ui.MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        return views;
    }

    /** 主题 id → 小组件底色 drawable（都是 surface + outline 的圆角纸面） */
    private static int backgroundRes(int theme) {
        switch (theme) {
            case ThemeManager.THEME_BLUE:
                return R.drawable.bg_widget_blue;
            case ThemeManager.THEME_RED:
                return R.drawable.bg_widget_red;
            case ThemeManager.THEME_ORANGE:
                return R.drawable.bg_widget_orange;
            case ThemeManager.THEME_PURPLE:
                return R.drawable.bg_widget_purple;
            case ThemeManager.THEME_GRAPHITE:
                return R.drawable.bg_widget_graphite;
            case ThemeManager.THEME_NIGHT:
                return R.drawable.bg_widget_night;
            case ThemeManager.THEME_GREEN:
            default:
                return R.drawable.bg_widget_green;
        }
    }

    /** 4dp 圆角进度条：ImageView 的 bitmap 方案，避开了 RemoteViews 不支持 tint 的限制 */
    private static Bitmap progressBitmap(int progress, int track, int fill) {
        final int width = 480;
        final int height = 24;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(track);
        canvas.drawRoundRect(0f, 0f, width, height, height / 2f, height / 2f, paint);
        int clamped = Math.max(0, Math.min(100, progress));
        if (clamped > 0) {
            float filled = Math.max(height, width * clamped / 100f);
            paint.setColor(fill);
            canvas.drawRoundRect(0f, 0f, filled, height, height / 2f, height / 2f, paint);
        }
        return bitmap;
    }
}
