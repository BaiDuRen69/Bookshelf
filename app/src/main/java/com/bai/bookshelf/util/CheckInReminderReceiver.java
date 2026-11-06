package com.bai.bookshelf.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 每日打卡提醒（第 4.4 步）：21:00 闹钟与开机重排都走这里，独立于借阅提醒。 */
public class CheckInReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !CheckInReminder.ACTION_CHECK.equals(action)) {
            return;
        }
        CheckInReminder.schedule(context);
        CheckInReminder.checkAndNotify(context);
    }
}
