package com.google.android.systemui.qs.tiles;

import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Temperature;
import android.provider.Settings;
import android.util.Log;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Prefs;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;

import com.google.android.systemui.res.R;

import javax.inject.Inject;

public class ReverseChargingTile extends QSTileImpl<QSTile.BooleanState>
        implements BatteryStateChangeCallback {

    public static final String TILE_SPEC = "reverse";
    private static final boolean DEBUG = Log.isLoggable("ReverseChargingTile", 3);
    private static final String TAG = "ReverseChargingTile";

    private final BatteryController mBatteryController;
    private final IThermalService mThermalService;

    private int mBatteryLevel;
    private boolean mListening;
    private boolean mOverHeat;
    private boolean mPowerSave;
    private boolean mReverse;
    private boolean mRtxDisabled;
    private int mThresholdLevel;

    private final IThermalEventListener.Stub mThermalEventListener =
            new IThermalEventListener.Stub() {
                @Override
                public void notifyThrottling(Temperature temperature) {
                    int status = temperature.getStatus();
                    mOverHeat = status >= 5;
                    if (DEBUG) {
                        Log.d(TAG, "notifyThrottling(): status=" + status);
                    }
                }
            };

    private final ContentObserver mSettingsObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    updateThresholdLevel();
                }
            };

    @Inject
    public ReverseChargingTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BatteryController batteryController,
            IThermalService thermalService) {
        super(
                host,
                uiEventLogger,
                backgroundLooper,
                mainHandler,
                falsingManager,
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger);
        mBatteryController = batteryController;
        mBatteryController.observe(getLifecycle(), this);
        mThermalService = thermalService;
    }

    @Override
    public Intent getLongClickIntent() {
        Intent intent = new Intent("android.settings.REVERSE_CHARGING_SETTINGS");
        intent.setPackage("com.android.settings");
        return intent;
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.reverse_charging_title);
    }

    @Override
    protected void handleClick(Expandable expandable) {
        if (mState.state == 0) {
            return;
        }
        mReverse = !mReverse;
        if (DEBUG) {
            Log.d(TAG, "handleClick(): rtx=" + (mReverse ? 1 : 0) + ",this=" + this);
        }
        mBatteryController.setReverseState(mReverse);
        if (Prefs.get(mHost.getUserContext()).getBoolean("HasSeenReverseBottomSheet", false)) {
            return;
        }
        Intent intent = new Intent("android.settings.REVERSE_CHARGING_BOTTOM_SHEET");
        intent.setPackage("com.android.settings");
        mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
        Prefs.putBoolean(mHost.getUserContext(), "HasSeenReverseBottomSheet", true);
    }

    @Override
    protected void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) {
            return;
        }
        mListening = listening;
        if (listening) {
            updateThresholdLevel();
            boolean isOverHeat = false;
            mContext.getContentResolver()
                    .registerContentObserver(
                            Settings.Global.getUriFor("advanced_battery_usage_amount"),
                            false,
                            mSettingsObserver);
            try {
                mThermalService.registerThermalEventListenerWithType(mThermalEventListener, 3);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not register thermal event listener, exception: " + e);
            }
            try {
                for (Temperature temperature : mThermalService.getCurrentTemperaturesWithType(3)) {
                    if (temperature.getStatus() >= 5) {
                        Log.w(
                                TAG,
                                "isOverHeat(): current skin status = "
                                        + temperature.getStatus()
                                        + ", temperature = "
                                        + temperature.getValue());
                        isOverHeat = true;
                        break;
                    }
                }
            } catch (RemoteException e) {
                Log.w(TAG, "isOverHeat(): " + e);
            }
            mOverHeat = isOverHeat;
        } else {
            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
            try {
                mThermalService.unregisterThermalEventListener(mThermalEventListener);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not unregister thermal event listener, exception: " + e);
            }
        }
        if (DEBUG) {
            Log.d(
                    TAG,
                    "handleSetListening(): rtx="
                            + (mReverse ? 1 : 0)
                            + ",level="
                            + mBatteryLevel
                            + ",threshold="
                            + mThresholdLevel
                            + ",listening="
                            + listening);
        }
    }

    @Override
    protected void handleUpdateState(QSTile.BooleanState state, Object arg) {
        boolean isWirelessCharging = mBatteryController.isWirelessCharging();
        boolean isLowBattery = mBatteryLevel <= mThresholdLevel;
        boolean isDisabled =
                mRtxDisabled || mOverHeat || mPowerSave || isWirelessCharging || isLowBattery;
        boolean isReverseOn = !isDisabled && mReverse;
        state.value = isReverseOn;
        state.state = isDisabled ? 0 : (mReverse ? 2 : 1);
        state.icon =
                maybeLoadResourceIcon(
                        isReverseOn
                                ? R.drawable.qs_battery_share_icon_on
                                : R.drawable.qs_battery_share_icon_off);
        CharSequence tileLabel = getTileLabel();
        state.label = tileLabel;
        state.contentDescription = tileLabel;
        state.expandedAccessibilityClassName = Switch.class.getName();
        String secondaryLabel;
        if (mOverHeat) {
            secondaryLabel = mContext.getString(R.string.too_hot_label);
        } else if (mPowerSave) {
            secondaryLabel =
                    mContext.getString(
                            com.android.systemui.res.R.string
                                    .quick_settings_dark_mode_secondary_label_battery_saver);
        } else if (isWirelessCharging) {
            secondaryLabel = mContext.getString(R.string.wireless_charging_label);
        } else if (isLowBattery) {
            secondaryLabel = mContext.getString(R.string.low_battery_label);
        } else {
            secondaryLabel = null;
        }
        state.secondaryLabel = secondaryLabel;
        if (DEBUG) {
            Log.d(
                    TAG,
                    "handleUpdateState(): disable="
                            + (mRtxDisabled ? 1 : 0)
                            + ",ps="
                            + (mPowerSave ? 1 : 0)
                            + ",wlc="
                            + (isWirelessCharging ? 1 : 0)
                            + ",low="
                            + (isLowBattery ? 1 : 0)
                            + ",over="
                            + (mOverHeat ? 1 : 0)
                            + ",rtx="
                            + (mReverse ? 1 : 0)
                            + ",this="
                            + this);
        }
    }

    @Override
    public boolean isAvailable() {
        return mBatteryController.isReverseSupported();
    }

    @Override
    public QSTile.BooleanState newTileState() {
        return new QSTile.BooleanState();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mBatteryLevel = level;
        mReverse = mBatteryController.isReverseOn();
        if (DEBUG) {
            Log.d(
                    TAG,
                    "onBatteryLevelChanged(): rtx="
                            + (mReverse ? 1 : 0)
                            + ",level="
                            + mBatteryLevel
                            + ",threshold="
                            + mThresholdLevel);
        }
        refreshState();
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mPowerSave = isPowerSave;
        refreshState();
    }

    @Override
    public void onReverseChanged(boolean isReverse, int level, String name) {
        if (DEBUG) {
            Log.d(
                    TAG,
                    "onReverseChanged(): rtx="
                            + (isReverse ? 1 : 0)
                            + ",level="
                            + level
                            + ",name="
                            + name
                            + ",this="
                            + this);
        }
        mReverse = isReverse;
        mRtxDisabled = !isReverse && level == -100;
        refreshState();
    }

    public void updateThresholdLevel() {
        mThresholdLevel =
                Settings.Global.getInt(
                                mContext.getContentResolver(), "advanced_battery_usage_amount", 2)
                        * 5;
        if (DEBUG) {
            Log.d(
                    TAG,
                    "updateThresholdLevel(): rtx="
                            + (mReverse ? 1 : 0)
                            + ",level="
                            + mBatteryLevel
                            + ",threshold="
                            + mThresholdLevel);
        }
    }
}
