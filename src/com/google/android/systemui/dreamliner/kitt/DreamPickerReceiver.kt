package com.google.android.systemui.dreamliner.kitt

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.RemoteException
import android.os.ResultReceiver
import android.service.dreams.DreamService
import android.service.dreams.IDreamManager
import android.util.Log
import com.android.internal.R
import com.android.systemui.settings.UserTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DreamPickerReceiver(
    private val dreamManager: IDreamManager,
    private val userTracker: UserTracker,
    private val backgroundScope: CoroutineScope,
) : BroadcastReceiver() {

    private data class DreamMetadataInfo(
        val settingsActivity: ComponentName?,
        val previewImageResId: Int,
        val showComplications: Boolean,
        val dreamCategory: Int,
        val userSelectable: Boolean,
    )

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) {
            Log.w(TAG, "intent is null")
            return
        }
        Log.i(TAG, "received action: ${intent.action}")
        when (intent.action) {
            ACTION_PREVIEW -> backgroundScope.launch { launchPreview(intent) }
            ACTION_LOAD_METADATA -> backgroundScope.launch { sendDreamMetadataList(intent) }
            ACTION_LAUNCH_SETTINGS -> backgroundScope.launch { launchSettings(context, intent) }
            else -> Log.w(TAG, "not support action: ${intent.action}")
        }
    }

    private fun launchPreview(intent: Intent) {
        val pkgName = intent.getStringExtra(EXTRA_DREAM_PKG_NAME)
        val clsName = intent.getStringExtra(EXTRA_DREAM_CLS_NAME)
        if (pkgName.isNullOrBlank() || clsName.isNullOrBlank()) {
            Log.w(TAG, "failed to launch preview. pkgName: $pkgName, clsName: $clsName")
            return
        }
        try {
            dreamManager.testDream(userTracker.userId, ComponentName(pkgName, clsName))
        } catch (e: RemoteException) {
            Log.e(TAG, "failed to launch preview", e)
        }
    }

    private fun sendDreamMetadataList(intent: Intent) {
        val resultReceiver =
            intent.getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
        if (resultReceiver == null) {
            Log.w(TAG, "no resultReceiver found from intent")
            return
        }
        val (resultCode, resultData) =
            try {
                Activity.RESULT_OK to loadDreamMetadataList()
            } catch (e: Exception) {
                Log.e(TAG, "failed to load dream metadata list")
                Activity.RESULT_FIRST_USER to Bundle.EMPTY
            }
        try {
            resultReceiver.send(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "failed to send resultReceiver", e)
        }
    }

    private fun launchSettings(context: Context, intent: Intent) {
        val pkgName = intent.getStringExtra(EXTRA_SETTINGS_PKG_NAME)
        val clsName = intent.getStringExtra(EXTRA_SETTINGS_CLS_NAME)
        if (pkgName.isNullOrBlank() || clsName.isNullOrBlank()) {
            Log.w(TAG, "failed to launch dream settings. pkgName $pkgName, clsName: $clsName")
            return
        }
        val settingsIntent =
            Intent().apply {
                component = ComponentName(pkgName, clsName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        try {
            context.startActivityAsUser(settingsIntent, userTracker.userHandle)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "start dream settings failed", e)
        }
    }

    private fun loadDreamMetadataList(): Bundle {
        val packageManager = userTracker.userContext.packageManager
        val dreamServices =
            packageManager
                ?.queryIntentServices(
                    Intent(DreamService.SERVICE_INTERFACE),
                    PackageManager.GET_META_DATA,
                )
                ?.filterNotNull() ?: emptyList()
        if (dreamServices.isEmpty()) {
            return Bundle(1).apply { putParcelableList(KEY_DREAM_METADATA_LIST, dreamServices) }
        }

        val disabledDreams =
            userTracker.userContext.resources
                ?.getStringArray(R.array.config_disabledDreamComponents)
                ?.mapNotNull { ComponentName.unflattenFromString(it) }
                ?.toSet()
        val activeDream =
            runCatching { dreamManager.getDreamComponentsForUser(userTracker.userId).firstOrNull() }
                .onFailure { Log.e(TAG, "getDreamComponentsForUser failed", it) }
                .onSuccess { if (it == null) Log.w(TAG, "fail to get activate dream") }
                .getOrNull()

        val dreams =
            dreamServices
                .map { resolveInfo ->
                    val serviceInfo = resolveInfo.serviceInfo
                    ComponentName(serviceInfo.packageName, serviceInfo.name) to
                        getDreamMetadata(serviceInfo)
                }
                .filter { (componentName, metadata) ->
                    disabledDreams?.contains(componentName) != true &&
                        metadata?.userSelectable == true
                }
                .map { (componentName, metadata) ->
                    metadata!!
                    Bundle().apply {
                        putBoolean("is_active", componentName == activeDream)
                        putString(EXTRA_DREAM_PKG_NAME, componentName.packageName)
                        putString(EXTRA_DREAM_CLS_NAME, componentName.className)
                        putString(EXTRA_SETTINGS_PKG_NAME, metadata.settingsActivity?.packageName)
                        putString(EXTRA_SETTINGS_CLS_NAME, metadata.settingsActivity?.className)
                        putInt("preview_image_resource_id", metadata.previewImageResId)
                        putBoolean("support_complications", metadata.showComplications)
                        putInt("dream_category", metadata.dreamCategory)
                    }
                }
        return Bundle(1).apply { putParcelableList(KEY_DREAM_METADATA_LIST, dreams) }
    }

    private fun getDreamMetadata(serviceInfo: ServiceInfo): DreamMetadataInfo? {
        val packageManager = userTracker.userContext.packageManager
        val attributes =
            packageManager.extractPackageItemInfoAttributes(
                serviceInfo,
                DreamService.DREAM_META_DATA,
                DREAM_META_DATA_ROOT_TAG,
                R.styleable.Dream,
            ) ?: return null
        return attributes.use {
            try {
                DreamMetadataInfo(
                    settingsActivity =
                        convertToComponentName(
                            it.getString(R.styleable.Dream_settingsActivity),
                            serviceInfo,
                            packageManager,
                        ),
                    previewImageResId = it.getResourceId(R.styleable.Dream_previewImage, 0),
                    showComplications =
                        it.getBoolean(R.styleable.Dream_showClockAndComplications, false),
                    dreamCategory = it.getInt(R.styleable.Dream_dreamCategory, 0),
                    userSelectable = it.getBoolean(R.styleable.Dream_userSelectable, true),
                )
            } catch (e: Exception) {
                Log.e(TAG, "failed to read metadata", e)
                null
            }
        }
    }

    companion object {
        private const val TAG = "DreamPickerReceiver"

        const val ACTION_PREVIEW = "com.google.android.systemui.dreamliner.action.ACTION_PREVIEW"
        const val ACTION_LOAD_METADATA =
            "com.google.android.systemui.dreamliner.action.ACTION_LOAD_METADATA"
        const val ACTION_LAUNCH_SETTINGS =
            "com.google.android.systemui.dreamliner.action.ACTION_LAUNCH_SETTINGS"

        private const val EXTRA_DREAM_PKG_NAME = "dream_pkg_name"
        private const val EXTRA_DREAM_CLS_NAME = "dream_cls_name"
        private const val EXTRA_SETTINGS_PKG_NAME = "settings_pkg_name"
        private const val EXTRA_SETTINGS_CLS_NAME = "settings_cls_name"
        private const val KEY_DREAM_METADATA_LIST = "dream_metadata_list"
        // DreamService.DREAM_META_DATA_ROOT_TAG is private.
        private const val DREAM_META_DATA_ROOT_TAG = "dream"

        private fun convertToComponentName(
            flattenedString: String?,
            serviceInfo: ServiceInfo,
            packageManager: PackageManager,
        ): ComponentName? {
            val componentName = flattenedString?.let {
                if (it.contains("/")) {
                    ComponentName.unflattenFromString(it)
                } else {
                    ComponentName(serviceInfo.packageName, it)
                }
            }
            if (componentName == null) {
                Log.w(TAG, "flattenedString convert to ComponentName failed")
                return null
            }
            if (componentName.packageName != serviceInfo.packageName) {
                Log.w(
                    TAG,
                    "inconsistent package name in component: ${componentName.packageName}, " +
                        "should be: ${serviceInfo.packageName}",
                )
                return null
            }
            if (
                Intent().setComponent(componentName).resolveActivityInfo(packageManager, 0) == null
            ) {
                Log.w(TAG, "dream settings activity not found: $componentName")
                return null
            }
            return componentName
        }
    }
}
