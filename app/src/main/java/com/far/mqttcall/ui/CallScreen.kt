package com.far.mqttcall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.far.mqttcall.CallAction
import com.far.mqttcall.CallUiState
import com.far.mqttcall.ConnectionState
import com.far.mqttcall.domain.BrokerProfile
import com.far.mqttcall.domain.defaultAudioPacketIntervalUnits
import com.far.mqttcall.domain.defaultBrokerProfiles
import com.far.mqttcall.domain.scanLan
import com.far.mqttcall.floor.TalkState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(
    state: CallUiState,
    onAction: (CallAction) -> Unit,
    onPttPress: () -> Boolean,
    onExit: () -> Unit,
    showMicrophoneSettings: Boolean,
    openAppSettings: () -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    BackHandler(managing) { managing = false }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(if (managing) "Manage" else "MQTT Call") },
                navigationIcon = { if (managing) TextButton(onClick = { managing = false }) { Text("Back") } },
                actions = { if (!managing) TextButton(onClick = onExit) { Text("Exit") } },
            )
        }) { padding ->
            if (managing) {
                ManagementPage(state, onAction, Modifier.padding(padding))
            } else {
                MainCallPage(
                    state, onAction, onPttPress,
                    showMicrophoneSettings, openAppSettings,
                    onManage = { managing = true },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun MainCallPage(
    state: CallUiState,
    onAction: (CallAction) -> Unit,
    onPttPress: () -> Boolean,
    showMicrophoneSettings: Boolean,
    openAppSettings: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.connection == ConnectionState.CONNECTED || state.connection == ConnectionState.CONNECTING
    val brokers = (state.savedBrokers + defaultBrokerProfiles() + state.broker).distinctBy { it.name }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PttButton(state, onAction, onPttPress, Modifier.fillMaxWidth().height(190.dp))
        if (showMicrophoneSettings) {
            Text("Microphone permission is required to talk.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = openAppSettings) { Text("App Settings") }
        }
        Choice("Broker: ${state.broker.name}", brokers, { it.name }, !busy) {
            onAction(CallAction.SelectBroker(it))
        }
        Choice("Channel: ${state.channel}", state.savedChannels.distinct(), { it }, !busy) {
            onAction(CallAction.SelectChannel(it))
        }
        Choice("Key: slot ${state.activeKeyIndex + 1}", state.keySlots.indices.toList(), { "Slot ${it + 1}" }, !busy) {
            onAction(CallAction.ActivateKey(it))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onAction(if (busy) CallAction.Disconnect else CallAction.Connect) }) {
                Text(if (busy) "Disconnect" else "Connect")
            }
            Text(state.connection.name.lowercase().replaceFirstChar(Char::uppercase))
        }
        Text("Talk: ${state.talkState.name.lowercase().replace('_', ' ')} · Buffer: ${state.bufferState.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onManage) { Text("Manage brokers, channels & keys") }
    }
}

@Composable
private fun <T> Choice(label: String, items: List<T>, itemLabel: (T) -> String, enabled: Boolean, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label, maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(itemLabel(item)) }, onClick = {
                    open = false
                    onSelect(item)
                })
            }
        }
    }
}

@Composable
private fun ManagementPage(state: CallUiState, onAction: (CallAction) -> Unit, modifier: Modifier = Modifier) {
    val busy = state.connection == ConnectionState.CONNECTED || state.connection == ConnectionState.CONNECTING
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var editingBroker by remember { mutableStateOf<BrokerProfile?>(null) }
    var brokerDialog by remember { mutableStateOf(false) }
    var channelInput by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var discovered by remember { mutableStateOf(emptyList<String>()) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Brokers", style = MaterialTheme.typography.titleLarge)
        Text("Disconnect to change brokers, channels, or keys.", style = MaterialTheme.typography.bodySmall)
        (state.savedBrokers + defaultBrokerProfiles()).distinctBy { it.name }.forEach { broker ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text("${broker.name} · ${broker.host}:${broker.port}")
                    Row {
                        TextButton(onClick = { onAction(CallAction.SelectBroker(broker)) }, enabled = !busy) { Text(if (state.broker == broker) "Selected" else "Select") }
                        if (broker in state.savedBrokers) {
                            TextButton(onClick = { editingBroker = broker; brokerDialog = true }, enabled = !busy) { Text("Edit") }
                            TextButton(onClick = { onAction(CallAction.DeleteBroker(broker.name)) }, enabled = !busy) { Text("Delete") }
                        }
                    }
                }
            }
        }
        Row {
            TextButton(onClick = { editingBroker = null; brokerDialog = true }, enabled = !busy) { Text("Add broker") }
            TextButton(onClick = {
                scanning = true
                scanMessage = null
                discovered = emptyList()
                scope.launch {
                    val result = scanLan(context)
                    discovered = result.getOrDefault(emptyList())
                    scanMessage = result.exceptionOrNull()?.message ?: if (discovered.isEmpty()) "No open TCP 1883 ports found" else null
                    scanning = false
                }
            }, enabled = !busy && !scanning) { Text(if (scanning) "Scanning…" else "Scan LAN") }
        }
        if (scanMessage != null) Text(scanMessage!!)
        discovered.forEach { host ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(host, Modifier.weight(1f))
                TextButton(onClick = {
                    onAction(CallAction.SaveBroker(BrokerProfile(host, host, 1883, false, audioPacketIntervalUnits = 1)))
                    discovered = discovered - host
                }) { Text("Save") }
            }
        }
        Text("Scan checks open TCP ports only; it does not verify MQTT login.", style = MaterialTheme.typography.bodySmall)

        Text("Channels", style = MaterialTheme.typography.titleLarge)
        state.savedChannels.distinct().forEach { channel ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel, Modifier.weight(1f))
                TextButton(onClick = { onAction(CallAction.SelectChannel(channel)) }, enabled = !busy) { Text(if (state.channel == channel) "Selected" else "Select") }
                TextButton(onClick = { onAction(CallAction.DeleteChannel(channel)) }, enabled = !busy) { Text("Delete") }
            }
        }
        OutlinedTextField(
            value = channelInput,
            onValueChange = { channelInput = it },
            label = { Text("New channel (1–16 ASCII digits)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = { onAction(CallAction.SaveChannel(channelInput)); channelInput = "" }, enabled = !busy && channelInput.isNotBlank()) { Text("Add channel") }
            TextButton(onClick = { onAction(CallAction.GenerateChannel) }, enabled = !busy) { Text("Generate random") }
        }

        Text("Keys", style = MaterialTheme.typography.titleLarge)
        state.keySlots.forEachIndexed { index, key ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text("Slot ${index + 1}${if (state.activeKeyIndex == index) " · Active" else ""}")
                    OutlinedTextField(
                        value = key,
                        onValueChange = { onAction(CallAction.UpdateKeySlot(index, it)) },
                        label = { Text("Shared key") },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        TextButton(onClick = { onAction(CallAction.ActivateKey(index)) }, enabled = !busy && key.isNotBlank()) { Text("Use") }
                        TextButton(onClick = { onAction(CallAction.GenerateKeySlot(index)) }, enabled = !busy) { Text("Generate") }
                        TextButton(onClick = { onAction(CallAction.UpdateKeySlot(index, "")) }, enabled = !busy) { Text("Clear") }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(key)) }, enabled = key.isNotBlank()) { Text("Copy") }
                    }
                }
            }
        }
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
    }
    if (brokerDialog) BrokerDialog(editingBroker, onDismiss = { brokerDialog = false }) { broker ->
        val old = editingBroker
        onAction(if (old == null) CallAction.SaveBroker(broker) else CallAction.EditBroker(old.name, broker))
        brokerDialog = false
    }
}

@Composable
private fun BrokerDialog(existing: BrokerProfile?, onDismiss: () -> Unit, onSave: (BrokerProfile) -> Unit) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var host by remember(existing) { mutableStateOf(existing?.host ?: "") }
    var port by remember(existing) { mutableStateOf((existing?.port ?: 1883).toString()) }
    var tls by remember(existing) { mutableStateOf(existing?.tls ?: false) }
    var username by remember(existing) { mutableStateOf(existing?.username ?: "") }
    var password by remember(existing) { mutableStateOf(existing?.password ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add broker" else "Edit broker") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("Host or IP address") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use TLS", Modifier.weight(1f))
                    Switch(tls, { tls = it })
                }
                OutlinedTextField(username, { username = it }, label = { Text("Username (optional)") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Password (optional)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(BrokerProfile(
                        name, host, port.toInt(), tls, username, password,
                        audioPacketIntervalUnits = if (existing?.host == host) existing.audioPacketIntervalUnits else defaultAudioPacketIntervalUnits(host),
                    ))
                },
                enabled = host.isNotBlank() && port.toIntOrNull() in 1..65535,
            ) { Text("Save and select") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PttButton(state: CallUiState, onAction: (CallAction) -> Unit, onPttPress: () -> Boolean, modifier: Modifier = Modifier) {
    val enabled = state.canTalk || state.talkState == TalkState.LOCAL_TALKING
    Box(
        modifier.clip(CircleShape)
            .background(if (enabled) MaterialTheme.colorScheme.primary else Color.Gray)
            .pointerInput(enabled, state.microphoneGranted) {
                detectTapGestures(onPress = {
                    if (!onPttPress()) return@detectTapGestures
                    try { tryAwaitRelease() } finally { onAction(CallAction.ReleaseTalk) }
                })
            }
            .semantics { role = Role.Button; contentDescription = "Push to talk" },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (state.talkState == TalkState.LOCAL_TALKING) "RELEASE TO STOP" else "PUSH TO TALK", color = Color.White, style = MaterialTheme.typography.titleLarge)
    }
}
