package com.google.android.systemui.power

import android.app.NotificationManager
import android.content.Context
import android.os.UserHandle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.android.internal.logging.UiEventLogger
import com.google.android.systemui.res.R

class DwellTempDefenderNotification(
    val context: Context,
    val notificationManager: NotificationManager?,
    val uiEventLogger: UiEventLogger?,
) {
    var notificationVisible = false
    var postNotificationVisible = false
    var lastPlugged = false

    enum class BatteryDefendType {
        TEMP_DEFEND,
        DWELL_DEFEND,
    }

    fun showNotification(plugged: Boolean, type: BatteryDefendType) {
        Log.d(TAG, "showNotification, postNotificationVisible:$postNotificationVisible->true")
        if (postNotificationVisible) {
            cancelPostNotification()
        }
        sendDefenderNotification(plugged, type)
        notificationVisible = true
        lastPlugged = plugged
    }

    fun updateNotificationIfNeeded(plugged: Boolean, type: BatteryDefendType) {
        Log.d(TAG, "updateNotificationIfNeeded, notificationVisible:$notificationVisible")
        if (!notificationVisible || plugged == lastPlugged) {
            return
        }
        lastPlugged = plugged
        sendDefenderNotification(plugged, type)
    }

    fun showPostNotification(startTime: String, endTime: String) {
        Log.d(TAG, "showPostNotification, postNotificationVisible:$postNotificationVisible->true")
        postNotificationVisible = true
        sendPostNotification(startTime, endTime)
    }

    fun swipeNotificationByUser() {
        Log.d(TAG, "swipeNotificationByUser, notificationVisible:$notificationVisible->false")
        notificationVisible = false
        uiEventLogger?.log(BatteryMetricEvent.DELETE_BATTERY_DEFENDER_NOTIFICATION)
    }

    fun cancelNotification() {
        Log.d(TAG, "cancelNotification, notificationVisible:$notificationVisible->false")
        notificationVisible = false
        notificationManager?.cancelAsUser(
            NOTIFICATION_TAG,
            R.string.defender_notify_title,
            UserHandle.ALL,
        )
    }

    fun cancelPostNotification() {
        Log.d(
            TAG,
            "swipeNotificationByUser, postNotificationVisible:$postNotificationVisible->false",
        )
        postNotificationVisible = false
        notificationManager?.cancelAsUser(
            NOTIFICATION_TAG,
            R.string.defender_post_notify_title,
            UserHandle.ALL,
        )
    }

    fun sendDefenderNotification(plugged: Boolean, type: BatteryDefendType) {
        Log.d(TAG, "sendDefenderNotification, plugged:$plugged, type: $type")
        val title =
            context.getString(
                when (type) {
                    BatteryDefendType.TEMP_DEFEND -> R.string.temp_defender_notify_title
                    BatteryDefendType.DWELL_DEFEND -> R.string.dwell_defender_notify_title
                }
            )
        val description =
            context.getString(
                when (type) {
                    BatteryDefendType.TEMP_DEFEND -> R.string.temp_defender_notify_des
                    BatteryDefendType.DWELL_DEFEND -> R.string.dwell_defender_notify_des
                }
            )
        val builder =
            NotificationCompat.Builder(context, "BAT")
                .setSmallIcon(R.drawable.ic_battery_with_shield)
                .setContentTitle(title)
                .setContentText(description)
                .setStyle(NotificationCompat.BigTextStyle().bigText(description))
                .setContentIntent(PowerUtils.createBatterySettingsPendingIntentAsUser(context))
                .setSilent(true)
                .setDeleteIntent(
                    PowerUtils.createPendingIntent(context, ACTION_DISMISS_DEFENDER_WARNING, null)
                )
                .addAction(
                    0,
                    context.getString(R.string.battery_health_notify_learn_more),
                    PowerUtils.createHelpArticlePendingIntentAsUser(
                        R.string.defender_notify_help_url,
                        context,
                    ),
                )
                .setLocalOnly(true)
        if (plugged) {
            builder.addAction(
                0,
                context.getString(R.string.defender_notify_resume_charge),
                PowerUtils.createPendingIntent(context, ACTION_RESUME_CHARGING, null),
            )
        }
        PowerUtils.overrideNotificationAppName(context, builder)
        notificationManager?.notifyAsUser(
            NOTIFICATION_TAG,
            R.string.defender_notify_title,
            builder.build(),
            UserHandle.ALL,
        )
    }

    private fun sendPostNotification(startTime: String, endTime: String) {
        Log.d(TAG, "sendPostNotification, startTime: $startTime, endTime: $endTime")
        val description = context.getString(R.string.defender_post_notify_des, startTime, endTime)
        val builder =
            NotificationCompat.Builder(context, "BAT")
                .setSmallIcon(R.drawable.ic_battery_with_shield)
                .setContentTitle(context.getString(R.string.defender_post_notify_title))
                .setContentText(description)
                .setStyle(NotificationCompat.BigTextStyle().bigText(description))
                .setContentIntent(PowerUtils.createBatterySettingsPendingIntentAsUser(context))
                .setSilent(true)
                .addAction(
                    0,
                    context.getString(R.string.battery_health_notify_learn_more),
                    PowerUtils.createHelpArticlePendingIntentAsUser(
                        R.string.defender_notify_help_url,
                        context,
                    ),
                )
                .setLocalOnly(true)
        PowerUtils.overrideNotificationAppName(context, builder)
        notificationManager?.notifyAsUser(
            NOTIFICATION_TAG,
            R.string.defender_post_notify_title,
            builder.build(),
            UserHandle.ALL,
        )
    }

    companion object {
        private const val TAG = "DwellTempDefenderNotification"
        private const val NOTIFICATION_TAG = "battery_defender"
        const val ACTION_RESUME_CHARGING = "PNW.defenderResumeCharging"
        const val ACTION_DISMISS_DEFENDER_WARNING =
            "systemui.power.action.dismissBatteryDefenderWarning"
    }
}
