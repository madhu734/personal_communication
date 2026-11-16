package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.call.MuteDurationPickerCard
import com.example.model.CallKind
import com.example.model.CallRecord
import com.example.model.CallRecordStatus
import com.example.model.ChatSummary
import com.example.model.MessageDeliveryState
import com.example.model.MessageKind
import com.example.model.MessageRequestItem
import com.example.model.MuteDurationOption
import com.example.model.PeerSummary
import com.example.repository.AppUiState
import com.example.repository.AppViewModel
import com.example.ui.theme.CoralHangup
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MutedTickGrayDark
import com.example.ui.theme.MutedTickGrayLight
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.SeenTickColorDark
import com.example.ui.theme.SeenTickColorLight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChatsListScreen(
    uiState: AppUiState,
    viewModel: AppViewModel
) {
    var muteTargetChat by remember { mutableStateOf<ChatSummary?>(null) }

    if (muteTargetChat != null) {
        val chat = muteTargetChat!!
        AlertDialog(
            onDismissRequest = { muteTargetChat = null },
            confirmButton = {},
            text = {
                MuteDurationPickerCard(
                    peerName = chat.peer.name,
                    onSelectOption = { option: MuteDurationOption ->
                        viewModel.muteConversation(chat.id, option)
                        muteTargetChat = null
                    },
                    onCancel = { muteTargetChat = null }
                )
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.chats.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No conversations yet",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Open the People tab to connect with friends by name or email.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("inbox_chats_list")
            ) {
                items(uiState.chats, key = { it.id }) { chat ->
                    ChatRowCard(
                        chat = chat,
                        ownUserId = uiState.currentUser?.id.orEmpty(),
                        viewModel = viewModel,
                        onOpenChat = { viewModel.openConversation(chat.id) },
                        onOpenMutePicker = {
                            if (chat.isMuted) {
                                viewModel.unmuteConversation(chat.id)
                            } else {
                                muteTargetChat = chat
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatRowCard(
    chat: ChatSummary,
    ownUserId: String,
    viewModel: AppViewModel,
    onOpenChat: () -> Unit,
    onOpenMutePicker: () -> Unit
) {
    val lastMsg = chat.lastMessage
    val isOwnLastMessage = lastMsg != null && lastMsg.sender == ownUserId
    val snippetText = when {
        chat.peerTyping -> "typing…"
        lastMsg == null -> "Tap to start chatting"
        lastMsg.isDeleted -> "This message was deleted."
        lastMsg.kind == MessageKind.IMAGE -> "Photo" + (if (lastMsg.text.isNotBlank()) ": ${lastMsg.text}" else "")
        lastMsg.kind == MessageKind.VOICE -> lastMsg.text.ifBlank { "Voice note" }
        lastMsg.kind == MessageKind.VIDEO -> "Video" + (if (lastMsg.text.isNotBlank()) ": ${lastMsg.text}" else "")
        else -> lastMsg.text
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenChat)
            .testTag("chat_item_${chat.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                ProtectedAvatarImage(
                    apiClient = viewModel.apiClient,
                    userId = chat.peer.id,
                    avatarFile = chat.peer.avatar,
                    fallbackName = chat.peer.name,
                    sizeDp = 52
                )
                if (chat.peerOnline) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(OnlineGreen)
                            .align(Alignment.BottomEnd)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
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
                                contentDescription = "Conversation Muted",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (lastMsg != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formatShortTimestamp(lastMsg.created),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isOwnLastMessage) {
                                Spacer(modifier = Modifier.width(4.dp))
                                MessageTickIndicator(
                                    deliveryState = lastMsg.deliveryState,
                                    testTagPrefix = "inbox_tick_${chat.id}"
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = snippetText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = if (chat.peerTyping || lastMsg?.isDeleted == true) FontStyle.Italic else FontStyle.Normal,
                        fontWeight = if (chat.peerTyping) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (chat.peerTyping) OnlineGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (chat.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Text(
                                text = chat.unreadCount.toString(),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onOpenMutePicker,
                modifier = Modifier.testTag("chat_mute_toggle_${chat.id}")
            ) {
                Icon(
                    imageVector = if (chat.isMuted) Icons.Default.NotificationsOff
                    else Icons.Outlined.Notifications,
                    contentDescription = if (chat.isMuted) "Unmute chat" else "Mute chat",
                    tint = if (chat.isMuted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Renders the unified message tick indicator right beside the timestamp:
 * - SENDING / FAILED: Clock icon
 * - SENT: Single gray tick (`✓`)
 * - DELIVERED: Double gray ticks (`✓✓`)
 * - SEEN: Double colored ticks (`✓✓` in emerald/teal)
 */
@Composable
fun MessageTickIndicator(
    deliveryState: MessageDeliveryState,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "msg_tick"
) {
    val isDark = isSystemInDarkTheme()
    val grayColor = if (isDark) MutedTickGrayDark else MutedTickGrayLight
    val seenColor = if (isDark) SeenTickColorDark else SeenTickColorLight

    when (deliveryState) {
        MessageDeliveryState.SENDING, MessageDeliveryState.FAILED -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = if (deliveryState == MessageDeliveryState.FAILED) "Send failed" else "Sending",
                tint = if (deliveryState == MessageDeliveryState.FAILED) CoralHangup else grayColor,
                modifier = modifier
                    .size(13.dp)
                    .testTag("${testTagPrefix}_${deliveryState.name.lowercase()}")
            )
        }

        MessageDeliveryState.SENT -> {
            Text(
                text = "✓",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = grayColor,
                modifier = modifier.testTag("${testTagPrefix}_sent")
            )
        }

        MessageDeliveryState.DELIVERED -> {
            Text(
                text = "✓✓",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = grayColor,
                letterSpacing = (-1.5).sp,
                modifier = modifier.testTag("${testTagPrefix}_delivered")
            )
        }

        MessageDeliveryState.SEEN -> {
            Text(
                text = "✓✓",
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                color = seenColor,
                letterSpacing = (-1.5).sp,
                modifier = modifier.testTag("${testTagPrefix}_seen")
            )
        }
    }
}

@Composable
fun RecentCallsScreen(
    uiState: AppUiState,
    viewModel: AppViewModel
) {
    val ownUserId = uiState.currentUser?.id.orEmpty()
    if (uiState.recentCalls.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No recent calls yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("recent_calls_list")
    ) {
        items(uiState.recentCalls, key = { it.id }) { callRec ->
            RecentCallRow(
                call = callRec,
                ownUserId = ownUserId,
                chats = uiState.chats,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun RecentCallRow(
    call: CallRecord,
    ownUserId: String,
    chats: List<ChatSummary>,
    viewModel: AppViewModel
) {
    val isOutgoing = call.caller == ownUserId
    val peerId = if (isOutgoing) call.receiver else call.caller
    val peer = chats.firstOrNull { it.id == call.conversation || it.peer.id == peerId }?.peer
        ?: PeerSummary(id = peerId, name = "Contact")
    val isMissedOrRejected = call.status in setOf(
        CallRecordStatus.MISSED,
        CallRecordStatus.REJECTED,
        CallRecordStatus.FAILED
    )

    val context = LocalContext.current
    var pendingCallbackKind by remember { mutableStateOf<CallKind?>(null) }
    val callbackPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val kindToStart = pendingCallbackKind
        pendingCallbackKind = null
        if (kindToStart != null) {
            viewModel.callCoordinator.startOutgoingCall(
                conversationId = call.conversation,
                peer = peer,
                kind = kindToStart
            )
        }
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProtectedAvatarImage(
                apiClient = viewModel.apiClient,
                userId = peer.id,
                avatarFile = peer.avatar,
                fallbackName = peer.name,
                sizeDp = 46
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isMissedOrRejected && !isOutgoing) CoralHangup
                    else MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            isMissedOrRejected && !isOutgoing -> Icons.AutoMirrored.Filled.CallMissed
                            isOutgoing -> Icons.AutoMirrored.Filled.CallMade
                            else -> Icons.AutoMirrored.Filled.CallReceived
                        },
                        contentDescription = null,
                        tint = if (isMissedOrRejected && !isOutgoing) CoralHangup
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${call.kind.wireName.replaceFirstChar { it.uppercase() }} • ${call.status.wireName} • ${formatShortTimestamp(call.created)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = {
                    val hasAudio = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasAudio) {
                        viewModel.callCoordinator.startOutgoingCall(
                            conversationId = call.conversation,
                            peer = peer,
                            kind = CallKind.AUDIO
                        )
                    } else {
                        pendingCallbackKind = CallKind.AUDIO
                        callbackPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                },
                modifier = Modifier.testTag("callback_audio_${call.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Audio Callback",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(
                onClick = {
                    val hasAudio = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasCam = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasAudio && hasCam) {
                        viewModel.callCoordinator.startOutgoingCall(
                            conversationId = call.conversation,
                            peer = peer,
                            kind = CallKind.VIDEO
                        )
                    } else {
                        pendingCallbackKind = CallKind.VIDEO
                        callbackPermissionsLauncher.launch(
                            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                        )
                    }
                },
                modifier = Modifier.testTag("callback_video_${call.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Video Callback",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun PeopleAndRequestsScreen(
    uiState: AppUiState,
    viewModel: AppViewModel
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var requestNotePeer by remember { mutableStateOf<PeerSummary?>(null) }
    var requestNoteText by rememberSaveable { mutableStateOf("") }

    if (requestNotePeer != null) {
        val target = requestNotePeer!!
        AlertDialog(
            onDismissRequest = { requestNotePeer = null },
            title = { Text("Connect with ${target.name}") },
            text = {
                OutlinedTextField(
                    value = requestNoteText,
                    onValueChange = { requestNoteText = it.take(500) },
                    label = { Text("Optional message") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.sendConnectionRequest(target, requestNoteText)
                        requestNoteText = ""
                        requestNotePeer = null
                    }
                ) {
                    Text("Send Request")
                }
            },
            dismissButton = {
                TextButton(onClick = { requestNotePeer = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    viewModel.searchMembers(it)
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("Search by name or email") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("member_search_input")
            )
        }

        if (uiState.searchResults.isNotEmpty()) {
            item {
                Text(
                    text = "Directory Matches",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(uiState.searchResults, key = { it.id }) { peer ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProtectedAvatarImage(
                            apiClient = viewModel.apiClient,
                            userId = peer.id,
                            avatarFile = peer.avatar,
                            fallbackName = peer.name,
                            sizeDp = 44
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = peer.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { requestNotePeer = peer }) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Request")
                        }
                    }
                }
            }
        }

        val incomingReqs = uiState.requests.filter { it.incoming }
        val outgoingReqs = uiState.requests.filter { !it.incoming }

        item {
            Text(
                text = "Incoming Requests (${incomingReqs.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (incomingReqs.isEmpty()) {
            item {
                Text(
                    text = "No pending incoming requests.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(incomingReqs, key = { it.id }) { req ->
                RequestItemCard(
                    request = req,
                    viewModel = viewModel,
                    onAccept = { viewModel.acceptRequest(req) },
                    onDeclineOrCancel = { viewModel.declineOrCancelRequest(req) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Sent Requests (${outgoingReqs.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (outgoingReqs.isEmpty()) {
            item {
                Text(
                    text = "No pending sent requests.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(outgoingReqs, key = { it.id }) { req ->
                RequestItemCard(
                    request = req,
                    viewModel = viewModel,
                    onAccept = {},
                    onDeclineOrCancel = { viewModel.declineOrCancelRequest(req) }
                )
            }
        }
    }
}

@Composable
private fun RequestItemCard(
    request: MessageRequestItem,
    viewModel: AppViewModel,
    onAccept: () -> Unit,
    onDeclineOrCancel: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProtectedAvatarImage(
                    apiClient = viewModel.apiClient,
                    userId = request.peer.id,
                    avatarFile = request.peer.avatar,
                    fallbackName = request.peer.name,
                    sizeDp = 44
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = request.peer.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (request.incoming) "Wants to connect with you" else "Outgoing request pending",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (request.note.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "“${request.note}”",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (request.incoming) {
                    Button(
                        onClick = onAccept,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("accept_request_${request.id}")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Accept")
                    }
                }
                OutlinedButton(
                    onClick = onDeclineOrCancel,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("decline_request_${request.id}")
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (request.incoming) "Decline" else "Cancel Request")
                }
            }
        }
    }
}

fun formatShortTimestamp(rawIso: String): String {
    if (rawIso.isBlank()) return ""
    return try {
        val normalized = rawIso.trim().replace(' ', 'T')
        val instant = Instant.parse(if (normalized.endsWith("Z")) normalized else "${normalized}Z")
        val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (_: Exception) {
        rawIso.takeLast(8).take(5)
    }
}
