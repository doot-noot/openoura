package dev.openoura.droid

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One chat turn. role is "user" or "assistant". */
data class Turn(val role: String, val text: String)

/** A chat backend. Claude now; a local OpenAI-compatible server comes next. */
interface AiProvider {
    /** Blocking — call from Dispatchers.IO. */
    fun chat(system: String, turns: List<Turn>): String
}

/** Anthropic Messages API. Your key, your account; data goes to Anthropic. */
class ClaudeProvider(private val apiKey: String, private val model: String) : AiProvider {
    override fun chat(system: String, turns: List<Turn>): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 2048)
            .put("system", system)
            .put("messages", JSONArray().apply {
                turns.forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
            })
        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
            error("Claude API $code: ${msg ?: text.take(300)}")
        }
        val content = JSONObject(text).getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") append(block.getString("text"))
            }
        }
    }
}

object HealthPrompt {
    /** Cap on summary JSON sent per conversation, to bound cost/context. */
    const val MAX_SUMMARY_CHARS = 120_000

    fun system(summaryJson: String): String = """
You are the analysis assistant inside OpenRing, a private, cloud-free app for an Oura ring.
Below is the user's data summary, computed on their phone from raw ring events by the
open_oura / open_health algorithms (JSON). Fields may be null where a model is not run.

Rules:
- Ground every claim in the data below; cite the dates/values you used.
- If the data doesn't support an answer, say what's missing instead of guessing.
- Be direct and technical; the user is comfortable with physiology terminology.
- Don't invent activity labels (e.g. "golf") — describe movement, HR and timing as measured.
- You're not diagnosing; flag anything that genuinely warrants a clinician, once, plainly.

<ring_summary>
${summaryJson.take(MAX_SUMMARY_CHARS)}
</ring_summary>
""".trimIndent()
}
