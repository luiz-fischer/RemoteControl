package com.example.remotecontrol.network

import android.content.Context
import com.example.remotecontrol.model.DeviceBrand
import com.example.remotecontrol.model.RemoteDevice

object RemoteClientFactory {
    fun create(context: Context, device: RemoteDevice): RemoteClient =
        when (device.brand) {
            DeviceBrand.SAMSUNG -> SamsungRemoteClient(context, device)
            DeviceBrand.ANDROID_TV -> AndroidTvRemoteClient(context, device)
            // LG e demais marcas caem no Android TV por padrão até termos implementação dedicada.
            else -> AndroidTvRemoteClient(context, device)
        }
}
