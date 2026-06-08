package com.jarvis.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val model = "gemini-1.5-flash"

    private val chatPrompt = """
You are JARVIS, an AI assistant inside an Android phone. You chat AND control the phone.
Respond ONLY in this JSON (no markdown):
{"reply":"short friendly reply, call them Boss","action":"ACTION","value":"value"}

Actions: none, open_app, tap_text, type_text, home, back, recents, scroll_down, scroll_up
Example: {"reply":"Opening WhatsApp, Boss.","action":"open_app","value":"WhatsApp"}
""".trimIndent()

    fun ask(userMessage: String, screenContext: String = ""): JarvisResponse {
        try {
            val prompt = "$chatPrompt\n\nScreen: ${screenContext.take(400)}\n\nUser: $userMessage"
            val text = callGemini(prompt) ?: return JarvisResponse("AI unavailable, Boss.", "none", "")
            val clean = text.replace("```json", "").replace("```", "").trim()
            val o = JSONObject(clean)
            return JarvisResponse(
                o.optString("reply", "Done, Boss."),
                o.optString("action", "none"),
                o.optString("value", "")
            )
        } catch (e: Exception) {
            return JarvisResponse("I had trouble with that, Boss.", "none", "")
        }
    }

    private val agentPrompt = """
You are an agent builder for JARVIS on Android. The user describes what an agent
should do. Convert it into a structured agent in this EXACT JSON (no markdown):

{
  "name": "short name",
  "trigger": "word to activate it",
  "personality": "one word style",
  "steps": [
    {"action":"ACTION","value":"value"}
  ]
}

Available step actions:
- open_app, tap_text, type_text, home, back, recents, scroll_down, scroll_up, wait

Example instruction: "Make an agent called Morning that opens Gmail then opens WhatsApp"
Output:
{"name":"Morning","trigger":"morning","personality":"energetic","steps":[{"action":"open_app","value":"Gmail"},{"action":"wait","value":""},{"action":"open_app","value":"WhatsApp"}]}

Now build an agent for this instruction:
""".trimIndent()

    fun buildAgent(instruction: String): Agent? {
        try {
            val prompt = "$agentPrompt\n$instruction"
            val text = callGemini(prompt) ?: return null
            val clean = text.replace("```json", "").replace("```", "").trim()
            val o = JSONObject(clean)

            val steps = mutableListOf<AgentStep>()
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                steps.add(AgentStep(s.optString("action"), s.optString("value")))
            }
            if (steps.isEmpty()) return null

            return Agent(
                name = o.optString("name", "Agent"),
                trigger = o.optString("trigger", o.optString("name", "agent")).lowercase(),
                personality = o.optString("personality", "helpful"),
                steps = steps
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun callGemini(prompt: String): String? {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val raw = resp.body?.string() ?: return null
            return try {
                JSONObject(raw)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0)
                    .getString("text")
            } catch (e: Exception) { null }
        }
    }
}

data class JarvisResponse(val reply: String, val action: String, val value: String)
