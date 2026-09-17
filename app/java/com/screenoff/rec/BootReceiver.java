package com.screenoff.rec;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** 开机自动恢复监听（仅当用户上次主动开启过监听时） */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("enabled", false)) return;
        Intent i = new Intent(context, RecService.class);
        i.setAction(RecService.ACTION_START);
        context.startForegroundService(i);
    }
}
