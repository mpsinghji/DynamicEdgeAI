package com.dynamicedgeai.router

import android.util.Log
import com.dynamicedgeai.cloud.CloudModelRunner
import com.dynamicedgeai.engine.DecisionEngine
import com.dynamicedgeai.engine.Strategy
import com.dynamicedgeai.engine.StrategyDetail
import com.dynamicedgeai.local.LocalModelRunner
import com.dynamicedgeai.local.ModelCapabilityRegistry
import com.dynamicedgeai.monitor.DeviceState
import com.dynamicedgeai.util.ModelStatus

data class ChatMessage(val role: String, val content: String)

class MessageRouter(
    private val decisionEngine: DecisionEngine,
    private val localModelRunner: LocalModelRunner,
    private val cloudModelRunner: CloudModelRunner
) {
    private val internalHistory = mutableListOf<ChatMessage>()
    private val MAX_HISTORY = 8

    suspend fun routeMessage(
        text: String, 
        isPrivacyModeOn: Boolean, 
        state: DeviceState,
        imagePath: String? = null,
        providedHistory: List<ChatMessage>? = null,
        overrideMode: com.dynamicedgeai.util.InferenceOverrideMode = com.dynamicedgeai.util.InferenceOverrideMode.OFF
    ): RouterResult {
        // Use provided conversation history or internal buffer
        val historyToUse = if (providedHistory != null) {
            providedHistory.toMutableList()
        } else {
            internalHistory.add(ChatMessage("user", text))
            internalHistory.takeLast(MAX_HISTORY).toMutableList()
        }

        val effectivePrivacy = isPrivacyModeOn || overrideMode == com.dynamicedgeai.util.InferenceOverrideMode.LOCAL

        var strategyDetail = when {
            overrideMode == com.dynamicedgeai.util.InferenceOverrideMode.LOCAL -> {
                StrategyDetail(Strategy.LOCAL, "Settings Override: Forced LOCAL / Edge AI")
            }
            overrideMode == com.dynamicedgeai.util.InferenceOverrideMode.CLOUD -> {
                StrategyDetail(Strategy.CLOUD, "Settings Override: Forced CLOUD AI")
            }
            isPrivacyModeOn -> {
                StrategyDetail(Strategy.LOCAL, "Privacy Mode ON (User override)")
            }
            else -> {
                decisionEngine.determineStrategy(state)
            }
        }

        // Multimodal Image Input Check
        val hasImage = !imagePath.isNullOrBlank()
        if (hasImage) {
            val localSupportsImage = ModelCapabilityRegistry.supportsImage(localModelRunner.currentModel)

            if (effectivePrivacy && !localSupportsImage) {
                return RouterResult(
                    "⚠️ The selected local model (${localModelRunner.currentModel.displayName}) is text-only and does not support image analysis.\n\n" +
                            "To analyse images, please switch to Gemma 4 E4B-it (LiteRT-LM) or turn off Privacy Mode to use Gemini 1.5 Flash.",
                    StrategyDetail(Strategy.LOCAL, "Image input unsupported by ${localModelRunner.currentModel.displayName}")
                )
            }

            if (!effectivePrivacy && !localSupportsImage) {
                // Auto-route to cloud Gemini for multimodal image recognition
                strategyDetail = StrategyDetail(Strategy.CLOUD, "Routing to Cloud: Image analysis required and local model is text-only")
            }
        }

        // Validate local model availability before routing to LOCAL
        if (strategyDetail.strategy == Strategy.LOCAL) {
            val currentModelStatus = localModelRunner.getModelStatus(localModelRunner.currentModel)
            
            if (currentModelStatus != ModelStatus.DOWNLOADED) {
                val available = localModelRunner.getFirstAvailableModel()
                if (available != null) {
                    localModelRunner.switchModel(available)
                    Log.d("MessageRouter", "Auto-switched to ${available.displayName} (current model unavailable)")
                } else if (effectivePrivacy) {
                    return RouterResult(
                        "⚠️ No local model is available. Please download a model from the Model Manager to use on-device inference.",
                        StrategyDetail(Strategy.LOCAL, "No local model available — download required")
                    )
                } else {
                    strategyDetail = StrategyDetail(Strategy.CLOUD, "Local model unavailable — falling back to Cloud")
                    Log.d("MessageRouter", "Falling back to CLOUD: no valid local model")
                }
            }
        }

        Log.d("MessageRouter", "Routing with strategy: ${strategyDetail.strategy}")

        val response = when (strategyDetail.strategy) {
            Strategy.LOCAL -> localModelRunner.runInference(historyToUse, imagePath)
            Strategy.CLOUD -> cloudModelRunner.runInference(text, imagePath)
        }

        if (providedHistory == null) {
            internalHistory.add(ChatMessage("assistant", response))
            if (internalHistory.size > MAX_HISTORY) {
                repeat(internalHistory.size - MAX_HISTORY) { internalHistory.removeAt(0) }
            }
        }

        return RouterResult(response, strategyDetail)
    }

    fun clearHistory() {
        internalHistory.clear()
    }
}

data class RouterResult(
    val response: String,
    val detail: StrategyDetail
)
