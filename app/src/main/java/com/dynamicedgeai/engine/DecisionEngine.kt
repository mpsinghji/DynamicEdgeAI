package com.dynamicedgeai.engine

import com.dynamicedgeai.monitor.DeviceState
import com.dynamicedgeai.monitor.NetworkQuality

class DecisionEngine {

    fun determineStrategyDetail(state: DeviceState): StrategyDetail {
        val networkDesc = when(state.networkQuality) {
            NetworkQuality.EXCELLENT -> "excellent"
            NetworkQuality.GOOD -> "strong"
            NetworkQuality.MODERATE -> "stable"
            else -> "weak"
        }

        val isNetworkFast = state.networkQuality == NetworkQuality.EXCELLENT || state.networkQuality == NetworkQuality.GOOD

        return when {
            // Priority 1: High Thermal Stress -> Cloud Only
            state.thermalState.ordinal >= 3 -> { // SEVERE or higher
                if (isNetworkFast) {
                    StrategyDetail(
                        mode = ExecutionStrategy.CLOUD_HEAVY,
                        modelName = "Gemini API (Cloud)",
                        reason = "Thermal state is high. Offloading to cloud to prevent local overheating and maintain performance.",
                        latency = "1.5s",
                        networkUsed = "Yes"
                    )
                } else {
                    StrategyDetail(
                        mode = ExecutionStrategy.LOCAL_LIGHTWEIGHT,
                        modelName = "DeepSeek Lite (Local)",
                        reason = "Thermal state is high and network is restricted. Throttling to lightweight local model to cool down.",
                        latency = "3.2s",
                        networkUsed = "No"
                    )
                }
            }

            // Priority 2: Strong Signal + Low RAM -> Cloud Heavy (Offload)
            isNetworkFast && state.ramAvailable < 800 -> {
                StrategyDetail(
                    mode = ExecutionStrategy.CLOUD_HEAVY,
                    modelName = "Gemini API (Cloud)",
                    reason = "RAM is low (${state.ramAvailable} MB) but $networkDesc network detected. Offloading processing to cloud to save device memory.",
                    latency = "1.2s",
                    networkUsed = "Yes"
                )
            }

            // Priority 3: Excellent Conditions
            state.networkQuality == NetworkQuality.EXCELLENT && state.ramAvailable > 1500 -> {
                StrategyDetail(
                    mode = ExecutionStrategy.CLOUD_HEAVY,
                    modelName = "Gemini API (High-Def)",
                    reason = "High RAM availability and excellent network detected. Using most powerful cloud model for optimal results.",
                    latency = "1.1s",
                    networkUsed = "Yes"
                )
            }

            // Priority 4: Good Conditions -> Hybrid
            state.ramAvailable > 1000 && isNetworkFast -> {
                StrategyDetail(
                    mode = ExecutionStrategy.HYBRID,
                    modelName = "DeepSeek Lite + Gemini API",
                    reason = "Stable resources and $networkDesc network. Balancing local speed with cloud intelligence.",
                    latency = "1.8s",
                    networkUsed = "Yes"
                )
            }

            // Default: Local Lightweight
            else -> {
                val ramReason = if (state.ramAvailable < 800) "RAM is tight (${state.ramAvailable} MB)" else "RAM is available"
                val netReason = if (!isNetworkFast) "network is $networkDesc" else "optimizing for efficiency"
                
                StrategyDetail(
                    mode = ExecutionStrategy.LOCAL_LIGHTWEIGHT,
                    modelName = "DeepSeek Lite (Local)",
                    reason = "$ramReason and $netReason. Using on-device lightweight model.",
                    latency = "2.5s",
                    networkUsed = "No"
                )
            }
        }
    }
}
