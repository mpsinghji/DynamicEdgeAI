package com.dynamicedgeai.engine

import com.dynamicedgeai.monitor.DeviceState
import com.dynamicedgeai.monitor.NetworkQuality
import com.dynamicedgeai.monitor.ThermalState

/**
 * Decision Engine responsible for selecting the AI execution strategy
 * based on real-time device resource metrics and state.
 */
class DecisionEngine {

    // --- TEST OVERRIDE AREA ---
    private val TEST_OVERRIDE = "OFF"
    // --------------------------

    private var lastStrategy: Strategy = Strategy.LOCAL

    /**
     * Determines the execution strategy based on device conditions.
     * Implements hysteresis for RAM thresholds to prevent decision flickering.
     */
    fun determineStrategy(state: DeviceState): StrategyDetail {

        // 0. Test Override (Highest Priority for debugging)
        if (TEST_OVERRIDE != "OFF") {
            val forcedStrategy = if (TEST_OVERRIDE == "CLOUD") Strategy.CLOUD else Strategy.LOCAL
            return StrategyDetail(forcedStrategy, "FORCED TEST MODE: $TEST_OVERRIDE")
        }

        // Calculation: 523MB / 2048MB = ~0.25 (25%)
        val ramRatio = if (state.totalRam > 0) state.ramAvailable.toDouble() / state.totalRam else 1.0
        val isNetworkUsable = state.networkQuality != NetworkQuality.POOR &&
                state.networkQuality != NetworkQuality.UNKNOWN

        // 1. Thermal Safety (High Priority)
        if (state.thermalState == ThermalState.SEVERE ||
            state.thermalState == ThermalState.CRITICAL ||
            state.thermalState == ThermalState.EMERGENCY) {

            return if (isNetworkUsable) {
                lastStrategy = Strategy.CLOUD
                StrategyDetail(Strategy.CLOUD, "Device temperature high (Offloading to Cloud)")
            } else {
                lastStrategy = Strategy.LOCAL
                StrategyDetail(Strategy.LOCAL, "Thermal high but network unavailable (Forced Local)")
            }
        }

        // 2. RAM Availability with Hysteresis (Fixed for 2GB Device)
        // We trigger CLOUD if RAM < 30% (Approx 614MB).
        // Your current 523MB (25%) will now trigger CLOUD.
        if (lastStrategy == Strategy.LOCAL) {
            if (ramRatio < 0.30) {
                if (isNetworkUsable) {
                    lastStrategy = Strategy.CLOUD
                    return StrategyDetail(Strategy.CLOUD, "RAM low (${(ramRatio * 100).toInt()}%). Offloading to Cloud.")
                }
            }
        } else {
            // Stay in CLOUD until RAM recovers to > 40% (Approx 820MB)
            if (ramRatio < 0.40) {
                return if (isNetworkUsable) {
                    StrategyDetail(Strategy.CLOUD, "Memory recovering (Current: ${(ramRatio * 100).toInt()}%)")
                } else {
                    lastStrategy = Strategy.LOCAL
                    StrategyDetail(Strategy.LOCAL, "Memory low but network lost (Forced Local)")
                }
            }
        }

        // 3. Network condition check (Safety catch)
        if (!isNetworkUsable) {
            lastStrategy = Strategy.LOCAL
            return StrategyDetail(Strategy.LOCAL, "Network unavailable or too slow for cloud")
        }

        // 4. Default Preference
        lastStrategy = Strategy.LOCAL
        return StrategyDetail(Strategy.LOCAL, "Optimal conditions for on-device execution")
    }
}