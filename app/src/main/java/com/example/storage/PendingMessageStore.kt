package com.example.storage

import android.content.Context
import com.example.model.ChatMessage
import com.example.model.MessageDeliveryState
import com.example.model.MessageKind
import org.json.JSONObject
import java.io.File

/** Stores unsent messages per account. Each record is committed before a send starts. */
class PendingMessageStore(context: Context) {
    private val prefs = context.getSharedPreferences("pending_messages", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "pending_attachments").apply { mkdirs() }

    data class Item(val message: ChatMessage, val attachment: File?, val mime: String)

    fun save(user: String, message: ChatMessage, source: File?, mime: String): Item {
        val key = "$user:${message.clientMessageId}"
        val existing = prefs.getString(key, null)?.let { parse(it) }
        val attachment = existing?.attachment ?: source?.let {
            val target = File(directory, message.clientMessageId)
            it.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            target
        }
        val obj = JSONObject().apply {
            put("conversation", message.conversation)
            put("sender", user)
            put("text", message.text)
            put("clientId", message.clientMessageId)
            put("kind", message.kind.wireName)
            put("reply", message.replyTo)
            put("inCall", message.inCall)
            put("created", message.created)
            put("attachment", attachment?.absolutePath.orEmpty())
            put("mime", mime)
        }
        check(prefs.edit().putString(key, obj.toString()).commit())
        return Item(message, attachment, mime)
    }

    fun list(user: String): List<Item> = prefs.all
        .filterKeys { it.startsWith("$user:") }
        .values.mapNotNull { (it as? String)?.let(::parse) }

    fun remove(user: String, clientId: String) {
        val key = "$user:$clientId"
        val old = prefs.getString(key, null)?.let(::parse)
        if (prefs.edit().remove(key).commit()) old?.attachment?.delete()
    }

    private fun parse(raw: String): Item? = try {
        val o = JSONObject(raw)
        val path = o.optString("attachment")
        val file = path.takeIf { it.isNotBlank() }?.let(::File)
        val id = o.getString("clientId")
        Item(
            ChatMessage(
                id = "local_$id",
                conversation = o.getString("conversation"),
                sender = o.getString("sender"),
                text = o.optString("text"),
                clientMessageId = id,
                kind = MessageKind.fromWire(o.optString("kind")),
                attachment = file?.name.orEmpty(),
                attachmentBytes = file?.length() ?: 0,
                replyTo = o.optString("reply"),
                inCall = o.optBoolean("inCall"),
                created = o.getString("created"),
                deliveryState = MessageDeliveryState.FAILED,
                localAttachmentUri = file?.absolutePath.orEmpty()
            ),
            file,
            o.optString("mime", "application/octet-stream")
        )
    } catch (_: Exception) { null }
}
