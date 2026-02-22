package com.dynamicedgeai.engine

import com.dynamicedgeai.monitor.DeviceState
import com.dynamicedgeai.monitor.NetworkQuality
import com.dynamicedgeai.monitor.ThermalState

class DecisionEngine {

    fun determineStrategy(state: DeviceState): ExecutionStrategy {
        // High Thermal Stress -> Offload to Cloud if network allows, else throttle to Lightweight
        if (state.thermalState == ThermalState.CRITICAL || state.thermalState == ThermalState.SEVERE) {
            return if (state.networkQuality == NetworkQuality.EXCELLENT || state.networkQuality == NetworkQuality.GOOD) {
                ExecutionStrategy.CLOUD_ONLY
            } else {
                ExecutionStrategy.LOCAL_LIGHTWEIGHT
            }
        }

        // Low Battery -> Offload to Cloud to save local processing energy if network is excellent
        if (state.batteryLevel < 15.0f && !state.isCharging) {
            return if (state.networkQuality == NetworkQuality.EXCELLENT) {
                ExecutionStrategy.CLOUD_ONLY
            } else {
                ExecutionStrategy.LOCAL_LIGHTWEIGHT
            }
        }

        // High CPU Usage or Low RAM -> Avoid Heavyweight local model
        if (state.cpuUsage > 80 || state.ramAvailable < 400) {
             return if (state.networkQuality == NetworkQuality.EXCELLENT || state.networkQuality == NetworkQuality.GOOD) {
                ExecutionStrategy.CLOUD_ONLY
            } else {
                ExecutionStrategy.LOCAL_LIGHTWEIGHT
            }
        }

        // Optimal Conditions: Good Battery, Normal Thermals, Available Resources
        if (state.batteryLevel > 30.0f && state.thermalState == ThermalState.NORMAL && state.ramAvailable > 800) {
            return ExecutionStrategy.LOCAL_HEAVYWEIGHT
        }

        // Default to local lightweight for balance
        return ExecutionStrategy.LOCAL_LIGHTWEIGHT
    }
}
