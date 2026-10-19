package com.google.android.systemui.reversecharging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.text.TextUtils;
import android.util.Log;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.settingslib.Utils;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;

import com.google.android.systemui.keyguard.domain.interactor.AmbientIndicationInteractor;
import com.google.android.systemui.res.R;
import com.google.android.systemui.statusbar.KeyguardIndicationControllerGoogle;

import java.util.concurrent.Executor;

import javax.inject.Inject;

@SysUISingleton
public class ReverseChargingViewController extends BroadcastReceiver
        implements LifecycleOwner, BatteryController.BatteryStateChangeCallback {

    public static final boolean DEBUG = Log.isLoggable("ReverseChargingViewCtrl", 3);
    private static final String TAG = "ReverseChargingViewCtrl";

    private final Context mContext;
    private final BatteryControllerImpl mBatteryController;
    private final StatusBarIconController mStatusBarIconController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Executor mMainExecutor;
    private final KeyguardIndicationControllerGoogle mKeyguardIndicationController;
    private final AmbientIndicationInteractor mAmbientIndicationInteractor;
    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

    private String mReverseCharging;
    private String mSlotReverseCharging;
    private String mContentDescription;

    private boolean mReverse = false;
    private int mLevel = 0;
    private String mName = null;
    private boolean mProvidingBattery = false;

    @Inject
    public ReverseChargingViewController(
            Context context,
            BatteryController batteryController,
            StatusBarIconController statusBarIconController,
            BroadcastDispatcher broadcastDispatcher,
            @Main Executor executor,
            KeyguardIndicationControllerGoogle keyguardIndicationControllerGoogle,
            AmbientIndicationInteractor ambientIndicationInteractor) {
        mContext = context;
        mBatteryController = (BatteryControllerImpl) batteryController;
        mStatusBarIconController = statusBarIconController;
        mBroadcastDispatcher = broadcastDispatcher;
        mMainExecutor = executor;
        mKeyguardIndicationController = keyguardIndicationControllerGoogle;
        mAmbientIndicationInteractor = ambientIndicationInteractor;

        loadStrings();
    }

    public void init() {
        mBatteryController.observe(mLifecycle, this);
        mLifecycle.setCurrentState(Lifecycle.State.RESUMED);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        mBroadcastDispatcher.registerReceiver(this, filter);
    }

    private void loadStrings() {
        mReverseCharging = mContext.getString(R.string.charging_reverse_text);
        mSlotReverseCharging = mContext.getString(R.string.status_bar_google_reverse_charging);
        mContentDescription = mContext.getString(R.string.reverse_charging_on_notification_title);
    }

    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mReverse = mBatteryController.isReverseOn();
        if (DEBUG) {
            Log.d(
                    TAG,
                    "onBatteryLevelChanged(): rtx="
                            + (mReverse ? 1 : 0)
                            + " level="
                            + mLevel
                            + " name="
                            + mName
                            + " this="
                            + this);
        }
        mMainExecutor.execute(this::updateMessage);
    }

    @Override
    public void onReverseChanged(boolean isReverse, int level, String name) {
        mReverse = isReverse;
        mLevel = level;
        mName = name;
        mProvidingBattery = isReverse && level >= 0;
        if (DEBUG) {
            Log.d(
                    TAG,
                    "onReverseChanged(): rtx="
                            + (isReverse ? 1 : 0)
                            + " level="
                            + level
                            + " name="
                            + name
                            + " this="
                            + this);
        }
        mMainExecutor.execute(this::updateMessage);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
            loadStrings();
            if (DEBUG) {
                Log.d(TAG, "onReceive(): ACTION_LOCALE_CHANGED this=" + this);
            }
            mMainExecutor.execute(this::updateMessage);
        }
    }

    private void updateMessage() {
        if (!mReverse && mBatteryController.isWirelessCharging() && !TextUtils.isEmpty(mName)) {
            String wlcString =
                    mContext.getString(
                            R.string.reverse_charging_device_providing_charge_text,
                            mName,
                            Utils.formatPercentage(mLevel));
            if (DEBUG) {
                Log.d(
                        TAG,
                        "updateMessage(): rtx=" + (mReverse ? 1 : 0) + " wlcString=" + wlcString);
            }
            mAmbientIndicationInteractor
                    .getAmbientIndicationRepository()
                    .getWirelessChargingMessage()
                    .setValue(wlcString);
            mKeyguardIndicationController.setReverseChargingMessage(wlcString);
        } else {
            if (!mBatteryController.isWirelessCharging() && !mProvidingBattery) {
                mAmbientIndicationInteractor
                        .getAmbientIndicationRepository()
                        .getWirelessChargingMessage()
                        .setValue("");
                if (DEBUG) {
                    Log.d(TAG, "updateMessage(): rtx=" + (mReverse ? 1 : 0) + " reset wlcString");
                }
            }
            String rtxString = mProvidingBattery ? mReverseCharging : "";
            mAmbientIndicationInteractor
                    .getAmbientIndicationRepository()
                    .getReverseChargingMessage()
                    .setValue(rtxString);
            mKeyguardIndicationController.setReverseChargingMessage(rtxString);
            if (DEBUG) {
                Log.d(
                        TAG,
                        "updateMessage(): rtx=" + (mReverse ? 1 : 0) + " rtxString=" + rtxString);
            }
        }

        mStatusBarIconController.setIcon(
                mSlotReverseCharging, R.drawable.ic_qs_reverse_charging, mContentDescription);
        mStatusBarIconController.setIconVisibility(mSlotReverseCharging, mProvidingBattery);
    }
}
