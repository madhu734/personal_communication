package com.example.network

import com.example.storage.SessionAndMuteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RealtimeRecordEvent(
    val topic: String,
    val collection: String,
    val action: String, // "create", "update", "delete"
    val record: JSONObject
)

/**
 * Implements PocketBase's realtime SSE protocol:
 * 1. Opens `GET /api/realtime`
 * 2. Receives `PB_CONNECT` with `clientId`
 * 3. Subscribes with authenticated `POST /api/realtime` and `{clientId, subscriptions}`
 * 4. Emits record create/update/delete events and triggers catch-up on reconnect.
 */
class PocketBaseRealtimeClient(
    private val sessionStore: SessionAndMuteStore,
    private val enableNetworkSse: Boolean = true
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sseHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // Keep SSE stream open while foregrounded
        .retryOnConnectionFailure(true)
        .build()

    private var activeEventSource: EventSource? = null
    private var reconnectJob: Job? = null
    @Volatile
    private var shouldStayConnected: Boolean = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeRecordEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RealtimeRecordEvent> = _events.asSharedFlow()

    private val _onReconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val onReconnected: SharedFlow<Unit> = _onReconnected.asSharedFlow()

    internal suspend fun emitEventForTest(event: RealtimeRecordEvent) {
        _events.emit(event)
    }

    internal suspend fun emitReconnectForTest() {
        _isConnected.value = true
        _onReconnected.emit(Unit)
    }

    fun connectIfAuthenticated() {
        val token = sessionStore.getAuthToken()
        if (token.isBlank()) {
            disconnect()
            return
        }
        shouldStayConnected = true
        if (!enableNetworkSse) return
        if (activeEventSource != null) return
        openEventSource()
    }

    fun disconnect() {
        shouldStayConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        activeEventSource?.cancel()
        activeEventSource = null
        _isConnected.value = false
    }

    private fun openEventSource() {
        val token = sessionStore.getAuthToken()
        if (!shouldStayConnected || token.isBlank()) return

        val request = Request.Builder()
            .url("${sessionStore.baseUrl}/api/realtime")
            .header("Accept", "text/event-stream")
            .header("Authorization", token)
            .build()

        val factory = EventSources.createFactory(sseHttpClient)
        activeEventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                _isConnected.value = true
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data.isBlank()) return
                try {
                    val json = JSONObject(data)
                    if (type == "PB_CONNECT") {
                        val clientId = json.optString("clientId").ifBlank { id.orEmpty() }
                        if (clientId.isNotBlank()) {
                            scope.launch {
                                subscribeTopics(clientId)
                                _onReconnected.emit(Unit)
                            }
                        }
                        return
                    }
                    val action = json.optString("action")
                    val record = json.optJSONObject("record") ?: return
                    val topic = type.orEmpty()
                    val collection = topic.substringBefore("/").ifBlank {
                        record.optString("collectionName")
                    }
                    if (action.isNotBlank() && collection.isNotBlank()) {
                        _events.tryEmit(
                            RealtimeRecordEvent(
                                topic = topic,
                                collection = collection,
                                action = action,
                                record = record
                            )
                        )
                    }
                } catch (_: Exception) {
                    // Ignore malformed heartbeats
                }
            }

            override fun onClosed(eventSource: EventSource) {
                _isConnected.value = false
                activeEventSource = null
                scheduleReconnect()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                _isConnected.value = false
                activeEventSource = null
                scheduleReconnect()
            }
        })
    }

    private fun subscribeTopics(clientId: String) {
        val token = sessionStore.getAuthToken()
        if (token.isBlank()) return
        val topics = JSONArray().apply {
            DEFAULT_TOPICS.forEach { put(it) }
        }
        val payload = JSONObject().apply {
            put("clientId", clientId)
            put("subscriptions", topics)
        }
        val req = Request.Builder()
            .url("${sessionStore.baseUrl}/api/realtime")
            .header("Authorization", token)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            sseHttpClient.newCall(req).execute().close()
        } catch (_: Exception) {
        }
    }

    private fun scheduleReconnect() {
        if (!shouldStayConnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3_500L)
            if (shouldStayConnected && activeEventSource == null) {
                openEventSource()
            }
        }
    }

    companion object {
        val DEFAULT_TOPICS = listOf(
            "conversations/*",
            "conversation_members/*",
            "message_requests/*",
            "messages/*",
            "message_hides/*",
            "message_reactions/*",
            "calls/*"
        )
    }
}
