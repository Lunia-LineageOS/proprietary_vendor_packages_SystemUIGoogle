package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.res.R;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import java.util.List;

public class BcSmartspaceCardCombination extends BcSmartspaceCardSecondary {
    public ConstraintLayout mFirstSubCard;
    public ConstraintLayout mSecondSubCard;

    public BcSmartspaceCardCombination(Context context) {
        super(context);
    }

    public final boolean fillSubCard(
            ConstraintLayout subCard,
            SmartspaceTarget target,
            SmartspaceAction action,
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
            BcSmartspaceCardLoggingInfo loggingInfo) {
        TextView textView = subCard.findViewById(R.id.sub_card_text);
        ImageView imageView = subCard.findViewById(R.id.sub_card_icon);
        if (textView == null) {
            Log.w("BcSmartspaceCardCombination", "No sub-card text field to update");
            return false;
        }
        if (imageView == null) {
            Log.w("BcSmartspaceCardCombination", "No sub-card image field to update");
            return false;
        }
        BcSmartSpaceUtil.setOnClickListener(
                subCard,
                target,
                action,
                eventNotifier,
                "BcSmartspaceCardCombination",
                loggingInfo,
                0);
        Drawable icon =
                BcSmartSpaceUtil.getIconDrawableWithCustomSize(
                        action.getIcon(),
                        getContext(),
                        getResources()
                                .getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_size));
        boolean hasContent = false;
        if (icon == null) {
            BcSmartspaceTemplateDataUtils.updateVisibility(imageView, View.GONE);
        } else {
            imageView.setImageDrawable(icon);
            BcSmartspaceTemplateDataUtils.updateVisibility(imageView, View.VISIBLE);
            hasContent = true;
        }
        CharSequence title = action.getTitle();
        if (TextUtils.isEmpty(title)) {
            BcSmartspaceTemplateDataUtils.updateVisibility(textView, View.GONE);
        } else {
            textView.setText(title);
            BcSmartspaceTemplateDataUtils.updateVisibility(textView, View.VISIBLE);
            hasContent = true;
        }
        subCard.setContentDescription(hasContent ? action.getContentDescription() : null);
        BcSmartspaceTemplateDataUtils.updateVisibility(
                subCard, hasContent ? View.VISIBLE : View.GONE);
        return hasContent;
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mFirstSubCard = findViewById(R.id.first_sub_card);
        mSecondSubCard = findViewById(R.id.second_sub_card);
    }

    @Override
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(mFirstSubCard, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondSubCard, View.GONE);
    }

    @Override
    public boolean setSmartspaceActions(
            SmartspaceTarget target,
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
            BcSmartspaceCardLoggingInfo loggingInfo) {
        List<SmartspaceAction> actionChips = target.getActionChips();
        if (actionChips == null || actionChips.isEmpty() || actionChips.get(0) == null) {
            return false;
        }
        boolean firstFilled =
                mFirstSubCard != null
                        && fillSubCard(
                                mFirstSubCard,
                                target,
                                actionChips.get(0),
                                eventNotifier,
                                loggingInfo);
        boolean hasSecondChip = actionChips.size() > 1 && actionChips.get(1) != null;
        boolean secondFilled =
                !hasSecondChip
                        || fillSubCard(
                                mSecondSubCard,
                                target,
                                actionChips.get(1),
                                eventNotifier,
                                loggingInfo);
        if (getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) getLayoutParams();
            layoutParams.weight = hasSecondChip && secondFilled ? 3.0f : 1.0f;
            setLayoutParams(layoutParams);
        }
        return firstFilled && secondFilled;
    }

    public BcSmartspaceCardCombination(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public final void setTextColor(int color) {}
}
