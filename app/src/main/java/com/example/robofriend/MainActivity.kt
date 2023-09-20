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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.lang.Exception
import kotlin.time.ExperimentalTime


class MainActivity : ComponentActivity(), CoroutineScope by MainScope() {
    companion object {
        const val MAX_CHUNK_SIZE = 200 // Change this value according to your needs
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        awsS3Service = AwsS3Service(this)
        deepgramService = DeepgramService()
        openaiService = OpenAIApiService("gpt-4","You are a helpful assistant!")
        elevenLabsApiService = ElevenLabsService("eleven_multilingual_v1", 0.5f,
            0.85f, 0.5f, false)

        awsS3Service?.downloadConversationHistory(this, BuildConfig.AWS_BUCKET_NAME, "conversation_history.json")
            ?.thenAccept { history ->
                allConversationHistory.clear()
                allConversationHistory.addAll(history)
            }?.exceptionally { null }?.handle { e, t -> Log.e("AWSS3", "error downloading conversation history: $t" ) }

        awsS3Service?.downloadConversationHistory(this, BuildConfig.AWS_BUCKET_NAME, "context.json")
            ?.thenAccept { history ->
                val systemMessage = history[0].substring(8)
                openaiService = OpenAIApiService("gpt-4", systemMessage)
                openaiService?.loadContextHistory(history.drop(1))
            }?.exceptionally { null }?.handle { e, t -> Log.e("AWSS3", "error downloading context: $t" ) }



        // ...

        setContent {
            AppTheme {
                ConstraintLayoutContent()
            }
        }
        requestAudioPermissions()
    }

    @Composable
    fun ConstraintLayoutContent(){
        val focusManager = LocalFocusManager.current
        ConstraintLayout(
            modifier = Modifier
                .fillMaxSize()
                .background(color = MaterialTheme.colorScheme.background)
        ) {
            // Create references for the composable
            val (muteButton, recordButton, replayButton, userInputField, sendButton, chatWindow,
                snackBar) = createRefs()

            LazyColumn(
                modifier = Modifier
                    .constrainAs(chatWindow) {
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


            TextButton(
                onClick = { isMuted.value = !isMuted.value },
                modifier = Modifier
                    .constrainAs(muteButton) {
                        // Add your constraints here
                        bottom.linkTo(userInputField.top)
                        end.linkTo(replayButton.start)
                    }
            ) {
                if (isMuted.value) {
                    Icon(Icons.Filled.VolumeOff, contentDescription = "Unmute")
                } else {
                    Icon(Icons.Filled.VolumeUp, contentDescription = "Mute")
                }
            }


            FloatingActionButton(
                onClick = { focusManager.clearFocus();
                    getAIResponse(userInput.value) },
                modifier = Modifier.constrainAs(sendButton) {
                    end.linkTo(parent.end, margin = 16.dp)
                    bottom.linkTo(parent.bottom, margin = 16.dp)
                }
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }

            TextButton(
                onClick = {
                    if (isRecording.value) {
                        stopRecording()
                    } else {
                        startRecording()
                    }
                },
                modifier = Modifier.constrainAs(recordButton) {
                    end.linkTo(parent.end, margin = 16.dp)
                    bottom.linkTo(sendButton.top)
                }
            ) {
                if (isRecording.value) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop Recording")
                } else {
                    Icon(Icons.Filled.Mic, contentDescription = "Start Recording")
                }
            }

            TextButton(
                onClick = {
                    if (isReplaying.value) {
                        stopReplaying()
                    } else {
                        replayLastAIResponse()
                    }
                },
                modifier = Modifier.constrainAs(replayButton) {
                    bottom.linkTo(userInputField.top)
                    end.linkTo(recordButton.start)
                }
            ) {
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
                    .constrainAs(userInputField) {
                        start.linkTo(parent.start, margin = 16.dp)
                        end.linkTo(sendButton.start, margin = 16.dp)
                        bottom.linkTo(parent.bottom, margin = 16.dp)
                        width = Dimension.fillToConstraints
                    }
            )
            if (replayWhenMuted.value){
                Button(
                    onClick = { replayWhenMuted.value = false},
                    modifier = Modifier.constrainAs(snackBar){
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

    @Composable
    fun Modifier.copyToClipboardOnClick(textToCopy: String): Modifier {
        val context = LocalContext.current
        val textToCopyState = rememberUpdatedState(textToCopy)

        return this.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Copied Text", textToCopyState.value)
            clipboard.setPrimaryClip(clip)
            // You can show a toast or some other indication that the text has been copied
        }
    }

    @Composable
    fun MessageBubble(message: String, isUserMessage: Boolean) {

        val backgroundColor = if (isUserMessage) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiaryContainer
        val textColor = if (isUserMessage) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer
        val horizontalAlignment = if (isUserMessage) Alignment.End else Alignment.Start
        var sub_message: String? = null
        sub_message = if (message == LOADING_MESSAGE){
            "..."
        } else {
            if (isUserMessage) message.subSequence(6, message.lastIndex + 1).toString() else message.subSequence(11, message.lastIndex + 1)
                .toString()
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .copyToClipboardOnClick(sub_message), // Apply the custom modifier here
            contentAlignment = if (isUserMessage) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Text(
                text = sub_message,
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
        lastAIResponseAudio.value = null
        lastAIResponseText.value = null
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
                val future = awsS3Service?.uploadFile(BuildConfig.AWS_BUCKET_NAME,
                    file, "audio_record.3gp")
                future?.thenAccept { presignedUrl ->
                    Log.d("AWS", "Presigned URL: $presignedUrl")

                    // Use Deepgram to transcribe the audio
                    if (presignedUrl != null) {
                        GlobalScope.launch(Dispatchers.Main) {
                            val transcript =
                                deepgramService?.postAudioUrlForTranscription(presignedUrl.toString())
                            Log.d("Deepgram", "Transcript: $transcript")
                            conversationHistory.add("USER: $transcript")
                            // Save the conversation history after the AI response is added
                            uploadConversationHistory()
                            // Send the transcript to OpenAI
                            val openaiResponse =
                                openaiService?.generateChatCompletion(transcript ?: "")
                            Log.d("OpenAI", "OpenAI Response: $openaiResponse")
                            update_list_and_play(openaiResponse)
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
            this,
            BuildConfig.AWS_BUCKET_NAME,
            awsS3Service
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

    private suspend fun update_list_and_play(openaiResponse: String?) {
        lastAIResponseText.value = openaiResponse
        conversationHistory.remove(LOADING_MESSAGE)
        conversationHistory.add("ASSISTANT: $openaiResponse")
        openaiService?.addAssistanceResponse(openaiResponse)
        uploadConversationHistory()

        if (!isMuted.value) {
            try{
                playResponse(openaiResponse)
            }
            catch (e: Exception){
                Log.e("Elevenlabs", "could not play audio")
            }
        }
    }

    private suspend fun MainActivity.playResponse(openaiResponse: String?) {
        // Split the OpenAI response into chunks
        val responseChunks = splitTextIntoChunks(openaiResponse ?: "", MAX_CHUNK_SIZE)

        val audioChunks = responseChunks.drop(1).map { chunk ->
            coroutineScope {
                async(Dispatchers.IO) {
                    elevenLabsApiService?.textToSpeech(chunk)
                }
            }
        }

        val firstAudioBytes = withContext(Dispatchers.IO) {
            elevenLabsApiService?.textToSpeech(responseChunks[0])
        }
        process_audio_bytes(firstAudioBytes)


        for (audioChunk in audioChunks) {
            // We use `await` to wait for the audio data to become available
            val audioBytes = audioChunk.await()
            // Concatenate the audio bytes
            process_audio_bytes(audioBytes)
        }
    }

    private suspend fun process_audio_bytes(audioBytes: ByteArray?) {
        // Concatenate the audio bytes
        val newAudioBytes =
            lastAIResponseAudio.value?.concat(audioBytes ?: ByteArray(0)) ?: audioBytes
        lastAIResponseAudio.value = newAudioBytes

        // Convert ByteArray to InputStream
        val audioStream = audioBytes?.inputStream()
        // Play the audio stream
        if (audioStream != null) {
            playAudio(audioStream)
            // Wait until the audio finishes playing before proceeding to the next chunk
            while (mediaPlayer?.isPlaying == true) {
                delay(1)
            }
        } else {
            Log.e("MainActivity", "Failed to convert audio bytes to stream")
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

    @OptIn(ExperimentalTime::class, BetaOpenAI::class, DelicateCoroutinesApi::class)
    private fun getAIResponse(input: String) {
        GlobalScope.launch(Dispatchers.Main) {
            conversationHistory.add("USER: $input")
            conversationHistory.add(LOADING_MESSAGE)
            uploadConversationHistory()
            userInput.value = ""
            val inputMethodManager = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
            lastAIResponseAudio.value = null
            lastAIResponseText.value = null
            val openaiResponse = openaiService?.generateChatCompletion(input)
            Log.d("OpenAI", "OpenAI Response: $openaiResponse")
            update_list_and_play(openaiResponse)
        }
    }

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
                Log.d("replayLastAIResponseD", "lastAIResponseAudio is null: ${lastAIResponseAudio.value == null}")
                Log.d("replayLastAIResponseD", "lastAIResponseText is null: ${lastAIResponseText.value == null}")
                // if the last message was not converted to speech because it was muted
                if (lastAIResponseAudio.value == null && lastAIResponseText.value != null) {
                    // Generate the speech for the last AI response text
                    GlobalScope.launch(Dispatchers.Main) {
                        try{
                            playResponse(lastAIResponseText.value!!)
                        }
                        catch (e: Exception){
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
                prev_length = lastPunctuationIndex + 1
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
