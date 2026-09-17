package com.screenoff.rec;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 核心服务：注册系统"音量键长按监听"（息屏可用，免 root，需一次性 ADB 授权），
 * 长按音量下开始录音、长按音量上停止；同时提供通知栏按钮作为无 ADB 的替代触发方式。
 * 全部录音仅保存在本机 /sdcard/Rec/，无任何网络代码。
 */
public class RecService extends Service {

    public static final String TAG = "SORC";

    public static final String ACTION_START        = "com.screenoff.rec.START";
    public static final String ACTION_STOP_ALL     = "com.screenoff.rec.STOP_ALL";
    public static final String ACTION_REC_START    = "com.screenoff.rec.REC_START";
    public static final String ACTION_REC_STOP     = "com.screenoff.rec.REC_STOP";

    /** 供 UI 轮询的状态位 */
    public static volatile boolean listening = false;
    public static volatile boolean recording = false;
    public static volatile String  lastError  = null;

    /** 供看门狗广播直接调用的实例引用 */
    public static volatile RecService self;

    private static final Object LOG_LOCK = new Object();

    private static final String CH_ID = "rec";
    private static final int NOTIF_ID = 42;
    private static final String ADB_PERM = "android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER";

    private MediaRecorder recorder;
    private File outFile;
    private Handler handler;
    private Runnable timeoutRun;
    private Runnable hbRun;
    private Object proxyListener;
    private boolean listenerRegistered = false;
    private long recStartAt = 0L;
    private PowerManager.WakeLock wl;

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        self = this;
        try { nm(); } catch (Throwable t) { Log.e(TAG, "channel init failed", t); }
        appendLog("服务创建");
        Log.i(TAG, "service created");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        Log.i(TAG, "onStartCommand action=" + action);
        try {
            if (ACTION_STOP_ALL.equals(action)) {
                stopRecordingInternal(false);
                listening = false;
                unregisterVolumeListener();
                stopHeartbeat();
                releaseWake();
                prefs().edit().putBoolean("enabled", false).apply();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_REC_START.equals(action)) startRecording();
            else if (ACTION_REC_STOP.equals(action)) stopRecordingInternal(true);

            startListeningNotification();
            listening = true;
            self = this;
            prefs().edit().putBoolean("enabled", true).apply();
            registerVolumeListener();
            acquireWake();
            startHeartbeat();
            scheduleNextAlarm();
        } catch (Throwable t) {
            lastError = "服务启动异常: " + t;
            appendLog("服务启动异常: " + t);
            Log.e(TAG, "onStartCommand crash", t);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRecordingInternal(false);
        unregisterVolumeListener();
        stopHeartbeat();
        releaseWake();
        listening = false;
        recording = false;
        self = null;
        appendLog("服务销毁");
        super.onDestroy();
    }

    // ---------------- 保活：CPU唤醒锁 + 闹钟心跳 ----------------

    private void acquireWake() {
        if (!prefs().getBoolean("keepAwake", true)) return;
        try {
            if (wl == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenOffRec:listen");
            }
            if (!wl.isHeld()) {
                wl.acquire(10 * 60 * 1000L); // 10分钟，心跳会不断续期
                appendLog("获取唤醒锁");
            }
        } catch (Throwable t) {
            appendLog("唤醒锁失败: " + t.getClass().getSimpleName());
        }
    }

    private void releaseWake() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) { }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        hbRun = new Runnable() {
            @Override public void run() {
                if (!listening) return;
                heartbeatNow();
                handler.postDelayed(this, 30_000);
            }
        };
        handler.postDelayed(hbRun, 30_000);
    }

    private void stopHeartbeat() {
        if (hbRun != null) { handler.removeCallbacks(hbRun); hbRun = null; }
    }

    private long lastBeatAt = 0;

    /** 心跳：检查监听是否被系统丢弃 + 续期唤醒锁 */
    public void heartbeatNow() {
        try {
            long now = SystemClock.uptimeMillis();
            if (lastBeatAt != 0) {
                long gap = now - lastBeatAt;
                if (gap > 60_000) {
                    appendLog("心跳间隔异常: " + (gap / 1000) + "s（疑似被系统冻结过）");
                }
            }
            lastBeatAt = now;
            acquireWake();
            if (!listenerRegistered) {
                appendLog("心跳发现监听失效，尝试重新注册");
                Log.w(TAG, "heartbeat: listener lost, re-registering");
                registerVolumeListener();
            }
        } catch (Throwable t) {
            appendLog("心跳异常: " + t);
        }
    }

    /** 看门狗用：强制拆掉再重装监听（对付系统侧静默摘除监听记录的情况） */
    public void forceReregister() {
        appendLog("看门狗强制重注册监听");
        unregisterVolumeListener();
        registerVolumeListener();
    }

    public void scheduleNextAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            Intent i = new Intent(this, WatchdogReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, 21, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 180_000L, pi);
        } catch (Throwable t) {
            appendLog("闹钟预约失败: " + t.getClass().getSimpleName());
        }
    }

    // ---------------- 音量键长按监听（隐藏 API，反射 + 动态代理） ----------------

    private void registerVolumeListener() {
        if (listenerRegistered) return;
        try {
            if (checkSelfPermission(ADB_PERM) != PackageManager.PERMISSION_GRANTED) {
                lastError = "未获 ADB 授权，音量键触发不可用（通知栏按钮仍可用）";
                return;
            }
            MediaSessionManager msm =
                    (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            Class<?> iface = Class.forName(
                    "android.media.session.MediaSessionManager$OnVolumeKeyLongPressListener");
            proxyListener = Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface},
                    (proxy, method, args) -> {
                        if ("onVolumeKeyLongPress".equals(method.getName())
                                && args != null && args.length == 1
                                && args[0] instanceof KeyEvent) {
                            onLongPressKey((KeyEvent) args[0]);
                        }
                        return null;
                    });
            Method set = MediaSessionManager.class.getMethod(
                    "setOnVolumeKeyLongPressListener", iface, Handler.class);
            set.invoke(msm, proxyListener, handler);
            listenerRegistered = true;
            lastError = null;
            Log.i(TAG, "音量键监听注册成功");
            appendLog("音量键监听注册成功");
        } catch (Throwable t) {
            lastError = "音量键监听注册失败: " + t;
            appendLog("音量键监听注册失败: " + t);
            Log.e(TAG, "registerVolumeListener failed", t);
        }
    }

    private void unregisterVolumeListener() {
        if (!listenerRegistered) return;
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            Class<?> iface = Class.forName(
                    "android.media.session.MediaSessionManager$OnVolumeKeyLongPressListener");
            Method set = MediaSessionManager.class.getMethod(
                    "setOnVolumeKeyLongPressListener", iface, Handler.class);
            set.invoke(msm, null, null);
        } catch (Throwable ignored) { }
        listenerRegistered = false;
        proxyListener = null;
    }

    private void onLongPressKey(KeyEvent e) {
        // 关键防"鬼魂启动"：进程被冻结后，冻结期间的按键事件会排队，解冻瞬间集中送达。
        // 超过8秒的迟到事件视为过期，直接丢弃。
        long age = SystemClock.uptimeMillis() - e.getEventTime();
        if (age > 8000) {
            appendLog("忽略过期长按事件: age=" + age + "ms key=" + e.getKeyCode()
                    + " action=" + e.getAction());
            Log.i(TAG, "drop stale volume event age=" + age);
            return;
        }
        Log.i(TAG, "onLongPressKey action=" + e.getAction()
                + " key=" + e.getKeyCode() + " repeat=" + e.getRepeatCount());
        appendLog("长按事件: key=" + e.getKeyCode() + " action=" + e.getAction());
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return;
        boolean swap = prefs().getBoolean("swap", false);
        int startKey = swap ? KeyEvent.KEYCODE_VOLUME_UP   : KeyEvent.KEYCODE_VOLUME_DOWN;
        int stopKey  = swap ? KeyEvent.KEYCODE_VOLUME_DOWN : KeyEvent.KEYCODE_VOLUME_UP;
        int code = e.getKeyCode();
        if (code == startKey && !recording) {
            startRecording();
        } else if (code == stopKey && recording) {
            stopRecordingInternal(true);
        }
    }

    // ---------------- 录音 ----------------

    private void startRecording() {
        if (recording) return;
        Log.i(TAG, "startRecording begin");
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Rec");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            outFile = new File(dir, "REC_" + ts + ".m4a");

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(48000);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.prepare();

            // Android 10+ 需在访问麦克风前把前台服务提升为 microphone 类型
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    startForeground(NOTIF_ID, buildRecordingNotification(),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                } catch (Throwable t) {
                    Log.e(TAG, "startForeground(mic) failed", t);
                    lastError = "前台服务(mic)异常: " + t;
                }
            }
            recorder.start();
            recording = true;
            recStartAt = System.currentTimeMillis();
            lastError = null;
            Log.i(TAG, "recording started -> " + outFile.getAbsolutePath());
            appendLog("开始录音 -> " + outFile.getName());
            if (Build.VERSION.SDK_INT < 29) startRecordingNotification();
            vibrate(45);

            int maxSecs = prefs().getInt("maxSecs", 3600);
            if (maxSecs > 0) {
                timeoutRun = () -> stopRecordingInternal(true);
                handler.postDelayed(timeoutRun, maxSecs * 1000L);
            }
        } catch (Throwable t) {
            recording = false;
            lastError = "录音启动失败（麦克风可能被占用）: " + t.getClass().getSimpleName();
            appendLog("录音启动失败: " + t);
            releaseRecorder();
            startListeningNotification();
            vibrate(18); vibrate(18);
            try { android.widget.Toast.makeText(this, lastError, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
        }
    }

    private void stopRecordingInternal(boolean feedback) {
        if (timeoutRun != null) { handler.removeCallbacks(timeoutRun); timeoutRun = null; }
        if (!recording) return;
        try { recorder.stop(); } catch (Throwable ignored) { }
        releaseRecorder();
        recording = false;
        if (outFile != null) {
            android.media.MediaScannerConnection.scanFile(this,
                    new String[]{outFile.getAbsolutePath()}, new String[]{"audio/mp4"}, null);
        }
        if (feedback) vibrate(25);
        appendLog("停止录音" + (outFile != null ? " -> " + outFile.getName() : ""));
        startListeningNotification();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Throwable ignored) { }
            try { recorder.release(); } catch (Throwable ignored) { }
            recorder = null;
        }
    }

    // ---------------- 通知 ----------------

    private NotificationManager nm() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "录音控制",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        return nm;
    }

    private Notification.Builder baseBuilder(String title, String text) {
        nm(); // 确保通知渠道已创建，否则 startForeground 会崩 (Bad notification for startForeground)
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setContentTitle(title)
         .setContentText(text)
         .setSmallIcon(android.R.drawable.ic_btn_speak_now)
         .setOngoing(true)
         .setContentIntent(pOpen)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .setOnlyAlertOnce(true);
        return b;
    }

    private Notification.Action action(String label, String actionName, int reqCode) {
        Intent i = new Intent(this, RecService.class).setAction(actionName);
        PendingIntent pi = PendingIntent.getService(this, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null, label, pi).build();
    }

    private void startListeningNotification() {
        boolean adbOk = checkSelfPermission(ADB_PERM) == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "startListeningNotification adbOk=" + adbOk + " recording=" + recording);
        String text = adbOk
                ? "长按 音量下=开始 · 音量上=停止（短按调音量不受影响）"
                : "音量键触发未授权：请先执行 ADB 命令。下方按钮仍可用";
        Notification n = baseBuilder("息屏速录 · 监听中", text)
                .addAction(action("● 开始录音", ACTION_REC_START, 11))
                .addAction(action("■ 停止监听", ACTION_STOP_ALL, 12))
                .build();
        if (Build.VERSION.SDK_INT >= 29 && !recording) {
            // 待机监听不需要 microphone 类型，降级为普通前台
            startForeground(NOTIF_ID, n);
        } else {
            nm().notify(NOTIF_ID, n);
        }
    }

    private Notification buildRecordingNotification() {
        return baseBuilder("● 录音中", "长按 音量上 停止（通知按钮也可）")
                .setUsesChronometer(true)
                .setWhen(recStartAt == 0 ? System.currentTimeMillis() : recStartAt)
                .addAction(action("■ 停止录音", ACTION_REC_STOP, 13))
                .build();
    }

    private void startRecordingNotification() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildRecordingNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            nm().notify(NOTIF_ID, buildRecordingNotification());
        }
    }

    // ---------------- 工具 ----------------

    private SharedPreferences prefs() {
        return getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    private void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Throwable ignored) { }
    }

    /** 落盘诊断日志：/sdcard/Rec/.log.txt（超过256KB自动清空重写） */
    public static void appendLog(String msg) {
        synchronized (LOG_LOCK) {
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "Rec");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, ".log.txt");
                if (f.length() > 262144) f.delete();
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
                FileWriter w = new FileWriter(f, true);
                w.write(ts + " " + msg + "\n");
                w.close();
            } catch (Throwable ignored) { }
        }
    }
}
