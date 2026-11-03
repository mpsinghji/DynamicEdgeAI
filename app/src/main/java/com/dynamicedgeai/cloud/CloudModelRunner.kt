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
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class CloudModelRunner(private val context: Context? = null) {

    private val api: GeminiApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(GeminiApi::class.java)
    }

    suspend fun runInference(prompt: String, imagePath: String? = null, overrideApiKey: String? = null): String {
        val apiKey = overrideApiKey?.takeIf { it.isNotBlank() }
            ?: context?.let { ApiKeyManager.getGeminiApiKey(it) }?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GEMINI_KEY.takeIf { it.isNotBlank() }

        if (apiKey.isNullOrEmpty()) {
            return "⚠️ Gemini API Key is not configured.\n\nPlease open the menu (bottom-right) ➔ tap 'API Key' to enter your Gemini API key from Google AI Studio."
        }

        return try {
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
            
            val response = api.generateContent(apiKey, request)
            response.candidates.firstOrNull()?.content?.parts?.firstOrNull { it.text != null }?.text 
                ?: "No valid response from Gemini API."
                
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e("CloudModelRunner", "Cloud inference failed: HTTP ${e.code()} - $errorBody")
            "Error: HTTP ${e.code()} - See Logcat for details."
        } catch (e: Exception) {
            Log.e("CloudModelRunner", "Cloud inference failed", e)
            "Error: Cloud processing failed (${e.localizedMessage})"
        }
    }
}
