package com.google.android.systemui.power

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.IBinder
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.fuelgauge.BatteryStatus
import com.android.systemui.util.time.SystemClock
import com.google.android.systemui.googlebattery.GoogleBatteryManager
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType
import com.google.android.systemui.res.R
import java.io.PrintWriter
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vendor.google.google_battery.BatteryDefendType
import vendor.google.google_battery.Feature
import vendor.google.google_battery.IGoogleBattery

class BatteryDefenderNotificationHandler(
    val context: Context,
    val uiEventLogger: UiEventLogger?,
    val systemClock: SystemClock,
    val mainDispatcher: CoroutineDispatcher,
    val notificationManager: NotificationManager?,
    val backgroundDispatcher: CoroutineDispatcher,
) {
    var dockDefendEnabled = context.resources.getBoolean(R.bool.config_tablet_device)
    val backgroundCoroutineScope = CoroutineScope(backgroundDispatcher + SupervisorJob())
    var batteryLevel = -1
    var inDefenderSection = false

    private val sharedPreferences: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(
            "defender_shared_prefs",
            Context.MODE_PRIVATE,
        )
    }
    private val dockDefenderNotification by lazy {
        DockDefenderNotification(context, notificationManager)
    }
    private val dwellTempDefenderNotification by lazy {
        DwellTempDefenderNotification(context, notificationManager, uiEventLogger)
    }

    fun dispatchIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> onActionBootCompleted()
            ACTION_RESUME_CHARGING_SETTINGS -> onActionResumeChargingSettings(intent)
            DwellTempDefenderNotification.ACTION_DISMISS_DEFENDER_WARNING ->
                dwellTempDefenderNotification.swipeNotificationByUser()
            DwellTempDefenderNotification.ACTION_RESUME_CHARGING -> onActionResumeChargingIntent()
        }
    }

    fun onBatteryEventUpdate(events: List<BatteryEventType>, level: Int, pluggedType: Int) {
        batteryLevel = level
        when {
            events.contains(BatteryEventType.DOCK_DEFEND_BATTERY) -> onDockDefenderEvent()
            events.contains(BatteryEventType.TEMP_DEFEND_BATTERY) ->
                onBatteryDefenderEvent(
                    pluggedType,
                    DwellTempDefenderNotification.BatteryDefendType.TEMP_DEFEND,
                )
            events.contains(BatteryEventType.DWELL_DEFEND_BATTERY) ->
                onBatteryDefenderEvent(
                    pluggedType,
                    DwellTempDefenderNotification.BatteryDefendType.DWELL_DEFEND,
                )
            else -> onNonDefenderEvent(pluggedType)
        }
    }

    fun dump(pw: PrintWriter) {
        pw.println("\tdump BatteryDefenderNotificationHandler states:")
        pw.println("\t\tdockDefendFirstRun: ${isDockDefendFirstRun()}")
        pw.println("\t\tdockDefendBypassed: ${isDockDefendBypassed()}")
        pw.println("\t\tdockDefendEnabled: $dockDefendEnabled")
        pw.println("\t\tinBatteryDefenderSection: $inDefenderSection")
        pw.println("\t\tbatteryLevel: $batteryLevel")
        pw.println("\t\tstartTimestamp: ${sharedPreferences.getLong(KEY_TRIGGER_TIME, -1L)}")
        pw.println("\t\tcurrentTimestamp: ${systemClock.currentTimeMillis()}")
    }

    private fun onDockDefenderEvent() {
        val dockDefend1RunStatus = !isDockDefendBypassed() && isDockDefendFirstRun()
        Log.d(TAG, "onDockDefenderEvent: dockDefend1RunStatus: $dockDefend1RunStatus")
        if (dockDefend1RunStatus) {
            Settings.Global.putInt(context.contentResolver, DOCK_DEFENDER_FIRST_RUN, 1)
            dockDefenderNotification.showNotification()
        }
    }

    private fun onBatteryDefenderEvent(
        pluggedType: Int,
        type: DwellTempDefenderNotification.BatteryDefendType,
    ) {
        val pluggedIn = BatteryStatus.isPluggedIn(pluggedType)
        Log.d(
            TAG,
            "onBatteryDefenderEvent, pluggedIn:$pluggedIn, inDefenderSection:$inDefenderSection",
        )
        if (inDefenderSection) {
            dwellTempDefenderNotification.updateNotificationIfNeeded(pluggedIn, type)
            return
        }
        inDefenderSection = true
        dwellTempDefenderNotification.showNotification(pluggedIn, type)
        uiEventLogger?.log(BatteryMetricEvent.BATTERY_DEFENDER_NOTIFICATION)
        backgroundCoroutineScope.launch { putDefenderStartTimestamp() }
    }

    private fun onNonDefenderEvent(pluggedType: Int) {
        val charged = batteryLevel >= 100
        Log.d(TAG, "onNonDefenderEvent: charged: $charged")
        if (dockDefendEnabled && inDefenderSection) {
            Log.d(TAG, "dockDefendEnabled:true, no post notification")
            inDefenderSection = false
            dwellTempDefenderNotification.cancelNotification()
            exitTempOrDwellDefendOnTablet()
            return
        }
        if (dockDefendEnabled) {
            if (pluggedType == BatteryManager.BATTERY_PLUGGED_DOCK) {
                return
            }
            dockDefenderNotification.cancelNotification()
            exitDockDefenderIfNeeded()
            return
        }
        if (charged) {
            dwellTempDefenderNotification.cancelPostNotification()
        }
        if (inDefenderSection) {
            inDefenderSection = false
            dwellTempDefenderNotification.cancelNotification()
            exitTempOrDwellDefenderIfNeeded()
        }
    }

    private fun onActionBootCompleted() {
        if (!dockDefendEnabled) {
            return
        }
        backgroundCoroutineScope.launch {
            withGoogleBattery {
                Log.d(TAG, "enable dock defend")
                it.setEnable(Feature.DOCK_DEFEND, true)
            }
            Settings.Global.putInt(context.contentResolver, DOCK_DEFENDER_BYPASS, 0)
        }
    }

    private fun onActionResumeChargingSettings(intent: Intent) {
        Log.d(TAG, "onActionResumeChargingSettings")
        uiEventLogger?.logWithPosition(
            BatteryMetricEvent.BATTERY_DEFENDER_BYPASS_LIMIT_FOR_TIPS,
            0,
            null,
            batteryLevel,
        )
        dwellTempDefenderNotification.cancelNotification()
        val bypassMode =
            if (intent.getBooleanExtra(EXTRA_IS_DOCK_DEFENDER, false)) {
                BatteryDefendType.DOCK_DEFEND
            } else {
                BatteryDefendType.ALL
            }
        backgroundCoroutineScope.launch { bypassDefenderMode(bypassMode) }
    }

    private fun onActionResumeChargingIntent() {
        Log.d(TAG, "onActionResumeChargingIntent")
        uiEventLogger?.logWithPosition(
            BatteryMetricEvent.BATTERY_DEFENDER_BYPASS_LIMIT,
            0,
            null,
            batteryLevel,
        )
        dwellTempDefenderNotification.cancelNotification()
        backgroundCoroutineScope.launch { bypassDefenderMode(BatteryDefendType.ALL) }
    }

    private suspend fun bypassDefenderMode(bypassMode: Int) {
        if (bypassMode == BatteryDefendType.DOCK_DEFEND) {
            Settings.Global.putInt(context.contentResolver, DOCK_DEFENDER_BYPASS, 1)
        }
        withGoogleBattery { it.clearBatteryDefenders(bypassMode) }
    }

    private fun exitDockDefenderIfNeeded() {
        backgroundCoroutineScope.launch {
            Settings.Global.putInt(context.contentResolver, DOCK_DEFENDER_BYPASS, 0)
        }
    }

    private fun exitTempOrDwellDefendOnTablet() {
        backgroundCoroutineScope.launch { clearDefenderStartTimestamp() }
    }

    private fun exitTempOrDwellDefenderIfNeeded() {
        backgroundCoroutineScope.launch {
            val endTimestamp = systemClock.currentTimeMillis()
            val startTimestamp = loadDefenderStartTimestamp()
            val duration =
                if (startTimestamp == -1L || endTimestamp <= startTimestamp) {
                    Log.w(TAG, "no defender start timestamp")
                    0L
                } else {
                    endTimestamp - startTimestamp
                }
            val startTime = formatTime(startTimestamp)
            val endTime = formatTime(endTimestamp)
            Log.d(
                TAG,
                "battery defender, startTime: $startTime, endTime:$endTime, duration:$duration ms",
            )
            if (duration > POST_NOTIFICATION_THRESHOLD_MILLIS) {
                withContext(mainDispatcher) {
                    dwellTempDefenderNotification.showPostNotification(startTime, endTime)
                }
            }
            clearDefenderStartTimestamp()
        }
    }

    private fun formatTime(timestamp: Long): String {
        val locale = PowerUtils.getLocale(context)
        val skeleton = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:m"
        return DateFormat.format(DateFormat.getBestDateTimePattern(locale, skeleton), timestamp)
            .toString()
            .uppercase(locale)
    }

    private suspend fun putDefenderStartTimestamp() =
        withContext(backgroundDispatcher) {
            val timestamp = systemClock.currentTimeMillis()
            Log.d(TAG, "putDefenderStartTimestamp: $timestamp")
            sharedPreferences.edit().putLong(KEY_TRIGGER_TIME, timestamp).apply()
        }

    private suspend fun loadDefenderStartTimestamp(): Long =
        withContext(backgroundDispatcher) {
            val timestamp = sharedPreferences.getLong(KEY_TRIGGER_TIME, -1L)
            Log.d(TAG, "loadDefenderStartTimestamp: $timestamp")
            timestamp
        }

    private suspend fun clearDefenderStartTimestamp() =
        withContext(backgroundDispatcher) {
            Log.d(TAG, "clearDefenderStartTimestamp")
            sharedPreferences.edit().clear().apply()
        }

    private suspend fun withGoogleBattery(action: (IGoogleBattery) -> Unit) =
        withContext(backgroundDispatcher) {
            val deathRecipient = IBinder.DeathRecipient {
                Log.e("GoogleBatteryDataSource", "Service died!!")
            }
            val googleBattery = GoogleBatteryManager.initHalInterface(deathRecipient)
            try {
                if (googleBattery == null) {
                    Log.w(TAG, "operation failed. cannot init hal interface")
                    return@withContext
                }
                action(googleBattery)
            } catch (e: Exception) {
                Log.e(TAG, "operation error: ", e)
            } finally {
                GoogleBatteryManager.destroyHalInterface(googleBattery, deathRecipient)
            }
        }

    private fun isDockDefendFirstRun() =
        Settings.Global.getInt(context.contentResolver, DOCK_DEFENDER_FIRST_RUN, -1) == -1

    private fun isDockDefendBypassed() =
        Settings.Global.getInt(context.contentResolver, DOCK_DEFENDER_BYPASS, 0) == 1

    companion object {
        private const val TAG = "BatteryDefenderNotification"
        private const val KEY_TRIGGER_TIME = "trigger_time"
        private const val DOCK_DEFENDER_BYPASS = "dock_defender_bypass"
        private const val DOCK_DEFENDER_FIRST_RUN = "dock_defender_first_run"
        private const val EXTRA_IS_DOCK_DEFENDER = "is_dock_defender"
        const val ACTION_RESUME_CHARGING_SETTINGS = "PNW.defenderResumeCharging.settings"
        private val POST_NOTIFICATION_THRESHOLD_MILLIS = 10.minutes.inWholeMilliseconds
    }
}
