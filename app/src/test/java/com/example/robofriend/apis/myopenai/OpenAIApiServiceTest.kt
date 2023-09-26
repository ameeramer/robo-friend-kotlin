package com.example.robofriend.apis.myopenai

import com.aallam.openai.api.BetaOpenAI

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.time.ExperimentalTime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Delta(val role: String? = null, val content: String? = null)

@Serializable
data class Choice(val index: Int, val delta: Delta?, val finish_reason: String?)

@Serializable
data class OpenAIResponse(val id: String, val `object`: String, val created: Long, val model: String, val choices: List<Choice>)

val json = Json { ignoreUnknownKeys = true }
class OpenAIApiServiceTest {
    @OptIn(ExperimentalTime::class, BetaOpenAI::class)
    @Test
    fun testGenerateCompletion(): Unit = runBlocking {
        val service = OpenAIApiService("gpt-4","You are a helpful assistant!")
        val prompt = "Translate the following English text to French: 'Hello, how are you?'"
        val response = service.generateChatCompletion(prompt)
        response.body?.source()?.let { source ->
            while (!source.exhausted()) {
                val line = source.readUtf8LineStrict()
                val prefix = "data: "
                if (line.startsWith(prefix)) {
                    val lineWithoutPrefix = line.removePrefix(prefix)
                    if (lineWithoutPrefix == "[DONE]"){
                        break
                    }
                    val obj = json.decodeFromString<OpenAIResponse>(lineWithoutPrefix)
                    val content = obj.choices.firstOrNull()?.delta?.content ?: ""
                    println(content)
                    // Here you can update your conversationHistory with `content`
                }
            }
        }
        println(service.numTokensFromMessages())
    }
}