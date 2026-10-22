package com.bai.bookshelf.util;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃兜底（v4.19）。
 *
 * <p><b>为什么需要：</b>用户反馈「很多地方一点就闪退」。闪退时系统只弹一个
 * 「应用已停止运行」的框，开发者拿不到任何堆栈，定位全靠猜。这里加一层
 * 全局兜底：把未捕获异常的完整堆栈写进 {@code files/crash.log}，
 * 用户可在「我的 → 设置 → 崩溃日志」里看到并复制出来反馈。
 *
 * <p><b>行为约定：</b>记录完日志后仍交给系统默认处理器（进程照常退出），
 * 不会「假装没崩」——应用状态已经不可信，硬撑反而会掩盖问题。
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    /** 崩溃日志文件名（放在应用私有目录，无需存储权限） */
    public static final String LOG_FILE = "crash.log";
    /** 日志最多保留的字符数，避免文件无限膨胀 */
    private static final int MAX_LOG_CHARS = 60_000;

    private final Thread.UncaughtExceptionHandler defaultHandler;
    private final Context appContext;

    private CrashHandler(Context appContext, Thread.UncaughtExceptionHandler defaultHandler) {
        this.appContext = appContext.getApplicationContext();
        this.defaultHandler = defaultHandler;
    }

    /** 在 Application 启动时安装 */
    public static void install(Application app) {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof CrashHandler) {
            return; // 避免重复安装
        }
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(app, prev));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            writeLog(throwable);
        } catch (Throwable ignored) {
            // 记日志本身失败不能再抛，否则会掩盖原始异常
        }
        Log.e(TAG, "未捕获异常，已写入 " + LOG_FILE, throwable);
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }
    }

    /** 把异常堆栈写入应用私有目录的 crash.log（覆盖上一份，只保留最近一次） */
    private void writeLog(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        try {
            pw.println("时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date()));
            pw.println("线程：" + Thread.currentThread().getName());
            pw.println("系统：" + android.os.Build.VERSION.RELEASE + " (API "
                    + android.os.Build.VERSION.SDK_INT + ")");
            pw.println("机型：" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            pw.println("--------------------------------------------------");
            throwable.printStackTrace(pw);
        } finally {
            pw.flush();
            pw.close();
        }
        String text = sw.toString();
        if (text.length() > MAX_LOG_CHARS) {
            text = text.substring(0, MAX_LOG_CHARS) + "\n...（日志过长已截断）";
        }
        File file = new File(appContext.getFilesDir(), LOG_FILE);
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception e) {
            Log.w(TAG, "写入崩溃日志失败：" + e.getMessage());
        }
    }

    /** 读取最近一次崩溃日志；没有则返回 null */
    public static String readLog(Context ctx) {
        try {
            File file = new File(ctx.getFilesDir(), LOG_FILE);
            if (!file.exists() || file.length() == 0) {
                return null;
            }
            byte[] buf = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                int read = fis.read(buf);
                if (read <= 0) {
                    return null;
                }
                return new String(buf, 0, read, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否存在崩溃日志（「我的」页据此决定是否显示入口） */
    public static boolean hasLog(Context ctx) {
        try {
            File file = new File(ctx.getFilesDir(), LOG_FILE);
            return file.exists() && file.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 清空崩溃日志 */
    public static void clearLog(Context ctx) {
        try {
            File file = new File(ctx.getFilesDir(), LOG_FILE);
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
