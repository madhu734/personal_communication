package com.example.model

import java.time.Instant

data class UserRecord(
    val id: String,
    val name: String,
    val email: String,
    val avatar: String = "",
    val verified: Boolean = true
)

data class PeerSummary(
    val id: String,
    val name: String,
    val avatar: String = ""
)

enum class MessageKind(val wireName: String) {
    TEXT("text"),
    IMAGE("image"),
    VOICE("voice"),
    VIDEO("video");

    companion object {
        fun fromWire(value: String?): MessageKind =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: TEXT
    }
}

/**
 * Unified message delivery & read tick states across Web and Android:
 * - SENDING: Queued locally in retry buffer
 * - FAILED: Network or upload error; retains client_message_id for idempotent retry
 * - SENT: Saved on server (`✓` gray)
 * - DELIVERED: Recipient's app synced the message and acknowledged via delivered watermark (`✓✓` gray)
 * - SEEN: Recipient opened the chat and advanced read_through/read_id (`✓✓` colored)
 */
enum class MessageDeliveryState {
    SENDING,
    FAILED,
    SENT,
    DELIVERED,
    SEEN
}

data class ConversationMemberRecord(
    val id: String,
    val conversation: String,
    val user: String,
    val readThrough: String = "",
    val readId: String = "",
    val deliveredThrough: String = "",
    val deliveredId: String = "",
    val presenceUntil: String = "",
    val typingUntil: String = ""
)

data class ChatMessage(
    val id: String,
    val conversation: String,
    val sender: String,
    val text: String,
    val clientMessageId: String,
    val kind: MessageKind = MessageKind.TEXT,
    val attachment: String = "",
    val attachmentBytes: Long = 0L,
    val replyTo: String = "",
    val inCall: Boolean = false,
    val created: String,
    val updated: String = created,
    val deliveryState: MessageDeliveryState = MessageDeliveryState.SENT,
    val localAttachmentUri: String = ""
) {
    val isEdited: Boolean
        get() = updated.isNotBlank() && created.isNotBlank() && updated != created &&
            parsePocketBaseInstantMillis(updated) - parsePocketBaseInstantMillis(created) > 1500L
}

data class MessageReaction(
    val id: String,
    val conversation: String,
    val message: String,
    val user: String,
    val emoji: String
)

data class ChatSummary(
    val id: String,
    val peer: PeerSummary,
    val lastMessage: ChatMessage? = null,
    val unreadCount: Int = 0,
    val isMuted: Boolean = false,
    val muteUntilEpochMs: Long? = null,
    val peerOnline: Boolean = false,
    val peerTyping: Boolean = false
)

data class MessageRequestItem(
    val id: String,
    val peer: PeerSummary,
    val incoming: Boolean,
    val note: String = "",
    val created: String = ""
)

/**
 * Timed conversation mute options in English shared by the in-app Chat Menu and Notification Mute Picker:
 * 1 hour, 2 hours, 8 hours, 1 day, Always.
 */
enum class MuteDurationOption(
    val key: String,
    val label: String,
    val durationMs: Long?
) {
    ONE_HOUR("1h", "1 hour", 3_600_000L),
    TWO_HOURS("2h", "2 hours", 7_200_000L),
    EIGHT_HOURS("8h", "8 hours", 28_800_000L),
    ONE_DAY("1d", "1 day", 86_400_000L),
    ALWAYS("always", "Always", null);

    val displayLabel: String
        get() = label

    fun computeMuteUntilEpochMs(nowEpochMs: Long = System.currentTimeMillis()): Long =
        durationMs?.let { nowEpochMs + it } ?: Long.MAX_VALUE

    companion object {
        fun fromKey(key: String?): MuteDurationOption =
            entries.firstOrNull { it.key == key } ?: ONE_HOUR
    }
}

enum class CallKind(val wireName: String) {
    AUDIO("audio"),
    VIDEO("video");

    companion object {
        fun fromWire(value: String?): CallKind =
            if (value.equals("video", ignoreCase = true)) VIDEO else AUDIO
    }
}

enum class CallRecordStatus(val wireName: String) {
    RINGING("ringing"),
    ACCEPTED("accepted"),
    CONNECTED("connected"),
    ENDED("ended"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
    MISSED("missed"),
    FAILED("failed");

    val isTerminal: Boolean
        get() = this in setOf(ENDED, REJECTED, CANCELLED, MISSED, FAILED)

    companion object {
        fun fromWire(value: String?): CallRecordStatus =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: ENDED
    }
}

data class CallRecord(
    val id: String,
    val conversation: String,
    val caller: String,
    val receiver: String,
    val room: String,
    val kind: CallKind,
    val status: CallRecordStatus,
    val ringExpiresAt: String = "",
    val acceptedAt: String = "",
    val connectedAt: String = "",
    val endedAt: String = "",
    val created: String = ""
)

data class CallTokenGrant(
    val token: String,
    val wsUrl: String,
    val roomName: String,
    val expiresAt: String
)

enum class AudioEndpointType(val label: String) {
    EARPIECE("Phone Earpiece"),
    SPEAKER("Speaker"),
    WIRED_HEADSET("Wired / USB Headset"),
    BLUETOOTH("Bluetooth Audio")
}

data class AudioEndpointItem(
    val id: String,
    val type: AudioEndpointType,
    val name: String = type.label
)

enum class VideoQualityPreset(val label: String, val maxHeightPx: Int, val audioOnly: Boolean) {
    AUTO_720P("Auto HD (720p)", 720, false),
    SMOOTH_360P("Smooth (360p)", 360, false),
    AUDIO_ONLY("Audio-only", 0, true)
}

enum class CallPhase {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    ANSWERING,
    CONNECTING,
    ACTIVE,
    RECONNECTING,
    ENDED
}

data class ActiveCallUiState(
    val callId: String = "",
    val conversationId: String = "",
    val peer: PeerSummary = PeerSummary("", ""),
    val kind: CallKind = CallKind.AUDIO,
    val isOutgoing: Boolean = true,
    val phase: CallPhase = CallPhase.IDLE,
    val isMicEnabled: Boolean = true,
    val isCameraEnabled: Boolean = false,
    val isFrontCamera: Boolean = true,
    val isScreenSharing: Boolean = false,
    val videoQuality: VideoQualityPreset = VideoQualityPreset.AUTO_720P,
    val isFocusMode: Boolean = false,
    val isMinimizedInApp: Boolean = false,
    val isInPipMode: Boolean = false,
    val showInCallChatSheet: Boolean = false,
    val inCallChatDraft: String = "",
    val currentEndpoint: AudioEndpointItem = AudioEndpointItem("earpiece", AudioEndpointType.EARPIECE),
    val availableEndpoints: List<AudioEndpointItem> = listOf(
        AudioEndpointItem("earpiece", AudioEndpointType.EARPIECE),
        AudioEndpointItem("speaker", AudioEndpointType.SPEAKER)
    ),
    val connectedElapsedSeconds: Long = 0L,
    val statusBannerText: String = "",
    val roomName: String = "",
    val wsConnected: Boolean = false
)

object WatermarkCursorUtils {
    val COMMON_REACTIONS = listOf("👍", "👎", "❤️", "😂", "😮", "😢", "😡")

    /**
     * Normalizes PocketBase timestamps ("2026-11-15 10:20:30.123Z") and RFC3339 strings to epoch millis.
     */
    fun parseInstantMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val normalized = raw.trim().replace(' ', 'T')
        return try {
            Instant.parse(normalized).toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.parse(if (normalized.endsWith("Z")) normalized else "${normalized}Z").toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    /**
     * Compares `(msgCreated, msgId)` against `(watermarkTime, watermarkId)` using the backend's
     * monotonic tuple ordering `(created, id)`.
     */
    fun isMessageAtOrBeforeWatermark(
        msgCreated: String,
        msgId: String,
        watermarkTime: String,
        watermarkId: String
    ): Boolean {
        if (watermarkTime.isBlank() || msgCreated.isBlank()) return false
        val msgMillis = parseInstantMillis(msgCreated)
        val watermarkMillis = parseInstantMillis(watermarkTime)
        if (msgMillis <= 0L || watermarkMillis <= 0L) {
            val rawCmp = msgCreated.compareTo(watermarkTime)
            if (rawCmp < 0) return true
            if (rawCmp > 0) return false
            return if (watermarkId.isBlank()) true else msgId <= watermarkId
        }
        if (msgMillis < watermarkMillis) return true
        if (msgMillis > watermarkMillis) return false
        return if (watermarkId.isBlank()) true else msgId <= watermarkId
    }

    /**
     * Evaluates the outgoing message tick strictly from server save + peer synced watermarks.
     * Never infers DELIVERED from FCM push enqueue or online presence.
     */
    fun resolveOutgoingDeliveryState(
        message: ChatMessage,
        peerMember: ConversationMemberRecord?
    ): MessageDeliveryState {
        if (message.deliveryState == MessageDeliveryState.SENDING ||
            message.deliveryState == MessageDeliveryState.FAILED
        ) {
            return message.deliveryState
        }
        if (message.id.isBlank() || message.id.startsWith("local_")) {
            return MessageDeliveryState.SENDING
        }
        if (peerMember == null) {
            return MessageDeliveryState.SENT
        }
        if (isMessageAtOrBeforeWatermark(
                msgCreated = message.created,
                msgId = message.id,
                watermarkTime = peerMember.readThrough,
                watermarkId = peerMember.readId
            )
        ) {
            return MessageDeliveryState.SEEN
        }
        if (isMessageAtOrBeforeWatermark(
                msgCreated = message.created,
                msgId = message.id,
                watermarkTime = peerMember.deliveredThrough,
                watermarkId = peerMember.deliveredId
            )
        ) {
            return MessageDeliveryState.DELIVERED
        }
        return MessageDeliveryState.SENT
    }

    fun isPresenceActive(untilTimestamp: String?, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val untilMs = parseInstantMillis(untilTimestamp)
        return untilMs > nowEpochMs
    }
}

fun parsePocketBaseInstantMillis(raw: String?): Long = WatermarkCursorUtils.parseInstantMillis(raw)
