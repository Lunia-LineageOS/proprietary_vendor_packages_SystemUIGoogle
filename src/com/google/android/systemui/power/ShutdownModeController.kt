package com.google.android.systemui.power

import android.app.Dialog
import android.app.StatusBarManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.fuelgauge.BatterySaverLogging
import com.android.settingslib.fuelgauge.BatterySaverUtils
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R as AR
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.time.SystemClock
import com.google.android.systemui.res.R
import javax.inject.Inject

class ShutdownModeController
@Inject
constructor(
    val context: Context,
    val broadcastSender: BroadcastSender,
    val systemUIDialogFactory: SystemUIDialog.Factory,
    val statusBarManager: StatusBarManager,
    val activityStarter: ActivityStarter,
    val flashlightController: FlashlightController,
    val globalSettings: GlobalSettings,
    val wifiManager: WifiManager?,
    val systemClock: SystemClock,
    val uiEventLogger: UiEventLogger?,
) {
    var isShutdownModeEnabled = false
    var dialog: Dialog? = null
    var confirmDialog: SystemUIDialog? = null
    var postShutdownModeDialog: SystemUIDialog? = null

    val sharedPreferences: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(
            "shutdown_mode_preference",
            Context.MODE_PRIVATE,
        )
    }

    fun checkShutdownModeState() {
        if (systemClock.elapsedRealtime() <= CHECK_STATE_MIN_UPTIME_MILLIS) {
            return
        }
        val intent = Intent(ACTION_CHECK_SHUTDOWN_MODE_STATE).setPackage(TURBO_PACKAGE)
        broadcastSender.sendBroadcastAsUser(intent, UserHandle.CURRENT)
        uiEventLogger?.log(BatteryMetricEvent.CHECK_SHUTDOWN_MODE_STATE)
    }

    fun onShutdownModeUpdate(intent: Intent) {
        val enabled = intent.getBooleanExtra(EXTRA_SHUTDOWN_MODE_ENABLED, false)
        if (enabled == isShutdownModeEnabled) {
            return
        }
        isShutdownModeEnabled = enabled
        if (enabled) {
            showShutdownModeScreen()
        } else {
            dismissShutdownModeScreen()
            confirmDialog?.cancel()
            confirmDialog = null
        }
    }

    fun recover() {
        if (sharedPreferences.contains(KEY_RECOVER_STATUS_BAR)) {
            setStatusBarEnable(sharedPreferences.getBoolean(KEY_RECOVER_STATUS_BAR, false), false)
            sharedPreferences.edit().remove(KEY_RECOVER_STATUS_BAR).apply()
        }
        if (sharedPreferences.contains(KEY_RECOVER_POWER_BUTTON_LONG_PRESS)) {
            setPowerButtonLongPressState(
                sharedPreferences.getInt(
                    KEY_RECOVER_POWER_BUTTON_LONG_PRESS,
                    defaultPowerButtonLongPressBehavior(),
                ),
                false,
            )
            sharedPreferences.edit().remove(KEY_RECOVER_POWER_BUTTON_LONG_PRESS).apply()
        }
        if (sharedPreferences.contains(KEY_RECOVER_AIRPLANE_MODE)) {
            setAirplaneModeEnable(sharedPreferences.getInt(KEY_RECOVER_AIRPLANE_MODE, 0), false)
            sharedPreferences.edit().remove(KEY_RECOVER_AIRPLANE_MODE).apply()
        }
        if (wifiManager != null && sharedPreferences.contains(KEY_RECOVER_WIFI)) {
            setWifiEnable(sharedPreferences.getBoolean(KEY_RECOVER_WIFI, false), false)
            sharedPreferences.edit().remove(KEY_RECOVER_WIFI).apply()
        }
        uiEventLogger?.log(BatteryMetricEvent.RECOVER_SHUTDOWN_MODE)
    }

    fun dismissShutdownModeScreen() {
        Log.d(TAG, "Dismiss shutdown mode screen")
        recover()
        if (dialog?.isShowing != true) {
            return
        }
        dialog?.dismiss()
        dialog = null
        showPostShutdownModeDialog()
        uiEventLogger?.log(BatteryMetricEvent.DISMISS_SHUTDOWN_MODE_SCREEN)
    }

    fun setStatusBarEnable(enable: Boolean, saveForRecovery: Boolean) {
        val current = sharedPreferences.getBoolean(KEY_RECOVER_STATUS_BAR, true)
        if (current == enable) {
            return
        }
        statusBarManager.disable2(
            if (enable) StatusBarManager.DISABLE2_NONE else StatusBarManager.DISABLE2_GLOBAL_ACTIONS
        )
        if (saveForRecovery) {
            sharedPreferences.edit().putBoolean(KEY_RECOVER_STATUS_BAR, current).apply()
        }
    }

    fun setPowerButtonLongPressState(state: Int, saveForRecovery: Boolean) {
        val current =
            globalSettings.getInt(
                Settings.Global.POWER_BUTTON_LONG_PRESS,
                defaultPowerButtonLongPressBehavior(),
            )
        if (current == state) {
            return
        }
        globalSettings.putInt(Settings.Global.POWER_BUTTON_LONG_PRESS, state)
        if (saveForRecovery) {
            sharedPreferences.edit().putInt(KEY_RECOVER_POWER_BUTTON_LONG_PRESS, current).apply()
        }
    }

    fun setAirplaneModeEnable(state: Int, saveForRecovery: Boolean) {
        val current = globalSettings.getInt(Settings.Global.AIRPLANE_MODE_ON, 0)
        if (state == current) {
            return
        }
        globalSettings.putInt(Settings.Global.AIRPLANE_MODE_ON, state)
        val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", state)
        broadcastSender.sendBroadcastAsUser(intent, UserHandle.ALL)
        if (saveForRecovery) {
            sharedPreferences.edit().putInt(KEY_RECOVER_AIRPLANE_MODE, current).apply()
        }
    }

    fun setWifiEnable(enable: Boolean, saveForRecovery: Boolean) {
        val current = wifiManager?.isWifiEnabled ?: return
        if (current == enable) {
            return
        }
        wifiManager.setWifiEnabled(enable)
        if (saveForRecovery) {
            sharedPreferences.edit().putBoolean(KEY_RECOVER_WIFI, current).apply()
        }
    }

    private fun showShutdownModeScreen() {
        Log.d(TAG, "Show shutdown mode screen")
        setStatusBarEnable(false, true)
        setPowerButtonLongPressState(0, true)
        setAirplaneModeEnable(1, true)
        setWifiEnable(false, true)
        if (dialog == null) {
            createShutdownModeScreen()
        }
        if (dialog?.isShowing != true) {
            dialog?.show()
            uiEventLogger?.log(BatteryMetricEvent.SHOW_SHUTDOWN_MODE_SCREEN)
        }
        if (
            flashlightController.hasFlashlight() &&
                flashlightController.isAvailable &&
                flashlightController.isEnabled
        ) {
            flashlightController.setFlashlight(false)
        }
    }

    private fun createShutdownModeScreen() {
        val newDialog =
            Dialog(createDarkThemeContext(), AR.style.Theme_SystemUI_Dialog_GlobalActions)
        dialog = newDialog
        newDialog.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            attributes.systemUiVisibility =
                attributes.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            // Inflate the decor view, so the attributes below are not overwritten by the theme.
            decorView
            attributes.width = ViewGroup.LayoutParams.MATCH_PARENT
            attributes.height = ViewGroup.LayoutParams.MATCH_PARENT
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setType(WindowManager.LayoutParams.TYPE_BOOT_PROGRESS)
            attributes.setFitInsetsTypes(0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            setWindowAnimations(AR.style.Animation_ShutdownUi)
        }
        dialog?.setContentView(R.layout.shutdown_mode_dialog)
        dialog?.setCancelable(false)
        dialog?.findViewById<LinearLayout>(R.id.shutdown_button)?.setOnClickListener {
            showShutdownConfirmDialog()
            uiEventLogger?.log(BatteryMetricEvent.CLICK_SHUTDOWN_MODE_SCREEN_SHUTDOWN_BUTTON)
        }
    }

    private fun showShutdownConfirmDialog() {
        if (confirmDialog?.isShowing == true) {
            return
        }
        confirmDialog?.let {
            it.show()
            return
        }
        val newDialog = systemUIDialogFactory.create(createDarkThemeContext())
        confirmDialog = newDialog
        newDialog.window?.setType(WindowManager.LayoutParams.TYPE_BOOT_PROGRESS)
        newDialog.setTitle(R.string.shutdown_confirmation_dialog_title)
        newDialog.setMessage(R.string.shutdown_confirmation_dialog_description)
        SystemUIDialog.setShowForAllUsers(newDialog, true)
        newDialog.setCanceledOnTouchOutside(true)
        newDialog.setPositiveButton(R.string.shutdown_confirmation_dialog_confirm) { _, _ ->
            BatterySaverUtils.setPowerSaveMode(
                context,
                false,
                false,
                BatterySaverLogging.SAVER_ENABLED_UNKNOWN,
            )
            val intent =
                Intent(Intent.ACTION_REQUEST_SHUTDOWN)
                    .putExtra(Intent.EXTRA_KEY_CONFIRM, false)
                    // ShutdownActivity reads EXTRA_REASON as a String, so this int is dropped.
                    .putExtra(Intent.EXTRA_REASON, PowerManager.SHUTDOWN_REASON_USER_REQUESTED)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivityAsUser(intent, UserHandle.CURRENT)
            confirmDialog?.cancel()
            confirmDialog = null
            dismissShutdownModeScreen()
            uiEventLogger?.log(
                BatteryMetricEvent.CLICK_SHUTDOWN_MODE_CONFIRM_DIALOG_SHUTDOWN_BUTTON
            )
        }
        newDialog.setNegativeButton(
            R.string.shutdown_confirmation_dialog_cancel,
            { _, _ ->
                confirmDialog?.cancel()
                confirmDialog = null
            },
            true,
        )
        newDialog.show()
    }

    private fun showPostShutdownModeDialog() {
        if (postShutdownModeDialog?.isShowing == true) {
            return
        }
        val newDialog = systemUIDialogFactory.create()
        newDialog.setIcon(AR.drawable.ic_device_thermostat_24)
        newDialog.setTitle(R.string.post_shutdown_mode_dialog_title)
        newDialog.setMessage(R.string.post_shutdown_mode_dialog_description)
        newDialog.setPositiveButton(com.android.internal.R.string.close_button_text, null)
        SystemUIDialog.setShowForAllUsers(newDialog, true)
        newDialog.setOnDismissListener { postShutdownModeDialog = null }
        val url = context.getString(AR.string.thermal_shutdown_dialog_help_url)
        if (url.isNotEmpty()) {
            newDialog.setNeutralButton(
                R.string.post_shutdown_mode_dialog_learn_more_button_text,
                { _, _ ->
                    activityStarter.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setData(Uri.parse(url))
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        true,
                    )
                    postShutdownModeDialog?.cancel()
                    postShutdownModeDialog = null
                },
                true,
            )
        }
        newDialog.show()
        postShutdownModeDialog = newDialog
        uiEventLogger?.log(BatteryMetricEvent.SHOW_POST_SHUTDOWN_MODE_DIALOG)
    }

    private fun createDarkThemeContext(): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_YES
        return context.createConfigurationContext(configuration)
    }

    private fun defaultPowerButtonLongPressBehavior() =
        context.resources.getInteger(com.android.internal.R.integer.config_longPressOnPowerBehavior)

    companion object {
        private const val TAG = "ShutdownModeController"
        const val ACTION_SHUTDOWN_MODE_UPDATE =
            "com.google.android.systemui.power.ACTION_SHUTDOWN_MODE_UPDATE"
        private const val ACTION_CHECK_SHUTDOWN_MODE_STATE =
            "com.google.android.apps.turbo.CHECK_SHUTDOWN_MODE_STATE"
        private const val TURBO_PACKAGE = "com.google.android.apps.turbo"
        private const val EXTRA_SHUTDOWN_MODE_ENABLED = "shutdown_mode_enabled"
        private const val CHECK_STATE_MIN_UPTIME_MILLIS = 600000L
        private const val KEY_RECOVER_STATUS_BAR = "recover_status_bar"
        private const val KEY_RECOVER_POWER_BUTTON_LONG_PRESS = "recover_power_button_long_press"
        private const val KEY_RECOVER_AIRPLANE_MODE = "recover_airplane_mode"
        private const val KEY_RECOVER_WIFI = "recover_wifi"
    }
}
