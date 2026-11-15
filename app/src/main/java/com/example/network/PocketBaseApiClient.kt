package com.example.network

import com.example.model.CallKind
import com.example.model.CallRecord
import com.example.model.CallRecordStatus
import com.example.model.CallTokenGrant
import com.example.model.ChatMessage
import com.example.model.ChatSummary
import com.example.model.ConversationMemberRecord
import com.example.model.MessageDeliveryState
import com.example.model.MessageKind
import com.example.model.MessageReaction
import com.example.model.MessageRequestItem
import com.example.model.PeerSummary
import com.example.model.UserRecord
import com.example.storage.SessionAndMuteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(
    val statusCode: Int,
    override val message: String,
    val isAuthEndpoint401: Boolean = false
) : Exception(message)

data class AuthResponse(
    val token: String,
    val user: UserRecord
)

data class InboxPayload(
    val chats: List<ChatSummary>,
    val requests: List<MessageRequestItem>
)

data class MessageRequestResult(
    val request: MessageRequestItem? = null,
    val direction: String = "",
    val existingConversationId: String? = null
)

data class PushConfigStatus(
    val androidEnabled: Boolean,
    val webVapidPublicKey: String = ""
)

class PocketBaseApiClient(
    private val sessionStore: SessionAndMuteStore,
    val httpClient: OkHttpClient = defaultOkHttpClient()
) {
    private val fileTokenMutex = Mutex()
    @Volatile
    private var cachedFileToken: String = ""
    @Volatile
    private var cachedFileTokenExpiresAtMs: Long = 0L

    val baseUrl: String
        get() = sessionStore.baseUrl

    // ---------------- Health & Session ----------------

    suspend fun checkHealth(): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/app/health")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false to false
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val ok = json.optString("status") == "ok" || resp.code == 200
            val calling = json.optBoolean("callingConfigured", true)
            ok to calling
        }
    }

    suspend fun registerAccount(
        name: String,
        email: String,
        password: String,
        passwordConfirm: String
    ): AuthResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("name", name.trim())
            put("email", email.trim())
            put("password", password)
            put("passwordConfirm", passwordConfirm)
        }
        val req = Request.Builder()
            .url("$baseUrl/api/app/account/register")
            .header("X-App-Device", sessionStore.deviceId)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        executeJson(req, isAuthCheck = false).let { parseAuthResponse(it) }
    }

    suspend fun signInWithPassword(
        email: String,
        password: String
    ): AuthResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("identity", email.trim())
            put("password", password)
        }
        val req = Request.Builder()
            .url("$baseUrl/api/collections/users/auth-with-password")
            .header("X-App-Device", sessionStore.deviceId)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        executeJson(req, isAuthCheck = false).let { parseAuthResponse(it) }
    }

    suspend fun refreshAuth(): AuthResponse = withContext(Dispatchers.IO) {
        val req = authenticatedBuilder("/api/collections/users/auth-refresh")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()
        executeJson(req, isAuthCheck = true).let { parseAuthResponse(it) }
    }

    suspend fun fetchSessionStatus(): Boolean = withContext(Dispatchers.IO) {
        val req = authenticatedBuilder("/api/app/account/session")
            .get()
            .build()
        val json = executeJson(req, isAuthCheck = true)
        val locked = json.optBoolean("historyLocked", false)
        sessionStore.historyLocked = locked
        locked
    }

    /**
     * Verifies whether a 401/error from a normal or file route actually means the user's session
     * was revoked on the server. Only returns `true` when `/api/app/account/session` returns 401.
     */
    suspend fun isSessionTrulyRevoked(): Boolean = withContext(Dispatchers.IO) {
        val token = sessionStore.getAuthToken()
        if (token.isBlank()) return@withContext true
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/app/account/session")
                .header("Authorization", token)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                resp.code == 401
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------------- History Unlock, Recovery & Account Changes ----------------

    suspend fun requestHistoryUnlockCode() = withContext(Dispatchers.IO) {
        val req = authenticatedBuilder("/api/app/account/history/request")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()
        executeNoContent(req)
    }

    suspend fun confirmHistoryUnlockCode(code: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("code", code.trim())
        val req = authenticatedBuilder("/api/app/account/history/confirm")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        executeNoContent(req)
    }

    suspend fun requestRecoveryCode(email: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("email", email.trim())
        val req = Request.Builder()
            .url("$baseUrl/api/app/account/recovery/request")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        executeNoContent(req)
    }

    suspend fun confirmRecoveryCode(
        email: String,
        code: String,
        newPassword: String,
        newPasswordConfirm: String
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("email", email.trim())
            put("code", code.trim())
            put("password", newPassword)
            put("passwordConfirm", newPasswordConfirm)
        }
        val req = Request.Builder()
            .url("$baseUrl/api/app/account/recovery/confirm")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        executeNoContent(req)
    }

    suspend fun requestPasswordResetLink(email: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("email", email.trim())
        val req = Request.Builder()
            .url("$baseUrl/api/collections/users/request-password-reset")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        executeNoContent(req)
    }

    suspend fun requestEmailChangeLink(newEmail: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("newEmail", newEmail.trim())
        val req = authenticatedBuilder("/api/collections/users/request-email-change")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        executeNoContent(req)
    }

    suspend fun changePasswordInSession(
        newPassword: String,
        newPasswordConfirm: String
    ): AuthResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("password", newPassword)
            put("passwordConfirm", newPasswordConfirm)
        }
        val req = authenticatedBuilder("/api/app/account/password")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        val authResp = parseAuthResponse(executeJson(req, isAuthCheck = true))
        sessionStore.saveSession(authResp.token, authResp.user)
        authResp
    }

    suspend fun updateProfile(
        userId: String,
        name: String?,
        avatarFile: File? = null,
        avatarMimeType: String = "image/jpeg",
        clearAvatar: Boolean = false
    ): UserRecord = withContext(Dispatchers.IO) {
        val body = if (avatarFile != null) {
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .apply {
                    if (name != null) addFormDataPart("name", name.trim())
                    addFormDataPart(
                        "avatar",
                        avatarFile.name,
                        avatarFile.asRequestBody(avatarMimeType.toMediaType())
                    )
                }
                .build()
        } else {
            JSONObject().apply {
                if (name != null) put("name", name.trim())
                if (clearAvatar) put("avatar", "")
            }.toString().toRequestBody(JSON_MEDIA)
        }
        val req = authenticatedBuilder("/api/collections/users/records/$userId")
            .patch(body)
            .build()
        val updated = parseUserRecord(executeJson(req, isAuthCheck = false))
        val currentToken = sessionStore.getAuthToken()
        if (currentToken.isNotBlank()) {
            sessionStore.saveSession(currentToken, updated)
        }
        updated
    }

    suspend fun logoutSession() = withContext(Dispatchers.IO) {
        val req = authenticatedBuilder("/api/app/account/logout")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()
        try {
            executeNoContent(req)
        } finally {
            clearCachedFileToken()
        }
    }

    // ---------------- Protected File Token & Media URLs ----------------

    suspend fun getFileToken(forceRefresh: Boolean = false): String = fileTokenMutex.withLock {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedFileToken.isNotBlank() && now < cachedFileTokenExpiresAtMs) {
            return@withLock cachedFileToken
        }
        withContext(Dispatchers.IO) {
            val req = authenticatedBuilder("/api/files/token")
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()
            val json = executeJson(req, isAuthCheck = false)
            val token = json.optString("token")
            if (token.isNotBlank()) {
                cachedFileToken = token
                // Server file token TTL is 3 minutes (180s); cache for 150s
                cachedFileTokenExpiresAtMs = System.currentTimeMillis() + 150_000L
            }
            token
        }
    }

    fun clearCachedFileToken() {
        cachedFileToken = ""
        cachedFileTokenExpiresAtMs = 0L
    }

    /**
     * Builds a protected file URL with `?token=...` and optional `thumb=96x96` or `400x0`.
     * If `forceRefreshToken` is true (e.g. when retrying a failed image/audio load), refreshes
     * the file token first without logging out the user.
     */
    suspend fun buildProtectedFileUrl(
        collection: String,
        recordId: String,
        filename: String,
        thumb: String? = null,
        forceRefreshToken: Boolean = false
    ): String {
        if (recordId.isBlank() || filename.isBlank()) return ""
        val token = try {
            getFileToken(forceRefresh = forceRefreshToken)
        } catch (_: Exception) {
            ""
        }
        val encodedFile = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        val sb = StringBuilder("$baseUrl/api/files/$collection/$recordId/$encodedFile")
        val params = mutableListOf<String>()
        if (token.isNotBlank()) params.add("token=${URLEncoder.encode(token, "UTF-8")}")
        if (!thumb.isNullOrBlank()) params.add("thumb=$thumb")
        if (params.isNotEmpty()) {
            sb.append("?").append(params.joinToString("&"))
        }
        return sb.toString()
    }

    // ---------------- Inbox, Search & Message Requests ----------------

    suspend fun fetchInbox(): InboxPayload = withContext(Dispatchers.IO) {
        val req = authenticatedBuilder("/api/app/inbox").get().build()
        val json = executeJson(req, isAuthCheck = false)
        val chatsArr = json.optJSONArray("chats") ?: JSONArray()
        val reqsArr = json.optJSONArray("requests") ?: JSONArray()

        val chats = buildList {
            for (i in 0 until chatsArr.length()) {
                val item = chatsArr.optJSONObject(i) ?: continue
                val peerObj = item.optJSONObject("peer") ?: JSONObject()
                add(
                    ChatSummary(
                        id = item.optString("id"),
                        peer = PeerSummary(
                            id = peerObj.optString("id"),
                            name = peerObj.optString("name").ifBlank { "Member" },
                            avatar = peerObj.optString("avatar")
                        )
                    )
                )
            }
        }
        val requests = buildList {
            for (i in 0 until reqsArr.length()) {
                val item = reqsArr.optJSONObject(i) ?: continue
                val peerObj = item.optJSONObject("peer") ?: JSONObject()
                add(
                    MessageRequestItem(
                        id = item.optString("id"),
                        peer = PeerSummary(
                            id = peerObj.optString("id"),
                            name = peerObj.optString("name").ifBlank { "Member" },
                            avatar = peerObj.optString("avatar")
                        ),
                        incoming = item.optBoolean("incoming", false),
                        note = item.optString("note"),
                        created = item.optString("created")
                    )
                )
            }
        }
        InboxPayload(chats = chats, requests = requests)
    }

    suspend fun searchMembers(query: String): List<PeerSummary> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()
        val url = "$baseUrl/api/app/members/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", trimmed)
            ?.build()
            ?: return@withContext emptyList()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", sessionStore.getAuthToken())
            .get()
            .build()
        val arr = executeJsonArray(req)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    PeerSummary(
                        id = obj.optString("id"),
                        name = obj.optString("name").ifBlank { "Member" },
                        avatar = obj.optString("avatar")
                    )
                )
            }
        }
    }

    suspend fun sendMessageRequest(recipientId: String, note: String = ""): MessageRequestResult =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("recipient", recipientId)
                if (note.isNotBlank()) put("note", note.trim().take(500))
            }
            val req = authenticatedBuilder("/api/app/message-requests")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            val json = executeJson(req, isAuthCheck = false)
            val convObj = json.optJSONObject("conversation")
            if (convObj != null) {
                return@withContext MessageRequestResult(
                    existingConversationId = convObj.optString("id")
                )
            }
            val reqObj = json.optJSONObject("request")
            val direction = json.optString("direction", "outgoing")
            val parsedReq = reqObj?.let {
                MessageRequestItem(
                    id = it.optString("id"),
                    peer = PeerSummary(id = recipientId, name = ""),
                    incoming = direction == "incoming",
                    note = it.optString("note", note),
                    created = it.optString("created")
                )
            }
            MessageRequestResult(request = parsedReq, direction = direction)
        }

    suspend fun acceptMessageRequest(requestId: String): String = withContext(Dispatchers.IO) {
        val req = authenticatedBuilder("/api/app/message-requests/$requestId/accept")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()
        val json = executeJson(req, isAuthCheck = false)
        json.optJSONObject("conversation")?.optString("id").orEmpty()
    }

    suspend fun deleteMessageRequest(requestId: String) = withContext(Dispatchers.IO) {
        val req = authenticatedBuilder("/api/app/message-requests/$requestId")
            .delete()
            .build()
        executeNoContent(req)
    }

    // ---------------- Messages, Watermarks, Reactions & Presence ----------------

    suspend fun fetchMessages(
        conversationId: String? = null,
        perPage: Int = 30,
        beforeCreated: String? = null,
        beforeId: String? = null
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        val filters = mutableListOf<String>()
        if (!conversationId.isNullOrBlank()) {
            filters.add("conversation=\"${escapeFilter(conversationId)}\"")
        }
        if (!beforeCreated.isNullOrBlank() && !beforeId.isNullOrBlank()) {
            val c = escapeFilter(beforeCreated)
            val id = escapeFilter(beforeId)
            filters.add("(created<\"$c\" || (created=\"$c\" && id<\"$id\"))")
        }
        val urlBuilder = "$baseUrl/api/collections/messages/records".toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("perPage", perPage.toString())
            .addQueryParameter("sort", "-created,-id")
        if (filters.isNotEmpty()) {
            urlBuilder.addQueryParameter("filter", filters.joinToString(" && "))
        }
        val req = Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", sessionStore.getAuthToken())
            .get()
            .build()
        val json = executeJson(req, isAuthCheck = false)
        val items = json.optJSONArray("items") ?: JSONArray()
        buildList {
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                add(parseChatMessage(obj))
            }
        }.sortedWith(compareBy<ChatMessage> { it.created }.thenBy { it.id })
    }

    suspend fun fetchConversationMembers(conversationId: String? = null): List<ConversationMemberRecord> =
        withContext(Dispatchers.IO) {
            val urlBuilder = "$baseUrl/api/collections/conversation_members/records".toHttpUrlOrNull()!!
                .newBuilder()
                .addQueryParameter("perPage", "100")
            if (!conversationId.isNullOrBlank()) {
                urlBuilder.addQueryParameter("filter", "conversation=\"${escapeFilter(conversationId)}\"")
            }
            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("Authorization", sessionStore.getAuthToken())
                .get()
                .build()
            val json = executeJson(req, isAuthCheck = false)
            val items = json.optJSONArray("items") ?: JSONArray()
            buildList {
                for (i in 0 until items.length()) {
                    val obj = items.optJSONObject(i) ?: continue
                    add(parseConversationMember(obj))
                }
            }
        }

    suspend fun fetchMessageReactions(conversationId: String): List<MessageReaction> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/collections/message_reactions/records".toHttpUrlOrNull()!!
                .newBuilder()
                .addQueryParameter("perPage", "200")
                .addQueryParameter("filter", "conversation=\"${escapeFilter(conversationId)}\"")
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", sessionStore.getAuthToken())
                .get()
                .build()
            val json = executeJson(req, isAuthCheck = false)
            val items = json.optJSONArray("items") ?: JSONArray()
            buildList {
                for (i in 0 until items.length()) {
                    val obj = items.optJSONObject(i) ?: continue
                    add(parseMessageReaction(obj))
                }
            }
        }

    suspend fun sendMessage(
        conversationId: String,
        text: String,
        clientMessageId: String,
        kind: MessageKind = MessageKind.TEXT,
        replyToId: String = "",
        inCall: Boolean = false,
        attachmentFile: File? = null,
        attachmentMimeType: String = "application/octet-stream"
    ): ChatMessage = withContext(Dispatchers.IO) {
        val requestBody = if (attachmentFile != null && kind != MessageKind.TEXT) {
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("conversation", conversationId)
                .addFormDataPart("text", text)
                .addFormDataPart("client_message_id", clientMessageId)
                .addFormDataPart("kind", kind.wireName)
                .apply {
                    if (replyToId.isNotBlank()) addFormDataPart("reply_to", replyToId)
                    if (inCall) addFormDataPart("in_call", "true")
                    addFormDataPart(
                        "attachment",
                        attachmentFile.name,
                        attachmentFile.asRequestBody(attachmentMimeType.toMediaType())
                    )
                }
                .build()
        } else {
            JSONObject().apply {
                put("conversation", conversationId)
                put("text", text)
                put("client_message_id", clientMessageId)
                put("kind", kind.wireName)
                if (replyToId.isNotBlank()) put("reply_to", replyToId)
                if (inCall) put("in_call", true)
            }.toString().toRequestBody(JSON_MEDIA)
        }
        val req = authenticatedBuilder("/api/app/messages")
            .post(requestBody)
            .build()
        parseChatMessage(executeJson(req, isAuthCheck = false))
    }

    suspend fun editMessage(messageId: String, newText: String): ChatMessage =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("text", newText)
            val req = authenticatedBuilder("/api/app/messages/$messageId")
                .patch(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            parseChatMessage(executeJson(req, isAuthCheck = false))
        }

    suspend fun setReaction(messageId: String, emoji: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("emoji", emoji)
        val req = authenticatedBuilder("/api/app/reactions/$messageId")
            .put(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        executeNoContent(req)
    }

    /**
     * Marks messages up to `messageId` as Seen (`read_through`, `read_id`).
     */
    suspend fun markConversationRead(conversationId: String, messageId: String) =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("message", messageId)
            val req = authenticatedBuilder("/api/app/conversations/$conversationId/read")
                .put(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            executeNoContent(req)
        }

    /**
     * Acknowledges that messages up to `messageId` have been genuinely synced by this client
     * (`delivered_through`, `delivered_id`). Gracefully ignores 404 if the backend migration has
     * not been deployed yet.
     */
    suspend fun markConversationDelivered(conversationId: String, messageId: String): Boolean =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("message", messageId)
            val req = authenticatedBuilder("/api/app/conversations/$conversationId/delivered")
                .put(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            try {
                executeNoContent(req)
                true
            } catch (e: ApiException) {
                if (e.statusCode == 404) false else throw e
            }
        }

    suspend fun sendPresence(conversationId: String, isTyping: Boolean) =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("typing", isTyping)
            val req = authenticatedBuilder("/api/app/conversations/$conversationId/presence")
                .put(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            executeNoContent(req)
        }

    // ---------------- Push & Device Registration ----------------

    suspend fun fetchPushConfig(): PushConfigStatus = withContext(Dispatchers.IO) {
        val req = authenticatedBuilder("/api/app/push").get().build()
        val json = executeJson(req, isAuthCheck = false)
        PushConfigStatus(
            androidEnabled = json.optBoolean("android", false),
            webVapidPublicKey = json.optString("webVapidPublicKey")
        )
    }

    suspend fun registerAndroidPushDevice(fcmToken: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("platform", "android")
            put("token", fcmToken)
        }
        val req = authenticatedBuilder("/api/app/devices")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        val json = executeJson(req, isAuthCheck = false)
        val recordId = json.optString("id")
        if (recordId.isNotBlank()) {
            sessionStore.registeredPushDeviceId = recordId
        }
        recordId
    }

    suspend fun deletePushDevice(deviceRecordId: String) = withContext(Dispatchers.IO) {
        if (deviceRecordId.isBlank()) return@withContext
        val req = authenticatedBuilder("/api/app/devices/$deviceRecordId")
            .delete()
            .build()
        try {
            executeNoContent(req)
        } catch (e: ApiException) {
            if (e.statusCode != 404) throw e
        }
    }

    // ---------------- Calls ----------------

    suspend fun fetchRecentCalls(perPage: Int = 40): List<CallRecord> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/collections/calls/records".toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("perPage", perPage.toString())
            .addQueryParameter("sort", "-created,-id")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", sessionStore.getAuthToken())
            .get()
            .build()
        val json = executeJson(req, isAuthCheck = false)
        val items = json.optJSONArray("items") ?: JSONArray()
        buildList {
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                add(parseCallRecord(obj))
            }
        }
    }

    suspend fun fetchCallRecord(callId: String): CallRecord = withContext(Dispatchers.IO) {
        val req = authenticatedBuilder("/api/collections/calls/records/$callId")
            .get()
            .build()
        parseCallRecord(executeJson(req, isAuthCheck = false))
    }

    suspend fun startCall(conversationId: String, kind: CallKind): CallRecord =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("conversation", conversationId)
                put("kind", kind.wireName)
            }
            val req = authenticatedBuilder("/api/app/calls")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            parseCallRecord(executeJson(req, isAuthCheck = false))
        }

    suspend fun acceptCall(callId: String): CallRecord = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("deviceId", sessionStore.deviceId)
        val req = authenticatedBuilder("/api/app/calls/$callId/accept")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        parseCallRecord(executeJson(req, isAuthCheck = false))
    }

    suspend fun rejectCall(callId: String) = withContext(Dispatchers.IO) {
        val req = authenticatedBuilder("/api/app/calls/$callId/reject")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()
        executeNoContent(req)
    }

    suspend fun endCall(callId: String) = withContext(Dispatchers.IO) {
        val req = authenticatedBuilder("/api/app/calls/$callId/end")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()
        executeNoContent(req)
    }

    suspend fun fetchCallToken(callId: String): CallTokenGrant = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("deviceId", sessionStore.deviceId)
        val req = authenticatedBuilder("/api/app/calls/$callId/token")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        val json = executeJson(req, isAuthCheck = false)
        CallTokenGrant(
            token = json.optString("token"),
            wsUrl = json.optString("wsUrl").ifBlank { "wss://lk.tmadhu.me" },
            roomName = json.optString("roomName"),
            expiresAt = json.optString("expiresAt")
        )
    }

    // ---------------- Helpers & JSON Parsers ----------------

    private fun authenticatedBuilder(path: String): Request.Builder {
        val fullUrl = if (path.startsWith("http")) path else "$baseUrl$path"
        val builder = Request.Builder().url(fullUrl)
        val token = sessionStore.getAuthToken()
        if (token.isNotBlank()) {
            builder.header("Authorization", token)
        }
        return builder
    }

    private fun executeJson(request: Request, isAuthCheck: Boolean): JSONObject {
        httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw parseApiError(resp.code, raw, isAuthCheck)
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    private fun executeJsonArray(request: Request): JSONArray {
        httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw parseApiError(resp.code, raw, isAuthCheck = false)
            }
            return if (raw.isBlank()) JSONArray() else JSONArray(raw)
        }
    }

    private fun executeNoContent(request: Request) {
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val raw = resp.body?.string().orEmpty()
                throw parseApiError(resp.code, raw, isAuthCheck = false)
            }
        }
    }

    private fun parseApiError(code: Int, rawBody: String, isAuthCheck: Boolean): ApiException {
        val msg = try {
            val json = JSONObject(rawBody)
            json.optString("message").ifBlank { "HTTP $code" }
        } catch (_: Exception) {
            rawBody.ifBlank { "HTTP $code" }
        }
        return ApiException(
            statusCode = code,
            message = msg,
            isAuthEndpoint401 = isAuthCheck && code == 401
        )
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun escapeFilter(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        fun parseAuthResponse(json: JSONObject): AuthResponse {
            val token = json.optString("token")
            val recordObj = json.optJSONObject("record") ?: json
            return AuthResponse(
                token = token,
                user = parseUserRecord(recordObj)
            )
        }

        fun parseUserRecord(obj: JSONObject): UserRecord = UserRecord(
            id = obj.optString("id"),
            name = obj.optString("name").ifBlank { obj.optString("email").substringBefore("@") },
            email = obj.optString("email"),
            avatar = obj.optString("avatar"),
            verified = obj.optBoolean("verified", true)
        )

        fun parseChatMessage(obj: JSONObject): ChatMessage = ChatMessage(
            id = obj.optString("id"),
            conversation = obj.optString("conversation"),
            sender = obj.optString("sender"),
            text = obj.optString("text"),
            clientMessageId = obj.optString("client_message_id"),
            kind = MessageKind.fromWire(obj.optString("kind", "text")),
            attachment = obj.optString("attachment"),
            attachmentBytes = obj.optLong("attachment_bytes", 0L),
            replyTo = obj.optString("reply_to"),
            inCall = obj.optBoolean("in_call", false),
            created = obj.optString("created"),
            updated = obj.optString("updated").ifBlank { obj.optString("created") },
            deliveryState = MessageDeliveryState.SENT
        )

        fun parseConversationMember(obj: JSONObject): ConversationMemberRecord =
            ConversationMemberRecord(
                id = obj.optString("id"),
                conversation = obj.optString("conversation"),
                user = obj.optString("user"),
                readThrough = obj.optString("read_through"),
                readId = obj.optString("read_id"),
                deliveredThrough = obj.optString("delivered_through"),
                deliveredId = obj.optString("delivered_id"),
                presenceUntil = obj.optString("presence_until"),
                typingUntil = obj.optString("typing_until")
            )

        fun parseMessageReaction(obj: JSONObject): MessageReaction = MessageReaction(
            id = obj.optString("id"),
            conversation = obj.optString("conversation"),
            message = obj.optString("message"),
            user = obj.optString("user"),
            emoji = obj.optString("emoji")
        )

        fun parseCallRecord(obj: JSONObject): CallRecord = CallRecord(
            id = obj.optString("id"),
            conversation = obj.optString("conversation"),
            caller = obj.optString("caller"),
            receiver = obj.optString("receiver"),
            room = obj.optString("room"),
            kind = CallKind.fromWire(obj.optString("kind")),
            status = CallRecordStatus.fromWire(obj.optString("status")),
            ringExpiresAt = obj.optString("ring_expires_at"),
            acceptedAt = obj.optString("accepted_at"),
            connectedAt = obj.optString("connected_at"),
            endedAt = obj.optString("ended_at"),
            created = obj.optString("created")
        )
    }
}
