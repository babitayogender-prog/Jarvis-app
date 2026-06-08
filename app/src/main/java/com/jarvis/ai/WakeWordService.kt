package com.jarvis.ai

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat

class WakeWordService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_wake"
        const val NOTIF_ID = 1
        const val WAKE_WORD = "arise"
        const val SLEEP_WORD = "goodbye"
        var isListening = false
            private set
        var isAwake = false
            private set
    }

    private var recognizer: SpeechRecognizer? = null
    private var shouldRun = true

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                buildNotification("Listening for \"Arise\"…"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Listening for \"Arise\"…"))
        }
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        shouldRun = true
        return START_STICKY
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition not available on this device")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.firstOrNull()?.lowercase() ?: ""
                handleHeard(heard)
                restartListening()
            }
            override fun onError(error: Int) { restartListening() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listenOnce()
    }

    private fun listenOnce() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            isListening = true
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            restartListening()
        }
    }

    private fun restartListening() {
        if (!shouldRun) return
        android.os.Handler(mainLooper).postDelayed({
            if (shouldRun) {
                try { recognizer?.cancel() } catch (_: Exception) {}
                listenOnce()
            }
        }, 600)
    }

    private fun handleHeard(text: String) {
        if (text.isBlank()) return

        if (!isAwake) {
            if (text.contains(WAKE_WORD)) {
                isAwake = true
                updateNotification("● AWAKE — listening for commands")
                AgentEngine.speak(applicationContext, "Yes Boss, I'm listening.")
                val command = text.substringAfter(WAKE_WORD).trim()
                if (command.isNotEmpty()) {
                    AgentEngine.handleVoiceCommand(applicationContext, command)
                }
            }
            return
        }

        if (text.contains(SLEEP_WORD)) {
            isAwake = false
            updateNotification("Listening for \"Arise\"…")
            AgentEngine.speak(applicationContext, "Goodbye Boss. Say Arise when you need me.")
            return
        }

        val command = if (text.contains(WAKE_WORD))
            text.substringAfter(WAKE_WORD).trim()
        else
            text.trim()

        if (command.isNotEmpty()) {
            updateNotification("● AWAKE — heard: $command")
            AgentEngine.handleVoiceCommand(applicationContext, command)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps JARVIS listening for the wake word"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldRun = false
        isListening = false
        try { recognizer?.destroy() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
