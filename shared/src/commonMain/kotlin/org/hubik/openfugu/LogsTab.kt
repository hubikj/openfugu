package org.hubik.openfugu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogsTab(
    logMessages: List<String>,
    appVersion: String,
    onShowMessage: (String) -> Unit,
    onSaveLogs: () -> String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        LogHeader(logMessages, appVersion, onShowMessage, onSaveLogs)
        Spacer(modifier = Modifier.height(4.dp))
        LogDisplay(messages = logMessages, modifier = Modifier.weight(1f))
    }
}

@Composable
fun LogHeader(
    messages: List<String>,
    appVersion: String,
    onShowMessage: (String) -> Unit,
    onSaveLogs: () -> String
) {
    val clipboard = LocalClipboardManager.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Log:", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "OpenFugu $appVersion",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(messages.joinToString("\n")))
                onShowMessage("Logs copied!")
            }) {
                Text("Copy", fontSize = 12.sp)
            }
            // An empty result means the platform gave its own feedback
            // (e.g. the iOS share sheet) — nothing further to show.
            TextButton(onClick = { onSaveLogs().takeIf { it.isNotEmpty() }?.let(onShowMessage) }) {
                Text("Save", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun LogDisplay(messages: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(8.dp)
        ) {
            items(messages) { msg ->
                Text(
                    msg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
