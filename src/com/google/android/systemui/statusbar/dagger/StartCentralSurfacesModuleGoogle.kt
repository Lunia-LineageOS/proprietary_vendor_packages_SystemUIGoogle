package com.google.android.systemui.statusbar.dagger

import com.android.systemui.CoreStartable
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.google.android.systemui.statusbar.phone.CentralSurfacesGoogle
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
interface StartCentralSurfacesModuleGoogle {
    /** Start the CentralSurfaces */
    @Binds
    @IntoMap
    @ClassKey(CentralSurfaces::class)
    abstract fun bindsCentralSurfaces(centralSurfaces: CentralSurfacesGoogle): CoreStartable
}
