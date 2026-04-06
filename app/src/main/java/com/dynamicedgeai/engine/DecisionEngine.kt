package com.dynamicedgeai.engine

import com.dynamicedgeai.monitor.DeviceState
import com.dynamicedgeai.monitor.NetworkQuality
import com.dynamicedgeai.monitor.ThermalState

class DecisionEngine {

    private val TEST_OVERRIDE = "OFF"
    private var lastStrategy: Strategy = Strategy.LOCAL

    /**
     * Determines the execution strategy.
     * Uses Absolute Thresholds for better compatibility with high-RAM devices.
     */
    fun determineStrategy(state: DeviceState): StrategyDetail {

        if (TEST_OVERRIDE != "OFF") {
            val forcedStrategy = if (TEST_OVERRIDE == "CLOUD") Strategy.CLOUD else Strategy.LOCAL
            return StrategyDetail(forcedStrategy, "FORCED TEST MODE: $TEST_OVERRIDE")
        }

        val isNetworkUsable = state.networkQuality != NetworkQuality.POOR &&
                state.networkQuality != NetworkQuality.UNKNOWN

        // 1. Thermal Safety
        if (state.thermalState == ThermalState.SEVERE ||
            state.thermalState == ThermalState.CRITICAL ||
            state.thermalState == ThermalState.EMERGENCY) {

            return if (isNetworkUsable) {
                lastStrategy = Strategy.CLOUD
                StrategyDetail(Strategy.CLOUD, "Thermal High: Offloading to Cloud")
            } else {
                lastStrategy = Strategy.LOCAL
                StrategyDetail(Strategy.LOCAL, "Thermal High but Network Weak: Forced Local")
            }
        }

        // 2. RAM Availability (Absolute Thresholds)
        // High RAM Optimization: If free RAM > 2GB, ignore minor thermal/network issues and stay LOCAL
        val freeRam = state.ramAvailable
        
        if (freeRam > 2048) {
            lastStrategy = Strategy.LOCAL
            return StrategyDetail(Strategy.LOCAL, "Plentiful RAM ($freeRam MB): On-Device Priority")
        }

        if (lastStrategy == Strategy.LOCAL) {
            if (freeRam < 600) {
                if (isNetworkUsable) {
                    lastStrategy = Strategy.CLOUD
                    return StrategyDetail(Strategy.CLOUD, "RAM Low ($freeRam MB): Offloading to Cloud")
                }
            }
        } else {
            if (freeRam < 900) {
                return if (isNetworkUsable) {
                    StrategyDetail(Strategy.CLOUD, "Memory Recovering ($freeRam MB)")
                } else {
                    lastStrategy = Strategy.LOCAL
                    StrategyDetail(Strategy.LOCAL, "Memory Low but Network Weak: Forced Local")
                }
            }
        }

        if (!isNetworkUsable) {
            lastStrategy = Strategy.LOCAL
            return StrategyDetail(Strategy.LOCAL, "Network Unavailable: Forced Local")
        }

        lastStrategy = Strategy.LOCAL
        return StrategyDetail(Strategy.LOCAL, "Optimal resources ($freeRam MB): On-Device")
    }
}
