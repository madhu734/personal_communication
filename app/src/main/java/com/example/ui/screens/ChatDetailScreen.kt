package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.model.CallKind
import com.example.model.ChatMessage
import com.example.model.ChatSummary
import com.example.model.MessageDeliveryState
import com.example.model.MessageKind
import com.example.model.MessageReaction
import com.example.model.MuteDurationOption
import com.example.model.PeerSummary
import com.example.model.WatermarkCursorUtils
import com.example.network.PocketBaseApiClient
import com.example.repository.AppUiState
import com.example.repository.AppViewModel
import com.example.ui.theme.CoralHangup
import com.example.ui.theme.OnlineGreen
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    conversationId: String,
    uiState: AppUiState,
    viewModel: AppViewModel,
    showBackButton: Boolean = true
) {
    BackHandler(enabled = showBackButton) {
        viewModel.openConversation(null)
    }

    val context = LocalContext.current
    val chat = uiState.chats.firstOrNull { it.id == conversationId }
        ?: ChatSummary(id = conversationId, peer = PeerSummary("", "Contact"))
    val messages = uiState.messagesByConversation[conversationId].orEmpty()
    val reactions = uiState.reactionsByConversation[conversationId].orEmpty()
    val ownUserId = uiState.currentUser?.id.orEmpty()

    var composerText by rememberSaveable(conversationId) { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var selectedMessageForActions by remember { mutableStateOf<ChatMessage?>(null) }
    var customEmojiTargetMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var customEmojiInput by rememberSaveable { mutableStateOf("") }

    val listState = rememberLazyListState()

    LaunchedEffect(uiState.editingMessage) {
        val editing = uiState.editingMessage
        if (editing != null && editing.conversation == conversationId) {
            composerText = editing.text
        }
    }

    LaunchedEffect(messages.size, chat.peerTyping) {
        val totalItems = messages.size + (if (chat.peerTyping) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.sendMediaAttachment(
                conversationId = conversationId,
                uri = uri,
                caption = composerText
            )
            composerText = ""
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.startVoiceNoteRecording()
    }

    if (selectedMessageForActions != null) {
        val target = selectedMessageForActions!!
        ModalBottomSheet(
            onDismissRequest = { selectedMessageForActions = null }
        ) {
            MessageActionAndReactionSheet(
                message = target,
                isOwnMessage = target.sender == ownUserId,
                onSelectReaction = { emoji ->
                    viewModel.toggleOrSetReaction(conversationId, target.id, emoji)
                    selectedMessageForActions = null
                },
                onOpenCustomEmoji = {
                    customEmojiTargetMessage = target
                    selectedMessageForActions = null
                },
                onReply = {
                    viewModel.setReplyTarget(target)
                    selectedMessageForActions = null
                },
                onEdit = {
                    viewModel.setEditingMessage(target)
                    selectedMessageForActions = null
                }
            )
        }
    }

    if (customEmojiTargetMessage != null) {
        val target = customEmojiTargetMessage!!
        AlertDialog(
            onDismissRequest = { customEmojiTargetMessage = null },
            title = { Text("Custom Emoji Reaction") },
            text = {
                OutlinedTextField(
                    value = customEmojiInput,
                    onValueChange = { customEmojiInput = it.take(8) },
                    label = { Text("Type any emoji") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customEmojiInput.isNotBlank()) {
                            viewModel.toggleOrSetReaction(
                                conversationId,
                                target.id,
                                customEmojiInput.trim()
                            )
                        }
                        customEmojiInput = ""
                        customEmojiTargetMessage = null
                    }
                ) {
                    Text("React")
                }
            },
            dismissButton = {
                TextButton(onClick = { customEmojiTargetMessage = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .navigationBarsPadding()
    ) {
        // Chat Header Bar
        TopAppBar(
            windowInsets = WindowInsets.statusBars,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            navigationIcon = {
                if (showBackButton) {
                    IconButton(
                        onClick = { viewModel.openConversation(null) },
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtectedAvatarImage(
                        apiClient = viewModel.apiClient,
                        userId = chat.peer.id,
                        avatarFile = chat.peer.avatar,
                        fallbackName = chat.peer.name,
                        sizeDp = 40
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = chat.peer.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (chat.isMuted) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.NotificationsOff,
                                    contentDescription = "Muted",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        val statusLine = when {
                            chat.peerTyping -> "typing…"
                            chat.peerOnline -> "Online"
                            chat.isMuted -> "Notifications muted"
                            else -> ""
                        }
                        if (statusLine.isNotEmpty()) {
                            Text(
                                text = statusLine,
                                style = MaterialTheme.typography.labelSmall,
                                fontStyle = if (chat.peerTyping) FontStyle.Italic else FontStyle.Normal,
                                color = if (chat.peerTyping || chat.peerOnline) OnlineGreen
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        viewModel.callCoordinator.startOutgoingCall(
                            conversationId = conversationId,
                            peer = chat.peer,
                            kind = CallKind.AUDIO
                        )
                    },
                    modifier = Modifier.testTag("start_audio_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Audio Call",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = {
                        viewModel.callCoordinator.startOutgoingCall(
                            conversationId = conversationId,
                            peer = chat.peer,
                            kind = CallKind.VIDEO
                        )
                    },
                    modifier = Modifier.testTag("start_video_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Video Call",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Box {
                    IconButton(
                        onClick = { showOverflowMenu = true },
                        modifier = Modifier.testTag("chat_overflow_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Chat Options"
                        )
                    }

                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        if (chat.isMuted) {
                            DropdownMenuItem(
                                text = { Text("Unmute Notifications") },
                                onClick = {
                                    viewModel.unmuteConversation(conversationId)
                                    showOverflowMenu = false
                                },
                                modifier = Modifier.testTag("menu_unmute_chat")
                            )
                        }
                        MuteDurationOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text("Mute: ${option.displayLabel}") },
                                onClick = {
                                    viewModel.muteConversation(conversationId, option)
                                    showOverflowMenu = false
                                },
                                modifier = Modifier.testTag("menu_mute_${option.key}")
                            )
                        }
                    }
                }
            }
        )

        // Messages List
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("chat_messages_list")
        ) {
            if (messages.size >= 30) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = { viewModel.loadOlderMessages(conversationId) }) {
                            Text("Load earlier messages")
                        }
                    }
                }
            }

            items(messages, key = { it.id.ifBlank { it.clientMessageId } }) { msg ->
                val isOwn = msg.sender == ownUserId
                val msgReactions = reactions.filter { it.message == msg.id }
                val repliedMsg = msg.replyTo.takeIf { it.isNotBlank() }?.let { replyId ->
                    messages.firstOrNull { it.id == replyId }
                }

                MessageBubbleItem(
                    message = msg,
                    isOwnMessage = isOwn,
                    repliedMessage = repliedMsg,
                    reactions = msgReactions,
                    ownUserId = ownUserId,
                    isPlayingAudio = uiState.playingAudioMessageId == msg.id,
                    apiClient = viewModel.apiClient,
                    onLongPressOrAction = { selectedMessageForActions = msg },
                    onToggleReaction = { emoji ->
                        viewModel.toggleOrSetReaction(conversationId, msg.id, emoji)
                    },
                    onPlayVoiceNote = { viewModel.togglePlayVoiceMessage(msg) },
                    onRetryFailed = { viewModel.retryFailedMessage(msg) }
                )
            }

            if (chat.peerTyping) {
                item(key = "peer_typing_indicator") {
                    AnimatedTypingIndicatorBubble(peerName = chat.peer.name)
                }
            }
        }

        // Reply or Edit Context Banner
        val replyTarget = uiState.replyTargetMessage
        val editingTarget = uiState.editingMessage
        if (replyTarget != null || editingTarget != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (editingTarget != null) Icons.Default.Edit
                        else Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (editingTarget != null) "Editing message"
                            else "Replying to message",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = (editingTarget?.text ?: replyTarget?.text).orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.setReplyTarget(null)
                            viewModel.setEditingMessage(null)
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            }
        }

        // Flush, Compact Message Composer Bar (zero extra gap above IME keyboard)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isRecordingVoiceNote) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(CoralHangup)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Recording (${uiState.recordingElapsedSeconds}s)…",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                viewModel.finishVoiceNoteRecording(conversationId, cancel = true)
                            }
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                viewModel.finishVoiceNoteRecording(conversationId, cancel = false)
                            },
                            modifier = Modifier.testTag("send_voice_note_button")
                        ) {
                            Text("Send")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            mediaPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        modifier = Modifier.testTag("attach_media_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attach photo or video",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    OutlinedTextField(
                        value = composerText,
                        onValueChange = {
                            composerText = it.take(10_000)
                            viewModel.notifyTypingInOpenConversation(it.isNotBlank())
                        },
                        placeholder = { Text("Message") },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ),
                        maxLines = 4,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_composer_input")
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    if (composerText.isBlank() && uiState.editingMessage == null) {
                        IconButton(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    viewModel.startVoiceNoteRecording()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.testTag("record_voice_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Record Voice Note",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                viewModel.sendOrEditTextMessage(
                                    conversationId = conversationId,
                                    text = composerText,
                                    inCall = false
                                )
                                composerText = ""
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("send_message_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Message",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedTypingIndicatorBubble(peerName: String) {
    val transition = rememberInfiniteTransition(label = "typing_dots")
    val dot1Alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, delayMillis = 160),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, delayMillis = 320),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag("typing_indicator_bubble"),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(dot1Alpha)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(dot2Alpha)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(dot3Alpha)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${peerName.split(" ").firstOrNull().orEmpty()} is typing",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageBubbleItem(
    message: ChatMessage,
    isOwnMessage: Boolean,
    repliedMessage: ChatMessage?,
    reactions: List<MessageReaction>,
    ownUserId: String,
    isPlayingAudio: Boolean,
    apiClient: PocketBaseApiClient,
    onLongPressOrAction: () -> Unit,
    onToggleReaction: (String) -> Unit,
    onPlayVoiceNote: () -> Unit,
    onRetryFailed: () -> Unit
) {
    val bubbleColor = if (isOwnMessage) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (isOwnMessage) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("message_bubble_${message.id}"),
        horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isOwnMessage) 18.dp else 4.dp,
                bottomEnd = if (isOwnMessage) 4.dp else 18.dp
            ),
            color = bubbleColor,
            tonalElevation = 2.dp,
            modifier = Modifier
                .widthIn(min = 90.dp, max = 310.dp)
                .combinedClickable(
                    onClick = { onLongPressOrAction() },
                    onLongClick = { onLongPressOrAction() }
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (repliedMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = "↳ ${repliedMessage.text.ifBlank { "[${repliedMessage.kind.wireName}]" }}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                if (message.kind == MessageKind.IMAGE || message.kind == MessageKind.VIDEO) {
                    ProtectedMessageMediaPreview(
                        apiClient = apiClient,
                        message = message
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (message.kind == MessageKind.VOICE) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onPlayVoiceNote)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isPlayingAudio) Icons.Default.Stop
                                else Icons.Default.PlayArrow,
                                contentDescription = if (isPlayingAudio) "Stop voice note" else "Play voice note",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = message.text.ifBlank { "Voice Note" },
                                style = MaterialTheme.typography.labelLarge,
                                color = textColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (message.text.isNotBlank() && message.kind != MessageKind.VOICE) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    if (message.inCall) {
                        Text(
                            text = "in-call • ",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                    if (message.isEdited) {
                        Text(
                            text = "edited • ",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        text = formatShortTimestamp(message.created),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.75f)
                    )
                    if (isOwnMessage) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageTickIndicator(
                            deliveryState = message.deliveryState,
                            testTagPrefix = "bubble_tick_${message.id}"
                        )
                    }
                }

                if (isOwnMessage && message.deliveryState == MessageDeliveryState.FAILED) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = onRetryFailed,
                        modifier = Modifier
                            .align(Alignment.End)
                            .testTag("retry_message_${message.clientMessageId}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry send",
                            tint = CoralHangup,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry", color = CoralHangup)
                    }
                }
            }
        }

        if (reactions.isNotEmpty()) {
            val grouped = reactions.groupBy { it.emoji }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 3.dp)
            ) {
                grouped.forEach { (emoji, list) ->
                    val ownReacted = list.any { it.user == ownUserId }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (ownReacted) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { onToggleReaction(emoji) }
                    ) {
                        Text(
                            text = "$emoji ${list.size}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtectedMessageMediaPreview(
    apiClient: PocketBaseApiClient,
    message: ChatMessage
) {
    var mediaUrl by remember(message.id, message.attachment, message.localAttachmentUri) {
        mutableStateOf("")
    }
    var retriedWithFreshToken by remember(message.id, message.attachment) {
        mutableStateOf(false)
    }

    LaunchedEffect(message.id, message.attachment, message.localAttachmentUri, retriedWithFreshToken) {
        if (message.localAttachmentUri.isNotBlank() &&
            (message.localAttachmentUri.startsWith("content://") || File(message.localAttachmentUri).exists())
        ) {
            mediaUrl = message.localAttachmentUri
        } else if (message.attachment.isNotBlank() && !message.id.startsWith("local_")) {
            mediaUrl = apiClient.buildProtectedFileUrl(
                collection = "messages",
                recordId = message.id,
                filename = message.attachment,
                thumb = if (message.kind == MessageKind.IMAGE) "400x0" else null,
                forceRefreshToken = retriedWithFreshToken
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
    ) {
        if (mediaUrl.isNotBlank() && message.kind == MessageKind.IMAGE) {
            AsyncImage(
                model = mediaUrl,
                contentDescription = message.text.ifBlank { "Shared image" },
                contentScale = ContentScale.Crop,
                onError = {
                    if (!retriedWithFreshToken) {
                        retriedWithFreshToken = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.attachment.ifBlank { message.kind.wireName.uppercase() },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageActionAndReactionSheet(
    message: ChatMessage,
    isOwnMessage: Boolean,
    onSelectReaction: (String) -> Unit,
    onOpenCustomEmoji: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            WatermarkCursorUtils.COMMON_REACTIONS.forEach { emoji ->
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(42.dp)
                        .clickable { onSelectReaction(emoji) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = emoji, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            IconButton(onClick = onOpenCustomEmoji) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEmotions,
                    contentDescription = "Custom Emoji"
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onReply,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reply")
            }
            if (isOwnMessage && message.kind == MessageKind.TEXT) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Edit Message")
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
