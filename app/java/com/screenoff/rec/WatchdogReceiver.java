package com.screenoff.rec;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * 闹钟看门狗：每5分钟被 AlarmManager 唤醒一次（闹钟可解冻被系统冻结的进程）。
 * - 服务活着：续期唤醒锁、重注册监听、预约下一轮闹钟
 * - 服务死了但用户开启过监听：尝试重新拉起前台服务（自愈）
 */
public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        RecService.appendLog("看门狗触发");
        RecService svc = RecService.self;
        if (svc != null) {
            svc.forceReregister();
            svc.heartbeatNow();
            svc.scheduleNextAlarm(); // 续约下一个3分钟周期
            return;
        }
        SharedPreferences p = context.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        if (p.getBoolean("enabled", false)) {
            try {
                Intent i = new Intent(context, RecService.class);
                i.setAction(RecService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
                else context.startService(i);
                RecService.appendLog("看门狗拉起服务");
            } catch (Throwable t) {
                RecService.appendLog("看门狗拉起失败: " + t.getClass().getSimpleName());
            }
        }
    }
}
