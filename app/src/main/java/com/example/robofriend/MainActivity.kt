package com.example.robofriend

import AwsS3Service
import DeepgramService
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import java.io.File
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.aallam.openai.api.BetaOpenAI
import com.example.compose.AppTheme
import com.example.robofriend.apis.elevenlabs.ElevenLabsService
import com.example.robofriend.apis.myopenai.OpenAIApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.lang.Exception
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue


@Serializable
data class Delta(val role: String? = null, val content: String? = null)

@Serializable
data class Choice(val index: Int, val delta: Delta?, val finish_reason: String?)

@Serializable
data class OpenAIResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>
)

val json = Json { ignoreUnknownKeys = true }


class MainActivity : ComponentActivity(), CoroutineScope by MainScope() {
    companion object {
        const val MIN_CHUNK_SIZE = 50 // Change this value according to your needs
        const val LOADING_MESSAGE = "LOADING..."
    }

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private val isRecording = mutableStateOf(false)
    private val isReplaying = mutableStateOf(false)
    private val isMuted = mutableStateOf(false)
    private val replayWhenMuted = mutableStateOf(false)

    // Add this line to initialize the S3Service
    private var awsS3Service: AwsS3Service? = null
    private var deepgramService: DeepgramService? = null
    private var openaiService: OpenAIApiService? = null
    private var elevenLabsApiService: ElevenLabsService? = null
    private var userInput = mutableStateOf("")
    private val lastAIResponseAudio = mutableStateOf<ByteArray?>(null)
    private val conversationHistory = mutableStateListOf<String>()
    private val allConversationHistory = mutableStateListOf<String>()
    private var lastAIResponseText = mutableStateOf<String?>(null)

    private var currentChunkToSpeak = StringBuilder()
    private val textChunksQueue = ConcurrentLinkedQueue<String>()
    private val audioChunksQueue = ConcurrentLinkedQueue<ByteArray?>()
    private val handleAudiolastPunctuationIndex = mutableStateOf(-1)
    private val lastChunk = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleAudio()
        handlePlayAudio()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        awsS3Service = AwsS3Service(this)
        deepgramService = DeepgramService()
        openaiService = OpenAIApiService("gpt-4", "You are a helpful assistant!")
        elevenLabsApiService = ElevenLabsService(
            "eleven_multilingual_v1", 0.5f, 0.85f, 0.5f, false
        )

        awsS3Service?.downloadConversationHistory(
            this, BuildConfig.AWS_BUCKET_NAME, "conversation_history.json"
        )?.thenAccept { history ->
            allConversationHistory.clear()
            allConversationHistory.addAll(history)
        }?.exceptionally { null }
            ?.handle { _, t -> Log.e("AWSS3", "error downloading conversation history: $t") }

        awsS3Service?.downloadConversationHistory(this, BuildConfig.AWS_BUCKET_NAME, "context.json")
            ?.thenAccept { history ->
                val systemMessage = history[0].substring(8)
                openaiService = OpenAIApiService("gpt-4", systemMessage)
                openaiService?.loadContextHistory(history.drop(1))
            }?.exceptionally { null }
            ?.handle { _, t -> Log.e("AWSS3", "error downloading context: $t") }


        // ...

        setContent {
            AppTheme {
                ConstraintLayoutContent()
            }
        }
        requestAudioPermissions()
    }

    @Composable
    fun ConstraintLayoutContent() {
        val focusManager = LocalFocusManager.current
        ConstraintLayout(
            modifier = Modifier
                .fillMaxSize()
                .background(color = MaterialTheme.colorScheme.background)
        ) {
            // Create references for the composable
            val (muteButton, recordButton, replayButton, userInputField, sendButton, chatWindow, snackBar) = createRefs()

            LazyColumn(
                modifier = Modifier.constrainAs(chatWindow) {
                    top.linkTo(parent.top, margin = 8.dp)
                    end.linkTo(parent.end)
                    bottom.linkTo(userInputField.bottom, margin = 64.dp)
                    start.linkTo(parent.start)
                    width = Dimension.fillToConstraints
                    height = Dimension.fillToConstraints
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(conversationHistory) { message ->
                    // Check if the message is from the user or the assistant
                    val isUserMessage = message.startsWith("USER")
                    // Apply different styles based on whether the message is from the user or the assistant
                    MessageBubble(message, isUserMessage)
                }
            }


            TextButton(onClick = { isMuted.value = !isMuted.value },
                modifier = Modifier.constrainAs(muteButton) {
                    // Add your constraints here
                    bottom.linkTo(userInputField.top)
                    end.linkTo(replayButton.start)
                }) {
                if (isMuted.value) {
                    Icon(Icons.Filled.VolumeOff, contentDescription = "Unmute")
                } else {
                    Icon(Icons.Filled.VolumeUp, contentDescription = "Mute")
                }
            }


            FloatingActionButton(onClick = {
                focusManager.clearFocus()
                getAIResponse(userInput.value)
            }, modifier = Modifier.constrainAs(sendButton) {
                end.linkTo(parent.end, margin = 16.dp)
                bottom.linkTo(parent.bottom, margin = 16.dp)
            }) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }

            TextButton(onClick = {
                if (isRecording.value) {
                    stopRecording()
                } else {
                    startRecording()
                }
            }, modifier = Modifier.constrainAs(recordButton) {
                end.linkTo(parent.end, margin = 16.dp)
                bottom.linkTo(sendButton.top)
            }) {
                if (isRecording.value) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop Recording")
                } else {
                    Icon(Icons.Filled.Mic, contentDescription = "Start Recording")
                }
            }

            TextButton(onClick = {
                if (isReplaying.value) {
                    stopReplaying()
                } else {
                    replayLastAIResponse()
                }
            }, modifier = Modifier.constrainAs(replayButton) {
                bottom.linkTo(userInputField.top)
                end.linkTo(recordButton.start)
            }) {
                if (isReplaying.value) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop Replaying")
                } else {
                    Icon(Icons.Filled.Replay, contentDescription = "Replay Last AI Response")
                }
            }

            TextField(value = userInput.value,
                onValueChange = { userInput.value = it },
                label = { Text("Type your prompt here") },
                modifier = Modifier.constrainAs(userInputField) {
                    start.linkTo(parent.start, margin = 16.dp)
                    end.linkTo(sendButton.start, margin = 16.dp)
                    bottom.linkTo(parent.bottom, margin = 16.dp)
                    width = Dimension.fillToConstraints
                })
            if (replayWhenMuted.value) {
                Button(
                    onClick = { replayWhenMuted.value = false },
                    modifier = Modifier.constrainAs(snackBar) {
                        bottom.linkTo(userInputField.top, margin = 16.dp)
                        end.linkTo(recordButton.start, margin = 16.dp)
                        start.linkTo(parent.start, margin = 16.dp)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    enabled = replayWhenMuted.value,
                ) {
                    Text(text = "Unmute first, Please")
                }
            }

        }
    }

    private fun Modifier.copyToClipboardOnClick(textToCopy: String): Modifier = composed {
        val context = LocalContext.current
        val textToCopyState = rememberUpdatedState(textToCopy)

        this.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Copied Text", textToCopyState.value)
            clipboard.setPrimaryClip(clip)
            // You can show a toast or some other indication that the text has been copied
        }
    }

    @Composable
    fun MessageBubble(message: String, isUserMessage: Boolean) {

        val backgroundColor =
            if (isUserMessage) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiaryContainer
        val textColor =
            if (isUserMessage) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer
        val horizontalAlignment = if (isUserMessage) Alignment.End else Alignment.Start
        val subMessage: String = if (message == LOADING_MESSAGE) {
            "..."
        } else {
            if (isUserMessage) message.subSequence(6, message.lastIndex + 1)
                .toString() else message.subSequence(11, message.lastIndex + 1).toString()
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .copyToClipboardOnClick(subMessage), // Apply the custom modifier here
            contentAlignment = if (isUserMessage) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Text(
                text = subMessage,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(color = backgroundColor)
                    .wrapContentWidth(horizontalAlignment)
                    .padding(8.dp),
                color = textColor,
                textAlign = TextAlign.Start
            )
        }
    }


    private fun startRecording() {
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
                val future = awsS3Service?.uploadFile(
                    BuildConfig.AWS_BUCKET_NAME, file, "audio_record.3gp"
                )
                future?.thenAccept { presignedUrl ->
                    Log.d("AWS", "Presigned URL: $presignedUrl")

                    // Use Deepgram to transcribe the audio
                    if (presignedUrl != null) {
                        GlobalScope.launch(Dispatchers.Main) {
                            val transcript =
                                deepgramService?.postAudioUrlForTranscription(presignedUrl.toString())
                            Log.d("Deepgram", "Transcript: $transcript")
                            conversationHistory.add("USER: $transcript")
                            // Save the conversation history after the user prompt is added
                            uploadConversationHistory()
                            // Send the transcript to OpenAI

                            val openaiResponse =
                                withContext(Dispatchers.IO) {  // Move to IO Dispatcher for Network and IO operations
                                    openaiService?.generateChatCompletion(transcript ?: "")
                                }

                            Log.d("OpenAI", "OpenAI Response: $openaiResponse")
                            if (openaiResponse != null) {
                                updateListAndPlay(openaiResponse)
                            }
                        }
                    }
                }?.exceptionally { throwable ->
                    // An error occurred during the upload
                    Log.e("AWS_S3", "Upload failed: ${throwable.message}")
                    null
                }

            } catch (e: RuntimeException) {
                e.printStackTrace()
            }
        }
    }

    private fun uploadConversationHistory() {
        val futureAWSOpenAI = openaiService?.uploadContextToS3(
            this, BuildConfig.AWS_BUCKET_NAME, awsS3Service
        )
        futureAWSOpenAI?.thenAccept { url ->
            Log.d("AWS_S3_CONTEXT", "Conversation history uploaded to: $url")
        }?.exceptionally { throwable ->
            Log.e("AWS_S3_CONTEXT", "Upload failed: ${throwable.message}")
            null
        }

        val futureAWS = awsS3Service?.uploadConversationHistory(
            this,
            BuildConfig.AWS_BUCKET_NAME,
            "conversation_history.json",
            "conversation_history.json",
            allConversationHistory.plus(conversationHistory)
        )
        futureAWS?.thenAccept { url ->
            Log.d("AWS_S3", "Conversation history uploaded to: $url")
        }?.exceptionally { throwable ->
            Log.e("AWS_S3", "Upload failed: ${throwable.message}")
            null
        }
    }

    private suspend fun updateListAndPlay(response: Response?) {
        lastAIResponseAudio.value = null
        lastAIResponseText.value = null
        currentChunkToSpeak.clear()
        textChunksQueue.clear()
        handleAudiolastPunctuationIndex.value = -1
        lastChunk.value = false
        val responseBuilder = StringBuilder()

        // Add a placeholder message to the list which will get updated in real-time
        conversationHistory.add("ASSISTANT: typing...")

        withContext(Dispatchers.IO) {
            if (response != null) {
                response.body?.source()?.let { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8LineStrict()
                        val prefix = "data: "
                        if (line.startsWith(prefix)) {
                            val linewithoutprefix = line.removePrefix(prefix)
                            if (linewithoutprefix == "[DONE]") {
                                // Final updates (if needed)
                                withContext(Dispatchers.Main) {
                                    openaiService?.addAssistanceResponse(responseBuilder.toString())
                                    uploadConversationHistory()
                                    lastAIResponseText.value = responseBuilder.toString()
                                    lastChunk.value = true
                                }
                                return@let
                            }
                            val chunk = json.decodeFromString<OpenAIResponse>(linewithoutprefix)
                            val chunkContent = chunk.choices[0].delta?.content ?: ""
                            responseBuilder.append(chunkContent)

                            // Add the chunk to the queue for audio processing
                            textChunksQueue.add(chunkContent)

                            withContext(Dispatchers.Main) {
                                conversationHistory[conversationHistory.size - 1] =
                                    "ASSISTANT: $responseBuilder"
                            }
                        }
                    }
                }
            }
        }
    }


    private suspend fun MainActivity.playResponse(openaiResponse: String?) {
        if (openaiResponse == null || openaiResponse == "") {
            return
        }
        if (!isMuted.value){ // TODO: remove condition. eventually we'd want to always do
            // text-to-speech in the background even if muted,
            // so the user can replay after un-muting and not wait for the service to respond
            // now I am keeping it this way to decrease billing of ElevenLabs while developing.
            val audio = withContext(Dispatchers.IO) {
                elevenLabsApiService?.textToSpeech(openaiResponse)
            }
            audioChunksQueue.add(audio)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun handlePlayAudio() = GlobalScope.launch(Dispatchers.IO) {
        while (isActive) {
            val nextAudioChunk = audioChunksQueue.poll()
            if (nextAudioChunk != null) {
                // Concatenate the audio bytes
                val newAudioBytes =
                    lastAIResponseAudio.value?.concat(nextAudioChunk) ?: nextAudioChunk
                lastAIResponseAudio.value = newAudioBytes

                // Convert ByteArray to InputStream
                val audioStream = nextAudioChunk.inputStream()
                // Play the audio stream
                playAudio(audioStream)
                // Wait until the audio finishes playing before proceeding to the next chunk
                while (mediaPlayer?.isPlaying == true) {
                    delay(1)
                }
            }
        }
    }

    private fun requestAudioPermissions() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0)
        }
    }

    private fun playAudio(audioStream: InputStream) {
        // Convert InputStream to a file or a suitable format for MediaPlayer
        if (!isMuted.value) {
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
    }

    private fun inputStreamToFile(inputStream: InputStream): File {
        val file = File.createTempFile("audio", "mp3")
        file.outputStream().use { inputStream.copyTo(it) }
        return file
    }

    @OptIn(ExperimentalTime::class, BetaOpenAI::class, DelicateCoroutinesApi::class)
    private fun getAIResponse(input: String) {
        GlobalScope.launch(Dispatchers.Main) {
            lastAIResponseAudio.value = null
            lastAIResponseText.value = null
            currentChunkToSpeak.clear()
            textChunksQueue.clear()
            handleAudiolastPunctuationIndex.value = -1
            lastChunk.value = false

            conversationHistory.add("USER: $input")
            userInput.value = ""
            val inputMethodManager =
                getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(currentFocus?.windowToken, 0)

            // Add a placeholder message to the list which will get updated in real-time
            conversationHistory.add("ASSISTANT: typing...")
            val responseBuilder = StringBuilder()

            val response =
                withContext(Dispatchers.IO) {  // Move to IO Dispatcher for Network and IO operations
                    openaiService?.generateChatCompletion(input)
                }

            if (response != null) {
                withContext(Dispatchers.IO) {
                    response.body?.source()?.let { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8LineStrict()
                            val prefix = "data: "
                            if (line.startsWith(prefix)) {
                                val linewithoutprefix = line.removePrefix(prefix)
                                if (linewithoutprefix == "[DONE]") {
                                    openaiService?.addAssistanceResponse(responseBuilder.toString())
                                    uploadConversationHistory()
                                    lastAIResponseText.value = responseBuilder.toString()
                                    lastChunk.value = true
                                    return@let
                                }
                                val chunk = json.decodeFromString<OpenAIResponse>(linewithoutprefix)
                                val chunkContent = chunk.choices[0].delta?.content ?: ""
                                responseBuilder.append(chunkContent)

                                // Add the chunk to the queue for audio processing
                                textChunksQueue.add(chunkContent)

                                // Switch to Main dispatcher just for UI update
                                withContext(Dispatchers.Main) {
                                    conversationHistory[conversationHistory.size - 1] =
                                        "ASSISTANT: $responseBuilder"
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    @OptIn(DelicateCoroutinesApi::class)
    fun handleAudio() = GlobalScope.launch(Dispatchers.IO) {
        while (isActive) {
            val nextTextChunk = textChunksQueue.poll()
            if (nextTextChunk != null || currentChunkToSpeak.isNotEmpty()) {
                try {
                    if (nextTextChunk != null) {
                        currentChunkToSpeak.append(nextTextChunk)
                    }

                    if (currentChunkToSpeak.contains('.') && currentChunkToSpeak.lastIndexOf('.') > handleAudiolastPunctuationIndex.value) {
                        handleAudiolastPunctuationIndex.value = currentChunkToSpeak.lastIndexOf('.')
                    }

                    if (currentChunkToSpeak.contains('!') && currentChunkToSpeak.lastIndexOf('!') > handleAudiolastPunctuationIndex.value) {
                        handleAudiolastPunctuationIndex.value = currentChunkToSpeak.lastIndexOf('!')
                    }

                    if (currentChunkToSpeak.contains('?') && currentChunkToSpeak.lastIndexOf('?') > handleAudiolastPunctuationIndex.value) {
                        handleAudiolastPunctuationIndex.value = currentChunkToSpeak.lastIndexOf('?')
                    }

                    if (currentChunkToSpeak.contains('\n') && currentChunkToSpeak.lastIndexOf('\n') > handleAudiolastPunctuationIndex.value) {
                        handleAudiolastPunctuationIndex.value =
                            currentChunkToSpeak.lastIndexOf('\n')
                    }

                    if (handleAudiolastPunctuationIndex.value >= MIN_CHUNK_SIZE) {
                        Log.d("playResponseWhole", currentChunkToSpeak.toString())
                        Log.d(
                            "playResponseLastSign", handleAudiolastPunctuationIndex.value.toString()
                        )
                        Log.d(
                            "playResponseTrimmed", currentChunkToSpeak.substring(
                                0, handleAudiolastPunctuationIndex.value + 1
                            )
                        )
                        playResponse(
                            currentChunkToSpeak.substring(
                                0, handleAudiolastPunctuationIndex.value + 1
                            )
                        )
                        currentChunkToSpeak = StringBuilder(
                            currentChunkToSpeak.substring(handleAudiolastPunctuationIndex.value + 1)
                        )
                        handleAudiolastPunctuationIndex.value = -1
                    } else {
                        if (textChunksQueue.isEmpty() && lastChunk.value && (currentChunkToSpeak.endsWith(
                                '.'
                            ) || currentChunkToSpeak.endsWith('!') || currentChunkToSpeak.endsWith(
                                '?'
                            ) || currentChunkToSpeak.endsWith('\n'))
                        ) {
                            playResponse(currentChunkToSpeak.toString())
                            currentChunkToSpeak.clear()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Elevenlabs", "could not play audio")
                }
            } else {
                delay(100)  // 100 ms
            }
        }
    }


    @OptIn(DelicateCoroutinesApi::class)
    private fun replayLastAIResponse() {
        Log.d("replayLastAIResponseD", "isMuted is: ${isMuted.value}")
        if (isMuted.value) {
            this.launch {
                replayWhenMuted.value = true
                delay(1000L)
                replayWhenMuted.value = false
            }
        } else {
            isReplaying.value = true
            Log.d("replayLastAIResponseD", "isMuted is False")
            val audioStream = lastAIResponseAudio.value?.inputStream()
            // Play the audio stream
            if (audioStream != null) {
                Log.d("replayLastAIResponseD", "audioStream is not null")
                playAudio(audioStream)
            } else {
                Log.d("replayLastAIResponseD", "audioStream is null")
                Log.d(
                    "replayLastAIResponseD",
                    "lastAIResponseAudio is null: ${lastAIResponseAudio.value == null}"
                )
                Log.d(
                    "replayLastAIResponseD",
                    "lastAIResponseText is null: ${lastAIResponseText.value == null}"
                )
                // if the last message was not converted to speech because it was muted
                if (lastAIResponseAudio.value == null && lastAIResponseText.value != null) {
                    // Generate the speech for the last AI response text
                    GlobalScope.launch(Dispatchers.Main) {
                        try {
                            playResponse(lastAIResponseText.value!!)
                        } catch (e: Exception) {
                            Log.e("Elevenlabs", "could not play audio")
                        }
                    }
                } else {
                    Log.e("MainActivity", "Failed to convert audio bytes to stream")
                }
            }
        }
    }


    private fun stopReplaying() {
        mediaPlayer?.stop()
        isReplaying.value = false
    }

    private fun ByteArray.concat(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        System.arraycopy(this, 0, result, 0, this.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }


}
