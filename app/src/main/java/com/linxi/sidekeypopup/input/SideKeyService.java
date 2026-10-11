package com.linxi.sidekeypopup.input;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.linxi.sidekeypopup.MainActivity;
import com.linxi.sidekeypopup.R;

/**
 * 只在走 Root / Shizuku 的时候才起来。
 *
 * 原因很实际：那两条路要靠应用进程里的一个线程一直读 /dev/input，
 * 没有前台服务的话进程很快会被系统回收，侧键就失效了。
 * 无障碍那条路不需要它——系统自己会保活无障碍服务。
 */
public class SideKeyService extends Service {

    private static final String TAG = "SideKeyPopup";
    private static final String CHANNEL_ID = "sidekey";
    private static final int NOTIFICATION_ID = 1001;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, SideKeyService.class));
        } catch (Throwable error) {
            Log.w(TAG, "start SideKeyService failed", error);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, SideKeyService.class));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable error) {
            Log.w(TAG, "startForeground failed", error);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sidekey)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(contentIntent)
                .build();
    }
}
