package com.example.helloworld

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ─── Chat data models ─────────────────────────────────────────────────────────

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isPending: Boolean = false,
    val readBy: List<String> = emptyList()
)

data class ChatConversation(
    val conversationId: String,
    val participants: List<String>,
    val names: Map<String, String>,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unread: Map<String, Int>
)

// ─── HTTP helpers (chat-local, no conflict with MainActivity) ─────────────────

private fun chatHttpGetArray(path: String): JSONArray? = try {
    val conn = java.net.URL("$BACKEND$path").openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 10_000; conn.readTimeout = 10_000; conn.requestMethod = "GET"
    if (conn.responseCode == 200) JSONArray(conn.inputStream.bufferedReader().readText()) else null
} catch (_: Exception) { null }

private fun chatHttpPost(path: String, body: JSONObject): JSONObject? = try {
    val conn = java.net.URL("$BACKEND$path").openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 10_000; conn.readTimeout = 10_000
    conn.requestMethod = "POST"; conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    java.io.OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
    if (conn.responseCode in 200..299)
        JSONObject(conn.inputStream.bufferedReader().readText()) else null
} catch (_: Exception) { null }

private fun chatHttpDelete(path: String): Boolean = try {
    val conn = java.net.URL("$BACKEND$path").openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 10_000; conn.readTimeout = 10_000; conn.requestMethod = "DELETE"
    conn.responseCode in 200..299
} catch (_: Exception) { false }

// ─── JSON parsers ─────────────────────────────────────────────────────────────

fun parseConversations(arr: JSONArray): List<ChatConversation> {
    val list = mutableListOf<ChatConversation>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val participants = mutableListOf<String>()
        val pArr = o.optJSONArray("participants")
        if (pArr != null) for (j in 0 until pArr.length()) participants.add(pArr.getString(j))
        val names = mutableMapOf<String, String>()
        val namesObj = o.optJSONObject("names")
        namesObj?.keys()?.forEach { k -> names[k] = namesObj.getString(k) }
        val unread = mutableMapOf<String, Int>()
        val unreadObj = o.optJSONObject("unread")
        unreadObj?.keys()?.forEach { k -> unread[k] = unreadObj.optInt(k, 0) }
        list.add(
            ChatConversation(
                conversationId = o.optString("conversationId"),
                participants   = participants,
                names          = names,
                lastMessage    = o.optString("lastMessage", ""),
                lastTimestamp  = o.optLong("lastTimestamp", 0L),
                unread         = unread
            )
        )
    }
    return list
}

fun parseMessages(arr: JSONArray): List<ChatMessage> {
    val list = mutableListOf<ChatMessage>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val readBy = mutableListOf<String>()
        val readByArr = o.optJSONArray("readBy")
        if (readByArr != null) for (j in 0 until readByArr.length()) readBy.add(readByArr.getString(j))
        list.add(
            ChatMessage(
                id             = o.optString("_id", UUID.randomUUID().toString()),
                conversationId = o.optString("conversationId"),
                senderId       = o.optString("senderId"),
                senderName     = o.optString("senderName"),
                text           = o.optString("text"),
                timestamp      = o.optLong("timestamp", 0L),
                readBy         = readBy
            )
        )
    }
    return list
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

fun makeConvId(a: String, b: String) = listOf(a, b).sorted().joinToString("__")

fun formatTimestamp(ts: Long): String {
    val now  = System.currentTimeMillis()
    val diff = now - ts
    val sdf  = when {
        diff < 86_400_000L  -> SimpleDateFormat("HH:mm",         Locale.getDefault())
        diff < 604_800_000L -> SimpleDateFormat("EEE HH:mm",     Locale.getDefault())
        else                -> SimpleDateFormat("dd MMM HH:mm",  Locale.getDefault())
    }
    return sdf.format(Date(ts))
}

// ─── Entry point ──────────────────────────────────────────────────────────────

@Composable
fun ChatEntryPoint(
    myTrackId: String,
    myName: String,
    trackedIds: List<String>,
    initialChatTrackId: String? = null,
    initialChatName: String?    = null,
    onBack: () -> Unit
) {
    var openConvId   by remember { mutableStateOf<String?>(null) }
    var openFriendId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(myTrackId, initialChatTrackId) {
        if (initialChatTrackId != null) {
            openFriendId = initialChatTrackId
            openConvId   = makeConvId(myTrackId, initialChatTrackId)
        }
    }

    if (openConvId != null && openFriendId != null) {
        ChatConversationScreen(
            myTrackId      = myTrackId,
            myName         = myName,
            friendId       = openFriendId!!,
            conversationId = openConvId!!,
            onBack         = { openConvId = null; openFriendId = null }
        )
    } else {
        ChatListScreen(
            myTrackId  = myTrackId,
            trackedIds = trackedIds,
            onOpenConv = { convId, friendId -> openConvId = convId; openFriendId = friendId },
            onBack     = onBack
        )
    }
}

// ─── Conversation list ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    myTrackId: String,
    trackedIds: List<String>,
    onOpenConv: (String, String) -> Unit,
    onBack: () -> Unit
) {
    var serverConvs  by remember { mutableStateOf<List<ChatConversation>>(emptyList()) }
    var isLoading    by remember { mutableStateOf(true) }
    var fetchFailed  by remember { mutableStateOf(false) }

    // Robust polling — keeps retrying on failure (handles Render cold-start)
    LaunchedEffect(myTrackId) {
        while (true) {
            val result = withContext(Dispatchers.IO) {
                try {
                    chatHttpGetArray("/api/chat/conversations/$myTrackId")
                        ?.let { parseConversations(it) }
                } catch (_: Exception) { null }
            }
            if (result != null) {
                serverConvs = result
                isLoading   = false
                fetchFailed = false
            } else {
                fetchFailed = isLoading  // only show error if we've never loaded yet
            }
            delay(3_000L)
        }
    }

    val allFriendIds = remember(trackedIds, serverConvs) {
        val fromServer = serverConvs.flatMap { it.participants }.filter { it != myTrackId }
        (trackedIds + fromServer).distinct()
    }

    Box(Modifier.fillMaxSize().background(DarkBg)) {
        Column(Modifier.fillMaxSize()) {

            // ── Header ────────────────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(DarkCard).clickable { onBack() },
                        Alignment.Center
                    ) {
                        Icon(Icons.Default.ArrowBack, null, tint = EmeraldGreen, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Messages", fontSize = 24.sp, fontWeight = FontWeight.Black, color = TextOnDark)
                        Text("${allFriendIds.size} conversations", fontSize = 12.sp, color = TextOnDarkMuted)
                    }
                }
            }

            // ── Loading / error / empty states ────────────────────────────────
            when {
                isLoading && allFriendIds.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = EmeraldGreen, strokeWidth = 2.dp)
                            if (fetchFailed) {
                                Spacer(Modifier.height(14.dp))
                                Text("Connecting to server…", fontSize = 12.sp, color = TextOnDarkMuted)
                            }
                        }
                    }
                    return@Column
                }

                allFriendIds.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("💬", fontSize = 52.sp)
                            Spacer(Modifier.height(16.dp))
                            Text("No conversations yet", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = TextOnDark)
                            Spacer(Modifier.height(6.dp))
                            Text("Add a friend to start chatting", fontSize = 13.sp, color = TextOnDarkMuted)
                        }
                    }
                    return@Column
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "CONVERSATIONS",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp, color = TextOnDarkMuted
            )

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(allFriendIds, key = { it }) { friendId ->
                    val convId      = makeConvId(myTrackId, friendId)
                    val conv        = serverConvs.find { it.conversationId == convId }
                    val unreadCount = conv?.unread?.get(myTrackId) ?: 0
                    val colorIndex  = trackedIds.indexOf(friendId).takeIf { it >= 0 }
                        ?: (friendId.hashCode().and(0x7FFFFFFF) % FriendColors.size)
                    DarkConversationRow(
                        friendId    = friendId,
                        conv        = conv,
                        colorIndex  = colorIndex,
                        unreadCount = unreadCount,
                        onClick     = { onOpenConv(convId, friendId) }
                    )
                }
            }
        }
    }
}

// ─── Conversation row ─────────────────────────────────────────────────────────

@Composable
private fun DarkConversationRow(
    friendId: String,
    conv: ChatConversation?,
    colorIndex: Int,
    unreadCount: Int,
    onClick: () -> Unit
) {
    val hasUnread = unreadCount > 0
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape    = RoundedCornerShape(18.dp),
        color    = DarkCard,
        border   = androidx.compose.foundation.BorderStroke(
            1.dp, if (hasUnread) EmeraldGreen.copy(alpha = 0.4f) else DarkBorderLight
        )
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(
                    if (hasUnread) Brush.linearGradient(listOf(EmeraldDeep, EmeraldGreen))
                    else Brush.linearGradient(listOf(DarkCardAlt, DarkBorderLight))
                ), Alignment.Center
            ) {
                Text(
                    friendId.firstOrNull()?.toString()?.uppercase() ?: "?",
                    fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    color    = if (hasUnread) DarkBg else TextOnDark
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    friendId,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp,
                    color      = TextOnDark,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    conv?.lastMessage?.takeIf { it.isNotEmpty() } ?: "Tap to start chatting",
                    fontSize   = 12.sp,
                    color      = if (hasUnread) EmeraldGreen else TextOnDarkMuted,
                    fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines   = 1
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (conv != null && conv.lastTimestamp > 0L)
                    Text(formatTimestamp(conv.lastTimestamp), fontSize = 10.sp, color = TextOnDarkMuted)
                if (hasUnread) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(EmeraldGreen), Alignment.Center) {
                        Text(
                            if (unreadCount > 9) "9+" else "$unreadCount",
                            fontSize = 10.sp, color = DarkBg, fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

// ─── Conversation screen ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatConversationScreen(
    myTrackId: String,
    myName: String,
    friendId: String,
    conversationId: String,
    onBack: () -> Unit
) {
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    var serverMessages  by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var pendingMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var deletedForMeIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Use stable server IDs for dedup — pending messages have "pending-" prefix
    val allMessages = remember(serverMessages, pendingMessages, deletedForMeIds) {
        val serverIds = serverMessages.map { it.id }.toSet()
        val filtered  = pendingMessages.filter { p ->
            p.id !in serverIds &&           // not yet confirmed by server
                    p.id !in deletedForMeIds
        }
        (serverMessages + filtered)
            .filter { it.id !in deletedForMeIds }
            .sortedBy { it.timestamp }
    }

    var inputText       by remember { mutableStateOf("") }
    var isSending       by remember { mutableStateOf(false) }
    var sendError       by remember { mutableStateOf<String?>(null) }
    var isLoadingMsgs   by remember { mutableStateOf(true) }
    var serverOnline    by remember { mutableStateOf(true) }
    var showClearDialog by remember { mutableStateOf(false) }

    // Long-press menu
    var selectedMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var showMsgMenu by remember { mutableStateOf(false) }

    // Auto-scroll when new messages arrive
    LaunchedEffect(allMessages.size) {
        if (allMessages.isNotEmpty()) {
            listState.animateScrollToItem(allMessages.size - 1)
        }
    }

    // ── Main polling loop — read receipt fires independently ──────────────────
    LaunchedEffect(conversationId) {
        // Fire read receipt without blocking the polling loop
        scope.launch(Dispatchers.IO) {
            chatHttpPost("/api/chat/read", JSONObject().apply {
                put("conversationId", conversationId)
                put("trackId", myTrackId)
            })
        }

        while (true) {
            val msgs = withContext(Dispatchers.IO) {
                try {
                    chatHttpGetArray("/api/chat/messages/$conversationId")
                        ?.let { parseMessages(it) }
                } catch (_: Exception) { null }
            }

            if (msgs != null) {
                serverMessages = msgs
                isLoadingMsgs  = false
                serverOnline   = true

                // Prune pending messages whose server copy has now arrived
                // Match by id (pending prefix) or by senderId+text+approximate timestamp
                val serverSet = msgs.map { it.id }.toSet()
                pendingMessages = pendingMessages.filter { p ->
                    // keep pending only if server doesn't have a message from same sender
                    // with same text sent within 30 seconds
                    val confirmedByServer = msgs.any { s ->
                        s.senderId == p.senderId &&
                                s.text == p.text &&
                                kotlin.math.abs(s.timestamp - p.timestamp) < 30_000L
                    }
                    !confirmedByServer
                }
            } else {
                serverOnline = false
            }

            delay(1_500L)
        }
    }

    // ── Re-send read receipt when coming back to this screen ─────────────────
    DisposableEffect(conversationId) {
        onDispose {
            // no-op: polling loop handles cleanup via coroutine cancellation
        }
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isBlank() || isSending) return

        val optimistic = ChatMessage(
            id             = "pending-${UUID.randomUUID()}",
            conversationId = conversationId,
            senderId       = myTrackId,
            senderName     = myName,
            text           = text,
            timestamp      = System.currentTimeMillis(),
            isPending      = true
        )
        pendingMessages = pendingMessages + optimistic
        inputText  = ""
        sendError  = null
        isSending  = true

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                chatHttpPost("/api/chat/send", JSONObject().apply {
                    put("conversationId", conversationId)
                    put("senderId",       myTrackId)
                    put("senderName",     myName)
                    put("receiverId",     friendId)
                    put("receiverName",   friendId)
                    put("text",           text)
                }) != null
            }
            isSending = false
            if (!ok) {
                // Remove the optimistic message and restore input
                pendingMessages = pendingMessages.filter { it.id != optimistic.id }
                inputText       = text
                sendError       = "Failed to send — check connection"
            } else {
                // Mark read after successful send
                scope.launch(Dispatchers.IO) {
                    chatHttpPost("/api/chat/read", JSONObject().apply {
                        put("conversationId", conversationId)
                        put("trackId", myTrackId)
                    })
                }
            }
        }
    }

    fun clearChat() {
        scope.launch {
            withContext(Dispatchers.IO) { chatHttpDelete("/api/chat/messages/$conversationId") }
            serverMessages  = emptyList()
            pendingMessages = emptyList()
            deletedForMeIds = emptySet()
        }
    }

    fun deleteForEveryone(msg: ChatMessage) {
        // Optimistic removal
        serverMessages  = serverMessages.filter { it.id != msg.id }
        pendingMessages = pendingMessages.filter { it.id != msg.id }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { chatHttpDelete("/api/chat/message/${msg.id}") }
            if (!ok) {
                // Re-fetch on failure
                val msgs = withContext(Dispatchers.IO) {
                    try { chatHttpGetArray("/api/chat/messages/$conversationId")?.let { parseMessages(it) } }
                    catch (_: Exception) { null }
                }
                if (msgs != null) serverMessages = msgs
            }
        }
    }

    fun deleteForMe(msg: ChatMessage) {
        deletedForMeIds = deletedForMeIds + msg.id
    }

    // ─── Context menu dialog ──────────────────────────────────────────────────
    if (showMsgMenu && selectedMsg != null) {
        val msg  = selectedMsg!!
        val isMe = msg.senderId == myTrackId
        Dialog(onDismissRequest = { showMsgMenu = false; selectedMsg = null }) {
            Surface(
                shape    = RoundedCornerShape(4.dp),
                color    = DarkCard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(DarkCardAlt)
                            .padding(horizontal = 20.dp, vertical = 13.dp)
                    ) {
                        Text(msg.text, fontSize = 13.sp, color = TextOnDarkMuted, lineHeight = 20.sp, maxLines = 4)
                    }
                    WaMenuItem("Copy") {
                        clipboard.setText(AnnotatedString(msg.text))
                        showMsgMenu = false; selectedMsg = null
                    }
                    if (isMe) {
                        WaMenuItem("Delete for me") {
                            deleteForMe(msg); showMsgMenu = false; selectedMsg = null
                        }
                        WaMenuItem("Delete for everyone", textColor = RedRecord) {
                            deleteForEveryone(msg); showMsgMenu = false; selectedMsg = null
                        }
                    } else {
                        WaMenuItem("Delete for me") {
                            deleteForMe(msg); showMsgMenu = false; selectedMsg = null
                        }
                    }
                    WaMenuItemLast("Cancel", textColor = TextOnDarkMuted) {
                        showMsgMenu = false; selectedMsg = null
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        DarkClearChatDialog(
            friendId  = friendId,
            onConfirm = { showClearDialog = false; clearChat() },
            onDismiss = { showClearDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(EmeraldDeep, EmeraldGreen))),
                            Alignment.Center
                        ) {
                            Text(
                                friendId.firstOrNull()?.toString()?.uppercase() ?: "?",
                                fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = DarkBg
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                friendId,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize   = 14.sp,
                                color      = TextOnDark,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    Modifier.size(6.dp).clip(CircleShape)
                                        .background(if (serverOnline) GreenOnline else TextOnDarkMuted)
                                )
                                Text(
                                    if (serverOnline) "LiveLoc Chat" else "Reconnecting…",
                                    fontSize = 10.sp,
                                    color    = if (serverOnline) TextOnDarkMuted else AmberWarning
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = EmeraldGreen)
                    }
                },
                actions = {
                    TextButton(
                        onClick          = { showClearDialog = true },
                        contentPadding   = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("Clear", fontSize = 13.sp, color = RedRecord.copy(alpha = 0.8f), fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBg,
        bottomBar = {
            Surface(
                color            = DarkSurface,
                shadowElevation  = 0.dp,
                border           = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderLight)
            ) {
                Column {
                    if (sendError != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(RedRecord.copy(alpha = 0.10f))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚠ $sendError", fontSize = 12.sp, color = RedRecord, modifier = Modifier.weight(1f))
                            TextButton(onClick = { sendError = null }, contentPadding = PaddingValues(0.dp)) {
                                Text("✕", fontSize = 12.sp, color = TextOnDarkMuted)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value           = inputText,
                            onValueChange   = { inputText = it; sendError = null },
                            placeholder     = { Text("Type a message…", color = TextOnDarkMuted) },
                            modifier        = Modifier.weight(1f),
                            shape           = RoundedCornerShape(20.dp),
                            colors          = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor    = EmeraldGreen,
                                unfocusedBorderColor  = DarkBorderLight,
                                focusedContainerColor = DarkCard,
                                unfocusedContainerColor = DarkCard,
                                focusedTextColor      = TextOnDark,
                                unfocusedTextColor    = TextOnDark,
                                cursorColor           = EmeraldGreen
                            ),
                            maxLines        = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendMessage() })
                        )
                        Spacer(Modifier.width(10.dp))
                        val canSend = inputText.isNotBlank() && !isSending
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) Brush.linearGradient(listOf(EmeraldDeep, EmeraldGreen))
                                    else Brush.linearGradient(listOf(DarkCard, DarkCard))
                                )
                                .clickable(enabled = canSend) { sendMessage() },
                            Alignment.Center
                        ) {
                            if (isSending)
                                CircularProgressIndicator(Modifier.size(20.dp), color = DarkBg, strokeWidth = 2.dp)
                            else
                                Icon(
                                    Icons.Default.Send, null,
                                    tint     = if (canSend) DarkBg else TextOnDarkMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            isLoadingMsgs -> {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = EmeraldGreen, strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Loading messages…", fontSize = 12.sp, color = TextOnDarkMuted)
                    }
                }
            }

            allMessages.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(80.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(EmeraldDeep, EmeraldGreen))),
                            Alignment.Center
                        ) { Text("👋", fontSize = 36.sp) }
                        Spacer(Modifier.height(16.dp))
                        Text("No messages yet", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TextOnDark)
                        Spacer(Modifier.height(6.dp))
                        Text("Say hello to $friendId!", fontSize = 13.sp, color = TextOnDarkMuted)
                    }
                }
            }

            else -> {
                LazyColumn(
                    state           = listState,
                    modifier        = Modifier.fillMaxSize().padding(padding),
                    contentPadding  = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allMessages, key = { it.id }) { msg ->
                        DarkMessageBubble(
                            msg         = msg,
                            isMe        = msg.senderId == myTrackId,
                            myTrackId   = myTrackId,
                            friendId    = friendId,
                            onLongPress = { selectedMsg = msg; showMsgMenu = true }
                        )
                    }
                }
            }
        }
    }
}

// ─── WhatsApp-style menu items ────────────────────────────────────────────────

@Composable
private fun WaMenuItem(label: String, textColor: Color = TextOnDark, onClick: () -> Unit) {
    Column {
        Box(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(label, fontSize = 15.sp, color = textColor, fontWeight = FontWeight.Normal)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(DarkBorderLight))
    }
}

@Composable
private fun WaMenuItemLast(label: String, textColor: Color = TextOnDark, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(label, fontSize = 15.sp, color = textColor, fontWeight = FontWeight.Normal)
    }
}

// ─── Clear chat dialog ────────────────────────────────────────────────────────

@Composable
private fun DarkClearChatDialog(friendId: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape    = RoundedCornerShape(24.dp),
            color    = DarkCard,
            modifier = Modifier.fillMaxWidth(),
            border   = androidx.compose.foundation.BorderStroke(1.dp, DarkBorderLight)
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("Clear chat?", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = TextOnDark)
                Spacer(Modifier.height(8.dp))
                Text(
                    "All messages with $friendId will be permanently deleted.",
                    fontSize = 14.sp, color = TextOnDarkMuted, lineHeight = 21.sp
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp))
                            .background(DarkCardAlt).clickable { onDismiss() },
                        Alignment.Center
                    ) { Text("Cancel", color = TextOnDarkMuted, fontWeight = FontWeight.SemiBold) }
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp))
                            .background(RedRecord.copy(alpha = 0.15f)).clickable { onConfirm() },
                        Alignment.Center
                    ) { Text("Delete", fontWeight = FontWeight.ExtraBold, color = RedRecord) }
                }
            }
        }
    }
}

// ─── Message tick indicator ───────────────────────────────────────────────────

@Composable
private fun MessageTicks(msg: ChatMessage, friendId: String) {
    when {
        msg.isPending ->
            Text("🕐", fontSize = 10.sp)
        msg.readBy.contains(friendId) ->
            Text("✓✓", fontSize = 11.sp, color = EmeraldGreen,
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
        else ->
            Text("✓", fontSize = 11.sp, color = TextOnDarkMuted, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Message bubble ───────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DarkMessageBubble(
    msg: ChatMessage,
    isMe: Boolean,
    myTrackId: String = "",
    friendId: String  = "",
    onLongPress: (() -> Unit)? = null
) {
    val timeStr = remember(msg.timestamp) { formatTimestamp(msg.timestamp) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        if (!isMe) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(DarkCard).align(Alignment.Bottom),
                Alignment.Center
            ) {
                Text(
                    msg.senderId.firstOrNull()?.toString()?.uppercase() ?: "?",
                    fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = EmeraldGreen
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Box(
                Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart    = 18.dp,
                            topEnd      = 18.dp,
                            bottomStart = if (isMe) 18.dp else 4.dp,
                            bottomEnd   = if (isMe) 4.dp  else 18.dp
                        )
                    )
                    .background(
                        when {
                            isMe && msg.isPending ->
                                Brush.linearGradient(listOf(EmeraldDeep.copy(alpha = 0.5f), EmeraldGreen.copy(alpha = 0.5f)))
                            isMe ->
                                Brush.linearGradient(listOf(EmeraldDeep, EmeraldGreen))
                            else ->
                                Brush.linearGradient(listOf(DarkCard, DarkCard))
                        }
                    )
                    .then(
                        if (onLongPress != null)
                            Modifier.combinedClickable(onClick = {}, onLongClick = { onLongPress() })
                        else Modifier
                    )
            ) {
                Text(
                    msg.text,
                    modifier   = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize   = 15.sp,
                    color      = if (isMe) DarkBg else TextOnDark,
                    lineHeight = 22.sp
                )
            }

            Spacer(Modifier.height(3.dp))
            Row(
                verticalAlignment   = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(timeStr, fontSize = 10.sp, color = TextOnDarkMuted)
                if (isMe && friendId.isNotBlank()) {
                    MessageTicks(msg = msg, friendId = friendId)
                }
            }
        }
    }
}

// ─── Legacy alias ─────────────────────────────────────────────────────────────

@Composable
fun MessageBubble(msg: ChatMessage, isMe: Boolean) = DarkMessageBubble(msg, isMe)
