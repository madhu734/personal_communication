package com.example.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
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
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.SoftwareVideoDecoderFactory
import livekit.org.webrtc.SoftwareVideoEncoderFactory
import livekit.org.webrtc.VideoCodecInfo
import livekit.org.webrtc.VideoDecoder
import livekit.org.webrtc.VideoDecoderFactory
import livekit.org.webrtc.VideoEncoder
import livekit.org.webrtc.VideoEncoderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private var audioFocusRequest: AudioFocusRequest? = null

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
        scope.launch {
            val canStart = callMutex.withLock {
                val currentPhase = _uiState.value.phase
                if (currentPhase != CallPhase.IDLE && currentPhase != CallPhase.ENDED) {
                    false
                } else {
                    _uiState.value = ActiveCallUiState(
                        callId = "starting_${System.currentTimeMillis()}",
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

            val endpoints = withContext(Dispatchers.IO) { querySystemAudioEndpoints() }
            try {
                val record = apiClient.startCall(conversationId, kind)
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
            val endpoints = withContext(Dispatchers.IO) { querySystemAudioEndpoints() }
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
                    if (e.statusCode == 404) {
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
                val endpoints = withContext(Dispatchers.IO) { querySystemAudioEndpoints() }
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
        scope.launch {
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

        val endpoints = withContext(Dispatchers.IO) {
            querySystemAudioEndpoints()
        }
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

            withContext(Dispatchers.IO) {
                stopRingtoneBlocking()
                requestCallAudioFocusBlocking()
                applySystemAudioEndpointBlocking(defaultRoute)
            }

            val isEmulator = isRunningOnEmulator()
            val room = LiveKit.create(
                appContext = appContext,
                overrides = LiveKitOverrides(
                    videoEncoderFactory = if (isEmulator) LazySoftwareVideoEncoderFactory() else null,
                    videoDecoderFactory = if (isEmulator) LazySoftwareVideoDecoderFactory() else null,
                    audioOptions = AudioOptions(
                        audioOutputType = AudioType.CallAudioType(),
                        audioHandler = NoAudioHandler(),
                        disableCommunicationModeWorkaround = true,
                        javaAudioDeviceModuleCustomizer = { builder ->
                            builder.setUseHardwareAcousticEchoCanceler(!isEmulator)
                            builder.setUseHardwareNoiseSuppressor(!isEmulator)
                            builder.setAudioSource(
                                if (isEmulator) MediaRecorder.AudioSource.MIC
                                else MediaRecorder.AudioSource.VOICE_COMMUNICATION
                            )
                            builder.setUseStereoInput(false)
                            builder.setUseStereoOutput(false)
                        }
                    ),
                    eglBase = if (isEmulator) SoftwareOnlyEglBase() else null
                )
            )
            activeLiveKitRoom = room
            _activeRoomFlow.value = room

            roomEventsJob?.cancel()
            roomEventsJob = scope.launch {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> {
                            val track = event.track
                            if (track is VideoTrack) {
                                _remoteVideoTrack.value = track
                            } else if (track is AudioTrack) {
                                try {
                                    room.setSpeakerMute(false)
                                } catch (_: Exception) {
                                }
                                ioScope.launch {
                                    requestCallAudioFocusBlocking()
                                    applySystemAudioEndpointBlocking(_uiState.value.currentEndpoint)
                                }
                            }
                        }

                        is RoomEvent.TrackUnsubscribed -> {
                            val track = event.track
                            if (track is VideoTrack && _remoteVideoTrack.value == track) {
                                _remoteVideoTrack.value = null
                            }
                        }

                        is RoomEvent.Reconnecting -> {
                            if (_uiState.value.callId == callId) {
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
                                ioScope.launch {
                                    requestCallAudioFocusBlocking()
                                    applySystemAudioEndpointBlocking(_uiState.value.currentEndpoint)
                                }
                            }
                        }

                        is RoomEvent.Disconnected -> {
                            if (_uiState.value.callId == callId &&
                                _uiState.value.phase != CallPhase.ENDED
                            ) {
                                _uiState.update {
                                    it.copy(wsConnected = false)
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }

            // Connect with up to 3 attempts so transient ICE / WebSocket hiccups recover automatically
            var connectException: Exception? = null
            var activeUrl = wsUrl
            var activeToken = jwtToken
            for (attempt in 0..2) {
                if (_uiState.value.callId != callId || _uiState.value.phase == CallPhase.ENDED) {
                    return
                }
                try {
                    if (attempt > 0) {
                        val refreshedGrant = apiClient.fetchCallToken(callId)
                        activeUrl = refreshedGrant.wsUrl
                        activeToken = refreshedGrant.token
                    }
                    withContext(Dispatchers.IO) {
                        room.connect(url = activeUrl, token = activeToken)
                    }
                    connectException = null
                    break
                } catch (e: Exception) {
                    connectException = e
                    if (attempt < 2) delay(700L)
                }
            }
            if (connectException != null) {
                throw connectException
            }

            try {
                room.setSpeakerMute(false)
                room.setMicrophoneMute(!_uiState.value.isMicEnabled)
            } catch (_: Exception) {
            }

            withContext(Dispatchers.IO) {
                requestCallAudioFocusBlocking()
                applySystemAudioEndpointBlocking(_uiState.value.currentEndpoint)
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

            if (hasMicPerm && !isEmulator && hasAudioInputHardware && _uiState.value.isMicEnabled) {
                try {
                    room.localParticipant.setMicrophoneEnabled(true)
                } catch (_: Exception) {
                }
            }

            val hasCamPerm = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (hasCamPerm &&
                !isEmulator &&
                _uiState.value.isCameraEnabled &&
                !_uiState.value.videoQuality.audioOnly
            ) {
                try {
                    room.localParticipant.setCameraEnabled(true)
                    updateLocalVideoTrackRef(room)
                } catch (_: Exception) {
                }
            }

            // Check if remote participant already published a video track
            room.remoteParticipants.values.forEach { participant ->
                participant.videoTrackPublications.forEach { (_, track) ->
                    if (track is VideoTrack) {
                        _remoteVideoTrack.value = track
                    }
                }
            }

            _uiState.update {
                it.copy(
                    phase = CallPhase.ACTIVE,
                    statusBannerText = "Connected",
                    wsConnected = true
                )
            }
            SystemNotificationAndFcmManager.showOngoingOrIncomingCallNotification(
                context = appContext,
                state = _uiState.value,
                isIncoming = false
            )
            startDurationTimer(callId)
        } catch (e: Exception) {
            roomJoinedCallIds.remove(callId)
            terminateCallInternal(callId, e.message ?: "Call connection failed")
        }
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
        if (isRunningOnEmulator()) return
        val hasMicPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm || !_uiState.value.isMicEnabled) return
        scope.launch {
            try {
                room.setMicrophoneMute(false)
                room.localParticipant.setMicrophoneEnabled(true)
            } catch (_: Exception) {
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
                    if (!isRunningOnEmulator()) {
                        room.localParticipant.setMicrophoneEnabled(next)
                    }
                }
                withContext(Dispatchers.IO) {
                    audioManager.isMicrophoneMute = !next
                }
            } catch (_: Exception) {
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
        if (isRunningOnEmulator()) return
        scope.launch {
            try {
                room.localParticipant.setCameraEnabled(next)
                updateLocalVideoTrackRef(room)
            } catch (_: Exception) {
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
        _uiState.update {
            it.copy(
                videoQuality = preset,
                isCameraEnabled = if (preset.audioOnly) false else it.isCameraEnabled,
                statusBannerText = preset.label
            )
        }
        if (preset.audioOnly) {
            val room = activeLiveKitRoom ?: return
            scope.launch {
                try {
                    room.localParticipant.setCameraEnabled(false)
                    _localVideoTrack.value = null
                } catch (_: Exception) {
                }
            }
        }
    }

    fun setScreenSharing(enabled: Boolean, mediaProjectionPermissionResultData: android.content.Intent? = null) {
        _uiState.update {
            it.copy(
                isScreenSharing = enabled,
                statusBannerText = if (enabled) "Sharing screen" else "Connected"
            )
        }
        val room = activeLiveKitRoom ?: return
        scope.launch {
            try {
                room.localParticipant.setScreenShareEnabled(enabled, mediaProjectionPermissionResultData)
            } catch (_: Exception) {
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
        ioScope.launch {
            val endpoints = querySystemAudioEndpoints()
            _uiState.update { state ->
                val activeStillPresent = endpoints.firstOrNull { it.type == state.currentEndpoint.type }
                    ?: endpoints.firstOrNull()
                    ?: state.currentEndpoint
                state.copy(
                    availableEndpoints = endpoints,
                    currentEndpoint = activeStillPresent
                )
            }
        }
    }

    fun requestAudioEndpointChange(endpoint: AudioEndpointItem) {
        _uiState.update { it.copy(currentEndpoint = endpoint) }
        ioScope.launch {
            requestCallAudioFocusBlocking()
            val applied = applySystemAudioEndpointBlocking(endpoint)
            if (!applied) {
                refreshAvailableAudioEndpoints()
            }
        }
    }

    @SuppressLint("SwitchIntDef")
    private fun querySystemAudioEndpoints(): List<AudioEndpointItem> {
        val endpoints = mutableListOf(
            AudioEndpointItem("earpiece", AudioEndpointType.EARPIECE, "Phone Earpiece"),
            AudioEndpointItem("speaker", AudioEndpointType.SPEAKER, "Speaker")
        )
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> {
                        if (endpoints.none { it.type == AudioEndpointType.WIRED_HEADSET }) {
                            endpoints.add(
                                AudioEndpointItem(
                                    id = "wired_${device.id}",
                                    type = AudioEndpointType.WIRED_HEADSET,
                                    name = device.productName?.toString()?.takeIf { it.isNotBlank() }
                                        ?: "Wired / USB Headset"
                                )
                            )
                        }
                    }

                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                        if (endpoints.none { it.type == AudioEndpointType.BLUETOOTH }) {
                            endpoints.add(
                                AudioEndpointItem(
                                    id = "bt_${device.id}",
                                    type = AudioEndpointType.BLUETOOTH,
                                    name = device.productName?.toString()?.takeIf { it.isNotBlank() }
                                        ?: "Bluetooth Audio"
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return endpoints
    }

    private fun applySystemAudioEndpointBlocking(endpoint: AudioEndpointItem): Boolean {
        return try {
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val targetDevices = audioManager.availableCommunicationDevices
                val matched = targetDevices.firstOrNull { dev ->
                    when (endpoint.type) {
                        AudioEndpointType.SPEAKER -> dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        AudioEndpointType.EARPIECE -> dev.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                        AudioEndpointType.WIRED_HEADSET -> dev.type in setOf(
                            AudioDeviceInfo.TYPE_WIRED_HEADSET,
                            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                            AudioDeviceInfo.TYPE_USB_HEADSET
                        )

                        AudioEndpointType.BLUETOOTH -> dev.type in setOf(
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                            AudioDeviceInfo.TYPE_BLE_HEADSET,
                            AudioDeviceInfo.TYPE_BLE_SPEAKER
                        )
                    }
                }
                if (matched != null) {
                    audioManager.setCommunicationDevice(matched)
                }
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = (endpoint.type == AudioEndpointType.SPEAKER)
            true
        } catch (_: Exception) {
            true
        }
    }

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

    private fun requestCallAudioFocusBlocking() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isMicrophoneMute = !_uiState.value.isMicEnabled
            if (audioFocusRequest == null) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                            val phase = _uiState.value.phase
                            if (phase == CallPhase.ACTIVE || phase == CallPhase.CONNECTING || phase == CallPhase.RECONNECTING) {
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            }
                        }
                    }
                    .build()
                audioManager.requestAudioFocus(req)
                audioFocusRequest = req
            }
        } catch (_: Exception) {
        }
    }

    private fun abandonCallAudioFocusBlocking() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    audioManager.clearCommunicationDevice()
                } catch (_: Exception) {
                }
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.isMicrophoneMute = false
            audioManager.mode = AudioManager.MODE_NORMAL
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } catch (_: Exception) {
        }
    }

    private suspend fun disconnectActiveRoomSuspend() {
        roomLifecycleMutex.withLock {
            roomEventsJob?.cancel()
            roomEventsJob = null
            _remoteVideoTrack.value = null
            _localVideoTrack.value = null
            val room = activeLiveKitRoom
            activeLiveKitRoom = null
            _activeRoomFlow.value = null
            if (room != null) {
                withContext(Dispatchers.IO) {
                    try {
                        room.disconnect()
                    } catch (_: Exception) {
                    }
                    try {
                        room.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun disconnectActiveRoomAsync() {
        ioScope.launch {
            disconnectActiveRoomSuspend()
        }
    }

    private suspend fun terminateCallInternal(callId: String, reason: String) {
        callMutex.withLock {
            if (_uiState.value.callId == callId || callId.startsWith("starting_")) {
                cleanupCallResourcesLocked(callId, reason)
            }
        }
    }

    private fun cleanupCallResourcesLocked(callId: String, reason: String) {
        seenTerminalCallIds.add(callId)
        roomJoinedCallIds.remove(callId)
        ringTimeoutJob?.cancel()
        callPollingJob?.cancel()
        durationTimerJob?.cancel()
        ioScope.launch {
            stopRingtoneBlocking()
            disconnectActiveRoomSuspend()
            val phase = _uiState.value.phase
            if (phase == CallPhase.IDLE || phase == CallPhase.ENDED) {
                abandonCallAudioFocusBlocking()
            }
        }
        SystemNotificationAndFcmManager.cancelCallNotification(appContext)
        CallForegroundService.stop(appContext)

        _uiState.update {
            it.copy(
                phase = CallPhase.ENDED,
                isCameraEnabled = false,
                isScreenSharing = false,
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

    private fun isRunningOnEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase()
        val hw = Build.HARDWARE.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fp.contains("generic") ||
            fp.contains("emulator") ||
            hw.contains("ranchu") ||
            hw.contains("goldfish") ||
            model.contains("sdk") ||
            model.contains("emulator") ||
            product.contains("sdk")
    }
}

/**
 * Software-backed EglBase implementation that avoids allocating hardware EGL14 contexts
 * or triggering `E/EGL_emulation: eglQueryContext 32c0 EGL_BAD_ATTRIBUTE` on the Android Emulator.
 */
private class SoftwareOnlyEglBase : EglBase {
    private val context = EglBase.Context { 0L }

    override fun createSurface(surface: android.view.Surface?) = Unit
    override fun createSurface(surfaceTexture: android.graphics.SurfaceTexture?) = Unit
    override fun createDummyPbufferSurface() = Unit
    override fun createPbufferSurface(width: Int, height: Int) = Unit
    override fun getEglBaseContext(): EglBase.Context = context
    override fun hasSurface(): Boolean = false
    override fun surfaceWidth(): Int = 0
    override fun surfaceHeight(): Int = 0
    override fun releaseSurface() = Unit
    override fun release() = Unit
    override fun makeCurrent() = Unit
    override fun detachCurrent() = Unit
    override fun swapBuffers() = Unit
    override fun swapBuffers(timeStampNs: Long) = Unit
}

/**
 * Lazily constructs SoftwareVideoEncoderFactory after LiveKit's RTCModule has loaded
 * `liblkjingle_peerconnection_so.so` via PeerConnectionFactory.initialize().
 */
private class LazySoftwareVideoEncoderFactory : VideoEncoderFactory {
    private val delegate: SoftwareVideoEncoderFactory by lazy {
        ensureWebRtcNativeLibraryLoaded()
        SoftwareVideoEncoderFactory()
    }

    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder? =
        delegate.createEncoder(info)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> =
        delegate.supportedCodecs
}

/**
 * Lazily constructs SoftwareVideoDecoderFactory after LiveKit's RTCModule has loaded
 * `liblkjingle_peerconnection_so.so` via PeerConnectionFactory.initialize().
 */
private class LazySoftwareVideoDecoderFactory : VideoDecoderFactory {
    private val delegate: SoftwareVideoDecoderFactory by lazy {
        ensureWebRtcNativeLibraryLoaded()
        SoftwareVideoDecoderFactory()
    }

    override fun createDecoder(info: VideoCodecInfo?): VideoDecoder? =
        delegate.createDecoder(info)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> =
        delegate.supportedCodecs
}

private fun ensureWebRtcNativeLibraryLoaded() {
    try {
        System.loadLibrary("lkjingle_peerconnection_so")
    } catch (_: Throwable) {
    }
}


