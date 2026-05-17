package com.example.remotecontrol.network

/**
 * Interface comum para clientes de TV.
 * Cada marca (Android TV v2, Samsung Tizen, LG webOS, ...) implementa esta interface
 * num arquivo separado.
 */
interface RemoteClient {
    var onSecretRequired: (() -> Unit)?
    var onConnected: (() -> Unit)?
    var onError: ((String) -> Unit)?

    suspend fun connect(forcePairing: Boolean = false): Boolean
    suspend fun sendPin(pin: String): Boolean
    fun sendKey(keyCode: Int)
    fun launchApp(url: String)
    fun disconnect()
}

/**
 * Mapa de teclas universal (KeyEvent Android) para tradução em cada implementação.
 * Constantes usadas na UI: 3=HOME, 4=BACK, 19=UP, 20=DOWN, 21=LEFT, 22=RIGHT, 23=ENTER/OK,
 * 24=VOLUME_UP, 25=VOLUME_DOWN, 26=POWER, 164=MUTE, 176=SETTINGS, 85=PLAY/PAUSE.
 */
object UniversalKey {
    const val HOME = 3
    const val BACK = 4
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val OK = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val PLAY_PAUSE = 85
    const val MUTE = 164
    const val SETTINGS = 176
}
