package com.example.remotecontrol.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.remotecontrol.model.DeviceBrand
import com.example.remotecontrol.model.RemoteDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceDiscoveryManager(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val sharedPrefs = context.getSharedPreferences("device_nicknames", Context.MODE_PRIVATE)
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    // IMPORTANTE: Tipos de serviço sem o ponto final para o discoverServices
    private val serviceProtocols = mapOf(
        "_androidtvremote2._tcp" to DeviceBrand.ANDROID_TV,
        "_androidtvremote._tcp" to DeviceBrand.ANDROID_TV,
        "_androidtv._tcp" to DeviceBrand.ANDROID_TV,
        "_googlecast._tcp" to DeviceBrand.ANDROID_TV,
        "_samsung-tv-remotely._tcp" to DeviceBrand.SAMSUNG,
        "_airplay._tcp" to DeviceBrand.SAMSUNG, // Muitas Samsung anunciam AirPlay
        "_spotify-connect._tcp" to DeviceBrand.SAMSUNG,
        "_webos._tcp" to DeviceBrand.LG,
        "_dlna._tcp" to DeviceBrand.GENERIC_DLNA
    )

    private val activeListeners = mutableMapOf<String, NsdManager.DiscoveryListener>()

    private fun createDiscoveryListener(brand: DeviceBrand) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d("Discovery", "Busca iniciada para: $regType")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d("Discovery", "Serviço encontrado: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
            
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    Log.e("Discovery", "Falha ao resolver ${si.serviceName}: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: ""
                    if (host.isNotEmpty()) {
                        Log.i("Discovery", "RESOLVIDO: ${info.serviceName} no IP $host")
                        val nickname = sharedPrefs.getString(host, null)
                        val device = RemoteDevice(
                            id = host,
                            originalName = info.serviceName,
                            host = host,
                            port = info.port,
                            serviceType = info.serviceType,
                            nickname = nickname,
                            brand = inferBrand(info, brand)
                        )
                        updateDeviceList(device)
                    }
                }
            })
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d("Discovery", "Serviço perdido: ${serviceInfo.serviceName}")
            removeDevice(serviceInfo.serviceName)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d("Discovery", "Busca parada: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("Discovery", "Falha no início: $serviceType -> $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("Discovery", "Falha ao parar: $serviceType -> $errorCode")
        }
    }

    private fun inferBrand(info: NsdServiceInfo, defaultBrand: DeviceBrand): DeviceBrand {
        val name = info.serviceName.lowercase()
        return when {
            name.contains("samsung") -> DeviceBrand.SAMSUNG
            name.contains("lg") || name.contains("webos") -> DeviceBrand.LG
            name.contains("fire") || name.contains("sala") || name.contains("aft") -> DeviceBrand.ANDROID_TV
            name.contains("mi") || name.contains("mitv") -> DeviceBrand.ANDROID_TV
            else -> defaultBrand
        }
    }

    fun startDiscovery() {
        stopDiscovery() 
        _devices.value = emptyList()

        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("RemoteControlDiscovery").apply {
                setReferenceCounted(true)
            }
        }
        
        try {
            multicastLock?.acquire()
            Log.d("Discovery", "Multicast Lock Ativo")
        } catch (e: Exception) {
            Log.e("Discovery", "Erro no Multicast Lock", e)
        }

        serviceProtocols.forEach { (type, brand) ->
            val listener = createDiscoveryListener(brand)
            activeListeners[type] = listener
            nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stopDiscovery() {
        activeListeners.forEach { (type, listener) ->
            try {
                nsdManager.stopServiceDiscovery(listener)
                Log.d("Discovery", "Parando busca para $type")
            } catch (e: Exception) {
                Log.w("Discovery", "Erro ao parar busca para $type: ${e.message}")
            }
        }
        activeListeners.clear()

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d("Discovery", "Multicast Lock Liberado")
            }
        } catch (e: Exception) {
            Log.e("Discovery", "Erro ao liberar Multicast", e)
        }
    }

    fun saveNickname(host: String, nickname: String) {
        sharedPrefs.edit().putString(host, nickname).apply()
        val currentList = _devices.value.map {
            if (it.host == host) it.copy(nickname = nickname) else it
        }
        _devices.value = currentList
    }

    private fun updateDeviceList(device: RemoteDevice) {
        val currentList = _devices.value.toMutableList()
        val index = currentList.indexOfFirst { it.host == device.host }
        if (index == -1) {
            currentList.add(device)
            Log.d("Discovery", "Novo dispositivo listado: ${device.originalName}")
        } else {
            val existing = currentList[index]
            currentList[index] = device.copy(nickname = existing.nickname ?: device.nickname)
        }
        _devices.value = currentList
    }

    private fun removeDevice(name: String) {
        val currentList = _devices.value.toMutableList()
        currentList.removeAll { it.originalName == name }
        _devices.value = currentList
    }
}
