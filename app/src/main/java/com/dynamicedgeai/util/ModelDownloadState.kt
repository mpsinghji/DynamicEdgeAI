package com.dynamicedgeai.util

import com.dynamicedgeai.local.LocalModel

enum class ModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
    DOWNLOADED,
    CORRUPTED
}

data class ModelDownloadState(
    val model: LocalModel,
    var status: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    var progress: Int = 0,
    var errorMessage: String? = null
)
