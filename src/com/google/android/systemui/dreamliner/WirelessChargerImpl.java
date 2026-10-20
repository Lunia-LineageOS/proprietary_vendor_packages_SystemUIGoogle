package com.google.android.systemui.dreamliner;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Log;

import vendor.google.wireless_charger.AlignInfo;
import vendor.google.wireless_charger.DockPresent;
import vendor.google.wireless_charger.FanInfo;
import vendor.google.wireless_charger.IWirelessCharger;
import vendor.google.wireless_charger.IWirelessChargerFanLevelChangedCallback;
import vendor.google.wireless_charger.IWirelessChargerInfoCallback;
import vendor.google.wireless_charger.KeyExchangeResponse;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class WirelessChargerImpl extends WirelessCharger implements IBinder.DeathRecipient {
    private static final String TAG = "Dreamliner-WLC_HAL";

    WirelessCharger.IsDockPresentCallback mCallback;
    Context mContext;
    CopyOnWriteArraySet<Consumer<Integer>> mFanLevelEventListeners;
    Handler mHandler;
    IWirelessChargerFanLevelChangedCallback mIWirelessChargerFanLevelChangedCallback;
    AtomicBoolean mIsFanLevelCallbackRegistered;
    long mPollingStartedTimeNs;
    Runnable mRunnable;
    IWirelessCharger mWirelessCharger;

    final class FanLevelChangedCallback extends IWirelessChargerFanLevelChangedCallback.Stub {
        private int mPreviousFanLevel = -1;

        @Override
        public void fanLevelChanged(int fanLevel) {
            Log.i(TAG, "fan level changed: " + mPreviousFanLevel + " > " + fanLevel);
            mPreviousFanLevel = fanLevel;
            for (Consumer<Integer> listener : mFanLevelEventListeners) {
                listener.accept(fanLevel);
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.printf("fan level callback in [%d]%s\n", Process.myPid(), mContext.getPackageName());
        }

        @Override
        public int getInterfaceVersion() {
            return VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return HASH;
        }
    }

    final class WirelessChargerInfoCallback extends IWirelessChargerInfoCallback.Stub {
        private final WirelessCharger.AlignInfoListener mListener;

        WirelessChargerInfoCallback(WirelessCharger.AlignInfoListener listener) {
            mListener = listener;
        }

        @Override
        public void alignInfoChanged(AlignInfo alignInfo) {
            mListener.onAlignInfoChanged(alignInfo);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.printf("alignment callback in [%d]%s\n", Process.myPid(), mContext.getPackageName());
        }

        @Override
        public int getInterfaceVersion() {
            return VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return HASH;
        }
    }

    public WirelessChargerImpl(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mRunnable =
                () -> {
                    if (!initHALInterface()) {
                        return;
                    }
                    try {
                        DockPresent dockPresent = mWirelessCharger.isDockPresent();
                        if (System.nanoTime() < mPollingStartedTimeNs + 5000000000L
                                && dockPresent.id == 0) {
                            mHandler.postDelayed(mRunnable, 100L);
                            return;
                        }
                        if (mCallback == null) {
                            return;
                        }
                        mCallback.onCallback(
                                dockPresent.docked,
                                dockPresent.type,
                                dockPresent.orientation,
                                dockPresent.isGetInfoSupported,
                                dockPresent.id,
                                dockPresent.ptmc);
                        mCallback = null;
                    } catch (Exception e) {
                        Log.i(TAG, "isDockPresent fail: " + e.getMessage());
                    }
                };
        mFanLevelEventListeners = new CopyOnWriteArraySet<>();
        mIsFanLevelCallbackRegistered = new AtomicBoolean(false);
        mIWirelessChargerFanLevelChangedCallback = new FanLevelChangedCallback();
    }

    public static Bundle convertFanDetailedInfo(byte b, FanInfo fanInfo) {
        Bundle bundle = new Bundle();
        bundle.putByte("fan_id", b);
        bundle.putByte("fan_mode", fanInfo.fanMode);
        bundle.putInt("fan_current_rpm", fanInfo.currentRpm);
        bundle.putInt("fan_min_rpm", fanInfo.minimumRpm);
        bundle.putInt("fan_max_rpm", fanInfo.maximumRpm);
        bundle.putByte("fan_type", fanInfo.type);
        bundle.putByte("fan_count", fanInfo.count);
        return bundle;
    }

    public static ArrayList<Byte> convertPrimitiveArrayToArrayList(byte[] bArr) {
        if (bArr == null || bArr.length <= 0) {
            return null;
        }
        ArrayList<Byte> arrayList = new ArrayList<>();
        for (byte b : bArr) {
            arrayList.add(b);
        }
        return arrayList;
    }

    public static int mapError(Exception exc) {
        if (!(exc instanceof ServiceSpecificException)) {
            return 2;
        }
        int i = ((ServiceSpecificException) exc).errorCode;
        if (i >= 0) {
            return i;
        }
        Log.w(TAG, "Got a ServiceSpecificExepction but failed to map erroCode: " + i);
        return 2;
    }

    @Override
    public void asyncIsDockPresent(WirelessCharger.IsDockPresentCallback callback) {
        if (initHALInterface()) {
            mPollingStartedTimeNs = System.nanoTime();
            mCallback = callback;
            mHandler.removeCallbacks(mRunnable);
            mHandler.postDelayed(mRunnable, 100L);
        }
    }

    @Override
    public void binderDied() {
        Log.i(TAG, "serviceDied");
        mWirelessCharger = null;
    }

    @Override
    public void challenge(byte b, byte[] bArr, WirelessCharger.ChallengeCallback callback) {
        if (initHALInterface()) {
            ArrayList<Byte> arrayList = null;
            int error = 0;
            try {
                arrayList = convertPrimitiveArrayToArrayList(mWirelessCharger.challenge(b, bArr));
            } catch (Exception e) {
                error = mapError(e);
                Log.i(TAG, "challenge fail: " + e.getMessage());
            }
            callback.onCallback(error, arrayList);
        }
    }

    @Override
    public void getFanInformation(byte b, WirelessCharger.GetFanInformationCallback callback) {
        if (initHALInterface()) {
            Log.d(TAG, "command=0");
            Bundle bundle = null;
            int error = 0;
            try {
                FanInfo fanInfo = mWirelessCharger.getFanInfo(b);
                Log.d(
                        TAG,
                        "command=0, i="
                                + ((int) b)
                                + ", m="
                                + ((int) fanInfo.fanMode)
                                + ", cr="
                                + fanInfo.currentRpm
                                + ", mir="
                                + fanInfo.minimumRpm
                                + ", mxr="
                                + fanInfo.maximumRpm
                                + ", t="
                                + ((int) fanInfo.type)
                                + ", c="
                                + ((int) fanInfo.count));
                bundle = convertFanDetailedInfo(b, fanInfo);
            } catch (Exception e) {
                error = mapError(e);
                Log.i(TAG, "command=0 fail: " + e.getMessage());
            }
            callback.onCallback(error, bundle);
        }
    }

    @Override
    public void getFanSimpleInformation(
            byte b, WirelessCharger.GetFanSimpleInformationCallback callback) {
        if (initHALInterface()) {
            Log.d(TAG, "command=3");
            try {
                FanInfo fanInfo = mWirelessCharger.getFanInfo(b);
                Bundle bundle = new Bundle();
                bundle.putByte("fan_id", b);
                bundle.putByte("fan_mode", fanInfo.fanMode);
                bundle.putInt("fan_current_rpm", fanInfo.currentRpm);
            } catch (Exception e) {
                mapError(e);
                Log.i(TAG, "command=3 fail: " + e.getMessage());
            }
            callback.onCallback();
        }
    }

    @Override
    public void getInformation(WirelessCharger.GetInformationCallback callback) {
        if (initHALInterface()) {
            DockInfo dockInfo = null;
            int error = 0;
            try {
                vendor.google.wireless_charger.DockInfo information =
                        mWirelessCharger.getInformation();
                dockInfo = new DockInfo();
                dockInfo.manufacturer = information.manufacturer;
                dockInfo.model = information.model;
                dockInfo.serialNumber = information.serial;
                dockInfo.accessoryType = Byte.valueOf(information.type).intValue();
            } catch (Exception e) {
                error = mapError(e);
                Log.i(TAG, "getInformation fail: " + e.getMessage());
            }
            callback.onCallback(error, dockInfo);
        }
    }

    public boolean initHALInterface() {
        if (mWirelessCharger != null) {
            return true;
        }
        try {
            IBinder service =
                    ServiceManager.getService(
                            "vendor.google.wireless_charger.IWirelessCharger/default");
            if (service != null) {
                mWirelessCharger = IWirelessCharger.Stub.asInterface(service);
                service.linkToDeath(this, 0);
                Log.i(TAG, "mWirelessCharger service connected!!!!");
            }
        } catch (Exception e) {
            Log.i(TAG, "WirelessCharger HAL not found: " + e.getMessage());
            mWirelessCharger = null;
        }
        return mWirelessCharger != null;
    }

    @Override
    public void keyExchange(byte[] bArr, WirelessCharger.KeyExchangeCallback callback) {
        if (initHALInterface()) {
            ArrayList<Byte> arrayList = null;
            int error = 0;
            byte dockId = -1;
            try {
                KeyExchangeResponse keyExchangeResponse = mWirelessCharger.keyExchange(bArr);
                arrayList = convertPrimitiveArrayToArrayList(keyExchangeResponse.dockPublicKey);
                dockId = keyExchangeResponse.dockIdentifier;
            } catch (Exception e) {
                error = mapError(e);
                Log.i(TAG, "keyExchange fail: " + e.getMessage());
            }
            callback.onCallback(error, dockId, arrayList);
        }
    }

    @Override
    public void registerAlignInfo(WirelessCharger.AlignInfoListener listener) {
        if (initHALInterface()) {
            try {
                mWirelessCharger.registerCallback(new WirelessChargerInfoCallback(listener));
            } catch (Exception e) {
                Log.i(TAG, "register alignInfo callback fail: " + e.getMessage());
            }
        }
    }

    @Override
    public void setFan(byte b, byte b2, int i, WirelessCharger.SetFanCallback callback) {
        if (initHALInterface()) {
            Log.d(TAG, "command=1, i=" + b + ", m=" + b2 + ", r=" + i);
            Bundle bundle = null;
            int error = 0;
            try {
                long currentTimeMillis = System.currentTimeMillis();
                FanInfo fan = mWirelessCharger.setFan(b, b2, (char) i);
                bundle = new Bundle();
                bundle.putByte("fan_id", b);
                bundle.putByte("fan_mode", fan.fanMode);
                bundle.putInt("fan_current_rpm", fan.currentRpm);
                Log.d(
                        TAG,
                        "command=1 spending time: "
                                + (System.currentTimeMillis() - currentTimeMillis));
            } catch (Exception e) {
                error = mapError(e);
                Log.i(TAG, "command=1 fail: " + e.getMessage());
            }
            callback.onCallback(error, bundle);
        }
    }
}
