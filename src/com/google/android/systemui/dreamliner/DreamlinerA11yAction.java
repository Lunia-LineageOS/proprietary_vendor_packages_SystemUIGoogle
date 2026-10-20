package com.google.android.systemui.dreamliner;

import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.core.view.ViewCompat;

import java.util.Objects;

public final class DreamlinerA11yAction {
    private static final String TAG = "DreamlnierA11yAction";
    private static final int NO_ACTION = -1;

    private final String mActionLabel;
    private final View mViewForAction;
    private final Runnable mCustomAction;
    private int mActionId = NO_ACTION;

    public DreamlinerA11yAction(String actionLabel, View viewForAction, Runnable customAction) {
        Objects.requireNonNull(TextUtils.isEmpty(actionLabel) ? null : actionLabel);
        mActionLabel = actionLabel;
        mViewForAction = Objects.requireNonNull(viewForAction);
        mCustomAction = customAction;
    }

    public void enable() {
        if (mActionId != NO_ACTION) {
            Log.d(TAG, "enable action: " + mActionLabel + " already enabled, skip enable");
            return;
        }
        Log.d(TAG, "enable action: " + mActionLabel);
        mActionId =
                ViewCompat.addAccessibilityAction(
                        mViewForAction,
                        mActionLabel,
                        (view, arguments) -> {
                            mCustomAction.run();
                            return true;
                        });
    }

    public void disable() {
        if (mActionId == NO_ACTION) {
            Log.d(
                    TAG,
                    "disable action: "
                            + mActionLabel
                            + " already disable, skip disable(actionId:"
                            + mActionId
                            + ", viewForAction==null:"
                            + (mViewForAction == null)
                            + ")");
            return;
        }
        Log.d(TAG, "disable action: " + mActionLabel);
        ViewCompat.removeAccessibilityAction(mViewForAction, mActionId);
        mActionId = NO_ACTION;
    }
}
