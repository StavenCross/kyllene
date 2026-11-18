package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The three action reactions submit a separate prompt; Got it only writes the reaction. */
enum class ReplyReaction(val emoji: String, private val instruction: String? = null) {
    Acknowledge("👍"),
    Continue("▶️", "Continue with the next step from this reply within the authority already granted. Ask if the next step is ambiguous."),
    Verify("🔍", "Verify the claims or results in this reply. Say what holds up and what is still uncertain."),
    Explain("❓", "Explain this reply in plain language.");

    fun prompt(rowId: Long, reply: String): String? = instruction?.let {
        val quote = reply.trim().takeLast(600)
        "$it\n\nAssistant message #$rowId (excerpt): ${JsonPrimitive(quote)}"
    }
}

internal fun userReactionEmoji(value: JsonElement?): String? =
    (value as? JsonArray).orEmpty().asSequence().filterIsInstance<JsonObject>()
        .firstOrNull { it.string("author") == "user" }?.string("emoji")
