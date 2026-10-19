package com.google.android.systemui.power;

import android.app.NotificationManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.power.PowerNotificationWarnings;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.SystemClock;

import com.google.android.systemui.googlebattery.AdaptiveChargingManager;
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;
import com.google.android.systemui.power.batteryevent.aidl.SurfaceType;
import com.google.android.systemui.res.R;

import dagger.Lazy;

import kotlin.Unit;

import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.ExecutorsKt;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

@SysUISingleton
public class PowerNotificationWarningsGoogleImpl extends PowerNotificationWarnings {

    private static final String TAG = "PowerNotificationWarningsGoogleImpl";

    private final Context mContext;
    private final ActivityStarter mActivityStarter;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final BroadcastSender mBroadcastSender;
    private final GlobalSettings mGlobalSettings;
    private final SecureSettings mSecureSettings;
    private final Lazy<BatteryController> mBatteryControllerLazy;
    private final EnhancedEstimates mEnhancedEstimates;
    private final UserTracker mUserTracker;
    private final BatteryEventClient mBatteryEventClient;
    private final Executor mExecutor;
    private final Handler mHandler;
    private final SystemClock mSystemClock;
    private final Provider<BatterySaverConfirmationDialog> mBatterySaverConfirmationDialogProvider;
    private final Provider<SevereLowBatteryNotification> mSevereLowBatteryNotificationProvider;
    private final Provider<ShutdownModeController> mShutdownModeControllerProvider;
    private final PulsarController mPulsarController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final BatterySaverAutoDisableController mBatterySaverAutoDisableController;
    private final SubscriptionManager mSubscriptionManager;
    private final DeviceStateManager mDeviceStateManager;

    private BatteryInfoBroadcast mBatteryInfoBroadcast;
    private SevereLowBatteryNotification mSevereLowBatteryNotification;
    private ShutdownModeController mShutdownModeController;
    private LowPowerWarningsController mLowPowerWarningsController;
    private ChargeLimitController mChargeLimitController;
    private BatteryReplacementNotification mBatteryReplacementNotification;
    private BatteryDefenderNotificationHandler mBatteryDefenderNotificationHandler;
    private AdaptiveChargingNotification mAdaptiveChargingNotification;
    private IncompatibleChargerNotification mIncompatibleChargerNotification;
    private WirelessChargingNotification mWirelessChargingNotification;
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
                        case ShutdownModeController.ACTION_SHUTDOWN_MODE_UPDATE:
                            if (mShutdownModeController != null) {
                                mShutdownModeController.onShutdownModeUpdate(intent);
                            }
                            return;
                        case Intent.ACTION_BOOT_COMPLETED:
                            if (mShutdownModeController != null) {
                                mShutdownModeController.recover();
                            }
                            break;
                    }
                    mBatteryInfoBroadcast.dispatchIntent(intent);
                    mLowPowerWarningsController.dispatchIntent(intent);
                    mChargeLimitController.dispatchIntent(intent);
                    mPulsarController.dispatchIntent(intent);
                    if (mBatteryDefenderNotificationHandler != null) {
                        mBatteryDefenderNotificationHandler.dispatchIntent(intent);
                    }
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
                    if (mIncompatibleChargerNotification != null) {
                        mIncompatibleChargerNotification.dispatchIntent(intent);
                    }
                    if (mBatteryReplacementNotification != null
                            && mSecureSettings.getInt("barrel_forcibly_disabled", 0) == 0) {
                        mBatteryReplacementNotification.dispatchIntent(intent);
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
            EnhancedEstimates enhancedEstimates,
            UserTracker userTracker,
            BatteryEventClient batteryEventClient,
            SystemUIDialog.Factory systemUIDialogFactory,
            @Background Executor backgroundExecutor,
            @Main Handler mainHandler,
            SystemClock systemClock,
            Provider<BatterySaverConfirmationDialog> batterySaverConfirmationDialogProvider,
            Provider<SevereLowBatteryNotification> severeLowBatteryNotificationProvider,
            Provider<ShutdownModeController> shutdownModeControllerProvider,
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
        mBroadcastSender = broadcastSender;
        mGlobalSettings = globalSettings;
        mSecureSettings = secureSettings;
        mBatteryControllerLazy = batteryControllerLazy;
        mEnhancedEstimates = enhancedEstimates;
        mUserTracker = userTracker;
        mBatteryEventClient = batteryEventClient;
        mExecutor = backgroundExecutor;
        mHandler = mainHandler;
        mSystemClock = systemClock;
        mBatterySaverConfirmationDialogProvider = batterySaverConfirmationDialogProvider;
        mSevereLowBatteryNotificationProvider = severeLowBatteryNotificationProvider;
        mShutdownModeControllerProvider = shutdownModeControllerProvider;
        mPulsarController = pulsarController;
        mDeviceProvisionedController = deviceProvisionedController;
        mBatterySaverAutoDisableController = batterySaverAutoDisableController;
        mSubscriptionManager = subscriptionManager;
        mDeviceStateManager = deviceStateManager;
        mainHandler.post(() -> init(context, uiEventLogger));
    }

    private void init(Context context, UiEventLogger uiEventLogger) {
        mBatteryInfoBroadcast =
                new BatteryInfoBroadcast(
                        context, mBroadcastSender, mEnhancedEstimates, mExecutor, mUserTracker);
        mSevereLowBatteryNotification = mSevereLowBatteryNotificationProvider.get();
        mShutdownModeController = mShutdownModeControllerProvider.get();
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
        if ("bluejay".equals(Build.DEVICE)) {
            mBatteryReplacementNotification =
                    new BatteryReplacementNotification(context, Clock.systemUTC());
        }
        mBatteryEventClient.registerBatteryEventCallback(
                SurfaceType.NOTIFICATION,
                TAG,
                Arrays.asList(
                        BatteryEventType.LOW_BATTERY,
                        BatteryEventType.SEVERE_LOW_BATTERY,
                        BatteryEventType.EXTREME_LOW_BATTERY,
                        BatteryEventType.WIRED_INCOMPATIBLE_CHARGING,
                        BatteryEventType.TEMP_DEFEND_BATTERY,
                        BatteryEventType.DWELL_DEFEND_BATTERY,
                        BatteryEventType.DOCK_DEFEND_BATTERY),
                (events, batteryLevel, pluggedType) -> {
                    Log.d(TAG, "[onBatteryEventUpdate] " + events);
                    if (mIncompatibleChargerNotification != null) {
                        mIncompatibleChargerNotification.refreshUsbState(
                                events.contains(BatteryEventType.WIRED_INCOMPATIBLE_CHARGING));
                    }
                    if (mLowPowerWarningsController != null) {
                        mLowPowerWarningsController.onBatteryEventUpdate(batteryLevel, events);
                    }
                    if (mBatteryDefenderNotificationHandler != null) {
                        mBatteryDefenderNotificationHandler.onBatteryEventUpdate(
                                events, batteryLevel, pluggedType);
                    }
                    return Unit.INSTANCE;
                });

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
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ALIAS_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction("systemui.power.action.START_FLIPENDO");
        filter.addAction("PNW.dismissSevereLowBatteryWarning");
        filter.addAction(ShutdownModeController.ACTION_SHUTDOWN_MODE_UPDATE);
        filter.addAction(PulsarController.ACTION_CLICK_PULSAR_ENABLED_NOTIFICATION);
        filter.addAction(PulsarController.ACTION_DISMISS_PULSAR_ENABLED_NOTIFICATION);
        filter.addAction(PulsarController.ACTION_CLICK_PULSAR_REMINDER_NOTIFICATION);
        filter.addAction(PulsarController.ACTION_DISMISS_PULSAR_REMINDER_NOTIFICATION);
        Resources resources = context.getResources();
        if (resources.getBoolean(R.bool.config_battery_defender_warning_enabled)) {
            mBatteryDefenderNotificationHandler =
                    new BatteryDefenderNotificationHandler(
                            context,
                            uiEventLogger,
                            mSystemClock,
                            Dispatchers.getMain(),
                            context.getSystemService(NotificationManager.class),
                            ExecutorsKt.from(mExecutor));
            filter.addAction(DwellTempDefenderNotification.ACTION_RESUME_CHARGING);
            filter.addAction(BatteryDefenderNotificationHandler.ACTION_RESUME_CHARGING_SETTINGS);
            filter.addAction(DwellTempDefenderNotification.ACTION_DISMISS_DEFENDER_WARNING);
        }
        if (resources.getBoolean(R.bool.config_adaptive_charging_warning_enabled)) {
            mAdaptiveChargingNotification =
                    new AdaptiveChargingNotification(
                            context, new AdaptiveChargingManager(context), uiEventLogger);
            filter.addAction(
                    "com.google.android.systemui.adaptivecharging.ADAPTIVE_CHARGING_DEADLINE_SET");
            filter.addAction("PNW.acChargeNormally");
            filter.addAction("systemui.power.action.dismissAdaptiveChargingWarning");
        }
        if (resources.getBoolean(R.bool.config_incompatible_charging_warning_enabled)) {
            mIncompatibleChargerNotification =
                    new IncompatibleChargerNotification(context, uiEventLogger);
            filter.addAction(
                    IncompatibleChargerNotification.ACTION_DISMISS_INCOMPATIBLE_CHARGER_WARNING);
        }
        mBroadcastDispatcher.registerReceiverWithHandler(
                mBroadcastReceiver, filter, mHandler, UserHandle.ALL);
        Intent batteryChanged =
                context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryChanged != null) {
            mBroadcastReceiver.onReceive(context, batteryChanged);
        }
        if (mShutdownModeController != null) {
            mShutdownModeController.checkShutdownModeState();
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
        Resources resources = context.getResources();
        IntentFilter filter = new IntentFilter();
        if (resources.getBoolean(R.bool.config_wireless_charging_warning_enabled)) {
            mWirelessChargingNotification =
                    new WirelessChargingNotification(
                            context,
                            mExecutor,
                            mActivityStarter,
                            uiEventLogger,
                            mDeviceProvisionedController,
                            mDeviceStateManager,
                            mSubscriptionManager);
            filter.addAction(
                    WirelessChargingNotification.ACTION_DISMISS_WIRELESS_CHARGING_NOTIFICATION);
            filter.addAction(
                    WirelessChargingNotification.ACTION_CLICK_WIRELESS_CHARGING_NOTIFICATION);
        }
        if (resources.getBoolean(R.bool.config_charge_limit_discovery_enabled)) {
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
                                if (mWirelessChargingNotification != null) {
                                    mWirelessChargingNotification.dispatchIntent(intent);
                                }
                                if (mChargeLimitDiscoveryNotification != null) {
                                    mChargeLimitDiscoveryNotification.dispatchIntent(intent);
                                }
                                break;
                            case WirelessChargingNotification
                                    .ACTION_DISMISS_WIRELESS_CHARGING_NOTIFICATION:
                            case WirelessChargingNotification
                                    .ACTION_CLICK_WIRELESS_CHARGING_NOTIFICATION:
                                if (mWirelessChargingNotification != null) {
                                    mWirelessChargingNotification.dispatchIntent(intent);
                                }
                                break;
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

    private void prepareTurboProviderDump() {
        try (ContentProviderClient client =
                mContext.getContentResolver()
                        .acquireUnstableContentProviderClient(DumpUtils.PROVIDER_URI)) {
            if (client == null) {
                Log.w(TAG, "Failed to acquire provider: " + DumpUtils.PROVIDER_URI);
                return;
            }
            client.call("prepareProviderDump", "", null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to call Turbo provider", e);
        }
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
        if (mBatteryInfoBroadcast != null) {
            mBatteryInfoBroadcast.dump(pw);
        }
        if (mIncompatibleChargerNotification != null) {
            mIncompatibleChargerNotification.dump(pw);
        }
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
        if (mBatteryDefenderNotificationHandler != null) {
            mBatteryDefenderNotificationHandler.dump(pw);
        }
        mExecutor.execute(this::prepareTurboProviderDump);
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
