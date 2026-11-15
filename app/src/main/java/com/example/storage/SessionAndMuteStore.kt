package com.example.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.model.MuteDurationOption
import com.example.model.UserRecord
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PinVerificationResult(
    val success: Boolean,
    val remainingLockoutSeconds: Int = 0,
    val attemptsRemaining: Int = SessionAndMuteStore.MAX_PIN_ATTEMPTS,
    val sessionInvalid: Boolean = false
)

/**
 * Manages:
 * 1. Persistent random installation UUID (`X-App-Device` & call `deviceId`), excluded from backup/restore.
 * 2. Android Keystore AES-GCM encrypted session token & signed-in UserRecord.
 * 3. Account- and conversation-scoped timed mute settings (`1 hour`, `2 hours`, `8 hours`, `1 day`, `Always`) with auto-expiry.
 * 4. Biometric App Lock with a 30-minute relock timer and mandatory Keystore-protected 4-digit backup PIN with rate-limiting.
 */
class SessionAndMuteStore(
    context: Context,
    prefsName: String = PREFS_NAME
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    val deviceId: String
        @Synchronized get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank() && existing.length in 24..80) {
                return existing
            }
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.trimEnd('/') ?: DEFAULT_BASE_URL
        set(value) {
            val sanitized = value.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
            prefs.edit().putString(KEY_BASE_URL, sanitized).apply()
        }

    var webAppUrl: String
        get() = prefs.getString(KEY_WEB_URL, DEFAULT_WEB_URL)?.trimEnd('/') ?: DEFAULT_WEB_URL
        set(value) {
            val sanitized = value.trim().trimEnd('/').ifBlank { DEFAULT_WEB_URL }
            prefs.edit().putString(KEY_WEB_URL, sanitized).apply()
        }

    var registeredPushDeviceId: String
        get() = prefs.getString(KEY_PUSH_RECORD_ID, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PUSH_RECORD_ID, value).apply()
        }

    var pendingFcmToken: String
        get() = prefs.getString(KEY_PENDING_FCM_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PENDING_FCM_TOKEN, value).apply()
        }

    // ---------------- 30-Minute Biometric App Lock & Keystore 4-Digit Backup PIN ----------------

    var isBiometricAppLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()
        }

    var lastBiometricUnlockMs: Long
        get() = prefs.getLong(KEY_LAST_BIOMETRIC_UNLOCK_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_BIOMETRIC_UNLOCK_MS, value).apply()
        }

    /**
     * Returns true if Biometric App Lock is enabled AND at least 30 minutes have elapsed since the
     * last successful biometric or backup PIN authentication.
     */
    fun isBiometricUnlockRequired(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (!isBiometricAppLockEnabled) return false
        val lastUnlock = lastBiometricUnlockMs
        if (lastUnlock <= 0L) return true
        return (nowEpochMs - lastUnlock) >= BIOMETRIC_RELOCK_INTERVAL_MS
    }

    fun recordBiometricUnlockSuccess(nowEpochMs: Long = System.currentTimeMillis()) {
        lastBiometricUnlockMs = nowEpochMs
    }

    fun isValidPinFormat(pin: String): Boolean {
        val trimmed = pin.trim()
        return trimmed.length == 4 && trimmed.all { it.isDigit() }
    }

    fun hasBackupPin(): Boolean {
        val raw = prefs.getString(KEY_BACKUP_PIN_VERIFIER, null) ?: return false
        val decrypted = decryptString(raw)
        return decrypted.contains(":") && decrypted.length > 32
    }

    /**
     * Sets and confirms a 4-digit backup PIN, storing a salted SHA-256 verifier encrypted with the
     * Android Keystore AES-GCM key.
     */
    fun setBackupPin(pin: String, confirmPin: String): Boolean {
        val cleanPin = pin.trim()
        val cleanConfirm = confirmPin.trim()
        if (!isValidPinFormat(cleanPin) || cleanPin != cleanConfirm) {
            return false
        }
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = saltBytes.toHexString()
        val hashHex = computePinHashHex(saltHex, cleanPin)
        val encryptedVerifier = encryptString("$saltHex:$hashHex")
        prefs.edit()
            .putString(KEY_BACKUP_PIN_VERIFIER, encryptedVerifier)
            .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_LOCKOUT_UNTIL_MS, 0L)
            .apply()
        return true
    }

    fun clearBackupPinAndBiometricLock() {
        prefs.edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .putLong(KEY_LAST_BIOMETRIC_UNLOCK_MS, 0L)
            .remove(KEY_BACKUP_PIN_VERIFIER)
            .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_LOCKOUT_UNTIL_MS, 0L)
            .apply()
    }

    fun getPinLockoutRemainingSeconds(nowEpochMs: Long = System.currentTimeMillis()): Int {
        val lockoutUntil = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL_MS, 0L)
        if (lockoutUntil <= nowEpochMs) return 0
        return (((lockoutUntil - nowEpochMs) + 999L) / 1000L).toInt().coerceAtLeast(1)
    }

    /**
     * Verifies the 4-digit backup PIN locally using the Keystore-protected verifier with rate-limiting.
     * The PIN unlocks the app ONLY while its PocketBase session is valid (`getAuthToken().isNotBlank()`).
     */
    @Synchronized
    fun verifyBackupPin(
        pin: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PinVerificationResult {
        if (getAuthToken().isBlank() || getUserRecord() == null) {
            return PinVerificationResult(
                success = false,
                remainingLockoutSeconds = 0,
                attemptsRemaining = 0,
                sessionInvalid = true
            )
        }

        val activeLockoutSecs = getPinLockoutRemainingSeconds(nowEpochMs)
        if (activeLockoutSecs > 0) {
            return PinVerificationResult(
                success = false,
                remainingLockoutSeconds = activeLockoutSecs,
                attemptsRemaining = 0,
                sessionInvalid = false
            )
        }

        val cleanPin = pin.trim()
        val storedEncrypted = prefs.getString(KEY_BACKUP_PIN_VERIFIER, null).orEmpty()
        val decrypted = decryptString(storedEncrypted)
        val parts = decrypted.split(":")
        val isMatch = if (isValidPinFormat(cleanPin) && parts.size == 2) {
            val saltHex = parts[0]
            val expectedHashHex = parts[1]
            val candidateHashHex = computePinHashHex(saltHex, cleanPin)
            MessageDigest.isEqual(
                expectedHashHex.toByteArray(Charsets.UTF_8),
                candidateHashHex.toByteArray(Charsets.UTF_8)
            )
        } else {
            false
        }

        if (isMatch) {
            prefs.edit()
                .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
                .putLong(KEY_PIN_LOCKOUT_UNTIL_MS, 0L)
                .apply()
            recordBiometricUnlockSuccess(nowEpochMs)
            return PinVerificationResult(
                success = true,
                remainingLockoutSeconds = 0,
                attemptsRemaining = MAX_PIN_ATTEMPTS,
                sessionInvalid = false
            )
        }

        val failedCount = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0) + 1
        if (failedCount >= MAX_PIN_ATTEMPTS) {
            val tier = (failedCount - MAX_PIN_ATTEMPTS).coerceIn(0, 4)
            val lockoutDurationMs = (PIN_LOCKOUT_DURATION_MS * (1L shl tier))
                .coerceAtMost(MAX_PIN_LOCKOUT_DURATION_MS)
            val lockoutUntil = nowEpochMs + lockoutDurationMs
            prefs.edit()
                .putInt(KEY_PIN_FAILED_ATTEMPTS, failedCount)
                .putLong(KEY_PIN_LOCKOUT_UNTIL_MS, lockoutUntil)
                .apply()
            val secs = ((lockoutDurationMs + 999L) / 1000L).toInt()
            return PinVerificationResult(
                success = false,
                remainingLockoutSeconds = secs,
                attemptsRemaining = 0,
                sessionInvalid = false
            )
        } else {
            prefs.edit()
                .putInt(KEY_PIN_FAILED_ATTEMPTS, failedCount)
                .apply()
            return PinVerificationResult(
                success = false,
                remainingLockoutSeconds = 0,
                attemptsRemaining = (MAX_PIN_ATTEMPTS - failedCount).coerceAtLeast(0),
                sessionInvalid = false
            )
        }
    }

    private fun computePinHashHex(saltHex: String, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$deviceId:$saltHex:$pin".toByteArray(Charsets.UTF_8)
        return digest.digest(input).toHexString()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    // ---------------- Session Storage ----------------

    fun saveSession(token: String, user: UserRecord) {
        val encryptedToken = encryptString(token)
        val userJson = JSONObject().apply {
            put("id", user.id)
            put("name", user.name)
            put("email", user.email)
            put("avatar", user.avatar)
            put("verified", user.verified)
        }.toString()
        val encryptedUser = encryptString(userJson)
        prefs.edit()
            .putString(KEY_AUTH_TOKEN, encryptedToken)
            .putString(KEY_USER_RECORD, encryptedUser)
            .apply()
    }

    fun getAuthToken(): String {
        val raw = prefs.getString(KEY_AUTH_TOKEN, null) ?: return ""
        return decryptString(raw)
    }

    fun getUserRecord(): UserRecord? {
        val raw = prefs.getString(KEY_USER_RECORD, null) ?: return null
        val jsonStr = decryptString(raw)
        if (jsonStr.isBlank()) return null
        return try {
            val obj = JSONObject(jsonStr)
            UserRecord(
                id = obj.optString("id"),
                name = obj.optString("name"),
                email = obj.optString("email"),
                avatar = obj.optString("avatar"),
                verified = obj.optBoolean("verified", true)
            ).takeIf { it.id.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clears auth session, user-scoped credentials, and session-bound app lock / PIN state while
     * keeping the installation `deviceId`, server URL, and per-account mute preferences intact.
     */
    fun clearAuthSession() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_USER_RECORD)
            .remove(KEY_PUSH_RECORD_ID)
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .putLong(KEY_LAST_BIOMETRIC_UNLOCK_MS, 0L)
            .remove(KEY_BACKUP_PIN_VERIFIER)
            .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_LOCKOUT_UNTIL_MS, 0L)
            .apply()
    }

    // ---------------- Timed Conversation Mute Store ----------------

    fun setConversationMute(
        userId: String,
        conversationId: String,
        option: MuteDurationOption,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Long {
        val untilMs = option.computeMuteUntilEpochMs(nowEpochMs)
        setConversationMuteUntil(userId, conversationId, untilMs)
        return untilMs
    }

    fun setConversationMuteUntil(
        userId: String,
        conversationId: String,
        muteUntilEpochMs: Long
    ) {
        if (userId.isBlank() || conversationId.isBlank()) return
        prefs.edit().putLong(muteKey(userId, conversationId), muteUntilEpochMs).apply()
    }

    fun clearConversationMute(userId: String, conversationId: String) {
        if (userId.isBlank() || conversationId.isBlank()) return
        prefs.edit().remove(muteKey(userId, conversationId)).apply()
    }

    /**
     * Returns the active `muteUntilEpochMs` (`Long.MAX_VALUE` for Always), or `null` if not muted
     * or if the mute duration has already expired (auto-unmuting on expiry).
     */
    fun getActiveMuteUntil(
        userId: String,
        conversationId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Long? {
        if (userId.isBlank() || conversationId.isBlank()) return null
        val key = muteKey(userId, conversationId)
        if (!prefs.contains(key)) return null
        val untilMs = prefs.getLong(key, 0L)
        if (untilMs <= 0L) {
            prefs.edit().remove(key).apply()
            return null
        }
        if (untilMs != Long.MAX_VALUE && nowEpochMs >= untilMs) {
            prefs.edit().remove(key).apply()
            return null
        }
        return untilMs
    }

    fun isConversationMuted(
        userId: String,
        conversationId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = getActiveMuteUntil(userId, conversationId, nowEpochMs) != null

    private fun muteKey(userId: String, conversationId: String): String =
        "mute_${userId}_${conversationId}"

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existingKey = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
            if (existingKey != null) return existingKey

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (_: Exception) {
            null
        }
    }

    private fun encryptString(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val secretKey = getOrCreateSecretKey()
            ?: return "PLAIN:" + Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "GCM:$ivB64:$cipherB64"
        } catch (_: Exception) {
            "PLAIN:" + Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    private fun decryptString(stored: String): String {
        if (stored.isBlank()) return ""
        return try {
            if (stored.startsWith("PLAIN:")) {
                val b64 = stored.removePrefix("PLAIN:")
                String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)
            } else if (stored.startsWith("GCM:")) {
                val parts = stored.split(":")
                if (parts.size != 3) return ""
                val secretKey = getOrCreateSecretKey() ?: return ""
                val iv = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipherBytes = Base64.decode(parts[2], Base64.NO_WRAP)
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val PREFS_NAME = "apun_ka_secure_session_prefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "apun_ka_session_aes_key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

        private const val KEY_DEVICE_ID = "installation_device_id"
        private const val KEY_BASE_URL = "pb_base_url"
        private const val KEY_WEB_URL = "web_app_url"
        private const val KEY_AUTH_TOKEN = "encrypted_auth_token"
        private const val KEY_USER_RECORD = "encrypted_user_record"
        private const val KEY_PUSH_RECORD_ID = "registered_push_device_id"
        private const val KEY_PENDING_FCM_TOKEN = "pending_fcm_token"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_app_lock_enabled"
        private const val KEY_LAST_BIOMETRIC_UNLOCK_MS = "last_biometric_unlock_ms"
        private const val KEY_BACKUP_PIN_VERIFIER = "encrypted_backup_pin_verifier"
        private const val KEY_PIN_FAILED_ATTEMPTS = "backup_pin_failed_attempts"
        private const val KEY_PIN_LOCKOUT_UNTIL_MS = "backup_pin_lockout_until_ms"

        const val MAX_PIN_ATTEMPTS = 5
        const val PIN_LOCKOUT_DURATION_MS = 30_000L // 30 seconds initial lockout
        const val MAX_PIN_LOCKOUT_DURATION_MS = 300_000L // 5 minutes cap
        const val BIOMETRIC_RELOCK_INTERVAL_MS = 30L * 60L * 1000L // 30 minutes
        const val DEFAULT_BASE_URL = "https://pb.tmadhu.me"
        const val DEFAULT_WEB_URL = "https://chat.tmadhu.me"
    }
}
