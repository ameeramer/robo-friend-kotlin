package com.example.robofriend.apis.elevenlabs

import com.example.robofriend.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

data class VoiceSettings(
    val stability: Float,
    val similarity_boost: Float,
    val style: Float,
    val use_speaker_boost: Boolean
)

data class Body(
    val text: String,
    val model_id: String,
    val voice_settings: VoiceSettings
)

class ElevenLabsService(
    private val modelId: String,
    private val stability: Float,
    private val similarityBoost: Float,
    private val style: Float,
    private val useSpeakerBoost: Boolean,
    private val apiKey: String = BuildConfig.ELEVENLABS_API_KEY,
    private val voiceId: String = BuildConfig.ELEVENLABS_VOICE_ID
) {
    private val client = OkHttpClient().newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)  // Set the timeout to 60 seconds
        .build()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Throws(IOException::class)
    fun textToSpeech(text: String): ByteArray {
        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId?optimize_streaming_latency=0"

        val bodyObj = Body(
            text = text,
            model_id = modelId,
            voice_settings = VoiceSettings(
                stability = stability,
                similarity_boost = similarityBoost,
                style = style,
                use_speaker_boost = useSpeakerBoost
            )
        )

        val bodyJson = moshi.adapter(Body::class.java).toJson(bodyObj)
        val body = bodyJson.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("accept", "audio/mpeg")
            .addHeader("xi-api-key", apiKey)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            return response.body!!.bytes()
        }
    }
}
