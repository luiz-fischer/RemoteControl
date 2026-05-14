package com.example.remotecontrol.model

enum class DeviceBrand {
    ANDROID_TV,
    SAMSUNG,
    LG,
    ROKU,
    GENERIC_DLNA,
    UNKNOWN
}

data class RemoteDevice(
    val id: String, 
    val originalName: String,
    val host: String,
    val port: Int,
    val serviceType: String = "",
    var nickname: String? = null,
    val brand: DeviceBrand = DeviceBrand.UNKNOWN
)
