package com.jarvis.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object AgentEngine {

    private const val PREFS = "jarvis_agents"
    private const val KEY_AGENTS = "agents_json"

    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var geminiApiKey: String = ""

    fun initTts(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
            }
        }
    }

    fun speak(context: Context, text: String) {
        initTts(context)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    fun loadAgents(context: Context): MutableList<Agent> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_AGENTS, "[]") ?: "[]"
        val list = mutableListOf<Agent>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                list.add(Agent.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveAgents(context: Context, agents: List<Agent>) {
        val arr = JSONArray()
        agents.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_AGENTS, arr.toString()).apply()
    }

    fun addAgent(context: Context, agent: Agent) {
        val agents = loadAgents(context)
        agents.removeAll { it.name.equals(agent.name, ignoreCase = true) }
        agents.add(agent)
        saveAgents(context, agents)
    }

    fun deleteAgent(context: Context, name: String) {
        val agents = loadAgents(context)
        agents.removeAll { it.name.equals(name, ignoreCase = true) }
        saveAgents(context, agents)
    }

    fun createAgentFromInstruction(
        context: Context,
        instruction: String,
        onResult: (Agent?, String) -> Unit
    ) {
        scope.launch {
            val gemini = GeminiClient(geminiApiKey)
            val agent = withContext(Dispatchers.IO) {
                gemini.buildAgent(instruction)
            }
            if (agent != null) {
                addAgent(context, agent)
                onResult(agent, "Created agent '${agent.name}' with ${agent.steps.size} steps, Boss.")
                speak(context, "Agent ${agent.name} created, Boss.")
            } else {
                onResult(null, "I couldn't build that agent. Try describing the steps more clearly, Boss.")
            }
        }
    }

    fun runAgent(context: Context, name: String, onLog: ((String) -> Unit)? = null) {
        val agents = loadAgents(context)
        val agent = agents.firstOrNull {
            it.name.equals(name, ignoreCase = true) ||
            it.trigger.equals(name, ignoreCase = true) ||
            name.contains(it.name, ignoreCase = true)
        }

        if (agent == null) {
            speak(context, "I don't have an agent called $name, Boss.")
            onLog?.invoke("No agent named '$name'")
            return
        }

        speak(context, "Running ${agent.name}, Boss.")
        onLog?.invoke("▶ Running ${agent.name}…")

        scope.launch {
            for ((index, step) in agent.steps.withIndex()) {
                onLog?.invoke("  Step ${index + 1}: ${step.action} ${step.value}")
                executeStep(context, step)
                delay(1500)
            }
            onLog?.invoke("✅ ${agent.name} finished")
            speak(context, "${agent.name} complete, Boss.")
        }
    }

    fun handleVoiceCommand(context: Context, command: String) {
        val lower = command.lowercase()

        if (lower.startsWith("run ") || lower.startsWith("start ")) {
            val name = command.substringAfter(" ").trim()
            runAgent(context, name)
            return
        }

        if (lower.contains("create") && lower.contains("agent")) {
            createAgentFromInstruction(context, command) { _, msg ->
                speak(context, msg)
            }
            return
        }

        scope.launch {
            val gemini = GeminiClient(geminiApiKey)
            val screen = withContext(Dispatchers.IO) {
                JarvisAccessibilityService.instance?.readScreen() ?: ""
            }
            val response = withContext(Dispatchers.IO) {
                gemini.ask(command, screen)
            }
            speak(context, response.reply)
            executeStep(context, AgentStep(response.action, response.value))
        }
    }

    private fun executeStep(context: Context, step: AgentStep) {
        val service = JarvisAccessibilityService.instance ?: return
        when (step.action) {
            "open_app" -> {
                val pkg = service.findPackageByAppName(step.value)
                if (pkg != null) service.launchApp(pkg)
            }
            "tap_text"    -> service.tapByText(step.value)
            "type_text"   -> service.typeText(step.value)
            "home"        -> service.pressHome()
            "back"        -> service.pressBack()
            "recents"     -> service.pressRecents()
            "scroll_down" -> service.scrollDown()
            "scroll_up"   -> service.scrollUp()
            "wait"        -> {}
            "none"        -> {}
        }
    }
}

data class AgentStep(val action: String, val value: String) {
    fun toJson() = JSONObject().put("action", action).put("value", value)
    companion object {
        fun fromJson(o: JSONObject) = AgentStep(o.optString("action"), o.optString("value"))
    }
}

data class Agent(
    val name: String,
    val trigger: String,
    val personality: String,
    val steps: List<AgentStep>
) {
    fun toJson(): JSONObject {
        val stepsArr = JSONArray()
        steps.forEach { stepsArr.put(it.toJson()) }
        return JSONObject()
            .put("name", name)
            .put("trigger", trigger)
            .put("personality", personality)
            .put("steps", stepsArr)
    }
    companion object {
        fun fromJson(o: JSONObject): Agent {
            val steps = mutableListOf<AgentStep>()
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) steps.add(AgentStep.fromJson(arr.getJSONObject(i)))
            return Agent(
                o.optString("name"),
                o.optString("trigger"),
                o.optString("personality", "helpful"),
                steps
            )
        }
    }
}
