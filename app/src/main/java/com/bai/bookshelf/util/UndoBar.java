package com.bai.bookshelf.util;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.snackbar.Snackbar;

/**
 * 撤销条（v5.8.16）：改动先落库，8 秒内可撤销；窗口结束后再跑「收尾」
 * （删文件这类不可逆动作必须等窗口过去，否则撤销回来的书会丢封面与图片）。
 *
 * <p>详情页删书时页面正在关闭、拿不到锚点视图：先 {@link #offer} 存着，
 * 回到书架由 {@link #attach} 补弹。同一时刻只保留一条待撤销操作，
 * 新的 offer 会先把上一条的收尾执行掉；万一一直没弹出去（用户切走了别的页），
 * 兜底定时器也会按时跑收尾，不留悬空文件。
 */
public final class UndoBar {

    /** 撤销窗口（毫秒）：Snackbar 时长与兜底定时器共用这一个值 */
    public static final int WINDOW_MS = 8000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Pending pending;

    private static final class Pending {
        final String message;
        @Nullable
        final Runnable undo;
        @Nullable
        final Runnable finish;
        boolean shown;
        boolean settled;
        final Runnable fallback;

        Pending(String message, @Nullable Runnable undo, @Nullable Runnable finish) {
            this.message = message;
            this.undo = undo;
            this.finish = finish;
            this.fallback = () -> {
                // 弹出去了就交给 Snackbar 自己的回调，这里只兜「一直没弹」的情况
                if (!shown) {
                    settle(this);
                }
            };
        }
    }

    private UndoBar() {
    }

    /** 有锚点就立刻弹；没有（比如详情页正在关闭）先存着，等 attach 补弹 */
    public static void offer(@Nullable View anchor, String message,
                             @Nullable Runnable undo, @Nullable Runnable finish) {
        flush();
        Pending p = new Pending(message, undo, finish);
        pending = p;
        MAIN.postDelayed(p.fallback, WINDOW_MS + 1500L);
        if (anchor != null) {
            show(anchor, p);
        }
    }

    /** 页面可见时补弹（书架 onResume / 切回本页时调） */
    public static void attach(@Nullable View anchor) {
        if (pending == null || pending.shown || pending.settled) {
            return;
        }
        show(anchor, pending);
    }

    /** 当前是否有待撤销的操作（供页面决定是否要补弹） */
    public static boolean hasPending() {
        return pending != null && !pending.settled;
    }

    private static void show(@Nullable View anchor, Pending p) {
        if (anchor == null || p.shown || p.settled) {
            return;
        }
        p.shown = true;
        Snackbar bar = Snackbar.make(anchor, p.message, WINDOW_MS);
        if (p.undo != null) {
            bar.setAction("撤销", v -> {
                Runnable undo = p.undo;
                settle(p);
                undo.run();
            });
        }
        bar.addCallback(new Snackbar.Callback() {
            @Override
            public void onShown(Snackbar b) {
            }

            @Override
            public void onDismissed(Snackbar b, int event) {
                // 点「撤销」时已结算过；超时 / 滑走 / 被顶掉都算确认这次改动，跑收尾
                settle(p);
            }
        });
        bar.show();
    }

    private static void settle(Pending p) {
        if (p.settled) {
            return;
        }
        p.settled = true;
        MAIN.removeCallbacks(p.fallback);
        if (pending == p) {
            pending = null;
        }
        if (p.finish != null) {
            p.finish.run();
        }
    }

    /** 新操作进来时，先把上一条的收尾做掉（上一条视为已确认） */
    private static void flush() {
        if (pending != null) {
            settle(pending);
        }
    }
}
