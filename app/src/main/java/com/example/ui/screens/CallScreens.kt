package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Expand
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.call.CallCoordinator
import com.example.model.ActiveCallUiState
import com.example.model.AudioEndpointItem
import com.example.model.AudioEndpointType
import com.example.model.CallKind
import com.example.model.CallPhase
import com.example.model.ChatMessage
import com.example.model.VideoQualityPreset
import com.example.repository.AppViewModel
import com.example.ui.theme.CoralHangup
import com.example.ui.theme.DeepForestBg
import com.example.ui.theme.ElevatedPineSurface
import com.example.ui.theme.EmeraldPrimaryDark
import com.example.ui.theme.JetBrainsMonoFontFamily
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenCallOverlay(
    callState: ActiveCallUiState,
    inCallMessages: List<ChatMessage>,
    viewModel: AppViewModel,
    onRequestEnterOsPip: () -> Unit
) {
    val context = LocalContext.current
    val coordinator = viewModel.callCoordinator
    val activeRoom by coordinator.activeRoomFlow.collectAsStateWithLifecycle()
    val localVideoTrack by coordinator.localVideoTrack.collectAsStateWithLifecycle()

    var showAudioEndpointSheet by remember { mutableStateOf(false) }
    var showQualitySheet by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        coordinator.toggleCamera()
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            coordinator.setScreenSharing(true, result.data)
        }
    }

    if (showAudioEndpointSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAudioEndpointSheet = false },
            containerColor = ElevatedPineSurface
        ) {
            AudioEndpointPickerSheet(
                currentEndpoint = callState.currentEndpoint,
                availableEndpoints = callState.availableEndpoints,
                onSelectEndpoint = {
                    coordinator.requestAudioEndpointChange(it)
                    showAudioEndpointSheet = false
                }
            )
        }
    }

    if (showQualitySheet) {
        ModalBottomSheet(
            onDismissRequest = { showQualitySheet = false },
            containerColor = ElevatedPineSurface
        ) {
            VideoQualityPickerSheet(
                currentPreset = callState.videoQuality,
                onSelectPreset = {
                    coordinator.setVideoQualityPreset(it)
                    showQualitySheet = false
                }
            )
        }
    }

    if (callState.showInCallChatSheet && !callState.isFocusMode) {
        ModalBottomSheet(
            onDismissRequest = { coordinator.setShowInCallChatSheet(false) },
            containerColor = ElevatedPineSurface
        ) {
            InCallChatSheetContent(
                conversationId = callState.conversationId,
                peerName = callState.peer.name,
                messages = inCallMessages,
                draft = callState.inCallChatDraft,
                onDraftChanged = { coordinator.updateInCallChatDraft(it) },
                onSend = {
                    if (callState.inCallChatDraft.isNotBlank() && callState.conversationId.isNotBlank()) {
                        viewModel.sendOrEditTextMessage(
                            conversationId = callState.conversationId,
                            text = callState.inCallChatDraft,
                            inCall = true
                        )
                        coordinator.updateInCallChatDraft("")
                    }
                }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepForestBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (callState.phase == CallPhase.ACTIVE &&
                    (callState.kind == CallKind.VIDEO || callState.isCameraEnabled)
                ) {
                    coordinator.toggleFocusMode()
                }
            }
            .testTag("full_screen_call_overlay")
    ) {
        RemoteCallMediaSurface(
            callState = callState,
            viewModel = viewModel
        )

        AnimatedVisibility(
            visible = callState.isCameraEnabled && !callState.isFocusMode && !callState.isInPipMode,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 76.dp, end = 16.dp)
        ) {
            LocalCameraPreviewCard(
                room = activeRoom,
                localVideoTrack = localVideoTrack,
                isFrontCamera = callState.isFrontCamera,
                onFlipCamera = { coordinator.switchCameraFacing() }
            )
        }

        AnimatedVisibility(
            visible = !callState.isFocusMode,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { coordinator.setMinimizedInApp(true) },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .testTag("minimize_call_in_app_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Minimize,
                            contentDescription = "Minimize Call",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = callState.peer.name.ifBlank { "Contact" },
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = when (callState.phase) {
                                CallPhase.ACTIVE -> formatCallDuration(callState.connectedElapsedSeconds) +
                                    " • " + callState.videoQuality.label
                                else -> callState.statusBannerText
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = Color(0xFFA7F3D0)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (callState.phase == CallPhase.ACTIVE &&
                        (callState.kind == CallKind.VIDEO || callState.isCameraEnabled)
                    ) {
                        IconButton(
                            onClick = onRequestEnterOsPip,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                                .testTag("enter_os_pip_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "Picture-in-Picture",
                                tint = Color.White
                            )
                        }
                    }

                    IconButton(
                        onClick = { showQualitySheet = true },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .testTag("video_quality_picker_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.HighQuality,
                            contentDescription = "Video Quality",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !callState.isFocusMode,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            if (callState.phase == CallPhase.INCOMING_RINGING && !callState.isOutgoing) {
                IncomingCallAnswerDock(
                    isVideoCall = callState.kind == CallKind.VIDEO,
                    onAnswerAudio = { coordinator.answerIncomingCall(withVideo = false) },
                    onAnswerVideo = { coordinator.answerIncomingCall(withVideo = true) },
                    onDecline = { coordinator.declineOrHangUpCall() }
                )
            } else {
                ActiveCallControlDock(
                    callState = callState,
                    onToggleMic = { coordinator.toggleMic() },
                    onToggleCamera = {
                        val hasCamPerm = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasCamPerm || callState.isCameraEnabled) {
                            coordinator.toggleCamera()
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onOpenAudioPicker = {
                        coordinator.refreshAvailableAudioEndpoints()
                        showAudioEndpointSheet = true
                    },
                    onToggleScreenShare = {
                        if (callState.isScreenSharing) {
                            coordinator.setScreenSharing(false)
                        } else {
                            try {
                                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                    as MediaProjectionManager
                                mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                            } catch (_: Exception) {
                                coordinator.setScreenSharing(true)
                            }
                        }
                    },
                    onOpenInCallChat = { coordinator.setShowInCallChatSheet(true) },
                    onHangUp = { coordinator.declineOrHangUpCall() }
                )
            }
        }
    }
}

@Composable
private fun RemoteCallMediaSurface(
    callState: ActiveCallUiState,
    viewModel: AppViewModel
) {
    val coordinator = viewModel.callCoordinator
    val remoteTrack by coordinator.remoteVideoTrack.collectAsStateWithLifecycle()
    val room by coordinator.activeRoomFlow.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF133829), DeepForestBg),
                    center = Offset(size.width * 0.5f, size.height * 0.4f),
                    radius = size.maxDimension * 0.75f
                )
            )
        }

        val currentRemoteTrack = remoteTrack
        val currentRoom = room
        if (currentRemoteTrack != null && currentRoom != null) {
            LiveKitVideoRendererView(
                room = currentRoom,
                videoTrack = currentRemoteTrack,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                ProtectedAvatarImage(
                    apiClient = viewModel.apiClient,
                    userId = callState.peer.id,
                    avatarFile = callState.peer.avatar,
                    fallbackName = callState.peer.name.ifBlank { "Contact" },
                    sizeDp = 104
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = callState.peer.name.ifBlank { "Contact" },
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        callState.isScreenSharing -> "Screen sharing active"
                        callState.phase == CallPhase.ACTIVE && callState.isFocusMode ->
                            "Tap anywhere to restore controls"
                        callState.phase == CallPhase.ACTIVE ->
                            "Connected • ${callState.currentEndpoint.name}"
                        else -> callState.statusBannerText
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFA7F3D0)
                )
            }
        }
    }
}

@Composable
private fun LiveKitVideoRendererView(
    room: Room,
    videoTrack: VideoTrack,
    modifier: Modifier = Modifier
) {
    var rendererRef by remember { mutableStateOf<TextureViewRenderer?>(null) }

    DisposableEffect(room, videoTrack, rendererRef) {
        val renderer = rendererRef
        if (renderer != null) {
            try {
                videoTrack.addRenderer(renderer)
            } catch (_: Exception) {
            }
        }
        onDispose {
            if (renderer != null) {
                try {
                    videoTrack.removeRenderer(renderer)
                } catch (_: Exception) {
                }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureViewRenderer(ctx).apply {
                try {
                    room.initVideoRenderer(this)
                } catch (_: Exception) {
                }
                rendererRef = this
            }
        },
        modifier = modifier
    )
}

@Composable
private fun LocalCameraPreviewCard(
    room: Room?,
    localVideoTrack: VideoTrack?,
    isFrontCamera: Boolean,
    onFlipCamera: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Bind CameraX ONLY when isFrontCamera or previewView changes — NEVER on every 1-second call timer tick!
    DisposableEffect(lifecycleOwner, isFrontCamera, previewViewRef, localVideoTrack) {
        val previewView = previewViewRef
        var cameraProvider: ProcessCameraProvider? = null
        if (localVideoTrack == null && previewView != null) {
            try {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(previewView.context)
                cameraProviderFuture.addListener({
                    try {
                        val provider = cameraProviderFuture.get()
                        cameraProvider = provider
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val selector = if (isFrontCamera) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, selector, preview)
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(previewView.context))
            } catch (_: Exception) {
            }
        }
        onDispose {
            try {
                cameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ElevatedPineSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier
            .size(width = 110.dp, height = 154.dp)
            .testTag("local_camera_preview_card")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (localVideoTrack != null && room != null) {
                LiveKitVideoRendererView(
                    room = room,
                    videoTrack = localVideoTrack,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            previewViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            IconButton(
                onClick = onFlipCamera,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Front/Rear Camera",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun IncomingCallAnswerDock(
    isVideoCall: Boolean,
    onAnswerAudio: () -> Unit,
    onAnswerVideo: () -> Unit,
    onDecline: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = ElevatedPineSurface.copy(alpha = 0.95f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundCallControlButton(
                icon = Icons.Default.CallEnd,
                contentDescription = "Decline Call",
                backgroundColor = CoralHangup,
                iconTint = Color.White,
                onClick = onDecline,
                testTag = "incoming_call_decline_btn"
            )

            RoundCallControlButton(
                icon = Icons.Default.Call,
                contentDescription = "Answer Audio",
                backgroundColor = EmeraldPrimaryDark,
                iconTint = Color(0xFF042F2E),
                onClick = onAnswerAudio,
                testTag = "incoming_call_answer_audio_btn"
            )

            if (isVideoCall) {
                RoundCallControlButton(
                    icon = Icons.Default.Videocam,
                    contentDescription = "Answer Video",
                    backgroundColor = EmeraldPrimaryDark,
                    iconTint = Color(0xFF042F2E),
                    onClick = onAnswerVideo,
                    testTag = "incoming_call_answer_video_btn"
                )
            }
        }
    }
}

@Composable
private fun ActiveCallControlDock(
    callState: ActiveCallUiState,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onOpenAudioPicker: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onOpenInCallChat: () -> Unit,
    onHangUp: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = ElevatedPineSurface.copy(alpha = 0.94f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundCallControlButton(
                icon = if (callState.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = if (callState.isMicEnabled) "Mute Microphone" else "Unmute Microphone",
                backgroundColor = if (callState.isMicEnabled) Color.White.copy(alpha = 0.14f)
                else Color.White,
                iconTint = if (callState.isMicEnabled) Color.White else DeepForestBg,
                onClick = onToggleMic,
                testTag = "call_toggle_mic_btn"
            )

            RoundCallControlButton(
                icon = if (callState.isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                contentDescription = if (callState.isCameraEnabled) "Turn Off Camera" else "Turn On Camera",
                backgroundColor = if (callState.isCameraEnabled) EmeraldPrimaryDark
                else Color.White.copy(alpha = 0.14f),
                iconTint = if (callState.isCameraEnabled) Color(0xFF042F2E) else Color.White,
                onClick = onToggleCamera,
                testTag = "call_toggle_camera_btn"
            )

            RoundCallControlButton(
                icon = when (callState.currentEndpoint.type) {
                    AudioEndpointType.BLUETOOTH -> Icons.Default.BluetoothAudio
                    AudioEndpointType.WIRED_HEADSET -> Icons.Default.Headset
                    AudioEndpointType.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
                    AudioEndpointType.EARPIECE -> Icons.Default.PhoneInTalk
                },
                contentDescription = "Select Audio Output (${callState.currentEndpoint.name})",
                backgroundColor = Color.White.copy(alpha = 0.14f),
                iconTint = Color.White,
                onClick = onOpenAudioPicker,
                testTag = "call_audio_route_picker_btn"
            )

            RoundCallControlButton(
                icon = if (callState.isScreenSharing) Icons.Default.StopScreenShare
                else Icons.Default.ScreenShare,
                contentDescription = if (callState.isScreenSharing) "Stop Screen Share" else "Share Screen",
                backgroundColor = if (callState.isScreenSharing) EmeraldPrimaryDark
                else Color.White.copy(alpha = 0.14f),
                iconTint = if (callState.isScreenSharing) Color(0xFF042F2E) else Color.White,
                onClick = onToggleScreenShare,
                testTag = "call_screen_share_btn"
            )

            RoundCallControlButton(
                icon = Icons.AutoMirrored.Filled.Chat,
                contentDescription = "Open In-Call Chat",
                backgroundColor = Color.White.copy(alpha = 0.14f),
                iconTint = Color.White,
                onClick = onOpenInCallChat,
                testTag = "call_open_chat_btn"
            )

            RoundCallControlButton(
                icon = Icons.Default.CallEnd,
                contentDescription = "Hang Up Call",
                backgroundColor = CoralHangup,
                iconTint = Color.White,
                onClick = onHangUp,
                testTag = "call_hang_up_btn"
            )
        }
    }
}

@Composable
private fun RoundCallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    backgroundColor: Color,
    iconTint: Color,
    onClick: () -> Unit,
    testTag: String,
    sizeDp: Int = 52
) {
    Surface(
        shape = CircleShape,
        color = backgroundColor,
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size((sizeDp * 0.46f).dp)
            )
        }
    }
}

@Composable
fun InAppMinimizedCallCard(
    callState: ActiveCallUiState,
    coordinator: CallCoordinator,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = DeepForestBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("in_app_minimized_call_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { coordinator.setMinimizedInApp(false) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = callState.peer.name.ifBlank { "Ongoing Call" },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatCallDuration(callState.connectedElapsedSeconds)} • Tap to expand",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = Color(0xFFA7F3D0)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundCallControlButton(
                    icon = if (callState.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = if (callState.isMicEnabled) "Mute Mic" else "Unmute Mic",
                    backgroundColor = if (callState.isMicEnabled) Color.White.copy(alpha = 0.16f)
                    else Color.White,
                    iconTint = if (callState.isMicEnabled) Color.White else DeepForestBg,
                    onClick = { coordinator.toggleMic() },
                    testTag = "mini_call_mic_btn",
                    sizeDp = 42
                )

                RoundCallControlButton(
                    icon = if (callState.isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    contentDescription = if (callState.isCameraEnabled) "Turn Off Camera" else "Turn On Camera",
                    backgroundColor = if (callState.isCameraEnabled) EmeraldPrimaryDark
                    else Color.White.copy(alpha = 0.16f),
                    iconTint = if (callState.isCameraEnabled) Color(0xFF042F2E) else Color.White,
                    onClick = { coordinator.toggleCamera() },
                    testTag = "mini_call_camera_btn",
                    sizeDp = 42
                )

                RoundCallControlButton(
                    icon = Icons.Default.Expand,
                    contentDescription = "Expand Call",
                    backgroundColor = Color.White.copy(alpha = 0.16f),
                    iconTint = Color.White,
                    onClick = { coordinator.setMinimizedInApp(false) },
                    testTag = "mini_call_expand_btn",
                    sizeDp = 42
                )

                RoundCallControlButton(
                    icon = Icons.Default.CallEnd,
                    contentDescription = "Hang Up Call",
                    backgroundColor = CoralHangup,
                    iconTint = Color.White,
                    onClick = { coordinator.declineOrHangUpCall() },
                    testTag = "mini_call_hangup_btn",
                    sizeDp = 42
                )
            }
        }
    }
}

@Composable
fun OsPipCallSurface(
    callState: ActiveCallUiState
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepForestBg)
            .testTag("os_pip_call_surface"),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = callState.peer.name.ifBlank { "Active Call" },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatCallDuration(callState.connectedElapsedSeconds),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = Color(0xFFA7F3D0)
            )
        }
    }
}

@Composable
private fun AudioEndpointPickerSheet(
    currentEndpoint: AudioEndpointItem,
    availableEndpoints: List<AudioEndpointItem>,
    onSelectEndpoint: (AudioEndpointItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Audio Output",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        availableEndpoints.forEach { endpoint ->
            val selected = endpoint.type == currentEndpoint.type
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (selected) EmeraldPrimaryDark.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.06f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectEndpoint(endpoint) }
                    .testTag("audio_endpoint_${endpoint.type.name.lowercase()}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (endpoint.type) {
                                AudioEndpointType.BLUETOOTH -> Icons.Default.BluetoothAudio
                                AudioEndpointType.WIRED_HEADSET -> Icons.Default.Headset
                                AudioEndpointType.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
                                AudioEndpointType.EARPIECE -> Icons.Default.PhoneInTalk
                            },
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = endpoint.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected route",
                            tint = EmeraldPrimaryDark
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun VideoQualityPickerSheet(
    currentPreset: VideoQualityPreset,
    onSelectPreset: (VideoQualityPreset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Video Quality",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        VideoQualityPreset.entries.forEach { preset ->
            val selected = preset == currentPreset
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (selected) EmeraldPrimaryDark.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.06f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectPreset(preset) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = preset.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected quality",
                            tint = EmeraldPrimaryDark
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun InCallChatSheetContent(
    conversationId: String,
    peerName: String,
    messages: List<ChatMessage>,
    draft: String,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .padding(16.dp)
    ) {
        Text(
            text = "In-Call Chat with $peerName",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(messages.takeLast(15), key = { it.id.ifBlank { it.clientMessageId } }) { msg ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg.text.ifBlank { "[${msg.kind.wireName}]" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        MessageTickIndicator(deliveryState = msg.deliveryState)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                placeholder = { Text("Message…") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("in_call_chat_input")
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = draft.isNotBlank() && conversationId.isNotBlank(),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(EmeraldPrimaryDark)
                    .testTag("in_call_chat_send_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send In-Call Message",
                    tint = Color(0xFF042F2E)
                )
            }
        }
    }
}

fun formatCallDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}
