package com.google.android.systemui.power;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.devicestate.DeviceStateManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.power.BatteryStateSnapshot;
import com.android.systemui.power.PowerNotificationWarnings;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import com.google.android.systemui.googlebattery.AdaptiveChargingManager;
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;
import com.google.android.systemui.power.batteryevent.common.module.BaseBatteryEventModule;
import com.google.android.systemui.power.batteryevent.common.module.ExtremeLowBatteryEventModule;
import com.google.android.systemui.power.batteryevent.common.module.LowBatteryEventModule;
import com.google.android.systemui.power.batteryevent.common.module.SevereLowBatteryEventModule;
import com.google.android.systemui.res.R;

import dagger.Lazy;

import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.ExecutorsKt;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

@SysUISingleton
public class PowerNotificationWarningsGoogleImpl extends PowerNotificationWarnings {

    private static final String TAG = "PowerNotificationWarningsGoogleImpl";

    private final Context mContext;
    private final ActivityStarter mActivityStarter;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final GlobalSettings mGlobalSettings;
    private final SecureSettings mSecureSettings;
    private final Lazy<BatteryController> mBatteryControllerLazy;
    private final UserTracker mUserTracker;
    private final Executor mExecutor;
    private final Handler mHandler;
    private final Provider<BatterySaverConfirmationDialog> mBatterySaverConfirmationDialogProvider;
    private final Provider<SevereLowBatteryNotification> mSevereLowBatteryNotificationProvider;
    private final PulsarController mPulsarController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final BatterySaverAutoDisableController mBatterySaverAutoDisableController;
    private final SubscriptionManager mSubscriptionManager;
    private final DeviceStateManager mDeviceStateManager;

    private SevereLowBatteryNotification mSevereLowBatteryNotification;
    private LowPowerWarningsController mLowPowerWarningsController;
    private ChargeLimitController mChargeLimitController;
    private AdaptiveChargingNotification mAdaptiveChargingNotification;
    private ChargeLimitDiscoveryNotification mChargeLimitDiscoveryNotification;
    private BatterySaverConfirmationDialog mBatterySaverConfirmationDialog;
    private BroadcastReceiver mLotXBroadcastReceiver;

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) {
                        return;
                    }
                    String action = intent.getAction();
                    Log.d(TAG, "onReceive: " + action);
                    switch (action) {
                        case "systemui.power.action.START_FLIPENDO":
                            mExecutor.execute(
                                    () -> {
                                        PowerUtils.enableFlipendo(mContext);
                                        mSevereLowBatteryNotification.logStartFlipendo(
                                                intent.getStringExtra(
                                                        "extra_severe_low_battery_notification"));
                                    });
                            return;
                        case "FLIPENDO.startSaverConfirmation":
                        case "PNW.startSaverConfirmation":
                            handleStartSaverConfirmation();
                            return;
                        case "PNW.dismissSevereLowBatteryWarning":
                            mSevereLowBatteryNotification.dismiss(
                                    intent.getStringExtra("extra_severe_low_battery_notification"));
                            dismissLowBatteryWarning();
                            return;
                    }
                    mLowPowerWarningsController.dispatchIntent(intent);
                    if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                        handleBatteryChanged(intent);
                    }
                    mChargeLimitController.dispatchIntent(intent);
                    mPulsarController.dispatchIntent(intent);
                    if (mAdaptiveChargingNotification != null) {
                        switch (action) {
                            case Intent.ACTION_BATTERY_CHANGED:
                                mAdaptiveChargingNotification.resolveBatteryChangedIntent(intent);
                                break;
                            case "com.google.android.systemui.adaptivecharging.ADAPTIVE_CHARGING_DEADLINE_SET":
                                mAdaptiveChargingNotification.checkAdaptiveChargingStatus(true);
                                break;
                            case "PNW.acChargeNormally":
                                mAdaptiveChargingNotification.mUiEventLogger.log(
                                        BatteryMetricEvent.ADAPTIVE_CHARGING_NOTIFICATION_BYPASS);
                                mAdaptiveChargingNotification.mAdaptiveChargingManager
                                        .setAdaptiveChargingDeadline(-3);
                                mAdaptiveChargingNotification.cancelNotification();
                                Intent acIntent =
                                        new Intent(
                                                "com.google.android.systemui.adaptivecharging.ADAPTIVE_CHARGING_DEADLINE_SET");
                                acIntent.setPackage(mContext.getPackageName());
                                acIntent.setFlags(
                                        Intent.FLAG_RECEIVER_REGISTERED_ONLY
                                                | Intent.FLAG_RECEIVER_FOREGROUND);
                                mContext.sendBroadcastAsUser(acIntent, UserHandle.ALL);
                                break;
                            case "systemui.power.action.dismissAdaptiveChargingWarning":
                                if (mAdaptiveChargingNotification.mUiEventLogger != null) {
                                    mAdaptiveChargingNotification.mUiEventLogger.log(
                                            BatteryMetricEvent
                                                    .DELETE_ADAPTIVE_CHARGING_NOTIFICATION);
                                }
                                break;
                        }
                    }
                }
            };

    @Inject
    public PowerNotificationWarningsGoogleImpl(
            Context context,
            ActivityStarter activityStarter,
            BroadcastDispatcher broadcastDispatcher,
            BroadcastSender broadcastSender,
            UiEventLogger uiEventLogger,
            GlobalSettings globalSettings,
            Lazy<BatteryController> batteryControllerLazy,
            DialogTransitionAnimator dialogTransitionAnimator,
            UserTracker userTracker,
            SystemUIDialog.Factory systemUIDialogFactory,
            @Background Executor backgroundExecutor,
            @Main Handler mainHandler,
            Provider<BatterySaverConfirmationDialog> batterySaverConfirmationDialogProvider,
            Provider<SevereLowBatteryNotification> severeLowBatteryNotificationProvider,
            SecureSettings secureSettings,
            PulsarController pulsarController,
            DeviceProvisionedController deviceProvisionedController,
            BatterySaverAutoDisableController batterySaverAutoDisableController,
            SubscriptionManager subscriptionManager,
            DeviceStateManager deviceStateManager) {
        super(
                context,
                activityStarter,
                broadcastSender,
                batteryControllerLazy,
                dialogTransitionAnimator,
                uiEventLogger,
                userTracker,
                systemUIDialogFactory);
        mContext = context;
        mActivityStarter = activityStarter;
        mBroadcastDispatcher = broadcastDispatcher;
        mGlobalSettings = globalSettings;
        mSecureSettings = secureSettings;
        mBatteryControllerLazy = batteryControllerLazy;
        mUserTracker = userTracker;
        mExecutor = backgroundExecutor;
        mHandler = mainHandler;
        mBatterySaverConfirmationDialogProvider = batterySaverConfirmationDialogProvider;
        mSevereLowBatteryNotificationProvider = severeLowBatteryNotificationProvider;
        mPulsarController = pulsarController;
        mDeviceProvisionedController = deviceProvisionedController;
        mBatterySaverAutoDisableController = batterySaverAutoDisableController;
        mSubscriptionManager = subscriptionManager;
        mDeviceStateManager = deviceStateManager;
        mainHandler.post(() -> init(context, uiEventLogger));
    }

    private void init(Context context, UiEventLogger uiEventLogger) {
        mSevereLowBatteryNotification = mSevereLowBatteryNotificationProvider.get();
        mLowPowerWarningsController =
                new LowPowerWarningsController(
                        context,
                        mExecutor,
                        mGlobalSettings,
                        uiEventLogger,
                        mSevereLowBatteryNotification);
        mChargeLimitController =
                new ChargeLimitController(
                        mSecureSettings, mUserTracker, ExecutorsKt.from(mExecutor));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction("PNW.startSaverConfirmation");
        filter.addAction("FLIPENDO.startSaverConfirmation");
        filter.addAction("com.android.settingslib.fuelgauge.ACTION_SAVER_STATE_MANUAL_UPDATE");
        filter.addAction("systemui.power.action.START_FLIPENDO");
        filter.addAction("PNW.dismissSevereLowBatteryWarning");
        filter.addAction(PulsarController.ACTION_CLICK_PULSAR_ENABLED_NOTIFICATION);
        filter.addAction(PulsarController.ACTION_DISMISS_PULSAR_ENABLED_NOTIFICATION);
        filter.addAction(PulsarController.ACTION_CLICK_PULSAR_REMINDER_NOTIFICATION);
        filter.addAction(PulsarController.ACTION_DISMISS_PULSAR_REMINDER_NOTIFICATION);
        Resources resources = context.getResources();
        if (resources.getBoolean(R.bool.config_adaptive_charging_warning_enabled)) {
            mAdaptiveChargingNotification =
                    new AdaptiveChargingNotification(
                            context, new AdaptiveChargingManager(context), uiEventLogger);
            filter.addAction(
                    "com.google.android.systemui.adaptivecharging.ADAPTIVE_CHARGING_DEADLINE_SET");
            filter.addAction("PNW.acChargeNormally");
            filter.addAction("systemui.power.action.dismissAdaptiveChargingWarning");
        }
        mBroadcastDispatcher.registerReceiverWithHandler(
                mBroadcastReceiver, filter, mHandler, UserHandle.ALL);
        Intent batteryChanged =
                context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryChanged != null) {
            mBroadcastReceiver.onReceive(context, batteryChanged);
        }

        mBatterySaverAutoDisableController.start();
        mExecutor.execute(
                () -> {
                    if (!TextUtils.equals(SystemProperties.get("ro.boot.warranty.sku"), "EMA")) {
                        Log.d(TAG, "Skip LotX intent registration for non-EU devices.");
                        return;
                    }
                    mHandler.post(() -> registerLotXReceiver(context, uiEventLogger));
                });
    }

    private void registerLotXReceiver(Context context, UiEventLogger uiEventLogger) {
        IntentFilter filter = new IntentFilter();
        if (context.getResources().getBoolean(R.bool.config_charge_limit_discovery_enabled)) {
            mChargeLimitDiscoveryNotification =
                    new ChargeLimitDiscoveryNotification(
                            context,
                            mActivityStarter,
                            uiEventLogger,
                            mChargeLimitController,
                            mSecureSettings,
                            mUserTracker,
                            mDeviceProvisionedController,
                            mDeviceStateManager,
                            mSubscriptionManager,
                            context.getSystemService(NotificationManager.class),
                            Dispatchers.getMain(),
                            ExecutorsKt.from(mExecutor));
            filter.addAction("systemui.power.action.enableChargeLimitFeature");
            filter.addAction("systemui.power.action.dismissChargeLimitNotification");
            filter.addAction("systemui.power.action.clickChargeLimitNotification");
        }
        if (filter.countActions() == 0) {
            return;
        }
        mLotXBroadcastReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent == null || intent.getAction() == null) {
                            return;
                        }
                        Log.d(TAG, "LotXReceiver onReceive: " + intent.getAction());
                        switch (intent.getAction()) {
                            case Intent.ACTION_BATTERY_CHANGED:
                            case "systemui.power.action.enableChargeLimitFeature":
                            case "systemui.power.action.dismissChargeLimitNotification":
                            case "systemui.power.action.clickChargeLimitNotification":
                                if (mChargeLimitDiscoveryNotification != null) {
                                    mChargeLimitDiscoveryNotification.dispatchIntent(intent);
                                }
                                break;
                        }
                    }
                };
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        Log.d(TAG, "Register LotX intents.");
        mBroadcastDispatcher.registerReceiverWithHandler(
                mLotXBroadcastReceiver, filter, mHandler, UserHandle.ALL);
        Intent batteryChanged =
                context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryChanged != null) {
            mLotXBroadcastReceiver.onReceive(context, batteryChanged);
        }
    }

    private void handleStartSaverConfirmation() {
        mLowPowerWarningsController.cancelNotification();
        if (!mContext.getResources().getBoolean(R.bool.config_extra_battery_saver_confirmation)) {
            return;
        }
        mBatterySaverConfirmationDialog = mBatterySaverConfirmationDialogProvider.get();
        WeakReference<Expandable> ref =
                mBatteryControllerLazy.get().getLastPowerSaverStartExpandable();
        mBatterySaverConfirmationDialog.show(ref != null ? ref.get() : null);
    }

    private void handleBatteryChanged(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int batteryLevel = BatteryStatus.getBatteryLevel(level, scale);
        updateBatteryEvent(batteryLevel, plugged);
    }

    private void updateBatteryEvent(int batteryLevel, int plugged) {
        List<BatteryEventType> events = evaluateBatteryEvents(batteryLevel, plugged);
        if (mLowPowerWarningsController != null) {
            mLowPowerWarningsController.onBatteryEventUpdate(batteryLevel, events);
        }
    }

    private final List<BaseBatteryEventModule> mBatteryEventModules =
            List.of(
                    new ExtremeLowBatteryEventModule(),
                    new SevereLowBatteryEventModule(),
                    new LowBatteryEventModule());

    private List<BatteryEventType> evaluateBatteryEvents(int batteryLevel, int plugged) {
        for (BaseBatteryEventModule module : mBatteryEventModules) {
            if (module.validate(batteryLevel, plugged)) {
                return Collections.singletonList(module.getModuleType());
            }
        }
        return Collections.emptyList();
    }

    @Override
    public void updateSnapshot(BatteryStateSnapshot snapshot) {
        super.updateSnapshot(snapshot);
        int plugged = snapshot.getPlugged() ? BatteryManager.BATTERY_PLUGGED_AC : 0;
        updateBatteryEvent(snapshot.getBatteryLevel(), plugged);
    }

    @Override
    public void showLowBatteryWarning(boolean playSound) {
        // Handled by LowPowerWarningsController
    }

    @Override
    protected void showWarningNotification() {
        // Handled by LowPowerWarningsController
    }

    @Override
    public void updateLowBatteryWarning() {
        // Handled by LowPowerWarningsController
    }

    @Override
    public void dismissLowBatteryWarning() {
        if (mLowPowerWarningsController != null) {
            mLowPowerWarningsController.cancelNotification();
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        super.dump(pw);
        if (mLowPowerWarningsController != null) {
            pw.println("\tdump LowPowerWarningsController states");
            pw.println("\t\tprevBatteryLevel: " + mLowPowerWarningsController.prevBatteryLevel);
            pw.println(
                    "\t\tprevBatteryEventType: "
                            + mLowPowerWarningsController.prevBatteryEventTypes);
            pw.println(
                    "\t\tisBatterySaverReminderDisabled: "
                            + (mGlobalSettings.getInt("low_power_mode_reminder_enabled", 1) == 0));
            pw.println(
                    "\t\tisScheduledByPercentage: "
                            + mLowPowerWarningsController.isScheduledByPercentage());
            pw.println(
                    "\t\tlowBatteryNotificationCancelled: "
                            + mLowPowerWarningsController.lowBatteryNotificationCancelled);
            pw.println(
                    "\t\tsevereLowBatteryNotificationCancelled: "
                            + mLowPowerWarningsController.severeLowBatteryNotificationCancelled);
        }
    }

    @Override
    public void userSwitched() {
        if (mLowPowerWarningsController != null
                && mLowPowerWarningsController.prevBatteryLevel != null) {
            mLowPowerWarningsController.onBatteryEventUpdate(
                    mLowPowerWarningsController.prevBatteryLevel,
                    mLowPowerWarningsController.prevBatteryEventTypes);
        }
        if (mChargeLimitController != null) {
            mChargeLimitController.onUserSwitched();
        }
    }
}
