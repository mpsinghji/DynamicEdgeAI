package com.dynamicedgeai.local

enum class ModelCapability {
    TEXT_INPUT,
    IMAGE_INPUT,
    MULTIMODAL_INPUT,
    TOOL_CALLING
}

object ModelCapabilityRegistry {

    private val localCapabilities = mapOf(
        LocalModel.GEMMA_2B to setOf(ModelCapability.TEXT_INPUT),
        LocalModel.Qwen to setOf(ModelCapability.TEXT_INPUT),
        LocalModel.TINY_LLAMA to setOf(ModelCapability.TEXT_INPUT),
        LocalModel.GEMMA_4_E4B to setOf(
            ModelCapability.TEXT_INPUT,
            ModelCapability.IMAGE_INPUT,
            ModelCapability.MULTIMODAL_INPUT,
            ModelCapability.TOOL_CALLING
        )
    )

    private val cloudCapabilities = setOf(
        ModelCapability.TEXT_INPUT,
        ModelCapability.IMAGE_INPUT,
        ModelCapability.MULTIMODAL_INPUT,
        ModelCapability.TOOL_CALLING
    )

    fun getCapabilities(model: LocalModel): Set<ModelCapability> {
        return localCapabilities[model] ?: setOf(ModelCapability.TEXT_INPUT)
    }

    fun supportsImage(model: LocalModel): Boolean {
        return getCapabilities(model).contains(ModelCapability.IMAGE_INPUT)
    }

    fun supportsImageInCloud(): Boolean {
        return cloudCapabilities.contains(ModelCapability.IMAGE_INPUT)
    }

    fun canProcessImage(isPrivacyModeOn: Boolean, activeLocalModel: LocalModel): Boolean {
        return if (isPrivacyModeOn) {
            supportsImage(activeLocalModel)
        } else {
            // In hybrid/cloud mode, Gemini 1.5 Flash supports image inputs
            true
        }
    }
}

