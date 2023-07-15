package com.example.robofriend

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import java.io.File
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException

class MainActivity : ComponentActivity() {
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(onClick = { startRecording() }) {
                    Text("Start Recording")
                }
                Button(onClick = { stopRecording() }) {
                    Text("Stop Recording")
                }
                Button(onClick = { startPlaying() }) {
                    Text("Play Audio")
                }
                Button(onClick = { stopPlaying() }) {
                    Text("Stop Audio")
                }
            }
        }
        requestAudioPermissions()
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
        isRecording = true
    }

    private fun stopRecording() {
        if (isRecording) {
            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
                mediaRecorder = null
                isRecording = false
            } catch (e: RuntimeException) {
                e.printStackTrace()
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
}