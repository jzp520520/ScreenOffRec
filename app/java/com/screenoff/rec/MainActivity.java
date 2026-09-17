package com.screenoff.rec;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_MIC = 1;
    private static final int REQ_NOTIF = 2;
    private static final int REQ_WRITE = 3;

    private TextView tvService, tvAdb, tvError;
    private LinearLayout llFiles;
    private MediaPlayer player;
    private String playingPath;
    private String lastSig = "";
    private Button btnToggleListen, btnToggleRec;
    private CheckBox cbSwap;
    private Button btnMax;
    private final Handler handler = new Handler();
    private SharedPreferences prefs;

    private static final String ADB_CMD =
            "adb shell pm grant com.screenoff.rec android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER";

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE);
        setContentView(buildUi());
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(android.R.id.content).findViewWithTag("subtitle"))
                    .setText("v" + ver + " · 纯本地录音 · 零联网 · 无广告");
        } catch (Throwable ignored) { }
    }

    @Override protected void onResume() { super.onResume(); refresher.run(); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresher); }

    private static final int[] MAX_OPTS = {900, 1800, 3600, 0}; // 15m 30m 60m 不限

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("息屏速录");
        title.setTextSize(26);
        title.setPadding(0, 0, 0, 4);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setTag("subtitle");
        sub.setText("v" + versionName() + " · 纯本地录音 · 零联网 · 无广告");
        sub.setAlpha(0.6f);
        sub.setPadding(0, 0, 0, 24);
        root.addView(sub);

        tvService = new TextView(this);
        tvService.setTextSize(16);
        root.addView(tvService);

        tvError = new TextView(this);
        tvError.setTextColor(0xFFFFB74D);
        tvError.setPadding(0, 4, 0, 12);
        root.addView(tvError);

        btnToggleListen = new Button(this);
        btnToggleListen.setOnClickListener(v -> toggleListen());
        root.addView(btnToggleListen, match());

        btnToggleRec = new Button(this);
        btnToggleRec.setOnClickListener(v -> toggleRec());
        root.addView(btnToggleRec, match());

        tvAdb = new TextView(this);
        tvAdb.setPadding(0, 20, 0, 6);
        tvAdb.setTextSize(15);
        root.addView(tvAdb);

        Button btnCopy = new Button(this);
        btnCopy.setText("复制 ADB 授权命令（电脑执行一次即可）");
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("adb", ADB_CMD));
            Toast.makeText(this, "已复制，电脑连接手机后执行", Toast.LENGTH_LONG).show();
        });
        root.addView(btnCopy, match());

        TextView help = new TextView(this);
        help.setText("第1步 安装本 App（已完成）\n第2步 点上方按钮复制命令，电脑上执行\n第3步 点「开始监听」→ 息屏后长按音量键即录\n\n· 长按 音量下 = 开始录音　长按 音量上 = 停止\n· 短按音量键仍正常调节音量\n· 通知栏也有 ●开始/■停止 按钮，锁屏可直接点");
        help.setTextSize(13);
        help.setAlpha(0.75f);
        help.setPadding(0, 8, 0, 16);
        root.addView(help);

        TextView permTitle = new TextView(this);
        permTitle.setText("权限");
        permTitle.setTextSize(15);
        root.addView(permTitle);

        Button btnMic = new Button(this);
        btnMic.setText("① 麦克风权限");
        btnMic.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "麦克风权限已授予", Toast.LENGTH_SHORT).show();
            else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        });
        root.addView(btnMic, match());

        Button btnStorage = new Button(this);
        btnStorage.setText("② 文件访问权限（保存到 /sdcard/Rec）");
        btnStorage.setOnClickListener(v -> requestStorage());
        root.addView(btnStorage, match());

        if (Build.VERSION.SDK_INT >= 33) {
            Button btnNotif = new Button(this);
            btnNotif.setText("③ 通知权限");
            btnNotif.setOnClickListener(v ->
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF));
            root.addView(btnNotif, match());
        }

        CheckBox ka = new CheckBox(this);
        ka.setText("增强保活（CPU唤醒锁+5分钟闹钟心跳，防系统冻结，轻微耗电）");
        ka.setChecked(prefs.getBoolean("keepAwake", true));
        ka.setOnCheckedChangeListener((b, w) -> prefs.edit().putBoolean("keepAwake", w).apply());
        root.addView(ka);

        cbSwap = new CheckBox(this);
        cbSwap.setText("交换音量键（长按音量上开始 / 长按音量下停止）");
        cbSwap.setChecked(prefs.getBoolean("swap", false));
        cbSwap.setOnCheckedChangeListener((b, w) -> prefs.edit().putBoolean("swap", w).apply());
        root.addView(cbSwap);

        btnMax = new Button(this);
        btnMax.setOnClickListener(v -> cycleMax());
        root.addView(btnMax, match());

        TextView filesTitle = new TextView(this);
        filesTitle.setText("最近录音（/sdcard/Rec/）· 点 ▶ 播放 / 再点停止");
        filesTitle.setTextSize(15);
        filesTitle.setPadding(0, 20, 0, 6);
        root.addView(filesTitle);

        llFiles = new LinearLayout(this);
        llFiles.setOrientation(LinearLayout.VERTICAL);
        root.addView(llFiles);

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        return sc;
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private void toggleListen() {
        Intent i = new Intent(this, RecService.class);
        i.setAction(RecService.listening ? RecService.ACTION_STOP_ALL : RecService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        handler.postDelayed(this::refresh, 400);
    }

    private void toggleRec() {
        String action = RecService.recording ? RecService.ACTION_REC_STOP
                : (RecService.listening ? RecService.ACTION_REC_START : null);
        if (action == null) {
            Toast.makeText(this, "请先「开始监听」", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, RecService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        handler.postDelayed(this::refresh, 400);
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "文件权限已授予", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "文件权限已授予", Toast.LENGTH_SHORT).show();
            else requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
        }
    }

    private void cycleMax() {
        int cur = prefs.getInt("maxSecs", 3600);
        int idx = 1;
        for (int i = 0; i < MAX_OPTS.length; i++) if (MAX_OPTS[i] == cur) { idx = (i + 1) % MAX_OPTS.length; break; }
        prefs.edit().putInt("maxSecs", MAX_OPTS[idx]).apply();
        refresh();
    }

    private void refresh() {
        boolean adbOk = checkSelfPermission(
                "android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER") == PackageManager.PERMISSION_GRANTED;

        tvService.setText(RecService.recording ? "状态：● 录音中"
                : (RecService.listening ? "状态：监听中（息屏长按音量键即录）" : "状态：未启动"));

        String err = RecService.lastError;
        tvError.setVisibility(err == null ? View.GONE : View.VISIBLE);
        tvError.setText(err == null ? "" : "⚠ " + err);

        btnToggleListen.setText(RecService.listening ? "停止监听" : "开始监听");
        btnToggleRec.setText(RecService.recording ? "■ 停止录音" : "● 立即录音");

        tvAdb.setText(adbOk ? "音量键触发：✅ 已授权（息屏可用）"
                : "音量键触发：❌ 未授权（手机连电脑执行下方命令一次）");

        int maxSecs = prefs.getInt("maxSecs", 3600);
        btnMax.setText("最长单次：" + (maxSecs == 0 ? "不限" : (maxSecs / 60) + " 分钟"));

        cbSwap.setOnCheckedChangeListener(null);
        cbSwap.setChecked(prefs.getBoolean("swap", false));
        cbSwap.setOnCheckedChangeListener((b, w) -> prefs.edit().putBoolean("swap", w).apply());

        String sig = dirSignature();
        if (!sig.equals(lastSig)) {
            lastSig = sig;
            rebuildFileRows();
        }
    }

    private List<File> recentFiles(int max) {
        File dir = new File(Environment.getExternalStorageDirectory(), "Rec");
        File[] fs = dir.listFiles();
        List<File> list = new ArrayList<>();
        if (fs != null) {
            Collections.addAll(list, fs);
            Collections.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        }
        return list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
    }

    private String dirSignature() {
        List<File> list = recentFiles(10);
        StringBuilder sb = new StringBuilder();
        for (File f : list) sb.append(f.getName()).append(':').append(f.length()).append('|');
        return sb.toString();
    }

    private void rebuildFileRows() {
        llFiles.removeAllViews();
        List<File> list = recentFiles(10);
        if (list.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("（暂无）");
            none.setAlpha(0.5f);
            llFiles.addView(none);
            return;
        }
        int pad = (int) (getResources().getDisplayMetrics().density * 6);
        for (final File f : list) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, pad, 0, pad);

            TextView name = new TextView(this);
            String label = f.getName().replace("REC_", "").replace(".m4a", "")
                    + "  (" + (f.length() / 1024) + " KB)";
            name.setText(label);
            name.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, lp);

            Button play = new Button(this);
            boolean isPlaying = f.getAbsolutePath().equals(playingPath);
            play.setText(isPlaying ? "■ 停止" : "▶ 播放");
            play.setAllCaps(false);
            play.setOnClickListener(v -> togglePlay(f));
            row.addView(play);

            Button del = new Button(this);
            del.setText("删");
            del.setAllCaps(false);
            del.setOnClickListener(v -> confirmDelete(f));
            row.addView(del);

            llFiles.addView(row);
        }
        int total = new File(Environment.getExternalStorageDirectory(), "Rec").list().length;
        if (total > 10) {
            TextView more = new TextView(this);
            more.setText("… 共 " + total + " 条，其余请用文件管理器查看");
            more.setTextSize(12);
            more.setAlpha(0.6f);
            llFiles.addView(more);
        }
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(this)
                .setTitle("删除录音")
                .setMessage("确定删除 " + f.getName() + " ？\n删除后无法恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    if (f.getAbsolutePath().equals(playingPath)) stopPlayer();
                    boolean ok = f.delete();
                    android.media.MediaScannerConnection.scanFile(this,
                            new String[]{f.getAbsolutePath()}, null, null);
                    Toast.makeText(this, ok ? "已删除" : "删除失败", Toast.LENGTH_SHORT).show();
                    lastSig = "";
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void togglePlay(File f) {
        if (player != null && f.getAbsolutePath().equals(playingPath)) {
            stopPlayer();
            rebuildFileRows();
            return;
        }
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.setOnCompletionListener(mp -> {
                stopPlayer();
                rebuildFileRows();
            });
            player.start();
            playingPath = f.getAbsolutePath();
        } catch (Throwable t) {
            Toast.makeText(this, "播放失败: " + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
            stopPlayer();
        }
        rebuildFileRows();
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        playingPath = null;
    }

    @Override
    protected void onDestroy() {
        stopPlayer();
        super.onDestroy();
    }
}
