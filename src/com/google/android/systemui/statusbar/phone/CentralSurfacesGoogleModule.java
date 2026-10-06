package com.google.android.systemui.statusbar.phone;

import android.app.AlarmManager;
import android.content.Context;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;

import com.google.android.systemui.power.batteryhealth.HealthManager;
import com.google.android.systemui.res.R;

import dagger.Module;
import dagger.Provides;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.CoroutineScope;

import java.util.Optional;

@Module
public interface CentralSurfacesGoogleModule {
    /**
     * Provides a HealthManager for CentralSurfacesGoogle. Stock constructs this one separately from
     * the injected HealthManager singleton.
     */
    @Provides
    @SysUISingleton
    static Optional<HealthManager> provideHealthManager(
            Context context,
            AlarmManager alarmManager,
            BroadcastDispatcher broadcastDispatcher,
            @Background CoroutineDispatcher bgDispatcher,
            @Application CoroutineScope applicationScope) {
        return context.getResources().getBoolean(R.bool.config_battery_index_enabled)
                ? Optional.of(
                        new HealthManager(
                                context,
                                alarmManager,
                                broadcastDispatcher,
                                bgDispatcher,
                                applicationScope))
                : Optional.empty();
    }
}
