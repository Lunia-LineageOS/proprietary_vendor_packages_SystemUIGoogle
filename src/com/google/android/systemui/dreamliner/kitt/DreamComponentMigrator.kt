package com.google.android.systemui.dreamliner.kitt

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.PatternMatcher
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.service.dreams.DreamService
import android.service.dreams.IDreamManager
import android.text.TextUtils
import android.util.Log
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SecureSettings
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

class DreamComponentMigrator(
    private val context: Context,
    private val dreamManager: IDreamManager,
    private val userTracker: UserTracker,
    private val userManager: UserManager,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val backgroundScope: CoroutineScope,
    private val backgroundExecutor: Executor,
    private val secureSettings: SecureSettings,
) : UserTracker.Callback {
    private val packageManager: PackageManager = context.packageManager

    private val dreamComponentSettingChangedObserverLock = Mutex()
    private val dreamComponentSettingChangedObserver by lazy {
        object : ContentObserver(backgroundExecutor, 0) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "DreamComponent setting changed")
                handleChangedEvent()
            }
        }
    }

    private var handleChangedEventJob: Job? = null

    class UserContextScope(val userContext: Context)

    fun start() {
        Log.d(TAG, "start")
        backgroundScope.launch { registerDreamComponentSettingsObserverForUser(userTracker.userId) }
        userTracker.addCallback(this, backgroundExecutor)
        registerDreamsPackageUpdatedReceiver()
        registerUserUnlockedReceiver()
    }

    private fun registerDreamsPackageUpdatedReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
                addDataSchemeSpecificPart(DREAMS_PACKAGE_NAME, PatternMatcher.PATTERN_LITERAL)
            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    if (
                        action != Intent.ACTION_PACKAGE_REPLACED &&
                            action != Intent.ACTION_PACKAGE_CHANGED
                    ) {
                        return
                    }
                    val data = intent.data ?: return
                    if (TextUtils.equals(data.schemeSpecificPart, DREAMS_PACKAGE_NAME)) {
                        Log.d(TAG, "$DREAMS_PACKAGE_NAME has been updated.")
                        handleChangedEvent()
                    }
                }
            }
        backgroundExecutor.execute {
            context.registerReceiverForAllUsers(
                receiver,
                intentFilter,
                null,
                null,
                Context.RECEIVER_EXPORTED,
            )
        }
    }

    private fun registerUserUnlockedReceiver() {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.d(TAG, "user unlocked")
                    handleChangedEvent()
                }
            }
        broadcastDispatcher.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            user = UserHandle.ALL,
        )
    }

    override fun onUserChanged(newUser: Int, userContext: Context) {
        Log.d(TAG, "user changed")
        handleChangedEvent()
        backgroundScope.launch { registerDreamComponentSettingsObserverForUser(newUser) }
    }

    private suspend fun registerDreamComponentSettingsObserverForUser(userId: Int) {
        dreamComponentSettingChangedObserverLock.withLock {
            secureSettings.unregisterContentObserver(dreamComponentSettingChangedObserver)
            secureSettings.registerContentObserverForUser(
                Settings.Secure.SCREENSAVER_COMPONENTS,
                dreamComponentSettingChangedObserver,
                userId,
            )
        }
    }

    private fun handleChangedEvent() {
        handleChangedEventJob?.cancel(CancellationException("New changed event, restart"))
        handleChangedEventJob = backgroundScope.launch {
            try {
                updateDreamComponentSettings(
                    UserContextScope(userTracker.createCurrentUserContext(userTracker.userContext))
                )
            } catch (e: CancellationException) {
                Log.i(TAG, "handleChangedEvent canceled with reason \"${e.message}\"")
            } catch (e: Exception) {
                Log.w(TAG, "handleDreamsApkChanged failed", e)
            }
        }
    }

    private suspend fun updateDreamComponentSettings(scope: UserContextScope) {
        yield()
        if (userManager.isUserUnlocked(scope.userContext.userId)) {
            yield()
            doUpdateDreamComponentSettings(scope)
        }
    }

    private suspend fun doUpdateDreamComponentSettings(scope: UserContextScope) {
        val isEverydayClockAvailable =
            isDreamComponentAvailable(scope, EVERYDAY_CLOCK_DREAM_COMPONENT_NAME)
        yield()
        if (isEverydayClockAvailable) {
            val success =
                secureSettings.putStringForUser(
                    Settings.Secure.SCREENSAVER_DEFAULT_COMPONENT,
                    EVERYDAY_CLOCK_DREAM_COMPONENT_NAME.flattenToString(),
                    scope.userContext.userId,
                )
            if (!success) {
                Log.w(TAG, "setDefaultDreamComponentForUser failed")
            }
            yield()
            migrateLegacyClockDreamIfSelected(scope)
            if (success) {
                scope.userContext.packageManager.setComponentEnabledSetting(
                    LEGACY_CLOCK_DREAM_COMPONENT_NAME,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            scope.userContext.packageManager.setComponentEnabledSetting(
                LEGACY_COLOR_DREAM_COMPONENT_NAME,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        yield()
        val isPhotoCarouselAvailable =
            isDreamComponentAvailable(scope, PHOTO_CAROUSEL_DREAM_COMPONENT_NAME)
        yield()
        if (isPhotoCarouselAvailable) {
            migrateLegacyPhotoDreamIfSelected(scope)
            scope.userContext.packageManager.setComponentEnabledSetting(
                LEGACY_PHOTO_DREAM_COMPONENT_NAME,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private suspend fun migrateLegacyClockDreamIfSelected(scope: UserContextScope) {
        val components = dreamManager.getDreamComponentsForUser(scope.userContext.userId)
        if (
            components != null &&
                components.isNotEmpty() &&
                components[0] == LEGACY_CLOCK_DREAM_COMPONENT_NAME
        ) {
            yield()
            dreamManager.setDreamComponentsForUser(
                scope.userContext.userId,
                arrayOf(EVERYDAY_CLOCK_DREAM_COMPONENT_NAME),
            )
        }
    }

    private suspend fun migrateLegacyPhotoDreamIfSelected(scope: UserContextScope) {
        val components = dreamManager.getDreamComponentsForUser(scope.userContext.userId)
        if (
            components != null &&
                components.isNotEmpty() &&
                components[0] == LEGACY_PHOTO_DREAM_COMPONENT_NAME
        ) {
            yield()
            dreamManager.setDreamComponentsForUser(
                scope.userContext.userId,
                arrayOf(PHOTO_CAROUSEL_DREAM_COMPONENT_NAME),
            )
        }
    }

    private fun isDreamComponentAvailable(
        scope: UserContextScope,
        componentName: ComponentName,
    ): Boolean {
        return try {
            val intent = Intent(DreamService.SERVICE_INTERFACE).setComponent(componentName)
            val resolveInfo =
                packageManager.resolveServiceAsUser(intent, 0, scope.userContext.userId)
            val serviceInfo = resolveInfo?.serviceInfo
            if (serviceInfo != null) {
                val setting =
                    scope.userContext.packageManager.getComponentEnabledSetting(componentName)
                if (setting != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    serviceInfo.enabled
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "$componentName is not available", e)
            false
        }
    }

    companion object {
        private const val TAG = "DreamComponentMigrator"
        private const val DREAMS_PACKAGE_NAME = "com.google.assistant.hubui"
        val EVERYDAY_CLOCK_DREAM_COMPONENT_NAME =
            ComponentName.createRelative(DREAMS_PACKAGE_NAME, ".dream.EverydayClockDreamService")
        val PHOTO_CAROUSEL_DREAM_COMPONENT_NAME =
            ComponentName.createRelative(DREAMS_PACKAGE_NAME, ".dream.PersonalPhotoDreamService")
        val LEGACY_CLOCK_DREAM_COMPONENT_NAME =
            ComponentName.createRelative(
                "com.google.android.deskclock",
                "com.android.deskclock.Screensaver",
            )
        val LEGACY_PHOTO_DREAM_COMPONENT_NAME =
            ComponentName.createRelative(
                "com.google.android.apps.photos",
                ".daydream.PhotosDreamService",
            )
        val LEGACY_COLOR_DREAM_COMPONENT_NAME =
            ComponentName.createRelative("com.android.dreams.basic", ".Colors")
    }
}
