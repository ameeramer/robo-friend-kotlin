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

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType

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

    private fun numTokensFromMessagesGeneral(messages: MutableList<JSONObject>, model: String = "gpt-4"): Int {
        val registry: EncodingRegistry = Encodings.newDefaultEncodingRegistry()
        val encoding: Encoding = registry.getEncoding(EncodingType.CL100K_BASE)

        var tokensPerMessage = 0
        var tokensPerName = 0

        when (model) {
            "gpt-3.5-turbo-0613", "gpt-3.5-turbo-16k-0613", "gpt-4-0314",
            "gpt-4-32k-0314", "gpt-4-0613", "gpt-4-32k-0613" -> {
                tokensPerMessage = 3
                tokensPerName = 1
            }
            "gpt-3.5-turbo-0301" -> {
                tokensPerMessage = 4
                tokensPerName = -1
            }
            else -> {
                return if (model.contains("gpt-3.5-turbo")){
                    numTokensFromMessagesGeneral(messages, model="gpt-3.5-turbo-0613")
                } else {
                    if (model.contains("gpt-4")){
                        numTokensFromMessagesGeneral(messages, model="gpt-4-0613")
                    } else {
                        throw NotImplementedError("numTokensFromMessages() is not implemented for model $model.")
                    }
                }
            }
        }

        var numTokens = 0

        for (message in messages) {
            numTokens += tokensPerMessage
            if (message.has("role")){
                val role = message.getString("role")
                numTokens += encoding.encode(role).size
            }
            if (message.has("content")){
                val content = message.getString("content")
                numTokens += encoding.encode(content).size
            }
            if (message.has("name")) {
                numTokens += tokensPerName
            }
        }

        numTokens += 3  // every reply is primed with <|start|>assistant<|message|>

        return numTokens
    }

    fun numTokensFromMessages(): Int{
        return numTokensFromMessagesGeneral(messages, modelId)
    }
}