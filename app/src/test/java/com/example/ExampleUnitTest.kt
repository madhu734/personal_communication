package com.example

import com.example.model.ChatMessage
import com.example.model.ConversationMemberRecord
import com.example.model.MessageDeliveryState
import com.example.model.MuteDurationOption
import com.example.model.WatermarkCursorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `outgoing message tick transitions strictly follow server save, delivered watermark, and read watermark`() {
        val msg = ChatMessage(
            id = "msg_100",
            conversation = "conv_1",
            sender = "user_a",
            text = "Hello friend",
            clientMessageId = "cid_100",
            created = "2026-11-15T10:00:00.000Z",
            deliveryState = MessageDeliveryState.SENT
        )

        // 1. No peer member record yet -> SENT (`✓` gray)
        assertEquals(
            MessageDeliveryState.SENT,
            WatermarkCursorUtils.resolveOutgoingDeliveryState(msg, peerMember = null)
        )

        // 2. Peer is online (presence_until in future) but has NOT synced message -> still SENT (`✓` gray)!
        val onlineButUnsyncedPeer = ConversationMemberRecord(
            id = "cm_peer",
            conversation = "conv_1",
            user = "user_b",
            readThrough = "2026-11-15T09:50:00.000Z",
            readId = "msg_050",
            deliveredThrough = "2026-11-15T09:55:00.000Z",
            deliveredId = "msg_080",
            presenceUntil = "2026-11-15T10:05:00.000Z"
        )
        assertEquals(
            MessageDeliveryState.SENT,
            WatermarkCursorUtils.resolveOutgoingDeliveryState(msg, onlineButUnsyncedPeer)
        )

        // 3. Peer syncs message and updates delivered_through/delivered_id -> DELIVERED (`✓✓` gray)
        val syncedPeer = onlineButUnsyncedPeer.copy(
            deliveredThrough = "2026-11-15T10:00:00.000Z",
            deliveredId = "msg_100"
        )
        assertEquals(
            MessageDeliveryState.DELIVERED,
            WatermarkCursorUtils.resolveOutgoingDeliveryState(msg, syncedPeer)
        )

        // 4. Peer opens chat and updates read_through/read_id -> SEEN (`✓✓` colored)
        val readPeer = syncedPeer.copy(
            readThrough = "2026-11-15T10:00:00.000Z",
            readId = "msg_100"
        )
        assertEquals(
            MessageDeliveryState.SEEN,
            WatermarkCursorUtils.resolveOutgoingDeliveryState(msg, readPeer)
        )
    }

    @Test
    fun `tuple watermark comparison breaks ties on message id when timestamps match`() {
        val ts = "2026-11-15 12:00:00.123Z"
        assertTrue(
            WatermarkCursorUtils.isMessageAtOrBeforeWatermark(
                msgCreated = ts,
                msgId = "abc000000000001",
                watermarkTime = ts,
                watermarkId = "abc000000000002"
            )
        )
        assertFalse(
            WatermarkCursorUtils.isMessageAtOrBeforeWatermark(
                msgCreated = ts,
                msgId = "abc000000000005",
                watermarkTime = ts,
                watermarkId = "abc000000000002"
            )
        )
    }

    @Test
    fun `mute duration options cover 1h, 2h, 8h, 1d, and Always`() {
        val now = 1_700_000_000_000L
        assertEquals(now + 3_600_000L, MuteDurationOption.ONE_HOUR.computeMuteUntilEpochMs(now))
        assertEquals(now + 7_200_000L, MuteDurationOption.TWO_HOURS.computeMuteUntilEpochMs(now))
        assertEquals(now + 28_800_000L, MuteDurationOption.EIGHT_HOURS.computeMuteUntilEpochMs(now))
        assertEquals(now + 86_400_000L, MuteDurationOption.ONE_DAY.computeMuteUntilEpochMs(now))
        assertEquals(Long.MAX_VALUE, MuteDurationOption.ALWAYS.computeMuteUntilEpochMs(now))
    }
}
