package com.example.autocall

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CallAudioRecorder(private val context: Context) {
    private val tag = "CallAudioRecorder"
    @Volatile private var isRecording = false
    @Volatile private var currentFile: File? = null
    private var recorder: MediaRecorder? = null

    @Suppress("DEPRECATION")
    @SuppressLint("ObsoleteSdkInt")
    @Synchronized
    fun startRecording(phone: String): String? {
        if (isRecording) return currentFile?.absolutePath
        return try {
            val dir = File(context.getExternalFilesDir(null), "call_records").apply { if (!exists()) mkdirs() }
            val name = "call_${phone}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.mp3"
            currentFile = File(dir, name)
            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            currentFile?.absolutePath
        } catch (e: Exception) {
            Log.e(tag, LanguageManager.getString("log.start_recording_failed", e.message ?: ""))
            recorder?.release()
            recorder = null
            isRecording = false
            currentFile = null
            null
        }
    }

    @Synchronized
    fun stopRecording(): String? {
        if (!isRecording) return null
        return try {
            recorder?.apply { stop(); release() }
            recorder = null
            isRecording = false
            currentFile?.absolutePath
        } catch (e: Exception) {
            Log.e(tag, LanguageManager.getString("log.stop_recording_failed", e.message ?: ""))
            recorder?.release(); recorder = null; isRecording = false; currentFile = null; null
        }
    }

    @Synchronized
    fun release() {
        if (isRecording) stopRecording()
        recorder?.release()
        recorder = null
        isRecording = false
        currentFile = null
    }

    fun isRecording() = isRecording
}