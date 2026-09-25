package com.far.mqttcall.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.far.mqttcall.CallAction
import com.far.mqttcall.R
import com.far.mqttcall.CallUiState
import com.far.mqttcall.ConnectionState
import com.far.mqttcall.domain.BrokerProfile
import com.far.mqttcall.domain.defaultAudioPacketIntervalUnits
import com.far.mqttcall.domain.defaultBrokerProfiles
import com.far.mqttcall.domain.scanLan
import com.far.mqttcall.floor.TalkState
import kotlinx.coroutines.launch

private val CornerShape = RoundedCornerShape(8.dp)
private val PttShape = RoundedCornerShape(32.dp)

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
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (managing) "Manage" else "MQTT Call",
                            fontWeight = FontWeight.Black,
                            letterSpacing = TextUnit(2f, TextUnitType.Sp),
                        )
                    },
                    navigationIcon = {
                        if (managing) HeaderIconButton(R.drawable.ic_back_arrow, "Back", onClick = { managing = false })
                    },
                    actions = {
                        if (!managing) HeaderIconButton(R.drawable.ic_exit, "Exit", onExit, danger = true)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            if (managing) {
                ManagementPage(state, onAction, Modifier.padding(padding))
            } else {
                MainCallPage(
                    state = state,
                    onAction = onAction,
                    onPttPress = onPttPress,
                    showMicrophoneSettings = showMicrophoneSettings,
                    openAppSettings = openAppSettings,
                    onManage = { managing = true },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .size(44.dp)
            .border(2.dp, if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, CornerShape)
            .semantics { contentDescription = description },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
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
    val clipboard = LocalClipboardManager.current

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val pttContainerHeight = maxHeight * 0.45f
        Column(Modifier.fillMaxSize()) {
        StatusRow(state)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) {
                Choice("Broker", brokers, { it.name }, state.broker, !busy) {
                    onAction(CallAction.SelectBroker(it))
                }
            }
            Box(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Choice("Channel", state.savedChannels.distinct(), { it }, state.channel, !busy) {
                            onAction(CallAction.SelectChannel(it))
                        }
                    }
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(state.channel)) },
                        enabled = state.channel.isNotBlank(),
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = "Copy channel ID" },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                Choice("Key", state.keySlots.indices.toList(), { "KEY ${it + 1}" }, state.activeKeyIndex, !busy) {
                    onAction(CallAction.ActivateKey(it))
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(
                        colors = if (state.talkerName.isNullOrBlank()) {
                            listOf(Color(0x0D00E676), MaterialTheme.colorScheme.background)
                        } else {
                            listOf(Color(0x2600E676), MaterialTheme.colorScheme.background)
                        },
                    ),
                )
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Current Speaker",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = TextUnit(3f, TextUnitType.Sp),
            )
            val talkerName = state.talkerName?.takeIf { it.isNotBlank() }
            Text(
                talkerName?.uppercase() ?: "NO SIGNAL",
                color = if (talkerName == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary,
                fontSize = if (talkerName.orEmpty().length > 14) 35.2.sp else 48.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = TextUnit(if (talkerName == null) 4f else 2f, TextUnitType.Sp),
                maxLines = 2,
                lineHeight = if (talkerName.orEmpty().length > 14) 39.sp else 53.sp,
            )
            if (state.error != null) {
                Spacer(Modifier.height(10.dp))
                Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (showMicrophoneSettings) PermissionBanner(openAppSettings)

        Box(Modifier.fillMaxWidth().height(pttContainerHeight).padding(horizontal = 20.dp, vertical = 20.dp)) {
            PttButton(state, onAction, onPttPress, Modifier.fillMaxSize())
        }

        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val connected = state.connection == ConnectionState.CONNECTED || state.connection == ConnectionState.CONNECTING
            Button(
                onClick = { onAction(if (connected) CallAction.Disconnect else CallAction.Connect) },
                modifier = Modifier.weight(1f).height(58.dp),
                shape = CornerShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    contentColor = if (connected) Color.White else Color.Black,
                ),
            ) { Text(if (connected) "Disconnect" else "Connect", fontWeight = FontWeight.Black) }
            OutlinedButton(
                onClick = onManage,
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
                    .semantics { contentDescription = "Manage brokers, channels & keys" },
                shape = CornerShape,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
            ) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.size(8.dp))
                Text("Manage", fontWeight = FontWeight.Black)
            }
        }
        }
    }
}

@Composable
private fun StatusRow(state: CallUiState) {
    val connected = state.connection == ConnectionState.CONNECTED
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(12.dp).clip(CircleShape).background(
                    if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                ),
            )
            Text(
                state.connection.name.lowercase().replaceFirstChar(Char::uppercase),
                fontWeight = FontWeight.Black,
                letterSpacing = TextUnit(1f, TextUnitType.Sp),
            )
        }
        Text(
            "Buffer: ${state.bufferState.name.lowercase()}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PermissionBanner(openAppSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Microphone permission required", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = openAppSettings) { Text("App Settings", color = MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
private fun <T> Choice(
    label: String,
    items: List<T>,
    itemLabel: (T) -> String,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = CornerShape,
            contentPadding = PaddingValues(horizontal = 10.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text("$label: ${itemLabel(selected)}", maxLines = 1, fontWeight = FontWeight.Bold)
        }
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
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("User Profile")
        OutlinedTextField(
            value = state.userName,
            onValueChange = { onAction(CallAction.UpdateUserName(it)) },
            label = { Text("User name (optional)") },
            supportingText = { Text("Shown to receivers when you press PTT · Max 64 bytes") },
            enabled = !busy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = CornerShape,
        )

        SectionTitle("Brokers")
        Text("Disconnect to change brokers, channels, or keys.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        (state.savedBrokers + defaultBrokerProfiles()).distinctBy { it.name }.forEach { broker ->
            val active = state.broker == broker
            Card(
                Modifier.fillMaxWidth(),
                shape = CornerShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(2.dp, if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(broker.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                            Text("${broker.host} : ${broker.port}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        if (active) Text("ACTIVE", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Black)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onAction(CallAction.SelectBroker(broker)) }, enabled = !busy) {
                            Text(if (active) "Selected" else "Select")
                        }
                        if (broker in state.savedBrokers) {
                            TextButton(onClick = { editingBroker = broker; brokerDialog = true }, enabled = !busy) { Text("Edit") }
                            TextButton(onClick = { onAction(CallAction.DeleteBroker(broker.name)) }, enabled = !busy) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { editingBroker = null; brokerDialog = true },
                enabled = !busy,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = CornerShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black),
            ) { Text("+ Add Broker", fontWeight = FontWeight.Black) }
            OutlinedButton(
                onClick = {
                    scanning = true
                    scanMessage = null
                    discovered = emptyList()
                    scope.launch {
                        val result = scanLan(context)
                        discovered = result.getOrDefault(emptyList())
                        scanMessage = result.exceptionOrNull()?.message ?: if (discovered.isEmpty()) "No open TCP 1883 ports found" else null
                        scanning = false
                    }
                },
                enabled = !busy && !scanning,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = CornerShape,
            ) { Text(if (scanning) "Scanning…" else "Scan LAN", fontWeight = FontWeight.Black) }
        }
        if (scanMessage != null) Text(scanMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        discovered.forEach { host ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(host, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = {
                    onAction(CallAction.SaveBroker(BrokerProfile(host, host, 1883, false, audioPacketIntervalUnits = 1)))
                    discovered = discovered - host
                }) { Text("Save") }
            }
        }
        Text("Scan checks open TCP ports only; it does not verify MQTT login.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle("Channels")
        state.savedChannels.distinct().forEach { channel ->
            val active = state.channel == channel
            Card(
                Modifier.fillMaxWidth(),
                shape = CornerShape,
                colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface),
                border = BorderStroke(2.dp, if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(channel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    if (active) Text("ACTIVE", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Black)
                    TextButton(onClick = { onAction(CallAction.SelectChannel(channel)) }, enabled = !busy) {
                        Text(if (active) "Selected" else "Select")
                    }
                    TextButton(onClick = { onAction(CallAction.DeleteChannel(channel)) }, enabled = !busy) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = channelInput,
                onValueChange = { channelInput = it },
                label = { Text("New channel (1–16 digits)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1.5f),
                shape = CornerShape,
            )
            Button(
                onClick = { onAction(CallAction.SaveChannel(channelInput)); channelInput = "" },
                enabled = !busy && channelInput.isNotBlank(),
                modifier = Modifier.weight(0.7f).height(56.dp),
                shape = CornerShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black),
            ) { Text("Add", fontWeight = FontWeight.Black) }
        }
        OutlinedButton(onClick = { onAction(CallAction.GenerateChannel) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = CornerShape) {
            Text("Generate Random", fontWeight = FontWeight.Black)
        }

        SectionTitle("Encryption Keys")
        state.keySlots.forEachIndexed { index, key ->
            val active = state.activeKeyIndex == index
            Card(
                Modifier.fillMaxWidth(),
                shape = CornerShape,
                colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface),
                border = BorderStroke(2.dp, if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Slot ${index + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        if (active) Text("ACTIVE", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Black)
                    }
                    OutlinedTextField(
                        value = key,
                        onValueChange = { onAction(CallAction.UpdateKeySlot(index, it)) },
                        label = { Text("Shared key") },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = CornerShape,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
private fun SectionTitle(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Black,
        letterSpacing = TextUnit(2f, TextUnitType.Sp),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
    )
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
        title = { Text(if (existing == null) "Add Broker" else "Edit Broker", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, shape = CornerShape)
                OutlinedTextField(host, { host = it }, label = { Text("Host or IP address") }, singleLine = true, shape = CornerShape)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = CornerShape)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Use TLS", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(tls, { tls = it })
                }
                OutlinedTextField(username, { username = it }, label = { Text("Username (optional)") }, singleLine = true, shape = CornerShape)
                OutlinedTextField(password, { password = it }, label = { Text("Password (optional)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, shape = CornerShape)
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
            ) { Text("Save & Select", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Black) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PttButton(state: CallUiState, onAction: (CallAction) -> Unit, onPttPress: () -> Boolean, modifier: Modifier = Modifier) {
    val enabled = state.canTalk || state.talkState == TalkState.LOCAL_TALKING
    val active = state.talkState == TalkState.LOCAL_TALKING
    val pulse = rememberInfiniteTransition(label = "ptt-pulse")
    val pulseProgress by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_500), repeatMode = RepeatMode.Restart),
        label = "ptt-pulse-progress",
    )
    val faceColor = when {
        active -> MaterialTheme.colorScheme.error
        enabled -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF333945)
    }
    val faceTextColor = when {
        active -> Color.White
        enabled -> Color.Black
        else -> Color(0xFF5C667A)
    }
    val disabledLabel = when {
        state.connection != ConnectionState.CONNECTED -> "LOCKED / OFFLINE"
        !state.microphoneGranted -> "MICROPHONE REQUIRED"
        state.talkState == TalkState.REMOTE_TALKING -> "CHANNEL BUSY"
        else -> "PUSH TO TALK"
    }
    Box(
        modifier = modifier.drawBehind {
            if (active) {
                val spread = 6.dp.toPx() + pulseProgress * 14.dp.toPx()
                drawRoundRect(
                    color = Color(0xFFFF5252).copy(alpha = (1f - pulseProgress) * 0.75f),
                    topLeft = Offset(-spread / 2f, -spread / 2f),
                    size = Size(size.width + spread, size.height + spread),
                    cornerRadius = CornerRadius(32.dp.toPx() + spread / 2f),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = if (active) 8.dp.toPx() else 0f }
            .shadow(if (active) 4.dp else if (enabled) 12.dp else 0.dp, PttShape, clip = false)
            .clip(PttShape)
            .background(faceColor)
            .border(4.dp, if (enabled) Color.White else Color(0xFF252932), PttShape)
            .pointerInput(enabled, state.microphoneGranted) {
                detectTapGestures(onPress = {
                    if (!onPttPress()) return@detectTapGestures
                    try { tryAwaitRelease() } finally { onAction(CallAction.ReleaseTalk) }
                })
            }
            .semantics { role = Role.Button; contentDescription = "Push to talk" },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_ptt_microphone),
                    contentDescription = null,
                    tint = faceTextColor,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    when {
                        active -> "RELEASE TO STOP"
                        enabled -> "PUSH TO TALK"
                        else -> disabledLabel
                    },
                    color = faceTextColor,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = TextUnit(2f, TextUnitType.Sp),
                )
            }
        }
    }
}
