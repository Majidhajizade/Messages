package com.majidhajizade.messages

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StoredMeshMessage(
    val body: String,
    val date: Long,
    val incoming: Boolean
)

object MeshMessageStore {

    private const val PREFS = "mesh_message_store"

    fun saveConversation(
        context: Context,
        meshId: String
    ) {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val conversations = prefs
            .getStringSet("conversations", emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()

        conversations.add(
            meshId.trim().uppercase()
        )

        prefs.edit()
            .putStringSet("conversations", conversations)
            .apply()
    }

    fun loadConversations(
        context: Context
    ): Set<String> {
        return context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .getStringSet("conversations", emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun saveMessage(
        context: Context,
        meshId: String,
        message: StoredMeshMessage
    ) {
        saveConversation(context, meshId)

        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val key = meshId.trim().uppercase()

        val messages = JSONArray(
            prefs.getString(key, "[]") ?: "[]"
        )

        messages.put(
            JSONObject().apply {
                put("body", message.body)
                put("date", message.date)
                put("incoming", message.incoming)
            }
        )

        prefs.edit()
            .putString(key, messages.toString())
            .apply()
    }

    fun loadLatestMessage(
        context: Context,
        meshId: String
    ): StoredMeshMessage? {
        return loadMessages(context, meshId)
            .maxByOrNull { it.date }
    }

    fun loadMessages(
        context: Context,
        meshId: String
    ): List<StoredMeshMessage> {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val key = meshId.trim().uppercase()

        val messages = JSONArray(
            prefs.getString(key, "[]") ?: "[]"
        )

        return buildList {
            for (index in 0 until messages.length()) {
                val item = messages.getJSONObject(index)

                add(
                    StoredMeshMessage(
                        body = item.optString("body"),
                        date = item.optLong("date"),
                        incoming = item.optBoolean("incoming")
                    )
                )
            }
        }
    }
}
