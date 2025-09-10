package com.google.android.systemui.smartspace

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.text.format.DateFormat
import android.view.View
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.res.R
import com.android.systemui.smartspace.dagger.SmartspaceModule
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.statusbar.policy.NextAlarmControllerImpl
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.statusbar.policy.domain.model.ZenModeInfo
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeyguardZenAlarmViewController
@Inject
constructor(
    val context: Context,
    @Named(SmartspaceModule.DATE_SMARTSPACE_DATA_PLUGIN) val datePlugin: BcSmartspaceDataPlugin,
    val zenModeController: ZenModeController,
    val zenModeInteractor: ZenModeInteractor,
    val alarmManager: AlarmManager,
    val nextAlarmController: NextAlarmControllerImpl,
    @Main val handler: Handler,
    @Application val applicationScope: CoroutineScope,
    @Background val bgDispatcher: CoroutineDispatcher,
) {
    val alarmImage: Drawable = context.resources.getDrawable(R.drawable.ic_access_alarms_big, null)
    val smartspaceViews = mutableSetOf<BcSmartspaceDataPlugin.SmartspaceView>()

    private val nextAlarmCallback = NextAlarmController.NextAlarmChangeCallback {
        updateNextAlarm()
    }

    private val showNextAlarm = AlarmManager.OnAlarmListener { showAlarm(null) }

    fun init() {
        datePlugin.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    addSmartspaceView(view as BcSmartspaceDataPlugin.SmartspaceView)
                    if (smartspaceViews.size == 1) {
                        nextAlarmController.addCallback(nextAlarmCallback)
                    }
                    updateNextAlarm()
                }

                override fun onViewDetachedFromWindow(view: View) {
                    smartspaceViews.remove(view as BcSmartspaceDataPlugin.SmartspaceView)
                    if (smartspaceViews.isEmpty()) {
                        nextAlarmController.removeCallback(nextAlarmCallback)
                    }
                }
            }
        )
        updateNextAlarm()
    }

    private fun addSmartspaceView(view: BcSmartspaceDataPlugin.SmartspaceView) {
        if (smartspaceViews.contains(view)) {
            return
        }
        smartspaceViews.add(view)
        (view as View).repeatWhenAttached {
            zenModeInteractor.mainActiveMode.collect { updateModeIcon(view, it) }
        }
    }

    fun updateNextAlarm() {
        applicationScope.launch {
            alarmManager.cancel(showNextAlarm)
            val nextAlarm = getNextAlarm()
            if (nextAlarm > 0) {
                val showTime = nextAlarm - TimeUnit.HOURS.toMillis(12L)
                if (showTime > 0) {
                    alarmManager.setExact(
                        AlarmManager.RTC,
                        showTime,
                        "lock_screen_next_alarm",
                        showNextAlarm,
                        handler,
                    )
                }
            }
            showAlarm(nextAlarm)
        }
    }

    fun showAlarm(alarm: Long?): Job = applicationScope.launch {
        val nextAlarm = alarm ?: getNextAlarm()
        if (
            nextAlarm > 0 && nextAlarm <= System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12L)
        ) {
            val pattern =
                if (DateFormat.is24HourFormat(context, ActivityManager.getCurrentUser())) {
                    "HH:mm"
                } else {
                    "h:mm"
                }
            val alarmString = DateFormat.format(pattern, nextAlarm).toString()
            smartspaceViews.forEach { it.setNextAlarm(alarmImage, alarmString) }
            zenModeInteractor.setHasNextAlarm(true)
        } else {
            smartspaceViews.forEach { it.setNextAlarm(null, null) }
            zenModeInteractor.setHasNextAlarm(false)
        }
    }

    private suspend fun getNextAlarm(): Long =
        withContext(bgDispatcher) { zenModeController.nextAlarm }

    fun updateModeIcon(view: BcSmartspaceDataPlugin.SmartspaceView, zenModeInfo: ZenModeInfo?) {
        applicationScope.launch {
            if (zenModeInfo != null) {
                val description =
                    context.getString(R.string.active_mode_content_description, zenModeInfo.name)
                view.setDnd(zenModeInfo.icon.drawable, description)
            } else {
                view.setDnd(null, null)
            }
        }
    }
}
