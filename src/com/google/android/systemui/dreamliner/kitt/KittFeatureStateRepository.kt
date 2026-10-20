package com.google.android.systemui.dreamliner.kitt

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.settings.UserTracker
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class KittFeatureStateRepository(
    private val userTracker: UserTracker,
    private val userManager: UserManager,
    private val backgroundScope: CoroutineScope,
    backgroundExecutor: Executor,
    broadcastDispatcher: BroadcastDispatcher,
) : UserTracker.Callback {
    private val _enabledState = MutableStateFlow(false)
    val enabledState: StateFlow<Boolean> = _enabledState.asStateFlow()

    private var updateStateJob: Job? = null

    private val userPackageManager: PackageManager
        get() = userTracker.createCurrentUserContext(userTracker.userContext).packageManager

    init {
        updateState()
        userTracker.addCallback(this, backgroundExecutor)
        registerFeatureChangedReceiver(broadcastDispatcher)
        registerUserUnlockedReceiver(broadcastDispatcher)
    }

    override fun onUserChanged(newUser: Int, userContext: Context) {
        Log.d(TAG, "user changed")
        updateState()
    }

    private fun registerFeatureChangedReceiver(broadcastDispatcher: BroadcastDispatcher) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.d(TAG, "dreamliner feature changed")
                    updateState()
                }
            }
        broadcastDispatcher.registerReceiver(
            receiver,
            IntentFilter(ACTION_FEATURE_CHANGED),
            user = UserHandle.ALL,
            permission = PERMISSION_WIRELESS_CHARGER_STATUS,
        )
    }

    private fun registerUserUnlockedReceiver(broadcastDispatcher: BroadcastDispatcher) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.d(TAG, "user unlocked")
                    updateState()
                }
            }
        broadcastDispatcher.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            user = UserHandle.ALL,
        )
    }

    private fun updateState() {
        updateStateJob?.cancel(CancellationException("New event, restart"))
        updateStateJob = backgroundScope.launch {
            try {
                doUpdateState()
            } catch (e: CancellationException) {
                Log.i(TAG, "updateState canceled with reason \"${e.message}\"")
            } catch (e: Exception) {
                Log.w(TAG, "updateState failed", e)
            }
        }
    }

    private suspend fun doUpdateState() {
        if (!userManager.isUserUnlocked(userTracker.userId)) {
            return
        }
        yield()
        _enabledState.value = isFeatureEnabled()
        Log.d(TAG, "updated feature state to ${enabledState.value}")
    }

    private fun isFeatureEnabled(): Boolean =
        try {
            when (userPackageManager.getComponentEnabledSetting(FEATURE_INDICATOR_COMPONENT_NAME)) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ->
                    try {
                        userPackageManager
                            .getReceiverInfo(FEATURE_INDICATOR_COMPONENT_NAME, 0)
                            .enabled
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.i(TAG, "Feature component not found")
                        false
                    }
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "isFeatureEnabled getting info failed", e)
            false
        }

    companion object {
        private const val TAG = "DL2025Feature"
        private const val ACTION_FEATURE_CHANGED =
            "com.google.android.apps.dreamliner.action.FEATURE_CHANGED"
        private const val PERMISSION_WIRELESS_CHARGER_STATUS =
            "com.google.android.systemui.permission.WIRELESS_CHARGER_STATUS"

        val FEATURE_INDICATOR_COMPONENT_NAME: ComponentName =
            ComponentName.createRelative(
                "com.google.android.apps.dreamliner",
                ".settings.kitt.ChargingExperienceReceiver",
            )
    }
}
