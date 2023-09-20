package com.example.robofriend.apis.myopenai

import AwsS3Service
import android.content.Context
import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.chat.ChatCompletion
//import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.example.robofriend.BuildConfig
import com.aallam.openai.client.OpenAI
import java.net.URL
import java.util.concurrent.CompletableFuture
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

class OpenAIApiService(private var modelId: String, private var systemMessage: String) {
    private val openai = OpenAI(
        token  = BuildConfig.OPENAI_API_KEY,
        timeout = Timeout(socket = 60.seconds)
    )

    @OptIn(BetaOpenAI::class)
    private val messages = listOf(
        ChatMessage(
            role = ChatRole.System,
            content = systemMessage
        )).toMutableList()

    private val messagesAsStrings = listOf(
        "SYSTEM: $systemMessage"
    ).toMutableList()

    @OptIn(BetaOpenAI::class)
    fun addAssistanceResponse(assistantResponse: String?) {
        messages.add(
            ChatMessage(
                role = ChatRole.Assistant,
                content = assistantResponse
            )
        )
        messagesAsStrings.add(
            "ASSISTANT: $assistantResponse"
        )
    }

    fun uploadContextToS3(context: Context, bucketName: String, aws3service: AwsS3Service?): CompletableFuture<URL>? {
        return aws3service?.uploadConversationHistory(context, bucketName, "context.json", "context.json", messagesAsStrings)
    }

    @ExperimentalTime
    @BetaOpenAI
    suspend fun generateChatCompletion(prompt: String): String? {
        // Append the new user message to the list
        messages.add(
            ChatMessage(
                role = ChatRole.User,
                content = prompt
            )
        )
        messagesAsStrings.add(
            "USER: $prompt"
        )

        val chatCompletionRequest = ChatCompletionRequest(
            model = ModelId(modelId),
            messages = messages
        )

        val completion: ChatCompletion = openai.chatCompletion(chatCompletionRequest)
        val response = completion
        return response.choices.first().message?.content
    }

    @OptIn(BetaOpenAI::class)
    fun loadContextHistory(contextHistory: List<String>) {
        messagesAsStrings.addAll(contextHistory)
        val loadedMessages = contextHistory.map { message ->
            val isUserMessage = message.startsWith("USER:")
            val role = if (isUserMessage) ChatRole.User else ChatRole.Assistant
            val content = if (isUserMessage) message.substring(6) else message.substring(11)
            ChatMessage(
                role = role,
                content = content.trim()
            )
        }
        messages.addAll(loadedMessages)
    }
}