package com.dynamicedgeai.engine

import com.dynamicedgeai.monitor.DeviceState
import com.dynamicedgeai.monitor.NetworkQuality
import com.dynamicedgeai.monitor.ThermalState

class DecisionEngine {

    // --- TEST OVERRIDE AREA ---
    // Set to "CLOUD_HEAVY", "HYBRID", "LOCAL_LIGHTWEIGHT", or "OFF"
    private val TEST_MODE = "OFF" 
    // --------------------------

    fun determineStrategyDetail(state: DeviceState, avgCloudLatency: String = "N/A"): StrategyDetail {
        // If test mode is active, bypass all logic
        if (TEST_MODE != "OFF") {
            return when(TEST_MODE) {
                "CLOUD_HEAVY" -> StrategyDetail(
                    mode = ExecutionStrategy.CLOUD_HEAVY,
                    modelName = "TEST: Gemini API",
                    reason = "Forced Test Mode: CLOUD_HEAVY",
                    latency = avgCloudLatency,
                    networkUsed = "Yes"
                )
                "HYBRID" -> StrategyDetail(
                    mode = ExecutionStrategy.HYBRID,
                    modelName = "TEST: DeepSeek + Gemini",
                    reason = "Forced Test Mode: HYBRID",
                    latency = avgCloudLatency,
                    networkUsed = "Yes"
                )
                else -> StrategyDetail(
                    mode = ExecutionStrategy.LOCAL_LIGHTWEIGHT,
                    modelName = "TEST: DeepSeek Lite",
                    reason = "Forced Test Mode: LOCAL_LIGHTWEIGHT",
                    latency = avgCloudLatency,
                    networkUsed = "No"
                )
            }
        }

        val networkDesc = when(state.networkQuality) {
            NetworkQuality.EXCELLENT -> "excellent"
            NetworkQuality.GOOD -> "strong"
            NetworkQuality.MODERATE -> "stable"
            else -> "weak"
        }

        val isNetworkFast = state.networkQuality == NetworkQuality.EXCELLENT || state.networkQuality == NetworkQuality.GOOD
        val isNetworkUsable = isNetworkFast || state.networkQuality == NetworkQuality.MODERATE
        
        // Hardware Agnostic RAM check (Low = < 15% available)
        val ramRatio = if (state.totalRam > 0) state.ramAvailable.toDouble() / state.totalRam else 1.0
        val isRamLow = ramRatio < 0.15

        return when {
            // Priority 1: High Thermal Stress (Using actual constants)
            state.thermalState == ThermalState.SEVERE || 
            state.thermalState == ThermalState.CRITICAL || 
            state.thermalState == ThermalState.EMERGENCY -> {
                if (isNetworkUsable) {
                    StrategyDetail(
                        mode = ExecutionStrategy.CLOUD_HEAVY,
                        modelName = "Gemini API (Cloud)",
                        reason = "Device temperature is high. Offloading to cloud to prevent local overheating.",
                        latency = avgCloudLatency,
                        networkUsed = "Yes"
                    )
                } else {
                    StrategyDetail(
                        mode = ExecutionStrategy.LOCAL_LIGHTWEIGHT,
                        modelName = "DeepSeek Lite (Local)",
                        reason = "Thermal state is high and network is restricted. Throttling to lightweight local model.",
                        latency = avgCloudLatency,
                        networkUsed = "No"
                    )
                }
            }

            // Priority 2: Low RAM for this specific device (Hardware Agnostic)
            isRamLow && isNetworkUsable -> {
                StrategyDetail(
                    mode = ExecutionStrategy.CLOUD_HEAVY,
                    modelName = "Gemini API (Cloud)",
                    reason = "RAM is critically low for this device (${(ramRatio * 100).toInt()}% free). Offloading to save memory.",
                    latency = avgCloudLatency,
                    networkUsed = "Yes"
                )
            }

            // Priority 3: Excellent Conditions
            state.networkQuality == NetworkQuality.EXCELLENT && !isRamLow -> {
                StrategyDetail(
                    mode = ExecutionStrategy.CLOUD_HEAVY,
                    modelName = "Gemini API (High-Def)",
                    reason = "Optimal RAM availability and excellent network detected.",
                    latency = avgCloudLatency,
                    networkUsed = "Yes"
                )
            }

            // Priority 4: Good/Moderate Conditions -> Hybrid
            state.ramAvailable > 500 && isNetworkUsable -> {
                StrategyDetail(
                    mode = ExecutionStrategy.HYBRID,
                    modelName = "DeepSeek Lite + Gemini API",
                    reason = "Stable resources and $networkDesc network. Balancing speed with cloud intelligence.",
                    latency = avgCloudLatency,
                    networkUsed = "Yes"
                )
            }

            // Default: Local Lightweight
            else -> {
                val ramReason = if (isRamLow) "RAM is tight" else "RAM is available"
                val netReason = if (!isNetworkUsable) "network is $networkDesc" else "optimizing for efficiency"
                
                StrategyDetail(
                    mode = ExecutionStrategy.LOCAL_LIGHTWEIGHT,
                    modelName = "DeepSeek Lite (Local)",
                    reason = "$ramReason and $netReason. Using on-device model.",
                    latency = avgCloudLatency,
                    networkUsed = "No"
                )
            }
        }
    }
}
