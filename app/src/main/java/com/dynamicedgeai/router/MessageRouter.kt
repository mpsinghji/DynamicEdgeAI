package com.dynamicedgeai.router

import android.util.Log
import com.dynamicedgeai.cloud.CloudModelRunner
import com.dynamicedgeai.engine.DecisionEngine
import com.dynamicedgeai.engine.Strategy
import com.dynamicedgeai.engine.StrategyDetail
import com.dynamicedgeai.local.LocalModelRunner
import com.dynamicedgeai.monitor.DeviceState
import com.dynamicedgeai.util.ModelStatus

data class ChatMessage(val role: String, val content: String)

class MessageRouter(
    private val decisionEngine: DecisionEngine,
    private val localModelRunner: LocalModelRunner,
    private val cloudModelRunner: CloudModelRunner
) {
    private val history = mutableListOf<ChatMessage>()
    private val MAX_HISTORY = 6 // Keep last 3 turns (3 user + 3 assistant)

    suspend fun routeMessage(
        text: String, 
        isPrivacyModeOn: Boolean, 
        state: DeviceState
    ): RouterResult {
        // Add user message to history
        history.add(ChatMessage("user", text))
        
        var strategyDetail = if (isPrivacyModeOn) {
            StrategyDetail(Strategy.LOCAL, "Privacy Mode ON (User override)")
        } else {
            decisionEngine.determineStrategy(state)
        }

        // Validate local model availability before routing to LOCAL
        if (strategyDetail.strategy == Strategy.LOCAL) {
            val currentModelStatus = localModelRunner.getModelStatus(localModelRunner.currentModel)
            
            if (currentModelStatus != ModelStatus.DOWNLOADED) {
                // Try to auto-switch to any available model
                val available = localModelRunner.getFirstAvailableModel()
                if (available != null) {
                    localModelRunner.switchModel(available)
                    Log.d("MessageRouter", "Auto-switched to ${available.displayName} (current model unavailable)")
                } else if (isPrivacyModeOn) {
                    // Privacy mode ON and no model available — return error
                    return RouterResult(
                        "⚠️ No local model is available. Please download a model from the Model Manager to use on-device inference.",
                        StrategyDetail(Strategy.LOCAL, "No local model available — download required")
                    )
                } else {
                    // Fall back to cloud
                    strategyDetail = StrategyDetail(Strategy.CLOUD, "Local model unavailable — falling back to Cloud")
                    Log.d("MessageRouter", "Falling back to CLOUD: no valid local model")
                }
            }
        }

        Log.d("MessageRouter", "Routing with strategy: ${strategyDetail.strategy}")

        // Prepare the prompt from history
        val response = when (strategyDetail.strategy) {
            Strategy.LOCAL -> localModelRunner.runInference(history.takeLast(MAX_HISTORY))
            Strategy.CLOUD -> cloudModelRunner.runInference(text) // Cloud handles its own history or we can add it later
        }

        // Add assistant response to history
        history.add(ChatMessage("assistant", response))
        
        // Trim history if too long
        if (history.size > MAX_HISTORY) {
            repeat(history.size - MAX_HISTORY) { history.removeAt(0) }
        }

        return RouterResult(response, strategyDetail)
    }

    fun clearHistory() {
        history.clear()
    }
}

data class RouterResult(
    val response: String,
    val detail: StrategyDetail
)
