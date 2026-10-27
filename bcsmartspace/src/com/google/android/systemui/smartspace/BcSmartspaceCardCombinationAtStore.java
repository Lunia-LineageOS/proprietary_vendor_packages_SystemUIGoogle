package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.util.AttributeSet;

import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import java.util.List;

public class BcSmartspaceCardCombinationAtStore extends BcSmartspaceCardCombination {
    public BcSmartspaceCardCombinationAtStore(Context context) {
        super(context);
    }

    @Override
    public final boolean setSmartspaceActions(
            SmartspaceTarget target,
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
            BcSmartspaceCardLoggingInfo loggingInfo) {
        List<SmartspaceAction> actionChips = target.getActionChips();
        if (actionChips == null || actionChips.isEmpty() || actionChips.get(0) == null) {
            return false;
        }
        boolean shoppingListFilled =
                mFirstSubCard instanceof BcSmartspaceCardShoppingList
                        && ((BcSmartspaceCardShoppingList) mFirstSubCard)
                                .setSmartspaceActions(target, eventNotifier, loggingInfo);
        boolean subCardFilled =
                mSecondSubCard != null
                        && fillSubCard(
                                mSecondSubCard,
                                target,
                                actionChips.get(0),
                                eventNotifier,
                                loggingInfo);
        if (shoppingListFilled) {
            mFirstSubCard.setBackgroundResource(R.drawable.bg_smartspace_combination_sub_card);
        }
        return shoppingListFilled && subCardFilled;
    }

    public BcSmartspaceCardCombinationAtStore(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
