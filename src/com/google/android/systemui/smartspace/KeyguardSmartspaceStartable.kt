package com.google.android.systemui.smartspace

import com.android.systemui.CoreStartable
import com.android.systemui.util.InitializationChecker
import javax.inject.Inject

class KeyguardSmartspaceStartable
@Inject
constructor(
    private val zenController: KeyguardZenAlarmViewController,
    private val mediaController: KeyguardMediaViewController,
    private val initializationChecker: InitializationChecker,
) : CoreStartable {

    override fun start() {
        if (initializationChecker.initializeComponents()) {
            zenController.init()
            mediaController.init()
        }
    }
}
