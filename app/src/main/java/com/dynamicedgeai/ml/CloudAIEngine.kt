package com.dynamicedgeai.ml

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class CloudAIEngine {
    private val api: GeminiApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(GeminiApi::class.java)
    }

    suspend fun runCloudModel(apiKey: String, prompt: String): String {
        return try {
            val request = GeminiRequest(
                contents = listOf(
                    Content(
                        role = "user",
                        parts = listOf(Part(text = prompt))
                    )
                )
            )
            val response = api.generateContent(apiKey, request)
            val resultText = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
            resultText ?: "No response from Cloud AI."
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e("CloudAIEngine", "HTTP Error: ${e.code()} - $errorBody")
            "Error: HTTP ${e.code()} - $errorBody"
        } catch (e: Exception) {
            Log.e("CloudAIEngine", "Error calling Gemini API", e)
            "Error: ${e.localizedMessage}"
        }
    }
}
