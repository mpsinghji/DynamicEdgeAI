package com.dynamicedgeai.local

enum class LocalModel(
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val description: String,
    val isRecommended: Boolean
) {
    GEMMA_2B(
        "Gemma 2B",
        "gemma-2b-it-cpu-int4.bin",
        "https://huggingface.co/metsman/gemma-2b-it-cpu-int4-org/resolve/main/gemma-2b-it-cpu-int4.bin?download=true",
        expectedSizeBytes = 1_500_000_000L,
        description = "Google Gemma 2B — lightweight, fast on-device model",
        isRecommended = true
    ),
    DEEPSEEK_R1(
        "DeepSeek-R1 1.5B",
        "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
        "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf?download=true",
        expectedSizeBytes = 1_100_000_000L,
        description = "DeepSeek-R1 1.5B (GGUF)",
        isRecommended = false
    ),
    TINY_LLAMA(
        "TinyLlama 1.1B",
        "tinyllama-1.1b-chat-v1.0.Q2_K.gguf",
        "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q2_K.gguf?download=true",
        expectedSizeBytes = 480_000_000L,
        description = "TinyLlama 1.1B (GGUF)",
        isRecommended = false
    );

    /** True for GGUF models (llama.cpp), false for .bin models (MediaPipe) */
    val isGguf: Boolean get() = fileName.endsWith(".gguf")

    /** Human-readable file size string */
    val fileSizeLabel: String
        get() {
            val mb = expectedSizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0)
            else String.format("%.0f MB", mb)
        }
}
