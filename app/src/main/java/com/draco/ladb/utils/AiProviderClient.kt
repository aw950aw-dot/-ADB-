package com.draco.ladb.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.io.InputStream

object AiProviderClient {
    data class Answer(val text: String, val provider: String, val model: String, val usedFallback: Boolean)
    private data class Target(val provider: String, val model: String)
    private class ApiFailure(val code: Int, message: String) : Exception(message)

    fun generate(context: Context, prompt: String, maxTokens: Int = 500): Answer {
        val prefs = context.getSharedPreferences("ai_settings", 0)
        val primaryProvider = prefs.getString("primary_provider", "gemini") ?: "gemini"
        val primaryModel = prefs.getString("primary_model", prefs.getString("model", "gemini-3.8-flash"))
            .orEmpty().ifBlank { "gemini-3.8-flash" }
        val fallbackProvider = prefs.getString("fallback_provider", "gemini") ?: "gemini"
        val fallbackModel = prefs.getString("fallback_model", "gemini-3.7-flash").orEmpty()
        val extraModels = prefs.getString("extra_fallback_models", "").orEmpty()
            .split(",", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }

        val targets = mutableListOf(Target(primaryProvider, primaryModel))
        if (fallbackModel.isNotBlank()) targets.add(Target(fallbackProvider, fallbackModel))
        extraModels.forEach { targets.add(Target(fallbackProvider, it)) }

        var lastError: Exception? = null
        val attempted = mutableSetOf<String>()
        targets.distinctBy { it.provider + "|" + it.model }.forEachIndexed { index, target ->
            val id = target.provider + "|" + target.model
            if (!attempted.add(id)) return@forEachIndexed
            val key = ApiKeyVault.read(context, target.provider)
            if (key.isBlank()) {
                lastError = IllegalStateException("לא הוגדר מפתח API עבור " + providerLabel(target.provider))
                return@forEachIndexed
            }
            try {
                val text = call(context, target, key, prompt, maxTokens)
                return Answer(text, target.provider, target.model, index > 0)
            } catch (e: Exception) {
                lastError = e
                val canFallback = e !is ApiFailure || e.code == 408 || e.code == 409 ||
                    e.code == 425 || e.code == 429 || e.code == 500 ||
                    e.code == 502 || e.code == 503 || e.code == 504
                if (!canFallback) throw e
            }
        }
        throw lastError ?: IllegalStateException("לא הוגדרו מודלים זמינים")
    }

    private fun providerLabel(provider: String) = when (provider) {
        "gemini" -> "Gemini"
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        "custom" -> "הספק המותאם"
        else -> provider
    }

    private fun readLimited(stream: InputStream?, maxBytes: Int): String {
        if (stream == null) return ""
        val output = java.io.ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "תגובת ספק ה־AI גדולה מדי." }
                output.write(buffer, 0, count)
            }
        }
        return output.toString("UTF-8")
    }

    private fun call(context: Context, target: Target, key: String, prompt: String, maxTokens: Int): String {
        val prefs = context.getSharedPreferences("ai_settings", 0)
        val endpoint = when (target.provider) {
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta/models/" + target.model + ":generateContent"
            "openai" -> "https://api.openai.com/v1/chat/completions"
            "anthropic" -> "https://api.anthropic.com/v1/messages"
            "custom" -> prefs.getString("custom_endpoint", "https://api.openai.com/v1/chat/completions").orEmpty().trim()
            else -> throw IllegalArgumentException("ספק AI לא מוכר")
        }
        if (!endpoint.startsWith("https://")) throw IllegalArgumentException("כתובת API חייבת להתחיל ב־HTTPS")
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 45000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body = when (target.provider) {
            "gemini" -> {
                connection.setRequestProperty("x-goog-api-key", key)
                JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", maxTokens))
            }
            "anthropic" -> {
                connection.setRequestProperty("x-api-key", key)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
                JSONObject().put("model", target.model).put("max_tokens", maxTokens)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            }
            else -> {
                connection.setRequestProperty("Authorization", "Bearer " + key)
                JSONObject().put("model", target.model)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                    .put("max_completion_tokens", maxTokens)
            }
        }
        OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = readLimited(stream, 4 * 1024 * 1024)
        if (status !in 200..299) throw ApiFailure(status, "HTTP " + status + ": " + raw.take(1600))
        val json = JSONObject(raw)
        val answer = when (target.provider) {
            "gemini" -> json.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            "anthropic" -> {
                val blocks = json.getJSONArray("content")
                (0 until blocks.length()).mapNotNull { i ->
                    blocks.getJSONObject(i).takeIf { it.optString("type") == "text" }?.optString("text")
                }.joinToString("\n")
            }
            else -> json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
        return answer.trim().ifBlank {
            throw IllegalStateException("המודל החזיר תשובה ריקה")
        }
        } finally {
            connection.disconnect()
        }
    }

    fun listModels(context: Context, provider: String, key: String): List<Pair<String, String>> {
        val endpoint = when (provider) {
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100"
            "openai" -> "https://api.openai.com/v1/models"
            "anthropic" -> "https://api.anthropic.com/v1/models?limit=100"
            "custom" -> return emptyList()
            else -> return emptyList()
        }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        when (provider) {
            "gemini" -> connection.setRequestProperty("x-goog-api-key", key)
            "anthropic" -> {
                connection.setRequestProperty("x-api-key", key)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
            }
            else -> connection.setRequestProperty("Authorization", "Bearer " + key)
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = readLimited(stream, 2 * 1024 * 1024)
        if (status !in 200..299) throw ApiFailure(status, "HTTP " + status + ": " + raw.take(1000))
        val json = JSONObject(raw)
        val data = json.optJSONArray(if (provider == "gemini") "models" else "data") ?: JSONArray()
        val result = mutableListOf<Pair<String, String>>()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val id = if (provider == "gemini") item.optString("name").removePrefix("models/") else item.optString("id")
            if (id.isBlank()) continue
            if (provider == "gemini") {
                val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                val supportsText = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (!supportsText || !id.startsWith("gemini-")) continue
            }
            val label = item.optString("displayName").ifBlank { item.optString("display_name").ifBlank { id } }
            result.add(Pair(id, label))
        }
        return result.distinctBy { it.first }.sortedBy { it.second }
        } finally {
            connection.disconnect()
        }
    }
}