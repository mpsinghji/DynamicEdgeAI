package com.dynamicedgeai.router

import android.util.Log
import com.dynamicedgeai.cloud.CloudModelRunner
import com.dynamicedgeai.engine.DecisionEngine
import com.dynamicedgeai.engine.Strategy
import com.dynamicedgeai.engine.StrategyDetail
import com.dynamicedgeai.local.LocalModelRunner
import com.dynamicedgeai.monitor.DeviceState
import com.dynamicedgeai.util.ModelStatus

class MessageRouter(
    private val decisionEngine: DecisionEngine,
    private val localModelRunner: LocalModelRunner,
    private val cloudModelRunner: CloudModelRunner
) {

    suspend fun routeMessage(
        text: String, 
        isPrivacyModeOn: Boolean, 
        state: DeviceState
    ): RouterResult {
        
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

        val response = when (strategyDetail.strategy) {
            Strategy.LOCAL -> localModelRunner.runInference(text)
            Strategy.CLOUD -> cloudModelRunner.runInference(text)
        }

        return RouterResult(response, strategyDetail)
    }
}

data class RouterResult(
    val response: String,
    val detail: StrategyDetail
)
