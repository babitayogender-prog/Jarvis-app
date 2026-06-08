package com.jarvis.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private val GEMINI_API_KEY = "PASTE_YOUR_GEMINI_API_KEY_HERE"

    private lateinit var chatLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var statusText: TextView
    private lateinit var agentListText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatLayout = findViewById(R.id.chatLayout)
        scrollView = findViewById(R.id.scrollView)
        inputField = findViewById(R.id.inputField)
        statusText = findViewById(R.id.statusText)
        agentListText = findViewById(R.id.agentListText)

        AgentEngine.geminiApiKey = GEMINI_API_KEY
        AgentEngine.initTts(this)

        findViewById<Button>(R.id.sendButton).setOnClickListener { onSend() }
        findViewById<Button>(R.id.refreshAgents).setOnClickListener { refreshAgentList() }
        findViewById<Button>(R.id.enableWake).setOnClickListener { setupEverything() }

        addMessage("Hello Boss! I'm JARVIS. Say \"Arise\" anytime to wake me.\n\n" +
                "You can:\n• Chat with me\n• Create agents: \"Create an agent called Morning that opens Gmail then WhatsApp\"\n" +
                "• Run agents: \"Run Morning\"", false)

        requestPermissions()
        refreshAgentList()
        updateStatus()
    }

    override fun onResume() { super.onResume(); updateStatus() }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    private fun setupEverything() {
        if (!JarvisAccessibilityService.isRunning()) {
            AlertDialog.Builder(this)
                .setTitle("Step 1: Enable Phone Control")
                .setMessage("Turn ON JARVIS in Accessibility settings so I can control your phone.")
                .setPositiveButton("Open") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .show()
            return
        }
        askIgnoreBattery()
        startWakeService()
        addMessage("All set, Boss! I'm now listening for \"Arise\" 24/7.", false)
        updateStatus()
    }

    private fun askIgnoreBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            } catch (_: Exception) {}
        }
    }

    private fun startWakeService() {
        val intent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun updateStatus() {
        val acc = JarvisAccessibilityService.isRunning()
        val wake = WakeWordService.isListening
        val awake = WakeWordService.isAwake
        statusText.text = buildString {
            append(if (acc) "● Phone control: ON" else "○ Phone control: OFF")
            append("\n")
            when {
                !wake -> append("○ Wake word: OFF")
                awake -> append("● AWAKE — listening for commands (say \"Goodbye\" to sleep)")
                else  -> append("● STANDBY — say \"Arise\" to wake me")
            }
        }
        statusText.setTextColor(if (acc && wake) 0xFF00FF99.toInt() else 0xFFFF8830.toInt())
    }

    private fun onSend() {
        val msg = inputField.text.toString().trim()
        if (msg.isEmpty()) return
        addMessage(msg, true)
        inputField.setText("")

        if (GEMINI_API_KEY == "PASTE_YOUR_GEMINI_API_KEY_HERE") {
            addMessage("⚠ Add your Gemini API key in the code first, Boss.", false)
            return
        }

        val lower = msg.lowercase()
        when {
            lower.contains("create") && lower.contains("agent") -> {
                addMessage("Building that agent…", false)
                AgentEngine.createAgentFromInstruction(this, msg) { _, result ->
                    runOnUiThread { addMessage(result, false); refreshAgentList() }
                }
            }
            lower.startsWith("run ") || lower.startsWith("start ") -> {
                val name = msg.substringAfter(" ").trim()
                AgentEngine.runAgent(this, name) { log ->
                    runOnUiThread { addMessage(log, false) }
                }
            }
            else -> {
                AgentEngine.handleVoiceCommand(this, msg)
                addMessage("On it, Boss.", false)
            }
        }
    }

    private fun refreshAgentList() {
        val agents = AgentEngine.loadAgents(this)
        if (agents.isEmpty()) {
            agentListText.text = "No agents yet. Create one by typing:\n\"Create an agent called X that...\""
        } else {
            agentListText.text = "YOUR AGENTS:\n" + agents.joinToString("\n") {
                "• ${it.name}  (say: \"Arise run ${it.trigger}\")  — ${it.steps.size} steps"
            }
        }
    }

    private fun addMessage(text: String, isUser: Boolean): View {
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(32, 24, 32, 24)
            setTextColor(if (isUser) 0xFFDFF0FF.toInt() else 0xFFCCE8F8.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 28f
                setColor(if (isUser) 0xFF0A4466.toInt() else 0xFF0B2035.toInt())
                setStroke(2, if (isUser) 0xFF00CFFF.toInt() else 0xFF0D3450.toInt())
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(24, 10, 24, 10) }
            gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
            addView(bubble)
        }
        chatLayout.addView(row)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        return row
    }
}
