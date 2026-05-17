package com.example.remotecontrol.network

import android.content.Context
import android.util.Log
import com.example.remotecontrol.model.RemoteDevice
import com.example.remotecontrol.proto.OuterMessage
import com.example.remotecontrol.proto.PairingConfiguration
import com.example.remotecontrol.proto.PairingOptions
import com.example.remotecontrol.proto.PairingRequest
import com.example.remotecontrol.proto.PairingSecret
import com.example.remotecontrol.proto.RemoteAppLinkLaunchRequest
import com.example.remotecontrol.proto.RemoteConfigure
import com.example.remotecontrol.proto.RemoteDeviceInfo
import com.example.remotecontrol.proto.RemoteDirection
import com.example.remotecontrol.proto.RemoteKeyInject
import com.example.remotecontrol.proto.RemoteMessage
import com.example.remotecontrol.proto.RemotePingResponse
import com.example.remotecontrol.proto.RemoteSetActive
import com.example.remotecontrol.security.KeyStoreManager
import com.google.protobuf.ByteString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class AndroidTvRemoteClient(context: Context, private val device: RemoteDevice) : RemoteClient {
    private val keyStoreManager = KeyStoreManager(context)
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private var heartbeatJob: Job? = null
    private var readerJob: Job? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeMutex = Mutex()

    private var pairingMode = false
    private var pairingAckDeferred: CompletableDeferred<Boolean>? = null

    override var onSecretRequired: (() -> Unit)? = null
    override var onConnected: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    override suspend fun connect(forcePairing: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, ">>> CONEXÃO INICIADA: ${device.host}:${if (forcePairing) PORT_PAIRING else PORT_COMMAND} (Pareamento: $forcePairing)")
            closeInternal()
            pairingMode = forcePairing

            val targetPort = if (forcePairing) PORT_PAIRING else PORT_COMMAND
            val sslContext = keyStoreManager.getSSLContext()
            val baseSocket = Socket().apply {
                connect(InetSocketAddress(device.host, targetPort), 5000)
            }
            val sslSocket = (sslContext.socketFactory
                .createSocket(baseSocket, device.host, targetPort, true) as SSLSocket).apply {
                soTimeout = if (forcePairing) 120000 else 30000
                startHandshake()
            }
            Log.d(TAG, "--- Handshake TLS OK")

            socket = sslSocket
            outputStream = sslSocket.outputStream
            inputStream = sslSocket.inputStream

            startReaderLoop()

            if (forcePairing) {
                sendPairingRequest()
            } else {
                Log.i(TAG, ">>> CANAL DE COMANDO PRONTO - Aguardando handshake da TV")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "!!! FALHA DE CONEXÃO", e)
            closeInternal()
            // Se falhou no canal de comando por cert desconhecido (TV ainda não pareada),
            // tentamos automaticamente o canal de pareamento.
            val certUnknown = e.message?.contains("CERTIFICATE_UNKNOWN", ignoreCase = true) == true
            if (!forcePairing && certUnknown) {
                Log.i(TAG, "--- TV não reconhece nosso cert. Trocando para canal de pareamento.")
                return@withContext connect(forcePairing = true)
            }
            onError?.invoke(e.message ?: "Erro desconhecido")
            false
        }
    }

    // No protocolo Android TV v2, é a TV que faz ping no cliente.
    // Não devemos enviar PingRequest pró-ativamente — a TV trata isso como
    // mensagem fora de protocolo e fecha a conexão com RemoteError.
    // Mantemos o método sem operação para preservar a chamada existente.
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
    }

    private fun startReaderLoop() {
        readerJob?.cancel()
        val modeAtStart = pairingMode
        readerJob = clientScope.launch {
            try {
                while (isActive) {
                    val body = readVarintFramedMessage() ?: break
                    if (pairingMode) handlePairingFrame(body) else handleRemoteFrame(body)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reader loop encerrado", e)
            }
            Log.d(TAG, "--- Reader loop terminado")
            // Se o socket de comandos cair, sinaliza erro pra UI poder reconectar.
            // (No modo de pareamento, o fim do reader é esperado após o SECRET_ACK.)
            if (!modeAtStart && socket != null) {
                onError?.invoke("Conexão perdida")
                closeInternal()
            }
        }
    }

    private fun readVarintFramedMessage(): ByteArray? {
        val size = readVarint() ?: return null
        if (size <= 0 || size > MAX_MSG) {
            Log.w(TAG, "Tamanho de frame inválido: $size")
            return null
        }
        return readFully(size)
    }

    private fun readVarint(): Int? {
        var result = 0
        var shift = 0
        while (shift < 32) {
            val b = try { inputStream?.read() ?: -1 } catch (e: Exception) { -1 }
            if (b < 0) return null
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return null
    }

    private fun readFully(length: Int): ByteArray? {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = try { inputStream?.read(buf, read, length - read) ?: -1 } catch (e: Exception) { -1 }
            if (n < 0) return null
            read += n
        }
        return buf
    }

    // ============================================================
    // PAREAMENTO (porta 6467)
    // ============================================================
    private fun handlePairingFrame(body: ByteArray) {
        val outer = try {
            OuterMessage.parseFrom(body)
        } catch (e: Exception) {
            Log.e(TAG, "Frame de pareamento inválido", e)
            return
        }

        val phase = when {
            outer.hasPairingRequestAck() -> "PAIRING_REQUEST_ACK"
            outer.hasOptions() -> "OPTIONS"
            outer.hasConfiguration() -> "CONFIGURATION"
            outer.hasConfigurationAck() -> "CONFIGURATION_ACK"
            outer.hasSecret() -> "SECRET"
            outer.hasSecretAck() -> "SECRET_ACK"
            else -> "UNKNOWN"
        }
        Log.i(TAG, "<<< PAREAMENTO: $phase status=${outer.status}")

        if (outer.status != OuterMessage.Status.STATUS_OK) {
            Log.e(TAG, "!!! TV retornou status de erro: ${outer.status}")
            pairingAckDeferred?.complete(false)
            onError?.invoke("Pareamento rejeitado: ${outer.status}")
            return
        }

        when {
            outer.hasPairingRequestAck() -> sendOptions()
            outer.hasOptions() -> sendConfiguration()
            outer.hasConfigurationAck() -> {
                Log.i(TAG, ">>> PIN ATIVO NA TV - aguardando usuário")
                onSecretRequired?.invoke()
            }
            outer.hasSecretAck() -> {
                Log.i(TAG, ">>> SECRET_ACK recebido - pareamento concluído")
                pairingAckDeferred?.complete(true)
            }
            else -> Log.w(TAG, "Mensagem de pareamento inesperada")
        }
    }

    private fun newOuterBuilder(): OuterMessage.Builder =
        OuterMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(OuterMessage.Status.STATUS_OK)

    private fun sendPairingRequest() {
        val req = PairingRequest.newBuilder()
            .setServiceName("atvremote")
            .setClientName(android.os.Build.MODEL)
            .build()
        sendFramed(newOuterBuilder().setPairingRequest(req).build().toByteArray())
    }

    private fun sendOptions() {
        val encoding = PairingOptions.Encoding.newBuilder()
            .setType(PairingOptions.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(6)
            .build()
        val opts = PairingOptions.newBuilder()
            .addInputEncodings(encoding)
            .setPreferredRole(PairingOptions.RoleType.ROLE_TYPE_INPUT)
            .build()
        sendFramed(newOuterBuilder().setOptions(opts).build().toByteArray())
    }

    private fun sendConfiguration() {
        val encoding = PairingOptions.Encoding.newBuilder()
            .setType(PairingOptions.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(6)
            .build()
        val config = PairingConfiguration.newBuilder()
            .setEncoding(encoding)
            .setClientRole(PairingOptions.RoleType.ROLE_TYPE_INPUT)
            .build()
        sendFramed(newOuterBuilder().setConfiguration(config).build().toByteArray())
    }

    override suspend fun sendPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanPin = pin.trim().uppercase()
            if (cleanPin.length != 6 || cleanPin.any { it !in '0'..'9' && it !in 'A'..'F' }) {
                Log.e(TAG, "PIN inválido (esperado 6 chars hex): '$pin'")
                return@withContext false
            }

            val session = (socket as? SSLSocket)?.session
            val serverCert = session?.peerCertificates?.get(0) as? X509Certificate
            val clientCert = keyStoreManager.getCertificate()
            if (serverCert == null || clientCert == null) {
                Log.e(TAG, "Certificados ausentes para gerar secret")
                return@withContext false
            }

            val hash = computeSecretHash(
                clientPub = clientCert.publicKey as RSAPublicKey,
                serverPub = serverCert.publicKey as RSAPublicKey,
                pinSuffix = cleanPin.substring(2)
            )

            val expectedFirstByte = cleanPin.substring(0, 2).toInt(16).toByte()
            Log.i(TAG, "PIN Checksum: esperado=${cleanPin.substring(0, 2)}, calculado=${String.format("%02X", hash[0])}")

            if (hash[0] != expectedFirstByte) {
                Log.e(TAG, "!!! Checksum do PIN não confere localmente. Cálculo matemático falhou.")
                onError?.invoke("Erro interno de criptografia (PIN mismatch)")
                return@withContext false
            }

            val deferred = CompletableDeferred<Boolean>()
            pairingAckDeferred = deferred

            val secret = PairingSecret.newBuilder()
                .setSecret(ByteString.copyFrom(hash))
                .build()
            sendFramed(newOuterBuilder().setSecret(secret).build().toByteArray())
            Log.d(TAG, "--- Secret enviado, aguardando SECRET_ACK")

            val ok = withTimeoutOrNull(10_000) { deferred.await() } == true
            pairingAckDeferred = null
            if (!ok) {
                Log.e(TAG, "Timeout/falha aguardando SECRET_ACK (TV rejeitou o PIN)")
                onError?.invoke("PIN rejeitado pela TV")
                return@withContext false
            }

            Log.i(TAG, ">>> Pareamento CONCLUÍDO. Mudando para canal de comando...")
            closeInternal()
            delay(1200)
            return@withContext connect(forcePairing = false)
        } catch (e: Exception) {
            Log.e(TAG, "Erro fatal no processamento do PIN", e)
            onError?.invoke("Erro técnico: ${e.message}")
            false
        }
    }

    private fun computeSecretHash(
        clientPub: RSAPublicKey,
        serverPub: RSAPublicKey,
        pinSuffix: String
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        
        fun updateWithBigInt(bi: java.math.BigInteger) {
            val bytes = bi.toByteArray()
            // No protocolo v2, enviamos o valor sem o byte de sinal se ele for apenas um preenchimento (00)
            val cleaned = if (bytes.size > 1 && bytes[0] == 0.toByte()) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
            md.update(cleaned)
        }

        updateWithBigInt(clientPub.modulus)
        updateWithBigInt(clientPub.publicExponent)
        updateWithBigInt(serverPub.modulus)
        updateWithBigInt(serverPub.publicExponent)
        md.update(pinSuffix.hexToBytes())

        return md.digest()
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex deve ter tamanho par: $this" }
        return ByteArray(length / 2) {
            ((Character.digit(this[it * 2], 16) shl 4)
                or Character.digit(this[it * 2 + 1], 16)).toByte()
        }
    }

    // ============================================================
    // COMANDOS (porta 6466)
    // ============================================================
    private fun handleRemoteFrame(body: ByteArray) {
        val msg = try {
            RemoteMessage.parseFrom(body)
        } catch (e: Exception) {
            Log.e(TAG, "Frame de comando inválido", e)
            return
        }
        
        when {
            msg.hasRemoteStart() -> {
                Log.i(TAG, "<<< RemoteStart: started=${msg.remoteStart.started}")
                if (msg.remoteStart.started) {
                    onConnected?.invoke()
                    startHeartbeat()
                }
            }
            msg.hasRemoteError() -> {
                Log.e(TAG, "<<< RemoteError: ${msg.remoteError.value} | Conteúdo completo: $msg")
                onError?.invoke("Erro no protocolo da TV")
                closeInternal()
            }
            msg.hasRemoteConfigure() -> {
                Log.d(TAG, "<<< RemoteConfigure (TV pediu config)")
                sendRemoteConfigure()
            }
            msg.hasRemoteSetActive() -> {
                Log.d(TAG, "<<< RemoteSetActive (TV enviou ativação)")
                sendRemoteSetActive()
            }
            msg.hasRemotePingRequest() -> {
                Log.d(TAG, "<<< RemotePingRequest")
                val pong = RemoteMessage.newBuilder()
                    .setRemotePingResponse(
                        RemotePingResponse.newBuilder().setVal1(msg.remotePingRequest.val1)
                    ).build()
                sendFramed(pong.toByteArray())
            }
            msg.hasRemotePingResponse() -> {
                Log.d(TAG, "<<< RemotePingResponse")
            }
            else -> Log.d(TAG, "<<< RemoteMessage não tratado: $msg")
        }
    }

    private fun sendRemoteConfigure() {
        val info = RemoteDeviceInfo.newBuilder()
            .setModel(android.os.Build.MODEL)
            .setVendor(android.os.Build.MANUFACTURER)
            .setUnknown1(1)
            .setPackageName("atvremote")
            .setAppVersion("1.0.0")
            .build()
        val msg = RemoteMessage.newBuilder()
            .setRemoteConfigure(
                RemoteConfigure.newBuilder().setCode1(ACTIVE_FEATURES).setDeviceInfo(info)
            ).build()
        sendFramed(msg.toByteArray())
    }

    private fun sendRemoteSetActive() {
        val msg = RemoteMessage.newBuilder()
            .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(ACTIVE_FEATURES))
            .build()
        sendFramed(msg.toByteArray())
    }

    override fun sendKey(keyCode: Int) {
        val msg = RemoteMessage.newBuilder()
            .setRemoteKeyInject(
                RemoteKeyInject.newBuilder()
                    .setKeyCode(keyCode)
                    .setDirection(RemoteDirection.SHORT_PRESS)
            ).build()
        sendFramed(msg.toByteArray())
    }

    override fun launchApp(url: String) {
        val msg = RemoteMessage.newBuilder()
            .setRemoteAppLinkLaunchRequest(
                RemoteAppLinkLaunchRequest.newBuilder().setAppLink(url)
            ).build()
        sendFramed(msg.toByteArray())
    }

    private fun sendFramed(bytes: ByteArray) {
        clientScope.launch {
            writeMutex.withLock {
                val out = outputStream
                if (out == null) {
                    Log.w(TAG, "sendFramed ignorado: outputStream nulo (socket fechado)")
                    return@withLock
                }
                try {
                    val header = encodeVarint(bytes.size)
                    // Junta header + body num único write para reduzir chance de
                    // SSLEngine fragmentar entre records (algumas TVs reclamam).
                    val full = ByteArray(header.size + bytes.size)
                    System.arraycopy(header, 0, full, 0, header.size)
                    System.arraycopy(bytes, 0, full, header.size, bytes.size)
                    out.write(full)
                    out.flush()
                    Log.v(TAG, ">>> Frame enviado (${bytes.size} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro de escrita", e)
                    onError?.invoke("Erro ao enviar comando")
                    closeInternal()
                }
            }
        }
    }

    private fun encodeVarint(value: Int): ByteArray {
        val out = ArrayList<Byte>(5)
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        out.add((v and 0x7F).toByte())
        return out.toByteArray()
    }

    override fun disconnect() = closeInternal()

    private fun closeInternal() {
        try { heartbeatJob?.cancel() } catch (_: Exception) {}
        try { readerJob?.cancel() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        // Desbloqueia quem estiver aguardando SECRET_ACK; resolve como falha.
        pairingAckDeferred?.takeIf { !it.isCompleted }?.complete(false)
        pairingAckDeferred = null
        socket = null
        inputStream = null
        outputStream = null
    }

    companion object {
        private const val TAG = "AndroidTvClient"
        private const val PORT_COMMAND = 6466
        private const val PORT_PAIRING = 6467
        private const val MAX_MSG = 1 shl 20
        private const val ACTIVE_FEATURES = 622
    }
}
