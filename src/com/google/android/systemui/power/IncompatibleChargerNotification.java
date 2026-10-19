package com.google.android.systemui.power;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.android.internal.logging.UiEventLogger;

import com.google.android.systemui.res.R;

import java.io.PrintWriter;

public class IncompatibleChargerNotification {
    private static final String TAG = "IncompatibleChargerNotification";
    static final String ACTION_DISMISS_INCOMPATIBLE_CHARGER_WARNING =
            "systemui.power.action.dismissIncompatibleChargerWarning";
    public static final String KEY_COMPATIBLE_CHARGER_TIME = "last_compatible_charger_time";
    public static final String KEY_INCOMPATIBLE_CHARGER_TIME = "last_incompatible_charger_time";

    private final Context mContext;
    private final UiEventLogger mUiEventLogger;
    private final NotificationManager mNotificationManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private boolean mNotificationActive = false;
    private boolean mContainsIncompatibleChargers = false;

    public IncompatibleChargerNotification(Context context, UiEventLogger uiEventLogger) {
        mContext = context;
        mUiEventLogger = uiEventLogger;
        mNotificationManager = context.getSystemService(NotificationManager.class);
    }

    public static SharedPreferences getSharedPreferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences("incompatible_charger_shared_prefs", Context.MODE_PRIVATE);
    }

    public void dispatchIntent(Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "dispatchIntent: " + action);
        if (ACTION_DISMISS_INCOMPATIBLE_CHARGER_WARNING.equals(action)) {
            logEvent(BatteryMetricEvent.DELETE_INCOMPATIBLE_CHARGING_NOTIFICATION);
        }
    }

    public void refreshUsbState(boolean isIncompatibleCharger) {
        if (isIncompatibleCharger == mContainsIncompatibleChargers) {
            return;
        }
        Log.d(TAG, "[refreshUsbState] isIncompatibleCharger: " + isIncompatibleCharger);
        mMainHandler.post(() -> updateNotification(isIncompatibleCharger));
        getSharedPreferences(mContext)
                .edit()
                .putLong(
                        isIncompatibleCharger
                                ? KEY_INCOMPATIBLE_CHARGER_TIME
                                : KEY_COMPATIBLE_CHARGER_TIME,
                        System.currentTimeMillis())
                .apply();
        mContainsIncompatibleChargers = isIncompatibleCharger;
    }

    public void dump(PrintWriter pw) {
        SharedPreferences prefs = getSharedPreferences(mContext);
        pw.println("\tdump IncompatibleCharger states:");
        pw.println(
                "\t\tLastCompatibleChargerTime: "
                        + DumpUtils.toReadableDateTime(
                                prefs.getLong(KEY_COMPATIBLE_CHARGER_TIME, 0L)));
        pw.println(
                "\t\tLastIncompatibleChargerTime: "
                        + DumpUtils.toReadableDateTime(
                                prefs.getLong(KEY_INCOMPATIBLE_CHARGER_TIME, 0L)));
    }

    private void updateNotification(boolean showNotification) {
        Log.d(TAG, "[updateNotification] showNotification: " + showNotification);
        if (showNotification) {
            sendNotification();
        } else {
            cancelNotification();
        }
    }

    private void sendNotification() {
        Log.d(TAG, "[sendNotification] isNotificationActive: " + mNotificationActive + " -> true");
        String description = mContext.getString(R.string.incompatible_charging_notify_des);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(mContext, "BAT")
                        .setSmallIcon(R.drawable.ic_battery_full)
                        .setShowWhen(false)
                        .setContentTitle(
                                mContext.getString(R.string.incompatible_charging_notify_title))
                        .setContentText(description)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(description))
                        .setDeleteIntent(
                                PowerUtils.createPendingIntent(
                                        mContext,
                                        ACTION_DISMISS_INCOMPATIBLE_CHARGER_WARNING,
                                        null))
                        .setContentIntent(
                                PowerUtils.createBatterySettingsPendingIntentAsUser(mContext))
                        .addAction(
                                0,
                                mContext.getString(R.string.incompatible_charging_learn_more),
                                PowerUtils.createHelpArticlePendingIntentAsUser(
                                        R.string.incompatible_charging_help_url, mContext))
                        .setLocalOnly(true);
        PowerUtils.overrideNotificationAppName(mContext, builder);
        mNotificationManager.notifyAsUser(
                "incompatible_charging",
                R.string.incompatible_charging_notify_title,
                builder.build(),
                UserHandle.ALL);
        mNotificationActive = true;
        logEvent(BatteryMetricEvent.SEND_INCOMPATIBLE_CHARGING_NOTIFICATION);
    }

    private void cancelNotification() {
        Log.d(
                TAG,
                "[cancelNotification] isNotificationActive: " + mNotificationActive + " -> false");
        if (mNotificationActive) {
            mNotificationManager.cancelAsUser(
                    "incompatible_charging",
                    R.string.incompatible_charging_notify_title,
                    UserHandle.ALL);
            mNotificationActive = false;
        }
    }

    private void logEvent(BatteryMetricEvent event) {
        if (mUiEventLogger != null) {
            mUiEventLogger.log(event);
        }
    }
}
