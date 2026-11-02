package com.sp.webos.template;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Native notifications for the generated Web App.
 * The web page reaches these through the SpWebOS JS bridge
 * (see MainActivity): immediate notifications, AlarmManager-scheduled
 * notifications (fire even when the app is closed) and the
 * keep-alive foreground service.
 */
public class NotifyHelper {

    static final String CHANNEL_ID = "sp_webos_alerts";

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Web App Notifications", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Is web app ke notifications");
                nm.createNotificationChannel(ch);
            }
        }
    }

    static void post(Context ctx, int id, String title, String body) {
        ensureChannel(ctx);
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setContentTitle(title == null || title.isEmpty() ? appLabel(ctx) : title)
                .setContentText(body == null ? "" : body)
                .setSmallIcon(ctx.getApplicationInfo().icon)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis());
        nm.notify(id, b.build());
    }

    /** Schedules a notification; exact when Android allows it, otherwise
     *  setAndAllowWhileIdle (may drift by a few minutes in Doze). */
    static void schedule(Context ctx, long triggerAtMillis, String title, String body) {
        int id = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        Intent i = new Intent(ctx, NotifyReceiver.class);
        i.putExtra(NotifyReceiver.EXTRA_TITLE, title);
        i.putExtra(NotifyReceiver.EXTRA_BODY, body);
        i.putExtra(NotifyReceiver.EXTRA_ID, id);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, id, i, piFlags);

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        } else if (Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        }
    }

    static String appLabel(Context ctx) {
        CharSequence label = ctx.getApplicationInfo()
                .loadLabel(ctx.getPackageManager());
        return label == null ? "Web App" : label.toString();
    }
}
