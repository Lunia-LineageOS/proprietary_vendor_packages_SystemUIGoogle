package com.google.android.systemui.power;

import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DumpUtils {

    public static final Uri PROVIDER_URI =
            new Uri.Builder()
                    .scheme("content")
                    .authority("com.google.android.apps.turbo.battery_health_provider")
                    .build();

    private DumpUtils() {}

    public static String toReadableDateTime(long timeMillis) {
        return new SimpleDateFormat("MMM dd,yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date(timeMillis));
    }
}
