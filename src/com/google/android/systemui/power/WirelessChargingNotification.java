package com.google.android.systemui.power;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.devicestate.DeviceStateManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.UserHandle;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.util.Utils;

import com.google.android.systemui.res.R;

import java.util.concurrent.Executor;

public class WirelessChargingNotification {
    private static final String TAG = "WirelessChargingNotification";
    static final String ACTION_DISMISS_WIRELESS_CHARGING_NOTIFICATION =
            "systemui.power.action.dismissWirelessChargingNotification";
    static final String ACTION_CLICK_WIRELESS_CHARGING_NOTIFICATION =
            "systemui.power.action.clickWirelessChargingNotification";
    static final long WIRELESS_CHARGING_DEFAULT_TIMESTAMP = -1;

    private final Context mContext;
    private final Executor mExecutor;
    private final ActivityStarter mActivityStarter;
    private final UiEventLogger mUiEventLogger;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final DeviceStateManager mDeviceStateManager;
    private final SubscriptionManager mSubscriptionManager;
    NotificationManager mNotificationManager;
    boolean mIsWirelessCharging;
    private boolean mIsNotificationActive = false;

    public WirelessChargingNotification(
            Context context,
            Executor executor,
            ActivityStarter activityStarter,
            UiEventLogger uiEventLogger,
            DeviceProvisionedController deviceProvisionedController,
            DeviceStateManager deviceStateManager,
            SubscriptionManager subscriptionManager) {
        mContext = context;
        mExecutor = executor;
        mActivityStarter = activityStarter;
        mUiEventLogger = uiEventLogger;
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mDeviceProvisionedController = deviceProvisionedController;
        mDeviceStateManager = deviceStateManager;
        mSubscriptionManager = subscriptionManager;
    }

    public void dispatchIntent(Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            if (!mDeviceProvisionedController.isDeviceProvisioned()) {
                Log.d(TAG, "[dispatchIntent] skip since device is not provisioned.");
                return;
            }
            boolean wasWirelessCharging = mIsWirelessCharging;
            mIsWirelessCharging =
                    intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                            == BatteryManager.BATTERY_PLUGGED_WIRELESS;
            if (!mIsWirelessCharging || wasWirelessCharging) {
                return;
            }
            Log.d(TAG, "mWirelessCharging:" + mIsWirelessCharging);
            mExecutor.execute(this::showNotificationIfNeeded);
        } else if (ACTION_CLICK_WIRELESS_CHARGING_NOTIFICATION.equals(action)) {
            Intent helpIntent =
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                    mContext.getString(
                                            R.string.wireless_charging_notification_help_url)));
            helpIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mActivityStarter.startActivity(helpIntent, true);
            mUiEventLogger.log(BatteryMetricEvent.CLICK_WIRELESS_CHARGING_NOTIFICATION);
        } else if (ACTION_DISMISS_WIRELESS_CHARGING_NOTIFICATION.equals(action)) {
            logEvent(BatteryMetricEvent.DISMISS_WIRELESS_CHARGING_NOTIFICATION);
        }
    }

    static String getKey() {
        return ActivityManager.getCurrentUser() + "|wireless_charging_notification_timestamp";
    }

    static SharedPreferences getSharedPreferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(
                        "wireless_charging_notification_shared_prefs", Context.MODE_PRIVATE);
    }

    static void putWirelessChargingNotificationTimestamp(Context context) {
        getSharedPreferences(context).edit().putLong(getKey(), System.currentTimeMillis()).apply();
    }

    private void showNotificationIfNeeded() {
        boolean showNotification = false;
        if (getSharedPreferences(mContext).getLong(getKey(), WIRELESS_CHARGING_DEFAULT_TIMESTAMP)
                == WIRELESS_CHARGING_DEFAULT_TIMESTAMP) {
            if (Utils.isDeviceFoldable(mContext.getResources(), mDeviceStateManager)
                    && !PowerUtils.isSimInEuCountry(mSubscriptionManager)) {
                putWirelessChargingNotificationTimestamp(mContext);
            } else {
                showNotification = true;
            }
        }
        Log.d(TAG, "showNotification: " + showNotification);
        if (showNotification) {
            putWirelessChargingNotificationTimestamp(mContext);
            sendNotification();
        }
    }

    private void sendNotification() {
        Log.d(TAG, "[sendNotification] isNotificationActive: " + mIsNotificationActive);
        String text = mContext.getString(R.string.wireless_charging_notification_text);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(mContext, "BAT")
                        .setSmallIcon(R.drawable.ic_battery_charging)
                        .setContentText(text)
                        .setContentTitle(
                                mContext.getString(R.string.wireless_charging_notification_title))
                        .setContentIntent(
                                PowerUtils.createPendingIntent(
                                        mContext,
                                        ACTION_CLICK_WIRELESS_CHARGING_NOTIFICATION,
                                        null))
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                        .setDeleteIntent(
                                PowerUtils.createPendingIntent(
                                        mContext,
                                        ACTION_DISMISS_WIRELESS_CHARGING_NOTIFICATION,
                                        null))
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setOngoing(true)
                        .setAutoCancel(true)
                        .setSilent(true)
                        .setLocalOnly(true);
        PowerUtils.overrideNotificationAppName(mContext, builder);
        mNotificationManager.notifyAsUser(
                "wireless_charging",
                R.string.wireless_charging_notification_title,
                builder.build(),
                UserHandle.CURRENT);
        mIsNotificationActive = true;
        logEvent(BatteryMetricEvent.SEND_WIRELESS_CHARGING_NOTIFICATION);
    }

    private void logEvent(BatteryMetricEvent event) {
        if (mUiEventLogger != null) {
            mUiEventLogger.log(event);
        }
    }
}
