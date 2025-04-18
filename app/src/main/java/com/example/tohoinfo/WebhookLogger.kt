package com.example.tohoinfo

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object WebhookLogger {
    private const val ERROR_WEBHOOK_URL = "https://discord.com/api/webhooks/..."
    private const val TESTING_WEBHOOK_URL = "https://discord.com/api/webhooks/..."

    fun send(message: String, isError: Boolean = false) {
        val url = if (isError) ERROR_WEBHOOK_URL else TESTING_WEBHOOK_URL

        Thread {
            try {
                val json = JSONObject().put("content", message)
                val requestBody =
                    json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder().url(url).post(requestBody).build()
                OkHttpClient().newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("WebhookLogger", "Failed to log to Discord", e)
            }
        }.start()
    }
}
