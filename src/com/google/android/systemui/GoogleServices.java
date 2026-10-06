package com.google.android.systemui;

import android.content.Context;

import com.android.systemui.Dumpable;
import com.android.systemui.VendorServices;
import com.android.systemui.dagger.SysUISingleton;

import com.google.android.systemui.columbus.legacy.ColumbusServiceWrapper;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.ArrayList;

import javax.inject.Inject;

@SysUISingleton
public class GoogleServices extends VendorServices {
    private static final String FEATURE_QUICK_TAP = "com.google.android.feature.QUICK_TAP";

    private final Context mContext;
    private final Lazy<ColumbusServiceWrapper> mColumbusStarter;
    private final ArrayList<Object> mServices = new ArrayList<>();

    @Inject
    public GoogleServices(Context context, Lazy<ColumbusServiceWrapper> columbusStarter) {
        mContext = context;
        mColumbusStarter = columbusStarter;
    }

    @Override
    public void start() {
        // Stock also starts AutorotateDataService, ScreenProtectorNotifierService and
        // TouchContextService, which are not ported yet, and ElmyraService, which needs Active
        // Edge hardware that Pixel 7 lacks.
        if (mContext.getPackageManager().hasSystemFeature(FEATURE_QUICK_TAP)) {
            Object columbusServiceWrapper = mColumbusStarter.get();
            if (columbusServiceWrapper != null) {
                mServices.add(columbusServiceWrapper);
            }
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        for (int i = 0; i < mServices.size(); i++) {
            if (mServices.get(i) instanceof Dumpable) {
                ((Dumpable) mServices.get(i)).dump(pw, args);
            }
        }
    }
}
