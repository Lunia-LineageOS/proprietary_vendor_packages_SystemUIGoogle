package com.google.android.systemui.dreamliner.kitt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.service.dreams.IDreamManager
import android.util.Log
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.util.time.SystemClock
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class KittCommandReceiver(
    private val clock: SystemClock,
    private val dreamManager: IDreamManager,
    private val powerManager: PowerManager,
    private val backgroundScope: CoroutineScope,
    keyguardInteractorProvider: Provider<KeyguardInteractor>,
) : BroadcastReceiver() {
    private val keyguardInteractor by lazy { keyguardInteractorProvider.get() }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive, action=${intent.action}")
        backgroundScope.launch {
            try {
                when (intent.action) {
                    ACTION_START_DREAM ->
                        if (!keyguardInteractor.isDreaming.value) {
                            dreamManager.dream()
                        }
                    ACTION_WAKE_UP_DREAM ->
                        if (keyguardInteractor.isDreaming.value) {
                            dreamManager.awaken()
                        }
                    ACTION_TURN_OFF_DREAM -> turnOffDream()
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to handle command", e)
            }
        }
    }

    private fun turnOffDream() {
        if (!powerManager.isInteractive) {
            return
        }
        if (
            keyguardInteractor.isDreaming.value ||
                keyguardInteractor.statusBarState.value == StatusBarState.KEYGUARD
        ) {
            Log.d(TAG, "going to sleep for turning off dream")
            powerManager.goToSleep(clock.uptimeMillis())
        }
    }

    companion object {
        private const val TAG = "KittCommandReceiver"
        const val ACTION_START_DREAM = "com.google.android.systemui.dreamliner.action.START_DREAM"
        const val ACTION_WAKE_UP_DREAM =
            "com.google.android.systemui.dreamliner.action.WAKE_UP_DREAM"
        const val ACTION_TURN_OFF_DREAM =
            "com.google.android.systemui.dreamliner.action.TURN_OFF_DREAM"
    }
}
