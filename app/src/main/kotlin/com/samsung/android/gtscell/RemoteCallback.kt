package com.samsung.android.gtscell

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * The callback Good Lock's share format ("GTS") hands a provider with `set_item`. The provider reads it from the
 * request by this exact class name, so the name and the package are Samsung's and not ours to choose; on the
 * wire it is one binder, which answers `com.samsung.android.gtscell.IRemoteCallback`. Read off LockStar 9.0.
 */
class RemoteCallback(
    private val binder: IBinder,
) : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(
        parcel: Parcel,
        flags: Int,
    ) {
        parcel.writeStrongBinder(binder)
    }

    companion object {
        const val INTERFACE = "com.samsung.android.gtscell.IRemoteCallback"

        @JvmField
        val CREATOR =
            object : Parcelable.Creator<RemoteCallback> {
                override fun createFromParcel(parcel: Parcel) = RemoteCallback(parcel.readStrongBinder())

                override fun newArray(size: Int) = arrayOfNulls<RemoteCallback>(size)
            }
    }
}
