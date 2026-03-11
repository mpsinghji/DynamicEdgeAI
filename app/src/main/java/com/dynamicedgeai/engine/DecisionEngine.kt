package com.dynamicedgeai.engine

import com.dynamicedgeai.monitor.DeviceState
import com.dynamicedgeai.monitor.NetworkQuality

class   DecisionEngine {

    // --- TEST OVERRIDE AREA ---
    // Set to "CLOUD_HEAVY", "HYBRID", "LOCAL_LIGHTWEIGHT", or "OFF"
    private val TEST_MODE = "OFF" 
    // --------------------------

    fun determineStrategyDetail(state: DeviceState): StrategyDetail {
        // If test mode is active, bypass all logic
        if (TEST_MODE != "OFF") {
            return when(TEST_MODE) {
                "CLOUD_HEAVY" -> StrategyDetail(
                    mode = ExecutionStrategy.CLOUD_HEAVY,
                    modelName = "TEST: Gemini API",
                    reason = "Forced Test Mode: CLOUD_HEAVY",
                    latency = "1.2s",
                    networkUsed = "Yes"
                )
                "HYBRID" -> StrategyDetail(
                    mode = ExecutionStrategy.HYBRID,
                    modelName = "TEST: DeepSeek + Gemini",
                    reason = "Forced Test Mode: HYBRID",
                    latency = "1.8s",
                    networkUsed = "Yes"
                )
                else -> StrategyDetail(
                    mode = ExecutionStrategy.LOCAL_LIGHTWEIGHT,
                    modelName = "TEST: DeepSeek Lite",
                    reason = "Forced Test Mode: LOCAL_LIGHTWEIGHT",
                    latency = "2.5s",
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

        return when {
            // Priority 1: High Thermal Stress
            state.thermalState.ordinal >= 3 -> {
                if (isNetworkFast) {
                    StrategyDetail(
                        mode = ExecutionStrategy.CLOUD_HEAVY,
                        modelName = "Gemini API (Cloud)",
                        reason = "Thermal state is high. Offloading to cloud to prevent local overheating.",
                        latency = "1.5s",
                        networkUsed = "Yes"
                    )
                } else {
                    StrategyDetail(
                        mode = ExecutionStrategy.LOCAL_LIGHTWEIGHT,
                        modelName = "DeepSeek Lite (Local)",
                        reason = "Thermal state is high and network is restricted. Throttling to lightweight local model.",
                        latency = "3.2s",
                        networkUsed = "No"
                    )
                }
            }

            // Priority 2: Strong Signal + Low RAM
            isNetworkFast && state.ramAvailable < 800 -> {
                StrategyDetail(
                    mode = ExecutionStrategy.CLOUD_HEAVY,
                    modelName = "Gemini API (Cloud)",
                    reason = "RAM is low (${state.ramAvailable} MB) but $networkDesc network detected. Offloading processing.",
                    latency = "1.2s",
                    networkUsed = "Yes"
                )
            }

            // Priority 3: Excellent Conditions
            state.networkQuality == NetworkQuality.EXCELLENT && state.ramAvailable > 1500 -> {
                StrategyDetail(
                    mode = ExecutionStrategy.CLOUD_HEAVY,
                    modelName = "Gemini API (High-Def)",
                    reason = "High RAM availability and excellent network detected.",
                    latency = "1.1s",
                    networkUsed = "Yes"
                )
            }

            // Priority 4: Good Conditions -> Hybrid
            state.ramAvailable > 1000 && isNetworkFast -> {
                StrategyDetail(
                    mode = ExecutionStrategy.HYBRID,
                    modelName = "DeepSeek Lite + Gemini API",
                    reason = "Stable resources and $networkDesc network. Balancing speed with intelligence.",
                    latency = "1.8s",
                    networkUsed = "Yes"
                )
            }

            // Default: Local Lightweight
            else -> {
                val ramReason = if (state.ramAvailable < 800) "RAM is tight" else "RAM is available"
                val netReason = if (!isNetworkFast) "network is $networkDesc" else "optimizing for efficiency"
                
                StrategyDetail(
                    mode = ExecutionStrategy.LOCAL_LIGHTWEIGHT,
                    modelName = "DeepSeek Lite (Local)",
                    reason = "$ramReason and $netReason. Using on-device model.",
                    latency = "2.5s",
                    networkUsed = "No"
                )
            }
        }
    }
}
