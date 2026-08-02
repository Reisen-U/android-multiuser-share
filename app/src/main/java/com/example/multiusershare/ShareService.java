package com.example.multiusershare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShareService extends Service {
    public static final String ACTION_START = "com.example.multiusershare.START";
    public static final String ACTION_STOP = "com.example.multiusershare.STOP";
    public static final String ACTION_STATUS = "com.example.multiusershare.STATUS";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_ADDRESS = "address";
    private static final String CHANNEL_ID = "share_service";

    public static volatile String currentState = "STOPPED";
    public static volatile String currentAddress = "";
    private LocalHttpServer server;
    private final ExecutorService starter = Executors.newSingleThreadExecutor();

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if ("RUNNING".equals(currentState) && server != null) return START_STICKY;
        setState("STARTING", "");
        startForeground(11, notification("多用户共享正在启动"));
        starter.execute(() -> {
            try {
                ConfigStore config = new ConfigStore(this);
                server = new LocalHttpServer(this, config.username(), config.password(), config.authEnabled(), config.port());
                server.start();
                currentAddress = "http://" + NetworkUtils.localAddress() + ":" + config.port();
                setState("RUNNING", currentAddress);
                updateNotification();
            } catch (Exception e) {
                setState("FAILED", "");
                updateNotification();
                if (server != null) {
                    server.stop();
                    server = null;
                }
            }
        });
        return START_STICKY;
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        setState("STOPPED", "");
    }

    @Override public void onDestroy() {
        stopServer();
        starter.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void setState(String state, String address) {
        currentState = state;
        currentAddress = address == null ? "" : address;
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_STATE, currentState).putExtra(EXTRA_ADDRESS, currentAddress));
    }

    private Notification notification(String message) {
        Intent stop = new Intent(this, ShareService.class).setAction(ACTION_STOP);
        PendingIntent pending = PendingIntent.getService(this, 12, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("多用户共享")
                .setContentText(message)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "停止", pending).build())
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String message = "RUNNING".equals(currentState) ? "正在运行 · " + currentAddress :
                "FAILED".equals(currentState) ? "启动失败，请检查端口" : "服务已停止";
        manager.notify(11, notification(message));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "共享服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示局域网共享服务状态");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }
}
