package com.example.remotecontrol

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.remotecontrol.discovery.DeviceDiscoveryManager
import com.example.remotecontrol.ui.MainScreen
import com.example.remotecontrol.ui.theme.RemoteControlTheme

class MainActivity : ComponentActivity() {
    private lateinit var discoveryManager: DeviceDiscoveryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate started")
        discoveryManager = DeviceDiscoveryManager(applicationContext)
        
        try {
            setContent {
                RemoteControlTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen(discoveryManager = discoveryManager)
                    }
                }
            }
            Log.d("MainActivity", "setContent finished")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate", e)
        }
    }

    override fun onStart() {
        super.onStart()
        discoveryManager.startDiscovery()
    }

    override fun onStop() {
        super.onStop()
        discoveryManager.stopDiscovery()
    }
}
