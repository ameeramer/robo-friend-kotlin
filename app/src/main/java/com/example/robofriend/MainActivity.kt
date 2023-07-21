package com.example.robofriend

import AwsS3Service
import DeepgramService
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import java.io.File
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.aallam.openai.api.BetaOpenAI
import com.example.robofriend.BuildConfig
import com.example.robofriend.apis.elevenlabs.ElevenLabsService
import com.example.robofriend.apis.myopenai.OpenAIApiService
import com.example.robofriend.ui.theme.RoboFriendTheme
import com.google.accompanist.insets.navigationBarsWithImePadding
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import kotlin.time.ExperimentalTime


class MainActivity : ComponentActivity() {
    companion object {
        const val MAX_CHUNK_SIZE = 200 // Change this value according to your needs
    }
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private val isRecording = mutableStateOf(false)
    private val isReplaying = mutableStateOf(false)

    // Add this line to initialize the S3Service
    private var awsS3Service: AwsS3Service? = null
    private var deepgramService: DeepgramService? = null
    private var openaiService: OpenAIApiService? = null
    private var elevenLabsApiService: ElevenLabsService? = null
    private var userInput = mutableStateOf("")
    private var aiResponse = mutableStateOf("")
    private val lastAIResponseAudio = mutableStateOf<ByteArray?>(null)
    private val conversationHistory = mutableStateListOf<String>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        awsS3Service = AwsS3Service(this)
        deepgramService = DeepgramService()
        openaiService = OpenAIApiService("gpt-4","You are a helpful assistant!")
        elevenLabsApiService = ElevenLabsService("eleven_monolingual_v1", 0.5f,
            0.85f, 0.5f, false)


        // ...

        setContent {
            RoboFriendTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(color = MaterialTheme.colorScheme.background)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 70.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(conversationHistory) { item ->
                            Text(item)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding().imePadding()
                            .padding(16.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FloatingActionButton(onClick = {
                                if (isRecording.value) {
                                    stopRecording()
                                } else {
                                    startRecording()
                                }
                            }) {
                                if (isRecording.value) {
                                    Icon(Icons.Filled.Stop, contentDescription = "Stop Recording")
                                } else {
                                    Icon(Icons.Filled.Mic, contentDescription = "Start Recording")
                                }
                            }
                            FloatingActionButton(onClick = {
                                if (lastAIResponseAudio.value != null) {
                                    if (isReplaying.value) {
                                        stopReplaying()
                                    } else {
                                        replayLastAIResponse()
                                    }
                                }
                            }) {
                                if (isReplaying.value) {
                                    Icon(Icons.Filled.Stop, contentDescription = "Stop Replaying")
                                } else {
                                    Icon(Icons.Filled.Replay, contentDescription = "Replay Last AI Response")
                                }
                            }
                            TextField(
                                value = userInput.value,
                                onValueChange = { userInput.value = it },
                                label = { Text("Type your prompt here") },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp)
                                    .padding(end = 8.dp)
                            )
                            FloatingActionButton(onClick = { getAIResponse(userInput.value) }) {
                                Icon(Icons.Filled.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        }
        requestAudioPermissions()
    }

    private fun startRecording() {
        lastAIResponseAudio.value = null
        val fileName = applicationContext.filesDir.absolutePath + "/audio_record.3gp"

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setOutputFile(fileName)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)

            try {
                prepare()
                start()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        isRecording.value = true
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalTime::class, BetaOpenAI::class)
    private fun stopRecording() {
        if (isRecording.value) {
            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
                mediaRecorder = null
                isRecording.value = false

                // Upload the file to S3 and get a presigned URL when the recording is stopped
                val fileName = applicationContext.filesDir.absolutePath + "/audio_record.3gp"
                val file = File(fileName)
                val presignedUrl = awsS3Service?.uploadFile(
                    BuildConfig.AWS_BUCKET_NAME,
                    file, "audio_record.3gp")
                Log.d("AWS", "Presigned URL: $presignedUrl")

                // Use Deepgram to transcribe the audio
                if (presignedUrl != null) {
                    GlobalScope.launch(Dispatchers.Main) {
                        val transcript = deepgramService?.postAudioUrlForTranscription(presignedUrl.toString())
                        Log.d("Deepgram", "Transcript: $transcript")
                        // Send the transcript to OpenAI
                        val openaiResponse = openaiService?.generateChatCompletion(transcript ?: "")
                        Log.d("OpenAI", "OpenAI Response: $openaiResponse")
                        update_list_and_play(transcript, openaiResponse)
                    }
                }

            } catch (e: RuntimeException) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun update_list_and_play(transcript: String?, openaiResponse: String?) {
        aiResponse.value = "USER: $transcript\n\nASSISTANT: $openaiResponse"
        conversationHistory.add("USER: $transcript")
        conversationHistory.add("ASSISTANT: $openaiResponse")
        // Split the OpenAI response into chunks
        val responseChunks = splitTextIntoChunks(openaiResponse ?: "", MAX_CHUNK_SIZE)

        // Convert each chunk of the OpenAI response to speech
        for (chunk in responseChunks) {
            val audioBytes = withContext(Dispatchers.IO) {
                elevenLabsApiService?.textToSpeech(chunk)
            }
            // Concatenate the audio bytes
            val newAudioBytes = lastAIResponseAudio.value?.concat(audioBytes ?: ByteArray(0)) ?: audioBytes
            lastAIResponseAudio.value = newAudioBytes

            // Convert ByteArray to InputStream
            val audioStream = audioBytes?.inputStream()
            // Play the audio stream
            if (audioStream != null) {
                playAudio(audioStream)
                // Wait until the audio finishes playing before proceeding to the next chunk
                while (mediaPlayer?.isPlaying == true) {
                    delay(100)
                }
            } else {
                Log.e("MainActivity", "Failed to convert audio bytes to stream")
            }
        }
    }

    private fun startPlaying() {
        val fileName = applicationContext.filesDir.absolutePath + "/audio_record.3gp"
        val file = File(fileName)
        if (!file.exists()) {
            Log.d("MainActivity", "Audio file does not exist: $fileName")
            return
        }
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(fileName)
                prepare()
                start()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun stopPlaying() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun requestAudioPermissions() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0)
        }
    }

    private fun playAudio(audioStream: InputStream) {
        // Convert InputStream to a file or a suitable format for MediaPlayer
        val file = inputStreamToFile(audioStream)

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    isReplaying.value = false
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun inputStreamToFile(inputStream: InputStream): File {
        val file = File.createTempFile("audio", "mp3")
        file.outputStream().use { inputStream.copyTo(it) }
        return file
    }

    @OptIn(ExperimentalTime::class, BetaOpenAI::class)
    private fun getAIResponse(input: String) {
        GlobalScope.launch(Dispatchers.Main) {
            lastAIResponseAudio.value = null
            val openaiResponse = openaiService?.generateChatCompletion(input)
            Log.d("OpenAI", "OpenAI Response: $openaiResponse")
            userInput.value = ""
            update_list_and_play(input, openaiResponse)
        }
    }

    private fun replayLastAIResponse() {
        // Convert ByteArray to InputStream
        isReplaying.value = true
        val audioStream = lastAIResponseAudio.value?.inputStream()
        // Play the audio stream
        if (audioStream != null) {
            playAudio(audioStream)
        } else {
            Log.e("MainActivity", "Failed to convert audio bytes to stream")
        }
    }

    private fun stopReplaying() {
        mediaPlayer?.stop()
        isReplaying.value = false
    }

    fun splitTextIntoChunks(text: String, maxSize: Int): List<String> {
        val chunks = mutableListOf<String>()
        var chunk = StringBuilder()
        var lastPunctuationIndex = -1
        var prev_length = 0

        for ((i, c) in text.withIndex()) {
            chunk.append(c)

            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                lastPunctuationIndex = i
            }

            if (chunk.length >= maxSize && lastPunctuationIndex != -1) {
                chunks.add(chunk.substring(0, lastPunctuationIndex + 1 - prev_length))
                Log.d("splitTextIntoChunksD", "added chunk ${chunks[chunks.lastIndex]}")
                chunk = StringBuilder(chunk.substring(lastPunctuationIndex + 1 - prev_length))
                Log.d("splitTextIntoChunksD", "chunk is now $chunk")
                prev_length = lastPunctuationIndex
                lastPunctuationIndex = -1
            }
        }

        if (chunk.isNotEmpty()) {
            chunks.add(chunk.toString())
        }

        return chunks
    }

    fun ByteArray.concat(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        System.arraycopy(this, 0, result, 0, this.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }


}
