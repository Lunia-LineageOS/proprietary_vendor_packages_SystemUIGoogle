package com.google.android.systemui.dreamliner;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionCondition;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.settings.SecureSettings;

import com.google.android.systemui.dreamliner.kitt.DreamComponentMigrator;
import com.google.android.systemui.dreamliner.kitt.DreamPickerReceiver;
import com.google.android.systemui.dreamliner.kitt.KittCommandReceiver;
import com.google.android.systemui.dreamliner.kitt.KittFeatureStateRepository;

import dagger.Lazy;

import kotlinx.coroutines.CoroutineScope;

import vendor.google.wireless_charger.PowerInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

@SysUISingleton
public final class DockObserver implements DockManager {
    private static final String TAG = "DLObserver";

    static final String ACTION_ALIGN_STATE_CHANGE =
            "com.google.android.systemui.dreamliner.ALIGNMENT_CHANGE";
    static final String ACTION_DOCK_UI_ACTIVE =
            "com.google.android.systemui.dreamliner.ACTION_DOCK_UI_ACTIVE";
    static final String ACTION_DOCK_UI_IDLE =
            "com.google.android.systemui.dreamliner.ACTION_DOCK_UI_IDLE";
    static final String ACTION_REBIND_DOCK_SERVICE =
            "com.google.android.systemui.dreamliner.ACTION_REBIND_DOCK_SERVICE";
    static final String ACTION_START_DREAMLINER_CONTROL_SERVICE =
            "com.google.android.apps.dreamliner.START";
    static final String ACTION_START_TESTING =
            "com.google.android.systemui.dreamliner.ACTION_START_TESTING";
    static final String COMPONENTNAME_DREAMLINER_CONTROL_SERVICE =
            "com.google.android.apps.dreamliner/.DreamlinerControlService";
    static final String EXTRA_ALIGN_STATE = "align_state";
    static final String KEY_SHOWING = "showing";
    static final int RESULT_NOT_FOUND = -1;
    static final int RESULT_OK = 0;

    private static final String PERMISSION_WIRELESS_CHARGER_STATUS =
            "com.google.android.systemui.permission.WIRELESS_CHARGER_STATUS";
    private static final String WIRELESS_INCOMPATIBLE_CHARGING_STATE =
            "wireless_incompatible_charging_state";

    static boolean sIsDockingUiShowing = false;

    private final List<DockManager.AlignmentStateListener> mAlignmentStateListeners =
            new ArrayList<>();
    private final Executor mBackgroundExecutor;
    private final List<DockManager.DockEventListener> mClients = new ArrayList<>();
    private final ConfigurationController mConfigurationController;
    private final Context mContext;
    private final DockAlignmentController mDockAlignmentController;
    DockGestureController mDockGestureController;
    private final DockObserverBroadcastReceiver mDockObserverBroadcastReceiver =
            new DockObserverBroadcastReceiver();
    int mDockState = DockManager.STATE_NONE;
    private DreamComponentMigrator mDreamComponentMigrator;
    private final IDreamManager mDreamManager;
    private ImageView mDreamlinerGear;
    DreamlinerServiceConn mDreamlinerServiceConn;
    private DockIndicationController mIndicationController;
    private final KeyguardStateController mKeyguardStateController;
    private KittFeatureStateRepository mKittFeatureStateRepository;
    int mLastAlignState = DockManager.ALIGN_STATE_UNKNOWN;
    private final DelayableExecutor mMainExecutor;
    private Runnable mPhotoAction;
    private FrameLayout mPhotoPreview;
    ProtectedBroadcastSender mProtectedBroadcastSender;
    StartTestingReceiver mStartTestingReceiver;
    private final StatusBarStateController mStatusBarStateController;
    private final UserTracker mUserTracker;
    private final Lazy<VisualInterruptionDecisionProvider> mVisualInterruptionDecisionProviderLazy;
    private final Optional<WirelessCharger> mWirelessCharger;
    private final WirelessChargerCommander mWirelessChargerCommander;
    private boolean mIsWirelessCharging = false;
    private boolean mIsCharging = false;
    final DreamlinerBroadcastReceiver mDreamlinerReceiver = new DreamlinerBroadcastReceiver();

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, Context userContext) {
                    stopDreamlinerService(mContext);
                    updateCurrentDockingStatus(mContext);
                }
            };

    private final VisualInterruptionCondition mVisualInterruptionCondition =
            new VisualInterruptionCondition(
                    Set.of(
                            VisualInterruptionType.PEEK,
                            VisualInterruptionType.PULSE,
                            VisualInterruptionType.BUBBLE),
                    "docking UI showing") {
                @Override
                public boolean shouldSuppress() {
                    return sIsDockingUiShowing;
                }
            };

    @Inject
    public DockObserver(
            Context context,
            com.android.systemui.util.time.SystemClock systemClock,
            Optional<WirelessCharger> wirelessCharger,
            WirelessChargerCommander wirelessChargerCommander,
            StatusBarStateController statusBarStateController,
            Lazy<VisualInterruptionDecisionProvider> visualInterruptionDecisionProviderLazy,
            ConfigurationController configurationController,
            @Main DelayableExecutor mainExecutor,
            KeyguardStateController keyguardStateController,
            DockAlignmentController dockAlignmentController,
            IDreamManager dreamManager,
            @Background Executor backgroundExecutor,
            @Background CoroutineScope backgroundScope,
            SecureSettings secureSettings,
            BroadcastDispatcher broadcastDispatcher,
            PowerManager powerManager,
            Provider<KeyguardInteractor> keyguardInteractorProvider,
            UserManager userManager,
            UserTracker userTracker) {
        mVisualInterruptionDecisionProviderLazy = visualInterruptionDecisionProviderLazy;
        mMainExecutor = mainExecutor;
        mContext = context;
        mKeyguardStateController = keyguardStateController;
        mUserTracker = userTracker;
        mProtectedBroadcastSender = new ProtectedBroadcastSender(context.getApplicationContext());
        mDreamManager = dreamManager;
        mBackgroundExecutor = backgroundExecutor;
        mWirelessCharger = wirelessCharger;
        if (wirelessCharger.isEmpty()) {
            Log.i(TAG, "wireless charger is not present, check dock component.");
        }
        mWirelessChargerCommander = wirelessChargerCommander;
        UserManager contextUserManager = context.getSystemService(UserManager.class);
        if (contextUserManager != null && contextUserManager.isSystemUser()) {
            wirelessChargerCommander
                    .getWirelessChargerFanLevelChangedCallback()
                    .add(this::onFanLevelChanged);
            wirelessChargerCommander.registerFanLevelChangedCallback();
            context.registerReceiverAsUser(
                    wirelessChargerCommander.getCommandReceiver(),
                    UserHandle.ALL,
                    wirelessChargerCommander.getCommandIntents(),
                    PERMISSION_WIRELESS_CHARGER_STATUS,
                    null,
                    Context.RECEIVER_EXPORTED);
            setUpTestingReceiverIfNotProduction();
            if (!context.getPackageManager()
                    .hasSystemFeature("com.google.android.feature.PIXEL_TABLET_2023_EXPERIENCE")) {
                mKittFeatureStateRepository =
                        new KittFeatureStateRepository(
                                userTracker,
                                userManager,
                                backgroundScope,
                                backgroundExecutor,
                                broadcastDispatcher);
                mDreamComponentMigrator =
                        new DreamComponentMigrator(
                                context,
                                dreamManager,
                                userTracker,
                                userManager,
                                broadcastDispatcher,
                                backgroundScope,
                                backgroundExecutor,
                                secureSettings);
                mDreamComponentMigrator.start();
                registerDreamPickerReceiver(
                        broadcastDispatcher, dreamManager, userTracker, backgroundScope);
                registerKittCommandReceiver(
                        broadcastDispatcher,
                        systemClock,
                        dreamManager,
                        powerManager,
                        backgroundScope,
                        keyguardInteractorProvider);
            }
        }
        mStatusBarStateController = statusBarStateController;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(ACTION_REBIND_DOCK_SERVICE);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(
                mDockObserverBroadcastReceiver,
                filter,
                PERMISSION_WIRELESS_CHARGER_STATUS,
                null,
                Context.RECEIVER_EXPORTED);
        mDockAlignmentController = dockAlignmentController;
        mConfigurationController = configurationController;
        if (isWirelessIncompatibleCharging()) {
            Settings.Global.putInt(
                    context.getContentResolver(), WIRELESS_INCOMPATIBLE_CHARGING_STATE, 0);
        }
    }

    private void setUpTestingReceiverIfNotProduction() {
        if (Build.IS_ENG || Build.IS_USERDEBUG) {
            Log.d(TAG, "setUpTestingReceiverIfNotProduction");
            if (mStartTestingReceiver == null) {
                mStartTestingReceiver = new StartTestingReceiver();
            }
            mContext.registerReceiver(
                    mStartTestingReceiver,
                    new IntentFilter(ACTION_START_TESTING),
                    PERMISSION_WIRELESS_CHARGER_STATUS,
                    null,
                    Context.RECEIVER_EXPORTED);
        }
    }

    private static void registerDreamPickerReceiver(
            BroadcastDispatcher broadcastDispatcher,
            IDreamManager dreamManager,
            UserTracker userTracker,
            CoroutineScope backgroundScope) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(DreamPickerReceiver.ACTION_LOAD_METADATA);
        filter.addAction(DreamPickerReceiver.ACTION_LAUNCH_SETTINGS);
        filter.addAction(DreamPickerReceiver.ACTION_PREVIEW);
        broadcastDispatcher.registerReceiver(
                new DreamPickerReceiver(dreamManager, userTracker, backgroundScope),
                filter,
                null,
                UserHandle.ALL,
                Context.RECEIVER_EXPORTED,
                PERMISSION_WIRELESS_CHARGER_STATUS);
    }

    private static void registerKittCommandReceiver(
            BroadcastDispatcher broadcastDispatcher,
            com.android.systemui.util.time.SystemClock systemClock,
            IDreamManager dreamManager,
            PowerManager powerManager,
            CoroutineScope backgroundScope,
            Provider<KeyguardInteractor> keyguardInteractorProvider) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(KittCommandReceiver.ACTION_TURN_OFF_DREAM);
        filter.addAction(KittCommandReceiver.ACTION_START_DREAM);
        filter.addAction(KittCommandReceiver.ACTION_WAKE_UP_DREAM);
        broadcastDispatcher.registerReceiver(
                new KittCommandReceiver(
                        systemClock,
                        dreamManager,
                        powerManager,
                        backgroundScope,
                        keyguardInteractorProvider),
                filter,
                null,
                UserHandle.ALL,
                Context.RECEIVER_EXPORTED,
                PERMISSION_WIRELESS_CHARGER_STATUS);
    }

    public static boolean assertNotNull(Object obj) {
        if (obj != null) {
            return true;
        }
        Log.w(TAG, "DockGestureController is null");
        return false;
    }

    public void addInterruptionSuppressor() {
        mVisualInterruptionDecisionProviderLazy.get().addCondition(mVisualInterruptionCondition);
    }

    @Override
    public void addListener(DockManager.DockEventListener listener) {
        Log.d(TAG, "add listener: " + listener);
        if (!mClients.contains(listener)) {
            mClients.add(listener);
        }
        mMainExecutor.execute(
                () -> {
                    Log.d(TAG, "onDockEvent mDockState = " + mDockState);
                    listener.onEvent(mDockState);
                });
    }

    @Override
    public void removeListener(DockManager.DockEventListener listener) {
        Log.d(TAG, "remove listener: " + listener);
        mClients.remove(listener);
    }

    @Override
    public void addAlignmentStateListener(DockManager.AlignmentStateListener listener) {
        Log.d(TAG, "add alignment listener: " + listener);
        if (!mAlignmentStateListeners.contains(listener)) {
            mAlignmentStateListeners.add(listener);
        }
    }

    @Override
    public void removeAlignmentStateListener(DockManager.AlignmentStateListener listener) {
        mAlignmentStateListeners.remove(listener);
    }

    @Override
    public boolean isDocked() {
        return mDockState == DockManager.STATE_DOCKED
                || mDockState == DockManager.STATE_DOCKED_HIDE;
    }

    @Override
    public boolean isHidden() {
        return mDockState == DockManager.STATE_DOCKED_HIDE;
    }

    public BroadcastReceiver getDockObserverBroadcastReceiver() {
        return mDockObserverBroadcastReceiver;
    }

    public void setDreamlinerGear(ImageView dreamlinerGear) {
        if (dreamlinerGear == null) {
            Log.e(TAG, "set null for dreamlinerGear");
            return;
        }
        mDreamlinerGear = dreamlinerGear;
    }

    public void setPhotoPreview(FrameLayout photoPreview) {
        mPhotoPreview = photoPreview;
    }

    public void setIndicationController(DockIndicationController indicationController) {
        mIndicationController = indicationController;
        mConfigurationController.addCallback(indicationController);
    }

    public void registerDockAlignInfo() {
        if (mWirelessCharger.isEmpty()) {
            Log.w(TAG, "wirelessCharger is not present");
            return;
        }
        mDockAlignmentController.registerAlignInfoListener();
        mDockAlignmentController.addListener(this::onAlignStateChanged);
    }

    boolean isNewFeatureEnabled() {
        return mKittFeatureStateRepository != null
                && mKittFeatureStateRepository.getEnabledState().getValue();
    }

    void notifyDreamlinerAlignStateChanged(int alignState) {
        Log.d(TAG, "sendBroadcastAsUser for alignment changed, alignState: " + alignState);
        if (isDocked()) {
            mProtectedBroadcastSender.sendBroadcastAsUser(
                    new Intent(ACTION_ALIGN_STATE_CHANGE)
                            .putExtra(EXTRA_ALIGN_STATE, alignState)
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
        }
    }

    private void onAlignStateChanged(int alignState) {
        Log.d(TAG, "onAlignStateChanged alignState = " + alignState);
        mLastAlignState = alignState;
        for (DockManager.AlignmentStateListener listener : mAlignmentStateListeners) {
            listener.onAlignmentStateChanged(alignState);
        }
        runPhotoAction();
        notifyDreamlinerAlignStateChanged(alignState);
    }

    void onDockStateChanged(int newState) {
        if (mDockState == newState) {
            return;
        }
        Log.d(TAG, "dock state changed from " + mDockState + " to " + newState);
        int previousState = mDockState;
        mDockState = newState;
        for (int i = 0; i < mClients.size(); i++) {
            Log.d(TAG, "onDockEvent mDockState = " + mDockState);
            mClients.get(i).onEvent(mDockState);
        }
        if (mIndicationController != null) {
            mIndicationController.setDocking(isDocked());
        }
        if (previousState == DockManager.STATE_NONE && newState == DockManager.STATE_DOCKED) {
            notifyDreamlinerAlignStateChanged(mLastAlignState);
        }
    }

    private void onFanLevelChanged(int fanLevel) {
        Log.d(TAG, "notify l=" + fanLevel + ", isDocked=" + isDocked());
        if (isDocked()) {
            mProtectedBroadcastSender.sendBroadcastAsUser(
                    new Intent("com.google.android.systemui.dreamliner.ACTION_UPDATE_FAN_LEVEL")
                            .putExtra("fan_level", fanLevel)
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
        }
    }

    private void runPhotoAction() {
        if (mLastAlignState == DockManager.ALIGN_STATE_POOR
                || mLastAlignState == DockManager.ALIGN_STATE_TERRIBLE
                || mPhotoAction == null
                || mIndicationController.isPromoShowing()) {
            return;
        }
        mMainExecutor.executeDelayed(mPhotoAction, Duration.ofSeconds(3).toMillis());
    }

    private void configPhotoAction(Intent intent) {
        ResultReceiver resultReceiver = intent.getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER);
        boolean enabled = intent.getBooleanExtra("enabled", false);
        Log.d(TAG, "configPhotoAction, enabled=" + enabled);
        if (mDockGestureController != null) {
            mDockGestureController.setPhotoEnabled(enabled);
        }
        if (resultReceiver != null && mIndicationController != null) {
            mPhotoAction = () -> mIndicationController.showPromo(resultReceiver);
        } else {
            mPhotoAction = null;
        }
        runPhotoAction();
    }

    synchronized void startDreamlinerService(
            Context context,
            int type,
            int orientation,
            boolean isGetInfoSupported,
            int id,
            int manufacturerCode,
            boolean suppressInterruptions,
            boolean isNewFlow) {
        if (mDreamlinerServiceConn != null) {
            return;
        }
        if (suppressInterruptions && !isNewFlow) {
            addInterruptionSuppressor();
            try {
                mDreamManager.forceAmbientDisplayEnabled(true);
            } catch (RemoteException e) {
            }
        }
        mDreamlinerReceiver.registerReceiver(context);
        Intent intent =
                new Intent(ACTION_START_DREAMLINER_CONTROL_SERVICE)
                        .setComponent(
                                ComponentName.unflattenFromString(
                                        COMPONENTNAME_DREAMLINER_CONTROL_SERVICE))
                        .putExtra("type", type)
                        .putExtra("orientation", orientation)
                        .putExtra("is_get_info_supported", isGetInfoSupported)
                        .putExtra("id", id)
                        .putExtra("manufacturer_code", manufacturerCode)
                        .putExtra("isNewFlow", isNewFlow)
                        .putExtra("occluded", mKeyguardStateController.isOccluded());
        mDreamlinerServiceConn = new DreamlinerServiceConn(context);
        boolean bound;
        try {
            bound =
                    context.bindServiceAsUser(
                            intent,
                            mDreamlinerServiceConn,
                            Context.BIND_AUTO_CREATE,
                            new UserHandle(mUserTracker.getUserId()));
        } catch (SecurityException e) {
            Log.e(TAG, e.getMessage(), e);
            bound = false;
        }
        if (bound) {
            mMainExecutor.execute(() -> initDockGestureController(context));
        } else {
            mDreamlinerServiceConn = null;
            Log.w(TAG, "Unable to bind Dreamliner service: " + intent);
        }
    }

    private void stopDreamlinerService(Context context) {
        try {
            mDreamManager.forceAmbientDisplayEnabled(false);
        } catch (RemoteException e) {
        }
        onDockStateChanged(DockManager.STATE_NONE);
        try {
            if (mDreamlinerServiceConn != null) {
                if (assertNotNull(mDockGestureController)) {
                    mConfigurationController.removeCallback(mDockGestureController);
                    mDockGestureController.stopMonitoring();
                    mDockGestureController = null;
                }
                mUserTracker.removeCallback(mUserChangedCallback);
                mDreamlinerReceiver.unregisterReceiver(context);
                context.unbindService(mDreamlinerServiceConn);
                mDreamlinerServiceConn = null;
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    private void initDockGestureController(Context context) {
        mUserTracker.addCallback(mUserChangedCallback, mMainExecutor);
        if (mDreamlinerGear == null) {
            Log.e(TAG, "initDockGestureController fail. dreamlinerGear is null");
            return;
        }
        mDockGestureController =
                new DockGestureController(
                        context,
                        mProtectedBroadcastSender,
                        mDreamlinerGear,
                        mPhotoPreview,
                        (View) mDreamlinerGear.getParent(),
                        mIndicationController,
                        mStatusBarStateController,
                        mKeyguardStateController);
        mConfigurationController.addCallback(mDockGestureController);
    }

    void tryToStartDreamlinerServiceIfNeeded(Context context) {
        Log.d(TAG, "isCharging:" + mIsCharging + " wirelessCharging:" + mIsWirelessCharging);
        if (mIsCharging) {
            UserManager userManager = context.getSystemService(UserManager.class);
            if (!userManager.isManagedProfile() || userManager.isSystemUser()) {
                if (mIsWirelessCharging) {
                    mWirelessChargerCommander.asyncIsDockPresent(
                            new IsDockPresentCallback(context));
                } else if (isNewFeatureEnabled()) {
                    Log.d(TAG, "starting DreamlinerService from new flow");
                    startDreamlinerService(mContext, -1, -1, false, 0, -1, false, true);
                }
                return;
            }
        }
        Log.i(TAG, "do not bind Dreamliner service for work profile");
    }

    void updateChargingState(Context context, Intent intent) {
        if (intent == null) {
            intent =
                    context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
        int plugged = -1;
        if (intent == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
            Log.i(
                    TAG,
                    "invalid battery intent when checking plugged status. batteryIntent=" + intent);
        } else {
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            Log.d(TAG, "plugged=" + plugged);
        }
        mIsWirelessCharging = plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
        mIsCharging =
                plugged == BatteryManager.BATTERY_PLUGGED_AC
                        || plugged == BatteryManager.BATTERY_PLUGGED_USB
                        || mIsWirelessCharging;
    }

    void updateCurrentDockingStatus(Context context) {
        try {
            mDreamManager.forceAmbientDisplayEnabled(false);
        } catch (RemoteException e) {
        }
        updateChargingState(context, null);
        tryToStartDreamlinerServiceIfNeeded(context);
    }

    private boolean isWirelessIncompatibleCharging() {
        return Settings.Global.getInt(
                        mContext.getContentResolver(), WIRELESS_INCOMPATIBLE_CHARGING_STATE, 0)
                == 1;
    }

    private void updateWirelessIncompatibleChargingState(boolean unplugged) {
        boolean incompatible = !unplugged && checkWirelessIncompatibleCharging();
        if (isWirelessIncompatibleCharging() != incompatible) {
            Settings.Global.putInt(
                    mContext.getContentResolver(),
                    WIRELESS_INCOMPATIBLE_CHARGING_STATE,
                    incompatible ? 1 : 0);
        }
    }

    private boolean checkWirelessIncompatibleCharging() {
        WirelessChargerImpl wirelessCharger = (WirelessChargerImpl) mWirelessCharger.get();
        if (!wirelessCharger.initHALInterface()) {
            return false;
        }
        try {
            PowerInfo powerInfo = wirelessCharger.mWirelessCharger.getPowerInformation();
            byte compatibility =
                    wirelessCharger.mWirelessCharger.getDockInformation().compatibility;
            Log.i(
                    "Dreamliner-WLC_HAL",
                    "dockCompatibility: "
                            + compatibility
                            + "powerInfo.negotiated: "
                            + powerInfo.negotiated
                            + "powerInfo.txlimitreason: "
                            + powerInfo.txlimitreason);
            // ??? Compatibility, power and TX limit reason values are unnamed in the HAL.
            return (compatibility == -1 || compatibility == 6)
                    && powerInfo.negotiated <= 5000
                    && powerInfo.txlimitreason == 2;
        } catch (Exception e) {
            Log.e("Dreamliner-WLC_HAL", "isWirelessIncompatibleCharging fail", e);
            return false;
        }
    }

    final class DockObserverBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            Log.d(TAG, "onReceive(); " + intent.getAction());
            switch (intent.getAction()) {
                case Intent.ACTION_POWER_DISCONNECTED:
                    stopDreamlinerService(context);
                    sIsDockingUiShowing = false;
                    break;
                case Intent.ACTION_BATTERY_CHANGED:
                    boolean wasCharging = mIsCharging;
                    boolean wasWirelessCharging = mIsWirelessCharging;
                    updateChargingState(context, intent);
                    if (wasCharging != mIsCharging) {
                        tryToStartDreamlinerServiceIfNeeded(context);
                    }
                    if (wasWirelessCharging
                            && !mIsWirelessCharging
                            && mWirelessCharger.isPresent()) {
                        mBackgroundExecutor.execute(
                                () -> updateWirelessIncompatibleChargingState(true));
                    } else if (mIsWirelessCharging && mWirelessCharger.isPresent()) {
                        mBackgroundExecutor.execute(
                                () -> updateWirelessIncompatibleChargingState(false));
                    }
                    break;
                case Intent.ACTION_BOOT_COMPLETED:
                case ACTION_REBIND_DOCK_SERVICE:
                    updateCurrentDockingStatus(context);
                    break;
            }
        }
    }

    class DreamlinerBroadcastReceiver extends BroadcastReceiver {
        private boolean mListening;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            Log.d(TAG, "Dock Receiver.onReceive(): " + intent.getAction());
            switch (intent.getAction()) {
                case ACTION_DOCK_UI_IDLE:
                    sendDockIdleIntent();
                    sIsDockingUiShowing = true;
                    break;
                case ACTION_DOCK_UI_ACTIVE:
                    sendDockActiveIntent();
                    sIsDockingUiShowing = false;
                    break;
                case "com.google.android.systemui.dreamliner.dream":
                    PowerManager powerManager = context.getSystemService(PowerManager.class);
                    if (powerManager.isScreenOn()) {
                        Log.d(TAG, "screen is on, going to sleep");
                        powerManager.goToSleep(SystemClock.uptimeMillis());
                    }
                    break;
                case "com.google.android.systemui.dreamliner.paired":
                    if (assertNotNull(mDockGestureController)) {
                        mDockGestureController.setTapAction(
                                intent.getParcelableExtra("single_tap_action"));
                    }
                    resumeDocking();
                    break;
                case "com.google.android.systemui.dreamliner.pause":
                    onDockStateChanged(DockManager.STATE_DOCKED_HIDE);
                    if (assertNotNull(mDockGestureController)) {
                        mDockGestureController.stopMonitoring();
                    }
                    break;
                case "com.google.android.systemui.dreamliner.resume":
                    resumeDocking();
                    break;
                case "com.google.android.systemui.dreamliner.undock":
                    onDockStateChanged(DockManager.STATE_NONE);
                    if (assertNotNull(mDockGestureController)) {
                        mDockGestureController.stopMonitoring();
                    }
                    break;
                case "com.google.android.systemui.dreamliner.assistant_poodle":
                    if (mIndicationController != null) {
                        mIndicationController.setShowing(
                                intent.getBooleanExtra(KEY_SHOWING, false));
                    }
                    break;
                case "com.google.android.systemui.dreamliner.photo":
                    configPhotoAction(intent);
                    break;
                case "com.google.android.systemui.dreamliner.photo_error":
                    Log.w(TAG, "Fail to launch photo");
                    if (mDockGestureController != null) {
                        mDockGestureController.hidePhotoPreview(false);
                    }
                    break;
            }
        }

        private void resumeDocking() {
            onDockStateChanged(DockManager.STATE_DOCKED);
            if (assertNotNull(mDockGestureController)) {
                mDockGestureController.startMonitoring();
            }
        }

        void registerReceiver(Context context) {
            if (mListening) {
                return;
            }
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_DOCK_UI_IDLE);
            filter.addAction(ACTION_DOCK_UI_ACTIVE);
            filter.addAction("com.google.android.systemui.dreamliner.dream");
            filter.addAction("com.google.android.systemui.dreamliner.paired");
            filter.addAction("com.google.android.systemui.dreamliner.pause");
            filter.addAction("com.google.android.systemui.dreamliner.resume");
            filter.addAction("com.google.android.systemui.dreamliner.undock");
            filter.addAction("com.google.android.systemui.dreamliner.assistant_poodle");
            filter.addAction("com.google.android.systemui.dreamliner.photo");
            filter.addAction("com.google.android.systemui.dreamliner.photo_error");
            context.registerReceiverAsUser(
                    this,
                    UserHandle.ALL,
                    filter,
                    PERMISSION_WIRELESS_CHARGER_STATUS,
                    null,
                    Context.RECEIVER_EXPORTED);
            mListening = true;
        }

        void unregisterReceiver(Context context) {
            if (mListening) {
                context.unregisterReceiver(this);
                mListening = false;
            }
        }
    }

    private void sendDockIdleIntent() {
        Log.d(TAG, "sendDockIdleIntent()");
        mProtectedBroadcastSender.sendBroadcast(
                new Intent(Intent.ACTION_DOCK_IDLE).addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
    }

    private void sendDockActiveIntent() {
        Log.d(TAG, "sendDockActiveIntent()");
        mProtectedBroadcastSender.sendBroadcast(
                new Intent(Intent.ACTION_DOCK_ACTIVE).addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
    }

    final class DreamlinerServiceConn implements ServiceConnection {
        private final Context mContext;

        DreamlinerServiceConn(Context context) {
            mContext = context;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {}

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sendDockActiveIntent();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            stopDreamlinerService(mContext);
            sIsDockingUiShowing = false;
            updateChargingState(mContext, null);
            Log.d(TAG, "onBindingDied, isCharging: " + mIsCharging);
            if (mIsCharging) {
                updateCurrentDockingStatus(mContext);
            }
        }
    }

    final class IsDockPresentCallback implements WirelessCharger.IsDockPresentCallback {
        private final Context mContext;

        IsDockPresentCallback(Context context) {
            mContext = context;
        }

        @Override
        public void onCallback(
                boolean docked,
                byte type,
                byte orientation,
                boolean isGetInfoSupported,
                int id,
                int manufacturerCode) {
            if (docked || isNewFeatureEnabled()) {
                startDreamlinerService(
                        mContext,
                        type,
                        orientation,
                        isGetInfoSupported,
                        id,
                        manufacturerCode,
                        true,
                        !docked && isNewFeatureEnabled());
            }
        }
    }

    static final class ProtectedBroadcastSender {
        private final Context mContext;

        ProtectedBroadcastSender(Context context) {
            mContext = context;
        }

        void sendBroadcast(Intent intent) {
            if (!mContext.getUser().isSystem()) {
                return;
            }
            try {
                mContext.sendBroadcast(intent);
            } catch (SecurityException e) {
                Log.w(TAG, "Send protected broadcast failed. intent= " + intent, e);
            }
        }

        void sendBroadcastAsUser(Intent intent) {
            if (!mContext.getUser().isSystem()) {
                return;
            }
            try {
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            } catch (SecurityException e) {
                Log.w(TAG, "Send protected broadcast failed. intent= " + intent, e);
            }
        }
    }

    final class StartTestingReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            Log.d(TAG, "onReceive(): " + action);
            if (!TextUtils.equals(action, ACTION_START_TESTING)) {
                Log.d(TAG, "unknown action, abort");
                return;
            }
            Log.d(TAG, "start testing");
            startDreamlinerService(
                    mContext, 0, 2, true, intent.getIntExtra("id", 0), 114, true, false);
        }
    }
}
