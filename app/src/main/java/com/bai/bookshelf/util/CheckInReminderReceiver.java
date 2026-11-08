package com.bai.bookshelf.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 每日打卡提醒（第 4.4 步）：闹钟到点发通知，开机/时间变化/覆盖安装后重排，
 * 独立于借阅提醒。
 *
 * <p>时区、时间、日期变化只重排闹钟，不立刻核对是否发通知——用户改个时区
 * 不该马上收到「今天还没打卡」。
 */
public class CheckInReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (CheckInReminder.ACTION_CHECK.equals(action)) {
            CheckInReminder.schedule(context);
            CheckInReminder.checkAndNotify(context);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // 开机后 alarm 全丢；时区/时间/日期变了，原先算好的绝对时刻就不对了
            CheckInReminder.schedule(context);
            // 第 12 项：跨 0 点/改时间后小组件的「今日已打卡」要重算
            com.bai.bookshelf.ui.widget.ReadingWidgetProvider.refresh(context);
        }
    }
}
