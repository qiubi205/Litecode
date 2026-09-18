// v0.6.0 Compose 换皮（DeepSeek 视觉）：装配层 = 数据类 + ChatScreen 骨架
package com.qiubi205.litecode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** role: "user" | "assistant" | "status" */
data class ChatEntry(val role: String, val text: String)
data class SessionUi(val id: String, val name: String, val count: Int)
data class ConfigUi(val baseUrl: String, val apiKey: String, val model: String, val rounds: Int)

internal val BrandBlue = Color(0xFF4D6BFE)
internal val BgLight = Color(0xFFF3F4F6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    entries: List<ChatEntry>,
    busy: Boolean,
    a11yReady: Boolean,
    sessions: List<SessionUi>,
    activeSessionId: String?,
    config: ConfigUi,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onSaveConfig: (String, String, String, Int) -> Unit,
    onOpenA11ySettings: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size, busy) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                SessionDrawerContent(
                    sessions = sessions,
                    activeSessionId = activeSessionId,
                    a11yReady = a11yReady,
                    onNewSession = onNewSession,
                    onSelectSession = onSelectSession,
                    onDeleteSession = onDeleteSession,
                    onOpenA11ySettings = onOpenA11ySettings,
                    closeDrawer = { scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = BgLight,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BgLight,
                        titleContentColor = Color(0xFF1A1A1A),
                    ),
                    title = { Text("Litecode", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", fontSize = 20.sp, color = Color(0xFF1A1A1A))
                        }
                    },
                    actions = {
                        TextButton(onClick = { showSettings = true }) {
                            Text("⚙", fontSize = 18.sp, color = Color(0xFF1A1A1A))
                        }
                    },
                )
            },
            bottomBar = { InputRow(busy = busy, onSend = onSend, onStop = onStop) },
        ) { pad ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries) { e -> MessageBubble(e) }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            config = config,
            onSave = onSaveConfig,
            onDismiss = { showSettings = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputRow(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("给 Litecode 下达指令…", fontSize = 14.sp) },
            maxLines = 4,
            shape = RoundedCornerShape(16.dp),
        )
        if (busy) {
            TextButton(onClick = onStop) { Text("⏹", fontSize = 18.sp) }
        } else {
            TextButton(
                onClick = {
                    val t = draft.trim()
                    if (t.isNotEmpty()) {
                        draft = ""
                        onSend(t)
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Text("➤", fontSize = 18.sp, color = if (draft.isNotBlank()) BrandBlue else Color(0xFFB0B4BC))
            }
        }
    }
}
