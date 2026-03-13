package com.dynamicedgeai.cloud

import android.util.Log
import com.dynamicedgeai.BuildConfig
import com.dynamicedgeai.ml.Content
import com.dynamicedgeai.ml.GeminiApi
import com.dynamicedgeai.ml.GeminiRequest
import com.dynamicedgeai.ml.Part
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class CloudModelRunner {

    private val api: GeminiApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        // Increased timeouts to handle long AI generations
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(GeminiApi::class.java)
    }

    suspend fun runInference(prompt: String): String {
        val apiKey = BuildConfig.GEMINI_KEY
        if (apiKey.isEmpty()) {
            return "Error: API Key is not set in local.properties. Please add GEMINI_API_KEY='YOUR_KEY' to that file."
        }

        return try {
            val request = GeminiRequest(
                contents = listOf(
                    Content(
                        parts = listOf(Part(text = prompt)),
                        role = "user"
                    )
                )
            )
            
            val response = api.generateContent(apiKey, request)
            response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text 
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
