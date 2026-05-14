package com.example.remotecontrol.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.remotecontrol.model.RemoteDevice
import com.example.remotecontrol.network.RemoteClientFactory
import com.example.remotecontrol.network.RemoteConnectionHolder
import com.example.remotecontrol.network.RemoteService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Mantido como typealias para preservar uso na UI (`ConnectionStatus.CONNECTED` etc). */
typealias ConnectionStatus = RemoteConnectionHolder.Status

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private var connectJob: Job? = null

    // Status e erro vêm direto do holder (compartilhado com o foreground service),
    // então a UI continua coerente mesmo se a Activity for recriada.
    val status: StateFlow<RemoteConnectionHolder.Status> = RemoteConnectionHolder.status
    val errorMessage: StateFlow<String?> = RemoteConnectionHolder.errorMessage

    fun setDevice(device: RemoteDevice, forcePairing: Boolean = false) {
        connectJob?.cancel()
        RemoteConnectionHolder.client?.disconnect()
        RemoteConnectionHolder.client = null

        connectJob = viewModelScope.launch {
            Log.d(TAG, "--- Conectando a ${device.originalName} (force=$forcePairing)")
            RemoteConnectionHolder.setStatus(RemoteConnectionHolder.Status.CONNECTING)
            RemoteConnectionHolder.setError(null)
            RemoteConnectionHolder.device = device

            val app = getApplication<Application>()
            val newClient = RemoteClientFactory.create(app, device).also { c ->
                c.onSecretRequired = {
                    RemoteConnectionHolder.setStatus(RemoteConnectionHolder.Status.PAIRING_REQUIRED)
                }
                c.onConnected = {
                    if (RemoteConnectionHolder.client === c) {
                        RemoteConnectionHolder.setStatus(RemoteConnectionHolder.Status.CONNECTED)
                        // Sobe o foreground service para manter a conexão viva.
                        RemoteService.start(app, device.nickname ?: device.originalName)
                    }
                }
                c.onError = { msg ->
                    if (RemoteConnectionHolder.client === c) {
                        RemoteConnectionHolder.setStatus(RemoteConnectionHolder.Status.ERROR)
                        RemoteConnectionHolder.setError(msg)
                    }
                }
            }
            RemoteConnectionHolder.client = newClient

            val ok = newClient.connect(forcePairing)
            if (!ok && RemoteConnectionHolder.client === newClient &&
                RemoteConnectionHolder.status.value == RemoteConnectionHolder.Status.CONNECTING
            ) {
                RemoteConnectionHolder.setStatus(RemoteConnectionHolder.Status.ERROR)
            }
        }
    }

    fun submitPin(pin: String) {
        viewModelScope.launch {
            Log.d(TAG, "--- Submetendo PIN")
            RemoteConnectionHolder.setStatus(RemoteConnectionHolder.Status.CONNECTING)
            val ok = RemoteConnectionHolder.client?.sendPin(pin) == true
            if (!ok) RemoteConnectionHolder.setStatus(RemoteConnectionHolder.Status.ERROR)
        }
    }

    fun onKeyPress(keyCode: Int) {
        val s = RemoteConnectionHolder.status.value
        Log.d(TAG, "onKeyPress($keyCode) status=$s")
        if (s == RemoteConnectionHolder.Status.CONNECTED) {
            RemoteConnectionHolder.client?.sendKey(keyCode)
                ?: Log.w(TAG, "client é null")
        }
    }

    /**
     * Envia uma string caractere a caractere via keyCodes Android.
     * Útil para preencher campos de busca depois do reconhecimento de voz.
     * Letras a-z → KEYCODE_A (29) + offset. Dígitos 0-9 → KEYCODE_0 (7) + offset. Espaço → 62.
     * Caracteres não mapeados são ignorados.
     */
    fun sendText(text: String) {
        val s = RemoteConnectionHolder.status.value
        if (s != RemoteConnectionHolder.Status.CONNECTED) {
            Log.w(TAG, "sendText: não conectado (status=$s)")
            return
        }
        val client = RemoteConnectionHolder.client ?: run {
            Log.w(TAG, "sendText: client é null"); return
        }
        viewModelScope.launch {
            for (c in text.lowercase()) {
                val key = when (c) {
                    in 'a'..'z' -> 29 + (c - 'a')
                    in '0'..'9' -> 7 + (c - '0')
                    ' ' -> 62
                    else -> -1
                }
                if (key > 0) {
                    client.sendKey(key)
                    kotlinx.coroutines.delay(40) // dá tempo do stick processar cada tecla
                }
            }
        }
    }

    fun launchApp(url: String) {
        val s = RemoteConnectionHolder.status.value
        if (s == RemoteConnectionHolder.Status.CONNECTED) {
            RemoteConnectionHolder.client?.launchApp(url)
        }
    }

    /** Desconecta totalmente e desliga o foreground service. */
    fun resetStatus() {
        connectJob?.cancel()
        connectJob = null
        RemoteService.stop(getApplication())
        RemoteConnectionHolder.clear()
    }

    fun clearError() {
        RemoteConnectionHolder.setError(null)
    }

    private companion object { const val TAG = "ViewModel" }
}
