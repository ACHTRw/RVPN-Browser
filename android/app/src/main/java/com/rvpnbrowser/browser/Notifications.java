package com.rvpnbrowser.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/** Notification channels, ids and builders shared by both processes. */
public final class Notifications {

    public static final String CHANNEL_STATUS = "rvpn_status";
    public static final String CHANNEL_CHALLENGE = "rvpn_challenge";
    public static final String CHANNEL_DOWNLOAD = "rvpn_download";

    /** Ongoing notification of the engine foreground service. */
    public static final int ID_STATUS = 1001;
    /** Ids above this value are challenge notifications. */
    private static final int ID_CHALLENGE_BASE = 3000;
    /** Ids above this value are download notifications. */
    private static final int ID_DOWNLOAD_BASE = 2000;
    private static final int ID_DOWNLOAD_COUNT = 500;

    private Notifications() {
    }

    /** Creates all channels; safe to call from any process, any number of times. */
    public static void createChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel status = new NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.notif_channel_status),
                NotificationManager.IMPORTANCE_LOW);
        status.setDescription(context.getString(R.string.notif_channel_status_desc));
        status.setShowBadge(false);
        nm.createNotificationChannel(status);

        NotificationChannel challenge = new NotificationChannel(
                CHANNEL_CHALLENGE,
                context.getString(R.string.notif_channel_challenge),
                NotificationManager.IMPORTANCE_HIGH);
        challenge.setDescription(context.getString(R.string.notif_channel_challenge_desc));
        nm.createNotificationChannel(challenge);

        NotificationChannel download = new NotificationChannel(
                CHANNEL_DOWNLOAD,
                context.getString(R.string.notif_channel_download),
                NotificationManager.IMPORTANCE_LOW);
        download.setDescription(context.getString(R.string.notif_channel_download_desc));
        download.setShowBadge(false);
        nm.createNotificationChannel(download);
    }

    public static int challengeId(String challengeId) {
        int hash = challengeId == null ? 0 : challengeId.hashCode();
        return ID_CHALLENGE_BASE + Math.abs(hash % 1000);
    }

    public static int downloadId(long id) {
        return ID_DOWNLOAD_BASE + (int) (Math.abs(id) % ID_DOWNLOAD_COUNT);
    }

    // ---------------------------------------------------------------- intents

    public static PendingIntent activityIntent(Context context, Intent intent, int requestCode) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static PendingIntent serviceIntent(Context context, Intent intent, int requestCode) {
        return PendingIntent.getService(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ------------------------------------------------------------- builders

    /**
     * Builder for the ongoing engine notification: open the browser, jump to the
     * RVPN settings or disconnect - directly from the shade.
     */
    public static Notification statusNotification(Context context, String title, String text,
                                                  boolean showDisconnect) {
        Intent open = new Intent(context, MainActivity.class);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_rvpn)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(activityIntent(context, open, 1))
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, context.getString(R.string.notif_action_settings),
                        activityIntent(context, new Intent(context, RvpnSettingsActivity.class), 2));
        if (showDisconnect) {
            Intent stop = new Intent(context, RvpnService.class).setAction(RvpnService.ACTION_STOP);
            b.addAction(0, context.getString(R.string.notif_action_disconnect),
                    serviceIntent(context, stop, 3));
        }
        return b.build();
    }

    public static Notification challengeNotification(Context context, String challengeId,
                                                     String title, String text) {
        Intent open = new Intent(context, RvpnSettingsActivity.class)
                .putExtra(RvpnSettingsActivity.EXTRA_CHALLENGE_ID, challengeId);
        return new NotificationCompat.Builder(context, CHANNEL_CHALLENGE)
                .setSmallIcon(R.drawable.ic_stat_rvpn)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(activityIntent(context, open, 4))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build();
    }

    public static Notification downloadNotification(Context context, String title, String text,
                                                    int progress, boolean indeterminate,
                                                    PendingIntent cancelIntent) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
                .setSmallIcon(R.drawable.ic_stat_rvpn)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(!indeterminate && progress < 100)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, progress, indeterminate);
        if (cancelIntent != null) {
            b.addAction(0, context.getString(R.string.download_cancel_action), cancelIntent);
        }
        return b.build();
    }

    public static Notification downloadFinishedNotification(Context context, String title,
                                                            String text, boolean failed) {
        Intent open = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_SHOW_DOWNLOADS, true);
        return new NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
                .setSmallIcon(R.drawable.ic_stat_rvpn)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(activityIntent(context, open, 5))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setProgress(0, 0, false)
                .setCategory(failed ? NotificationCompat.CATEGORY_ERROR
                        : NotificationCompat.CATEGORY_PROGRESS)
                .build();
    }

    /** True on API levels that need the POST_NOTIFICATIONS runtime permission. */
    public static boolean needsRuntimePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }
}
