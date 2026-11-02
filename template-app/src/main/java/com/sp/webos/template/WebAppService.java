package com.sp.webos.template;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keep-alive foreground service. While it runs, Android keeps this
 * app's process alive in the background (a persistent notification
 * is shown, as Android requires). The web page controls it through
 * the SpWebOS bridge: SpWebOS.keepAlive(true/false).
 * Note: this keeps the PROCESS alive; the page's own JS timers can
 * still be throttled by the WebView/OEM battery managers.
 */
public class WebAppService extends Service {

    private static final int FG_ID = 2;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotifyHelper.ensureChannel(this);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, NotifyHelper.CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(NotifyHelper.appLabel(this) + " — background me chal raha hai")
                .setContentText("Band karne ke liye app kholo aur keepAlive band karo, ya app ko recent apps se hatao.")
                .setSmallIcon(getApplicationInfo().icon)
                .setContentIntent(pi)
                .setOngoing(true);
        startForeground(FG_ID, b.build());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
