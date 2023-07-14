package com.example.robofriend.apis.myopenai

import com.aallam.openai.api.BetaOpenAI
import org.junit.jupiter.api.Assertions.*

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.time.ExperimentalTime

class OpenAIApiServiceTest {
    @OptIn(ExperimentalTime::class, BetaOpenAI::class)
    @Test
    fun testGenerateCompletion() = runBlocking {
        val service = OpenAIApiService("gpt-4","You are a helpful assistant!")
        val prompt = "Translate the following English text to French: 'Hello, how are you?'"
        val completion = service.generateChatCompletion(prompt)
        assertNotNull(completion)
        println(completion)
    }
}