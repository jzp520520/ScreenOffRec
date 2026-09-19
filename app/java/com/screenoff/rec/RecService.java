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
    public static final String ACTION_REC_START_VID = "com.screenoff.rec.REC_START_VID";
    public static final String ACTION_REC_STOP     = "com.screenoff.rec.REC_STOP";

    /** 供 UI 轮询的状态位 */
    public static volatile boolean listening = false;
    public static volatile boolean recording = false;
    public static volatile String  lastError  = null;
    /** 当前录制类型：0 无 1 音频 2 录像 */
    public static volatile int recType = 0;

    /** 供看门狗广播直接调用的实例引用 */
    public static volatile RecService self;

    private static final Object LOG_LOCK = new Object();

    private static final String CH_ID = "rec";
    private static final int NOTIF_ID = 42;
    private static final String ADB_PERM = "android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER";

    private MediaRecorder recorder;
    private MediaRecorder vRec;
    private android.hardware.Camera camera;
    private android.graphics.SurfaceTexture previewTex;
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
            if (ACTION_REC_START.equals(action)) startAudioInternal();
            else if (ACTION_REC_START_VID.equals(action)) startVideoInternal();
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
        // v2.1 键位：空闲时长按下=录音、长按上=录像（swap 可交换）；录制中任意键长按=停止
        boolean swap = prefs().getBoolean("swap", false);
        int audioKey = swap ? KeyEvent.KEYCODE_VOLUME_UP   : KeyEvent.KEYCODE_VOLUME_DOWN;
        int videoKey = swap ? KeyEvent.KEYCODE_VOLUME_DOWN : KeyEvent.KEYCODE_VOLUME_UP;
        int code = e.getKeyCode();
        if (recording) {
            stopRecordingInternal(true);
        } else if (code == audioKey) {
            startAudioInternal();
        } else if (code == videoKey) {
            startVideoInternal();
        }
    }

    // ---------------- 录音 / 录像 ----------------

    private void startAudioInternal() {
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
            recType = 1;
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
            recType = 0;
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
        boolean wasVideo = recType == 2;
        if (wasVideo) {
            releaseVideo();
        } else {
            try { recorder.stop(); } catch (Throwable ignored) { }
            releaseRecorder();
        }
        recording = false;
        recType = 0;
        if (outFile != null) {
            android.media.MediaScannerConnection.scanFile(this,
                    new String[]{outFile.getAbsolutePath()},
                    new String[]{wasVideo ? "video/mp4" : "audio/mp4"}, null);
        }
        if (feedback) vibrate(25);
        appendLog("停止" + (wasVideo ? "录像" : "录音")
                + (outFile != null ? " -> " + outFile.getName() : ""));
        startListeningNotification();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Throwable ignored) { }
            try { recorder.release(); } catch (Throwable ignored) { }
            recorder = null;
        }
    }

    // ---------------- 录像（MediaRecorder + Camera1，息屏无预览录制） ----------------

    private void startVideoInternal() {
        if (checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED) {
            lastError = "录像需要相机权限：请打开 App 点「相机权限」按钮";
            appendLog("录像失败: 无相机权限");
            vibrate(60);
            return;
        }
        try {
            // 必须在打开相机之前把前台服务类型提升为 CAMERA|MICROPHONE
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, buildVideoNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                                | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildVideoNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                nm().notify(NOTIF_ID, buildVideoNotification());
            }

            int facing = "front".equals(prefs().getString("lens", "back"))
                    ? android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT
                    : android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK;
            android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
            int idx = -1;
            for (int i = 0; i < android.hardware.Camera.getNumberOfCameras(); i++) {
                android.hardware.Camera.getCameraInfo(i, info);
                if (info.facing == facing) { idx = i; break; }
            }
            camera = android.hardware.Camera.open(idx >= 0 ? idx : 0);

            boolean hd = !"720".equals(prefs().getString("res", "1080"));
            int w = hd ? 1920 : 1280, h = hd ? 1080 : 720;
            android.hardware.Camera.Parameters p = camera.getParameters();
            android.hardware.Camera.Size best = pickSize(p.getSupportedPreviewSizes(), w, h);
            if (best != null) p.setPreviewSize(best.width, best.height);
            p.setRecordingHint(true);
            camera.setParameters(p);
            camera.unlock();

            // 无 UI 息屏录制：SurfaceTexture 提供虚拟预览面
            previewTex = new android.graphics.SurfaceTexture(0);
            if (best != null) previewTex.setDefaultBufferSize(best.width, best.height);
            try { camera.setPreviewTexture(previewTex); } catch (Throwable ignored) { }
            android.view.Surface surf = new android.view.Surface(previewTex);

            vRec = new MediaRecorder();
            vRec.setCamera(camera);
            vRec.setAudioSource(MediaRecorder.AudioSource.MIC);
            vRec.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            vRec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            File dir = new File(Environment.getExternalStorageDirectory(), "Rec");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            outFile = new File(dir, "VID_" + ts + ".mp4");
            vRec.setOutputFile(outFile.getAbsolutePath());
            if (best != null) vRec.setVideoSize(best.width, best.height);
            vRec.setVideoFrameRate(30);
            vRec.setVideoEncodingBitRate(hd ? 12_000_000 : 6_000_000);
            vRec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            vRec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            vRec.setAudioEncodingBitRate(128000);
            vRec.setAudioSamplingRate(44100);
            try {
                vRec.setOrientationHint(facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT ? 270 : 90);
            } catch (Throwable ignored) { }
            vRec.setPreviewDisplay(surf);
            vRec.prepare();
            vRec.start();

            recording = true;
            recType = 2;
            recStartAt = System.currentTimeMillis();
            lastError = null;
            appendLog("开始录像 -> " + outFile.getName()
                    + (best != null ? " (" + best.width + "x" + best.height + ")" : ""));
            vibrate(45);

            int maxSecs = prefs().getInt("maxSecs", 3600);
            if (maxSecs > 0) {
                timeoutRun = () -> stopRecordingInternal(true);
                handler.postDelayed(timeoutRun, maxSecs * 1000L);
            }
        } catch (Throwable t) {
            appendLog("录像启动失败: " + t);
            lastError = "录像启动失败: " + t.getClass().getSimpleName();
            releaseVideo();
            recording = false;
            recType = 0;
            startListeningNotification();
            vibrate(18); vibrate(18);
            try { android.widget.Toast.makeText(this, lastError, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
        }
    }

    private void releaseVideo() {
        try { if (vRec != null) vRec.stop(); } catch (Throwable ignored) { }
        try { if (vRec != null) vRec.release(); } catch (Throwable ignored) { }
        vRec = null;
        try { if (camera != null) camera.lock(); } catch (Throwable ignored) { }
        try { if (camera != null) camera.release(); } catch (Throwable ignored) { }
        camera = null;
        try { if (previewTex != null) previewTex.release(); } catch (Throwable ignored) { }
        previewTex = null;
    }

    private static android.hardware.Camera.Size pickSize(
            java.util.List<android.hardware.Camera.Size> list, int w, int h) {
        if (list == null || list.isEmpty()) return null;
        android.hardware.Camera.Size best = null;
        for (android.hardware.Camera.Size s : list) {
            if (s.width == w && s.height == h) return s;
            if (s.width <= w && s.height <= h
                    && (best == null || s.width * s.height > best.width * best.height)) best = s;
        }
        return best != null ? best : list.get(0);
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
        boolean swap = prefs().getBoolean("swap", false);
        String text = adbOk
                ? (swap ? "长按 音量上=录音 · 音量下=录像 · 录制中任意键停止"
                        : "长按 音量下=录音 · 音量上=录像 · 录制中任意键停止")
                : "音量键触发未授权：请先执行 ADB 命令。下方按钮仍可用";
        Notification n = baseBuilder("息屏速录 · 监听中", text)
                .addAction(action("● 录音", ACTION_REC_START, 11))
                .addAction(action("🔴 录像", ACTION_REC_START_VID, 15))
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
        return baseBuilder("● 录音中", "长按任意音量键停止（通知按钮也可）")
                .setUsesChronometer(true)
                .setWhen(recStartAt == 0 ? System.currentTimeMillis() : recStartAt)
                .addAction(action("■ 停止录音", ACTION_REC_STOP, 13))
                .build();
    }

    private Notification buildVideoNotification() {
        return baseBuilder("🔴 录像中", "长按任意音量键停止（通知按钮也可）")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setUsesChronometer(true)
                .setWhen(recStartAt == 0 ? System.currentTimeMillis() : recStartAt)
                .addAction(action("■ 停止录像", ACTION_REC_STOP, 13))
                .build();
    }

    /** 供 UI 调用：模式设置变化后刷新通知按钮 */
    public void refreshNotif() {
        if (!recording) startListeningNotification();
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
