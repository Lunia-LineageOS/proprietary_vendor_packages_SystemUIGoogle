package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.android.app.animation.Interpolators;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.res.R;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardMetadataLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceSubcardLoggingInfo;
import com.google.android.systemui.smartspace.utils.ContentDescriptionUtil;

import java.util.List;
import java.util.Locale;

public class BcSmartspaceCard extends ConstraintLayout implements SmartspaceCard {
    public final DoubleShadowIconDrawable mBaseActionIconDrawable;
    public Rect mBaseActionIconSubtitleHitRect;
    public DoubleShadowTextView mBaseActionIconSubtitleView;
    public float mDozeAmount;
    public BcSmartspaceDataPlugin.SmartspaceEventNotifier mEventNotifier;
    public final DoubleShadowIconDrawable mIconDrawable;
    public int mIconTintColor;
    public BcSmartspaceCardLoggingInfo mLoggingInfo;
    public BcSmartspaceCardSecondary mSecondaryCard;
    public ViewGroup mSecondaryCardGroup;
    public TextView mSubtitleTextView;
    public SmartspaceTarget mTarget;
    public ViewGroup mTextGroup;
    public TextView mTitleTextView;
    public boolean mTouchDelegateIsDirty;
    public String mUiSurface;
    public boolean mUsePageIndicatorUi;
    public boolean mValidSecondaryCard;

    public BcSmartspaceCard(Context context) {
        this(context, null);
    }

    public static int getClickedIndex(BcSmartspaceCardLoggingInfo loggingInfo, int cardTypeId) {
        BcSmartspaceSubcardLoggingInfo subcardInfo = loggingInfo.mSubcardInfo;
        if (subcardInfo != null && subcardInfo.mSubcards != null) {
            List<BcSmartspaceCardMetadataLoggingInfo> subcards = subcardInfo.mSubcards;
            for (int i = 0; i < subcards.size(); i++) {
                BcSmartspaceCardMetadataLoggingInfo subcard = subcards.get(i);
                if (subcard != null && subcard.mCardTypeId == cardTypeId) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    @Override
    public final void bindData(
            SmartspaceTarget target,
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
            BcSmartspaceCardLoggingInfo loggingInfo,
            boolean usePageIndicatorUi) {
        mLoggingInfo = null;
        mEventNotifier = null;
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondaryCardGroup, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mBaseActionIconSubtitleView, View.GONE);
        mIconDrawable.mIconDrawable = null;
        mBaseActionIconDrawable.mIconDrawable = null;
        setTitle(null, null, false);
        setSubtitle(null, null, false);
        setBaseActionIconSubtitle(null, null, null);
        updateIconTint();
        setOnClickListener(null);
        if (mTitleTextView != null) {
            mTitleTextView.setOnClickListener(null);
            mTitleTextView.setClickable(false);
        }
        if (mSubtitleTextView != null) {
            mSubtitleTextView.setOnClickListener(null);
            mSubtitleTextView.setClickable(false);
        }
        if (mBaseActionIconSubtitleView != null) {
            mBaseActionIconSubtitleView.setOnClickListener(null);
            mBaseActionIconSubtitleView.setClickable(false);
        }
        mTarget = target;
        mEventNotifier = eventNotifier;
        SmartspaceAction headerAction = target.getHeaderAction();
        SmartspaceAction baseAction = target.getBaseAction();
        mLoggingInfo = loggingInfo;
        mUsePageIndicatorUi = usePageIndicatorUi;
        mValidSecondaryCard = false;
        if (mTextGroup != null) {
            mTextGroup.setTranslationX(0.0f);
        }
        int iconSize = getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_size);
        if (headerAction != null) {
            if (mSecondaryCard != null) {
                mSecondaryCard.reset(target.getSmartspaceTargetId());
                mValidSecondaryCard =
                        mSecondaryCard.setSmartspaceActions(target, mEventNotifier, loggingInfo);
            }
            if (mSecondaryCardGroup != null) {
                mSecondaryCardGroup.setAlpha(1.0f);
            }
            BcSmartspaceTemplateDataUtils.updateVisibility(
                    mSecondaryCardGroup,
                    mDozeAmount != 1.0f && mValidSecondaryCard ? View.VISIBLE : View.GONE);
            Drawable icon =
                    BcSmartSpaceUtil.getIconDrawableWithCustomSize(
                            headerAction.getIcon(), getContext(), iconSize);
            boolean hasIcon = icon != null;
            mIconDrawable.setIcon(icon);
            CharSequence title = headerAction.getTitle();
            CharSequence subtitle = headerAction.getSubtitle();
            boolean hasTitle = target.getFeatureType() == 1 || !TextUtils.isEmpty(title);
            boolean hasSubtitle = !TextUtils.isEmpty(subtitle);
            setTitle(
                    hasTitle ? title : subtitle,
                    headerAction.getContentDescription(),
                    hasTitle != hasSubtitle && hasIcon);
            setSubtitle(
                    hasTitle && hasSubtitle ? subtitle : null,
                    headerAction.getContentDescription(),
                    hasIcon);
        }
        if (baseAction != null) {
            int subcardType =
                    (baseAction.getExtras() == null || baseAction.getExtras().isEmpty())
                            ? -1
                            : baseAction.getExtras().getInt("subcardType", -1);
            mBaseActionIconDrawable.setIcon(
                    baseAction.getIcon() != null
                            ? BcSmartSpaceUtil.getIconDrawableWithCustomSize(
                                    baseAction.getIcon(), getContext(), iconSize)
                            : null);
            setBaseActionIconSubtitle(
                    baseAction.getSubtitle(),
                    baseAction.getContentDescription(),
                    mBaseActionIconDrawable);
            int clickedIndex = 0;
            if (subcardType != -1) {
                clickedIndex = getClickedIndex(loggingInfo, subcardType);
            } else {
                Log.d(
                        "BcSmartspaceCard",
                        "Subcard expected but missing type. loggingInfo="
                                + loggingInfo.toString()
                                + ", baseAction="
                                + baseAction.toString());
            }
            BcSmartSpaceUtil.setOnClickListener(
                    mBaseActionIconSubtitleView,
                    target,
                    baseAction,
                    mEventNotifier,
                    "BcSmartspaceCard",
                    loggingInfo,
                    clickedIndex);
        }
        updateIconTint();
        SmartspaceAction cardAction;
        if (hasIntent(headerAction)) {
            cardAction = headerAction;
        } else if (hasIntent(baseAction)) {
            cardAction = baseAction;
        } else {
            cardAction = headerAction;
        }
        if (cardAction != null) {
            BcSmartSpaceUtil.setOnClickListener(
                    this, target, cardAction, mEventNotifier, "BcSmartspaceCard", loggingInfo, 0);
        }
        if (mSecondaryCardGroup == null) {
            return;
        }
        ConstraintLayout.LayoutParams layoutParams =
                (ConstraintLayout.LayoutParams) mSecondaryCardGroup.getLayoutParams();
        if (BcSmartSpaceUtil.getFeatureType(target) == -2) {
            layoutParams.matchConstraintMaxWidth = (getWidth() * 3) / 4;
        } else {
            layoutParams.matchConstraintMaxWidth = getWidth() / 2;
        }
        mSecondaryCardGroup.setLayoutParams(layoutParams);
        mTouchDelegateIsDirty = true;
    }

    private static boolean hasIntent(SmartspaceAction action) {
        return action != null && (action.getIntent() != null || action.getPendingIntent() != null);
    }

    @Override
    public final AccessibilityNodeInfo createAccessibilityNodeInfo() {
        AccessibilityNodeInfo info = super.createAccessibilityNodeInfo();
        AccessibilityNodeInfoCompat.wrap(info).setRoleDescription(" ");
        return info;
    }

    @Override
    public final BcSmartspaceCardLoggingInfo getLoggingInfo() {
        if (mLoggingInfo != null) {
            return mLoggingInfo;
        }
        BcSmartspaceCardLoggingInfo.Builder builder =
                new BcSmartspaceCardLoggingInfo.Builder()
                        .setDisplaySurface(
                                BcSmartSpaceUtil.getLoggingDisplaySurface(mUiSurface, mDozeAmount))
                        .setFeatureType(mTarget != null ? mTarget.getFeatureType() : 0)
                        .setUid(-1);
        return new BcSmartspaceCardLoggingInfo(builder);
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        setPaddingRelative(
                getResources().getDimensionPixelSize(R.dimen.non_remoteviews_card_padding_start),
                getPaddingTop(),
                getPaddingEnd(),
                getPaddingBottom());
        mTextGroup = (ViewGroup) findViewById(R.id.text_group);
        mSecondaryCardGroup = (ViewGroup) findViewById(R.id.secondary_card_group);
        mTitleTextView = (TextView) findViewById(R.id.title_text);
        mSubtitleTextView = (TextView) findViewById(R.id.subtitle_text);
        mBaseActionIconSubtitleView = findViewById(R.id.base_action_icon_subtitle);
        if (mBaseActionIconSubtitleView != null) {
            mBaseActionIconSubtitleHitRect = new Rect();
        }
    }

    @Override
    public final void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!changed && !mTouchDelegateIsDirty) {
            return;
        }
        mTouchDelegateIsDirty = false;
        setTouchDelegate(null);
        if (mBaseActionIconSubtitleView == null
                || mBaseActionIconSubtitleView.getVisibility() != View.VISIBLE) {
            return;
        }
        int padding =
                (getResources().getDimensionPixelSize(R.dimen.subtitle_hit_rect_height)
                                - mBaseActionIconSubtitleView.getHeight())
                        / 2;
        mBaseActionIconSubtitleView.getHitRect(mBaseActionIconSubtitleHitRect);
        offsetDescendantRectToMyCoords(
                (View) mBaseActionIconSubtitleView.getParent(), mBaseActionIconSubtitleHitRect);
        if (padding > 0 || mBaseActionIconSubtitleHitRect.bottom != getHeight()) {
            if (padding > 0) {
                mBaseActionIconSubtitleHitRect.top -= padding;
            }
            mBaseActionIconSubtitleHitRect.bottom = getHeight();
            setTouchDelegate(
                    new TouchDelegate(mBaseActionIconSubtitleHitRect, mBaseActionIconSubtitleView));
        }
    }

    public final void setBaseActionIconSubtitle(
            CharSequence text, CharSequence contentDescription, Drawable icon) {
        if (mBaseActionIconSubtitleView == null) {
            Log.w("BcSmartspaceCard", "No base action icon subtitle view to update");
            return;
        }
        if (TextUtils.isEmpty(text)) {
            BcSmartspaceTemplateDataUtils.updateVisibility(mBaseActionIconSubtitleView, View.GONE);
            return;
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(mBaseActionIconSubtitleView, View.VISIBLE);
        mBaseActionIconSubtitleView.setText(text);
        mBaseActionIconSubtitleView.setCompoundDrawablesRelative(icon, null, null, null);
        ContentDescriptionUtil.setFormattedContentDescription(
                "BcSmartspaceCard", mBaseActionIconSubtitleView, text, contentDescription);
    }

    @Override
    public final void setDozeAmount(float dozeAmount) {
        mDozeAmount = dozeAmount;
        if (mTarget != null
                && mTarget.getBaseAction() != null
                && mTarget.getBaseAction().getExtras() != null) {
            Bundle extras = mTarget.getBaseAction().getExtras();
            if (mTitleTextView != null && extras.getBoolean("hide_title_on_aod")) {
                mTitleTextView.setAlpha(1.0f - dozeAmount);
            }
            if (mSubtitleTextView != null && extras.getBoolean("hide_subtitle_on_aod")) {
                mSubtitleTextView.setAlpha(1.0f - dozeAmount);
            }
        }
        if (mTextGroup == null) {
            return;
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(
                mSecondaryCardGroup,
                (mDozeAmount == 1.0f || !mValidSecondaryCard) ? View.GONE : View.VISIBLE);
        if (mTarget != null && mTarget.getFeatureType() == 30) {
            return;
        }
        if (mSecondaryCardGroup == null || mSecondaryCardGroup.getVisibility() == View.GONE) {
            mTextGroup.setTranslationX(0.0f);
            return;
        }
        mTextGroup.setTranslationX(
                ((PathInterpolator) Interpolators.EMPHASIZED).getInterpolation(mDozeAmount)
                        * mSecondaryCardGroup.getWidth()
                        * (isRtl() ? 1 : -1));
        mSecondaryCardGroup.setAlpha(
                Math.max(0.0f, Math.min(1.0f, ((1.0f - mDozeAmount) * 9.0f) - 6.0f)));
    }

    @Override
    public final void setPrimaryTextColor(int color) {
        if (mTitleTextView != null) {
            mTitleTextView.setTextColor(color);
        }
        if (mSubtitleTextView != null) {
            mSubtitleTextView.setTextColor(color);
        }
        if (mBaseActionIconSubtitleView != null) {
            mBaseActionIconSubtitleView.setTextColor(color);
        }
        if (mSecondaryCard != null) {
            mSecondaryCard.setTextColor(color);
        }
        mIconTintColor = color;
        updateIconTint();
    }

    public final void setSubtitle(
            CharSequence text, CharSequence contentDescription, boolean useIcon) {
        if (mSubtitleTextView == null) {
            Log.w("BcSmartspaceCard", "No subtitle view to update");
            return;
        }
        mSubtitleTextView.setText(text);
        mSubtitleTextView.setCompoundDrawablesRelative(
                (TextUtils.isEmpty(text) || !useIcon) ? null : mIconDrawable, null, null, null);
        mSubtitleTextView.setMaxLines(
                (mTarget == null || mTarget.getFeatureType() != 5 || mUsePageIndicatorUi) ? 1 : 2);
        ContentDescriptionUtil.setFormattedContentDescription(
                "BcSmartspaceCard", mSubtitleTextView, text, contentDescription);
        BcSmartspaceTemplateDataUtils.offsetTextViewForIcon(
                mSubtitleTextView, useIcon ? mIconDrawable : null, isRtl());
    }

    public final void setTitle(
            CharSequence text, CharSequence contentDescription, boolean useIcon) {
        if (mTitleTextView == null) {
            Log.w("BcSmartspaceCard", "No title view to update");
            return;
        }
        mTitleTextView.setText(text);
        SmartspaceAction headerAction = mTarget == null ? null : mTarget.getHeaderAction();
        Bundle extras = headerAction == null ? null : headerAction.getExtras();
        if (extras == null || !extras.containsKey("titleEllipsize")) {
            if (mTarget != null
                    && mTarget.getFeatureType() == 2
                    && Locale.ENGLISH
                            .getLanguage()
                            .equals(getResources().getConfiguration().locale.getLanguage())) {
                mTitleTextView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            } else {
                mTitleTextView.setEllipsize(TextUtils.TruncateAt.END);
            }
        } else {
            try {
                mTitleTextView.setEllipsize(
                        TextUtils.TruncateAt.valueOf(extras.getString("titleEllipsize")));
            } catch (IllegalArgumentException unused) {
                Log.w(
                        "BcSmartspaceCard",
                        "Invalid TruncateAt value: " + extras.getString("titleEllipsize"));
            }
        }
        boolean disableTitleIcon = false;
        if (extras != null) {
            if (extras.getInt("titleMaxLines") != 0) {
                mTitleTextView.setMaxLines(extras.getInt("titleMaxLines"));
            }
            disableTitleIcon = extras.getBoolean("disableTitleIcon");
        }
        boolean showIcon = useIcon && !disableTitleIcon;
        if (showIcon) {
            ContentDescriptionUtil.setFormattedContentDescription(
                    "BcSmartspaceCard", mTitleTextView, text, contentDescription);
        }
        mTitleTextView.setCompoundDrawablesRelative(
                showIcon ? mIconDrawable : null, null, null, null);
        BcSmartspaceTemplateDataUtils.offsetTextViewForIcon(
                mTitleTextView, showIcon ? mIconDrawable : null, isRtl());
    }

    public final void updateIconTint() {
        if (mTarget == null) {
            return;
        }
        if (mTarget.getFeatureType() == 1) {
            mIconDrawable.setTintList(null);
        } else {
            mIconDrawable.setTint(mIconTintColor);
        }
        SmartspaceAction baseAction = mTarget.getBaseAction();
        int subcardType = -1;
        if (baseAction != null
                && baseAction.getExtras() != null
                && !baseAction.getExtras().isEmpty()) {
            subcardType = baseAction.getExtras().getInt("subcardType", -1);
        }
        if (subcardType == 1) {
            mBaseActionIconDrawable.setTintList(null);
        } else {
            mBaseActionIconDrawable.setTint(mIconTintColor);
        }
    }

    public BcSmartspaceCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSecondaryCard = null;
        mIconTintColor = GraphicsUtils.getAttrColor(context, android.R.attr.textColorPrimary);
        mTextGroup = null;
        mSecondaryCardGroup = null;
        mTitleTextView = null;
        mSubtitleTextView = null;
        mBaseActionIconSubtitleView = null;
        mBaseActionIconSubtitleHitRect = null;
        mUiSurface = null;
        mTouchDelegateIsDirty = false;
        context.getTheme().applyStyle(R.style.Smartspace, false);
        mIconDrawable = new DoubleShadowIconDrawable(context);
        mBaseActionIconDrawable = new DoubleShadowIconDrawable(context);
        setDefaultFocusHighlightEnabled(false);
    }

    @Override
    public final View getView() {
        return this;
    }

    @Override
    public final void setScreenOn(boolean screenOn) {}
}
