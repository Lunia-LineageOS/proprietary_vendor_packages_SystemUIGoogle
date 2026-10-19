package com.google.android.systemui.reversecharging;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Temperature;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.systemui.BootCompleteCache;
import com.android.systemui.BootCompleteCacheImpl;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.CallbackController;

import com.google.android.systemui.res.R;
import com.google.android.systemui.statusbar.policy.BatteryControllerImplGoogle;

import vendor.google.wireless_charger.RtxStatusInfo;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@SysUISingleton
public class ReverseChargingController extends BroadcastReceiver
        implements CallbackController<ReverseChargingController.ReverseChargingChangeCallback> {

    private static final boolean DEBUG = Log.isLoggable("ReverseChargingControl", 3);
    private static final String TAG = "ReverseChargingControl";

    public static final long DURATION_TO_REVERSE_TIME_OUT = 60000;
    public static final long DURATION_TO_REVERSE_AC_TIME_OUT = 60000;
    public static final long DURATION_TO_REVERSE_RX_REMOVAL_TIME_OUT = 30000;
    public static final long DURATION_TO_ADVANCED_ACCESSORY_DEVICE_RECONNECTED_TIME_OUT = 120000;
    public static final long DURATION_TO_ADVANCED_PHONE_RECONNECTED_TIME_OUT = 120000;
    public static final long DURATION_TO_ADVANCED_PLUS_ACCESSORY_DEVICE_RECONNECTED_TIME_OUT =
            120000;
    public static final long DURATION_WAIT_NFC_SERVICE = 10000;

    public interface ReverseChargingChangeCallback {
        default void onReverseChargingChanged(boolean isReverse, int level, String name) {}
    }

    private final Context mContext;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Optional<ReverseWirelessCharger> mRtxChargerManagerOptional;
    private final AlarmManager mAlarmManager;
    private final Optional<UsbManager> mUsbManagerOptional;
    private final Executor mMainExecutor;
    private final Executor mBgExecutor;
    private final BootCompleteCacheImpl mBootCompleteCache;
    private final IThermalService mThermalService;

    private final boolean mDoesNfcConflictWithWlc;
    private final boolean mDoesNfcConflictWithUsbAudio;
    private final int[] mNfcUsbVendorIds;
    private final int[] mNfcUsbProductIds;

    private final ArrayList<ReverseChargingChangeCallback> mChangeCallbacks = new ArrayList<>();
    private final BootCompleteCache.BootCompleteListener mBootCompleteListener;
    private final BatteryController.BatteryStateChangeCallback mBatteryStateChangeCallback;
    private final AlarmManager.OnAlarmListener mRtxFinishAlarmAction;
    private final AlarmManager.OnAlarmListener mRtxFinishRxFullAlarmAction;
    private final AlarmManager.OnAlarmListener mCheckNfcConflictWithUsbAudioAlarmAction;
    private final AlarmManager.OnAlarmListener mReconnectedTimeoutAlarmAction;
    private final AlarmManager.OnAlarmListener mAccessoryDeviceRemovedTimeoutAlarmAction;

    private IThermalEventListener mSkinThermalEventListener;

    private int mCurrentRtxMode = 0;
    private boolean mIsUsbPlugIn = false;
    private int mCurrentRtxReceiverType = 0;
    private boolean mProvidingBattery = false;
    private long mReverseStartTime = 0;

    private boolean mReverseChargingEnabled = false;
    private int mRtxLevel = -1;
    private String mName = null;

    private int mLevel = 0;
    private boolean mPluggedAc = false;
    private boolean mPowerSave = false;
    private boolean mWirelessCharging = false;
    private boolean mStopReverseAtAcUnplug = false;
    private boolean mStartReconnected = false;
    private boolean mUseRxRemovalTimeOut = false;
    private boolean mRestoreUsbNfcPollingMode = false;
    private boolean mRestoreWlcNfcPollingMode = false;

    private boolean mCacheIsReverseSupported = false;
    private boolean mIsReverseSupported = false;
    private boolean mBootCompleted = false;

    final class SkinThermalEventListener extends IThermalEventListener.Stub {
        @Override
        public void notifyThrottling(Temperature temperature) {
            int status = temperature.getStatus();
            Log.i(TAG, "notifyThrottling(): thermal status=" + status);
            if (!mReverseChargingEnabled || status < 4) {
                return;
            }
            setReverseStateInternal(3, false);
        }
    }

    @Inject
    public ReverseChargingController(
            Context context,
            BroadcastDispatcher broadcastDispatcher,
            Optional<ReverseWirelessCharger> rtxChargerManagerOptional,
            AlarmManager alarmManager,
            Optional<UsbManager> usbManagerOptional,
            @Main Executor mainExecutor,
            @Background Executor bgExecutor,
            BootCompleteCacheImpl bootCompleteCache,
            IThermalService thermalService) {
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mRtxChargerManagerOptional = rtxChargerManagerOptional;
        mAlarmManager = alarmManager;
        mUsbManagerOptional = usbManagerOptional;
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;
        mBootCompleteCache = bootCompleteCache;
        mThermalService = thermalService;

        mBootCompleteListener =
                () -> {
                    if (DEBUG) {
                        Log.d(TAG, "onBootComplete(): ACTION_BOOT_COMPLETED");
                    }
                    mBootCompleted = true;
                    setRtxTimer(2, DURATION_WAIT_NFC_SERVICE);
                };

        mRtxFinishAlarmAction = () -> onAlarmRtxFinish(5);
        mRtxFinishRxFullAlarmAction = () -> onAlarmRtxFinish(103);
        mCheckNfcConflictWithUsbAudioAlarmAction =
                () -> {
                    if (mUsbManagerOptional.isPresent()) {
                        for (UsbDevice device :
                                mUsbManagerOptional.get().getDeviceList().values()) {
                            checkAndChangeNfcPollingAgainstUsbAudioDevice(false, device);
                        }
                    }
                };
        mReconnectedTimeoutAlarmAction =
                () -> {
                    if (DEBUG) {
                        Log.w(TAG, "mReConnectedTimeoutAlarmAction() timeout");
                    }
                    mStartReconnected = false;
                    onAlarmRtxFinish(6);
                };
        mAccessoryDeviceRemovedTimeoutAlarmAction =
                () -> {
                    if (DEBUG) {
                        Log.w(TAG, "mAccessoryDeviceRemovedTimeoutAlarmAction() timeout");
                    }
                    onAlarmRtxFinish(6);
                };

        mBatteryStateChangeCallback =
                new BatteryController.BatteryStateChangeCallback() {
                    @Override
                    public void onPowerSaveChanged(boolean isPowerSave) {
                        mPowerSave = isPowerSave;
                    }

                    @Override
                    public void onWirelessChargingChanged(boolean isWirelessCharging) {
                        mWirelessCharging = isWirelessCharging;
                    }
                };

        mDoesNfcConflictWithWlc =
                context.getResources().getBoolean(R.bool.config_nfc_conflict_with_wlc);
        mNfcUsbVendorIds =
                context.getResources()
                        .getIntArray(R.array.config_nfc_conflict_with_usb_audio_vendorid);
        mNfcUsbProductIds =
                context.getResources()
                        .getIntArray(R.array.config_nfc_conflict_with_usb_audio_productid);
        if (mNfcUsbVendorIds.length == mNfcUsbProductIds.length) {
            mDoesNfcConflictWithUsbAudio =
                    context.getResources().getBoolean(R.bool.config_nfc_conflict_with_usb_audio);
        } else {
            throw new IllegalStateException("VendorIds and ProductIds must be the same length");
        }
    }

    public void init(BatteryControllerImplGoogle batteryController) {
        if (!mContext.getSystemService(UserManager.class).isSystemUser()) {
            Log.i(TAG, "Skip initialization for non system user");
            mCacheIsReverseSupported = true;
            mIsReverseSupported = false;
            return;
        }
        batteryController.addCallback(mBatteryStateChangeCallback);
        mCacheIsReverseSupported = false;
        mReverseChargingEnabled = false;
        mRtxLevel = -1;
        mName = null;

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        mBroadcastDispatcher.registerReceiver(this, filter);

        mBootCompleteCache.addListener(mBootCompleteListener);

        if (mRtxChargerManagerOptional.isPresent()) {
            setRtxMode(false);
            ReverseWirelessCharger rwc = mRtxChargerManagerOptional.get();
            rwc.addRtxStatusCallback(this::onRtxStatusChanged);
            try {
                if (mSkinThermalEventListener == null) {
                    mSkinThermalEventListener = new SkinThermalEventListener();
                }
                mThermalService.registerThermalEventListenerWithType(mSkinThermalEventListener, 3);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not register thermal event listener, exception: " + e);
            }
        }
    }

    public boolean isReverseSupported() {
        if (mCacheIsReverseSupported) {
            return mIsReverseSupported;
        }
        if (!mRtxChargerManagerOptional.isPresent()) {
            if (DEBUG) {
                Log.d(TAG, "isReverseSupported(): mRtxChargerManagerOptional is not present!");
            }
            return false;
        }
        boolean supported = mRtxChargerManagerOptional.get().isRtxSupported();
        mIsReverseSupported = supported;
        mCacheIsReverseSupported = true;
        return supported;
    }

    private boolean isLowBattery() {
        int threshold =
                Settings.Global.getInt(
                                mContext.getContentResolver(), "advanced_battery_usage_amount", 2)
                        * 5;
        if (mLevel > threshold) {
            return false;
        }
        Log.w(
                TAG,
                "The battery is lower than threshold turn off reverse charging ! level : "
                        + mLevel
                        + ", threshold : "
                        + threshold);
        return true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        handleIntentForReverseCharging(intent);
    }

    public void handleIntentForReverseCharging(Intent intent) {
        if (!isReverseSupported()) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            boolean prevPluggedAc = mPluggedAc;
            mLevel =
                    (int)
                            ((intent.getIntExtra("level", 0) * 100.0f)
                                    / intent.getIntExtra("scale", 100));
            int plugged = intent.getIntExtra("plugged", 0);
            mPluggedAc = plugged == 1;
            Log.i(
                    TAG,
                    "handleIntentForReverseCharging(): rtx="
                            + (mReverseChargingEnabled ? 1 : 0)
                            + " wlc="
                            + (mWirelessCharging ? 1 : 0)
                            + " plgac="
                            + (prevPluggedAc ? 1 : 0)
                            + " ac="
                            + (mPluggedAc ? 1 : 0)
                            + " acrtx="
                            + (mStopReverseAtAcUnplug ? 1 : 0)
                            + " extra="
                            + plugged
                            + " this="
                            + this);

            if (mReverseChargingEnabled && mWirelessCharging) {
                if (DEBUG) {
                    Log.d(TAG, "handleIntentForReverseCharging(): wireless charging, stop");
                }
                setReverseStateInternal(102, false);
                return;
            }
            if (mReverseChargingEnabled && prevPluggedAc && !mPluggedAc && mStopReverseAtAcUnplug) {
                if (DEBUG) {
                    Log.d(TAG, "handleIntentForReverseCharging(): wired charging, stop");
                }
                mStopReverseAtAcUnplug = false;
                setReverseStateInternal(106, false);
                return;
            }
            if (mReverseChargingEnabled && isLowBattery()) {
                if (DEBUG) {
                    Log.d(
                            TAG,
                            "handleIntentForReverseCharging(): lower then battery threshold,"
                                    + " stop");
                }
                setReverseStateInternal(4, false);
                return;
            }
            if (mReverseChargingEnabled || prevPluggedAc || !mPluggedAc) {
                return;
            }
            if (mCurrentRtxMode == 0) {
                Log.d(TAG, "RTX is disabled");
                return;
            }
            if (Settings.Global.getInt(
                            mContext.getContentResolver(),
                            "settings_key_reverse_charging_auto_turn_on",
                            0)
                    != 1) {
                Log.d(TAG, "auto turn on is disabled");
                return;
            }
            if (!mBootCompleted) {
                Log.i(TAG, "skip auto turn on");
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "handleIntentForReverseCharging(): wired charging, start");
            }
            mStopReverseAtAcUnplug = true;
            setReverseStateInternal(3, true);
        } else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)) {
            if (mReverseChargingEnabled && mPowerSave) {
                Log.i(TAG, "handleIntentForReverseCharging(): power save, stop");
                setReverseStateInternal(105, false);
            }
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            UsbDevice device = (UsbDevice) intent.getParcelableExtra("device");
            if (device == null) {
                Log.w(TAG, "handleIntentForReverseCharging() UsbDevice is null!");
                mIsUsbPlugIn = false;
                return;
            }
            if (mDoesNfcConflictWithUsbAudio) {
                checkAndChangeNfcPollingAgainstUsbAudioDevice(false, device);
            }
            boolean isAudio = false;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                if (device.getInterface(i).getInterfaceClass() == 1) {
                    isAudio = true;
                    break;
                }
            }
            boolean isLowPower = false;
            for (int i = 0; i < device.getConfigurationCount(); i++) {
                if (device.getConfiguration(i).getMaxPower() < 100) {
                    isLowPower = true;
                    break;
                }
            }
            mIsUsbPlugIn = !(isAudio && isLowPower);
            if (mReverseChargingEnabled && mIsUsbPlugIn) {
                setReverseStateInternal(108, false);
                Log.d(
                        TAG,
                        "handleIntentForReverseCharging(): stop reverse charging because USB-C"
                                + " plugin!");
            }
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            UsbDevice device = (UsbDevice) intent.getParcelableExtra("device");
            if (mDoesNfcConflictWithUsbAudio && device != null) {
                checkAndChangeNfcPollingAgainstUsbAudioDevice(true, device);
            }
            mIsUsbPlugIn = false;
        }
    }

    public void setReverseState(boolean reverse) {
        if (isReverseSupported()) {
            if (DEBUG) {
                Log.d(TAG, "setReverseState(): rtx=" + (reverse ? 1 : 0));
            }
            mStopReverseAtAcUnplug = false;
            setReverseStateInternal(2, reverse);
        }
    }

    private void setReverseStateInternal(int reason, boolean reverse) {
        if (!isReverseSupported()) {
            return;
        }
        Log.i(TAG, "setReverseStateInternal(): rtx=" + (reverse ? 1 : 0) + ",reason=" + reason);
        if (!reverse || mReverseChargingEnabled) {
            logReverseStopEvent(reason);
        } else {
            logReverseStartEvent(reason);
            if (mPowerSave) {
                logReverseStopEvent(104);
                return;
            } else if (isLowBattery()) {
                logReverseStopEvent(100);
                return;
            } else if (mIsUsbPlugIn) {
                logReverseStopEvent(107);
                return;
            }
        }
        if (reverse != mReverseChargingEnabled) {
            if (reverse && mDoesNfcConflictWithWlc && !mRestoreWlcNfcPollingMode) {
                enableNfcPollingMode(false);
                mRestoreWlcNfcPollingMode = true;
            }
            mReverseChargingEnabled = reverse;
            if (reverse) {
                setRtxTimer(0, DURATION_TO_REVERSE_TIME_OUT);
            }
            setRtxMode(reverse);
        }
    }

    private void setRtxMode(boolean enable) {
        if (!mRtxChargerManagerOptional.isPresent()) {
            Log.i(TAG, "setRtxMode(): rtx not available");
            return;
        }
        mBgExecutor.execute(
                () -> {
                    Log.i(TAG, "setRtxMode(): rtx=" + (enable ? 1 : 0));
                    mRtxChargerManagerOptional.get().setRtxMode(enable);
                });
    }

    private void onRtxStatusChanged(RtxStatusInfo rtxStatusInfo) {
        if (ReverseWirelessCharger.DEBUG) {
            Log.d(
                    "ReverseWirelessCharger",
                    "onRtxStatusChanged() RtxStatusInfo : " + rtxStatusInfo.toString());
        }
        Bundle bundle = new Bundle();
        bundle.putInt("key_rtx_mode", rtxStatusInfo.mode);
        bundle.putInt("key_accessory_type", rtxStatusInfo.acctype);
        bundle.putBoolean("key_rtx_connection", rtxStatusInfo.chgConnected);
        bundle.putInt("key_rtx_iout", rtxStatusInfo.iout);
        bundle.putInt("key_rtx_vout", rtxStatusInfo.vout);
        bundle.putInt("key_rtx_level", rtxStatusInfo.level);
        bundle.putInt("key_reason_type", rtxStatusInfo.reason);
        onReverseStateChanged(bundle);
    }

    private void onReverseStateChanged(Bundle bundle) {
        Log.i(
                TAG,
                "onReverseStateChanged(): rtx="
                        + (bundle.getInt("key_rtx_mode") == 1 ? 1 : 0)
                        + " bundle="
                        + bundle
                        + " this="
                        + this);
        mBgExecutor.execute(() -> onReverseStateChangedOnBackgroundThread(bundle));
    }

    private void onReverseStateChangedOnBackgroundThread(Bundle bundle) {
        if (DEBUG) {
            Log.d(
                    TAG,
                    "onReverseStateChangedOnBackgroundThread(): rtx="
                            + (bundle.getInt("key_rtx_mode") == 1 ? 1 : 0)
                            + " bundle="
                            + bundle
                            + " this="
                            + this);
        }
        int rtxMode = bundle.getInt("key_rtx_mode");
        int reasonType = bundle.getInt("key_reason_type");
        boolean rtxConnection = bundle.getBoolean("key_rtx_connection");
        int accessoryType = bundle.getInt("key_accessory_type");
        int rtxLevel = bundle.getInt("key_rtx_level");

        if (!mReverseChargingEnabled && mWirelessCharging && rtxMode == 0 && rtxLevel > 0) {
            mRtxLevel = rtxLevel;
            if (TextUtils.isEmpty(mName)) {
                mName = mContext.getString(R.string.reverse_charging_device_name_text);
            }
            fireReverseChanged();
            return;
        }

        if (!isReverseSupported()) {
            mReverseChargingEnabled = false;
            mRtxLevel = -1;
            mName = null;
            fireReverseChanged();
            return;
        }

        if (mCurrentRtxMode == 1 && rtxMode != 1 && mReverseChargingEnabled) {
            switch (reasonType) {
                case 0:
                    if (rtxMode == 2 && mCurrentRtxReceiverType != 0) {
                        logReverseStopEvent(8);
                    } else {
                        logReverseStopEvent(1);
                    }
                    break;
                case 1:
                    logReverseStopEvent(4);
                    break;
                case 2:
                    logReverseStopEvent(3);
                    break;
                case 3:
                    logReverseStopEvent(102);
                    break;
                case 4:
                    logReverseStopEvent(110);
                    break;
                case 15:
                    logReverseStopEvent(8);
                    break;
            }
            Log.w(TAG, "Reverse charging error happened : " + reasonType);
        } else if (mCurrentRtxMode != 1 && rtxMode == 1 && !mReverseChargingEnabled) {
            logReverseStartEvent(1);
        }

        if (mCurrentRtxMode != 1
                && rtxMode == 1
                && !mReverseChargingEnabled
                && mDoesNfcConflictWithWlc
                && !mRestoreWlcNfcPollingMode) {
            enableNfcPollingMode(false);
            mRestoreWlcNfcPollingMode = true;
        }

        mCurrentRtxMode = rtxMode;
        mReverseChargingEnabled = false;
        mRtxLevel = -1;
        mName = null;

        if (rtxMode == 1) {
            playSoundIfNecessary(rtxConnection, accessoryType);
            mProvidingBattery = rtxConnection;
            mReverseChargingEnabled = true;

            if (!rtxConnection) {
                if (DEBUG) {
                    Log.d(TAG, "receiver is not available");
                }
                mRtxLevel = -1;
                mCurrentRtxReceiverType = 0;
            } else {
                mStopReverseAtAcUnplug = false;
                mRtxLevel = rtxLevel;
                mUseRxRemovalTimeOut = true;
                if (mCurrentRtxReceiverType != accessoryType) {
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "receiver type updated: "
                                        + mCurrentRtxReceiverType
                                        + " "
                                        + accessoryType);
                        Log.d(TAG, "logReverseAccessoryType: " + accessoryType);
                    }
                    if (accessoryType != 0) {
                        ReverseChargingMetrics.logAccessoryType(accessoryType);
                    }
                    mCurrentRtxReceiverType = accessoryType;
                }
            }
        } else {
            mStopReverseAtAcUnplug = false;
            mProvidingBattery = false;
            mUseRxRemovalTimeOut = false;
            mStartReconnected = false;
            if (mDoesNfcConflictWithWlc && mRestoreWlcNfcPollingMode) {
                mRestoreWlcNfcPollingMode = false;
                enableNfcPollingMode(!mRestoreUsbNfcPollingMode);
            }
        }

        if (rtxMode == 0 && (reasonType == 4 || reasonType == 5)) {
            Log.i(TAG, "disable RTX by reason: " + reasonType);
            mRtxLevel = -100;
        }

        fireReverseChanged();

        cancelRtxTimer(0);
        cancelRtxTimer(1);
        cancelRtxTimer(4);
        if (!mStartReconnected) {
            cancelRtxTimer(3);
        }

        if (mReverseChargingEnabled && mRtxLevel == -1) {
            long duration;
            if (mStartReconnected) {
                if (accessoryType == 16) {
                    duration = DURATION_TO_ADVANCED_ACCESSORY_DEVICE_RECONNECTED_TIME_OUT;
                } else if (accessoryType == 114) {
                    duration = DURATION_TO_ADVANCED_PHONE_RECONNECTED_TIME_OUT;
                } else {
                    duration = DURATION_TO_ADVANCED_PLUS_ACCESSORY_DEVICE_RECONNECTED_TIME_OUT;
                }
            } else if (mStopReverseAtAcUnplug) {
                duration = DURATION_TO_REVERSE_AC_TIME_OUT;
            } else if (mUseRxRemovalTimeOut) {
                duration = DURATION_TO_REVERSE_RX_REMOVAL_TIME_OUT;
            } else {
                duration = DURATION_TO_REVERSE_TIME_OUT;
            }

            String prop = mStopReverseAtAcUnplug ? "rtx.ac.timeout" : "rtx.timeout";
            String propVal = SystemProperties.get(prop);
            if (!TextUtils.isEmpty(propVal)) {
                try {
                    duration = Long.parseLong(propVal);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "getRtxTimeOut(): invalid timeout, " + e);
                }
            }
            if (DEBUG) {
                Log.d(
                        TAG,
                        "onReverseStateChangedOnBackgroundThread(): time out, setRtxTimer,"
                                + " duration="
                                + duration);
            }
            int timerType =
                    mStartReconnected
                            ? 3
                            : (mUseRxRemovalTimeOut && !mStopReverseAtAcUnplug ? 4 : 0);
            setRtxTimer(timerType, duration);
        } else if (mReverseChargingEnabled && mRtxLevel >= 100) {
            if (DEBUG) {
                Log.d(
                        TAG,
                        "onReverseStateChangedOnBackgroundThread(): rtx="
                                + (mReverseChargingEnabled ? 1 : 0)
                                + ", Rx fully charged, setRtxTimer, REVERSE_FINISH_RX_FULL");
            }
            setRtxTimer(1, 0);
        }
    }

    private void playSoundIfNecessary(boolean rtxConnection, int accessoryType) {
        String soundPath = null;
        if (!mProvidingBattery && rtxConnection) {
            if (DEBUG) {
                Log.d(
                        TAG,
                        "playSoundIfNecessary() play start charging sound: "
                                + rtxConnection
                                + ", accType : "
                                + accessoryType
                                + ", mStartReconnected : "
                                + mStartReconnected);
            }
            if (mStartReconnected
                    && (accessoryType == 16 || accessoryType == 90 || accessoryType == 114)) {
                soundPath = null;
            } else {
                soundPath = mContext.getString(R.string.reverse_charging_started_sound);
            }
            mStartReconnected = false;
        } else if (mProvidingBattery && !rtxConnection) {
            if (DEBUG) {
                Log.d(
                        TAG,
                        "playSoundIfNecessary() play end charging sound: "
                                + rtxConnection
                                + ", accType : "
                                + accessoryType
                                + ", mStartReConnected : "
                                + mStartReconnected);
            }
            if (!mStartReconnected
                    && (accessoryType == 16 || accessoryType == 90 || accessoryType == 114)) {
                mStartReconnected = true;
                if (DEBUG) {
                    Log.w(TAG, "playSoundIfNecessary() start reconnected");
                }
            }
        }
        if (!TextUtils.isEmpty(soundPath)) {
            Uri soundUri = new Uri.Builder().scheme("file").appendPath(soundPath).build();
            playSound(RingtoneManager.getRingtone(mContext, soundUri));
        }
    }

    private void playSound(Ringtone ringtone) {
        if (ringtone != null) {
            ringtone.setStreamType(AudioManager.STREAM_SYSTEM);
            ringtone.play();
        }
    }

    private void enableNfcPollingMode(boolean enable) {
        if (DEBUG) {
            Log.d(TAG, "Change NFC reader mode to flags: " + (enable ? 0 : 4096));
        }
        mBgExecutor.execute(
                () -> {
                    try {
                        NfcAdapter.getDefaultAdapter(mContext).setReaderModePollingEnabled(enable);
                    } catch (Exception e) {
                        Log.e(TAG, "Could not change NFC reader mode, exception: " + e);
                    }
                });
    }

    private void checkAndChangeNfcPollingAgainstUsbAudioDevice(boolean enable, UsbDevice device) {
        for (int i = 0; i < mNfcUsbVendorIds.length; i++) {
            if (device.getVendorId() == mNfcUsbVendorIds[i]
                    && device.getProductId() == mNfcUsbProductIds[i]) {
                mRestoreUsbNfcPollingMode = !enable;
                enableNfcPollingMode(!mRestoreWlcNfcPollingMode && enable);
                return;
            }
        }
    }

    private void onAlarmRtxFinish(int reason) {
        Log.i(TAG, "onAlarmRtxFinish(): rtx=0, reason: " + reason);
        setReverseStateInternal(reason, false);
    }

    private void setRtxTimer(int timerType, long duration) {
        AlarmManager.OnAlarmListener listener;
        switch (timerType) {
            case 0:
                listener = mRtxFinishAlarmAction;
                break;
            case 1:
                listener = mRtxFinishRxFullAlarmAction;
                break;
            case 2:
                listener = mCheckNfcConflictWithUsbAudioAlarmAction;
                break;
            case 3:
                listener = mReconnectedTimeoutAlarmAction;
                break;
            case 4:
                listener = mAccessoryDeviceRemovedTimeoutAlarmAction;
                break;
            default:
                return;
        }
        mAlarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + duration,
                TAG,
                listener,
                null);
    }

    private void cancelRtxTimer(int timerType) {
        switch (timerType) {
            case 0:
                mAlarmManager.cancel(mRtxFinishAlarmAction);
                break;
            case 1:
                mAlarmManager.cancel(mRtxFinishRxFullAlarmAction);
                break;
            case 3:
                mAlarmManager.cancel(mReconnectedTimeoutAlarmAction);
                break;
            case 4:
                mAlarmManager.cancel(mAccessoryDeviceRemovedTimeoutAlarmAction);
                break;
            default:
                break;
        }
    }

    private void fireReverseChanged() {
        synchronized (mChangeCallbacks) {
            mMainExecutor.execute(
                    () -> {
                        ArrayList<ReverseChargingChangeCallback> callbacks =
                                new ArrayList<>(mChangeCallbacks);
                        for (ReverseChargingChangeCallback callback : callbacks) {
                            callback.onReverseChargingChanged(
                                    mReverseChargingEnabled, mRtxLevel, mName);
                        }
                    });
        }
    }

    private void logReverseStartEvent(int reason) {
        if (DEBUG) {
            Log.d(TAG, "logReverseStartEvent: " + reason);
        }
        mReverseStartTime = SystemClock.uptimeMillis();
        ReverseChargingMetrics.logStartEvent(reason, mLevel);
    }

    private void logReverseStopEvent(int reason) {
        if (DEBUG) {
            Log.d(TAG, "logReverseStopEvent: " + reason);
        }
        long durationSec = (SystemClock.uptimeMillis() - mReverseStartTime) / 1000;
        ReverseChargingMetrics.logStopEvent(reason, mLevel, durationSec);
    }

    @Override
    public void addCallback(ReverseChargingChangeCallback callback) {
        synchronized (mChangeCallbacks) {
            mChangeCallbacks.add(callback);
        }
        callback.onReverseChargingChanged(mReverseChargingEnabled, mRtxLevel, mName);
    }

    @Override
    public void removeCallback(ReverseChargingChangeCallback callback) {
        synchronized (mChangeCallbacks) {
            mChangeCallbacks.remove(callback);
        }
    }
}
