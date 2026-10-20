package com.google.android.systemui.dreamliner;

import android.util.Log;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dock.DockManager;

import vendor.google.wireless_charger.AlignInfo;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.inject.Inject;

@SysUISingleton
public final class DockAlignmentController {
    private static final String TAG = "DockAlignmentController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public interface DockAlignmentStateChangeListener {
        void onAlignmentStateChanged(int alignState);
    }

    private int mAlignmentState = DockManager.ALIGN_STATE_GOOD;
    private final CopyOnWriteArrayList<DockAlignmentStateChangeListener>
            mDockAlignmentStateChangeListeners = new CopyOnWriteArrayList<>();
    private final Optional<WirelessCharger> mWirelessCharger;

    @Inject
    public DockAlignmentController(Optional<WirelessCharger> wirelessCharger) {
        mWirelessCharger = wirelessCharger;
    }

    public void registerAlignInfoListener() {
        if (mWirelessCharger.isPresent()) {
            mWirelessCharger.get().registerAlignInfo(this::onAlignInfoChanged);
        } else {
            Log.w(TAG, "wirelessCharger is null");
        }
    }

    public void addListener(DockAlignmentStateChangeListener listener) {
        mDockAlignmentStateChangeListeners.add(listener);
    }

    private void onAlignInfoChanged(AlignInfo alignInfo) {
        int alignState = Byte.valueOf(alignInfo.alignState).intValue();
        int alignPct = Byte.valueOf(alignInfo.alignPct).intValue();
        int previousState = mAlignmentState;
        if (DEBUG) {
            Log.d(TAG, "onAlignInfo, state: " + alignState + ", alignPct: " + alignPct);
        }
        mAlignmentState = getAlignmentState(alignState, alignPct, previousState);
        if (previousState != mAlignmentState) {
            for (DockAlignmentStateChangeListener listener : mDockAlignmentStateChangeListeners) {
                listener.onAlignmentStateChanged(mAlignmentState);
            }
            if (DEBUG) {
                Log.d(TAG, "onAlignStateChanged, state: " + mAlignmentState);
            }
        }
    }

    private static int getAlignmentState(int alignState, int alignPct, int previousState) {
        switch (alignState) {
            case 0:
                return previousState;
            case 1:
                return DockManager.ALIGN_STATE_TERRIBLE;
            case 2:
                if (alignPct < 0) {
                    return DockManager.ALIGN_STATE_UNKNOWN;
                }
                return alignPct < 100 ? DockManager.ALIGN_STATE_POOR : DockManager.ALIGN_STATE_GOOD;
            case 3:
                return DockManager.ALIGN_STATE_UNKNOWN;
            default:
                Log.w(TAG, "Unexpected state: " + alignState);
                return DockManager.ALIGN_STATE_UNKNOWN;
        }
    }
}
