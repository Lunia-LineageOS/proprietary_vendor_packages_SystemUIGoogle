package com.google.android.systemui.dreamliner;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;

import com.android.app.animation.Interpolators;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.wm.shell.shared.animation.PhysicsAnimator;

import com.google.android.systemui.res.R;

import java.util.concurrent.TimeUnit;

public final class DockGestureController extends GestureDetector.SimpleOnGestureListener
        implements View.OnTouchListener,
                StatusBarStateController.StateListener,
                KeyguardStateController.Callback,
                ConfigurationController.ConfigurationListener {
    private static final String TAG = "DLGestureController";

    private static final long GEAR_VISIBLE_TIME_MILLIS = TimeUnit.SECONDS.toMillis(15);
    private static final long PREVIEW_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(1);

    private final AccessibilityManager mAccessibilityManager;
    private final DockObserver.ProtectedBroadcastSender mBroadcastSender;
    private final Context mContext;
    private final DockIndicationController mDockIndicationController;
    private final GestureDetector mGestureDetector;
    private final Runnable mHideGearRunnable = this::hideGear;
    private final KeyguardStateController mKeyguardStateController;
    private final int mPhotoDiffThreshold;
    private final FrameLayout mPhotoPreview;
    private final TextView mPhotoPreviewText;
    private final PhysicsAnimator<FrameLayout> mPreviewTargetAnimator;
    private final ImageView mSettingsGear;
    private final DreamlinerA11yAction mShowPhotoFrameA11yAction;
    private final StatusBarStateController mStatusBarStateController;
    private final PhysicsAnimator.SpringConfig mTargetSpringConfig =
            new PhysicsAnimator.SpringConfig(1500.0f, 1.0f);
    private final View mTouchDelegateView;

    private float mDiffX;
    private float mFirstTouchX;
    private float mFirstTouchY;
    private boolean mFromRight;
    private float mLastTouchX;
    private boolean mLaunchedPhoto;
    private boolean mPhotoEnabled;
    private boolean mShouldConsumeTouch;
    private PendingIntent mTapAction;
    private boolean mTriggerPhoto;
    private VelocityTracker mVelocityTracker;
    private float mVelocityX;

    private final KeyguardStateController.Callback mKeyguardMonitorCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    if (mKeyguardStateController.isOccluded()) {
                        hidePhotoPreview(false);
                    }
                }
            };

    public DockGestureController(
            Context context,
            DockObserver.ProtectedBroadcastSender protectedBroadcastSender,
            ImageView dreamlinerGear,
            FrameLayout photoPreview,
            View touchDelegateView,
            DockIndicationController indicationController,
            StatusBarStateController statusBarStateController,
            KeyguardStateController keyguardStateController) {
        mDockIndicationController = indicationController;
        mContext = context;
        mBroadcastSender = protectedBroadcastSender;
        mGestureDetector = new GestureDetector(context, this);
        mTouchDelegateView = touchDelegateView;
        mSettingsGear = dreamlinerGear;
        mPhotoPreview = photoPreview;
        mPhotoPreviewText = photoPreview.findViewById(R.id.photo_preview_text);
        mPhotoPreviewText.setText(
                context.getResources().getString(R.string.dock_photo_preview_text));
        mSettingsGear.setOnClickListener(
                v -> {
                    hideGear();
                    sendProtectedBroadcast(
                            new Intent("com.google.android.systemui.dreamliner.SETTINGS"));
                });
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mPhotoDiffThreshold = context.getResources().getDimensionPixelSize(R.dimen.dock_photo_diff);
        mStatusBarStateController = statusBarStateController;
        mKeyguardStateController = keyguardStateController;
        mPreviewTargetAnimator = PhysicsAnimator.Companion.getInstance(photoPreview);
        mShowPhotoFrameA11yAction =
                new DreamlinerA11yAction(
                        context.getString(R.string.dock_a11y_action_show_photo_text),
                        photoPreview.getRootView(),
                        () ->
                                sendProtectedBroadcast(
                                        new Intent(
                                                "com.google.android.systemui.dreamliner.PHOTO_EVENT")));
    }

    public void hideGear() {
        if (mSettingsGear.isVisibleToUser() || mSettingsGear.getVisibility() == View.VISIBLE) {
            mSettingsGear.removeCallbacks(mHideGearRunnable);
            mSettingsGear
                    .animate()
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .alpha(0.0f)
                    .withEndAction(() -> mSettingsGear.setVisibility(View.INVISIBLE))
                    .start();
        }
    }

    public void hidePhotoPreview(boolean animate) {
        if (mPhotoPreview.getVisibility() != View.VISIBLE) {
            return;
        }
        if (!animate) {
            mPhotoPreview.setAlpha(0.0f);
            mPhotoPreview.setVisibility(View.INVISIBLE);
        } else {
            mPhotoPreview.post(
                    () -> {
                        mPreviewTargetAnimator.spring(
                                DynamicAnimation.TRANSLATION_X,
                                mPhotoPreview.getRight(),
                                mVelocityX,
                                mTargetSpringConfig);
                        mPreviewTargetAnimator.withEndActions(
                                () -> {
                                    mPhotoPreview.setAlpha(0.0f);
                                    mPhotoPreview.setVisibility(View.INVISIBLE);
                                });
                        mPreviewTargetAnimator.start();
                    });
        }
    }

    @Override
    public boolean onDown(MotionEvent motionEvent) {
        sendProtectedBroadcast(new Intent("com.google.android.systemui.dreamliner.TOUCH_EVENT"));
        return false;
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        if (!isDozing) {
            mTouchDelegateView.setOnTouchListener(null);
            hideGear();
            if (mLaunchedPhoto) {
                mPhotoPreview.postDelayed(() -> hidePhotoPreview(false), PREVIEW_DELAY_MILLIS);
            } else {
                hidePhotoPreview(true);
            }
            mShowPhotoFrameA11yAction.disable();
            return;
        }
        mTouchDelegateView.setOnTouchListener(this);
        showGear();
        mShowPhotoFrameA11yAction.enable();
    }

    @Override
    public void onLocaleListChanged() {
        mPhotoPreviewText.setText(
                mContext.getResources().getString(R.string.dock_photo_preview_text));
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent motionEvent) {
        if (mTapAction != null) {
            try {
                mTapAction.send();
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "Tap action pending intent cancelled", e);
            }
        }
        showGear();
        return false;
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        float x = motionEvent.getX();
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            mVelocityTracker.clear();
            mFirstTouchX = x;
            mFirstTouchY = motionEvent.getY();
            mLaunchedPhoto = false;
            mFromRight = false;
            boolean consume = true;
            if (mDockIndicationController != null) {
                ImageView icon = mDockIndicationController.mDockedTopIcon;
                if (icon != null) {
                    int[] loc = new int[2];
                    icon.getLocationOnScreen(loc);
                    RectF rect =
                            new RectF(
                                    loc[0],
                                    loc[1],
                                    loc[0] + icon.getWidth(),
                                    loc[1] + icon.getHeight());
                    boolean touchedIcon =
                            rect.contains(motionEvent.getRawX(), motionEvent.getRawY());
                    Log.d("DLIndicator", "dockedTopIcon touched=" + touchedIcon);
                    if (touchedIcon) {
                        consume = false;
                    }
                }
            }
            mShouldConsumeTouch = consume;
            if (consume && x > mPhotoPreview.getRight() - mPhotoDiffThreshold) {
                mFromRight = true;
                if (mPhotoEnabled) {
                    mPhotoPreview.setVisibility(View.VISIBLE);
                    CrossFadeHelper.fadeIn(mPhotoPreview, 100L);
                }
            }
        } else if (actionMasked == MotionEvent.ACTION_UP) {
            mVelocityTracker.computeCurrentVelocity(1000);
            mVelocityX = mVelocityTracker.getXVelocity();
            if (Math.signum(mDiffX) == Math.signum(mVelocityX)
                    || mLastTouchX > mPhotoPreview.getRight() - mPhotoDiffThreshold) {
                mTriggerPhoto = false;
            }
            if (mTriggerPhoto && mPhotoPreview.getVisibility() == View.VISIBLE) {
                sendProtectedBroadcast(
                        new Intent("com.google.android.systemui.dreamliner.PHOTO_EVENT"));
                mPhotoPreview.post(
                        () -> {
                            mPreviewTargetAnimator.spring(
                                    DynamicAnimation.TRANSLATION_X,
                                    0.0f,
                                    mVelocityX,
                                    mTargetSpringConfig);
                            mPreviewTargetAnimator.start();
                        });
                mLaunchedPhoto = true;
                mTriggerPhoto = false;
            } else {
                hidePhotoPreview(true);
            }
        } else if (actionMasked == MotionEvent.ACTION_MOVE && mFromRight && mPhotoEnabled) {
            float moveX = motionEvent.getX();
            mPhotoPreview.setTranslationX(mLastTouchX + (moveX - mLastTouchX));
            mVelocityTracker.addMovement(motionEvent);
            mDiffX = mFirstTouchX - moveX;
            if (Math.abs(mDiffX) > Math.abs(mFirstTouchY - motionEvent.getY())
                    && Math.abs(mDiffX) > mPhotoDiffThreshold) {
                mTriggerPhoto = true;
            }
        }
        mLastTouchX = x;
        if (mShouldConsumeTouch) {
            mGestureDetector.onTouchEvent(motionEvent);
        }
        return mShouldConsumeTouch;
    }

    public void sendProtectedBroadcast(Intent intent) {
        try {
            mBroadcastSender.sendBroadcastAsUser(intent);
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot send event", e);
        }
    }

    public void showGear() {
        if (mTapAction != null) {
            return;
        }
        if (!mSettingsGear.isVisibleToUser()) {
            mSettingsGear.setVisibility(View.VISIBLE);
            mSettingsGear
                    .animate()
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .alpha(1.0f)
                    .start();
        }
        mSettingsGear.removeCallbacks(mHideGearRunnable);
        mSettingsGear.postDelayed(
                mHideGearRunnable,
                mAccessibilityManager == null
                        ? GEAR_VISIBLE_TIME_MILLIS
                        : mAccessibilityManager.getRecommendedTimeoutMillis(
                                Math.toIntExact(GEAR_VISIBLE_TIME_MILLIS),
                                AccessibilityManager.FLAG_CONTENT_ICONS
                                        | AccessibilityManager.FLAG_CONTENT_CONTROLS));
    }

    void setPhotoEnabled(boolean photoEnabled) {
        mPhotoEnabled = photoEnabled;
    }

    void setTapAction(PendingIntent tapAction) {
        mTapAction = tapAction;
    }

    void startMonitoring() {
        mSettingsGear.setVisibility(View.INVISIBLE);
        onDozingChanged(mStatusBarStateController.isDozing());
        mStatusBarStateController.addCallback(this);
        mKeyguardStateController.addCallback(mKeyguardMonitorCallback);
    }

    void stopMonitoring() {
        mStatusBarStateController.removeCallback(this);
        mKeyguardStateController.removeCallback(mKeyguardMonitorCallback);
        onDozingChanged(false);
        mSettingsGear.setVisibility(View.GONE);
    }
}
