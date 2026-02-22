package com.dynamicedgeai.engine

import com.dynamicedgeai.monitor.DeviceState
import com.dynamicedgeai.monitor.NetworkQuality

class DecisionEngine {

    fun determineStrategyDetail(state: DeviceState): StrategyDetail {
        return when {
            // Cloud Heavy Conditions: Excellent Network and High RAM
            state.networkQuality == NetworkQuality.EXCELLENT && state.ramAvailable > 2000 -> {
                StrategyDetail(
                    mode = ExecutionStrategy.CLOUD_HEAVY,
                    modelName = "Gemini API (Powerful 32B Model)",
                    reason = "High RAM availability and excellent network detected. Switching to powerful cloud-based model for enhanced capabilities.",
                    latency = "1.5s",
                    networkUsed = "Yes"
                )
            }
            // Hybrid Conditions: Sufficient RAM and Strong/Moderate Network
            state.ramAvailable > 1000 && (state.networkQuality == NetworkQuality.GOOD || state.networkQuality == NetworkQuality.MODERATE) -> {
                StrategyDetail(
                    mode = ExecutionStrategy.HYBRID,
                    modelName = "DeepSeek Lite (1.5B 4-bit) + Gemini API",
                    reason = "Sufficient RAM and strong network detected. Utilizing both local model and cloud inference for balanced performance.",
                    latency = "2.1s",
                    networkUsed = "Yes"
                )
            }
            // Local Lightweight: Default fallback for low RAM or weak network
            else -> {
                StrategyDetail(
                    mode = ExecutionStrategy.LOCAL_LIGHTWEIGHT,
                    modelName = "DeepSeek Lite (1.5B 4-bit)",
                    reason = "RAM is limited, network is weak. Switching to local lightweight model to optimize resources.",
                    latency = "3.2s",
                    networkUsed = "No"
                )
            }
        }
    }
}
