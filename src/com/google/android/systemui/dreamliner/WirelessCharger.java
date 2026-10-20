package com.google.android.systemui.dreamliner;

import android.os.Bundle;

import vendor.google.wireless_charger.AlignInfo;

import java.util.ArrayList;

public abstract class WirelessCharger {

    public interface AlignInfoListener {
        void onAlignInfoChanged(AlignInfo alignInfo);
    }

    public interface ChallengeCallback {
        void onCallback(int result, ArrayList<Byte> response);
    }

    public interface KeyExchangeCallback {
        void onCallback(int result, byte dockId, ArrayList<Byte> dockPublicKey);
    }

    public interface GetInformationCallback {
        void onCallback(int result, DockInfo dockInfo);
    }

    public interface GetFanInformationCallback {
        void onCallback(int result, Bundle fanInfo);
    }

    public interface GetFanSimpleInformationCallback {
        void onCallback();
    }

    public interface SetFanCallback {
        void onCallback(int result, Bundle fanInfo);
    }

    public interface IsDockPresentCallback {
        void onCallback(
                boolean docked,
                byte type,
                byte orientation,
                boolean isGetInfoSupported,
                int id,
                int manufacturerCode);
    }

    public abstract void asyncIsDockPresent(IsDockPresentCallback callback);

    public abstract void challenge(byte dockId, byte[] challengeData, ChallengeCallback callback);

    public abstract void getFanInformation(byte fanId, GetFanInformationCallback callback);

    public abstract void getFanSimpleInformation(
            byte fanId, GetFanSimpleInformationCallback callback);

    public abstract void getInformation(GetInformationCallback callback);

    public abstract void keyExchange(byte[] publicKey, KeyExchangeCallback callback);

    public abstract void registerAlignInfo(AlignInfoListener listener);

    public abstract void setFan(byte fanId, byte fanMode, int fanRpm, SetFanCallback callback);
}
