package com.google.android.systemui.power

import android.app.NotificationManager
import android.content.Context
import android.os.UserHandle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.systemui.res.R

class DockDefenderNotification(
    val context: Context,
    val notificationManager: NotificationManager?,
) {
    fun showNotification() {
        Log.d(TAG, "showNotification")
        val description = context.getString(R.string.dock_defender_first_run_des)
        val builder =
            NotificationCompat.Builder(context, "BAT")
                .setSmallIcon(R.drawable.ic_battery_with_shield)
                .setContentTitle(context.getString(R.string.dock_defender_first_run_title))
                .setContentText(description)
                .setContentIntent(PowerUtils.createBatterySettingsPendingIntentAsUser(context))
                .setStyle(NotificationCompat.BigTextStyle().bigText(description))
                .setSilent(true)
                .setAutoCancel(true)
                .addAction(
                    0,
                    context.getString(R.string.battery_health_notify_learn_more),
                    PowerUtils.createHelpArticlePendingIntentAsUser(
                        R.string.dock_defender_notify_helper_url,
                        context,
                    ),
                )
                .addAction(
                    0,
                    context.getString(R.string.dock_defender_first_run_settings),
                    PowerUtils.createBatterySettingsPendingIntentAsUser(context),
                )
                .setLocalOnly(true)
        PowerUtils.overrideNotificationAppName(context, builder)
        notificationManager?.notifyAsUser(
            NOTIFICATION_TAG,
            R.string.dock_defender_first_run_title,
            builder.build(),
            UserHandle.ALL,
        )
    }

    fun cancelNotification() {
        Log.d(TAG, "cancelNotification")
        notificationManager?.cancelAsUser(
            NOTIFICATION_TAG,
            R.string.dock_defender_first_run_title,
            UserHandle.ALL,
        )
    }

    private companion object {
        const val TAG = "DockDefenderNotification"
        const val NOTIFICATION_TAG = "battery_defender"
    }
}
