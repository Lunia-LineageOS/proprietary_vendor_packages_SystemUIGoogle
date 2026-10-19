package com.google.android.systemui.power;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.UserHandle;
import android.text.Html;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.android.settingslib.Utils;

import com.google.android.systemui.res.R;

import java.time.Clock;
import java.time.Duration;

public class BatteryReplacementNotification {
    private static final String TAG = "BatteryReplacementNotification";
    private static final String KEY_RECOMMENDATION_TRIGGER_TIMES = "recommendation_trigger_times";
    private static final String KEY_LAST_RECOMMENDATION_TIMESTAMP_MS =
            "last_recommendation_timestamp_ms";
    private static final String KEY_EARLY_RECOMMENDATION_TRIGGER_TIMES =
            "early_recommendation_trigger_times";
    // android.hardware.health.BatteryHealth.FAIR, not exposed by BatteryManager.
    private static final int BATTERY_HEALTH_FAIR = 8;
    private static final int EARLY_RECOMMENDATION_CYCLE_COUNT = 375;
    private static final int MAX_RECOMMENDATION_TRIGGER_TIMES = 3;
    private static final int MAX_EARLY_RECOMMENDATION_TRIGGER_TIMES = 1;
    static final long RECOMMENDATION_INTERVAL_MS = Duration.ofDays(14).toMillis();

    private final Context mContext;
    private final Clock mClock;
    private SharedPreferences mSharedPreferences;
    private int mRecommendationTriggerTimes = -1;
    private long mLastRecommendationTimestampMs = -1;
    private int mEarlyRecommendationTriggerTimes = -1;

    public BatteryReplacementNotification(Context context, Clock clock) {
        mContext = context;
        mClock = clock;
    }

    public void dispatchIntent(Intent intent) {
        if (!Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
            return;
        }
        int health =
                intent.getIntExtra(
                        BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
        int cycleCount = intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);
        Log.d(TAG, "battery health: " + health + ", cycle count: " + cycleCount);
        if (health == BatteryManager.BATTERY_HEALTH_DEAD) {
            sendRecommendationIfNeeded();
        } else if (health == BATTERY_HEALTH_FAIR
                && cycleCount >= EARLY_RECOMMENDATION_CYCLE_COUNT) {
            sendEarlyRecommendationIfNeeded();
        }
    }

    private void sendRecommendationIfNeeded() {
        if (mRecommendationTriggerTimes == -1 || mLastRecommendationTimestampMs == -1) {
            SharedPreferences prefs = getSharedPreferences();
            mRecommendationTriggerTimes = prefs.getInt(KEY_RECOMMENDATION_TRIGGER_TIMES, 0);
            mLastRecommendationTimestampMs =
                    prefs.getLong(KEY_LAST_RECOMMENDATION_TIMESTAMP_MS, 0L);
        }
        long now = mClock.millis();
        if (mRecommendationTriggerTimes >= MAX_RECOMMENDATION_TRIGGER_TIMES
                || now - mLastRecommendationTimestampMs <= RECOMMENDATION_INTERVAL_MS) {
            return;
        }
        Log.w(
                TAG,
                "recommendation, count: "
                        + mRecommendationTriggerTimes
                        + ", last: "
                        + mLastRecommendationTimestampMs);
        sendNotification(
                mContext.getString(R.string.battery_replacement_notify_title),
                Html.fromHtml(
                        mContext.getString(R.string.battery_replacement_notify_des),
                        Html.FROM_HTML_MODE_LEGACY),
                false);
        mRecommendationTriggerTimes++;
        mLastRecommendationTimestampMs = now;
        getSharedPreferences()
                .edit()
                .putInt(KEY_RECOMMENDATION_TRIGGER_TIMES, mRecommendationTriggerTimes)
                .putLong(KEY_LAST_RECOMMENDATION_TIMESTAMP_MS, mLastRecommendationTimestampMs)
                .apply();
    }

    private void sendEarlyRecommendationIfNeeded() {
        if (mEarlyRecommendationTriggerTimes == -1) {
            mEarlyRecommendationTriggerTimes =
                    getSharedPreferences().getInt(KEY_EARLY_RECOMMENDATION_TRIGGER_TIMES, 0);
        }
        if (mEarlyRecommendationTriggerTimes >= MAX_EARLY_RECOMMENDATION_TRIGGER_TIMES) {
            return;
        }
        Log.w(TAG, "early recommendation, count: " + mEarlyRecommendationTriggerTimes);
        sendNotification(
                mContext.getString(R.string.battery_replacement_notify_title),
                Html.fromHtml(
                        mContext.getString(R.string.battery_replacement_early_notify_des),
                        Html.FROM_HTML_MODE_LEGACY),
                true);
        mEarlyRecommendationTriggerTimes++;
        getSharedPreferences()
                .edit()
                .putInt(KEY_EARLY_RECOMMENDATION_TRIGGER_TIMES, mEarlyRecommendationTriggerTimes)
                .apply();
    }

    private SharedPreferences getSharedPreferences() {
        if (mSharedPreferences == null) {
            mSharedPreferences =
                    mContext.getApplicationContext()
                            .getSharedPreferences(
                                    "battery_replacement_shared_prefs", Context.MODE_PRIVATE);
        }
        return mSharedPreferences;
    }

    private void sendNotification(String title, CharSequence text, boolean showCycleCountAction) {
        NotificationManager notificationManager =
                mContext.getSystemService(NotificationManager.class);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(mContext, "BAT")
                        .setSmallIcon(R.drawable.ic_battery_alert_fill)
                        .setColor(
                                Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorError))
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(
                                PowerUtils.createBatterySettingsPendingIntentAsUser(mContext))
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                        .setSilent(true)
                        .addAction(
                                0,
                                mContext.getString(R.string.battery_health_notify_learn_more),
                                PowerUtils.createHelpArticlePendingIntentAsUser(
                                        R.string.battery_replacement_notify_help_url, mContext));
        if (showCycleCountAction) {
            builder.addAction(
                    0,
                    mContext.getString(R.string.battery_management_action),
                    PowerUtils.createBatterySettingsPendingIntentAsUser(mContext));
        }
        PowerUtils.overrideNotificationAppName(mContext, builder);
        notificationManager.notifyAsUser(
                "battery_replacement",
                R.string.battery_replacement_notify_title,
                builder.build(),
                UserHandle.CURRENT);
    }
}
