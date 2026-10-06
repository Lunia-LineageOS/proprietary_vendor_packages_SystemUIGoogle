package com.google.android.systemui.statusbar.phone;

import static com.android.systemui.Dependency.TIME_TICK_HANDLER_NAME;
import static com.android.systemui.charging.WirelessChargingAnimation.UNKNOWN_BATTERY_LEVEL;

import android.app.WallpaperManager;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.dreams.IDreamManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.logging.MetricsLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuController;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.brightness.data.repository.BrightnessMirrorShowingRepository;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.charging.WiredChargingRippleController;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.communal.domain.interactor.CommunalInteractor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.emergency.EmergencyGestureModule.EmergencyGestureIntentFactory;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.media.NotificationMediaManager;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.notetask.NoteTaskController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.CameraLauncher;
import com.android.systemui.shade.GlanceableHubContainerController;
import com.android.systemui.shade.NotificationShadeWindowViewController;
import com.android.systemui.shade.QuickSettingsController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.ShadeLogger;
import com.android.systemui.shade.ShadeSurface;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LightRevealScrim;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks;
import com.android.systemui.statusbar.phone.CentralSurfacesImpl;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.DozeScrimController;
import com.android.systemui.statusbar.phone.DozeServiceHost;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ShadeTouchableRegionManager;
import com.android.systemui.statusbar.phone.StatusBarHideIconsForBouncerManager;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.topui.TopUiController;
import com.android.systemui.util.WallpaperController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.MessageRouter;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.utils.windowmanager.WindowManagerProvider;
import com.android.systemui.volume.VolumeComponent;
import com.android.systemui.wallet.controller.QuickAccessWalletController;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.startingsurface.StartingSurface;

import com.google.android.systemui.power.batteryhealth.HealthManager;

import dagger.Lazy;

import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;

public class CentralSurfacesGoogle extends CentralSurfacesImpl {
    private static final String TAG = "CentralSurfacesGoogle";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long CHARGING_ANIMATION_DURATION = 1500;

    private final Optional<HealthManager> mHealthManagerOptional;
    private long mAnimStartTime;
    private boolean mChargingAnimShown;
    private int mReceivingBatteryLevel;
    private boolean mReverseChargingAnimShown;

    private final BatteryStateChangeCallback mBatteryStateChangeCallback =
            new BatteryStateChangeCallback() {
                @Override
                public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
                    mReceivingBatteryLevel = level;
                    if (!mBatteryController.isWirelessCharging()) {
                        if (SystemClock.uptimeMillis() - mAnimStartTime
                                > CHARGING_ANIMATION_DURATION) {
                            mChargingAnimShown = false;
                        }
                        mReverseChargingAnimShown = false;
                    }
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "onBatteryLevelChanged(): level="
                                        + level
                                        + ",wlc="
                                        + (mBatteryController.isWirelessCharging() ? 1 : 0)
                                        + ",wlcs="
                                        + mChargingAnimShown
                                        + ",rtxs="
                                        + mReverseChargingAnimShown
                                        + ",this="
                                        + this);
                    }
                }

                @Override
                public void onReverseChanged(boolean isReverse, int level, String name) {
                    if (!isReverse
                            && level >= 0
                            && !TextUtils.isEmpty(name)
                            && mBatteryController.isWirelessCharging()
                            && mChargingAnimShown
                            && !mReverseChargingAnimShown) {
                        mReverseChargingAnimShown = true;
                        long elapsed = SystemClock.uptimeMillis() - mAnimStartTime;
                        showChargingAnimation(
                                mReceivingBatteryLevel,
                                level,
                                elapsed > CHARGING_ANIMATION_DURATION
                                        ? 0
                                        : CHARGING_ANIMATION_DURATION - elapsed);
                    }
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "onReverseChanged(): rtx="
                                        + (isReverse ? 1 : 0)
                                        + ",rxlevel="
                                        + mReceivingBatteryLevel
                                        + ",level="
                                        + level
                                        + ",name="
                                        + name
                                        + ",wlc="
                                        + (mBatteryController.isWirelessCharging() ? 1 : 0)
                                        + ",wlcs="
                                        + mChargingAnimShown
                                        + ",rtxs="
                                        + mReverseChargingAnimShown
                                        + ",this="
                                        + this);
                    }
                }
            };

    @Inject
    public CentralSurfacesGoogle(
            Optional<HealthManager> healthManagerOptional,
            Context context,
            NotificationsController notificationsController,
            FragmentService fragmentService,
            LightBarController lightBarController,
            AutoHideController autoHideController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationWakeUpCoordinator notificationWakeUpCoordinator,
            KeyguardBypassController keyguardBypassController,
            KeyguardStateController keyguardStateController,
            HeadsUpManager headsUpManager,
            FalsingManager falsingManager,
            FalsingCollector falsingCollector,
            BroadcastDispatcher broadcastDispatcher,
            NotificationGutsManager notificationGutsManager,
            ShadeExpansionStateManager shadeExpansionStateManager,
            KeyguardViewMediator keyguardViewMediator,
            DisplayMetrics displayMetrics,
            MetricsLogger metricsLogger,
            ShadeLogger shadeLogger,
            JavaAdapter javaAdapter,
            @UiBackground Executor uiBgExecutor,
            ShadeSurface shadeSurface,
            NotificationMediaManager notificationMediaManager,
            NotificationLockscreenUserManager lockScreenUserManager,
            NotificationRemoteInputManager remoteInputManager,
            QuickSettingsController quickSettingsController,
            BatteryController batteryController,
            SysuiColorExtractor colorExtractor,
            ScreenLifecycle screenLifecycle,
            WakefulnessLifecycle wakefulnessLifecycle,
            PowerInteractor powerInteractor,
            CommunalInteractor communalInteractor,
            SysuiStatusBarStateController statusBarStateController,
            Optional<Bubbles> bubblesOptional,
            Lazy<NoteTaskController> noteTaskControllerLazy,
            DeviceProvisionedController deviceProvisionedController,
            NavigationBarController navigationBarController,
            AccessibilityFloatingMenuController accessibilityFloatingMenuController,
            Lazy<AssistManager> assistManagerLazy,
            ConfigurationController configurationController,
            NotificationShadeWindowController notificationShadeWindowController,
            Lazy<NotificationShadeWindowViewController> notificationShadeWindowViewControllerLazy,
            TopUiController topUiController,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            Lazy<NotificationPresenter> notificationPresenterLazy,
            Lazy<NotificationActivityStarter> notificationActivityStarterLazy,
            NotificationLaunchAnimatorControllerProvider notifTransitionAnimatorControllerProvider,
            DozeParameters dozeParameters,
            Lazy<ScrimController> scrimController,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            DozeServiceHost dozeServiceHost,
            PowerManager powerManager,
            DozeScrimController dozeScrimController,
            VolumeComponent volumeComponent,
            CommandQueue commandQueue,
            Lazy<CentralSurfacesCommandQueueCallbacks> commandQueueCallbacksLazy,
            PluginManager pluginManager,
            ShadeController shadeController,
            WindowRootViewVisibilityInteractor windowRootViewVisibilityInteractor,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            ViewMediatorCallback viewMediatorCallback,
            @Named(TIME_TICK_HANDLER_NAME) Handler timeTickHandler,
            ExtensionController extensionController,
            UserInfoControllerImpl userInfoControllerImpl,
            PhoneStatusBarPolicy phoneStatusBarPolicy,
            KeyguardIndicationController keyguardIndicationController,
            DemoModeController demoModeController,
            Lazy<NotificationShadeDepthController> notificationShadeDepthControllerLazy,
            ShadeTouchableRegionManager shadeTouchableRegionManager,
            ScreenOffAnimationController screenOffAnimationController,
            WallpaperController wallpaperController,
            StatusBarHideIconsForBouncerManager statusBarHideIconsForBouncerManager,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            @Main DelayableExecutor delayableExecutor,
            @Main MessageRouter messageRouter,
            WallpaperManager wallpaperManager,
            Optional<StartingSurface> startingSurfaceOptional,
            ActivityTransitionAnimator activityTransitionAnimator,
            DeviceStateManager deviceStateManager,
            WiredChargingRippleController wiredChargingRippleController,
            IDreamManager dreamManager,
            Lazy<CameraLauncher> cameraLauncherLazy,
            LightRevealScrim lightRevealScrim,
            AlternateBouncerInteractor alternateBouncerInteractor,
            UserTracker userTracker,
            ActivityStarter activityStarter,
            BrightnessMirrorShowingRepository brightnessMirrorShowingRepository,
            GlanceableHubContainerController glanceableHubContainerController,
            EmergencyGestureIntentFactory emergencyGestureIntentFactory,
            QuickAccessWalletController walletController,
            WindowManager windowManager,
            WindowManagerProvider windowManagerProvider,
            SessionTracker sessionTracker) {
        super(
                context,
                notificationsController,
                fragmentService,
                lightBarController,
                autoHideController,
                keyguardUpdateMonitor,
                pulseExpansionHandler,
                notificationWakeUpCoordinator,
                keyguardBypassController,
                keyguardStateController,
                headsUpManager,
                falsingManager,
                falsingCollector,
                broadcastDispatcher,
                notificationGutsManager,
                shadeExpansionStateManager,
                keyguardViewMediator,
                displayMetrics,
                metricsLogger,
                shadeLogger,
                javaAdapter,
                uiBgExecutor,
                shadeSurface,
                notificationMediaManager,
                lockScreenUserManager,
                remoteInputManager,
                quickSettingsController,
                batteryController,
                colorExtractor,
                screenLifecycle,
                wakefulnessLifecycle,
                powerInteractor,
                communalInteractor,
                statusBarStateController,
                bubblesOptional,
                noteTaskControllerLazy,
                deviceProvisionedController,
                navigationBarController,
                accessibilityFloatingMenuController,
                assistManagerLazy,
                configurationController,
                notificationShadeWindowController,
                notificationShadeWindowViewControllerLazy,
                topUiController,
                notificationStackScrollLayoutController,
                notificationPresenterLazy,
                notificationActivityStarterLazy,
                notifTransitionAnimatorControllerProvider,
                dozeParameters,
                scrimController,
                biometricUnlockControllerLazy,
                dozeServiceHost,
                powerManager,
                dozeScrimController,
                volumeComponent,
                commandQueue,
                commandQueueCallbacksLazy,
                pluginManager,
                shadeController,
                windowRootViewVisibilityInteractor,
                statusBarKeyguardViewManager,
                viewMediatorCallback,
                timeTickHandler,
                extensionController,
                userInfoControllerImpl,
                phoneStatusBarPolicy,
                keyguardIndicationController,
                demoModeController,
                notificationShadeDepthControllerLazy,
                shadeTouchableRegionManager,
                screenOffAnimationController,
                wallpaperController,
                statusBarHideIconsForBouncerManager,
                lockscreenShadeTransitionController,
                keyguardUnlockAnimationController,
                delayableExecutor,
                messageRouter,
                wallpaperManager,
                startingSurfaceOptional,
                activityTransitionAnimator,
                deviceStateManager,
                wiredChargingRippleController,
                dreamManager,
                cameraLauncherLazy,
                lightRevealScrim,
                alternateBouncerInteractor,
                userTracker,
                activityStarter,
                brightnessMirrorShowingRepository,
                glanceableHubContainerController,
                emergencyGestureIntentFactory,
                walletController,
                windowManager,
                windowManagerProvider,
                sessionTracker);
        mHealthManagerOptional = healthManagerOptional;
    }

    @Override
    public void start() {
        super.start();
        mBatteryController.observe(getLifecycle(), mBatteryStateChangeCallback);
        // Stock also starts DockObserver, ReverseChargingViewController and WallpaperNotifier
        // here; they are not ported yet.
        mHealthManagerOptional.ifPresent(HealthManager::registerBootCompletedReceiver);
    }

    @Override
    public void showWirelessChargingAnimation(int batteryLevel) {
        if (DEBUG) {
            Log.d(TAG, "showWirelessChargingAnimation()");
        }
        mChargingAnimShown = true;
        showChargingAnimation(batteryLevel, UNKNOWN_BATTERY_LEVEL, 0);
        mAnimStartTime = SystemClock.uptimeMillis();
    }
}
