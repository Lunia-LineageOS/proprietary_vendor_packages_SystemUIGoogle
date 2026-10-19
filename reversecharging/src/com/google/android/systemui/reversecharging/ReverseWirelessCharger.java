package com.google.android.systemui.reversecharging;

import android.content.Context;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Log;

import vendor.google.wireless_charger.IWirelessCharger;
import vendor.google.wireless_charger.IWirelessChargerRtxStatusCallback;
import vendor.google.wireless_charger.RtxStatusInfo;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class ReverseWirelessCharger extends IWirelessChargerRtxStatusCallback.Stub
        implements IBinder.DeathRecipient {

    private static final String TAG = "ReverseWirelessCharger";
    public static final boolean DEBUG = Log.isLoggable(TAG, 3);

    private final Context mContext;
    private final Object mLock = new Object();
    private final ArrayList<RtxInformationCallback> mRtxInformationCallbacks = new ArrayList<>();
    private final ArrayList<RtxStatusCallback> mRtxStatusCallbacks = new ArrayList<>();
    private IWirelessCharger mWirelessCharger;

    public interface RtxInformationCallback {
        void onRtxInformationChanged(RtxStatusInfo rtxStatusInfo);
    }

    public interface RtxStatusCallback {
        void onRtxStatusChanged(RtxStatusInfo rtxStatusInfo);
    }

    public ReverseWirelessCharger(Context context, String name) {
        mContext = context;
    }

    @Override
    public int getInterfaceVersion() {
        return VERSION;
    }

    @Override
    public String getInterfaceHash() {
        return HASH;
    }

    @Override
    public void binderDied() {
        Log.i(TAG, "serviceDied");
        mWirelessCharger = null;
    }

    @Override
    protected void dump(FileDescriptor fileDescriptor, PrintWriter printWriter, String[] strArr) {
        printWriter.printf("rtx callback in [%d]%s\n", Process.myPid(), mContext.getPackageName());
    }

    private boolean initHALInterface() {
        if (mWirelessCharger != null) {
            return true;
        }
        IBinder service =
                ServiceManager.getService(
                        "vendor.google.wireless_charger.IWirelessCharger/default");
        if (service != null) {
            mWirelessCharger = IWirelessCharger.Stub.asInterface(service);
            try {
                service.linkToDeath(this, 0);
                Log.i(TAG, "mWirelessCharger service connected!!!!");
            } catch (RemoteException e) {
                Log.w(TAG, "Can't link death recipient to HAL");
                mWirelessCharger = null;
            }
        }
        if (mWirelessCharger == null) {
            return false;
        }
        try {
            mWirelessCharger.registerRtxCallback(this);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == 5) {
                Log.d(TAG, "RtxCallback is already registered...");
            } else {
                Log.w(TAG, "RtxCallback registration error: " + e.errorCode);
            }
        } catch (Exception e) {
            Log.w(TAG, "registerRtxCallback fail: ", e);
        }
        return mWirelessCharger != null;
    }

    public boolean isRtxSupported() {
        if (!initHALInterface()) {
            return false;
        }
        try {
            return mWirelessCharger.isRtxSupported();
        } catch (Exception e) {
            Log.w(TAG, "isRtxSupported fail: ", e);
            return false;
        }
    }

    public void getRtxInformation() {
        if (initHALInterface()) {
            try {
                dispatchRtxInformationCallbacks(mWirelessCharger.getRtxInformation());
            } catch (Exception e) {
                Log.w(TAG, "getRtxInformation fail: ", e);
            }
        }
    }

    public void addRtxInformationCallback(RtxInformationCallback callback) {
        synchronized (mLock) {
            if (mRtxInformationCallbacks.contains(callback)) {
                return;
            }
            mRtxInformationCallbacks.add(callback);
        }
    }

    private void dispatchRtxInformationCallbacks(RtxStatusInfo rtxStatusInfo) {
        ArrayList<RtxInformationCallback> callbacks;
        synchronized (mLock) {
            callbacks = new ArrayList<>(mRtxInformationCallbacks);
        }
        for (RtxInformationCallback callback : callbacks) {
            callback.onRtxInformationChanged(rtxStatusInfo);
        }
    }

    public void setRtxMode(boolean enable) {
        if (initHALInterface()) {
            try {
                mWirelessCharger.setRtxMode(enable);
            } catch (Exception e) {
                Log.w(TAG, "setRtxMode fail: ", e);
            }
        }
    }

    public boolean isRtxModeOn() {
        if (!initHALInterface()) {
            return false;
        }
        try {
            return mWirelessCharger.isRtxModeOn();
        } catch (Exception e) {
            Log.w(TAG, "isRtxModeOn fail: ", e);
            return false;
        }
    }

    public void addRtxStatusCallback(RtxStatusCallback callback) {
        synchronized (mLock) {
            mRtxStatusCallbacks.add(callback);
        }
    }

    private void dispatchRtxStatusCallbacks(RtxStatusInfo rtxStatusInfo) {
        ArrayList<RtxStatusCallback> callbacks;
        synchronized (mLock) {
            callbacks = new ArrayList<>(mRtxStatusCallbacks);
        }
        for (RtxStatusCallback callback : callbacks) {
            callback.onRtxStatusChanged(rtxStatusInfo);
        }
    }

    @Override
    public void rtxStatusInfoChanged(RtxStatusInfo rtxStatusInfo) {
        dispatchRtxStatusCallbacks(rtxStatusInfo);
    }
}
