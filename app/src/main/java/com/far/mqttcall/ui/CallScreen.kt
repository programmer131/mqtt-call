package com.far.mqttcall.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.far.mqttcall.CallAction
import com.far.mqttcall.CallUiState
import com.far.mqttcall.ConnectionState
import com.far.mqttcall.crypto.SecurityLevel
import com.far.mqttcall.domain.defaultBrokerProfiles
import com.far.mqttcall.domain.BrokerProfile
import com.far.mqttcall.floor.TalkState

@Composable
fun CallScreen(
    state: CallUiState,
    onAction: (CallAction) -> Unit,
    requestMicrophone: () -> Unit,
) {
    var brokerMenuOpen by remember { mutableStateOf(false) }
    var addBrokerOpen by remember { mutableStateOf(false) }
    var brokerName by remember { mutableStateOf("") }
    var brokerHost by remember { mutableStateOf("") }
    var brokerPort by remember { mutableStateOf("1883") }
    var brokerTls by remember { mutableStateOf(false) }
    var brokerUsername by remember { mutableStateOf("") }
    var brokerPassword by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val brokerProfiles = remember(state.savedBrokers) {
        (defaultBrokerProfiles() + state.savedBrokers).distinct()
    }
    val connected = state.connection == ConnectionState.CONNECTED

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("MQTT Call", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Private push-to-talk over any MQTT broker",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    Box {
                        OutlinedButton(
                            onClick = { brokerMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(state.broker.name) }
                        DropdownMenu(
                            expanded = brokerMenuOpen,
                            onDismissRequest = { brokerMenuOpen = false },
                        ) {
                            brokerProfiles.forEach { broker ->
                                DropdownMenuItem(
                                    text = { Text(broker.name) },
                                    onClick = {
                                        brokerMenuOpen = false
                                        onAction(CallAction.SelectBroker(broker))
                                    },
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            brokerName = ""
                            brokerHost = ""
                            brokerPort = "1883"
                            brokerTls = false
                            brokerUsername = ""
                            brokerPassword = ""
                            addBrokerOpen = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add broker") }
                    Text("${state.broker.host}:${state.broker.port}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Public test brokers are shared and may be unavailable. Use TLS and a unique key for anything sensitive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onAction(if (connected) CallAction.Disconnect else CallAction.Connect) },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (connected) "Disconnect" else "Connect") }
                        StatusPill(connectionLabel(state.connection), connected)
                    }
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Channel security", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.channel,
                        onValueChange = { onAction(CallAction.UpdateChannel(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Channel number") },
                        supportingText = { Text("Topic: ${state.topic.ifEmpty { "invalid channel" }}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Text("Saved encryption keys", style = MaterialTheme.typography.titleSmall)
                    state.keySlots.forEachIndexed { index, key ->
                        val active = state.activeKeyIndex == index
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (active) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                OutlinedTextField(
                                    value = key,
                                    onValueChange = { onAction(CallAction.UpdateKeySlot(index, it)) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(if (index == 0) "Default shared key" else "Saved key ${index + 1}") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    enabled = !connected,
                                    singleLine = true,
                                )
                                IconButton(
                                    onClick = { onAction(CallAction.GenerateKeySlot(index)) },
                                    enabled = !connected,
                                    modifier = Modifier.semantics { contentDescription = "Regenerate key ${index + 1}" },
                                ) {
                                    Text("↻", style = MaterialTheme.typography.titleLarge)
                                }
                                if (active) {
                                    Text(
                                        "ACTIVE",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                } else {
                                    TextButton(
                                        onClick = { onAction(CallAction.ActivateKey(index)) },
                                        enabled = !connected && key.isNotBlank(),
                                    ) { Text("Use") }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.key)) }) { Text("Copy active") }
                        TextButton(
                            onClick = { onAction(CallAction.ResetDefaults) },
                            enabled = !connected,
                        ) { Text("Reset") }
                    }
                    Text(
                        "Encryption: ${securityLabel(state.securityLevel)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Call status", style = MaterialTheme.typography.titleMedium)
                    Text("Talk: ${state.talkState.name.lowercase().replace('_', ' ')}")
                    Text("Audio buffer: ${state.bufferState.name.lowercase()}")
                    Text("Sent ${state.sentBatches} batches · received ${state.receivedBatches}")
                    if (state.error != null) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            val pttEnabled = state.canTalk || state.talkState == TalkState.LOCAL_TALKING
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(CircleShape)
                    .background(if (pttEnabled) MaterialTheme.colorScheme.primary else Color.Gray)
                    .pointerInput(pttEnabled, state.microphoneGranted) {
                        detectTapGestures(
                            onPress = {
                                if (!state.microphoneGranted) {
                                    requestMicrophone()
                                    return@detectTapGestures
                                }
                                if (!state.canTalk && state.talkState != TalkState.LOCAL_TALKING) return@detectTapGestures
                                onAction(CallAction.PressTalk)
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    onAction(CallAction.ReleaseTalk)
                                }
                            },
                        )
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Push to talk"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.talkState == TalkState.LOCAL_TALKING) "RELEASE TO STOP" else "PUSH TO TALK",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.size(4.dp))
        }
    }

    if (addBrokerOpen) {
        val parsedPort = brokerPort.toIntOrNull()
        AlertDialog(
            onDismissRequest = { addBrokerOpen = false },
            title = { Text("Add MQTT broker") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "This broker is saved on this device and selected automatically next time.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = brokerName,
                        onValueChange = { brokerName = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = brokerHost,
                        onValueChange = { brokerHost = it },
                        label = { Text("Host or IP address") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = brokerPort,
                        onValueChange = { brokerPort = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use TLS", modifier = Modifier.weight(1f))
                        Switch(checked = brokerTls, onCheckedChange = { brokerTls = it })
                    }
                    OutlinedTextField(
                        value = brokerUsername,
                        onValueChange = { brokerUsername = it },
                        label = { Text("Username (optional)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = brokerPassword,
                        onValueChange = { brokerPassword = it },
                        label = { Text("Password (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = brokerHost.isNotBlank() && parsedPort in 1..65535,
                    onClick = {
                        onAction(
                            CallAction.SaveBroker(
                                BrokerProfile(
                                    name = brokerName,
                                    host = brokerHost,
                                    port = parsedPort ?: 1883,
                                    tls = brokerTls,
                                    username = brokerUsername,
                                    password = brokerPassword,
                                ),
                            ),
                        )
                        addBrokerOpen = false
                    },
                ) { Text("Save and select") }
            },
            dismissButton = {
                TextButton(onClick = { addBrokerOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusPill(label: String, active: Boolean) {
    Text(
        label,
        modifier = Modifier.padding(vertical = 12.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

private fun connectionLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.CONNECTING -> "Connecting"
    ConnectionState.ERROR -> "Error"
    ConnectionState.DISCONNECTED -> "Offline"
}

private fun securityLabel(level: SecurityLevel?): String = when (level) {
    SecurityLevel.STRONGBOX -> "StrongBox"
    SecurityLevel.TRUSTED_ENVIRONMENT -> "Trusted environment"
    SecurityLevel.SOFTWARE -> "Software fallback"
    null -> "Not connected"
}
