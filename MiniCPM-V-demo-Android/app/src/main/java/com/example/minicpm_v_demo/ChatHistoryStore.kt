package com.example.minicpm_v_demo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ChatHistoryStore {
    data class Summary(
        val id: String,
        val title: String,
        val preview: String,
        val updatedAt: Long,
        val messageCount: Int
    )

    private const val PREFS_NAME = "chat_history"
    private const val KEY_CONVERSATIONS = "conversations"
    private const val MAX_HISTORY = 30
    private const val DEFAULT_TITLE = "\u65B0\u7684\u732B\u5A18\u5BF9\u8BDD"

    fun saveConversation(
        context: Context,
        conversationId: String?,
        messages: List<ChatMessage>
    ): String? {
        val savedMessages = messages.mapNotNull { message ->
            when (message) {
                is ChatMessage.UserMessage ->
                    message.text.takeIf { it.isNotBlank() }?.let { "user" to it }
                is ChatMessage.AiMessage ->
                    message.text.takeIf { it.isNotBlank() && !message.isGenerating }?.let { "ai" to it }
                is ChatMessage.WelcomeCard -> null
            }
        }
        if (savedMessages.isEmpty()) return conversationId

        val id = conversationId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val existing = find(context, id)
        val generatedTitle = savedMessages.firstOrNull { it.first == "user" }?.second
            ?.replace('\n', ' ')
            ?.take(28)
            ?: DEFAULT_TITLE
        val title = existing?.optString("title")?.takeIf { it.isNotBlank() } ?: generatedTitle

        val messageArray = JSONArray()
        for ((role, text) in savedMessages) {
            messageArray.put(JSONObject().put("role", role).put("text", text))
        }

        val item = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("updatedAt", now)
            .put("messages", messageArray)

        val all = readAll(context)
        val filtered = mutableListOf<JSONObject>()
        filtered.add(item)
        for (i in 0 until all.length()) {
            val old = all.getJSONObject(i)
            if (old.optString("id") != id) filtered.add(old)
        }

        val trimmed = JSONArray()
        filtered.take(MAX_HISTORY).forEach { trimmed.put(it) }
        prefs(context).edit().putString(KEY_CONVERSATIONS, trimmed.toString()).apply()
        return id
    }

    fun list(context: Context): List<Summary> {
        val all = readAll(context)
        return buildList {
            for (i in 0 until all.length()) {
                val item = all.getJSONObject(i)
                add(
                    Summary(
                        id = item.optString("id"),
                        title = item.optString("title", DEFAULT_TITLE),
                        preview = previewOf(item),
                        updatedAt = item.optLong("updatedAt", 0L),
                        messageCount = item.optJSONArray("messages")?.length() ?: 0
                    )
                )
            }
        }
    }

    private fun previewOf(item: JSONObject): String {
        val messages = item.optJSONArray("messages") ?: return ""
        for (i in 0 until messages.length()) {
            val text = messages.getJSONObject(i).optString("text")
                .replace('\n', ' ')
                .trim()
            if (text.isNotBlank()) return text.take(72)
        }
        return ""
    }

    fun legacyList(context: Context): List<Summary> {
        val all = readAll(context)
        return buildList {
            for (i in 0 until all.length()) {
                val item = all.getJSONObject(i)
                add(
                    Summary(
                        id = item.optString("id"),
                        title = item.optString("title", DEFAULT_TITLE),
                        preview = previewOf(item),
                        updatedAt = item.optLong("updatedAt", 0L),
                        messageCount = item.optJSONArray("messages")?.length() ?: 0
                    )
                )
            }
        }
    }

    fun loadMessages(context: Context, conversationId: String): List<ChatMessage> {
        val item = find(context, conversationId) ?: return emptyList()
        val array = item.optJSONArray("messages") ?: return emptyList()
        var nextId = 1L
        return buildList {
            for (j in 0 until array.length()) {
                val msg = array.getJSONObject(j)
                val text = msg.optString("text")
                when (msg.optString("role")) {
                    "user" -> add(ChatMessage.UserMessage(id = nextId++, text = text))
                    "ai" -> add(ChatMessage.AiMessage(id = nextId++, text = text))
                }
            }
        }
    }

    fun rename(context: Context, conversationId: String, newTitle: String): Boolean {
        val trimmedTitle = newTitle.trim().replace('\n', ' ').take(40)
        if (trimmedTitle.isBlank()) return false

        val all = readAll(context)
        var changed = false
        for (i in 0 until all.length()) {
            val item = all.getJSONObject(i)
            if (item.optString("id") == conversationId) {
                item.put("title", trimmedTitle)
                changed = true
                break
            }
        }
        if (changed) {
            prefs(context).edit().putString(KEY_CONVERSATIONS, all.toString()).apply()
        }
        return changed
    }

    fun delete(context: Context, conversationId: String): Boolean {
        val all = readAll(context)
        val kept = JSONArray()
        var changed = false
        for (i in 0 until all.length()) {
            val item = all.getJSONObject(i)
            if (item.optString("id") == conversationId) {
                changed = true
            } else {
                kept.put(item)
            }
        }
        if (changed) {
            prefs(context).edit().putString(KEY_CONVERSATIONS, kept.toString()).apply()
        }
        return changed
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CONVERSATIONS).apply()
    }

    private fun find(context: Context, conversationId: String): JSONObject? {
        val all = readAll(context)
        for (i in 0 until all.length()) {
            val item = all.getJSONObject(i)
            if (item.optString("id") == conversationId) return item
        }
        return null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readAll(context: Context): JSONArray {
        val raw = prefs(context).getString(KEY_CONVERSATIONS, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }
}
