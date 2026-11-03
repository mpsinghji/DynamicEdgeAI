package com.dynamicedgeai.cloud

import android.content.Context
import android.util.Base64
import android.util.Log
import com.dynamicedgeai.BuildConfig
import com.dynamicedgeai.ml.Content
import com.dynamicedgeai.ml.GeminiApi
import com.dynamicedgeai.ml.GeminiRequest
import com.dynamicedgeai.ml.InlineData
import com.dynamicedgeai.ml.Part
import com.dynamicedgeai.util.ApiKeyManager
import com.dynamicedgeai.util.PlatformType
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class CloudModelRunner(private val context: Context? = null) {

    private val tag = "CloudModelRunner"
    private val gson = Gson()

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val geminiApi: GeminiApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
            .create(GeminiApi::class.java)
    }

    suspend fun runInference(
        prompt: String,
        imagePath: String? = null,
        overridePlatform: PlatformType? = null,
        overrideApiKey: String? = null
    ): String = withContext(Dispatchers.IO) {
        val platform = overridePlatform
            ?: context?.let { ApiKeyManager.getActivePlatform(it) }
            ?: PlatformType.GEMINI

        val apiKey = overrideApiKey?.takeIf { it.isNotBlank() }
            ?: context?.let { ApiKeyManager.getApiKey(it, platform) }?.takeIf { it.isNotBlank() }
            ?: if (platform == PlatformType.GEMINI) BuildConfig.GEMINI_KEY.takeIf { it.isNotBlank() } else null

        if (apiKey.isNullOrEmpty()) {
            return@withContext "⚠️ ${platform.displayName} API Key is not configured.\n\n" +
                    "Please tap the Settings icon (top-right or menu) ➔ ${platform.displayName} to save your client-side API key."
        }

        return@withContext try {
            when (platform) {
                PlatformType.GEMINI -> runGeminiInference(apiKey, prompt, imagePath)
                PlatformType.OPENAI -> runOpenAiCompatibleInference(
                    url = "https://api.openai.com/v1/chat/completions",
                    apiKey = apiKey,
                    model = platform.defaultModel,
                    prompt = prompt
                )
                PlatformType.GROQ -> runOpenAiCompatibleInference(
                    url = "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = apiKey,
                    model = platform.defaultModel,
                    prompt = prompt
                )
                PlatformType.OPENROUTER -> runOpenAiCompatibleInference(
                    url = "https://openrouter.ai/api/v1/chat/completions",
                    apiKey = apiKey,
                    model = platform.defaultModel,
                    prompt = prompt
                )
                PlatformType.ANTHROPIC -> runAnthropicInference(apiKey, platform.defaultModel, prompt)
            }
        } catch (t: Throwable) {
            Log.e(tag, "${platform.displayName} inference failed", t)
            val detailedMsg = if (t is HttpException) {
                val errBody = try { t.response()?.errorBody()?.string() } catch (_: Exception) { null }
                if (!errBody.isNullOrBlank()) "HTTP ${t.code()}: $errBody" else "HTTP ${t.code()}"
            } else {
                t.localizedMessage ?: t.javaClass.simpleName
            }
            "Error: ${platform.displayName} request failed ($detailedMsg)"
        }
    }

    private suspend fun runGeminiInference(apiKey: String, prompt: String, imagePath: String?): String {
        val parts = mutableListOf<Part>()
        if (!imagePath.isNullOrBlank()) {
            val imageFile = File(imagePath)
            if (imageFile.exists() && imageFile.canRead()) {
                val bytes = imageFile.readBytes()
                val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val mime = if (imagePath.endsWith(".png", true)) "image/png" else "image/jpeg"
                parts.add(Part(inline_data = InlineData(mime_type = mime, data = base64Data)))
            }
        }
        parts.add(Part(text = prompt))

        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = parts,
                    role = "user"
                )
            )
        )

        // Try gemini-2.5-flash endpoint (per configuration) with fallback to gemini-1.5-flash
        val candidateModels = listOf("gemini-2.5-flash", "gemini-1.5-flash")
        var lastError: Throwable? = null

        for (model in candidateModels) {
            try {
                val response = geminiApi.generateContentWithModel(model, apiKey, request)
                val text = response.candidates.firstOrNull()?.content?.parts?.firstOrNull { it.text != null }?.text
                if (!text.isNullOrBlank()) {
                    return text
                }
            } catch (e: HttpException) {
                lastError = e
                Log.w(tag, "Gemini model $model returned HTTP ${e.code()}")
                if (e.code() == 404 || e.code() == 403) {
                    continue
                }
                throw e
            } catch (t: Throwable) {
                lastError = t
                Log.w(tag, "Gemini model $model error", t)
            }
        }

        if (lastError != null) throw lastError
        return "No valid response received from Gemini API."
    }

    private fun runOpenAiCompatibleInference(
        url: String,
        apiKey: String,
        model: String,
        prompt: String
    ): String {
        val payload = JsonObject().apply {
            addProperty("model", model)
            val messagesArray = com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            }
            add("messages", messagesArray)
            addProperty("max_tokens", 1024)
            addProperty("temperature", 0.7)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = payload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $responseBody")
        }

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val choices = json.getAsJsonArray("choices")
        if (choices != null && choices.size() > 0) {
            val messageObj = choices[0].asJsonObject.getAsJsonObject("message")
            return messageObj.get("content").asString.trim()
        }
        return "No text output returned by model."
    }

    private fun runAnthropicInference(apiKey: String, model: String, prompt: String): String {
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", 1024)
            val messagesArray = com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            }
            add("messages", messagesArray)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = payload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $responseBody")
        }

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val contentArray = json.getAsJsonArray("content")
        if (contentArray != null && contentArray.size() > 0) {
            return contentArray[0].asJsonObject.get("text").asString.trim()
        }
        return "No text output returned by Claude."
    }
}
