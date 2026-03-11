package com.dynamicedgeai.engine

/**
 * Data class to hold the selected strategy and the reason for the decision.
 * This is used for explainable AI output in the UI.
 */
data class StrategyDetail(
    val strategy: Strategy,
    val reason: String
)
