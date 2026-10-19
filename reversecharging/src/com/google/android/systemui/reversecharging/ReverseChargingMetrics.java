package com.google.android.systemui.reversecharging;

import android.frameworks.stats.IStats;
import android.frameworks.stats.VendorAtom;
import android.frameworks.stats.VendorAtomValue;
import android.os.ServiceManager;
import android.util.Log;

import java.util.Optional;

public abstract class ReverseChargingMetrics {

    private static final String TAG = "ReverseChargingMetrics";
    protected static final boolean DEBUG = Log.isLoggable(TAG, 3);

    private static VendorAtom createVendorAtom(int capacity) {
        VendorAtom vendorAtom = new VendorAtom();
        vendorAtom.reverseDomainName = "";
        vendorAtom.values = new VendorAtomValue[capacity];
        return vendorAtom;
    }

    private static Optional<IStats> tryConnectingToStatsService() {
        String serviceName = IStats.DESCRIPTOR + "/default";
        if (!ServiceManager.isDeclared(serviceName)) {
            Log.e(TAG, "IStats is not registered");
            return Optional.empty();
        }
        return Optional.ofNullable(
                IStats.Stub.asInterface(ServiceManager.waitForDeclaredService(serviceName)));
    }

    private static void reportVendorAtom(VendorAtom vendorAtom) {
        try {
            Optional<IStats> statsService = tryConnectingToStatsService();
            if (statsService.isPresent()) {
                statsService.get().reportVendorAtom(vendorAtom);
                if (DEBUG) {
                    Log.i(TAG, "Report vendor atom OK, " + vendorAtom);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to log atom to IStats service, " + e);
        }
    }

    public static void logStartEvent(int reason, int level) {
        VendorAtom vendorAtom = createVendorAtom(2);
        vendorAtom.atomId = 100037;
        vendorAtom.values[0] = VendorAtomValue.intValue(reason);
        vendorAtom.values[1] = VendorAtomValue.intValue(level);
        reportVendorAtom(vendorAtom);
    }

    public static void logStopEvent(int reason, int level, long duration) {
        VendorAtom vendorAtom = createVendorAtom(3);
        vendorAtom.atomId = 100038;
        vendorAtom.values[0] = VendorAtomValue.intValue(reason);
        vendorAtom.values[1] = VendorAtomValue.intValue(level);
        vendorAtom.values[2] = VendorAtomValue.longValue(duration);
        reportVendorAtom(vendorAtom);
    }

    public static void logLowBatteryThresholdChange(int threshold) {
        VendorAtom vendorAtom = createVendorAtom(2);
        vendorAtom.atomId = 100039;
        vendorAtom.values[0] = VendorAtomValue.intValue(1);
        vendorAtom.values[1] = VendorAtomValue.intValue(threshold);
        reportVendorAtom(vendorAtom);
    }

    public static void logAccessoryType(int accessoryType) {
        VendorAtom vendorAtom = createVendorAtom(1);
        vendorAtom.atomId = 100040;
        vendorAtom.values[0] =
                VendorAtomValue.intValue(accessoryType == 16 || accessoryType == 114 ? 1 : 0);
        reportVendorAtom(vendorAtom);
    }
}
