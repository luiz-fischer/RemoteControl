package com.example.remotecontrol.network

import com.example.remotecontrol.model.RemoteDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton compartilhado entre RemoteService (foreground) e RemoteViewModel.
 *
 * O service mantém o RemoteClient vivo enquanto a notificação estiver ativa;
 * o ViewModel observa o estado via os flows daqui.
 *
 * Ambos manipulam o mesmo cliente, então a UI pode parar de existir
 * (Activity destroyed) e o service continua mantendo o socket aberto.
 */
object RemoteConnectionHolder {
    enum class Status { IDLE, CONNECTING, PAIRING_REQUIRED, CONNECTED, ERROR }

    @Volatile var client: RemoteClient? = null
    @Volatile var device: RemoteDevice? = null

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun setStatus(s: Status) { _status.value = s }
    fun setError(msg: String?) { _errorMessage.value = msg }

    fun clear() {
        client?.disconnect()
        client = null
        device = null
        _status.value = Status.IDLE
        _errorMessage.value = null
    }
}
