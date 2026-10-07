package com.google.android.systemui.power.batteryevent.common.module

import android.content.Intent
import com.android.settingslib.fuelgauge.BatteryStatus
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType
import com.google.android.systemui.power.batteryevent.common.EventDataType
import com.google.android.systemui.power.batteryevent.common.data.SystemEventData

class SevereLowBatteryEventModule : BaseBatteryEventModule() {
    override val moduleType = BatteryEventType.SEVERE_LOW_BATTERY

    override val intentActions = listOf(Intent.ACTION_BATTERY_CHANGED)

    override val eventDataTypes = emptyList<EventDataType>()

    override fun validate(systemEventData: SystemEventData): Boolean {
        val plugged = systemEventData.plugged
        val level = systemEventData.batteryLevel
        val scale = systemEventData.batteryScale
        if (plugged.isChanged || level.isChanged || scale.isChanged) {
            val batteryLevel = BatteryStatus.getBatteryLevel(level.value, scale.value)
            lastValidation =
                batteryLevel <= 10 && batteryLevel > 3 && !BatteryStatus.isPluggedIn(plugged.value)
        }
        return lastValidation
    }
}
