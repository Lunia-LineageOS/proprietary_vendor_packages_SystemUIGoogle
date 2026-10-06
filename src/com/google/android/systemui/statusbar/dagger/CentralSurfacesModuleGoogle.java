package com.google.android.systemui.statusbar.dagger;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.dagger.CentralSurfacesDependenciesModule;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.StatusBarNotificationPresenterModule;

import com.google.android.systemui.statusbar.phone.CentralSurfacesGoogle;
import com.google.android.systemui.statusbar.phone.CentralSurfacesGoogleModule;

import dagger.Binds;
import dagger.Module;

/** Dagger Module providing {@link CentralSurfacesGoogle}. */
@Module(
        includes = {
            CentralSurfacesDependenciesModule.class,
            CentralSurfacesGoogleModule.class,
            StatusBarNotificationPresenterModule.class
        })
public interface CentralSurfacesModuleGoogle {
    /** Provides our instance of CentralSurfaces which is considered optional. */
    @Binds
    @SysUISingleton
    CentralSurfaces bindsCentralSurfaces(CentralSurfacesGoogle impl);
}
