package com.bai.bookshelf.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 借阅到期检查（v4.42）：每日闹钟与开机广播都走这里。
 * 无论应用是否在运行都会拉起进程 → Application.onCreate 打开数据库 → 查询并发通知。
 */
public class BorrowReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        BorrowReminder.schedule(context);
        BorrowReminder.checkAndNotify(context);
    }
}
