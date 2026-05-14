package com.example.remotecontrol.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.remotecontrol.model.RemoteDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Cliente para TVs Samsung Tizen (modelos 2016+).
 *
 * Conecta via WebSocket Secure na porta 8002 (path /api/v2/channels/samsung.remote.control).
 * Na primeira conexão a TV mostra um pop-up "Permitir/Negar"; ao permitir, devolve um token
 * que persistimos em SharedPreferences para reusar sem novo pareamento.
 */
class SamsungRemoteClient(
    private val context: Context,
    private val device: RemoteDevice
) : RemoteClient {

    override var onSecretRequired: (() -> Unit)? = null
    override var onConnected: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val httpClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived WS
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun connect(forcePairing: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val nameB64 = Base64.encodeToString(CLIENT_NAME.toByteArray(), Base64.NO_WRAP)
            val token = if (forcePairing) null else prefs.getString(tokenKey(device.host), null)
            val tokenParam = token?.let { "&token=$it" } ?: ""
            val url = "wss://${device.host}:$PORT/api/v2/channels/samsung.remote.control" +
                "?name=$nameB64$tokenParam"
            Log.d(TAG, ">>> Samsung connect: $url")

            val opened = CompletableDeferred<Boolean>()

            val request = Request.Builder().url(url).build()
            val outer = this@SamsungRemoteClient
            val newWs = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "--- WS aberto (HTTP ${response.code})")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== outer.webSocket) return // ignora listeners de conexões antigas
                    Log.d(TAG, "<<< $text")
                    handleIncoming(text, opened)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== outer.webSocket) return
                    Log.e(TAG, "WS falhou (HTTP ${response?.code}): ${t.message}", t)
                    if (!opened.isCompleted) opened.complete(false)
                    onError?.invoke(t.message ?: "Falha WebSocket")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WS closing: $code $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WS closed: $code $reason")
                    if (webSocket === outer.webSocket) outer.webSocket = null
                }
            })
            webSocket = newWs

            val ok = withTimeoutOrNull(15_000) { opened.await() } == true
            if (!ok) {
                Log.e(TAG, "Timeout aguardando ms.channel.connect")
                onError?.invoke("Sem resposta da Samsung (autorização negada na TV?)")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao conectar Samsung", e)
            onError?.invoke(e.message ?: "Erro desconhecido")
            false
        }
    }

    private fun handleIncoming(text: String, opened: CompletableDeferred<Boolean>) {
        try {
            val json = JSONObject(text)
            when (json.optString("event")) {
                "ms.channel.connect" -> {
                    val data = json.optJSONObject("data")
                    data?.optString("token")?.takeIf { it.isNotEmpty() }?.let {
                        Log.i(TAG, ">>> Token recebido, persistindo")
                        prefs.edit().putString(tokenKey(device.host), it).apply()
                    }
                    Log.i(TAG, ">>> Canal Samsung pronto")
                    onConnected?.invoke()
                    if (!opened.isCompleted) opened.complete(true)
                }
                "ms.channel.unauthorized" -> {
                    Log.e(TAG, "!!! Acesso negado pela TV")
                    prefs.edit().remove(tokenKey(device.host)).apply()
                    onError?.invoke("Permissão negada na TV")
                    if (!opened.isCompleted) opened.complete(false)
                }
                "ms.channel.timeOut" -> {
                    Log.e(TAG, "!!! Timeout de autorização")
                    onError?.invoke("Tempo esgotado para autorizar na TV")
                    if (!opened.isCompleted) opened.complete(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mensagem WS inválida", e)
        }
    }

    /** Samsung não usa PIN — a autorização é via popup na TV. */
    override suspend fun sendPin(pin: String): Boolean = true

    override fun sendKey(keyCode: Int) {
        val samsungKey = mapKey(keyCode) ?: run {
            Log.w(TAG, "Tecla não mapeada para Samsung: $keyCode")
            return
        }
        val msg = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", samsungKey)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        }.toString()
        Log.d(TAG, ">>> sendKey $samsungKey")
        webSocket?.send(msg)
    }

    /** Lançamento de app por URL não é suportado via remote.control. */
    override fun launchApp(url: String) {
        Log.w(TAG, "launchApp não suportado em Samsung Tizen via WS de controle remoto")
    }

    override fun disconnect() {
        try { webSocket?.close(1000, "client disconnect") } catch (_: Exception) {}
        webSocket = null
    }

    private fun tokenKey(host: String) = "samsung_token_$host"

    /** Traduz KeyEvent Android -> string KEY_* Samsung. */
    private fun mapKey(keyCode: Int): String? = when (keyCode) {
        UniversalKey.POWER -> "KEY_POWER"
        UniversalKey.HOME -> "KEY_HOME"
        UniversalKey.BACK -> "KEY_RETURN"
        UniversalKey.UP -> "KEY_UP"
        UniversalKey.DOWN -> "KEY_DOWN"
        UniversalKey.LEFT -> "KEY_LEFT"
        UniversalKey.RIGHT -> "KEY_RIGHT"
        UniversalKey.OK -> "KEY_ENTER"
        UniversalKey.VOLUME_UP -> "KEY_VOLUP"
        UniversalKey.VOLUME_DOWN -> "KEY_VOLDOWN"
        UniversalKey.MUTE -> "KEY_MUTE"
        UniversalKey.PLAY_PAUSE -> "KEY_PLAY_BACK"
        UniversalKey.SETTINGS -> "KEY_MENU"
        else -> null
    }

    companion object {
        private const val TAG = "SamsungClient"
        private const val PORT = 8002
        private const val CLIENT_NAME = "Universal Remote"
        private const val PREFS = "samsung_remote"
    }
}
