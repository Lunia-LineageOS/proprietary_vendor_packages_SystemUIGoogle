package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Constraints;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.res.R;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import java.util.Locale;

public class BcSmartspaceCardWeatherForecast extends BcSmartspaceCardSecondary {

    public interface ItemUpdateFunction {
        void update(View view, int index);
    }

    public BcSmartspaceCardWeatherForecast(Context context) {
        super(context);
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        ConstraintLayout[] columns = new ConstraintLayout[4];
        for (int i = 0; i < 4; i++) {
            ConstraintLayout column =
                    (ConstraintLayout)
                            ViewGroup.inflate(
                                    getContext(),
                                    R.layout.smartspace_card_weather_forecast_column,
                                    null);
            column.setId(View.generateViewId());
            columns[i] = column;
        }
        for (int i = 0; i < 4; i++) {
            Constraints.LayoutParams params = new Constraints.LayoutParams(-2, 0);
            ConstraintLayout prevColumn = i > 0 ? columns[i - 1] : null;
            ConstraintLayout nextColumn = i < 3 ? columns[i + 1] : null;
            if (i == 0) {
                params.startToStart = 0;
                params.horizontalChainStyle = 1;
            } else {
                params.startToEnd = prevColumn.getId();
            }
            if (i == 3) {
                params.endToEnd = 0;
            } else {
                params.endToStart = nextColumn.getId();
            }
            params.topToTop = 0;
            params.bottomToBottom = 0;
            addView(columns[i], params);
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
        boolean hasData = false;
        if (extras.containsKey("temperatureValues")) {
            String[] temperatures = extras.getStringArray("temperatureValues");
            if (temperatures == null) {
                Log.w("BcSmartspaceCardWeatherForecast", "Temperature values array is null.");
            } else {
                updateFields(
                        (view, i) -> ((TextView) view).setText(temperatures[i]),
                        temperatures.length,
                        R.id.temperature_value,
                        "temperature value");
            }
            hasData = true;
        }
        if (extras.containsKey("weatherIcons")) {
            Bitmap[] weatherIcons = (Bitmap[]) extras.get("weatherIcons");
            if (weatherIcons == null) {
                Log.w("BcSmartspaceCardWeatherForecast", "Weather icons array is null.");
            } else {
                updateFields(
                        (view, i) -> ((ImageView) view).setImageBitmap(weatherIcons[i]),
                        weatherIcons.length,
                        R.id.weather_icon,
                        "weather icon");
            }
            hasData = true;
        }
        if (extras.containsKey("timestamps")) {
            String[] timestamps = extras.getStringArray("timestamps");
            if (timestamps == null) {
                Log.w("BcSmartspaceCardWeatherForecast", "Timestamps array is null.");
            } else {
                updateFields(
                        (view, i) -> ((TextView) view).setText(timestamps[i]),
                        timestamps.length,
                        R.id.timestamp,
                        "timestamp");
            }
            hasData = true;
        }
        return hasData;
    }

    @Override
    public final void setTextColor(int color) {
        updateFields(
                (view, index) -> ((TextView) view).setTextColor(color),
                4,
                R.id.temperature_value,
                "temperature value");

        updateFields(
                (view, index) -> ((TextView) view).setTextColor(color),
                4,
                R.id.timestamp,
                "timestamp");
    }

    public final void updateFields(
            ItemUpdateFunction itemUpdateFunction, int count, int viewId, String viewName) {
        if (getChildCount() < 4) {
            Log.w(
                    "BcSmartspaceCardWeatherForecast",
                    String.format(
                            Locale.US,
                            "Missing %d " + viewName + " view(s) to update.",
                            4 - getChildCount()));
            return;
        }
        if (count < 4) {
            int missing = 4 - count;
            Log.w(
                    "BcSmartspaceCardWeatherForecast",
                    String.format(
                            Locale.US,
                            "Missing %d " + viewName + "(s). Hiding incomplete columns.",
                            missing));
            if (getChildCount() < 4) {
                Log.w(
                        "BcSmartspaceCardWeatherForecast",
                        "Missing " + (4 - getChildCount()) + " columns to update.");
            } else {
                int lastVisibleColumn = 3 - missing;
                for (int i = 0; i < 4; i++) {
                    BcSmartspaceTemplateDataUtils.updateVisibility(
                            getChildAt(i), i <= lastVisibleColumn ? View.VISIBLE : View.GONE);
                }
                ((ConstraintLayout.LayoutParams)
                                        ((ConstraintLayout) getChildAt(0)).getLayoutParams())
                                .horizontalChainStyle =
                        missing == 0 ? 1 : 0;
            }
        }
        int columns = Math.min(4, count);
        for (int i = 0; i < columns; i++) {
            View view = getChildAt(i).findViewById(viewId);
            if (view == null) {
                Log.w(
                        "BcSmartspaceCardWeatherForecast",
                        String.format(
                                Locale.US,
                                "Missing " + viewName + " view to update at column: %d.",
                                i + 1));
                return;
            }
            itemUpdateFunction.update(view, i);
        }
    }

    public BcSmartspaceCardWeatherForecast(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
