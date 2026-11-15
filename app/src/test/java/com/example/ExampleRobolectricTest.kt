package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.MuteDurationOption
import com.example.model.UserRecord
import com.example.storage.SessionAndMuteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val store = SessionAndMuteStore(context)
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
    fun `conversation mute store scopes to account and chat and auto-unmutes when duration expires`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SessionAndMuteStore(context)
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
        val store = SessionAndMuteStore(context)
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
}
