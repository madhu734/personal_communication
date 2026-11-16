package com.example.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallsManager
import com.example.model.ActiveCallUiState
import com.example.model.AudioEndpointItem
import com.example.model.AudioEndpointType
import com.example.model.CallKind
import com.example.model.CallPhase
import com.example.model.CallRecord
import com.example.model.CallRecordStatus
import com.example.model.PeerSummary
import com.example.model.VideoQualityPreset
import com.example.model.WatermarkCursorUtils
import com.example.network.ApiException
import com.example.network.PocketBaseApiClient
import com.example.storage.SessionAndMuteStore
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.util.flow
import io.livekit.android.audio.AudioSwitchHandler
import com.twilio.audioswitch.AudioDevice
import kotlinx.coroutines.CancellationException
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoPreset169
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import livekit.org.webrtc.PeerConnection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single session-scoped call coordinator shared by Compose UI, FirebaseMessagingService,
 * notification action broadcasts, Android Core-Telecom, and the LiveKit WebRTC Room SDK.
 * All blocking audio/hardware calls are offloaded to Dispatchers.IO so the UI never freezes.
 */
class CallCoordinator(
    private val appContext: Context,
    private val sessionStore: SessionAndMuteStore,
    private val apiClient: PocketBaseApiClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callMutex = Mutex()
    private val roomLifecycleMutex = Mutex()
    private val seenTerminalCallIds = ConcurrentHashMap.newKeySet<String>()
    private val roomJoinedCallIds = ConcurrentHashMap.newKeySet<String>()

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var activeLiveKitRoom: Room? = null
    private var roomEventsJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var callPollingJob: Job? = null
    private var durationTimerJob: Job? = null
    private var activeRingtone: Ringtone? = null
    private var roomConnectJob: Job? = null

    private val _uiState = MutableStateFlow(ActiveCallUiState())
    val uiState: StateFlow<ActiveCallUiState> = _uiState.asStateFlow()

    private val _activeRoomFlow = MutableStateFlow<Room?>(null)
    val activeRoomFlow: StateFlow<Room?> = _activeRoomFlow.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<LocalVideoTrack?>(null)
    val localVideoTrack: StateFlow<LocalVideoTrack?> = _localVideoTrack.asStateFlow()

    var peerLookupProvider: ((String, String) -> PeerSummary)? = null

    init {
        // Register with Telecom asynchronously on IO thread so startup never blocks
        ioScope.launch {
            try {
                CallsManager(appContext).registerAppWithTelecom(
                    CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING or
                        CallsManager.CAPABILITY_SUPPORTS_CALL_STREAMING
                )
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------- Outgoing & Incoming Call Entry Points ----------------

    fun startOutgoingCall(
        conversationId: String,
        peer: PeerSummary,
        kind: CallKind
    ) {
        if (sessionStore.getAuthToken().isBlank()) return
        val pendingCallId = "starting_" + UUID.randomUUID()
        scope.launch {
            val canStart = callMutex.withLock {
                val currentPhase = _uiState.value.phase
                if (currentPhase != CallPhase.IDLE && currentPhase != CallPhase.ENDED) {
                    false
                } else {
                    _uiState.value = ActiveCallUiState(
                        callId = pendingCallId,
                        conversationId = conversationId,
                        peer = peer,
                        kind = kind,
                        isOutgoing = true,
                        phase = CallPhase.OUTGOING_RINGING,
                        isMicEnabled = true,
                        isCameraEnabled = kind == CallKind.VIDEO,
                        statusBannerText = "Calling ${peer.name}..."
                    )
                    true
                }
            }
            if (!canStart) return@launch

            val endpoints = querySystemAudioEndpoints()
            try {
                val record = apiClient.startCall(conversationId, kind)
                if (_uiState.value.callId != pendingCallId || _uiState.value.phase != CallPhase.OUTGOING_RINGING) {
                    apiClient.endCall(record.id)
                    return@launch
                }
                val expiresMs = WatermarkCursorUtils.parseInstantMillis(record.ringExpiresAt)
                    .takeIf { it > 0L } ?: (System.currentTimeMillis() + 45_000L)

                callMutex.withLock {
                    _uiState.value = ActiveCallUiState(
                        callId = record.id,
                        conversationId = record.conversation,
                        peer = peer,
                        kind = record.kind,
                        isOutgoing = true,
                        phase = CallPhase.OUTGOING_RINGING,
                        isMicEnabled = true,
                        isCameraEnabled = record.kind == CallKind.VIDEO,
                        availableEndpoints = endpoints,
                        statusBannerText = "Ringing...",
                        roomName = record.room
                    )
                }

                CallForegroundService.start(appContext, record.kind == CallKind.VIDEO)
                SystemNotificationAndFcmManager.showOngoingOrIncomingCallNotification(
                    context = appContext,
                    state = _uiState.value,
                    isIncoming = false
                )
                scheduleRingExpiryTimer(record.id, expiresMs)
                startAuthoritativeCallPoller(record.id)
            } catch (e: Exception) {
                callMutex.withLock {
                    if (_uiState.value.callId != pendingCallId) return@launch
                    _uiState.value = ActiveCallUiState(
                        callId = "err_${System.currentTimeMillis()}",
                        conversationId = conversationId,
                        peer = peer,
                        kind = kind,
                        phase = CallPhase.ENDED,
                        statusBannerText = e.message ?: "Unable to start call"
                    )
                }
                delay(2_000L)
                resetToIdleIfEnded()
            }
        }
    }

    /**
     * Handles incoming call signal from FCM (`incoming_call`) or SSE (`calls` topic).
     * Posts a generic privacy-safe CallStyle notification promptly, then revalidates via
     * authenticated `GET /api/collections/calls/records/{id}`.
     */
    fun onIncomingCallSignal(
        callId: String,
        expiresAtRaw: String,
        preloadedRecord: CallRecord? = null,
        fallbackPeer: PeerSummary? = null
    ) {
        val signalReceivedMs = System.currentTimeMillis()
        scope.launch {
            val endpoints = querySystemAudioEndpoints()
            val acceptedForRinging = callMutex.withLock {
                if (callId.isBlank() || seenTerminalCallIds.contains(callId)) return@withLock false
                val expiresMs = WatermarkCursorUtils.parseInstantMillis(expiresAtRaw)
                    .takeIf { it > 0L } ?: (signalReceivedMs + 45_000L)
                if (expiresMs <= signalReceivedMs) {
                    return@withLock false
                }
                val current = _uiState.value
                if (current.callId == callId && current.phase != CallPhase.IDLE) {
                    return@withLock false
                }
                if (current.phase != CallPhase.IDLE && current.phase != CallPhase.ENDED) {
                    return@withLock false
                }

                val initialPeer = fallbackPeer ?: PeerSummary(id = "", name = "Incoming Call")
                val initialKind = preloadedRecord?.kind ?: CallKind.AUDIO
                _uiState.value = ActiveCallUiState(
                    callId = callId,
                    conversationId = preloadedRecord?.conversation.orEmpty(),
                    peer = initialPeer,
                    kind = initialKind,
                    isOutgoing = false,
                    phase = CallPhase.INCOMING_RINGING,
                    isMicEnabled = true,
                    isCameraEnabled = false,
                    availableEndpoints = endpoints,
                    statusBannerText = "Incoming ${initialKind.wireName} call..."
                )
                scheduleRingExpiryTimer(callId, expiresMs)
                true
            }

            if (!acceptedForRinging) return@launch

            startRingtoneAsync()
            SystemNotificationAndFcmManager.showOngoingOrIncomingCallNotification(
                context = appContext,
                state = _uiState.value,
                isIncoming = true
            )
            startAuthoritativeCallPoller(callId)

            if (preloadedRecord == null && sessionStore.getAuthToken().isNotBlank()) {
                try {
                    val authoritative = apiClient.fetchCallRecord(callId)
                    if (peerLookupProvider == null && fallbackPeer == null) {
                        try {
                            val inbox = apiClient.fetchInbox()
                            val matchedPeer = inbox.chats.firstOrNull {
                                it.id == authoritative.conversation ||
                                    it.peer.id == authoritative.caller
                            }?.peer
                            if (matchedPeer != null) {
                                callMutex.withLock {
                                    if (_uiState.value.callId == callId) {
                                        _uiState.update { it.copy(peer = matchedPeer) }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                    onCallRecordUpdated(authoritative)
                } catch (e: ApiException) {
                    if (e.statusCode == 404 || e.statusCode == 401 || e.statusCode == 403) {
                        terminateCallInternal(callId, "Call ended")
                    }
                } catch (_: Exception) {
                }
            } else if (preloadedRecord != null) {
                onCallRecordUpdated(preloadedRecord)
            }
        }
    }

    fun onCallRecordUpdated(record: CallRecord) {
        scope.launch {
            var shouldConnectRoom = false
            callMutex.withLock {
                val ownUserId = sessionStore.getUserRecord()?.id.orEmpty()
                if (ownUserId.isNotBlank() &&
                    record.caller != ownUserId &&
                    record.receiver != ownUserId
                ) {
                    if (_uiState.value.callId == record.id) {
                        cleanupCallResourcesLocked(record.id, "Call belongs to another account")
                    }
                    return@withLock
                }

                if (record.status.isTerminal) {
                    seenTerminalCallIds.add(record.id)
                    if (_uiState.value.callId == record.id) {
                        cleanupCallResourcesLocked(record.id, "Call ${record.status.wireName}")
                    }
                    return@withLock
                }

                val current = _uiState.value
                val peerId = if (record.caller == ownUserId) record.receiver else record.caller
                val resolvedPeer = peerLookupProvider?.invoke(record.conversation, peerId)
                    ?: current.peer.takeIf { it.name.isNotBlank() && it.name != "Incoming Call" }
                    ?: PeerSummary(id = peerId, name = "Contact")

                if (current.phase == CallPhase.IDLE &&
                    record.status == CallRecordStatus.RINGING &&
                    record.receiver == ownUserId
                ) {
                    val expiresMs = WatermarkCursorUtils.parseInstantMillis(record.ringExpiresAt)
                        .takeIf { it > 0L } ?: (System.currentTimeMillis() + 45_000L)
                    if (expiresMs > System.currentTimeMillis()) {
                        _uiState.value = ActiveCallUiState(
                            callId = record.id,
                            conversationId = record.conversation,
                            peer = resolvedPeer,
                            kind = record.kind,
                            isOutgoing = false,
                            phase = CallPhase.INCOMING_RINGING,
                            isMicEnabled = true,
                            isCameraEnabled = false,
                            statusBannerText = "Incoming ${record.kind.wireName} call",
                            roomName = record.room
                        )
                        startRingtoneAsync()
                        SystemNotificationAndFcmManager.showOngoingOrIncomingCallNotification(
                            context = appContext,
                            state = _uiState.value,
                            isIncoming = true
                        )
                        scheduleRingExpiryTimer(record.id, expiresMs)
                        startAuthoritativeCallPoller(record.id)
                    }
                    return@withLock
                }

                if (current.callId != record.id) return@withLock

                _uiState.update {
                    it.copy(
                        conversationId = record.conversation.ifBlank { it.conversationId },
                        peer = resolvedPeer,
                        kind = record.kind,
                        secureBy = record.secureBy,
                        roomName = record.room.ifBlank { it.roomName }
                    )
                }

                if (current.phase == CallPhase.INCOMING_RINGING) {
                    SystemNotificationAndFcmManager.showOngoingOrIncomingCallNotification(
                        context = appContext,
                        state = _uiState.value,
                        isIncoming = true
                    )
                }

                if ((record.status == CallRecordStatus.ACCEPTED || record.status == CallRecordStatus.CONNECTED) &&
                    (current.phase == CallPhase.OUTGOING_RINGING || current.phase == CallPhase.ANSWERING)
                ) {
                    stopRingtoneAsync()
                    if (roomJoinedCallIds.add(record.id)) {
                        _uiState.update {
                            it.copy(phase = CallPhase.CONNECTING, statusBannerText = "Connecting...")
                        }
                        shouldConnectRoom = true
                    }
                }
            }

            if (shouldConnectRoom) {
                connectToLiveKitRoom(record.id)
            }
        }
    }

    /**
     * Polls `GET /api/collections/calls/records/{callId}` every 1.5 seconds while a call is active
     * or ringing so call acceptance, connection, or remote hang-up is detected reliably even if SSE lags.
     */
    private fun startAuthoritativeCallPoller(callId: String) {
        callPollingJob?.cancel()
        callPollingJob = ioScope.launch {
            while (true) {
                delay(1_500L)
                val current = _uiState.value
                if (current.callId != callId ||
                    current.phase == CallPhase.IDLE ||
                    current.phase == CallPhase.ENDED
                ) {
                    break
                }
                if (sessionStore.getAuthToken().isBlank()) break
                try {
                    val fresh = apiClient.fetchCallRecord(callId)
                    onCallRecordUpdated(fresh)
                    if (fresh.status.isTerminal) break
                } catch (e: ApiException) {
                    if (e.statusCode == 404 || e.statusCode == 401 || e.statusCode == 403) {
                        terminateCallInternal(callId, "Call ended")
                        break
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------------- Call Control Actions (Answer / Decline / End) ----------------

    fun answerIncomingCall(withVideo: Boolean = false) {
        val snapshot = _uiState.value
        val callId = snapshot.callId
        if (callId.isBlank()) return

        scope.launch {
            val proceed = callMutex.withLock {
                if (_uiState.value.callId != callId ||
                    _uiState.value.phase != CallPhase.INCOMING_RINGING
                ) {
                    false
                } else {
                    stopRingtoneAsync()
                    _uiState.update {
                        it.copy(
                            phase = CallPhase.ANSWERING,
                            isCameraEnabled = withVideo && it.kind == CallKind.VIDEO,
                            statusBannerText = "Connecting..."
                        )
                    }
                    true
                }
            }
            if (!proceed) return@launch

            try {
                val accepted = try {
                    apiClient.acceptCall(callId)
                } catch (e: ApiException) {
                    // If the call was already accepted via another event/retry, verify state before failing
                    val record = try {
                        apiClient.fetchCallRecord(callId)
                    } catch (_: Exception) {
                        null
                    }
                    if (record != null &&
                        (record.status == CallRecordStatus.ACCEPTED || record.status == CallRecordStatus.CONNECTED)
                    ) {
                        record
                    } else {
                        throw e
                    }
                }
                if (_uiState.value.callId != callId || _uiState.value.phase == CallPhase.ENDED) return@launch
                CallForegroundService.start(appContext, accepted.kind == CallKind.VIDEO)
                if (roomJoinedCallIds.add(callId)) {
                    connectToLiveKitRoom(callId)
                }
            } catch (e: ApiException) {
                terminateCallInternal(callId, "Call ended (${e.statusCode})")
            } catch (e: Exception) {
                terminateCallInternal(callId, e.message ?: "Failed to answer call")
            }
        }
    }

    /**
     * Recovers and answers an incoming call from notification action if the process was restarted.
     */
    fun restoreAndAnswerCallFromNotification(callId: String) {
        if (callId.isBlank() || sessionStore.getAuthToken().isBlank()) return
        scope.launch {
            try {
                val record = apiClient.fetchCallRecord(callId)
                if (record.status.isTerminal) {
                    SystemNotificationAndFcmManager.cancelCallNotification(appContext)
                    CallForegroundService.stop(appContext)
                    return@launch
                }
                val peer = try {
                    apiClient.fetchInbox().chats.firstOrNull {
                        it.id == record.conversation || it.peer.id == record.caller
                    }?.peer
                } catch (_: Exception) {
                    null
                } ?: PeerSummary(id = record.caller, name = "Contact")
                val endpoints = querySystemAudioEndpoints()
                callMutex.withLock {
                    _uiState.value = ActiveCallUiState(
                        callId = record.id,
                        conversationId = record.conversation,
                        peer = peer,
                        kind = record.kind,
                        isOutgoing = false,
                        phase = CallPhase.INCOMING_RINGING,
                        isMicEnabled = true,
                        isCameraEnabled = false,
                        availableEndpoints = endpoints,
                        statusBannerText = "Connecting...",
                        roomName = record.room
                    )
                }
                answerIncomingCall(withVideo = false)
            } catch (_: Exception) {
                SystemNotificationAndFcmManager.cancelCallNotification(appContext)
                CallForegroundService.stop(appContext)
            }
        }
    }

    fun declineOrHangUpCall() {
        val snapshot = _uiState.value
        val callId = snapshot.callId
        if (callId.isBlank()) return
        val phase = snapshot.phase
        val isIncomingRinging = phase == CallPhase.INCOMING_RINGING && !snapshot.isOutgoing

        scope.launch {
            terminateCallInternal(callId, if (isIncomingRinging) "Call declined" else "Call ended")
            if (!callId.startsWith("starting_") &&
                !callId.startsWith("err_") &&
                sessionStore.getAuthToken().isNotBlank()
            ) {
                ioScope.launch {
                    try {
                        if (isIncomingRinging) {
                            apiClient.rejectCall(callId)
                        } else {
                            apiClient.endCall(callId)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    // ---------------- LiveKit WebRTC Room & Audio / Video Controls ----------------

    private fun connectToLiveKitRoom(callId: String) {
        roomConnectJob?.cancel()
        roomConnectJob = scope.launch {
            try {
                var grantError: Exception? = null
                var grant = null as com.example.model.CallTokenGrant?
                for (attempt in 0..2) {
                    if (_uiState.value.callId != callId || _uiState.value.phase == CallPhase.ENDED) {
                        return@launch
                    }
                    try {
                        grant = apiClient.fetchCallToken(callId)
                        break
                    } catch (e: Exception) {
                        grantError = e
                        if (attempt < 2) delay(600L)
                    }
                }
                val validGrant = grant ?: throw (grantError ?: IllegalStateException("Unable to fetch call token"))
                joinLiveKitWebRtcRoom(
                    callId = callId,
                    roomName = validGrant.roomName,
                    wsUrl = validGrant.wsUrl,
                    jwtToken = validGrant.token
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                roomJoinedCallIds.remove(callId)
                terminateCallInternal(callId, e.message ?: "Unable to connect call media")
            }
        }
    }

    private suspend fun joinLiveKitWebRtcRoom(
        callId: String,
        roomName: String,
        wsUrl: String,
        jwtToken: String
    ) {
        ringTimeoutJob?.cancel()
        stopRingtoneAsync()

        // Ensure ForegroundService is active with MICROPHONE / CAMERA types before WebRTC opens audio/video
        CallForegroundService.start(appContext, _uiState.value.kind == CallKind.VIDEO)

        val endpoints = querySystemAudioEndpoints()
        val defaultRoute = if (_uiState.value.kind == CallKind.VIDEO) {
            endpoints.firstOrNull { it.type == AudioEndpointType.SPEAKER } ?: endpoints.first()
        } else {
            endpoints.firstOrNull { it.type == AudioEndpointType.BLUETOOTH }
                ?: endpoints.firstOrNull { it.type == AudioEndpointType.WIRED_HEADSET }
                ?: endpoints.first()
        }

        _uiState.update {
            it.copy(
                phase = CallPhase.CONNECTING,
                roomName = roomName,
                currentEndpoint = defaultRoute,
                availableEndpoints = endpoints,
                statusBannerText = "Joining room..."
            )
        }

        try {
            // Wait for any previous LiveKit room teardown to finish completely before creating a new Room
            // or acquiring audio focus so delayed release() never resets AudioManager.mode mid-call.
            disconnectActiveRoomSuspend()

            if (_uiState.value.callId != callId || _uiState.value.phase == CallPhase.ENDED) return
            stopRingtoneBlocking()
            val room = LiveKit.create(appContext)
            val audioHandler = room.audioHandler as? AudioSwitchHandler
            audioHandler?.apply {
                // Keep an attached headset preferred for both voice and video calls.
                preferredDeviceList = listOf(
                    AudioDevice.BluetoothHeadset::class.java,
                    AudioDevice.WiredHeadset::class.java,
                    if (_uiState.value.kind == CallKind.VIDEO) AudioDevice.Speakerphone::class.java
                    else AudioDevice.Earpiece::class.java,
                    if (_uiState.value.kind == CallKind.VIDEO) AudioDevice.Earpiece::class.java
                    else AudioDevice.Speakerphone::class.java
                )
                audioDeviceChangeListener = { devices, selected ->
                    if (activeLiveKitRoom === room && _uiState.value.callId == callId) {
                        _uiState.update { state ->
                            state.copy(
                                availableEndpoints = devices.map(::audioEndpoint),
                                currentEndpoint = selected?.let(::audioEndpoint) ?: state.currentEndpoint
                            )
                        }
                    }
                }
            }
            activeLiveKitRoom = room
            _activeRoomFlow.value = room

            roomEventsJob?.cancel()
            roomEventsJob = scope.launch {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> {
                            val track = event.track
                            if (track is VideoTrack) {
                                updateRemoteVideoTrack(room)
                            } else if (track is AudioTrack) {
                                try {
                                    room.setSpeakerMute(false)
                                } catch (_: Exception) {
                                }
                            }
                        }

                        is RoomEvent.TrackUnsubscribed -> {
                            updateRemoteVideoTrack(room)
                        }

                        is RoomEvent.TrackMuted, is RoomEvent.TrackUnmuted -> updateRemoteVideoTrack(room)

                        is RoomEvent.Reconnecting -> {
                            if (_uiState.value.callId == callId && _uiState.value.phase == CallPhase.ACTIVE) {
                                _uiState.update {
                                    it.copy(
                                        phase = CallPhase.RECONNECTING,
                                        wsConnected = false,
                                        statusBannerText = "Reconnecting..."
                                    )
                                }
                            }
                        }

                        is RoomEvent.Reconnected -> {
                            if (_uiState.value.callId == callId) {
                                _uiState.update {
                                    it.copy(
                                        phase = CallPhase.ACTIVE,
                                        wsConnected = true,
                                        statusBannerText = "Connected"
                                    )
                                }
                            }
                        }

                        is RoomEvent.Disconnected -> {
                            if (_uiState.value.callId == callId &&
                                (_uiState.value.phase == CallPhase.ACTIVE || _uiState.value.phase == CallPhase.RECONNECTING)
                            ) {
                                terminateCallInternal(callId, "Call connection lost")
                            }
                        }

                        else -> Unit
                    }
                }
            }

            val mediaConnected = withTimeoutOrNull(10_000L) {
                room.connect(url = wsUrl, token = jwtToken)
                room::state.flow.first { it == Room.State.CONNECTED }
                true
            } ?: false
            if (!mediaConnected) {
                room.disconnect()
                _uiState.update { it.copy(statusBannerText = "Connecting through relay...") }
                val grant = apiClient.fetchCallToken(callId)
                val relay = PeerConnection.RTCConfiguration(emptyList()).apply {
                    iceTransportsType = PeerConnection.IceTransportsType.RELAY
                }
                val relayed = withTimeoutOrNull(15_000L) {
                    room.connect(url = grant.wsUrl, token = grant.token, options = ConnectOptions(rtcConfig = relay))
                    room::state.flow.first { it == Room.State.CONNECTED }
                    true
                } ?: false
                check(relayed) { "Could not connect call audio or video. Try another network." }
            }

            try {
                room.setSpeakerMute(false)
                room.setMicrophoneMute(!_uiState.value.isMicEnabled)
            } catch (_: Exception) {
            }

            val hasMicPerm = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasAudioInputHardware = withContext(Dispatchers.IO) {
                try {
                    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).isNotEmpty()
                } catch (_: Exception) {
                    true
                }
            }

            if (hasMicPerm && hasAudioInputHardware && _uiState.value.isMicEnabled) {
                try {
                    setMicrophoneEnabled(room, true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update { it.copy(isMicEnabled = false, statusBannerText = "Microphone unavailable. Check permissions and try again.") }
                }
            }

            val hasCamPerm = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (hasCamPerm &&
                _uiState.value.isCameraEnabled &&
                !_uiState.value.videoQuality.audioOnly
            ) {
                try {
                    setCameraEnabled(room, true)
                    updateLocalVideoTrackRef(room)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update { it.copy(isCameraEnabled = false, statusBannerText = "Camera unavailable. Check permissions and try again.") }
                }
            }

            if (_uiState.value.callId != callId || activeLiveKitRoom !== room) return
            updateRemoteVideoTrack(room)

            _uiState.update {
                it.copy(
                    phase = CallPhase.ACTIVE,
                    statusBannerText = if (it.isMicEnabled) "Connected" else "Connected. Microphone is off.",
                    wsConnected = true
                )
            }
            SystemNotificationAndFcmManager.showOngoingOrIncomingCallNotification(
                context = appContext,
                state = _uiState.value,
                isIncoming = false
            )
            startDurationTimer(callId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            roomJoinedCallIds.remove(callId)
            terminateCallInternal(callId, e.message ?: "Call connection failed")
        }
    }

    private suspend fun setMicrophoneEnabled(room: Room, enabled: Boolean) {
        room.localParticipant.setMicrophoneEnabled(enabled)
        checkMediaPublication(room, Track.Source.MICROPHONE, enabled)
    }

    private suspend fun setCameraEnabled(room: Room, enabled: Boolean) {
        room.localParticipant.setCameraEnabled(enabled)
        checkMediaPublication(room, Track.Source.CAMERA, enabled)
    }

    private fun checkMediaPublication(room: Room, source: Track.Source, enabled: Boolean) {
        val publication = room.localParticipant.getTrackPublication(source)
        check((publication?.track != null && !publication.muted) == enabled) {
            "Could not change ${source.name.lowercase()}"
        }
    }

    private fun updateRemoteVideoTrack(room: Room) {
        if (activeLiveKitRoom !== room) return
        _remoteVideoTrack.value = room.remoteParticipants.values
            .flatMap { it.trackPublications.values }
            .filter { !it.muted && it.track is VideoTrack }
            .maxByOrNull { it.source == Track.Source.SCREEN_SHARE }?.track as? VideoTrack
    }

    private fun updateLocalVideoTrackRef(room: Room) {
        val pubTrack = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track
        _localVideoTrack.value = pubTrack as? LocalVideoTrack
    }

    /**
     * Ensures that if RECORD_AUDIO permission is granted after answering a call (e.g. from a
     * system notification), the microphone track is immediately enabled and published.
     */
    fun ensureMicrophonePublishedIfPermitted() {
        val room = activeLiveKitRoom ?: return
        if (room.state != Room.State.CONNECTED) return
        val hasMicPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm || !_uiState.value.isMicEnabled) return
        scope.launch {
            try {
                room.setMicrophoneMute(false)
                setMicrophoneEnabled(room, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isMicEnabled = false, statusBannerText = "Microphone unavailable. Check permissions and try again.") }
            }
        }
    }

    fun toggleMic() {
        val next = !_uiState.value.isMicEnabled
        _uiState.update { it.copy(isMicEnabled = next) }
        val room = activeLiveKitRoom
        scope.launch {
            try {
                if (room != null) {
                    room.setMicrophoneMute(!next)
                    setMicrophoneEnabled(room, next)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isMicEnabled = !next, statusBannerText = "Unable to change microphone. Try again.") }
            }
        }
    }

    fun toggleCamera() {
        val current = _uiState.value
        val next = !current.isCameraEnabled
        _uiState.update {
            it.copy(
                isCameraEnabled = next,
                kind = if (next) CallKind.VIDEO else it.kind
            )
        }
        val room = activeLiveKitRoom ?: return
        scope.launch {
            try {
                CallForegroundService.start(appContext, next)
                setCameraEnabled(room, next)
                updateLocalVideoTrackRef(room)
                if (next && _uiState.value.videoQuality.audioOnly) {
                    _uiState.update { it.copy(videoQuality = VideoQualityPreset.AUTO_720P) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isCameraEnabled = !next, statusBannerText = "Unable to change camera. Check camera permission.") }
            }
        }
    }

    fun switchCameraFacing() {
        val nextFront = !_uiState.value.isFrontCamera
        _uiState.update { it.copy(isFrontCamera = nextFront) }
        val localTrack = _localVideoTrack.value ?: return
        scope.launch {
            try {
                localTrack.switchCamera(
                    position = if (nextFront) CameraPosition.FRONT else CameraPosition.BACK
                )
            } catch (_: Exception) {
            }
        }
    }

    fun setVideoQualityPreset(preset: VideoQualityPreset) {
        val room = activeLiveKitRoom ?: return
        scope.launch {
            try {
                if (preset.audioOnly) {
                    setCameraEnabled(room, false)
                } else {
                    val track = _localVideoTrack.value
                    if (track != null) {
                        val capture = if (preset == VideoQualityPreset.SMOOTH_360P) VideoPreset169.H360.capture
                        else VideoPreset169.H720.capture
                        track.restartTrack(track.options.copy(captureParams = capture))
                    }
                }
                if (activeLiveKitRoom !== room) return@launch
                _uiState.update {
                    it.copy(videoQuality = preset, isCameraEnabled = !preset.audioOnly && it.isCameraEnabled)
                }
                updateLocalVideoTrackRef(room)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(statusBannerText = "Unable to change video quality. Try again.") }
            }
        }
    }

    fun setScreenSharing(enabled: Boolean, mediaProjectionPermissionResultData: android.content.Intent? = null) {
        val room = activeLiveKitRoom ?: return
        scope.launch {
            try {
                val params = mediaProjectionPermissionResultData?.let { ScreenCaptureParams(it) }
                check(room.localParticipant.setScreenShareEnabled(enabled, params))
                if (activeLiveKitRoom === room) {
                    _uiState.update {
                        it.copy(
                            isScreenSharing = enabled,
                            statusBannerText = if (enabled) "Sharing screen" else "Connected"
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeLiveKitRoom === room) {
                    _uiState.update { it.copy(statusBannerText = "Could not change screen sharing.") }
                }
            }
        }
    }

    fun toggleSecureMode() {
        val state = _uiState.value
        val user = sessionStore.getUserRecord()?.id.orEmpty()
        if (state.callId.isBlank() || user.isBlank()) return
        if (state.secureBy.isNotBlank() && state.secureBy != user) {
            _uiState.update { it.copy(statusBannerText = "Only the person who enabled secure mode can turn it off.") }
            return
        }
        scope.launch {
            try {
                val updated = apiClient.setCallSecure(state.callId, state.secureBy.isBlank())
                onCallRecordUpdated(updated)
            } catch (e: Exception) {
                _uiState.update { it.copy(statusBannerText = "Could not change secure mode. Try again.") }
            }
        }
    }

    fun toggleFocusMode() {
        _uiState.update {
            it.copy(
                isFocusMode = !it.isFocusMode,
                showInCallChatSheet = if (!it.isFocusMode) false else it.showInCallChatSheet
            )
        }
    }

    fun setMinimizedInApp(minimized: Boolean) {
        _uiState.update {
            it.copy(
                isMinimizedInApp = minimized,
                isFocusMode = if (minimized) false else it.isFocusMode,
                showInCallChatSheet = if (minimized) false else it.showInCallChatSheet
            )
        }
    }

    fun setPipMode(inPip: Boolean) {
        _uiState.update {
            it.copy(
                isInPipMode = inPip,
                showInCallChatSheet = if (inPip) false else it.showInCallChatSheet
            )
        }
    }

    fun setShowInCallChatSheet(show: Boolean) {
        _uiState.update { it.copy(showInCallChatSheet = show) }
    }

    fun updateInCallChatDraft(draft: String) {
        _uiState.update { it.copy(inCallChatDraft = draft) }
    }

    // ---------------- Audio Endpoint Picker (Non-Blocking on IO) ----------------

    fun refreshAvailableAudioEndpoints() {
        val handler = activeLiveKitRoom?.audioHandler as? AudioSwitchHandler ?: return
        _uiState.update {
            it.copy(
                availableEndpoints = handler.availableAudioDevices.map(::audioEndpoint),
                currentEndpoint = handler.selectedAudioDevice?.let(::audioEndpoint) ?: it.currentEndpoint
            )
        }
    }

    fun requestAudioEndpointChange(endpoint: AudioEndpointItem) {
        val handler = activeLiveKitRoom?.audioHandler as? AudioSwitchHandler ?: return
        val selected = handler.availableAudioDevices.firstOrNull { audioEndpoint(it).id == endpoint.id }
        if (selected == null) {
            refreshAvailableAudioEndpoints()
            return
        }
        handler.selectDevice(selected)
        refreshAvailableAudioEndpoints()
    }

    private fun audioEndpoint(device: AudioDevice): AudioEndpointItem {
        val type = when (device) {
            is AudioDevice.BluetoothHeadset -> AudioEndpointType.BLUETOOTH
            is AudioDevice.WiredHeadset -> AudioEndpointType.WIRED_HEADSET
            is AudioDevice.Speakerphone -> AudioEndpointType.SPEAKER
            else -> AudioEndpointType.EARPIECE
        }
        return AudioEndpointItem(type.name + ":" + device.name, type, device.name)
    }

    private fun querySystemAudioEndpoints(): List<AudioEndpointItem> =
        (activeLiveKitRoom?.audioHandler as? AudioSwitchHandler)?.availableAudioDevices
            ?.map(::audioEndpoint)?.takeIf { it.isNotEmpty() }
            ?: listOf(
                AudioEndpointItem("earpiece", AudioEndpointType.EARPIECE),
                AudioEndpointItem("speaker", AudioEndpointType.SPEAKER)
            )

    // ---------------- Timers, Ringtone & Non-Blocking Teardown ----------------

    private fun scheduleRingExpiryTimer(callId: String, expiresAtEpochMs: Long) {
        ringTimeoutJob?.cancel()
        val waitMs = (expiresAtEpochMs - System.currentTimeMillis()).coerceIn(1_000L, 48_000L)
        ringTimeoutJob = scope.launch {
            delay(waitMs)
            val current = _uiState.value
            if (current.callId == callId &&
                (current.phase == CallPhase.INCOMING_RINGING || current.phase == CallPhase.OUTGOING_RINGING)
            ) {
                terminateCallInternal(callId, "No answer")
            }
        }
    }

    private fun startDurationTimer(callId: String) {
        durationTimerJob?.cancel()
        durationTimerJob = scope.launch {
            var seconds = 0L
            while (_uiState.value.callId == callId &&
                (_uiState.value.phase == CallPhase.ACTIVE || _uiState.value.phase == CallPhase.RECONNECTING)
            ) {
                delay(1_000L)
                seconds += 1L
                _uiState.update { it.copy(connectedElapsedSeconds = seconds) }
            }
        }
    }

    private fun startRingtoneAsync() {
        ioScope.launch {
            stopRingtoneBlocking()
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val ringtone = RingtoneManager.getRingtone(appContext, uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone?.isLooping = true
                }
                ringtone?.play()
                activeRingtone = ringtone
            } catch (_: Exception) {
            }
        }
    }

    private fun stopRingtoneAsync() {
        ioScope.launch { stopRingtoneBlocking() }
    }

    private fun stopRingtoneBlocking() {
        try {
            activeRingtone?.stop()
        } catch (_: Exception) {
        }
        activeRingtone = null
    }

    private fun detachActiveRoom(): Room? {
        roomEventsJob?.cancel()
        roomEventsJob = null
        _remoteVideoTrack.value = null
        _localVideoTrack.value = null
        val room = activeLiveKitRoom
        activeLiveKitRoom = null
        _activeRoomFlow.value = null
        return room
    }

    private suspend fun releaseRoom(room: Room?) {
        roomLifecycleMutex.withLock {
            if (room != null) {
                try {
                    room.disconnect()
                } finally {
                    room.release()
                }
            }
        }
    }

    private suspend fun disconnectActiveRoomSuspend() {
        releaseRoom(detachActiveRoom())
    }

    private suspend fun terminateCallInternal(callId: String, reason: String) {
        callMutex.withLock {
            if (_uiState.value.callId == callId || callId.startsWith("starting_")) {
                cleanupCallResourcesLocked(callId, reason)
            }
        }
    }

    fun onRemoteCallEnded(callId: String) {
        scope.launch { terminateCallInternal(callId, "Call ended") }
    }

    private fun cleanupCallResourcesLocked(callId: String, reason: String) {
        seenTerminalCallIds.add(callId)
        roomJoinedCallIds.remove(callId)
        ringTimeoutJob?.cancel()
        callPollingJob?.cancel()
        durationTimerJob?.cancel()
        roomConnectJob?.cancel()
        roomConnectJob = null
        val endedRoom = detachActiveRoom()
        scope.launch {
            stopRingtoneBlocking()
            releaseRoom(endedRoom)
        }
        CallForegroundService.stop(appContext)
        SystemNotificationAndFcmManager.cancelCallNotification(appContext)

        _uiState.update {
            it.copy(
                phase = CallPhase.ENDED,
                isCameraEnabled = false,
                isScreenSharing = false,
                secureBy = "",
                isMinimizedInApp = false,
                isInPipMode = false,
                showInCallChatSheet = false,
                statusBannerText = reason
            )
        }
        scope.launch {
            delay(1_000L)
            resetToIdleIfEnded()
        }
    }

    fun resetToIdleIfEnded() {
        if (_uiState.value.phase == CallPhase.ENDED) {
            _uiState.value = ActiveCallUiState()
        }
    }

}
