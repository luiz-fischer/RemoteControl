package com.example.remotecontrol.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.remotecontrol.MainActivity
import com.example.remotecontrol.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service que mantém a conexão com a TV viva enquanto o usuário
 * estiver pareado. Sem isso, o Android mata o processo em background e
 * derruba o socket, forçando novo pareamento na próxima abertura.
 *
 * O service não cria o RemoteClient — apenas detém uma referência ao que
 * o ViewModel criou via RemoteConnectionHolder. Quando o usuário toca
 * "Desconectar" (action na notificação ou botão Voltar na UI), o service
 * libera o cliente e para a si mesmo.
 */
class RemoteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var autoStopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Recebido ACTION_STOP")
                RemoteConnectionHolder.clear()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "TV"
                startInForeground(deviceName)
                observeStatusForAutoStop()
            }
        }
        return START_STICKY
    }

    /**
     * Se a conexão entrar em ERROR e ficar nesse estado por mais de [AUTO_STOP_GRACE_MS],
     * o service se auto-desliga — não faz sentido manter notificação de "Conectado"
     * quando o stick foi desligado. Reconexão dentro da janela (volta pra CONNECTING/CONNECTED)
     * cancela o auto-stop.
     */
    private fun observeStatusForAutoStop() {
        scope.launch {
            RemoteConnectionHolder.status.collect { s ->
                if (s == RemoteConnectionHolder.Status.ERROR) {
                    if (autoStopJob?.isActive != true) {
                        Log.d(TAG, "Status=ERROR, agendando auto-stop em ${AUTO_STOP_GRACE_MS}ms")
                        autoStopJob = scope.launch {
                            delay(AUTO_STOP_GRACE_MS)
                            if (RemoteConnectionHolder.status.value == RemoteConnectionHolder.Status.ERROR) {
                                Log.d(TAG, "Auto-stop: stick não voltou, fechando service")
                                RemoteConnectionHolder.clear()
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    }
                } else {
                    autoStopJob?.cancel()
                    autoStopJob = null
                }
            }
        }
    }

    private fun startInForeground(deviceName: String) {
        createChannelIfNeeded()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RemoteService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Conectado: $deviceName")
            .setContentText("Toque para abrir o controle remoto")
            .setContentIntent(tapPi)
            .addAction(0, "Desconectar", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Conexão com TV",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mantém a conexão com a TV enquanto o app está em background"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.d(TAG, "Service destruído")
    }

    companion object {
        private const val TAG = "RemoteService"
        const val CHANNEL_ID = "remote_connection"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.remotecontrol.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val AUTO_STOP_GRACE_MS = 5_000L

        fun start(context: Context, deviceName: String) {
            val intent = Intent(context, RemoteService::class.java)
                .putExtra(EXTRA_DEVICE_NAME, deviceName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RemoteService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
