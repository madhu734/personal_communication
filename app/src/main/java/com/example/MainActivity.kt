package com.example

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.call.SystemNotificationAndFcmManager
import com.example.model.CallKind
import com.example.model.CallPhase
import com.example.repository.AppUiState
import com.example.repository.AppViewModel
import com.example.repository.TopLevelTab
import com.example.ui.screens.AccountAndDiagnosticsScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatsListScreen
import com.example.ui.screens.FullScreenCallOverlay
import com.example.ui.screens.InAppMinimizedCallCard
import com.example.ui.screens.OsPipCallSurface
import com.example.ui.screens.PeopleAndRequestsScreen
import com.example.ui.screens.RecentCallsScreen
import com.example.ui.screens.SetBackupPinDialog
import com.example.ui.screens.promptDeviceBiometricAuth
import com.example.ui.theme.ApunKaTheme
import com.example.ui.theme.CoralHangup

class MainActivity : FragmentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            ApunKaTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val callState by viewModel.callCoordinator.uiState.collectAsStateWithLifecycle()

                // Request POST_NOTIFICATIONS on Android 13+ when signed in
                val context = LocalContext.current
                val notifPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ -> }

                LaunchedEffect(uiState.currentUser) {
                    if (uiState.currentUser != null &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // Update Android 12+ auto-enter PiP eligibility whenever video call state changes
                LaunchedEffect(callState.phase, callState.kind, callState.isCameraEnabled) {
                    updatePipParams()
                }

                // If the Activity is currently in OS Picture-in-Picture mode, show only the clean remote video surface
                if (callState.isInPipMode && callState.phase != CallPhase.IDLE) {
                    OsPipCallSurface(callState = callState)
                } else {
                    ApunKaRootContent(
                        uiState = uiState,
                        callState = callState,
                        viewModel = viewModel,
                        onRequestEnterOsPip = { enterOsPipIfEligible() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkBiometricLockStateOnResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val convId = intent?.getStringExtra(SystemNotificationAndFcmManager.EXTRA_CONVERSATION_ID)
        if (!convId.isNullOrBlank()) {
            viewModel.openConversation(convId)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val callState = viewModel.callCoordinator.uiState.value
        val eligibleForVideoPip = callState.phase == CallPhase.ACTIVE &&
            (callState.kind == CallKind.VIDEO || callState.isCameraEnabled)
        if (eligibleForVideoPip) {
            enterOsPipIfEligible()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.callCoordinator.setPipMode(isInPictureInPictureMode)
    }

    private fun updatePipParams(): PictureInPictureParams? {
        val callState = viewModel.callCoordinator.uiState.value
        val eligible = callState.phase == CallPhase.ACTIVE &&
            (callState.kind == CallKind.VIDEO || callState.isCameraEnabled)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sourceBounds = android.graphics.Rect()
            window.decorView.getGlobalVisibleRect(sourceBounds)
            if (!sourceBounds.isEmpty) {
                builder.setSourceRectHint(sourceBounds)
            }
            builder.setAutoEnterEnabled(eligible)
        }
        val params = builder.build()
        try {
            setPictureInPictureParams(params)
        } catch (_: Exception) {
        }
        return params
    }

    private fun enterOsPipIfEligible() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val params = updatePipParams() ?: return
        try {
            enterPictureInPictureMode(params)
        } catch (_: Exception) {
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApunKaRootContent(
    uiState: AppUiState,
    callState: com.example.model.ActiveCallUiState,
    viewModel: AppViewModel,
    onRequestEnterOsPip: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedScreen = maxWidth >= 720.dp

        if (uiState.currentUser == null) {
            Column(modifier = Modifier.fillMaxSize()) {
                FeedbackBannerOverlay(uiState = uiState, onDismiss = { viewModel.clearBannerAndError() })
                AuthScreen(uiState = uiState, viewModel = viewModel)
            }
        } else if (uiState.isBiometricLockedNow && callState.phase == CallPhase.IDLE) {
            BiometricAppLockOverlay(
                uiState = uiState,
                onBiometricUnlocked = { viewModel.onBiometricUnlockSucceeded() },
                onVerifyBackupPin = { pin -> viewModel.unlockWithBackupPin(pin) },
                onSetMandatoryBackupPin = { pin, confirmPin ->
                    viewModel.completeBiometricAndBackupPinSetup(pin, confirmPin)
                },
                onTurnOffAppLock = { viewModel.setBiometricAppLockEnabled(false) },
                onClearPinError = { viewModel.clearPinError() },
                onForgotPinSignOut = { viewModel.recoverForgottenPinBySigningOut() }
            )
        } else {
            if (uiState.needsBackupPinSetup) {
                SetBackupPinDialog(
                    isMandatoryForExistingBiometricUser = true,
                    errorMessage = uiState.pinErrorMessage,
                    onDismiss = { viewModel.setBiometricAppLockEnabled(false) },
                    onConfirmPinSetup = { pin, confirmPin ->
                        viewModel.completeBiometricAndBackupPinSetup(pin, confirmPin)
                    }
                )
            }
            val totalUnread = uiState.chats.sumOf { it.unreadCount }
            val incomingReqsCount = uiState.requests.count { it.incoming }

            // On phone when a chat is open, render ChatDetailScreen directly without outer Scaffold
            // so keyboard IME insets and navigation bar insets have zero duplicate gap.
            if (!isExpandedScreen && uiState.selectedConversationId != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    FeedbackBannerOverlay(
                        uiState = uiState,
                        onDismiss = { viewModel.clearBannerAndError() },
                        onRetry = if (uiState.retryableAction != null) {
                            { viewModel.retryLastFailedAction() }
                        } else {
                            null
                        }
                    )
                    if (callState.phase != CallPhase.IDLE && callState.isMinimizedInApp) {
                        InAppMinimizedCallCard(
                            callState = callState,
                            coordinator = viewModel.callCoordinator
                        )
                    }
                    ChatDetailScreen(
                        conversationId = uiState.selectedConversationId,
                        uiState = uiState,
                        viewModel = viewModel,
                        showBackButton = true
                    )
                }
            } else {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            title = {
                                Text(
                                    text = "Apun Ka Communication",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            actions = {
                                IconButton(
                                    onClick = { viewModel.refreshAllAuthenticatedData() },
                                    modifier = Modifier.testTag("sync_inbox_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh"
                                    )
                                }
                            }
                        )
                    },
                    bottomBar = {
                        if (!isExpandedScreen) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = uiState.activeTab == TopLevelTab.CHATS,
                                    onClick = { viewModel.selectTab(TopLevelTab.CHATS) },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (totalUnread > 0) {
                                                    Badge { Text(totalUnread.toString()) }
                                                }
                                            }
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chats")
                                        }
                                    },
                                    label = { Text("Chats") },
                                    modifier = Modifier.testTag("nav_tab_chats")
                                )

                                NavigationBarItem(
                                    selected = uiState.activeTab == TopLevelTab.CALLS,
                                    onClick = { viewModel.selectTab(TopLevelTab.CALLS) },
                                    icon = { Icon(Icons.Default.Call, contentDescription = "Calls") },
                                    label = { Text("Calls") },
                                    modifier = Modifier.testTag("nav_tab_calls")
                                )

                                NavigationBarItem(
                                    selected = uiState.activeTab == TopLevelTab.REQUESTS,
                                    onClick = { viewModel.selectTab(TopLevelTab.REQUESTS) },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (incomingReqsCount > 0) {
                                                    Badge { Text(incomingReqsCount.toString()) }
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Group, contentDescription = "People")
                                        }
                                    },
                                    label = { Text("People") },
                                    modifier = Modifier.testTag("nav_tab_requests")
                                )

                                NavigationBarItem(
                                    selected = uiState.activeTab == TopLevelTab.ACCOUNT,
                                    onClick = { viewModel.selectTab(TopLevelTab.ACCOUNT) },
                                    icon = { Icon(Icons.Default.Person, contentDescription = "Settings") },
                                    label = { Text("Settings") },
                                    modifier = Modifier.testTag("nav_tab_account")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        FeedbackBannerOverlay(
                            uiState = uiState,
                            onDismiss = { viewModel.clearBannerAndError() },
                            onRetry = if (uiState.retryableAction != null) {
                                { viewModel.retryLastFailedAction() }
                            } else {
                                null
                            }
                        )

                        if (callState.phase != CallPhase.IDLE && callState.isMinimizedInApp) {
                            InAppMinimizedCallCard(
                                callState = callState,
                                coordinator = viewModel.callCoordinator
                            )
                        }

                        if (isExpandedScreen) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                NavigationRail {
                                    NavigationRailItem(
                                        selected = uiState.activeTab == TopLevelTab.CHATS,
                                        onClick = { viewModel.selectTab(TopLevelTab.CHATS) },
                                        icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chats") },
                                        label = { Text("Chats") }
                                    )
                                    NavigationRailItem(
                                        selected = uiState.activeTab == TopLevelTab.CALLS,
                                        onClick = { viewModel.selectTab(TopLevelTab.CALLS) },
                                        icon = { Icon(Icons.Default.Call, contentDescription = "Calls") },
                                        label = { Text("Calls") }
                                    )
                                    NavigationRailItem(
                                        selected = uiState.activeTab == TopLevelTab.REQUESTS,
                                        onClick = { viewModel.selectTab(TopLevelTab.REQUESTS) },
                                        icon = { Icon(Icons.Default.Group, contentDescription = "People") },
                                        label = { Text("People") }
                                    )
                                    NavigationRailItem(
                                        selected = uiState.activeTab == TopLevelTab.ACCOUNT,
                                        onClick = { viewModel.selectTab(TopLevelTab.ACCOUNT) },
                                        icon = { Icon(Icons.Default.Person, contentDescription = "Settings") },
                                        label = { Text("Settings") }
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .width(340.dp)
                                        .fillMaxHeight()
                                ) {
                                    ActiveTabPane(uiState = uiState, viewModel = viewModel)
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    val openConvId = uiState.selectedConversationId
                                        ?: uiState.chats.firstOrNull()?.id
                                    if (openConvId != null && uiState.activeTab == TopLevelTab.CHATS) {
                                        ChatDetailScreen(
                                            conversationId = openConvId,
                                            uiState = uiState,
                                            viewModel = viewModel,
                                            showBackButton = false
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Select a conversation to start messaging",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            ActiveTabPane(uiState = uiState, viewModel = viewModel)
                        }
                    }
                }
            }
        }

        // Full-Screen Call Overlay when a call is active/ringing and NOT minimized in-app
        if (callState.phase != CallPhase.IDLE && !callState.isMinimizedInApp) {
            val inCallMsgs = uiState.messagesByConversation[callState.conversationId].orEmpty()
            FullScreenCallOverlay(
                callState = callState,
                inCallMessages = inCallMsgs,
                viewModel = viewModel,
                onRequestEnterOsPip = onRequestEnterOsPip
            )
        }
    }
}

@Composable
private fun BiometricAppLockOverlay(
    uiState: AppUiState,
    onBiometricUnlocked: () -> Unit,
    onVerifyBackupPin: (String) -> Unit,
    onSetMandatoryBackupPin: (String, String) -> Unit,
    onTurnOffAppLock: () -> Unit,
    onClearPinError: () -> Unit,
    onForgotPinSignOut: () -> Unit
) {
    val context = LocalContext.current
    var usePinMode by rememberSaveable { mutableStateOf(false) }
    var pinInput by rememberSaveable { mutableStateOf("") }

    if (uiState.needsBackupPinSetup && !uiState.hasBackupPin) {
        SetBackupPinDialog(
            isMandatoryForExistingBiometricUser = true,
            errorMessage = uiState.pinErrorMessage,
            onDismiss = onTurnOffAppLock,
            onConfirmPinSetup = onSetMandatoryBackupPin
        )
    }

    LaunchedEffect(Unit) {
        if (!usePinMode) {
            promptDeviceBiometricAuth(
                context = context,
                title = "Apun Ka Communication Locked",
                subtitle = "Unlock with fingerprint, face, or screen lock",
                onSuccess = onBiometricUnlocked,
                onFallbackNoHardware = {
                    if (uiState.hasBackupPin) {
                        usePinMode = true
                    }
                }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .testTag("biometric_lock_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (usePinMode) Icons.Default.Lock else Icons.Default.Fingerprint,
                        contentDescription = if (usePinMode) "PIN Lock" else "Biometric Lock",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            Text(
                text = "Apun Ka Communication Locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            if (usePinMode) {
                Text(
                    text = "Enter your 4-digit backup PIN to unlock.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!uiState.pinErrorMessage.isNullOrBlank()) {
                    Text(
                        text = uiState.pinErrorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = CoralHangup,
                        modifier = Modifier.testTag("pin_error_text")
                    )
                }

                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { raw ->
                        pinInput = raw.filter { it.isDigit() }.take(4)
                        onClearPinError()
                    },
                    label = { Text("4-Digit PIN") },
                    singleLine = true,
                    enabled = uiState.pinLockoutSeconds <= 0,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_pin_input")
                )

                Button(
                    onClick = { onVerifyBackupPin(pinInput) },
                    enabled = pinInput.length == 4 && uiState.pinLockoutSeconds <= 0,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("verify_pin_button")
                ) {
                    Text(
                        if (uiState.pinLockoutSeconds > 0) {
                            "Locked (${uiState.pinLockoutSeconds}s)"
                        } else {
                            "Unlock with PIN"
                        }
                    )
                }

                TextButton(
                    onClick = {
                        usePinMode = false
                        onClearPinError()
                        promptDeviceBiometricAuth(
                            context = context,
                            title = "Apun Ka Communication Locked",
                            subtitle = "Unlock with fingerprint, face, or screen lock",
                            onSuccess = onBiometricUnlocked,
                            onFallbackNoHardware = { usePinMode = true }
                        )
                    },
                    modifier = Modifier.testTag("switch_to_biometric_button")
                ) {
                    Text("Use Biometrics")
                }
            } else {
                Text(
                    text = "Authenticate with your device biometrics or choose Use PIN to unlock.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                        promptDeviceBiometricAuth(
                            context = context,
                            title = "Apun Ka Communication Locked",
                            subtitle = "Unlock with fingerprint, face, or screen lock",
                            onSuccess = onBiometricUnlocked,
                            onFallbackNoHardware = { usePinMode = true }
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("biometric_unlock_button")
                ) {
                    Text("Unlock with Biometrics")
                }

                // Small, accessible Use PIN option always visible on the biometric unlock screen
                OutlinedButton(
                    onClick = {
                        onClearPinError()
                        usePinMode = true
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("use_pin_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Use PIN")
                }
            }

            TextButton(
                onClick = onForgotPinSignOut,
                modifier = Modifier.testTag("forgot_pin_sign_in_button")
            ) {
                Text("Forgot PIN? Sign in again")
            }
        }
    }
}

@Composable
private fun ActiveTabPane(
    uiState: AppUiState,
    viewModel: AppViewModel
) {
    when (uiState.activeTab) {
        TopLevelTab.CHATS -> ChatsListScreen(uiState = uiState, viewModel = viewModel)
        TopLevelTab.CALLS -> RecentCallsScreen(uiState = uiState, viewModel = viewModel)
        TopLevelTab.REQUESTS -> PeopleAndRequestsScreen(uiState = uiState, viewModel = viewModel)
        TopLevelTab.ACCOUNT -> AccountAndDiagnosticsScreen(
            uiState = uiState,
            viewModel = viewModel
        )
    }
}

@Composable
private fun FeedbackBannerOverlay(
    uiState: AppUiState,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val msg = uiState.errorMessage ?: uiState.bannerMessage ?: return
    val isError = uiState.errorMessage != null
    Surface(
        color = if (isError) CoralHangup else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("feedback_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = msg,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) androidx.compose.ui.graphics.Color.White
                else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            if (isError && onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("banner_retry_button")
                ) {
                    Text(
                        text = "Retry",
                        color = androidx.compose.ui.graphics.Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.width(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss message",
                    tint = if (isError) androidx.compose.ui.graphics.Color.White
                    else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
