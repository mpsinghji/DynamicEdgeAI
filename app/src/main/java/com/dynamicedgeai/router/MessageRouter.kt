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
        overrideMode: com.dynamicedgeai.util.InferenceOverrideMode = com.dynamicedgeai.util.InferenceOverrideMode.OFF,
        onPartialResponse: ((String) -> Unit)? = null
    ): RouterResult {
        // Use provided conversation history or internal buffer
        val historyToUse = if (providedHistory != null) {
            providedHistory.toMutableList()
        } else {
            internalHistory.add(ChatMessage("user", text))
            internalHistory.takeLast(MAX_HISTORY).toMutableList()
        }

        val hasImage = !imagePath.isNullOrBlank()
        val localSupportsImage = ModelCapabilityRegistry.supportsImage(localModelRunner.currentModel)

        var strategyDetail = when {
            // Priority 1: User explicitly attached photos, but active local model is text-only (e.g. Gemma 4).
            // Unless the strict on-device Privacy switch is explicitly turned ON, route to Gemini Vision!
            hasImage && !localSupportsImage && !isPrivacyModeOn -> {
                StrategyDetail(
                    Strategy.CLOUD,
                    "Routing to Gemini Vision (${localModelRunner.currentModel.displayName} is text-only)"
                )
            }
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
        if (hasImage) {
            if (isPrivacyModeOn && !localSupportsImage) {
                return RouterResult(
                    "🔒 Privacy Mode is ON (Strict On-Device AI), but ${localModelRunner.currentModel.displayName} is a text-only language model.\n\n" +
                            "To analyze photos, please toggle Privacy Mode OFF above so Gemini Vision can process your images.",
                    StrategyDetail(Strategy.LOCAL, "Image input unsupported by ${localModelRunner.currentModel.displayName} while Privacy Mode ON")
                )
            }

            if (!localSupportsImage) {
                // Auto-route to cloud Gemini for multimodal image recognition
                strategyDetail = StrategyDetail(
                    Strategy.CLOUD,
                    "Routing to Cloud: Image analysis required and ${localModelRunner.currentModel.displayName} is text-only"
                )
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
                } else if (isPrivacyModeOn) {
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
            Strategy.LOCAL -> localModelRunner.runInference(historyToUse, imagePath, onPartialResponse)
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
