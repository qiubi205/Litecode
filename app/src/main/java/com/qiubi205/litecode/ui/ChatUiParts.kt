// v0.6.0 Compose 零件库：气泡/设置弹层/抽屉
package com.qiubi205.litecode.ui

import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageBubble(entry: ChatEntry) {
    when (entry.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .fillMaxWidth(0.85f)
                    .background(BrandBlue, RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Text(entry.text, color = Color.White, fontSize = 14.sp)
            }
        }
        "assistant" -> Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth(0.85f)
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                AndroidView(
                    factory = { TextView(it) },
                    update = { tv ->
                        tv.text = Markdown.render(entry.text)
                        tv.textSize = 15f
                        tv.setTextIsSelectable(true)
                        tv.setLineSpacing(0f, 1.3f)
                    }
                )
            }
        }
        "status" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(entry.text, fontSize = 12.sp, color = Color(0xFF8A8F98))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(config: ConfigUi, onSave: (String, String, String, Int) -> Unit, onDismiss: () -> Unit) {
    var url by remember(config) { mutableStateOf(config.baseUrl) }
    var key by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var rounds by remember(config) { mutableStateOf(config.rounds.toString()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = rounds, onValueChange = { rounds = it }, label = { Text("工具循环上限") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onSave(url, key, model, rounds.toIntOrNull() ?: 25) },
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDrawerContent(
    sessions: List<SessionUi>, activeSessionId: String?, a11yReady: Boolean,
    onNewSession: () -> Unit, onSelectSession: (String) -> Unit, onDeleteSession: (String) -> Unit,
    onOpenA11ySettings: () -> Unit, closeDrawer: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpenA11ySettings(); closeDrawer() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).background(if (a11yReady) Color(0xFF34C759) else Color(0xFFFF3B30), CircleShape))
            Text(
                if (a11yReady) "无障碍 已开启" else "无障碍 未开启",
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onNewSession(); closeDrawer() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("➕ 新建会话", color = BrandBlue, fontSize = 15.sp)
        }
        sessions.forEach { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelectSession(s.id); closeDrawer() }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.name,
                        fontSize = 15.sp,
                        fontWeight = if (s.id == activeSessionId) FontWeight.Bold else FontWeight.Normal,
                        color = if (s.id == activeSessionId) BrandBlue else Color(0xFF1A1A1A)
                    )
                    Text("${s.count} 条", fontSize = 12.sp, color = Color(0xFF8A8F98))
                }
                Text("✕", fontSize = 14.sp, color = Color(0xFF8A8F98), modifier = Modifier.clickable { onDeleteSession(s.id) })
            }
        }
    }
}
