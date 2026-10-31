package com.bai.bookshelf.util;

import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteFullException;

/**
 * 后台异常的分流规则（v5.8.4 从 BookshelfApp 抽出，便于真机测试直接断言）。
 *
 * <p>只有「库结构/文件本身坏了」才值得把用户送到修复页；磁盘满、锁冲突这些
 * 是临时状况，提示一句就够，把人拉到修复页反而吓人。
 */
public final class DbFailure {

    public static final String MSG_FULL = "存储空间不足，请清理后再试";
    public static final String MSG_LOCKED = "数据库正忙，请稍后重试";

    private DbFailure() {
    }

    /** 迁移没跑通、库文件损坏、表/列不存在 —— 只有这些算「库坏了」 */
    public static boolean isDatabaseBroken(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            String msg = c.getMessage();
            if (msg == null) {
                continue;
            }
            String m = msg.toLowerCase(java.util.Locale.ROOT);
            if (m.contains("migration didn't properly handle")
                    || m.contains("malformed")
                    || m.contains("file is not a database")
                    || m.contains("no such table") || m.contains("no such column")) {
                return true;
            }
        }
        return false;
    }

    /** 临时性数据库错误的一句话提示；不需要提示时返回 null */
    public static String userMessage(Throwable t) {
        if (t instanceof SQLiteFullException) {
            return MSG_FULL;
        }
        if (t instanceof SQLiteDatabaseLockedException) {
            return MSG_LOCKED;
        }
        return null;
    }
}
