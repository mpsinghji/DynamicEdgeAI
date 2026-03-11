package com.dynamicedgeai.router

import android.util.Log
import com.dynamicedgeai.cloud.CloudModelRunner
import com.dynamicedgeai.engine.DecisionEngine
import com.dynamicedgeai.engine.Strategy
import com.dynamicedgeai.engine.StrategyDetail
import com.dynamicedgeai.local.LocalModelRunner
import com.dynamicedgeai.monitor.DeviceState

/**
 * Routes user messages to either local or cloud models based on 
 * privacy settings and device state.
 */
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
        
        val strategyDetail = if (isPrivacyModeOn) {
            StrategyDetail(Strategy.LOCAL, "Privacy Mode ON (User override)")
        } else {
            decisionEngine.determineStrategy(state)
        }

        Log.d("MessageRouter", "Selected strategy: ${strategyDetail.strategy} Reason: ${strategyDetail.reason}")

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
