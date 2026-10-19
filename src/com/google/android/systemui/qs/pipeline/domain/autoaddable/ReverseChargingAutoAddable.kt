package com.google.android.systemui.qs.pipeline.domain.autoaddable

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.pipeline.domain.autoaddable.CallbackControllerAutoAddable
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.statusbar.policy.BatteryController
import com.google.android.systemui.qs.tiles.ReverseChargingTile
import javax.inject.Inject
import kotlinx.coroutines.channels.ProducerScope

@SysUISingleton
class ReverseChargingAutoAddable @Inject constructor(batteryController: BatteryController) :
    CallbackControllerAutoAddable<BatteryController.BatteryStateChangeCallback, BatteryController>(
        batteryController
    ) {

    override val spec: TileSpec = TileSpec.create(ReverseChargingTile.TILE_SPEC)

    override val description: String = "ReverseChargingAutoAddable ($autoAddTracking)"

    override fun ProducerScope<AutoAddSignal>.getCallback():
        BatteryController.BatteryStateChangeCallback {
        return object : BatteryController.BatteryStateChangeCallback {
            override fun onReverseChanged(isReverse: Boolean, level: Int, name: String?) {
                if (isReverse) {
                    sendAdd()
                }
            }
        }
    }
}
