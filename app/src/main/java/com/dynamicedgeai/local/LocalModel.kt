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
//    Phi(
//        "Phi-3 Mini",
//        "Phi-3-mini-4k-instruct-q4.gguf",
//        "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf?download=true",
//        expectedSizeBytes = 2_400_000_000L,
//        description = "Microsoft Phi-3 Mini GGUF — strong reasoning, optimized for edge devices",
//        isRecommended = false
//    ),

    Qwen(
        "Qwen 2.5 3B",
        "qwen2.5-3b-instruct-q4_k_m.gguf",
        "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf?download=true",
        expectedSizeBytes = 2_150_000_000L,
        description = "Alibaba Qwen 2.5 3B GGUF — strong coding and instruction-following model",
        isRecommended = false
    ),
//    DEEPSEEK_R1_Q4(
//        "DeepSeek-R1 1.5B Q4",
//        "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
//        "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf?download=true",
//        expectedSizeBytes = 1_120_000_000L,
//        description = "DeepSeek-R1 1.5B (GGUF)",
//        isRecommended = false
//    ),
//    DEEPSEEK_R1_Q2(
//        "DeepSeek-R1 1.5B Q2",
//        "DeepSeek-R1-Distill-Qwen-1.5B-Q2_K.gguf",
//        "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q2_K.gguf?download=true",
//        expectedSizeBytes = 708_000_000L,
//        description = "DeepSeek-R1 1.5B (GGUF)",
//        isRecommended = false
//    ),
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
