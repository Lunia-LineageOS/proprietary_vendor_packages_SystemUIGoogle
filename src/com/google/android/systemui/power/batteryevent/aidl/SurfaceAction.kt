package com.google.android.systemui.power.batteryevent.aidl

import android.os.Parcel
import android.os.Parcelable
import android.util.Log

enum class SurfaceAction(val actionName: String) : Parcelable {
    UNKNOWN("unknown"),
    POSITIVE("positive"),
    NEGATIVE("negative"),
    NEUTRAL("neutral");

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(actionName)
    }

    companion object {
        @JvmField
        val CREATOR =
            object : Parcelable.Creator<SurfaceAction> {
                override fun createFromParcel(parcel: Parcel?): SurfaceAction {
                    val name = parcel?.readString()
                    if (name == null) {
                        Log.w("SurfaceAction", "null parameter for createFromParcel")
                        return UNKNOWN
                    }
                    return entries.firstOrNull { it.actionName == name } ?: UNKNOWN
                }

                override fun newArray(size: Int): Array<SurfaceAction?> = arrayOfNulls(size)
            }
    }
}
