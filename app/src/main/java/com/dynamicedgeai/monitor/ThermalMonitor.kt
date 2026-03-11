package com.dynamicedgeai.monitor

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart

class ThermalMonitor(private val context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun observeThermalState(): Flow<ThermalState> = callbackFlow {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                trySend(mapStatus(status))
            }
            powerManager.addThermalStatusListener(listener)
            awaitClose {
                powerManager.removeThermalStatusListener(listener)
            }
        } else {
            // No listener for pre-Q, just emit once
            trySend(ThermalState.NORMAL)
            awaitClose()
        }
    }.onStart {
        emit(getCurrentStatus())
    }

    private fun getCurrentStatus(): ThermalState {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mapStatus(powerManager.currentThermalStatus)
        } else {
            ThermalState.NORMAL
        }
    }

    private fun mapStatus(status: Int): ThermalState {
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalState.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
            else -> ThermalState.NORMAL
        }
    }
}
