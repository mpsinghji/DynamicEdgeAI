package com.dynamicedgeai.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

class NetworkMonitor(private val context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun observeNetworkQuality(): Flow<NetworkQuality> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentQuality())
            }

            override fun onLost(network: Network) {
                trySend(NetworkQuality.POOR)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(getCurrentQuality())
            }
            
            override fun onUnavailable() {
                trySend(NetworkQuality.POOR)
            }
        }

        // registerDefaultNetworkCallback tracks the current default network for the device
        connectivityManager.registerDefaultNetworkCallback(callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.onStart {
        emit(getCurrentQuality())
    }.distinctUntilChanged()

    private fun getCurrentQuality(): NetworkQuality {
        val network = connectivityManager.activeNetwork ?: return NetworkQuality.POOR
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkQuality.POOR

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        // If not validated, the system doesn't have "real" internet access yet
        if (!hasInternet || !isValidated) return NetworkQuality.POOR

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkQuality.EXCELLENT
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val speed = capabilities.linkDownstreamBandwidthKbps
                if (speed > 15000) NetworkQuality.EXCELLENT 
                else if (speed > 5000) NetworkQuality.GOOD 
                else NetworkQuality.MODERATE
            }
            else -> NetworkQuality.POOR
        }
    }
}
