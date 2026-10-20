package com.google.android.systemui.dreamliner;

import android.content.Context;
import android.content.Intent;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.statusbar.policy.ConfigurationController;

import com.google.android.systemui.res.R;

import java.util.concurrent.TimeUnit;

public final class DockIndicationController
        implements StatusBarStateController.StateListener,
                View.OnClickListener,
                View.OnAttachStateChangeListener,
                ConfigurationController.ConfigurationListener {
    private static final String TAG = "DLIndicator";

    static final String ACTION_ASSISTANT_POODLE =
            "com.google.android.systemui.dreamliner.ASSISTANT_POODLE";

    private static final long PROMO_SHOWING_TIME_MILLIS = TimeUnit.SECONDS.toMillis(2);
    private static final long KEYGUARD_INDICATION_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15);
    private static final int MAX_PROMO_SHOW_TIMES = 3;

    private final AccessibilityManager mAccessibilityManager;
    private final BroadcastSender mBroadcastSender;
    private final Context mContext;
    private final KeyguardIndicationController mKeyguardIndicationController;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final Animation mShowPromoAnimation;
    private final Animation mHidePromoAnimation;
    private final Runnable mHidePromoRunnable = this::hidePromo;
    private final Runnable mDisableLiveRegionRunnable = this::disableLiveRegion;

    private LinearLayout mAmbientIndicationContainer;
    FrameLayout mDockPromo;
    ImageView mDockedTopIcon;
    private TextView mPromoText;
    private KeyguardIndicationTextView mTopIndicationView;
    private boolean mDocking;
    private boolean mDozing;
    boolean mIconViewsValidated;
    private boolean mShowPromo;
    private int mShowPromoTimes;
    private int mStatusBarState;
    private boolean mTopIconShowing;
    private boolean mViewAttached = true;

    public DockIndicationController(
            Context context,
            BroadcastSender broadcastSender,
            NotificationShadeWindowController notificationShadeWindowController,
            KeyguardIndicationController keyguardIndicationController,
            StatusBarStateController statusBarStateController) {
        mContext = context;
        mBroadcastSender = broadcastSender;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mKeyguardIndicationController = keyguardIndicationController;
        statusBarStateController.addCallback(this);

        mShowPromoAnimation = AnimationUtils.loadAnimation(context, R.anim.dock_promo_animation);
        mShowPromoAnimation.setAnimationListener(
                new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {}

                    @Override
                    public void onAnimationRepeat(Animation animation) {}

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        mDockPromo.postDelayed(
                                mHidePromoRunnable,
                                getRecommendedTimeoutMillis(PROMO_SHOWING_TIME_MILLIS));
                    }
                });
        mHidePromoAnimation = AnimationUtils.loadAnimation(context, R.anim.dock_promo_fade_out);
        mHidePromoAnimation.setAnimationListener(
                new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {}

                    @Override
                    public void onAnimationRepeat(Animation animation) {}

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        if (mShowPromoTimes < MAX_PROMO_SHOW_TIMES) {
                            showPromoInner();
                        } else {
                            mKeyguardIndicationController.setVisible(true);
                            mDockPromo.setVisibility(View.GONE);
                        }
                    }
                });
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
    }

    @Override
    public void onClick(View view) {
        if (view.getId() != R.id.docked_top_icon) {
            return;
        }
        Intent intent = new Intent(ACTION_ASSISTANT_POODLE);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        try {
            mBroadcastSender.sendBroadcastAsUser(intent, UserHandle.ALL);
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot send event for intent= " + intent, e);
        }
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        Log.d(TAG, "onDozingChanged(" + isDozing + ")");
        mDozing = isDozing;
        updateVisibility();
        updateLiveRegionIfNeeded();
        if (mDozing) {
            showPromoInner();
        } else {
            mShowPromo = false;
        }
    }

    @Override
    public void onStateChanged(int newState) {
        Log.d(TAG, "onStateChanged(" + newState + ")");
        mStatusBarState = newState;
        updateVisibility();
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        Log.d(TAG, "onViewAttachedToWindow()");
        mViewAttached = true;
        updateVisibility();
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        Log.d(TAG, "onViewDetachedFromWindow()");
        mViewAttached = false;
        view.removeOnAttachStateChangeListener(this);
        mIconViewsValidated = false;
        mDockedTopIcon = null;
    }

    @Override
    public void onLocaleListChanged() {
        if (!mIconViewsValidated && mViewAttached) {
            initializeIconViews();
        }
        mPromoText.setText(mContext.getResources().getString(R.string.dock_promo_text));
    }

    public void setShowing(boolean showing) {
        mTopIconShowing = showing;
        updateVisibility();
    }

    public void setDocking(boolean docking) {
        mDocking = docking;
        if (!mDocking) {
            mTopIconShowing = false;
            mShowPromo = false;
        }
        updateVisibility();
        updateLiveRegionIfNeeded();
    }

    boolean isPromoShowing() {
        return mDockPromo.getVisibility() == View.VISIBLE;
    }

    void showPromo(ResultReceiver resultReceiver) {
        mShowPromoTimes = 0;
        mShowPromo = true;
        if (mDozing && mDocking) {
            showPromoInner();
            resultReceiver.send(DockObserver.RESULT_OK, null);
        } else {
            resultReceiver.send(1, null);
        }
    }

    private void showPromoInner() {
        if (!mDozing || !mDocking || !mShowPromo) {
            return;
        }
        mKeyguardIndicationController.setVisible(false);
        mDockPromo.setVisibility(View.VISIBLE);
        mDockPromo.startAnimation(mShowPromoAnimation);
        mShowPromoTimes++;
    }

    private void hidePromo() {
        if (mDozing && mDocking) {
            mDockPromo.startAnimation(mHidePromoAnimation);
        }
    }

    private void disableLiveRegion() {
        if (mTopIndicationView != null && mDocking && mDozing) {
            mTopIndicationView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        }
    }

    void updateLiveRegionIfNeeded() {
        if (mTopIndicationView == null) {
            return;
        }
        if (mDozing && mDocking) {
            mTopIndicationView.removeCallbacks(mDisableLiveRegionRunnable);
            mTopIndicationView.postDelayed(
                    mDisableLiveRegionRunnable,
                    getRecommendedTimeoutMillis(KEYGUARD_INDICATION_TIMEOUT_MILLIS));
        } else if (mTopIndicationView.getAccessibilityLiveRegion()
                != View.ACCESSIBILITY_LIVE_REGION_POLITE) {
            mTopIndicationView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
    }

    private long getRecommendedTimeoutMillis(long originalTimeout) {
        return mAccessibilityManager == null
                ? originalTimeout
                : mAccessibilityManager.getRecommendedTimeoutMillis(
                        Math.toIntExact(originalTimeout), AccessibilityManager.FLAG_CONTENT_TEXT);
    }

    void updateVisibility() {
        Log.d(
                TAG,
                "updateVisibility(), mDozing="
                        + mDozing
                        + ", mDocking="
                        + mDocking
                        + ", mTopIconShowing="
                        + mTopIconShowing
                        + ", mViewAttached="
                        + mViewAttached);
        if (!mViewAttached) {
            try {
                initializeIconViews();
            } catch (Exception e) {
                Log.w(TAG, "tryInitializeIconView() failed", e);
                return;
            }
        }
        if (!mIconViewsValidated) {
            initializeIconViews();
        }
        boolean isTopIconShowing;
        if (mDozing && mDocking) {
            isTopIconShowing = mTopIconShowing;
            mDockedTopIcon.setVisibility(isTopIconShowing ? View.VISIBLE : View.GONE);
        } else {
            isTopIconShowing = false;
            mDockPromo.setVisibility(View.GONE);
            mDockedTopIcon.setVisibility(View.GONE);
            mKeyguardIndicationController.setVisible(
                    mStatusBarState == StatusBarState.KEYGUARD
                            || mStatusBarState == StatusBarState.SHADE_LOCKED);
        }
        updateAmbientIndicationMargin(isTopIconShowing);
    }

    private void updateAmbientIndicationMargin(boolean isTopIconShowing) {
        if (mAmbientIndicationContainer == null) {
            return;
        }
        int topMargin =
                isTopIconShowing
                        ? mAmbientIndicationContainer
                                .getResources()
                                .getDimensionPixelSize(
                                        com.android.systemui.res.R.dimen
                                                .ambient_indication_container_margin_top)
                        : 0;
        if (!(mAmbientIndicationContainer.getLayoutParams()
                instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams lp =
                (ViewGroup.MarginLayoutParams) mAmbientIndicationContainer.getLayoutParams();
        if (lp.topMargin != topMargin) {
            lp.setMargins(lp.leftMargin, topMargin, lp.rightMargin, lp.bottomMargin);
            mAmbientIndicationContainer.requestLayout();
        }
    }

    void initializeIconViews() {
        Log.d(TAG, "initializeIconViews()");
        ViewGroup rootView = mNotificationShadeWindowController.getWindowRootView();
        mDockedTopIcon = rootView.findViewById(R.id.docked_top_icon);
        mDockedTopIcon.setImageResource(R.drawable.ic_assistant_logo);
        mDockedTopIcon.setContentDescription(
                mContext.getString(R.string.accessibility_assistant_poodle));
        mDockedTopIcon.setTooltipText(mContext.getString(R.string.accessibility_assistant_poodle));
        mDockedTopIcon.setOnClickListener(this);
        mDockPromo = rootView.findViewById(R.id.dock_promo);
        mPromoText = rootView.findViewById(R.id.photo_promo_text);
        mPromoText.setAutoSizeTextTypeUniformWithConfiguration(
                10, 16, 1, TypedValue.COMPLEX_UNIT_SP);
        rootView.findViewById(R.id.ambient_indication).addOnAttachStateChangeListener(this);
        mTopIndicationView =
                rootView.findViewById(com.android.systemui.res.R.id.keyguard_indication_text);
        mAmbientIndicationContainer = rootView.findViewById(R.id.ambient_indication_info_container);
        mIconViewsValidated = true;
        mViewAttached = true;
    }
}
