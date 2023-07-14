package com.example.robofriend.apis.myopenai

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
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

class OpenAIApiService(private var modelId: String, private var systemMessage: String) {
    private val openai = OpenAI(
        token  = BuildConfig.OPENAI_API_KEY,
        timeout = Timeout(socket = 60.seconds)
    )

    @ExperimentalTime
    @BetaOpenAI
    suspend fun generateChatCompletion(prompt: String): String? {
        val chatCompletionRequest = ChatCompletionRequest(
            model = ModelId(modelId),
            messages = listOf(
                ChatMessage(
                    role = ChatRole.System,
                    content = systemMessage
                ),
                ChatMessage(
                    role = ChatRole.User,
                    content = prompt
                )
            )
        )
//        val completion: Flow<ChatCompletionChunk> = openai.chatCompletions(chatCompletionRequest)
        val completion: ChatCompletion = openai.chatCompletion(chatCompletionRequest)
        val response = completion
        return response.choices.first().message?.content //.delta?.content
    }
}