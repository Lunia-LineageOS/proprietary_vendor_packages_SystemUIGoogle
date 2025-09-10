package com.google.android.systemui.smartspace

import android.app.smartspace.SmartspaceTarget
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.View
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo

class BcSmartspaceRemoteViewsCard(context: Context) : AppWidgetHostView(context), SmartspaceCard {
    var _loggingInfo: BcSmartspaceCardLoggingInfo? = null
    var _target: SmartspaceTarget? = null
    var _uiSurface: String? = null

    init {
        setOnLongClickListener(null)
        if (_uiSurface == BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD) {
            super.setInteractionHandler(null)
        }
    }

    override fun bindData(
        target: SmartspaceTarget,
        eventNotifier: BcSmartspaceDataPlugin.SmartspaceEventNotifier?,
        loggingInfo: BcSmartspaceCardLoggingInfo?,
        usePageIndicatorUi: Boolean,
    ) {
        updateAppWidget(null)
        _target = target
        _loggingInfo = loggingInfo
        updateAppWidget(target.remoteViews)
        val headerAction = target.headerAction
        if (headerAction == null) {
            setOnClickListener(null)
            super.setInteractionHandler(null)
            return
        }
        BcSmartSpaceUtil.setOnClickListener(
            this,
            target,
            headerAction,
            eventNotifier,
            "BcSmartspaceRemoteViewsCard",
            loggingInfo,
            0,
        )
        if (_uiSurface == BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD) {
            super.setInteractionHandler(
                BcSmartSpaceUtil.InteractionHandler(
                    loggingInfo,
                    eventNotifier,
                    target,
                    headerAction,
                )
            )
        }
    }

    override fun getLoggingInfo(): BcSmartspaceCardLoggingInfo =
        _loggingInfo
            ?: BcSmartspaceCardLoggingInfo(
                BcSmartspaceCardLoggingInfo.Builder()
                    .setDisplaySurface(BcSmartSpaceUtil.getLoggingDisplaySurface(_uiSurface, 0f))
                    .setFeatureType(_target?.featureType ?: 0)
                    .setUid(-1)
            )

    override fun getView(): View = this

    override fun setDozeAmount(dozeAmount: Float) {}

    override fun setPrimaryTextColor(color: Int) {}

    override fun setScreenOn(screenOn: Boolean) {}
}
