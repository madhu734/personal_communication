package com.example.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.ActiveCallUiState
import com.example.model.CallKind
import com.example.model.MessageKind
import com.example.model.MuteDurationOption
import com.example.model.WatermarkCursorUtils
import com.example.network.PocketBaseApiClient
import com.example.storage.SessionAndMuteStore
import com.example.ui.theme.ApunKaTheme
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates:
 * 1. FCM `incoming_call` and `new_message` push payloads (`ApunKaMessagingService`).
 * 2. High-importance `NotificationCompat.CallStyle` notifications with Answer/Decline/Hang-up.
 * 3. Per-conversation grouped `MessagingStyle` notifications with inline `RemoteInput` **Reply**
 *    (retaining `client_message_id` on retry) and **Mute** duration picker (`1h, 2h, 8h, 1d, Always`).
 * 4. Ongoing Call Foreground Service (`CallForegroundService`).
 */
object SystemNotificationAndFcmManager {
    const val CHANNEL_CALLS = "apun_ka_calls_high_v1"
    const val CHANNEL_MESSAGES = "apun_ka_messages_v1"
    const val CALL_NOTIFICATION_ID = 9001

    const val KEY_TEXT_REPLY = "key_direct_reply_text"
    const val ACTION_DIRECT_REPLY = "me.tmadhu.mandychat.ACTION_DIRECT_REPLY"
    const val ACTION_RETRY_REPLY = "me.tmadhu.mandychat.ACTION_RETRY_REPLY"
    const val ACTION_CALL_ANSWER = "me.tmadhu.mandychat.ACTION_CALL_ANSWER"
    const val ACTION_CALL_DECLINE = "me.tmadhu.mandychat.ACTION_CALL_DECLINE"
    const val ACTION_CALL_END = "me.tmadhu.mandychat.ACTION_CALL_END"

    const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    const val EXTRA_PEER_NAME = "extra_peer_name"
    const val EXTRA_CLIENT_MESSAGE_ID = "extra_client_message_id"
    const val EXTRA_RETRY_TEXT = "extra_retry_text"
    const val EXTRA_CALL_ID = "extra_call_id"

    private val conversationHistoryMap = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()
    private val deduplicatedMessageEvents = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var activeCallCoordinator: CallCoordinator? = null

    fun getOrCreateCallCoordinator(
        context: Context,
        sessionStore: SessionAndMuteStore = SessionAndMuteStore(context.applicationContext),
        apiClient: PocketBaseApiClient = PocketBaseApiClient(sessionStore)
    ): CallCoordinator {
        val existing = activeCallCoordinator
        if (existing != null) return existing
        return synchronized(this) {
            activeCallCoordinator ?: CallCoordinator(
                appContext = context.applicationContext,
                sessionStore = sessionStore,
                apiClient = apiClient
            ).also { activeCallCoordinator = it }
        }
    }

    @Volatile
    var onDirectReplySentCallback: ((String) -> Unit)? = null

    fun ensureChannelsCreated(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val callChannel = NotificationChannel(
            CHANNEL_CALLS,
            context.getString(R.string.notif_channel_calls_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_calls_desc)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setBypassDnd(true)
        }
        val msgChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.notif_channel_messages_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_messages_desc)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(callChannel)
        nm.createNotificationChannel(msgChannel)
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nm.canUseFullScreenIntent()
        } else {
            true
        }
    }

    fun buildCallNotification(
        context: Context,
        state: ActiveCallUiState,
        isIncoming: Boolean
    ): Notification {
        ensureChannelsCreated(context)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_ID, state.callId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_CALL_ANSWER
            putExtra(EXTRA_CALL_ID, state.callId)
        }
        val answerPending = PendingIntent.getBroadcast(
            context,
            101,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = if (isIncoming) ACTION_CALL_DECLINE else ACTION_CALL_END
            putExtra(EXTRA_CALL_ID, state.callId)
        }
        val declinePending = PendingIntent.getBroadcast(
            context,
            102,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val publicFallback = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Apun Ka Communication")
            .setContentText(if (isIncoming) "Incoming call" else "Ongoing call")
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(state.peer.name.ifBlank { "Call" })
            .setContentText(
                if (isIncoming) "Incoming ${state.kind.wireName} call"
                else "Ongoing ${state.kind.wireName} call"
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicFallback)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPendingIntent)

        if (isIncoming && canUseFullScreenIntent(context)) {
            builder.setFullScreenIntent(contentPendingIntent, true)
        }

        if (isIncoming) {
            builder.addAction(
                R.drawable.ic_launcher_foreground,
                context.getString(R.string.action_answer),
                answerPending
            )
            builder.addAction(
                R.drawable.ic_launcher_foreground,
                context.getString(R.string.action_decline),
                declinePending
            )
        } else {
            builder.addAction(
                R.drawable.ic_launcher_foreground,
                context.getString(R.string.action_hang_up),
                declinePending
            )
        }

        return builder.build()
    }

    fun showOngoingOrIncomingCallNotification(
        context: Context,
        state: ActiveCallUiState,
        isIncoming: Boolean
    ): Notification {
        val notification = buildCallNotification(
            context = context,
            state = state,
            isIncoming = isIncoming
        )
        val hasPostNotifPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (hasPostNotifPerm) {
            try {
                NotificationManagerCompat.from(context).notify(CALL_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
            } catch (_: Throwable) {
            }
        }
        return notification
    }

    fun cancelCallNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    /**
     * Posts or updates a grouped conversation message notification with:
     * 1. Inline Direct Reply (`RemoteInput`) wired to `POST /api/app/messages`
     * 2. Mute button launching `MutePickerDialogActivity` (`১ ঘণ্টা, ২ ঘণ্টা, ৮ ঘণ্টা, ১ দিন, সবসময়`)
     * 3. Optional Retry button if a previous inline reply failed over the network (preserving `client_message_id`).
     */
    fun showConversationMessageNotification(
        context: Context,
        conversationId: String,
        peerName: String,
        senderName: String,
        messageText: String,
        messageId: String = "",
        failedReplyText: String? = null,
        failedClientMessageId: String? = null
    ) {
        if (conversationId.isBlank()) return
        val dedupKey = "$conversationId:$messageId:$messageText:${failedClientMessageId.orEmpty()}"
        if (messageId.isNotBlank() && failedClientMessageId == null) {
            if (!deduplicatedMessageEvents.add(dedupKey)) return
        }

        val sessionStore = SessionAndMuteStore(context)
        val userId = sessionStore.getUserRecord()?.id.orEmpty()
        if (userId.isNotBlank() && sessionStore.isConversationMuted(userId, conversationId)) {
            // Muted conversation: keep silent!
            return
        }

        ensureChannelsCreated(context)
        val notifId = conversationNotificationId(conversationId)

        val history = conversationHistoryMap.getOrPut(conversationId) { mutableListOf() }
        synchronized(history) {
            if (messageText.isNotBlank()) {
                history.add(senderName to messageText)
                while (history.size > 5) {
                    history.removeAt(0)
                }
            }
        }

        val selfPerson = Person.Builder().setName("You").build()
        val messagingStyle = NotificationCompat.MessagingStyle(selfPerson)
            .setConversationTitle(peerName)

        synchronized(history) {
            history.forEachIndexed { idx, (author, body) ->
                val p = Person.Builder().setName(author).build()
                messagingStyle.addMessage(body, System.currentTimeMillis() + idx, p)
            }
        }

        // 1. Direct Reply Action with RemoteInput
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply to $peerName...")
            .build()

        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DIRECT_REPLY
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_PEER_NAME, peerName)
            // Generate a fresh client_message_id or reuse existing failed one
            putExtra(
                EXTRA_CLIENT_MESSAGE_ID,
                failedClientMessageId ?: UUID.randomUUID().toString()
            )
        }
        val replyPendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            replyIntent,
            replyPendingFlags
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_launcher_foreground,
            context.getString(R.string.action_reply),
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // 2. Mute Action opening MutePickerDialogActivity
        val muteIntent = Intent(context, MutePickerDialogActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_PEER_NAME, peerName)
        }
        val mutePendingIntent = PendingIntent.getActivity(
            context,
            notifId + 2,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteAction = NotificationCompat.Action.Builder(
            R.drawable.ic_launcher_foreground,
            context.getString(R.string.action_mute),
            mutePendingIntent
        ).build()

        val openChatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val openChatPending = PendingIntent.getActivity(
            context,
            notifId,
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val publicFallback = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Apun Ka Communication")
            .setContentText("New private message")
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(messagingStyle)
            .setGroup("chat_group_$conversationId")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicFallback)
            .setAutoCancel(true)
            .setContentIntent(openChatPending)
            .addAction(replyAction)
            .addAction(muteAction)

        // 3. If a previous reply failed, expose an explicit Retry Reply button keeping the exact same client_message_id
        if (!failedReplyText.isNullOrBlank() && !failedClientMessageId.isNullOrBlank()) {
            builder.setSubText("Reply failed to send — tap Retry Reply")
            val retryIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_RETRY_REPLY
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_RETRY_TEXT, failedReplyText)
                putExtra(EXTRA_CLIENT_MESSAGE_ID, failedClientMessageId)
            }
            val retryPending = PendingIntent.getBroadcast(
                context,
                notifId + 3,
                retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_foreground,
                    context.getString(R.string.action_retry_reply),
                    retryPending
                ).build()
            )
        }

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun cancelConversationNotification(context: Context, conversationId: String) {
        conversationHistoryMap.remove(conversationId)
        try {
            NotificationManagerCompat.from(context)
                .cancel(conversationNotificationId(conversationId))
        } catch (_: Exception) {
        }
    }

    fun cancelAllNotifications(context: Context) {
        conversationHistoryMap.clear()
        deduplicatedMessageEvents.clear()
        try {
            NotificationManagerCompat.from(context).cancelAll()
        } catch (_: Exception) {
        }
    }

    fun conversationNotificationId(conversationId: String): Int =
        2000 + (conversationId.hashCode() and 0x7FFF)
}

/**
 * Handles FCM data messages (`incoming_call` and `new_message`) and token registration updates.
 */
class ApunKaMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val store = SessionAndMuteStore(applicationContext)
        store.pendingFcmToken = token
        if (store.getAuthToken().isNotBlank()) {
            serviceScope.launch {
                try {
                    val api = PocketBaseApiClient(store)
                    val oldId = store.registeredPushDeviceId
                    val newId = api.registerAndroidPushDevice(token)
                    if (oldId.isNotBlank() && oldId != newId) {
                        api.deletePushDevice(oldId)
                    }
                    store.pendingFcmToken = ""
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val event = data["event"].orEmpty()
        val id = data["id"].orEmpty()
        val expiresAt = data["expiresAt"].orEmpty()
        if (event.isBlank() || id.isBlank()) return

        val store = SessionAndMuteStore(applicationContext)
        val user = store.getUserRecord()
        if (store.getAuthToken().isBlank() || user == null) return

        val expiresMs = WatermarkCursorUtils.parseInstantMillis(expiresAt)
        if (expiresMs > 0L && expiresMs <= System.currentTimeMillis()) {
            // Discard expired push event
            return
        }

        when (event) {
            "incoming_call" -> {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val wakeLock = pm?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "me.tmadhu.mandychat:IncomingCallWake"
                    )
                    wakeLock?.acquire(10_000L)
                } catch (_: Exception) {
                }
                val coordinator = SystemNotificationAndFcmManager.getOrCreateCallCoordinator(
                    context = applicationContext,
                    sessionStore = store,
                    apiClient = PocketBaseApiClient(store)
                )
                coordinator.onIncomingCallSignal(callId = id, expiresAtRaw = expiresAt)
            }

            "new_message" -> {
                val conversationId = id
                if (store.isConversationMuted(user.id, conversationId)) {
                    // Conversation is muted; still sync & acknowledge delivery in background without ringing notification
                    serviceScope.launch {
                        syncAndAcknowledgePushMessages(store, conversationId, showNotification = false)
                    }
                } else {
                    serviceScope.launch {
                        syncAndAcknowledgePushMessages(store, conversationId, showNotification = true)
                    }
                }
            }
        }
    }

    private suspend fun syncAndAcknowledgePushMessages(
        store: SessionAndMuteStore,
        conversationId: String,
        showNotification: Boolean
    ) {
        val ownUser = store.getUserRecord() ?: return
        val api = PocketBaseApiClient(store)
        try {
            val messages = api.fetchMessages(conversationId = conversationId, perPage = 5)
            val latestPeerMsg = messages.lastOrNull { it.sender != ownUser.id }
            if (latestPeerMsg != null) {
                // Acknowledge that the message was genuinely synced to this device (`✓✓` gray Delivered)
                api.markConversationDelivered(conversationId, latestPeerMsg.id)
                if (showNotification) {
                    val inbox = try {
                        api.fetchInbox()
                    } catch (_: Exception) {
                        null
                    }
                    val peerName = inbox?.chats?.firstOrNull { it.id == conversationId }?.peer?.name
                        ?: "Contact"
                    val displayBody = when (latestPeerMsg.kind) {
                        MessageKind.TEXT -> latestPeerMsg.text
                        MessageKind.IMAGE -> "📷 Photo"
                        MessageKind.VOICE -> "🎤 Voice note"
                        MessageKind.VIDEO -> "🎬 Video"
                    }
                    SystemNotificationAndFcmManager.showConversationMessageNotification(
                        context = applicationContext,
                        conversationId = conversationId,
                        peerName = peerName,
                        senderName = peerName,
                        messageText = displayBody,
                        messageId = latestPeerMsg.id
                    )
                }
            } else if (showNotification) {
                SystemNotificationAndFcmManager.showConversationMessageNotification(
                    context = applicationContext,
                    conversationId = conversationId,
                    peerName = "New Message",
                    senderName = "Contact",
                    messageText = "Tap to view conversation"
                )
            }
        } catch (_: Exception) {
        }
    }
}

/**
 * Handles notification actions:
 * - Direct Reply (`RemoteInput`) and Retry Reply (retaining `client_message_id`)
 * - Incoming Call Answer / Decline / End
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val callIdFromIntent = intent.getStringExtra(SystemNotificationAndFcmManager.EXTRA_CALL_ID).orEmpty()

        when (action) {
            SystemNotificationAndFcmManager.ACTION_CALL_ANSWER -> {
                val store = SessionAndMuteStore(appContext)
                val api = PocketBaseApiClient(store)
                val coordinator = SystemNotificationAndFcmManager.getOrCreateCallCoordinator(appContext, store, api)
                if (coordinator.uiState.value.callId.isNotBlank()) {
                    coordinator.answerIncomingCall(withVideo = false)
                } else if (callIdFromIntent.isNotBlank()) {
                    coordinator.restoreAndAnswerCallFromNotification(callIdFromIntent)
                }
                // Bring MainActivity to foreground so the user sees the active call screen and mic permission dialog
                try {
                    val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                        if (callIdFromIntent.isNotBlank()) {
                            putExtra(SystemNotificationAndFcmManager.EXTRA_CALL_ID, callIdFromIntent)
                        }
                    }
                    appContext.startActivity(launchIntent)
                } catch (_: Exception) {
                }
            }

            SystemNotificationAndFcmManager.ACTION_CALL_DECLINE,
            SystemNotificationAndFcmManager.ACTION_CALL_END -> {
                val store = SessionAndMuteStore(appContext)
                val api = PocketBaseApiClient(store)
                val coordinator = SystemNotificationAndFcmManager.getOrCreateCallCoordinator(appContext, store, api)
                if (coordinator.uiState.value.callId.isNotBlank()) {
                    coordinator.declineOrHangUpCall()
                } else {
                    SystemNotificationAndFcmManager.cancelCallNotification(appContext)
                    CallForegroundService.stop(appContext)
                    if (callIdFromIntent.isNotBlank() && store.getAuthToken().isNotBlank()) {
                        val pendingResult = goAsync()
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            try {
                                if (action == SystemNotificationAndFcmManager.ACTION_CALL_DECLINE) {
                                    api.rejectCall(callIdFromIntent)
                                } else {
                                    api.endCall(callIdFromIntent)
                                }
                            } catch (_: Exception) {
                            } finally {
                                pendingResult.finish()
                            }
                        }
                    }
                }
            }

            SystemNotificationAndFcmManager.ACTION_DIRECT_REPLY -> {
                val remoteInputBundle: Bundle? = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInputBundle
                    ?.getCharSequence(SystemNotificationAndFcmManager.KEY_TEXT_REPLY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                val conversationId =
                    intent.getStringExtra(SystemNotificationAndFcmManager.EXTRA_CONVERSATION_ID).orEmpty()
                val peerName =
                    intent.getStringExtra(SystemNotificationAndFcmManager.EXTRA_PEER_NAME)
                        .orEmpty()
                        .ifBlank { "Contact" }
                val clientMessageId =
                    intent.getStringExtra(SystemNotificationAndFcmManager.EXTRA_CLIENT_MESSAGE_ID)
                        ?.takeIf { it.isNotBlank() }
                        ?: UUID.randomUUID().toString()

                if (replyText.isNotBlank() && conversationId.isNotBlank()) {
                    val pendingResult = goAsync()
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        try {
                            submitDirectReply(
                                context = context.applicationContext,
                                conversationId = conversationId,
                                peerName = peerName,
                                replyText = replyText,
                                clientMessageId = clientMessageId
                            )
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }

            SystemNotificationAndFcmManager.ACTION_RETRY_REPLY -> {
                val conversationId =
                    intent.getStringExtra(SystemNotificationAndFcmManager.EXTRA_CONVERSATION_ID).orEmpty()
                val peerName =
                    intent.getStringExtra(SystemNotificationAndFcmManager.EXTRA_PEER_NAME)
                        .orEmpty()
                        .ifBlank { "Contact" }
                val replyText =
                    intent.getStringExtra(SystemNotificationAndFcmManager.EXTRA_RETRY_TEXT).orEmpty()
                val clientMessageId =
                    intent.getStringExtra(SystemNotificationAndFcmManager.EXTRA_CLIENT_MESSAGE_ID).orEmpty()

                if (replyText.isNotBlank() && conversationId.isNotBlank() && clientMessageId.isNotBlank()) {
                    val pendingResult = goAsync()
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        try {
                            submitDirectReply(
                                context = context.applicationContext,
                                conversationId = conversationId,
                                peerName = peerName,
                                replyText = replyText,
                                clientMessageId = clientMessageId
                            )
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        suspend fun submitDirectReply(
            context: Context,
            conversationId: String,
            peerName: String,
            replyText: String,
            clientMessageId: String
        ): Boolean {
            val store = SessionAndMuteStore(context)
            if (store.getAuthToken().isBlank()) return false
            val api = PocketBaseApiClient(store)
            return try {
                api.sendMessage(
                    conversationId = conversationId,
                    text = replyText,
                    clientMessageId = clientMessageId,
                    kind = MessageKind.TEXT
                )
                SystemNotificationAndFcmManager.showConversationMessageNotification(
                    context = context,
                    conversationId = conversationId,
                    peerName = peerName,
                    senderName = "You",
                    messageText = replyText,
                    messageId = clientMessageId
                )
                SystemNotificationAndFcmManager.onDirectReplySentCallback?.invoke(conversationId)
                true
            } catch (_: Exception) {
                // Keep the exact same client_message_id and expose Retry Reply action on the notification
                SystemNotificationAndFcmManager.showConversationMessageNotification(
                    context = context,
                    conversationId = conversationId,
                    peerName = peerName,
                    senderName = "You (Not sent)",
                    messageText = replyText,
                    failedReplyText = replyText,
                    failedClientMessageId = clientMessageId
                )
                false
            }
        }
    }
}

/**
 * Compact dialog activity opened when tapping **Mute** on a message notification.
 * Offers the exact same 5 mute durations as the in-app chat menu:
 * ১ ঘণ্টা (1h), ২ ঘণ্টা (2h), ৮ ঘণ্টা (8h), ১ দিন (1d), সবসময় (Always).
 */
class MutePickerDialogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conversationId =
            intent.getStringExtra(SystemNotificationAndFcmManager.EXTRA_CONVERSATION_ID).orEmpty()
        val peerName =
            intent.getStringExtra(SystemNotificationAndFcmManager.EXTRA_PEER_NAME)
                .orEmpty()
                .ifBlank { "this chat" }
        if (conversationId.isBlank()) {
            finish()
            return
        }

        val store = SessionAndMuteStore(applicationContext)
        val userId = store.getUserRecord()?.id.orEmpty().ifBlank { "default_user" }

        setContent {
            ApunKaTheme {
                MuteDurationPickerCard(
                    peerName = peerName,
                    onSelectOption = { option ->
                        store.setConversationMute(userId, conversationId, option)
                        SystemNotificationAndFcmManager.cancelConversationNotification(
                            applicationContext,
                            conversationId
                        )
                        SystemNotificationAndFcmManager.onDirectReplySentCallback?.invoke(conversationId)
                        finish()
                    },
                    onCancel = { finish() }
                )
            }
        }
    }
}

@Composable
fun MuteDurationPickerCard(
    peerName: String,
    onSelectOption: (MuteDurationOption) -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Mute notifications for $peerName",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Messages will still sync in real time and incoming calls will still ring.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MuteDurationOption.entries.forEach { option ->
                    Button(
                        onClick = { onSelectOption(option) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(text = option.label, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancel")
            }
        }
    }
}

/**
 * Foreground service maintaining call lifecycle across backgrounding and OS Picture-in-Picture.
 */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coordinator = SystemNotificationAndFcmManager.activeCallCoordinator
        val state = coordinator?.uiState?.value ?: ActiveCallUiState(
            callId = "ongoing",
            kind = CallKind.AUDIO
        )
        val notification = SystemNotificationAndFcmManager.buildCallNotification(
            context = applicationContext,
            state = state,
            isIncoming = false
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                if ( androidx.core.content.ContextCompat.checkSelfPermission(
                        applicationContext,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        applicationContext,
                        android.Manifest.permission.CAMERA
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                startForeground(
                    SystemNotificationAndFcmManager.CALL_NOTIFICATION_ID,
                    notification,
                    fgsType
                )
            } else {
                startForeground(
                    SystemNotificationAndFcmManager.CALL_NOTIFICATION_ID,
                    notification
                )
            }
        } catch (_: Throwable) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(
                        SystemNotificationAndFcmManager.CALL_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    startForeground(
                        SystemNotificationAndFcmManager.CALL_NOTIFICATION_ID,
                        notification
                    )
                }
            } catch (_: Throwable) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context, isVideo: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra("is_video", isVideo)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CallForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
