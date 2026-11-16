package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.ChatMessage
import com.example.model.MessageKind
import com.example.storage.PendingMessageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PendingMessageStoreTest {
    @Test
    fun attachmentSurvivesStoreRecreationAndIsIsolatedByAccount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val source = File.createTempFile("pending_test", ".jpg", context.cacheDir)
        source.writeText("test attachment")
        val message = ChatMessage(
            id = "local_$id", conversation = "chat", sender = "user-one", text = "photo",
            clientMessageId = id, kind = MessageKind.IMAGE, created = "2026-01-01T00:00:00Z"
        )
        PendingMessageStore(context).save("user-one", message, source, "image/jpeg")
        source.delete()
        val restored = PendingMessageStore(context).list("user-one").single { it.message.clientMessageId == id }
        assertEquals("test attachment", restored.attachment?.readText())
        assertTrue(PendingMessageStore(context).list("user-two").none { it.message.clientMessageId == id })
        PendingMessageStore(context).remove("user-one", id)
        assertFalse(restored.attachment!!.exists())
    }
}
