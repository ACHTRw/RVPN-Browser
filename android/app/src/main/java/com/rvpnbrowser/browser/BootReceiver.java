package com.rvpnbrowser.browser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

/**
 * Restores RVPN mode after a reboot or an app update.
 *
 * <p>Android 12+ restricts foreground-service starts from the background; a
 * {@code BOOT_COMPLETED} receiver is allowed to try, but the attempt can still
 * be refused (battery optimisation, restricted state). When that happens a
 * notification invites the user to open the app instead of failing silently -
 * the app reconnects automatically as soon as it is in the foreground.</p>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        RvpnPrefs prefs = new RvpnPrefs(context);
        if (!prefs.isRvpnMode() || !prefs.autoConnect()) {
            Log.i(TAG, "RVPN mode is off, nothing to restore");
            return;
        }
        if (prefs.validate() != 0) {
            Log.w(TAG, "RVPN mode is on but the settings are incomplete");
            return;
        }
        try {
            RvpnService.start(context, prefs.configJson());
            Log.i(TAG, "RVPN service restarted after " + action);
        } catch (Throwable t) {
            Log.w(TAG, "the platform refused the background start", t);
            try {
                NotificationManagerCompat.from(context).notify(Notifications.ID_STATUS + 1,
                        new androidx.core.app.NotificationCompat.Builder(context,
                                Notifications.CHANNEL_STATUS)
                                .setSmallIcon(R.drawable.ic_stat_rvpn)
                                .setContentTitle(context.getString(R.string.notif_boot_title))
                                .setContentText(context.getString(R.string.engine_start_forbidden))
                                .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle()
                                        .bigText(context.getString(
                                                R.string.engine_start_forbidden)))
                                .setContentIntent(Notifications.activityIntent(context,
                                        new Intent(context, MainActivity.class), 6))
                                .setAutoCancel(true)
                                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                                .build());
            } catch (Throwable inner) {
                Log.w(TAG, "cannot post the boot notification", inner);
            }
        }
    }
}
