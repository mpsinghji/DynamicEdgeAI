package com.dynamicedgeai.engine

data class StrategyDetail(
    val mode: ExecutionStrategy,
    val modelName: String,
    val reason: String,
    val latency: String,
    val networkUsed: String
)
