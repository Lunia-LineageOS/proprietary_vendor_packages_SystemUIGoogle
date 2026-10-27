package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import java.util.Locale;

public class BcSmartspaceCardShoppingList extends BcSmartspaceCardSecondary {
    public static final int[] LIST_ITEM_TEXT_VIEW_IDS = {
        R.id.list_item_1, R.id.list_item_2, R.id.list_item_3
    };
    public ImageView mCardPromptIconView;
    public TextView mCardPromptView;
    public TextView mEmptyListMessageView;
    public ImageView mListIconView;
    public final TextView[] mListItems;

    public BcSmartspaceCardShoppingList(Context context) {
        super(context);
        mListItems = new TextView[3];
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mCardPromptView = findViewById(R.id.card_prompt);
        mEmptyListMessageView = findViewById(R.id.empty_list_message);
        mCardPromptIconView = findViewById(R.id.card_prompt_icon);
        mListIconView = findViewById(R.id.list_icon);
        for (int i = 0; i < 3; i++) {
            mListItems[i] = findViewById(LIST_ITEM_TEXT_VIEW_IDS[i]);
        }
    }

    @Override
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(mEmptyListMessageView, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mListIconView, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mCardPromptIconView, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mCardPromptView, View.GONE);
        for (int i = 0; i < 3; i++) {
            BcSmartspaceTemplateDataUtils.updateVisibility(mListItems[i], View.GONE);
        }
    }

    @Override
    public final boolean setSmartspaceActions(
            SmartspaceTarget target,
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
            BcSmartspaceCardLoggingInfo loggingInfo) {
        SmartspaceAction baseAction = target.getBaseAction();
        Bundle extras = baseAction == null ? null : baseAction.getExtras();
        if (extras == null) {
            return false;
        }
        Bitmap icon = null;
        if (extras.containsKey("appIcon")) {
            icon = (Bitmap) extras.get("appIcon");
        } else if (extras.containsKey("imageBitmap")) {
            icon = (Bitmap) extras.get("imageBitmap");
        }
        mCardPromptIconView.setImageBitmap(icon);
        mListIconView.setImageBitmap(icon);
        if (extras.containsKey("cardPrompt")) {
            String cardPrompt = extras.getString("cardPrompt");
            if (mCardPromptView == null) {
                Log.w("BcSmartspaceCardShoppingList", "No card prompt view to update");
            } else {
                mCardPromptView.setText(cardPrompt);
            }
            BcSmartspaceTemplateDataUtils.updateVisibility(mCardPromptView, View.VISIBLE);
            if (icon != null) {
                BcSmartspaceTemplateDataUtils.updateVisibility(mCardPromptIconView, View.VISIBLE);
            }
        } else if (extras.containsKey("emptyListString")) {
            String emptyListString = extras.getString("emptyListString");
            if (mEmptyListMessageView == null) {
                Log.w("BcSmartspaceCardShoppingList", "No empty list message view to update");
            } else {
                mEmptyListMessageView.setText(emptyListString);
            }
            BcSmartspaceTemplateDataUtils.updateVisibility(mEmptyListMessageView, View.VISIBLE);
            BcSmartspaceTemplateDataUtils.updateVisibility(mListIconView, View.VISIBLE);
        } else if (extras.containsKey("listItems")) {
            String[] listItems = extras.getStringArray("listItems");
            if (listItems.length != 0) {
                BcSmartspaceTemplateDataUtils.updateVisibility(mListIconView, View.VISIBLE);
                for (int i = 0; i < 3; i++) {
                    TextView listItemView = mListItems[i];
                    if (listItemView == null) {
                        Log.w(
                                "BcSmartspaceCardShoppingList",
                                String.format(
                                        Locale.US,
                                        "Missing list item view to update at row: %d",
                                        i + 1));
                        break;
                    }
                    if (i < listItems.length) {
                        BcSmartspaceTemplateDataUtils.updateVisibility(listItemView, View.VISIBLE);
                        listItemView.setText(listItems[i]);
                    } else {
                        BcSmartspaceTemplateDataUtils.updateVisibility(listItemView, View.GONE);
                        listItemView.setText("");
                    }
                }
            }
        }
        return true;
    }

    @Override
    public final void setTextColor(int color) {
        mCardPromptView.setTextColor(color);
        mEmptyListMessageView.setTextColor(color);
        for (int i = 0; i < 3; i++) {
            TextView listItemView = mListItems[i];
            if (listItemView == null) {
                Log.w(
                        "BcSmartspaceCardShoppingList",
                        String.format(
                                Locale.US, "Missing list item view to update at row: %d", i + 1));
                return;
            }
            listItemView.setTextColor(color);
        }
    }

    public BcSmartspaceCardShoppingList(Context context, AttributeSet attrs) {
        super(context, attrs);
        mListItems = new TextView[3];
    }
}
