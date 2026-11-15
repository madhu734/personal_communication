package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.MuteDurationOption
import com.example.model.UserRecord
import com.example.network.PocketBaseApiClient
import com.example.storage.SessionAndMuteStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches Apun Ka Communication`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Apun Ka Communication", appName)
    }

    @Test
    fun `installation deviceId remains stable across session revocation while credentials clear`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SessionAndMuteStore(context, prefsName = "test_prefs_device_id")
        val firstDeviceId = store.deviceId
        assertTrue(firstDeviceId.length in 24..80)

        store.saveSession(
            token = "pb_jwt_token_123",
            user = UserRecord(id = "u_1", name = "Madhu", email = "madhu@tmadhu.me")
        )
        assertEquals("pb_jwt_token_123", store.getAuthToken())
        assertNotNull(store.getUserRecord())

        // Simulate auth revocation / sign-out
        store.clearAuthSession()
        assertEquals("", store.getAuthToken())
        assertNull(store.getUserRecord())
        // Device UUID must stay identical
        assertEquals(firstDeviceId, store.deviceId)
    }

    @Test
    fun `third distinct device signs out only the oldest device and auth-refresh preserves sign-in order`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store1 = SessionAndMuteStore(context, prefsName = "test_two_device_limit_1").also { it.clearAuthSession() }
        val store2 = SessionAndMuteStore(context, prefsName = "test_two_device_limit_2").also { it.clearAuthSession() }
        val store3 = SessionAndMuteStore(context, prefsName = "test_two_device_limit_3").also { it.clearAuthSession() }

        assertNotEquals(store1.deviceId, store2.deviceId)
        assertNotEquals(store2.deviceId, store3.deviceId)

        // Simulated backend tracking at most 2 active devices in strict sign-in order
        val activeDevicesBySignInOrder = mutableListOf<String>()
        val tokenToDevice = mutableMapOf<String, String>()
        var tokenCounter = 0

        val backendInterceptor = Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val deviceHeader = req.header("X-App-Device").orEmpty()
            val authHeader = req.header("Authorization").orEmpty()
            val jsonMedia = "application/json; charset=utf-8".toMediaType()

            fun buildResponse(code: Int, bodyJson: String): Response =
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Unauthorized")
                    .body(bodyJson.toResponseBody(jsonMedia))
                    .build()

            when (path) {
                "/api/collections/users/auth-with-password", "/api/app/account/register" -> {
                    require(deviceHeader.isNotBlank()) { "Missing X-App-Device header on sign-in/signup" }
                    activeDevicesBySignInOrder.remove(deviceHeader)
                    activeDevicesBySignInOrder.add(deviceHeader)
                    // Enforce 2-device limit: evict oldest device by sign-in order
                    while (activeDevicesBySignInOrder.size > 2) {
                        val evictedDevice = activeDevicesBySignInOrder.removeAt(0)
                        val tokensToRemove = tokenToDevice.filterValues { it == evictedDevice }.keys.toList()
                        tokensToRemove.forEach { tokenToDevice.remove(it) }
                    }
                    tokenCounter += 1
                    val newToken = "jwt_token_$tokenCounter"
                    tokenToDevice[newToken] = deviceHeader
                    val respObj = JSONObject().apply {
                        put("token", newToken)
                        put("record", JSONObject().apply {
                            put("id", "user_madhu")
                            put("name", "Madhu")
                            put("email", "madhu@tmadhu.me")
                            put("verified", true)
                        })
                    }
                    buildResponse(200, respObj.toString())
                }

                "/api/collections/users/auth-refresh" -> {
                    val dev = tokenToDevice[authHeader]
                    if (dev == null || dev !in activeDevicesBySignInOrder) {
                        buildResponse(401, JSONObject().put("message", "Session ended").toString())
                    } else {
                        // Refreshing a token issues a new JWT for the same device WITHOUT changing activeDevicesBySignInOrder
                        tokenCounter += 1
                        val refreshedToken = "jwt_refreshed_$tokenCounter"
                        tokenToDevice.remove(authHeader)
                        tokenToDevice[refreshedToken] = dev
                        val respObj = JSONObject().apply {
                            put("token", refreshedToken)
                            put("record", JSONObject().apply {
                                put("id", "user_madhu")
                                put("name", "Madhu")
                                put("email", "madhu@tmadhu.me")
                                put("verified", true)
                            })
                        }
                        buildResponse(200, respObj.toString())
                    }
                }

                "/api/app/account/session" -> {
                    val dev = tokenToDevice[authHeader]
                    if (dev != null && dev == deviceHeader && dev in activeDevicesBySignInOrder) {
                        buildResponse(200, JSONObject().put("active", true).put("deviceId", dev).toString())
                    } else {
                        buildResponse(401, JSONObject().put("message", "Session ended").toString())
                    }
                }

                else -> buildResponse(404, "{}")
            }
        }

        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor(backendInterceptor)
            .build()

        val client1 = PocketBaseApiClient(store1, mockHttpClient)
        val client2 = PocketBaseApiClient(store2, mockHttpClient)
        val client3 = PocketBaseApiClient(store3, mockHttpClient)

        // 1. Device 1 signs in (oldest)
        val auth1 = client1.signInWithPassword("madhu@tmadhu.me", "password123")
        store1.saveSession(auth1.token, auth1.user)
        assertTrue(client1.validateCurrentSession())

        // 2. Device 2 signs in (second)
        val auth2 = client2.signInWithPassword("madhu@tmadhu.me", "password123")
        store2.saveSession(auth2.token, auth2.user)
        assertTrue(client1.validateCurrentSession())
        assertTrue(client2.validateCurrentSession())

        // 3. Device 1 refreshes its token — refreshing MUST NOT change the sign-in eviction order
        val refreshed1 = client1.refreshAuth()
        store1.saveSession(refreshed1.token, refreshed1.user)
        assertTrue(client1.validateCurrentSession())

        // 4. Device 3 signs in — must evict ONLY Device 1 (the oldest by sign-in order), leaving Device 2 and Device 3 active
        val auth3 = client3.signInWithPassword("madhu@tmadhu.me", "password123")
        store3.saveSession(auth3.token, auth3.user)

        assertFalse("Oldest device (Device 1) must be signed out", client1.validateCurrentSession())
        assertTrue("Device 1 isSessionTrulyRevoked must be true", client1.isSessionTrulyRevoked())
        assertTrue("Second device (Device 2) must remain signed in", client2.validateCurrentSession())
        assertTrue("Newest device (Device 3) must be signed in", client3.validateCurrentSession())

        // When Device 1 detects its session ended, local credentials are cleared while keeping deviceId
        val dev1IdBeforeCleanup = store1.deviceId
        store1.clearAuthSession()
        assertEquals("", store1.getAuthToken())
        assertNull(store1.getUserRecord())
        assertEquals(dev1IdBeforeCleanup, store1.deviceId)
    }

    @Test
    fun `existing chat history loads immediately after sign-in without any unlock code`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SessionAndMuteStore(context, prefsName = "test_direct_history_prefs").also {
            it.clearAuthSession()
        }

        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                val jsonMedia = "application/json; charset=utf-8".toMediaType()
                val body = when (path) {
                    "/api/collections/users/auth-with-password" -> JSONObject().apply {
                        put("token", "valid_token_abc")
                        put("record", JSONObject().apply {
                            put("id", "u_self")
                            put("name", "Madhu")
                            put("email", "madhu@tmadhu.me")
                        })
                    }.toString()

                    "/api/app/inbox" -> JSONObject().apply {
                        put("chats", JSONArray().put(
                            JSONObject().apply {
                                put("id", "conv_existing_1")
                                put("peer", JSONObject().apply {
                                    put("id", "u_friend")
                                    put("name", "Rahim")
                                    put("avatar", "")
                                })
                            }
                        ))
                        put("requests", JSONArray())
                    }.toString()

                    "/api/collections/messages/records" -> JSONObject().apply {
                        put("items", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", "msg_old_1")
                                put("conversation", "conv_existing_1")
                                put("sender", "u_friend")
                                put("text", "Earlier message before this device signed in")
                                put("kind", "text")
                                put("created", "2026-11-10T08:00:00.000Z")
                            })
                            put(JSONObject().apply {
                                put("id", "msg_old_2")
                                put("conversation", "conv_existing_1")
                                put("sender", "u_self")
                                put("text", "Replying right away without history lock")
                                put("kind", "text")
                                put("created", "2026-11-10T08:01:00.000Z")
                            })
                        })
                    }.toString()

                    else -> "{}"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(jsonMedia))
                    .build()
            }
            .build()

        val apiClient = PocketBaseApiClient(store, mockHttpClient)
        val auth = apiClient.signInWithPassword("madhu@tmadhu.me", "password123")
        store.saveSession(auth.token, auth.user)

        val inbox = apiClient.fetchInbox()
        assertEquals(1, inbox.chats.size)
        assertEquals("conv_existing_1", inbox.chats.first().id)

        val messages = apiClient.fetchMessages(conversationId = "conv_existing_1")
        assertEquals(2, messages.size)
        assertEquals("Earlier message before this device signed in", messages[0].text)
        assertEquals("Replying right away without history lock", messages[1].text)
    }

    @Test
    fun `backup 4-digit PIN unlocks when biometrics fail, rate-limits failed attempts, and requires valid session`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SessionAndMuteStore(context, prefsName = "test_backup_pin_prefs").also {
            it.clearAuthSession()
        }
        val t0 = 2_000_000_000_000L

        // Save an active PocketBase session
        store.saveSession(
            token = "active_pb_session_jwt",
            user = UserRecord(id = "u_1", name = "Madhu", email = "madhu@tmadhu.me")
        )

        // 1. Validate 4-digit PIN format & confirmation matching before completing biometric setup
        assertFalse("3-digit PIN must be rejected", store.setBackupPin("123", "123"))
        assertFalse("Non-numeric PIN must be rejected", store.setBackupPin("12a4", "12a4"))
        assertFalse("Mismatched confirmation must be rejected", store.setBackupPin("2580", "2581"))
        assertFalse(store.hasBackupPin())

        assertTrue("Valid matching 4-digit PIN must succeed", store.setBackupPin("2580", "2580"))
        assertTrue(store.hasBackupPin())
        store.isBiometricAppLockEnabled = true

        // Simulate 30 minutes elapsed so App Lock is required and biometrics are unavailable/fail
        store.recordBiometricUnlockSuccess(nowEpochMs = t0)
        val lockTime = t0 + SessionAndMuteStore.BIOMETRIC_RELOCK_INTERVAL_MS
        assertTrue(store.isBiometricUnlockRequired(nowEpochMs = lockTime))

        // 2. Wrong PIN attempts decrement remaining attempts and trigger rate-limiting after 5 failures
        for (attempt in 1..4) {
            val wrongRes = store.verifyBackupPin("9999", nowEpochMs = lockTime)
            assertFalse(wrongRes.success)
            assertEquals(0, wrongRes.remainingLockoutSeconds)
            assertEquals(5 - attempt, wrongRes.attemptsRemaining)
            assertTrue("App must remain locked after wrong PIN", store.isBiometricUnlockRequired(nowEpochMs = lockTime))
        }

        // 5th failed attempt triggers a 30-second lockout cooldown
        val fifthFail = store.verifyBackupPin("9999", nowEpochMs = lockTime)
        assertFalse(fifthFail.success)
        assertEquals(30, fifthFail.remainingLockoutSeconds)
        assertEquals(0, fifthFail.attemptsRemaining)

        // Even the correct PIN is rejected while rate-limit lockout is active (+10s into 30s cooldown)
        val duringLockout = store.verifyBackupPin("2580", nowEpochMs = lockTime + 10_000L)
        assertFalse(duringLockout.success)
        assertEquals(20, duringLockout.remainingLockoutSeconds)

        // 3. After the 30-second lockout expires, the correct 4-digit backup PIN unlocks the app
        val afterLockoutTime = lockTime + 31_000L
        val validUnlock = store.verifyBackupPin("2580", nowEpochMs = afterLockoutTime)
        assertTrue(validUnlock.success)
        assertEquals(0, validUnlock.remainingLockoutSeconds)
        assertFalse(validUnlock.sessionInvalid)
        assertFalse("App must be unlocked for the next 30 minutes", store.isBiometricUnlockRequired(nowEpochMs = afterLockoutTime + 60_000L))

        // 4. If the PocketBase session ends / is revoked, the PIN must NOT unlock the app
        store.clearAuthSession()
        val afterSessionEnded = store.verifyBackupPin("2580", nowEpochMs = afterLockoutTime + 120_000L)
        assertFalse(afterSessionEnded.success)
        assertTrue("PIN must report sessionInvalid when PocketBase session has ended", afterSessionEnded.sessionInvalid)
    }

    @Test
    fun `conversation mute store scopes to account and chat and auto-unmutes when duration expires`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SessionAndMuteStore(context, prefsName = "test_mute_prefs")
        val startMs = 1_800_000_000_000L

        // Mute conv_1 for 1 hour for user_1
        store.setConversationMute("user_1", "conv_1", MuteDurationOption.ONE_HOUR, nowEpochMs = startMs)

        // Muted for user_1 at +30 minutes, but NOT muted for user_2
        assertTrue(store.isConversationMuted("user_1", "conv_1", nowEpochMs = startMs + 1_800_000L))
        assertFalse(store.isConversationMuted("user_2", "conv_1", nowEpochMs = startMs + 1_800_000L))

        // Auto-unmutes at +60 minutes (3_600_000L)
        assertFalse(store.isConversationMuted("user_1", "conv_1", nowEpochMs = startMs + 3_600_000L))

        // Always mute stays active indefinitely until manually unmuted
        store.setConversationMute("user_1", "conv_2", MuteDurationOption.ALWAYS, nowEpochMs = startMs)
        assertTrue(store.isConversationMuted("user_1", "conv_2", nowEpochMs = startMs + 999_999_999_000L))
        store.clearConversationMute("user_1", "conv_2")
        assertFalse(store.isConversationMuted("user_1", "conv_2", nowEpochMs = startMs + 999_999_999_000L))
    }

    @Test
    fun `biometric app lock enforces 30-minute relock timer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SessionAndMuteStore(context, prefsName = "test_biometric_timer_prefs")
        val t0 = 1_900_000_000_000L

        store.isBiometricAppLockEnabled = false
        assertFalse(store.isBiometricUnlockRequired(nowEpochMs = t0))

        // Enable lock and record unlock at t0
        store.isBiometricAppLockEnabled = true
        store.recordBiometricUnlockSuccess(nowEpochMs = t0)

        // Inside 30 minutes (+15 mins) -> NOT required
        assertFalse(store.isBiometricUnlockRequired(nowEpochMs = t0 + 15 * 60 * 1000L))
        // Inside 30 minutes (+29 mins 59s) -> NOT required
        assertFalse(store.isBiometricUnlockRequired(nowEpochMs = t0 + 30 * 60 * 1000L - 1000L))
        // At or after 30 minutes (+30 mins) -> Unlock required again!
        assertTrue(store.isBiometricUnlockRequired(nowEpochMs = t0 + 30 * 60 * 1000L))
    }

    @Test
    fun `ongoing and incoming call notifications build and post safely without CallStyle IllegalArgumentException`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = com.example.model.ActiveCallUiState(
            callId = "call_123",
            conversationId = "conv_1",
            peer = com.example.model.PeerSummary(id = "u_2", name = "Friend"),
            kind = com.example.model.CallKind.AUDIO
        )
        val ongoingNotif = com.example.call.SystemNotificationAndFcmManager.showOngoingOrIncomingCallNotification(
            context = context,
            state = state,
            isIncoming = false
        )
        assertNotNull(ongoingNotif)

        val incomingNotif = com.example.call.SystemNotificationAndFcmManager.showOngoingOrIncomingCallNotification(
            context = context,
            state = state,
            isIncoming = true
        )
        assertNotNull(incomingNotif)
    }
}
