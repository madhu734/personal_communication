package com.example

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.call.CallCoordinator
import com.example.model.CallKind
import com.example.model.CallPhase
import com.example.model.PeerSummary
import com.example.model.UserRecord
import com.example.network.PocketBaseApiClient
import com.example.storage.SessionAndMuteStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CallLifecycleTest {
    @Test
    fun cancelledOutgoingRequestCannotReviveTheCall() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SessionAndMuteStore(context, prefsName = "call_cancel_regression")
        store.saveSession("test-token", UserRecord("sender", "Sender", "sender@example.test"))
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val serverEnded = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val json = if (request.url.encodedPath == "/api/app/calls") {
                requestStarted.countDown()
                check(releaseResponse.await(5, TimeUnit.SECONDS))
                """{"id":"late_call","conversation":"chat","caller":"sender","receiver":"peer","kind":"audio","status":"ringing"}"""
            } else {
                if (request.url.encodedPath == "/api/app/calls/late_call/end") serverEnded.countDown()
                "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val coordinator = CallCoordinator(context, store, PocketBaseApiClient(store, client))
        try {
            coordinator.startOutgoingCall("chat", PeerSummary("peer", "Peer"), CallKind.AUDIO)
            awaitCondition { requestStarted.count == 0L }
            coordinator.declineOrHangUpCall()
            awaitCondition { coordinator.uiState.value.phase == CallPhase.ENDED }
            releaseResponse.countDown()
            awaitCondition { serverEnded.count == 0L }
            assertTrue(coordinator.uiState.value.phase in listOf(CallPhase.ENDED, CallPhase.IDLE))
        } finally {
            releaseResponse.countDown()
            store.clearAuthSession()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Call lifecycle condition timed out", condition())
    }
}
