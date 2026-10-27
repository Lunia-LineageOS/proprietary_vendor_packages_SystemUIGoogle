package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.os.Debug;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class BcSmartspaceDataProvider implements BcSmartspaceDataPlugin {
    public static final boolean DEBUG = Log.isLoggable(BcSmartspaceDataPlugin.TAG, 3);

    public final View.OnAttachStateChangeListener mStateChangeListener;
    public final Set<BcSmartspaceDataPlugin.SmartspaceTargetListener> mSmartspaceTargetListeners =
            new CopyOnWriteArraySet<>();
    public List<SmartspaceTarget> mSmartspaceTargets = Collections.emptyList();
    public final Set<View> mViews = new HashSet<>();
    public final Set<View.OnAttachStateChangeListener> mAttachListeners = new HashSet<>();
    public final EventNotifierProxy mEventNotifier = new EventNotifierProxy();

    public final class StateChangeListener implements View.OnAttachStateChangeListener {
        @Override
        public void onViewAttachedToWindow(View view) {
            mViews.add(view);
            for (View.OnAttachStateChangeListener listener : mAttachListeners) {
                listener.onViewAttachedToWindow(view);
            }
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            mViews.remove(view);
            for (View.OnAttachStateChangeListener listener : mAttachListeners) {
                listener.onViewDetachedFromWindow(view);
            }
        }
    }

    public BcSmartspaceDataProvider() {
        mStateChangeListener = new StateChangeListener();
    }

    @Override
    public final void addOnAttachStateChangeListener(View.OnAttachStateChangeListener listener) {
        mAttachListeners.add(listener);
        for (View view : mViews) {
            listener.onViewAttachedToWindow(view);
        }
    }

    @Override
    public final BcSmartspaceDataPlugin.SmartspaceEventNotifier getEventNotifier() {
        return mEventNotifier;
    }

    @Override
    public final BcSmartspaceDataPlugin.SmartspaceView getView(Context context) {
        View view =
                LayoutInflater.from(context).inflate(R.layout.smartspace_enhanced2, null, false);
        view.addOnAttachStateChangeListener(mStateChangeListener);
        return (BcSmartspaceDataPlugin.SmartspaceView) view;
    }

    @Override
    public final void onTargetsAvailable(List<SmartspaceTarget> list) {
        if (DEBUG) {
            Log.d(
                    BcSmartspaceDataPlugin.TAG,
                    this + " onTargetsAvailable called. Callers = " + Debug.getCallers(3));
            Log.d(BcSmartspaceDataPlugin.TAG, "    targets.size() = " + list.size());
            Log.d(BcSmartspaceDataPlugin.TAG, "    targets = " + list.toString());
        }

        mSmartspaceTargets = list.stream().filter(target -> target.getFeatureType() != 15).toList();

        mSmartspaceTargetListeners.forEach(
                listener -> listener.onSmartspaceTargetsUpdated(mSmartspaceTargets));
    }

    @Override
    public final void registerListener(BcSmartspaceDataPlugin.SmartspaceTargetListener listener) {
        mSmartspaceTargetListeners.add(listener);
        listener.onSmartspaceTargetsUpdated(mSmartspaceTargets);
    }

    @Override
    public final void unregisterListener(BcSmartspaceDataPlugin.SmartspaceTargetListener listener) {
        mSmartspaceTargetListeners.remove(listener);
    }

    @Override
    public final void setEventDispatcher(
            BcSmartspaceDataPlugin.SmartspaceEventDispatcher dispatcher) {
        mEventNotifier.eventDispatcher = dispatcher;
    }

    @Override
    public final void setIntentStarter(BcSmartspaceDataPlugin.IntentStarter intentStarter) {
        mEventNotifier.intentStarterRef = intentStarter;
    }
}
