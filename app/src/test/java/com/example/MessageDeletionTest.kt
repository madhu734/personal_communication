package com.example

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.model.ChatMessage
import com.example.model.UserRecord
import com.example.network.PocketBaseApiClient
import com.example.network.PocketBaseRealtimeClient
import com.example.network.RealtimeRecordEvent
import com.example.repository.AppViewModel
import com.example.repository.RetryableAction
import com.example.storage.SessionAndMuteStore
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.InCallChatSheetContent
import com.example.ui.screens.MessageActionAndReactionSheet
import com.example.ui.theme.ApunKaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageDeletionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(20)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Timed out waiting for condition", predicate())
    }

    private fun createTestBackend(
        seedMessages: List<JSONObject>,
        failDeleteForMeCount: AtomicInteger = AtomicInteger(0),
        failDeleteForEveryoneCount: AtomicInteger = AtomicInteger(0),
        hidesCollectionForbidden: Boolean = false
    ): FakeMessagingBackend {
        return FakeMessagingBackend(
            seedMessages = seedMessages,
            failDeleteForMeCount = failDeleteForMeCount,
            failDeleteForEveryoneCount = failDeleteForEveryoneCount,
            hidesCollectionForbidden = hidesCollectionForbidden
        )
    }

    private fun buildMessageJson(
        id: String,
        conversation: String = "conv_1",
        sender: String,
        text: String,
        created: String,
        kind: String = "text",
        attachment: String = "",
        attachmentBytes: Long = 0L,
        replyTo: String = "",
        inCall: Boolean = false,
        deletedAt: String? = null
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("conversation", conversation)
        put("sender", sender)
        put("text", text)
        put("client_message_id", "cid_$id")
        put("kind", kind)
        put("attachment", attachment)
        put("attachment_bytes", attachmentBytes)
        put("reply_to", replyTo)
        put("in_call", inCall)
        put("created", created)
        put("updated", created)
        if (deletedAt == null) {
            put("deleted_at", JSONObject.NULL)
        } else {
            put("deleted_at", deletedAt)
        }
    }

    private fun createViewModel(
        prefsName: String,
        user: UserRecord,
        token: String,
        client: OkHttpClient
    ): AppViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = SessionAndMuteStore(app, prefsName = prefsName).apply {
            clearAuthSession()
            saveSession(token, user)
        }
        val api = PocketBaseApiClient(store, client)
        val rt = PocketBaseRealtimeClient(store, enableNetworkSse = false)
        return AppViewModel(
            application = app,
            sessionStore = store,
            apiClient = api,
            realtimeClient = rt,
            autoInitialize = false
        )
    }

    @Test
    fun `action sheet shows Delete for me on all saved messages and Delete for everyone only on own non-deleted messages`() {
        val sentActive = ChatMessage(
            id = "m_sent_active",
            conversation = "conv_1",
            sender = "u_me",
            text = "My active message",
            clientMessageId = "cid_sent_active",
            created = "2026-11-16T01:00:00.000Z"
        )
        val receivedActive = ChatMessage(
            id = "m_recv_active",
            conversation = "conv_1",
            sender = "u_peer",
            text = "Peer active message",
            clientMessageId = "cid_recv_active",
            created = "2026-11-16T01:01:00.000Z"
        )
        val sentDeleted = ChatMessage(
            id = "m_sent_deleted",
            conversation = "conv_1",
            sender = "u_me",
            text = "",
            clientMessageId = "cid_sent_deleted",
            created = "2026-11-16T01:02:00.000Z",
            deletedAt = "2026-11-16T01:03:00.000Z"
        )

        val targetState = androidx.compose.runtime.mutableStateOf(sentActive to true)

        composeRule.setContent {
            ApunKaTheme {
                val (msg, isOwn) = targetState.value
                MessageActionAndReactionSheet(
                    message = msg,
                    isOwnMessage = isOwn,
                    onSelectReaction = {},
                    onOpenCustomEmoji = {},
                    onReply = {},
                    onEdit = {},
                    onDeleteForMe = {},
                    onDeleteForEveryone = {}
                )
            }
        }

        // 1. Sent non-deleted: both Delete for me and Delete for everyone + Reply + Edit
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("action_delete_for_me").assertIsDisplayed()
        composeRule.onNodeWithTag("action_delete_for_everyone").assertIsDisplayed()
        composeRule.onNodeWithText("Reply").assertIsDisplayed()

        // 2. Received non-deleted: only Delete for me (NO Delete for everyone)
        composeRule.runOnUiThread {
            targetState.value = receivedActive to false
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("action_delete_for_me").assertIsDisplayed()
        composeRule.onAllNodesWithTag("action_delete_for_everyone").assertCountEquals(0)

        // 3. Deleted placeholder: ONLY Delete for me (no Delete for everyone, no Reply)
        composeRule.runOnUiThread {
            targetState.value = sentDeleted to true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("action_delete_for_me").assertIsDisplayed()
        composeRule.onAllNodesWithTag("action_delete_for_everyone").assertCountEquals(0)
        composeRule.onNodeWithText("Reply").assertDoesNotExist()
    }

    @Test
    fun `delete for me waits for server 204, supports retry on error, cleans cache, and syncs to second device live and on resume`(): Unit =
        runBlocking {
            val sender = UserRecord(id = "u_sender", name = "Madhu", email = "madhu@tmadhu.me")
            val failCounter = AtomicInteger(1)
            val backend = createTestBackend(
                seedMessages = listOf(
                    buildMessageJson(
                        id = "m_1",
                        sender = "u_peer",
                        text = "Secret note with attachment",
                        kind = "image",
                        attachment = "photo.jpg",
                        attachmentBytes = 1024L,
                        created = "2026-11-16T01:00:00.000Z"
                    ),
                    buildMessageJson(
                        id = "m_2",
                        sender = "u_sender",
                        text = "Keep me visible",
                        created = "2026-11-16T01:01:00.000Z"
                    )
                ),
                failDeleteForMeCount = failCounter
            )

            val device1Vm = createViewModel("del_me_dev1", sender, "tok_u_sender", backend.httpClient)
            val device2Vm = createViewModel("del_me_dev2", sender, "tok_u_sender", backend.httpClient)
            val device2ResumeVm = createViewModel("del_me_dev2_resume", sender, "tok_u_sender", backend.httpClient)

            device1Vm.refreshAllAuthenticatedData()
            device2Vm.refreshAllAuthenticatedData()
            device2ResumeVm.refreshAllAuthenticatedData()
            awaitCondition {
                device1Vm.uiState.value.messagesByConversation["conv_1"]?.size == 2 &&
                    device2Vm.uiState.value.messagesByConversation["conv_1"]?.size == 2 &&
                    device2ResumeVm.uiState.value.messagesByConversation["conv_1"]?.size == 2
            }

            // Create cached media file for m_1 to verify cleanup on hide
            val cacheDir = ApplicationProvider.getApplicationContext<Application>().cacheDir
            val cachedMedia = File(cacheDir, "media_m_1_photo.jpg").apply { writeText("cached-bytes") }
            assertTrue(cachedMedia.exists())

            val targetMsg = device1Vm.uiState.value.messagesByConversation["conv_1"]!!.first { it.id == "m_1" }

            // 1. First call fails -> message stays visible and error is retryable
            device1Vm.deleteMessageForMe(targetMsg)
            awaitCondition { device1Vm.uiState.value.retryableAction is RetryableAction.DeleteMessageForMe }
            assertEquals(2, device1Vm.uiState.value.messagesByConversation["conv_1"]!!.size)
            assertTrue("Cached file must remain until server confirms hide", cachedMedia.exists())

            // 2. Retry succeeds (204) -> m_1 removed from device 1 timeline, cache, and reactions; inbox preview updates
            device1Vm.retryLastFailedAction()
            awaitCondition {
                device1Vm.uiState.value.messagesByConversation["conv_1"]?.none { it.id == "m_1" } == true &&
                    device1Vm.uiState.value.retryableAction == null
            }
            assertEquals(listOf("m_2"), device1Vm.uiState.value.messagesByConversation["conv_1"]!!.map { it.id })
            assertFalse("Cached media for hidden message must be deleted", cachedMedia.exists())
            assertEquals("m_2", device1Vm.uiState.value.chats.first().lastMessage?.id)

            // Verify repeating Delete for me request safely succeeds if invoked again
            backend.apiCalls.clear()
            device1Vm.deleteMessageForMe(targetMsg)
            awaitCondition { backend.apiCalls.any { it == "DELETE /api/app/messages/m_1?scope=me" } }

            // 3. Live sync via message_hides/* create event reaches same account's second device
            device2Vm.realtimeClient.emitEventForTest(
                RealtimeRecordEvent(
                    topic = "message_hides/*",
                    collection = "message_hides",
                    action = "create",
                    record = JSONObject().apply {
                        put("id", "hide_1")
                        put("message", "m_1")
                        put("user", "u_sender")
                        put("conversation", "conv_1")
                    }
                )
            )
            awaitCondition {
                device2Vm.uiState.value.messagesByConversation["conv_1"]?.none { it.id == "m_1" } == true
            }

            // 4. Second device that missed the live SSE event still removes m_1 on app resume reconciliation
            assertEquals(2, device2ResumeVm.uiState.value.messagesByConversation["conv_1"]?.size)
            device2ResumeVm.checkBiometricLockStateOnResume()
            awaitCondition {
                device2ResumeVm.uiState.value.messagesByConversation["conv_1"]?.map { it.id } == listOf("m_2")
            }
        }

    @Test
    fun `delete for everyone replaces content with placeholder, keeps timeline position and replies, clears reactions and media, and reaches recipient live and on reconnect`(): Unit =
        runBlocking {
            val sender = UserRecord(id = "u_sender", name = "Madhu", email = "madhu@tmadhu.me")
            val recipient = UserRecord(id = "u_peer", name = "Rahim", email = "rahim@tmadhu.me")

            val failEveryone = AtomicInteger(1)
            val backend = createTestBackend(
                seedMessages = listOf(
                    buildMessageJson(
                        id = "m_1",
                        sender = "u_sender",
                        text = "First message",
                        created = "2026-11-16T01:00:00.000Z"
                    ),
                    buildMessageJson(
                        id = "m_2",
                        sender = "u_sender",
                        text = "Original photo caption",
                        kind = "image",
                        attachment = "pic.jpg",
                        attachmentBytes = 2048L,
                        created = "2026-11-16T01:01:00.000Z"
                    ),
                    buildMessageJson(
                        id = "m_3",
                        sender = "u_peer",
                        text = "Reply pointing to m_2",
                        replyTo = "m_2",
                        created = "2026-11-16T01:02:00.000Z"
                    )
                ),
                failDeleteForEveryoneCount = failEveryone
            )
            backend.reactions.add(
                JSONObject().apply {
                    put("id", "r_1")
                    put("conversation", "conv_1")
                    put("message", "m_2")
                    put("user", "u_peer")
                    put("emoji", "❤️")
                }
            )
            backend.reactions.add(
                JSONObject().apply {
                    put("id", "r_2")
                    put("conversation", "conv_1")
                    put("message", "m_3")
                    put("user", "u_sender")
                    put("emoji", "👍")
                }
            )

            val senderVm = createViewModel("del_all_sender", sender, "tok_u_sender", backend.httpClient)
            val recipientLiveVm = createViewModel("del_all_recv_live", recipient, "tok_u_peer", backend.httpClient)
            val recipientReconnectVm = createViewModel("del_all_recv_recon", recipient, "tok_u_peer", backend.httpClient)

            senderVm.refreshAllAuthenticatedData()
            senderVm.refreshConversationData("conv_1")
            recipientLiveVm.refreshAllAuthenticatedData()
            recipientLiveVm.refreshConversationData("conv_1")
            recipientReconnectVm.refreshAllAuthenticatedData()
            recipientReconnectVm.refreshConversationData("conv_1")

            awaitCondition {
                senderVm.uiState.value.messagesByConversation["conv_1"]?.size == 3 &&
                    senderVm.uiState.value.reactionsByConversation["conv_1"]?.size == 2 &&
                    recipientLiveVm.uiState.value.messagesByConversation["conv_1"]?.size == 3 &&
                    recipientReconnectVm.uiState.value.messagesByConversation["conv_1"]?.size == 3
            }

            val cacheDir = ApplicationProvider.getApplicationContext<Application>().cacheDir
            val cachedMedia = File(cacheDir, "media_m_2_pic.jpg").apply { writeText("secret-image-bytes") }
            val m2Before = senderVm.uiState.value.messagesByConversation["conv_1"]!!.first { it.id == "m_2" }

            // 1. Failing Delete for everyone preserves message & triggers RetryableAction
            senderVm.deleteMessageForEveryone(m2Before)
            awaitCondition { senderVm.uiState.value.retryableAction is RetryableAction.DeleteMessageForEveryone }
            val stillOriginal = senderVm.uiState.value.messagesByConversation["conv_1"]!!.first { it.id == "m_2" }
            assertFalse(stillOriginal.isDeleted)
            assertEquals("Original photo caption", stillOriginal.text)

            // 2. Retry succeeds -> 200 returned record has deleted_at, clears text/media/reactions, keeps ID & timeline order & reply m_3
            senderVm.retryLastFailedAction()
            awaitCondition {
                senderVm.uiState.value.messagesByConversation["conv_1"]
                    ?.firstOrNull { it.id == "m_2" }?.isDeleted == true
            }
            val senderMsgs = senderVm.uiState.value.messagesByConversation["conv_1"]!!
            assertEquals(listOf("m_1", "m_2", "m_3"), senderMsgs.map { it.id })
            val m2Deleted = senderMsgs[1]
            assertTrue(m2Deleted.isDeleted)
            assertEquals("", m2Deleted.text)
            assertEquals("", m2Deleted.attachment)
            assertFalse("Cached media for m_2 must be cleared", cachedMedia.exists())
            // Reaction on m_2 cleared while reaction on reply m_3 stays
            assertEquals(listOf("r_2"), senderVm.uiState.value.reactionsByConversation["conv_1"]!!.map { it.id })
            // Existing reply m_3 is preserved
            assertEquals("Reply pointing to m_2", senderMsgs[2].text)
            assertEquals("m_2", senderMsgs[2].replyTo)

            // 3. Shared delete reaches recipient LIVE via SSE messages/* update event
            val deletedJsonOnServer = backend.messages.first { it.getString("id") == "m_2" }
            recipientLiveVm.realtimeClient.emitEventForTest(
                RealtimeRecordEvent(
                    topic = "messages/*",
                    collection = "messages",
                    action = "update",
                    record = deletedJsonOnServer
                )
            )
            awaitCondition {
                recipientLiveVm.uiState.value.messagesByConversation["conv_1"]
                    ?.firstOrNull { it.id == "m_2" }?.isDeleted == true
            }
            val recvLiveMsgs = recipientLiveVm.uiState.value.messagesByConversation["conv_1"]!!
            assertEquals(listOf("m_1", "m_2", "m_3"), recvLiveMsgs.map { it.id })
            assertEquals("", recvLiveMsgs[1].text)
            assertEquals("Reply pointing to m_2", recvLiveMsgs[2].text)

            // Stale non-deleted SSE event must NOT restore original content on recipient
            recipientLiveVm.realtimeClient.emitEventForTest(
                RealtimeRecordEvent(
                    topic = "messages/*",
                    collection = "messages",
                    action = "update",
                    record = buildMessageJson(
                        id = "m_2",
                        sender = "u_sender",
                        text = "STALE RESTORED TEXT",
                        created = "2026-11-16T01:01:00.000Z",
                        deletedAt = null
                    )
                )
            )
            awaitCondition {
                recipientLiveVm.uiState.value.messagesByConversation["conv_1"]
                    ?.firstOrNull { it.id == "m_2" }?.isDeleted == true
            }
            assertEquals(
                "",
                recipientLiveVm.uiState.value.messagesByConversation["conv_1"]!!.first { it.id == "m_2" }.text
            )

            // 4. Shared delete reaches recipient AFTER RECONNECTING when live SSE was missed
            assertFalse(
                recipientReconnectVm.uiState.value.messagesByConversation["conv_1"]!!
                    .first { it.id == "m_2" }.isDeleted
            )
            recipientReconnectVm.realtimeClient.emitReconnectForTest()
            awaitCondition {
                recipientReconnectVm.uiState.value.messagesByConversation["conv_1"]
                    ?.firstOrNull { it.id == "m_2" }?.isDeleted == true
            }

            // 5. A deleted placeholder can still be hidden with Delete for me
            val placeholder = senderVm.uiState.value.messagesByConversation["conv_1"]!!.first { it.id == "m_2" }
            senderVm.deleteMessageForMe(placeholder)
            awaitCondition {
                senderVm.uiState.value.messagesByConversation["conv_1"]?.none { it.id == "m_2" } == true
            }
            // Existing reply m_3 still remains preserved
            assertEquals(
                listOf("m_1", "m_3"),
                senderVm.uiState.value.messagesByConversation["conv_1"]!!.map { it.id }
            )
        }

    @Test
    fun `stale in-flight fetch response never restores deleted or hidden messages`(): Unit = runBlocking {
        val user = UserRecord(id = "u_sender", name = "Madhu", email = "madhu@tmadhu.me")
        val backend = createTestBackend(
            seedMessages = listOf(
                buildMessageJson(
                    id = "m_stale_deleted",
                    sender = "u_sender",
                    text = "Will be deleted for everyone while fetch in flight",
                    created = "2026-11-16T01:00:00.000Z"
                ),
                buildMessageJson(
                    id = "m_stale_hidden",
                    sender = "u_peer",
                    text = "Will be hidden for me while fetch in flight",
                    created = "2026-11-16T01:01:00.000Z"
                )
            )
        )

        val vm = createViewModel("stale_guard_prefs", user, "tok_u_sender", backend.httpClient)
        vm.refreshAllAuthenticatedData()
        awaitCondition { vm.uiState.value.messagesByConversation["conv_1"]?.size == 2 }

        val fetchStarted = CountDownLatch(1)
        val allowFetchToReturn = CountDownLatch(1)
        val pauseNextMessagesFetch = AtomicBoolean(false)
        backend.afterSnapshotHook = { path, method ->
            if (method == "GET" &&
                path == "/api/collections/messages/records" &&
                pauseNextMessagesFetch.compareAndSet(true, false)
            ) {
                fetchStarted.countDown()
                allowFetchToReturn.await(5, TimeUnit.SECONDS)
            }
        }

        val staleVm = createViewModel("stale_guard_prefs_2", user, "tok_u_sender", backend.httpClient)
        // Pre-populate stale state cleanly first
        staleVm.reconcileConversationDataInternal("conv_1")
        assertEquals(2, staleVm.uiState.value.messagesByConversation["conv_1"]?.size)

        // Now trigger an in-flight refresh that reads pre-delete records on server
        pauseNextMessagesFetch.set(true)
        val refreshJob = launch(Dispatchers.IO) {
            staleVm.reconcileConversationDataInternal("conv_1")
        }
        assertTrue(fetchStarted.await(3, TimeUnit.SECONDS))

        // While the stale GET is blocked, user executes Delete for everyone on m_stale_deleted and Delete for me on m_stale_hidden
        val msgToDeleteAll = staleVm.uiState.value.messagesByConversation["conv_1"]!!.first { it.id == "m_stale_deleted" }
        val msgToHide = staleVm.uiState.value.messagesByConversation["conv_1"]!!.first { it.id == "m_stale_hidden" }
        staleVm.deleteMessageForEveryone(msgToDeleteAll)
        staleVm.deleteMessageForMe(msgToHide)

        awaitCondition {
            val msgs = staleVm.uiState.value.messagesByConversation["conv_1"].orEmpty()
            msgs.size == 1 && msgs.first().id == "m_stale_deleted" && msgs.first().isDeleted
        }

        // Now let the stale pre-delete GET response finish!
        allowFetchToReturn.countDown()
        refreshJob.join()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Monotonic floor MUST ensure m_stale_hidden stays gone and m_stale_deleted stays deleted!
        val finalMsgs = staleVm.uiState.value.messagesByConversation["conv_1"].orEmpty()
        assertEquals(listOf("m_stale_deleted"), finalMsgs.map { it.id })
        assertTrue(finalMsgs.first().isDeleted)
        assertEquals("", finalMsgs.first().text)
    }

    @Test
    fun `regular chat renders deleted placeholder, preserves reply preview, and opens compact delete menu`(): Unit =
        runBlocking {
            val peer = UserRecord(id = "u_peer", name = "Rahim", email = "rahim@tmadhu.me")
            val backend = createTestBackend(
                seedMessages = listOf(
                    buildMessageJson(
                        id = "m_orig_deleted",
                        sender = "u_sender",
                        text = "",
                        created = "2026-11-16T01:00:00.000Z",
                        deletedAt = "2026-11-16T01:00:30.000Z"
                    ),
                    buildMessageJson(
                        id = "m_reply",
                        sender = "u_peer",
                        text = "Replying during call",
                        replyTo = "m_orig_deleted",
                        inCall = true,
                        created = "2026-11-16T01:01:00.000Z"
                    )
                )
            )
            val vm = createViewModel("ui_chat_regular", peer, "tok_u_peer", backend.httpClient)
            vm.refreshAllAuthenticatedData()
            awaitCondition { vm.uiState.value.messagesByConversation["conv_1"]?.size == 2 }

            composeRule.setContent {
                ApunKaTheme {
                    ChatDetailScreen(
                        conversationId = "conv_1",
                        uiState = vm.uiState.value,
                        viewModel = vm,
                        showBackButton = false
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("deleted_message_placeholder_m_orig_deleted").assertIsDisplayed()
            composeRule.onNodeWithText("↳ This message was deleted.").assertIsDisplayed()
            composeRule.onNodeWithText("Replying during call").assertIsDisplayed()

            // Long press peer's own message m_reply -> offers BOTH Delete for me and Delete for everyone
            composeRule.onNodeWithTag("message_bubble_m_reply")
                .performTouchInput { longClick() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("action_delete_for_me").assertIsDisplayed()
            composeRule.onNodeWithTag("action_delete_for_everyone").assertIsDisplayed()
            Unit
        }

    @Test
    fun `in-call chat renders deleted placeholder, preserves reply preview, and supports delete for everyone`(): Unit =
        runBlocking {
            val peer = UserRecord(id = "u_peer", name = "Rahim", email = "rahim@tmadhu.me")
            val backend = createTestBackend(
                seedMessages = listOf(
                    buildMessageJson(
                        id = "m_orig_deleted",
                        sender = "u_sender",
                        text = "",
                        created = "2026-11-16T01:00:00.000Z",
                        deletedAt = "2026-11-16T01:00:30.000Z"
                    ),
                    buildMessageJson(
                        id = "m_reply",
                        sender = "u_peer",
                        text = "Replying during call",
                        replyTo = "m_orig_deleted",
                        inCall = true,
                        created = "2026-11-16T01:01:00.000Z"
                    )
                )
            )
            val vm = createViewModel("ui_chat_incall", peer, "tok_u_peer", backend.httpClient)
            vm.refreshAllAuthenticatedData()
            awaitCondition { vm.uiState.value.messagesByConversation["conv_1"]?.size == 2 }

            composeRule.setContent {
                ApunKaTheme {
                    InCallChatSheetContent(
                        conversationId = "conv_1",
                        peerName = "Madhu",
                        ownUserId = "u_peer",
                        messages = vm.uiState.value.messagesByConversation["conv_1"].orEmpty(),
                        draft = "",
                        onDraftChanged = {},
                        onSend = {},
                        onDeleteForMe = { vm.deleteMessageForMe(it) },
                        onDeleteForEveryone = { vm.deleteMessageForEveryone(it) }
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("in_call_deleted_placeholder_m_orig_deleted").assertIsDisplayed()
            composeRule.onNodeWithText("↳ This message was deleted.").assertIsDisplayed()

            // Long press m_reply in in-call chat -> Delete for everyone appears; clicking deletes for everyone
            composeRule.onNodeWithTag("in_call_message_m_reply")
                .performTouchInput { longClick() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("in_call_delete_for_me_m_reply").assertIsDisplayed()
            composeRule.onNodeWithTag("in_call_delete_for_everyone_m_reply").performClick()

            awaitCondition {
                vm.uiState.value.messagesByConversation["conv_1"]
                    ?.firstOrNull { it.id == "m_reply" }?.isDeleted == true
            }
        }

    private class FakeMessagingBackend(
        seedMessages: List<JSONObject>,
        private val failDeleteForMeCount: AtomicInteger,
        private val failDeleteForEveryoneCount: AtomicInteger,
        private val hidesCollectionForbidden: Boolean
    ) {
        val messages = CopyOnWriteArrayList(seedMessages)
        val reactions = CopyOnWriteArrayList<JSONObject>()
        val hides = CopyOnWriteArrayList<JSONObject>()
        val apiCalls = CopyOnWriteArrayList<String>()
        @Volatile
        var afterSnapshotHook: ((path: String, method: String) -> Unit)? = null

        private val jsonMedia = "application/json; charset=utf-8".toMediaType()

        private fun userForToken(authHeader: String): String = when {
            authHeader.contains("u_sender") -> "u_sender"
            authHeader.contains("u_peer") -> "u_peer"
            else -> "u_sender"
        }

        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                val method = req.method
                val query = req.url.query
                val fullCall = if (query != null) "$method $path?$query" else "$method $path"
                apiCalls.add(fullCall)
                val callerId = userForToken(req.header("Authorization").orEmpty())

                fun jsonResp(code: Int, body: String): Response = Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "Error")
                    .body(body.toResponseBody(jsonMedia))
                    .build()

                fun emptyResp(code: Int): Response = Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("No Content")
                    .body("".toResponseBody(null))
                    .build()

                when {
                    path == "/api/app/account/session" -> {
                        jsonResp(200, JSONObject().put("active", true).toString())
                    }

                    path == "/api/app/inbox" -> {
                        val peerObj = if (callerId == "u_sender") {
                            JSONObject().put("id", "u_peer").put("name", "Rahim").put("avatar", "")
                        } else {
                            JSONObject().put("id", "u_sender").put("name", "Madhu").put("avatar", "")
                        }
                        val payload = JSONObject().apply {
                            put(
                                "chats",
                                JSONArray().put(
                                    JSONObject().put("id", "conv_1").put("peer", peerObj)
                                )
                            )
                            put("requests", JSONArray())
                        }
                        jsonResp(200, payload.toString())
                    }

                    path == "/api/collections/conversation_members/records" -> {
                        val items = JSONArray().apply {
                            put(
                                JSONObject()
                                    .put("id", "cm_1")
                                    .put("conversation", "conv_1")
                                    .put("user", "u_sender")
                            )
                            put(
                                JSONObject()
                                    .put("id", "cm_2")
                                    .put("conversation", "conv_1")
                                    .put("user", "u_peer")
                            )
                        }
                        jsonResp(200, JSONObject().put("items", items).toString())
                    }

                    path == "/api/collections/message_hides/records" -> {
                        if (hidesCollectionForbidden) {
                            jsonResp(403, JSONObject().put("message", "Forbidden").toString())
                        } else {
                            val items = JSONArray().apply {
                                hides.filter { it.optString("user") == callerId }.forEach { put(it) }
                            }
                            jsonResp(200, JSONObject().put("items", items).toString())
                        }
                    }

                    path == "/api/collections/messages/records" -> {
                        val hiddenIds = hides.filter { it.optString("user") == callerId }
                            .map { it.getString("message") }
                            .toSet()
                        val items = JSONArray().apply {
                            messages.filter { it.getString("id") !in hiddenIds }.forEach { put(JSONObject(it.toString())) }
                        }
                        afterSnapshotHook?.invoke(path, method)
                        jsonResp(200, JSONObject().put("items", items).toString())
                    }

                    path == "/api/collections/message_reactions/records" -> {
                        val items = JSONArray().apply {
                            reactions.forEach { put(JSONObject(it.toString())) }
                        }
                        jsonResp(200, JSONObject().put("items", items).toString())
                    }

                    path == "/api/collections/calls/records" -> {
                        jsonResp(200, JSONObject().put("items", JSONArray()).toString())
                    }

                    path.startsWith("/api/app/conversations/") &&
                        (path.endsWith("/delivered") || path.endsWith("/read")) -> {
                        emptyResp(204)
                    }

                    method == "DELETE" && path.startsWith("/api/app/messages/") -> {
                        val messageId = path.substringAfterLast('/')
                        val scope = req.url.queryParameter("scope").orEmpty()
                        if (scope == "me") {
                            if (failDeleteForMeCount.getAndDecrement() > 0) {
                                return@Interceptor jsonResp(500, JSONObject().put("message", "Temporary delete failure").toString())
                            }
                            val target = messages.firstOrNull { it.getString("id") == messageId }
                                ?: return@Interceptor jsonResp(404, JSONObject().put("message", "Not found").toString())
                            val alreadyHidden = hides.any {
                                it.getString("message") == messageId && it.getString("user") == callerId
                            }
                            if (!alreadyHidden) {
                                hides.add(
                                    JSONObject()
                                        .put("id", "hide_${hides.size + 1}")
                                        .put("message", messageId)
                                        .put("user", callerId)
                                        .put("conversation", target.optString("conversation"))
                                )
                            }
                            emptyResp(204)
                        } else if (scope == "everyone") {
                            if (failDeleteForEveryoneCount.getAndDecrement() > 0) {
                                return@Interceptor jsonResp(500, JSONObject().put("message", "Temporary delete everyone failure").toString())
                            }
                            val idx = messages.indexOfFirst { it.getString("id") == messageId }
                            if (idx < 0) {
                                return@Interceptor jsonResp(404, JSONObject().put("message", "Not found").toString())
                            }
                            val existing = messages[idx]
                            if (existing.optString("sender") != callerId) {
                                return@Interceptor jsonResp(403, JSONObject().put("message", "Only sender can delete for everyone").toString())
                            }
                            val deletedAt = existing.optString("deleted_at")
                                .takeIf { it.isNotBlank() && it != "null" }
                                ?: "2026-11-16T01:05:00.000Z"
                            val updated = JSONObject(existing.toString()).apply {
                                put("text", "")
                                put("attachment", "")
                                put("attachment_bytes", 0)
                                put("deleted_at", deletedAt)
                                put("updated", deletedAt)
                            }
                            messages[idx] = updated
                            reactions.removeAll { it.optString("message") == messageId }
                            jsonResp(200, updated.toString())
                        } else {
                            jsonResp(400, JSONObject().put("message", "Invalid scope").toString())
                        }
                    }

                    else -> jsonResp(404, "{}")
                }
            })
            .build()
    }
}
