package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.network.PocketBaseApiClient
import com.example.repository.AppUiState
import com.example.repository.AppViewModel
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.CoralHangup

private enum class AuthMode {
    SIGN_IN,
    SIGN_UP
}

@Composable
fun AuthScreen(
    uiState: AppUiState,
    viewModel: AppViewModel
) {
    var authMode by rememberSaveable { mutableStateOf(AuthMode.SIGN_IN) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordConfirm by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showForgotPasswordDialog by rememberSaveable { mutableStateOf(false) }

    if (showForgotPasswordDialog) {
        ForgotPasswordDialog(
            initialEmail = email,
            isLoading = uiState.isLoading,
            onDismiss = { showForgotPasswordDialog = false },
            onRequestOtpCode = { targetEmail ->
                viewModel.requestPasswordRecoveryCode(targetEmail)
            },
            onRequestEmailLink = { targetEmail ->
                viewModel.requestPasswordResetEmailLink(targetEmail)
            },
            onConfirmReset = { targetEmail, code, newPass, newPassConfirm ->
                viewModel.confirmPasswordRecoveryCode(
                    email = targetEmail,
                    code = code,
                    newPassword = newPass,
                    newPasswordConfirm = newPassConfirm
                )
                showForgotPasswordDialog = false
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Apun Ka Communication",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Apun Ka Communication",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (authMode == AuthMode.SIGN_UP) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Full Name") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_name_input")
                        )
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_email_input")
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = "Toggle password visibility"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_password_input")
                    )

                    if (authMode == AuthMode.SIGN_UP) {
                        OutlinedTextField(
                            value = passwordConfirm,
                            onValueChange = { passwordConfirm = it },
                            label = { Text("Confirm Password") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            visualTransformation = if (showPassword) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_password_confirm_input")
                        )
                    }

                    if (authMode == AuthMode.SIGN_IN) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = { showForgotPasswordDialog = true },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .testTag("forgot_password_button")
                            ) {
                                Text("Forgot password?")
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Button(
                        onClick = {
                            when (authMode) {
                                AuthMode.SIGN_IN -> viewModel.signIn(email, password)
                                AuthMode.SIGN_UP -> viewModel.signUp(name, email, password, passwordConfirm)
                            }
                        },
                        enabled = !uiState.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("auth_submit_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(
                                text = if (authMode == AuthMode.SIGN_IN) "Sign In" else "Create Account",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = {
                    authMode = if (authMode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN
                },
                modifier = Modifier.testTag(
                    if (authMode == AuthMode.SIGN_IN) "auth_tab_signup" else "auth_tab_signin"
                )
            ) {
                Text(
                    text = if (authMode == AuthMode.SIGN_IN) {
                        "Don't have an account? Create Account"
                    } else {
                        "Already have an account? Sign In"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ForgotPasswordDialog(
    initialEmail: String,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onRequestOtpCode: (String) -> Unit,
    onRequestEmailLink: (String) -> Unit,
    onConfirmReset: (String, String, String, String) -> Unit
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var newPasswordConfirm by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter your account email to receive a 6-digit recovery code or a password reset link.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onRequestOtpCode(email) },
                        enabled = email.isNotBlank() && !isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Send Code")
                    }
                    OutlinedButton(
                        onClick = { onRequestEmailLink(email) },
                        enabled = email.isNotBlank() && !isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Email Link")
                    }
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(6) },
                    label = { Text("6-Digit Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_recovery_code_input")
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("New Password (8+ chars)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPasswordConfirm,
                    onValueChange = { newPasswordConfirm = it },
                    label = { Text("Confirm New Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmReset(email, code, newPassword, newPasswordConfirm)
                },
                enabled = email.isNotBlank() && code.trim().length == 6 && newPassword.length >= 8
            ) {
                Text("Reset Password")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SetBackupPinDialog(
    isMandatoryForExistingBiometricUser: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirmPinSetup: (String, String) -> Unit
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmPin by rememberSaveable { mutableStateOf("") }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

    val cleanPin = pin.trim()
    val cleanConfirm = confirmPin.trim()
    val isPinFourDigits = cleanPin.length == 4 && cleanPin.all { it.isDigit() }
    val isConfirmFourDigits = cleanConfirm.length == 4 && cleanConfirm.all { it.isDigit() }

    AlertDialog(
        onDismissRequest = {
            if (!isMandatoryForExistingBiometricUser) {
                onDismiss()
            }
        },
        title = {
            Text(
                if (isMandatoryForExistingBiometricUser) {
                    "Set 4-Digit Backup PIN"
                } else {
                    "Set 4-Digit Backup PIN for App Lock"
                }
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag("set_backup_pin_dialog")
            ) {
                Text(
                    text = if (isMandatoryForExistingBiometricUser) {
                        "Your Biometric App Lock requires a 4-digit backup PIN so you can always unlock the app if biometrics are unavailable."
                    } else {
                        "Set and confirm a 4-digit backup PIN before enabling Biometric App Lock. You can use this PIN anytime to unlock the app."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val displayError = localError ?: errorMessage
                if (!displayError.isNullOrBlank()) {
                    Text(
                        text = displayError,
                        style = MaterialTheme.typography.bodySmall,
                        color = CoralHangup
                    )
                }

                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        pin = input.filter { it.isDigit() }.take(4)
                        localError = null
                    },
                    label = { Text("4-Digit PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("setup_pin_input")
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { input ->
                        confirmPin = input.filter { it.isDigit() }.take(4)
                        localError = null
                    },
                    label = { Text("Confirm 4-Digit PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("setup_pin_confirm_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isPinFourDigits) {
                        localError = "Enter a 4-digit PIN (0000–9999)."
                    } else if (cleanPin != cleanConfirm) {
                        localError = "The 4-digit PINs do not match."
                    } else {
                        onConfirmPinSetup(cleanPin, cleanConfirm)
                    }
                },
                enabled = isPinFourDigits && isConfirmFourDigits,
                modifier = Modifier.testTag("confirm_setup_pin_button")
            ) {
                Text("Save PIN & Enable")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_setup_pin_button")
            ) {
                Text(if (isMandatoryForExistingBiometricUser) "Turn Off App Lock" else "Cancel")
            }
        }
    )
}

@Composable
fun AccountAndDiagnosticsScreen(
    uiState: AppUiState,
    viewModel: AppViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val user = uiState.currentUser ?: return

    var showEditProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showChangePasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showChangeEmailDialog by rememberSaveable { mutableStateOf(false) }
    var showSetBackupPinDialog by rememberSaveable { mutableStateOf(false) }

    // Dynamically track whether notifications are enabled; refreshes automatically on resume
    var areNotificationsEnabled by remember {
        mutableStateOf(checkNotificationsEnabled(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                areNotificationsEnabled = checkNotificationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        areNotificationsEnabled = granted && checkNotificationsEnabled(context)
        if (!areNotificationsEnabled) {
            openAppNotificationSettings(context)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.updateProfileNameAndAvatar(newName = user.name, avatarUri = uri)
        }
    }

    if (showEditProfileDialog) {
        EditProfileDialog(
            currentName = user.name,
            hasAvatar = user.avatar.isNotBlank(),
            onDismiss = { showEditProfileDialog = false },
            onSaveName = { newName ->
                viewModel.updateProfileNameAndAvatar(newName = newName)
                showEditProfileDialog = false
            },
            onPickPhoto = {
                showEditProfileDialog = false
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onRemovePhoto = {
                viewModel.updateProfileNameAndAvatar(newName = user.name, clearAvatar = true)
                showEditProfileDialog = false
            }
        )
    }

    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onSubmit = { newPass, confirmPass ->
                viewModel.changePasswordInSession(newPass, confirmPass)
                showChangePasswordDialog = false
            }
        )
    }

    if (showChangeEmailDialog) {
        ChangeEmailDialog(
            currentEmail = user.email,
            onDismiss = { showChangeEmailDialog = false },
            onRequestChangeLink = { newEmail ->
                viewModel.requestEmailChangeLink(newEmail)
                showChangeEmailDialog = false
            }
        )
    }

    if (showSetBackupPinDialog) {
        SetBackupPinDialog(
            isMandatoryForExistingBiometricUser = false,
            errorMessage = uiState.pinErrorMessage,
            onDismiss = {
                showSetBackupPinDialog = false
                viewModel.clearPinError()
            },
            onConfirmPinSetup = { pin, confirmPin ->
                promptDeviceBiometricAuth(
                    context = context,
                    title = "Enable Biometric App Lock",
                    subtitle = "Confirm your fingerprint, face, or screen lock",
                    onSuccess = {
                        if (viewModel.completeBiometricAndBackupPinSetup(pin, confirmPin)) {
                            showSetBackupPinDialog = false
                        }
                    },
                    onFallbackNoHardware = {
                        if (viewModel.completeBiometricAndBackupPinSetup(pin, confirmPin)) {
                            showSetBackupPinDialog = false
                        }
                    }
                )
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Compact Profile Header Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showEditProfileDialog = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ProtectedAvatarImage(
                    apiClient = viewModel.apiClient,
                    userId = user.id,
                    avatarFile = user.avatar,
                    fallbackName = user.name,
                    sizeDp = 60
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = user.email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { showEditProfileDialog = true },
                    modifier = Modifier.testTag("edit_profile_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Profile",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Dynamic Notification Permission Card: ONLY shown when notifications are disabled/denied
        if (!areNotificationsEnabled) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("allow_notifications_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Notifications Disabled",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Enable notifications to receive incoming calls and messages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                openAppNotificationSettings(context)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("allow_notifications_button")
                    ) {
                        Text("Allow")
                    }
                }
            }
        }

        // Compact Security & Account Options Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // WhatsApp-style Biometric App Lock (30-minute relock timer)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Biometric App Lock",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Require biometric or 4-digit PIN after 30 minutes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.isBiometricLockEnabled,
                        onCheckedChange = { wantEnabled ->
                            if (wantEnabled) {
                                viewModel.clearPinError()
                                showSetBackupPinDialog = true
                            } else {
                                viewModel.setBiometricAppLockEnabled(false)
                            }
                        },
                        modifier = Modifier.testTag("biometric_lock_switch")
                    )
                }

                if (uiState.isBiometricLockEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    CompactSettingsActionRow(
                        icon = Icons.Default.Lock,
                        title = "Change 4-Digit Backup PIN",
                        subtitle = "Update the backup PIN used when biometrics aren't used",
                        onClick = {
                            viewModel.clearPinError()
                            showSetBackupPinDialog = true
                        },
                        testTag = "settings_change_backup_pin_row"
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                CompactSettingsActionRow(
                    icon = Icons.Default.Key,
                    title = "Change Password",
                    subtitle = "Update your password for this account",
                    onClick = { showChangePasswordDialog = true },
                    testTag = "settings_change_password_row"
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                CompactSettingsActionRow(
                    icon = Icons.Default.Email,
                    title = "Change Email",
                    subtitle = "Send a verification link to a new email address",
                    onClick = { showChangeEmailDialog = true },
                    testTag = "settings_change_email_row"
                )
            }
        }

        // Sign Out Button
        Button(
            onClick = { viewModel.signOut() },
            colors = ButtonDefaults.buttonColors(containerColor = CoralHangup),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("sign_out_button")
        ) {
            Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign Out", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CompactSettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EditProfileDialog(
    currentName: String,
    hasAvatar: Boolean,
    onDismiss: () -> Unit,
    onSaveName: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit
) {
    var displayName by rememberSaveable(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onPickPhoto,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Change Photo")
                    }
                    if (hasAvatar) {
                        OutlinedButton(
                            onClick = onRemovePhoto,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Remove Photo")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveName(displayName) },
                enabled = displayName.trim().isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("New Password (8+ chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm New Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(newPassword, confirmPassword) },
                enabled = newPassword.length >= 8 && newPassword == confirmPassword
            ) {
                Text("Update Password")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ChangeEmailDialog(
    currentEmail: String,
    onDismiss: () -> Unit,
    onRequestChangeLink: (String) -> Unit
) {
    var newEmail by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Email") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Current email: $currentEmail",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = newEmail,
                    onValueChange = { newEmail = it },
                    label = { Text("New Email Address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRequestChangeLink(newEmail) },
                enabled = newEmail.contains("@")
            ) {
                Text("Send Verification Link")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun checkNotificationsEnabled(context: Context): Boolean {
    val systemEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    if (!systemEnabled) return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

fun openAppNotificationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

fun promptDeviceBiometricAuth(
    context: Context,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onFallbackNoHardware: () -> Unit = {}
) {
    val activity = context as? FragmentActivity
    if (activity == null) {
        onFallbackNoHardware()
        return
    }
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    val canAuth = BiometricManager.from(context).canAuthenticate(authenticators)
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        onFallbackNoHardware()
        return
    }

    val executor = ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onFallbackNoHardware()
            }
        }
    )

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(authenticators)
        .build()

    try {
        prompt.authenticate(promptInfo)
    } catch (_: Exception) {
        onFallbackNoHardware()
    }
}

@Composable
fun ProtectedAvatarImage(
    apiClient: PocketBaseApiClient,
    userId: String,
    avatarFile: String,
    fallbackName: String,
    sizeDp: Int = 46
) {
    var resolvedUrl by remember(userId, avatarFile) { mutableStateOf("") }
    var retriedToken by remember(userId, avatarFile) { mutableStateOf(false) }

    LaunchedEffect(userId, avatarFile, retriedToken) {
        if (avatarFile.isBlank()) {
            resolvedUrl = ""
        } else if (avatarFile.startsWith("http") || avatarFile.startsWith("content://")) {
            resolvedUrl = avatarFile
        } else {
            resolvedUrl = apiClient.buildProtectedFileUrl(
                collection = "users",
                recordId = userId,
                filename = avatarFile,
                thumb = "96x96",
                forceRefreshToken = retriedToken
            )
        }
    }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(sizeDp.dp)
    ) {
        if (resolvedUrl.isNotBlank()) {
            AsyncImage(
                model = resolvedUrl,
                contentDescription = "$fallbackName avatar",
                contentScale = ContentScale.Crop,
                onError = {
                    if (!retriedToken) {
                        retriedToken = true
                    } else {
                        resolvedUrl = ""
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                val initials = fallbackName.trim()
                    .split(" ")
                    .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                    .take(2)
                    .joinToString("")
                    .ifBlank { "?" }
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

fun openInCustomTab(context: Context, url: String) {
    try {
        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(context, Uri.parse(url))
    } catch (_: Exception) {
    }
}
