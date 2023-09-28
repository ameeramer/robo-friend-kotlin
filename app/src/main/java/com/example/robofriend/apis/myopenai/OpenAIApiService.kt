package com.example.robofriend.apis.myopenai

import AwsS3Service
import android.content.Context
import com.aallam.openai.api.BetaOpenAI

import com.example.robofriend.BuildConfig
import java.net.URL
import java.util.concurrent.CompletableFuture

import kotlin.time.ExperimentalTime
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class OpenAIApiService(private var modelId: String, systemMessage: String) {
    private val messages = listOf(
        JSONObject(mapOf("role" to "system", "content" to systemMessage))
    ).toMutableList()

    private val messagesAsStrings = listOf(
        "SYSTEM: $systemMessage"
    ).toMutableList()

    fun addAssistanceResponse(assistantResponse: String?) {
        messages.add(
            JSONObject(
                mapOf(
                    "role" to "assistant", "content" to assistantResponse
                )
            )
        )
        messagesAsStrings.add(
            "ASSISTANT: $assistantResponse"
        )
    }

    fun uploadContextToS3(
        context: Context, bucketName: String, aws3service: AwsS3Service?
    ): CompletableFuture<URL>? {
        return aws3service?.uploadConversationHistory(
            context, bucketName, "context_lillian2.json", "context_lillian2.json", messagesAsStrings
        )
    }

    @ExperimentalTime
    @BetaOpenAI
    fun generateChatCompletion(prompt: String): Response {
        // Append the new user message to the list
        messages.add(JSONObject(mapOf("role" to "user", "content" to prompt)))
        messagesAsStrings.add("USER: $prompt")

        // Initialize OkHttp client
        val client = OkHttpClient()

        // Create request
        val request = Request.Builder().url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            .header("Content-Type", "application/json").post(
                """
        {
            "model": "$modelId",
    "messages": $messages,
    "stream": true
        }
        """.toRequestBody("application/json".toMediaTypeOrNull())
            ).build()

        // Listening for server-sent events
        val call = client.newCall(request)

        return call.execute()
    }

    fun loadContextHistory(contextHistory: List<String>) {
        messagesAsStrings.addAll(contextHistory)
        val loadedMessages = contextHistory.map { message ->
            val isUserMessage = message.startsWith("USER:")
            val role = if (isUserMessage) "user" else "assistant"
            val content = if (isUserMessage) message.substring(6) else message.substring(11)
            JSONObject(
                mapOf(
                    "role" to role, "content" to content.trim()
                )
            )
        }
        messages.addAll(loadedMessages)
    }
}