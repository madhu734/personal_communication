package com.example.repository

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.call.CallCoordinator
import com.example.call.SystemNotificationAndFcmManager
import com.example.model.CallRecord
import com.example.model.ChatMessage
import com.example.model.ChatSummary
import com.example.model.ConversationMemberRecord
import com.example.model.MessageDeliveryState
import com.example.model.MessageKind
import com.example.model.MessageReaction
import com.example.model.MessageRequestItem
import com.example.model.MuteDurationOption
import com.example.model.PeerSummary
import com.example.model.UserRecord
import com.example.model.WatermarkCursorUtils
import com.example.network.ApiException
import com.example.network.PocketBaseApiClient
import com.example.network.PocketBaseRealtimeClient
import com.example.network.RealtimeRecordEvent
import com.example.storage.SessionAndMuteStore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

enum class TopLevelTab {
    CHATS,
    CALLS,
    REQUESTS,
    ACCOUNT
}

data class AppUiState(
    val isInitialized: Boolean = false,
    val isLoading: Boolean = false,
    val currentUser: UserRecord? = null,
    val isBiometricLockEnabled: Boolean = false,
    val isBiometricLockedNow: Boolean = false,
    val hasBackupPin: Boolean = false,
    val needsBackupPinSetup: Boolean = false,
    val pinErrorMessage: String? = null,
    val pinLockoutSeconds: Int = 0,
    val sseConnected: Boolean = false,
    val activeTab: TopLevelTab = TopLevelTab.CHATS,
    val selectedConversationId: String? = null,
    val chats: List<ChatSummary> = emptyList(),
    val requests: List<MessageRequestItem> = emptyList(),
    val searchResults: List<PeerSummary> = emptyList(),
    val isSearchingMembers: Boolean = false,
    val recentCalls: List<CallRecord> = emptyList(),
    val messagesByConversation: Map<String, List<ChatMessage>> = emptyMap(),
    val reactionsByConversation: Map<String, List<MessageReaction>> = emptyMap(),
    val membersByConversation: Map<String, List<ConversationMemberRecord>> = emptyMap(),
    val replyTargetMessage: ChatMessage? = null,
    val editingMessage: ChatMessage? = null,
    val isRecordingVoiceNote: Boolean = false,
    val recordingElapsedSeconds: Int = 0,
    val playingAudioMessageId: String? = null,
    val bannerMessage: String? = null,
    val errorMessage: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val sessionStore = SessionAndMuteStore(application)
    val apiClient = PocketBaseApiClient(sessionStore)
    val realtimeClient = PocketBaseRealtimeClient(sessionStore)
    val callCoordinator = CallCoordinator(
        appContext = application.applicationContext,
        sessionStore = sessionStore,
        apiClient = apiClient
    )

    private val _uiState = MutableStateFlow(
        AppUiState(
            currentUser = sessionStore.getUserRecord(),
            isBiometricLockEnabled = sessionStore.isBiometricAppLockEnabled,
            isBiometricLockedNow = sessionStore.getUserRecord() != null &&
                sessionStore.isBiometricUnlockRequired(),
            hasBackupPin = sessionStore.hasBackupPin(),
            needsBackupPinSetup = sessionStore.getUserRecord() != null &&
                sessionStore.isBiometricAppLockEnabled &&
                !sessionStore.hasBackupPin(),
            pinLockoutSeconds = sessionStore.getPinLockoutRemainingSeconds()
        )
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var presenceTickerJob: Job? = null
    private var presenceExpiryWatcherJob: Job? = null
    private var typingStopDebounceJob: Job? = null
    private var pinLockoutTickerJob: Job? = null
    private var lastTypingSentEpochMs: Long = 0L
    private var voiceRecordTickerJob: Job? = null
    private val voiceRecorderMutex = Mutex()
    private var mediaRecorder: MediaRecorder? = null
    private var activeVoiceOutputFile: File? = null
    private var mediaPlayer: MediaPlayer? = null

    // Tracks failed outgoing attachment files by clientMessageId for idempotent retry
    private val pendingRetryAttachments = mutableMapOf<String, Pair<File, String>>()

    init {
        SystemNotificationAndFcmManager.ensureChannelsCreated(application)
        SystemNotificationAndFcmManager.activeCallCoordinator = callCoordinator
        callCoordinator.peerLookupProvider = { conversationId, peerId ->
            _uiState.value.chats.firstOrNull { it.id == conversationId || it.peer.id == peerId }?.peer
                ?: PeerSummary(id = peerId, name = "Contact")
        }
        SystemNotificationAndFcmManager.onDirectReplySentCallback = { conversationId ->
            viewModelScope.launch {
                refreshConversationData(conversationId)
                recomputeChatsSummary()
            }
        }

        observeRealtimeEvents()
        startPresenceExpiryWatcher()
        restoreExistingSession()
    }

    private fun observeRealtimeEvents() {
        viewModelScope.launch {
            realtimeClient.isConnected.collect { connected ->
                _uiState.update { it.copy(sseConnected = connected) }
            }
        }
        viewModelScope.launch {
            realtimeClient.onReconnected.collect {
                if (_uiState.value.currentUser != null) {
                    refreshAllAuthenticatedData()
                }
            }
        }
        viewModelScope.launch {
            realtimeClient.events.collect { event ->
                handleRealtimeEvent(event)
            }
        }
    }

    /**
     * Periodically refreshes expired `typing_until` (8s) and `presence_until` (45s) statuses
     * so typing and online indicators never stay stuck when a user stops typing.
     */
    private fun startPresenceExpiryWatcher() {
        presenceExpiryWatcherJob?.cancel()
        presenceExpiryWatcherJob = viewModelScope.launch {
            while (true) {
                delay(2_000L)
                val state = _uiState.value
                if (state.currentUser != null && state.chats.isNotEmpty()) {
                    val nowMs = System.currentTimeMillis()
                    val anyChanged = state.chats.any { chat ->
                        val peerMember = state.membersByConversation[chat.id]
                            ?.firstOrNull { it.user != state.currentUser.id }
                        val onlineNow = WatermarkCursorUtils.isPresenceActive(peerMember?.presenceUntil, nowMs)
                        val typingNow = WatermarkCursorUtils.isPresenceActive(peerMember?.typingUntil, nowMs)
                        chat.peerOnline != onlineNow || chat.peerTyping != typingNow
                    }
                    if (anyChanged) {
                        recomputeChatsSummary()
                    }
                }
            }
        }
    }

    private fun restoreExistingSession() {
        viewModelScope.launch {
            val existingToken = sessionStore.getAuthToken()
            val existingUser = sessionStore.getUserRecord()
            if (existingToken.isNotBlank() && existingUser != null) {
                val hasPin = sessionStore.hasBackupPin()
                val bioEnabled = sessionStore.isBiometricAppLockEnabled
                _uiState.update {
                    it.copy(
                        currentUser = existingUser,
                        isInitialized = true,
                        isBiometricLockEnabled = bioEnabled,
                        isBiometricLockedNow = sessionStore.isBiometricUnlockRequired(),
                        hasBackupPin = hasPin,
                        needsBackupPinSetup = bioEnabled && !hasPin,
                        pinLockoutSeconds = sessionStore.getPinLockoutRemainingSeconds()
                    )
                }
                if (_uiState.value.pinLockoutSeconds > 0) {
                    startPinLockoutCountdownTicker()
                }
                try {
                    val sessionValid = apiClient.validateCurrentSession()
                    if (!sessionValid) {
                        performLocalSignOutCleanup("Your session ended on this device. Please sign in again.")
                        return@launch
                    }
                    val refreshed = apiClient.refreshAuth()
                    sessionStore.saveSession(refreshed.token, refreshed.user)
                    _uiState.update {
                        it.copy(currentUser = refreshed.user)
                    }
                    realtimeClient.connectIfAuthenticated()
                    refreshAllAuthenticatedData()
                    registerFcmTokenIfNeeded()
                } catch (e: ApiException) {
                    if (e.statusCode == 401 && apiClient.isSessionTrulyRevoked()) {
                        performLocalSignOutCleanup("Your session ended on this device. Please sign in again.")
                        return@launch
                    }
                    realtimeClient.connectIfAuthenticated()
                    refreshAllAuthenticatedData()
                } catch (_: Exception) {
                    realtimeClient.connectIfAuthenticated()
                    refreshAllAuthenticatedData()
                }
            } else {
                _uiState.update { it.copy(isInitialized = true) }
            }
        }
    }

    // ---------------- Biometric App Lock & Mandatory 4-Digit Backup PIN ----------------

    fun checkBiometricLockStateOnResume() {
        if (_uiState.value.currentUser == null) return
        if (sessionStore.getAuthToken().isBlank()) {
            performLocalSignOutCleanup("Your session ended. Please sign in again.")
            return
        }
        val bioEnabled = sessionStore.isBiometricAppLockEnabled
        val hasPin = sessionStore.hasBackupPin()
        val required = sessionStore.isBiometricUnlockRequired()
        val lockoutSecs = sessionStore.getPinLockoutRemainingSeconds()
        _uiState.update {
            it.copy(
                isBiometricLockEnabled = bioEnabled,
                isBiometricLockedNow = required,
                hasBackupPin = hasPin,
                needsBackupPinSetup = bioEnabled && !hasPin,
                pinLockoutSeconds = lockoutSecs
            )
        }
        if (lockoutSecs > 0) {
            startPinLockoutCountdownTicker()
        }
        // Validate active session against GET /api/app/account/session on resume
        viewModelScope.launch {
            try {
                if (!apiClient.validateCurrentSession()) {
                    performLocalSignOutCleanup("Your session ended on this device. Please sign in again.")
                }
            } catch (_: Exception) {
            }
        }
    }

    fun onBiometricUnlockSucceeded() {
        if (sessionStore.getAuthToken().isBlank() || sessionStore.getUserRecord() == null) {
            performLocalSignOutCleanup("Your session ended. Please sign in again.")
            return
        }
        viewModelScope.launch {
            try {
                if (!apiClient.validateCurrentSession()) {
                    performLocalSignOutCleanup("Your session ended on this device. Please sign in again.")
                    return@launch
                }
            } catch (_: Exception) {
            }
            sessionStore.recordBiometricUnlockSuccess()
            val bioEnabled = sessionStore.isBiometricAppLockEnabled
            val hasPin = sessionStore.hasBackupPin()
            _uiState.update {
                it.copy(
                    isBiometricLockEnabled = bioEnabled,
                    isBiometricLockedNow = false,
                    hasBackupPin = hasPin,
                    needsBackupPinSetup = bioEnabled && !hasPin,
                    pinErrorMessage = null
                )
            }
        }
    }

    /**
     * Completes biometric setup by setting and confirming a 4-digit backup PIN first.
     */
    fun completeBiometricAndBackupPinSetup(pin: String, confirmPin: String): Boolean {
        if (!sessionStore.isValidPinFormat(pin)) {
            _uiState.update {
                it.copy(pinErrorMessage = "Backup PIN must be exactly 4 digits (0000–9999).")
            }
            return false
        }
        if (pin.trim() != confirmPin.trim()) {
            _uiState.update {
                it.copy(pinErrorMessage = "The 4-digit PINs do not match.")
            }
            return false
        }
        val saved = sessionStore.setBackupPin(pin, confirmPin)
        if (!saved) {
            _uiState.update {
                it.copy(pinErrorMessage = "Could not save backup PIN. Please try again.")
            }
            return false
        }
        sessionStore.isBiometricAppLockEnabled = true
        sessionStore.recordBiometricUnlockSuccess()
        _uiState.update {
            it.copy(
                isBiometricLockEnabled = true,
                isBiometricLockedNow = false,
                hasBackupPin = true,
                needsBackupPinSetup = false,
                pinErrorMessage = null,
                pinLockoutSeconds = 0,
                bannerMessage = "Biometric App Lock and 4-digit backup PIN enabled"
            )
        }
        return true
    }

    fun setBiometricAppLockEnabled(enabled: Boolean) {
        if (!enabled) {
            sessionStore.clearBackupPinAndBiometricLock()
            pinLockoutTickerJob?.cancel()
            _uiState.update {
                it.copy(
                    isBiometricLockEnabled = false,
                    isBiometricLockedNow = false,
                    hasBackupPin = false,
                    needsBackupPinSetup = false,
                    pinErrorMessage = null,
                    pinLockoutSeconds = 0,
                    bannerMessage = "App Lock disabled"
                )
            }
            return
        }

        if (!sessionStore.hasBackupPin()) {
            // Require setting and confirming a 4-digit backup PIN before completing biometric setup
            _uiState.update {
                it.copy(
                    needsBackupPinSetup = true,
                    pinErrorMessage = null
                )
            }
            return
        }

        sessionStore.isBiometricAppLockEnabled = true
        sessionStore.recordBiometricUnlockSuccess()
        _uiState.update {
            it.copy(
                isBiometricLockEnabled = true,
                isBiometricLockedNow = false,
                hasBackupPin = true,
                needsBackupPinSetup = false,
                pinErrorMessage = null,
                bannerMessage = "App Lock enabled (requires biometric or PIN every 30 minutes)"
            )
        }
    }

    /**
     * Verifies the 4-digit backup PIN locally with Keystore-backed protection and rate-limiting,
     * and ensures the PocketBase session is valid before unlocking.
     */
    fun unlockWithBackupPin(pin: String) {
        val result = sessionStore.verifyBackupPin(pin)
        if (result.sessionInvalid) {
            performLocalSignOutCleanup("Your session ended. Please sign in again.")
            return
        }
        if (!result.success) {
            if (result.remainingLockoutSeconds > 0) {
                _uiState.update {
                    it.copy(
                        pinLockoutSeconds = result.remainingLockoutSeconds,
                        pinErrorMessage = "Too many failed attempts. Try again in ${result.remainingLockoutSeconds}s."
                    )
                }
                startPinLockoutCountdownTicker()
            } else {
                _uiState.update {
                    it.copy(
                        pinLockoutSeconds = 0,
                        pinErrorMessage = "Incorrect 4-digit PIN (${result.attemptsRemaining} attempts remaining)."
                    )
                }
            }
            return
        }

        viewModelScope.launch {
            try {
                if (!apiClient.validateCurrentSession()) {
                    performLocalSignOutCleanup("Your session ended on this device. Please sign in again.")
                    return@launch
                }
            } catch (_: Exception) {
            }
            pinLockoutTickerJob?.cancel()
            _uiState.update {
                it.copy(
                    isBiometricLockedNow = false,
                    pinErrorMessage = null,
                    pinLockoutSeconds = 0
                )
            }
        }
    }

    fun clearPinError() {
        _uiState.update { it.copy(pinErrorMessage = null) }
    }

    /**
     * Requires account sign-in again to recover a forgotten PIN or restore an expired/revoked session.
     */
    fun recoverForgottenPinBySigningOut() {
        viewModelScope.launch {
            val pushId = sessionStore.registeredPushDeviceId
            if (sessionStore.getAuthToken().isNotBlank()) {
                try {
                    if (pushId.isNotBlank()) {
                        apiClient.deletePushDevice(pushId)
                    }
                } catch (_: Exception) {
                }
                try {
                    apiClient.logoutSession()
                } catch (_: Exception) {
                }
            }
            performLocalSignOutCleanup("Sign in with your account password to reset your App Lock PIN.")
        }
    }

    private fun startPinLockoutCountdownTicker() {
        pinLockoutTickerJob?.cancel()
        pinLockoutTickerJob = viewModelScope.launch {
            while (true) {
                val secs = sessionStore.getPinLockoutRemainingSeconds()
                if (secs <= 0) {
                    _uiState.update {
                        it.copy(
                            pinLockoutSeconds = 0,
                            pinErrorMessage = null
                        )
                    }
                    break
                }
                _uiState.update {
                    it.copy(
                        pinLockoutSeconds = secs,
                        pinErrorMessage = "Too many failed attempts. Try again in ${secs}s."
                    )
                }
                delay(1_000L)
            }
        }
    }

    // ---------------- Authentication & Account Actions ----------------

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your email and password.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val auth = apiClient.signInWithPassword(email, password)
                sessionStore.saveSession(auth.token, auth.user)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentUser = auth.user,
                        isBiometricLockEnabled = sessionStore.isBiometricAppLockEnabled,
                        isBiometricLockedNow = false,
                        hasBackupPin = sessionStore.hasBackupPin(),
                        needsBackupPinSetup = sessionStore.isBiometricAppLockEnabled && !sessionStore.hasBackupPin(),
                        bannerMessage = null
                    )
                }
                realtimeClient.connectIfAuthenticated()
                registerFcmTokenIfNeeded()
                refreshAllAuthenticatedData()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Sign-in failed. Please check your credentials."
                    )
                }
            }
        }
    }

    fun signUp(name: String, email: String, password: String, passwordConfirm: String) {
        if (name.isBlank() || email.isBlank() || password.length < 8) {
            _uiState.update {
                it.copy(errorMessage = "Name, valid email, and password (at least 8 characters) are required.")
            }
            return
        }
        if (password != passwordConfirm) {
            _uiState.update { it.copy(errorMessage = "Passwords do not match.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val auth = apiClient.registerAccount(name, email, password, passwordConfirm)
                sessionStore.saveSession(auth.token, auth.user)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentUser = auth.user,
                        isBiometricLockEnabled = sessionStore.isBiometricAppLockEnabled,
                        isBiometricLockedNow = false,
                        hasBackupPin = sessionStore.hasBackupPin(),
                        needsBackupPinSetup = sessionStore.isBiometricAppLockEnabled && !sessionStore.hasBackupPin()
                    )
                }
                realtimeClient.connectIfAuthenticated()
                registerFcmTokenIfNeeded()
                refreshAllAuthenticatedData()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "Registration failed.")
                }
            }
        }
    }

    fun requestPasswordRecoveryCode(email: String) {
        if (email.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter your email address first.") }
            return
        }
        viewModelScope.launch {
            try {
                apiClient.requestRecoveryCode(email)
                _uiState.update {
                    it.copy(bannerMessage = "6-digit recovery code sent to $email")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = e.message ?: "Could not request recovery code.")
                }
            }
        }
    }

    fun confirmPasswordRecoveryCode(
        email: String,
        code: String,
        newPassword: String,
        newPasswordConfirm: String
    ) {
        if (newPassword.length < 8 || newPassword != newPasswordConfirm) {
            _uiState.update {
                it.copy(errorMessage = "Matching passwords of at least 8 characters are required.")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                apiClient.confirmRecoveryCode(email, code, newPassword, newPasswordConfirm)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        bannerMessage = "Password reset complete. Signing in..."
                    )
                }
                signIn(email, newPassword)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "Recovery failed.")
                }
            }
        }
    }

    fun requestPasswordResetEmailLink(email: String) {
        if (email.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter your email address first.") }
            return
        }
        viewModelScope.launch {
            try {
                apiClient.requestPasswordResetLink(email)
                _uiState.update {
                    it.copy(bannerMessage = "Password reset link sent to your email.")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to request reset link.") }
            }
        }
    }

    fun requestEmailChangeLink(newEmail: String) {
        if (newEmail.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter the new email address.") }
            return
        }
        viewModelScope.launch {
            try {
                apiClient.requestEmailChangeLink(newEmail)
                _uiState.update {
                    it.copy(bannerMessage = "Confirmation link sent to $newEmail.")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to request email change.") }
            }
        }
    }

    fun changePasswordInSession(newPassword: String, newPasswordConfirm: String) {
        if (newPassword.length < 8 || newPassword != newPasswordConfirm) {
            _uiState.update {
                it.copy(errorMessage = "New passwords must match and be at least 8 characters.")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val updatedAuth = apiClient.changePasswordInSession(newPassword, newPasswordConfirm)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentUser = updatedAuth.user,
                        bannerMessage = "Password updated."
                    )
                }
            } catch (e: Exception) {
                handleApiFailure(e, "Could not change password.")
            }
        }
    }

    fun updateProfileNameAndAvatar(
        newName: String,
        avatarUri: Uri? = null,
        clearAvatar: Boolean = false
    ) {
        val user = _uiState.value.currentUser ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val copiedAvatar = avatarUri?.let { copyUriToTempFile(it, "avatar_upload") }
                val updatedUser = apiClient.updateProfile(
                    userId = user.id,
                    name = newName,
                    avatarFile = copiedAvatar?.first,
                    avatarMimeType = copiedAvatar?.second ?: "image/jpeg",
                    clearAvatar = clearAvatar
                )
                copiedAvatar?.first?.delete()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentUser = updatedUser,
                        bannerMessage = "Profile updated."
                    )
                }
            } catch (e: Exception) {
                handleApiFailure(e, "Failed to update profile.")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val pushId = sessionStore.registeredPushDeviceId
            if (sessionStore.getAuthToken().isNotBlank()) {
                try {
                    if (pushId.isNotBlank()) {
                        apiClient.deletePushDevice(pushId)
                    }
                } catch (_: Exception) {
                }
                try {
                    apiClient.logoutSession()
                } catch (_: Exception) {
                }
            }
            performLocalSignOutCleanup(null)
        }
    }

    /**
     * Clears local credentials and private cached data, stops any active call and the realtime SSE
     * connection, cancels notifications, and returns to the Sign-In state.
     */
    fun performLocalSignOutCleanup(reasonBanner: String?) {
        presenceTickerJob?.cancel()
        typingStopDebounceJob?.cancel()
        pinLockoutTickerJob?.cancel()
        voiceRecordTickerJob?.cancel()
        stopAudioPlayback()
        realtimeClient.disconnect()
        callCoordinator.declineOrHangUpCall()
        SystemNotificationAndFcmManager.cancelAllNotifications(getApplication())
        apiClient.clearCachedFileToken()
        sessionStore.clearAuthSession()
        pendingRetryAttachments.values.forEach { (file, _) ->
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
        pendingRetryAttachments.clear()
        _uiState.update {
            AppUiState(
                isInitialized = true,
                bannerMessage = reasonBanner
            )
        }
    }

    /**
     * Validates the current session against `GET /api/app/account/session` when a 401 occurs.
     * If the server confirms that the session ended, clears local credentials and private cached
     * data, stops the call and realtime connection, and returns to sign-in.
     */
    private suspend fun handleApiFailure(e: Exception, defaultMsg: String) {
        if (e is ApiException && (e.statusCode == 401 || e.statusCode == 403)) {
            val revoked = apiClient.isSessionTrulyRevoked()
            if (revoked) {
                performLocalSignOutCleanup("Your session ended on this device. Please sign in again.")
                return
            }
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = e.message ?: defaultMsg
            )
        }
    }

    // ---------------- Push Registration ----------------

    private fun registerFcmTokenIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pushStatus = apiClient.fetchPushConfig()
                if (!pushStatus.androidEnabled) return@launch
            } catch (_: Exception) {
            }

            val pending = sessionStore.pendingFcmToken
            if (pending.isNotBlank()) {
                registerFcmTokenWithBackend(pending)
            }
            try {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    if (!token.isNullOrBlank()) {
                        viewModelScope.launch(Dispatchers.IO) {
                            registerFcmTokenWithBackend(token)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun registerFcmTokenWithBackend(token: String) {
        try {
            val prevId = sessionStore.registeredPushDeviceId
            val recordId = apiClient.registerAndroidPushDevice(token)
            if (prevId.isNotBlank() && prevId != recordId) {
                apiClient.deletePushDevice(prevId)
            }
            sessionStore.pendingFcmToken = ""
        } catch (_: Exception) {
        }
    }

    // ---------------- Non-Blocking Parallel Data Sync & Watermarks ----------------

    fun refreshAllAuthenticatedData() {
        val ownUser = _uiState.value.currentUser ?: return
        viewModelScope.launch {
            try {
                val sessionValidDeferred = async(Dispatchers.IO) {
                    try {
                        apiClient.validateCurrentSession()
                    } catch (_: Exception) {
                        true
                    }
                }
                val inboxDeferred = async(Dispatchers.IO) { apiClient.fetchInbox() }
                val membersDeferred = async(Dispatchers.IO) {
                    try {
                        apiClient.fetchConversationMembers()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                if (!sessionValidDeferred.await()) {
                    performLocalSignOutCleanup("Your session ended on this device. Please sign in again.")
                    return@launch
                }

                val inbox = inboxDeferred.await()
                val allMembers = membersDeferred.await()
                val membersMap = allMembers.groupBy { it.conversation }

                // Show inbox list immediately so UI never feels frozen
                _uiState.update {
                    it.copy(
                        chats = inbox.chats,
                        requests = inbox.requests,
                        membersByConversation = membersMap
                    )
                }
                recomputeChatsSummary()

                // Fetch existing chat history and recent calls normally after sign-in
                val callsDeferred = async(Dispatchers.IO) {
                    try {
                        apiClient.fetchRecentCalls()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                val fetchedByChat = coroutineScope {
                    inbox.chats.map { chat ->
                        async(Dispatchers.IO) {
                            try {
                                val msgs = apiClient.fetchMessages(conversationId = chat.id, perPage = 30)
                                acknowledgeSyncedPeerMessages(
                                    conversationId = chat.id,
                                    messages = msgs,
                                    ownUserId = ownUser.id,
                                    ownMember = membersMap[chat.id]?.firstOrNull { it.user == ownUser.id }
                                )
                                chat.id to msgs
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                val updatedMessagesMap = _uiState.value.messagesByConversation.toMutableMap()
                for ((chatId, serverMsgs) in fetchedByChat) {
                    val existingFailed = updatedMessagesMap[chatId]
                        ?.filter {
                            it.deliveryState == MessageDeliveryState.FAILED ||
                                it.deliveryState == MessageDeliveryState.SENDING
                        }
                        .orEmpty()
                    updatedMessagesMap[chatId] = mergeMessagesWithLocalQueue(
                        serverMessages = serverMsgs,
                        localQueue = existingFailed,
                        ownUserId = ownUser.id,
                        peerMember = membersMap[chatId]?.firstOrNull { it.user != ownUser.id }
                    )
                }

                val calls = callsDeferred.await()
                _uiState.update {
                    it.copy(
                        recentCalls = calls,
                        messagesByConversation = updatedMessagesMap
                    )
                }
                recomputeChatsSummary()
            } catch (e: Exception) {
                handleApiFailure(e, "Could not sync inbox.")
            }
        }
    }

    fun refreshConversationData(conversationId: String) {
        if (conversationId.isBlank()) return
        val ownUser = _uiState.value.currentUser ?: return
        viewModelScope.launch {
            try {
                val (msgs, members, reactions) = coroutineScope {
                    val mDef = async(Dispatchers.IO) {
                        apiClient.fetchMessages(conversationId = conversationId, perPage = 30)
                    }
                    val memDef = async(Dispatchers.IO) {
                        apiClient.fetchConversationMembers(conversationId = conversationId)
                    }
                    val rDef = async(Dispatchers.IO) {
                        apiClient.fetchMessageReactions(conversationId = conversationId)
                    }
                    Triple(mDef.await(), memDef.await(), rDef.await())
                }

                val peerMember = members.firstOrNull { it.user != ownUser.id }
                val ownMember = members.firstOrNull { it.user == ownUser.id }

                val currentList = _uiState.value.messagesByConversation[conversationId].orEmpty()
                val localPending = currentList.filter {
                    it.deliveryState == MessageDeliveryState.FAILED ||
                        it.deliveryState == MessageDeliveryState.SENDING
                }
                val merged = mergeMessagesWithLocalQueue(msgs, localPending, ownUser.id, peerMember)

                _uiState.update { state ->
                    state.copy(
                        messagesByConversation = state.messagesByConversation + (conversationId to merged),
                        membersByConversation = state.membersByConversation + (conversationId to members),
                        reactionsByConversation = state.reactionsByConversation + (conversationId to reactions)
                    )
                }

                acknowledgeSyncedPeerMessages(
                    conversationId = conversationId,
                    messages = msgs,
                    ownUserId = ownUser.id,
                    ownMember = ownMember
                )
                if (_uiState.value.selectedConversationId == conversationId) {
                    markOpenConversationReadIfNeeded(conversationId, merged)
                }
                recomputeChatsSummary()
            } catch (e: Exception) {
                handleApiFailure(e, "Failed to refresh conversation.")
            }
        }
    }

    fun loadOlderMessages(conversationId: String) {
        val current = _uiState.value.messagesByConversation[conversationId].orEmpty()
        val oldestServer = current.firstOrNull { !it.id.startsWith("local_") } ?: return
        val ownUser = _uiState.value.currentUser ?: return
        viewModelScope.launch {
            try {
                val older = apiClient.fetchMessages(
                    conversationId = conversationId,
                    perPage = 30,
                    beforeCreated = oldestServer.created,
                    beforeId = oldestServer.id
                )
                if (older.isEmpty()) return@launch
                val peerMember = _uiState.value.membersByConversation[conversationId]
                    ?.firstOrNull { it.user != ownUser.id }
                val combined = (older + current).distinctBy {
                    it.id.ifBlank { it.clientMessageId }
                }
                val merged = mergeMessagesWithLocalQueue(
                    serverMessages = combined.filter { !it.id.startsWith("local_") },
                    localQueue = combined.filter { it.id.startsWith("local_") },
                    ownUserId = ownUser.id,
                    peerMember = peerMember
                )
                _uiState.update { state ->
                    state.copy(
                        messagesByConversation = state.messagesByConversation + (conversationId to merged)
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun acknowledgeSyncedPeerMessages(
        conversationId: String,
        messages: List<ChatMessage>,
        ownUserId: String,
        ownMember: ConversationMemberRecord?
    ) {
        val newestPeerMsg = messages.lastOrNull { it.sender != ownUserId && !it.id.startsWith("local_") }
            ?: return
        val alreadyDelivered = ownMember != null &&
            WatermarkCursorUtils.isMessageAtOrBeforeWatermark(
                msgCreated = newestPeerMsg.created,
                msgId = newestPeerMsg.id,
                watermarkTime = ownMember.deliveredThrough,
                watermarkId = ownMember.deliveredId
            )
        if (!alreadyDelivered) {
            try {
                apiClient.markConversationDelivered(conversationId, newestPeerMsg.id)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun markOpenConversationReadIfNeeded(
        conversationId: String,
        messages: List<ChatMessage>
    ) {
        val ownUser = _uiState.value.currentUser ?: return
        val newestPeerMsg = messages.lastOrNull { it.sender != ownUser.id && !it.id.startsWith("local_") }
            ?: return
        val ownMember = _uiState.value.membersByConversation[conversationId]
            ?.firstOrNull { it.user == ownUser.id }
        val alreadyRead = ownMember != null &&
            WatermarkCursorUtils.isMessageAtOrBeforeWatermark(
                msgCreated = newestPeerMsg.created,
                msgId = newestPeerMsg.id,
                watermarkTime = ownMember.readThrough,
                watermarkId = ownMember.readId
            )
        if (!alreadyRead) {
            try {
                apiClient.markConversationRead(conversationId, newestPeerMsg.id)
                apiClient.markConversationDelivered(conversationId, newestPeerMsg.id)
            } catch (_: Exception) {
            }
        }
    }

    private fun mergeMessagesWithLocalQueue(
        serverMessages: List<ChatMessage>,
        localQueue: List<ChatMessage>,
        ownUserId: String,
        peerMember: ConversationMemberRecord?
    ): List<ChatMessage> {
        val serverClientIds = serverMessages.mapNotNull {
            it.clientMessageId.takeIf { cid -> cid.isNotBlank() }
        }.toSet()
        val resolvedServer = serverMessages.map { msg ->
            if (msg.sender == ownUserId) {
                msg.copy(
                    deliveryState = WatermarkCursorUtils.resolveOutgoingDeliveryState(
                        message = msg.copy(deliveryState = MessageDeliveryState.SENT),
                        peerMember = peerMember
                    )
                )
            } else {
                msg
            }
        }
        val remainingLocal = localQueue.filter { it.clientMessageId !in serverClientIds }
        return (resolvedServer + remainingLocal)
            .sortedWith(compareBy<ChatMessage> { it.created }.thenBy { it.id })
    }

    fun recomputeChatsSummary() {
        val state = _uiState.value
        val ownUserId = state.currentUser?.id.orEmpty()
        val nowMs = System.currentTimeMillis()

        val updatedMessagesByConv = state.messagesByConversation.mapValues { (convId, msgs) ->
            val peerMember = state.membersByConversation[convId]
                ?.firstOrNull { it.user != ownUserId }
            msgs.map { msg ->
                if (msg.sender == ownUserId &&
                    msg.deliveryState != MessageDeliveryState.SENDING &&
                    msg.deliveryState != MessageDeliveryState.FAILED
                ) {
                    msg.copy(
                        deliveryState = WatermarkCursorUtils.resolveOutgoingDeliveryState(
                            message = msg,
                            peerMember = peerMember
                        )
                    )
                } else {
                    msg
                }
            }
        }

        val enrichedChats = state.chats.map { chat ->
            val msgs = updatedMessagesByConv[chat.id].orEmpty()
            val members = state.membersByConversation[chat.id].orEmpty()
            val ownMember = members.firstOrNull { it.user == ownUserId }
            val peerMember = members.firstOrNull { it.user != ownUserId }

            val lastMsg = msgs.lastOrNull()
            val unread = if (state.selectedConversationId == chat.id) {
                0
            } else {
                msgs.count { msg ->
                    msg.sender != ownUserId &&
                        !WatermarkCursorUtils.isMessageAtOrBeforeWatermark(
                            msgCreated = msg.created,
                            msgId = msg.id,
                            watermarkTime = ownMember?.readThrough.orEmpty(),
                            watermarkId = ownMember?.readId.orEmpty()
                        )
                }
            }
            val activeMuteUntil = sessionStore.getActiveMuteUntil(ownUserId, chat.id, nowMs)
            chat.copy(
                lastMessage = lastMsg,
                unreadCount = unread,
                isMuted = activeMuteUntil != null,
                muteUntilEpochMs = activeMuteUntil,
                peerOnline = WatermarkCursorUtils.isPresenceActive(peerMember?.presenceUntil, nowMs),
                peerTyping = WatermarkCursorUtils.isPresenceActive(peerMember?.typingUntil, nowMs)
            )
        }.sortedByDescending { it.lastMessage?.created.orEmpty() }

        _uiState.update {
            it.copy(
                chats = enrichedChats,
                messagesByConversation = updatedMessagesByConv
            )
        }
    }

    private fun handleRealtimeEvent(event: RealtimeRecordEvent) {
        val ownUser = _uiState.value.currentUser ?: return
        when (event.collection) {
            "messages" -> {
                val msg = PocketBaseApiClient.parseChatMessage(event.record)
                val convId = msg.conversation
                val currentList = _uiState.value.messagesByConversation[convId].orEmpty()
                val updated = when (event.action) {
                    "delete" -> currentList.filterNot { it.id == msg.id }
                    else -> {
                        val filtered = currentList.filterNot {
                            it.id == msg.id ||
                                (msg.clientMessageId.isNotBlank() && it.clientMessageId == msg.clientMessageId)
                        }
                        filtered + msg
                    }
                }
                val peerMember = _uiState.value.membersByConversation[convId]
                    ?.firstOrNull { it.user != ownUser.id }
                val merged = mergeMessagesWithLocalQueue(
                    serverMessages = updated.filter { !it.id.startsWith("local_") },
                    localQueue = updated.filter { it.id.startsWith("local_") },
                    ownUserId = ownUser.id,
                    peerMember = peerMember
                )
                _uiState.update { state ->
                    state.copy(
                        messagesByConversation = state.messagesByConversation + (convId to merged)
                    )
                }
                if (msg.sender != ownUser.id) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            apiClient.markConversationDelivered(convId, msg.id)
                        } catch (_: Exception) {
                        }
                        if (_uiState.value.selectedConversationId == convId) {
                            markOpenConversationReadIfNeeded(convId, merged)
                        } else {
                            val peerName = _uiState.value.chats.firstOrNull { it.id == convId }?.peer?.name
                                ?: "Contact"
                            SystemNotificationAndFcmManager.showConversationMessageNotification(
                                context = getApplication(),
                                conversationId = convId,
                                peerName = peerName,
                                senderName = peerName,
                                messageText = msg.text.ifBlank { "[${msg.kind.wireName}]" },
                                messageId = msg.id
                            )
                        }
                    }
                }
                recomputeChatsSummary()
            }

            "conversation_members" -> {
                val member = PocketBaseApiClient.parseConversationMember(event.record)
                val convId = member.conversation
                val currentMembers = _uiState.value.membersByConversation[convId].orEmpty()
                val nextMembers = currentMembers.filterNot {
                    it.id == member.id || (it.conversation == convId && it.user == member.user)
                } + member
                _uiState.update { state ->
                    state.copy(
                        membersByConversation = state.membersByConversation + (convId to nextMembers)
                    )
                }
                recomputeChatsSummary()
            }

            "message_reactions" -> {
                val reaction = PocketBaseApiClient.parseMessageReaction(event.record)
                val convId = reaction.conversation
                val current = _uiState.value.reactionsByConversation[convId].orEmpty()
                val next = if (event.action == "delete") {
                    current.filterNot { it.id == reaction.id }
                } else {
                    current.filterNot {
                        it.id == reaction.id || (it.message == reaction.message && it.user == reaction.user)
                    } + reaction
                }
                _uiState.update { state ->
                    state.copy(
                        reactionsByConversation = state.reactionsByConversation + (convId to next)
                    )
                }
            }

            "calls" -> {
                val callRec = PocketBaseApiClient.parseCallRecord(event.record)
                callCoordinator.onCallRecordUpdated(callRec)
                val currentCalls = _uiState.value.recentCalls.filterNot { it.id == callRec.id }
                _uiState.update { state ->
                    state.copy(recentCalls = listOf(callRec) + currentCalls)
                }
            }

            "conversations", "message_requests" -> {
                refreshAllAuthenticatedData()
            }
        }
    }

    // ---------------- Navigation, Mute & Debounced Typing Presence ----------------

    fun selectTab(tab: TopLevelTab) {
        _uiState.update { it.copy(activeTab = tab) }
        recomputeChatsSummary()
    }

    fun openConversation(conversationId: String?) {
        val previousConvId = _uiState.value.selectedConversationId
        if (!previousConvId.isNullOrBlank() && previousConvId != conversationId) {
            typingStopDebounceJob?.cancel()
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    apiClient.sendPresence(previousConvId, isTyping = false)
                } catch (_: Exception) {
                }
            }
        }

        _uiState.update {
            it.copy(
                selectedConversationId = conversationId,
                replyTargetMessage = null,
                editingMessage = null
            )
        }
        presenceTickerJob?.cancel()
        if (!conversationId.isNullOrBlank()) {
            SystemNotificationAndFcmManager.cancelConversationNotification(
                getApplication(),
                conversationId
            )
            refreshConversationData(conversationId)
            val msgs = _uiState.value.messagesByConversation[conversationId].orEmpty()
            viewModelScope.launch {
                markOpenConversationReadIfNeeded(conversationId, msgs)
                recomputeChatsSummary()
            }
            presenceTickerJob = viewModelScope.launch(Dispatchers.IO) {
                while (_uiState.value.selectedConversationId == conversationId) {
                    try {
                        apiClient.sendPresence(conversationId, isTyping = false)
                    } catch (_: Exception) {
                    }
                    delay(30_000L)
                }
            }
        } else {
            recomputeChatsSummary()
        }
    }

    /**
     * Debounces typing presence updates so typing in the composer never fires an HTTP request per
     * keystroke, and automatically sends `typing: false` after 2.5s of inactivity.
     */
    fun notifyTypingInOpenConversation(isTyping: Boolean) {
        val convId = _uiState.value.selectedConversationId ?: return
        typingStopDebounceJob?.cancel()

        if (!isTyping) {
            lastTypingSentEpochMs = 0L
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    apiClient.sendPresence(convId, isTyping = false)
                } catch (_: Exception) {
                }
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTypingSentEpochMs >= 3_000L) {
            lastTypingSentEpochMs = now
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    apiClient.sendPresence(convId, isTyping = true)
                } catch (_: Exception) {
                }
            }
        }

        typingStopDebounceJob = viewModelScope.launch(Dispatchers.IO) {
            delay(2_500L)
            lastTypingSentEpochMs = 0L
            try {
                apiClient.sendPresence(convId, isTyping = false)
            } catch (_: Exception) {
            }
        }
    }

    fun muteConversation(conversationId: String, option: MuteDurationOption) {
        val userId = _uiState.value.currentUser?.id.orEmpty().ifBlank { "default_user" }
        sessionStore.setConversationMute(userId, conversationId, option)
        SystemNotificationAndFcmManager.cancelConversationNotification(getApplication(), conversationId)
        recomputeChatsSummary()
        _uiState.update {
            it.copy(bannerMessage = "Muted notifications for ${option.label}")
        }
    }

    fun unmuteConversation(conversationId: String) {
        val userId = _uiState.value.currentUser?.id.orEmpty().ifBlank { "default_user" }
        sessionStore.clearConversationMute(userId, conversationId)
        recomputeChatsSummary()
        _uiState.update {
            it.copy(bannerMessage = "Notifications unmuted")
        }
    }

    // ---------------- Messaging, Replies, Edits, Reactions & Attachments ----------------

    fun setReplyTarget(message: ChatMessage?) {
        _uiState.update { it.copy(replyTargetMessage = message, editingMessage = null) }
    }

    fun setEditingMessage(message: ChatMessage?) {
        _uiState.update { it.copy(editingMessage = message, replyTargetMessage = null) }
    }

    fun sendOrEditTextMessage(
        conversationId: String,
        text: String,
        inCall: Boolean = false
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        typingStopDebounceJob?.cancel()
        lastTypingSentEpochMs = 0L

        val editing = _uiState.value.editingMessage
        if (editing != null && !inCall) {
            submitMessageEdit(editing, trimmed)
            return
        }
        val replyToId = if (inCall) "" else _uiState.value.replyTargetMessage?.id.orEmpty()
        _uiState.update { it.copy(replyTargetMessage = null) }
        enqueueAndSendMessage(
            conversationId = conversationId,
            text = trimmed,
            kind = MessageKind.TEXT,
            replyToId = replyToId,
            inCall = inCall
        )
    }

    fun sendMediaAttachment(
        conversationId: String,
        uri: Uri,
        caption: String = ""
    ) {
        viewModelScope.launch {
            val copied = copyUriToTempFile(uri, "msg_attachment")
            if (copied == null) {
                _uiState.update { it.copy(errorMessage = "Unable to read selected attachment.") }
                return@launch
            }
            val (file, mime) = copied
            if (file.length() > 50L * 1024L * 1024L) {
                file.delete()
                _uiState.update { it.copy(errorMessage = "Attachment exceeds 50 MiB limit.") }
                return@launch
            }
            val kind = when {
                mime.startsWith("video/") -> MessageKind.VIDEO
                mime.startsWith("audio/") -> MessageKind.VOICE
                else -> MessageKind.IMAGE
            }
            enqueueAndSendMessage(
                conversationId = conversationId,
                text = caption.trim(),
                kind = kind,
                replyToId = _uiState.value.replyTargetMessage?.id.orEmpty(),
                inCall = false,
                attachmentFile = file,
                attachmentMimeType = mime,
                localUriString = uri.toString()
            )
            _uiState.update { it.copy(replyTargetMessage = null) }
        }
    }

    fun retryFailedMessage(message: ChatMessage) {
        val filePair = pendingRetryAttachments[message.clientMessageId]
        enqueueAndSendMessage(
            conversationId = message.conversation,
            text = message.text,
            kind = message.kind,
            replyToId = message.replyTo,
            inCall = message.inCall,
            existingClientMessageId = message.clientMessageId,
            attachmentFile = filePair?.first,
            attachmentMimeType = filePair?.second ?: "application/octet-stream",
            localUriString = message.localAttachmentUri
        )
    }

    private fun enqueueAndSendMessage(
        conversationId: String,
        text: String,
        kind: MessageKind,
        replyToId: String = "",
        inCall: Boolean = false,
        existingClientMessageId: String? = null,
        attachmentFile: File? = null,
        attachmentMimeType: String = "application/octet-stream",
        localUriString: String = ""
    ) {
        val ownUser = _uiState.value.currentUser ?: return
        val clientMsgId = existingClientMessageId ?: UUID.randomUUID().toString()
        if (attachmentFile != null) {
            pendingRetryAttachments[clientMsgId] = attachmentFile to attachmentMimeType
        }
        val nowIso = Instant.now().toString()
        val optimistic = ChatMessage(
            id = "local_$clientMsgId",
            conversation = conversationId,
            sender = ownUser.id,
            text = text,
            clientMessageId = clientMsgId,
            kind = kind,
            attachment = attachmentFile?.name.orEmpty(),
            attachmentBytes = attachmentFile?.length() ?: 0L,
            replyTo = replyToId,
            inCall = inCall,
            created = nowIso,
            updated = nowIso,
            deliveryState = MessageDeliveryState.SENDING,
            localAttachmentUri = localUriString.ifBlank { attachmentFile?.absolutePath.orEmpty() }
        )

        val currentMsgs = _uiState.value.messagesByConversation[conversationId].orEmpty()
            .filterNot { it.clientMessageId == clientMsgId }
        _uiState.update { state ->
            state.copy(
                messagesByConversation = state.messagesByConversation + (conversationId to (currentMsgs + optimistic))
            )
        }
        recomputeChatsSummary()

        viewModelScope.launch {
            try {
                val serverSaved = apiClient.sendMessage(
                    conversationId = conversationId,
                    text = text,
                    clientMessageId = clientMsgId,
                    kind = kind,
                    replyToId = replyToId,
                    inCall = inCall,
                    attachmentFile = attachmentFile,
                    attachmentMimeType = attachmentMimeType
                )
                pendingRetryAttachments.remove(clientMsgId)?.first?.delete()
                replaceMessageByClientId(
                    conversationId = conversationId,
                    clientMessageId = clientMsgId,
                    replacement = serverSaved.copy(
                        localAttachmentUri = optimistic.localAttachmentUri,
                        deliveryState = MessageDeliveryState.SENT
                    )
                )
            } catch (e: Exception) {
                replaceMessageByClientId(
                    conversationId = conversationId,
                    clientMessageId = clientMsgId,
                    replacement = optimistic.copy(deliveryState = MessageDeliveryState.FAILED)
                )
                handleApiFailure(e, "Message failed to send. Tap Retry on the message bubble.")
            }
        }
    }

    private fun replaceMessageByClientId(
        conversationId: String,
        clientMessageId: String,
        replacement: ChatMessage
    ) {
        val list = _uiState.value.messagesByConversation[conversationId].orEmpty()
            .filterNot { it.clientMessageId == clientMessageId || it.id == replacement.id }
        _uiState.update { state ->
            state.copy(
                messagesByConversation = state.messagesByConversation + (conversationId to (list + replacement))
            )
        }
        recomputeChatsSummary()
    }

    private fun submitMessageEdit(original: ChatMessage, newText: String) {
        _uiState.update { it.copy(editingMessage = null) }
        viewModelScope.launch {
            try {
                val updated = apiClient.editMessage(original.id, newText)
                replaceMessageByClientId(original.conversation, original.clientMessageId, updated)
            } catch (e: Exception) {
                handleApiFailure(e, "Could not edit message.")
            }
        }
    }

    fun toggleOrSetReaction(conversationId: String, messageId: String, emoji: String) {
        val ownUser = _uiState.value.currentUser ?: return
        val existingList = _uiState.value.reactionsByConversation[conversationId].orEmpty()
        val ownExisting = existingList.firstOrNull { it.message == messageId && it.user == ownUser.id }
        val targetEmoji = if (ownExisting?.emoji == emoji) "" else emoji

        val optimisticNext = if (targetEmoji.isBlank()) {
            existingList.filterNot { it.message == messageId && it.user == ownUser.id }
        } else {
            existingList.filterNot { it.message == messageId && it.user == ownUser.id } +
                MessageReaction(
                    id = ownExisting?.id ?: "react_${UUID.randomUUID().toString().take(8)}",
                    conversation = conversationId,
                    message = messageId,
                    user = ownUser.id,
                    emoji = targetEmoji
                )
        }
        _uiState.update { state ->
            state.copy(
                reactionsByConversation = state.reactionsByConversation + (conversationId to optimisticNext)
            )
        }

        viewModelScope.launch {
            try {
                apiClient.setReaction(messageId, targetEmoji)
            } catch (e: Exception) {
                handleApiFailure(e, "Failed to update reaction.")
            }
        }
    }

    // ---------------- Voice Note Recording & Protected Media Playback ----------------

    fun startVoiceNoteRecording() {
        if (_uiState.value.isRecordingVoiceNote) return
        stopAudioPlayback()
        val outFile = File(getApplication<Application>().cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        activeVoiceOutputFile = outFile
        viewModelScope.launch(Dispatchers.IO) {
            voiceRecorderMutex.withLock {
                try {
                    mediaRecorder?.release()
                } catch (_: Exception) {
                }
                mediaRecorder = null
                try {
                    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(getApplication())
                    } else {
                        @Suppress("DEPRECATION")
                        MediaRecorder()
                    }
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioEncodingBitRate(64_000)
                    recorder.setAudioSamplingRate(44_100)
                    recorder.setOutputFile(outFile.absolutePath)
                    recorder.prepare()
                    recorder.start()
                    mediaRecorder = recorder
                } catch (_: Exception) {
                    mediaRecorder = null
                    outFile.writeBytes(MINIMAL_M4A_HEADER)
                }
            }
        }

        _uiState.update { it.copy(isRecordingVoiceNote = true, recordingElapsedSeconds = 0) }
        voiceRecordTickerJob?.cancel()
        voiceRecordTickerJob = viewModelScope.launch {
            var secs = 0
            while (_uiState.value.isRecordingVoiceNote) {
                delay(1_000L)
                secs += 1
                _uiState.update { it.copy(recordingElapsedSeconds = secs) }
            }
        }
    }

    fun finishVoiceNoteRecording(conversationId: String, cancel: Boolean = false) {
        voiceRecordTickerJob?.cancel()
        val file = activeVoiceOutputFile
        activeVoiceOutputFile = null
        val secs = _uiState.value.recordingElapsedSeconds
        _uiState.update { it.copy(isRecordingVoiceNote = false, recordingElapsedSeconds = 0) }

        viewModelScope.launch(Dispatchers.IO) {
            voiceRecorderMutex.withLock {
                try {
                    mediaRecorder?.stop()
                } catch (_: Exception) {
                }
                try {
                    mediaRecorder?.release()
                } catch (_: Exception) {
                }
                mediaRecorder = null
            }

            if (cancel || file == null) {
                file?.delete()
                return@launch
            }
            if (!file.exists() || file.length() == 0L) {
                file.writeBytes(MINIMAL_M4A_HEADER)
            }
            withContext(Dispatchers.Main) {
                enqueueAndSendMessage(
                    conversationId = conversationId,
                    text = "Voice note (${secs.coerceAtLeast(1)}s)",
                    kind = MessageKind.VOICE,
                    attachmentFile = file,
                    attachmentMimeType = "audio/mp4",
                    localUriString = file.absolutePath
                )
            }
        }
    }

    fun togglePlayVoiceMessage(message: ChatMessage) {
        if (_uiState.value.playingAudioMessageId == message.id) {
            stopAudioPlayback()
            return
        }
        stopAudioPlayback()
        _uiState.update { it.copy(playingAudioMessageId = message.id) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localFile = if (message.localAttachmentUri.isNotBlank() &&
                    File(message.localAttachmentUri).exists() &&
                    File(message.localAttachmentUri).length() > 0L
                ) {
                    File(message.localAttachmentUri)
                } else {
                    apiClient.downloadProtectedFileToCache(
                        collection = "messages",
                        recordId = message.id,
                        filename = message.attachment,
                        cacheDir = getApplication<Application>().cacheDir
                    )
                }

                if (localFile == null || !localFile.exists() || localFile.length() == 0L) {
                    delay(1_200L)
                    _uiState.update { it.copy(playingAudioMessageId = null) }
                    return@launch
                }

                val player = MediaPlayer()
                mediaPlayer = player
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                player.setOnCompletionListener {
                    try {
                        it.release()
                    } catch (_: Exception) {
                    }
                    if (mediaPlayer === it) {
                        mediaPlayer = null
                    }
                    _uiState.update { state -> state.copy(playingAudioMessageId = null) }
                }
                player.setOnErrorListener { mp, _, _ ->
                    try {
                        mp.release()
                    } catch (_: Exception) {
                    }
                    if (mediaPlayer === mp) {
                        mediaPlayer = null
                    }
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            apiClient.getFileToken(forceRefresh = true)
                        } catch (_: Exception) {
                        }
                        _uiState.update { state -> state.copy(playingAudioMessageId = null) }
                    }
                    true
                }
                player.setOnPreparedListener { mp ->
                    if (_uiState.value.playingAudioMessageId == message.id) {
                        mp.start()
                    } else {
                        try {
                            mp.release()
                        } catch (_: Exception) {
                        }
                    }
                }
                FileInputStream(localFile).use { fis ->
                    player.setDataSource(fis.fd)
                }
                player.prepareAsync()
            } catch (_: Exception) {
                delay(1_200L)
                _uiState.update { it.copy(playingAudioMessageId = null) }
            }
        }
    }

    private fun stopAudioPlayback() {
        val playerToStop = mediaPlayer
        mediaPlayer = null
        _uiState.update { it.copy(playingAudioMessageId = null) }
        if (playerToStop != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    playerToStop.stop()
                } catch (_: Exception) {
                }
                try {
                    playerToStop.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------------- Member Search & Message Requests ----------------

    fun searchMembers(query: String) {
        if (query.trim().length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearchingMembers = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingMembers = true) }
            try {
                val results = apiClient.searchMembers(query)
                _uiState.update { it.copy(searchResults = results, isSearchingMembers = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearchingMembers = false) }
                handleApiFailure(e, "Member search failed.")
            }
        }
    }

    fun sendConnectionRequest(peer: PeerSummary, note: String) {
        viewModelScope.launch {
            try {
                val res = apiClient.sendMessageRequest(peer.id, note)
                if (!res.existingConversationId.isNullOrBlank()) {
                    refreshAllAuthenticatedData()
                    openConversation(res.existingConversationId)
                    return@launch
                }
                refreshAllAuthenticatedData()
                _uiState.update {
                    it.copy(bannerMessage = "Request sent to ${peer.name}")
                }
            } catch (e: Exception) {
                handleApiFailure(e, "Could not send request.")
            }
        }
    }

    fun acceptRequest(request: MessageRequestItem) {
        viewModelScope.launch {
            try {
                val convId = apiClient.acceptMessageRequest(request.id)
                refreshAllAuthenticatedData()
                if (convId.isNotBlank()) {
                    openConversation(convId)
                }
            } catch (e: Exception) {
                refreshAllAuthenticatedData()
                handleApiFailure(e, "Could not accept request.")
            }
        }
    }

    fun declineOrCancelRequest(request: MessageRequestItem) {
        viewModelScope.launch {
            try {
                apiClient.deleteMessageRequest(request.id)
                refreshAllAuthenticatedData()
            } catch (e: Exception) {
                refreshAllAuthenticatedData()
                handleApiFailure(e, "Could not remove request.")
            }
        }
    }

    fun clearBannerAndError() {
        _uiState.update { it.copy(bannerMessage = null, errorMessage = null) }
    }

    private suspend fun copyUriToTempFile(uri: Uri, prefix: String): Pair<File, String>? =
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val mime = resolver.getType(uri) ?: "image/jpeg"
            var ext = ".bin"
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    val displayName = cursor.getString(nameIndex).orEmpty()
                    if (displayName.contains('.')) {
                        ext = "." + displayName.substringAfterLast('.')
                    }
                }
            }
            val tempFile = File.createTempFile(prefix, ext, getApplication<Application>().cacheDir)
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null
            tempFile to mime
        }

    companion object {
        private val MINIMAL_M4A_HEADER = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x4D, 0x34, 0x41, 0x20, 0x00, 0x00, 0x00, 0x00,
            0x4D, 0x34, 0x41, 0x20, 0x69, 0x73, 0x6F, 0x6D
        )
    }
}
