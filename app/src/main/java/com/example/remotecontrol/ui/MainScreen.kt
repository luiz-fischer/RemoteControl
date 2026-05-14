package com.example.remotecontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.remotecontrol.discovery.DeviceDiscoveryManager
import com.example.remotecontrol.model.DeviceBrand
import com.example.remotecontrol.model.RemoteDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    discoveryManager: DeviceDiscoveryManager,
    viewModel: RemoteViewModel = viewModel()
) {
    val devices by discoveryManager.devices.collectAsState()
    val status by viewModel.status.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var selectedDevice by remember { mutableStateOf<RemoteDevice?>(null) }
    var deviceToRename by remember { mutableStateOf<RemoteDevice?>(null) }

    LaunchedEffect(status) {
        if (status == ConnectionStatus.IDLE) selectedDevice = null
    }

    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Universal Remote", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (selectedDevice != null) {
                        IconButton(onClick = {
                            selectedDevice = null
                            viewModel.resetStatus()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                        }
                    }
                },
                actions = {
                    if (selectedDevice == null) {
                        IconButton(onClick = { discoveryManager.startDiscovery() }) {
                            Icon(Icons.Default.Refresh, "Atualizar")
                        }
                    } else if (selectedDevice?.brand == DeviceBrand.ANDROID_TV) {
                        IconButton(onClick = {
                            viewModel.setDevice(selectedDevice!!, forcePairing = true)
                        }) {
                            Icon(Icons.Default.Link, "Forçar Pareamento")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF1E1E1E),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (selectedDevice == null) {
                DeviceList(
                    devices = devices,
                    onDeviceSelected = {
                        selectedDevice = it
                        viewModel.setDevice(it, forcePairing = false)
                    },
                    onRenameRequest = { deviceToRename = it }
                )
            } else {
                RemoteInterface(viewModel) {
                    selectedDevice = null
                    viewModel.resetStatus()
                }
            }

            if (status == ConnectionStatus.PAIRING_REQUIRED) {
                PairingDialog(
                    onSubmit = { viewModel.submitPin(it) },
                    onCancel = { viewModel.resetStatus() }
                )
            }

            errorMessage?.let { msg ->
                AlertDialog(
                    onDismissRequest = { viewModel.clearError() },
                    title = { Text("Erro de Conexão") },
                    text = { Text(msg) },
                    confirmButton = {
                        Button(onClick = {
                            viewModel.clearError()
                            selectedDevice = null
                            viewModel.resetStatus()
                        }) { Text("Voltar") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.clearError()
                            selectedDevice?.let { viewModel.setDevice(it, forcePairing = true) }
                        }) { Text("Parear") }
                    }
                )
            }

            deviceToRename?.let { d ->
                RenameDialog(
                    currentNickname = d.nickname ?: d.originalName,
                    onConfirm = { newName ->
                        discoveryManager.saveNickname(d.host, newName)
                        deviceToRename = null
                    },
                    onDismiss = { deviceToRename = null }
                )
            }

            Text(
                text = "Powered By Luiz Fischer",
                color = Color.Gray.copy(alpha = 0.5f),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
            )
        }
    }
}

@Composable
fun DeviceList(
    devices: List<RemoteDevice>,
    onDeviceSelected: (RemoteDevice) -> Unit,
    onRenameRequest: (RemoteDevice) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Dispositivos na rede",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Procurando dispositivos…",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(devices) { device ->
                    DeviceCard(device, onClick = { onDeviceSelected(device) }, onRenameRequest)
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: RemoteDevice,
    onClick: () -> Unit,
    onRenameRequest: (RemoteDevice) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Tv,
                contentDescription = null,
                tint = brandColor(device.brand),
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.nickname ?: device.originalName,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${device.host} • ${brandLabel(device.brand)}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = { onRenameRequest(device) }) {
                Icon(Icons.Default.Edit, "Renomear", tint = Color.Gray)
            }
        }
    }
}

private fun brandLabel(brand: DeviceBrand): String = when (brand) {
    DeviceBrand.ANDROID_TV -> "Android TV"
    DeviceBrand.SAMSUNG -> "Samsung"
    DeviceBrand.LG -> "LG"
    DeviceBrand.ROKU -> "Roku"
    DeviceBrand.GENERIC_DLNA -> "DLNA"
    DeviceBrand.UNKNOWN -> "Desconhecido"
}

private fun brandColor(brand: DeviceBrand): Color = when (brand) {
    DeviceBrand.ANDROID_TV -> Color(0xFFBB86FC)
    DeviceBrand.SAMSUNG -> Color(0xFF1428A0)
    DeviceBrand.LG -> Color(0xFFA50034)
    else -> Color(0xFF888888)
}

@Composable
fun RemoteInterface(viewModel: RemoteViewModel, onBack: () -> Unit) {
    val status by viewModel.status.collectAsState()
    val scroll = rememberScrollState()
    val context = LocalContext.current

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) {
                Toast.makeText(context, "“$text”", Toast.LENGTH_SHORT).show()
                viewModel.sendText(text)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (status == ConnectionStatus.CONNECTED) "Conectado" else "Conectando…",
                color = if (status == ConnectionStatus.CONNECTED) Color(0xFF4CAF50) else Color.Yellow,
                fontSize = 14.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale para pesquisar…")
                    }
                    try {
                        voiceLauncher.launch(intent)
                    } catch (_: Exception) {
                        Toast.makeText(
                            context,
                            "Reconhecimento de voz indisponível neste dispositivo",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) {
                    Icon(Icons.Default.Mic, "Microfone", tint = Color.White)
                }
                IconButton(onClick = { viewModel.onKeyPress(26) }) {
                    Icon(Icons.Default.PowerSettingsNew, "Power", tint = Color(0xFFFF5252))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CircleRemoteButton(Icons.Default.Home, "Home", Color(0xFF424242)) {
                viewModel.onKeyPress(3) // KEYCODE_HOME
            }
            CircleRemoteButton(Icons.Default.Settings, "Config", Color(0xFF424242)) {
                viewModel.onKeyPress(176) // KEYCODE_SETTINGS
            }
            CircleRemoteButton(Icons.AutoMirrored.Filled.Input, "Input", Color(0xFF424242)) {
                viewModel.onKeyPress(178)
            }
        }

        Spacer(Modifier.height(32.dp))

        DPad(viewModel)

        Spacer(Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { viewModel.onKeyPress(24) }) {
                    Icon(Icons.Default.Add, "Vol+", tint = Color.White)
                }
                Text("VOL", color = Color.Gray, fontSize = 12.sp)
                IconButton(onClick = { viewModel.onKeyPress(25) }) {
                    Icon(Icons.Default.Remove, "Vol-", tint = Color.White)
                }
            }

            CircleRemoteButton(Icons.AutoMirrored.Filled.VolumeOff, "Mute", Color(0xFF424242)) {
                viewModel.onKeyPress(164)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { viewModel.onKeyPress(166) }) {
                    Icon(Icons.Default.KeyboardArrowUp, "CH+", tint = Color.White)
                }
                Text("CH", color = Color.Gray, fontSize = 12.sp)
                IconButton(onClick = { viewModel.onKeyPress(167) }) {
                    Icon(Icons.Default.KeyboardArrowDown, "CH-", tint = Color.White)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        DynamicKeyboard(viewModel = viewModel)

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AppButton("Netflix", Color(0xFFE50914)) {
                viewModel.launchApp("https://www.netflix.com/title")
            }
            AppButton("YouTube", Color(0xFFFF0000)) {
                viewModel.launchApp("https://www.youtube.com")
            }
            AppButton("Disney+", Color(0xFF113CCF)) {
                viewModel.launchApp("https://www.disneyplus.com")
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun DPad(viewModel: RemoteViewModel) {
    Box(
        modifier = Modifier.size(220.dp).clip(CircleShape).background(Color(0xFF1E1E1E)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            modifier = Modifier.align(Alignment.TopCenter).size(60.dp),
            onClick = { viewModel.onKeyPress(19) }
        ) { Icon(Icons.Default.KeyboardArrowUp, "Cima", tint = Color.White, modifier = Modifier.size(40.dp)) }

        IconButton(
            modifier = Modifier.align(Alignment.BottomCenter).size(60.dp),
            onClick = { viewModel.onKeyPress(20) }
        ) { Icon(Icons.Default.KeyboardArrowDown, "Baixo", tint = Color.White, modifier = Modifier.size(40.dp)) }

        IconButton(
            modifier = Modifier.align(Alignment.CenterStart).size(60.dp),
            onClick = { viewModel.onKeyPress(21) }
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Esquerda", tint = Color.White, modifier = Modifier.size(40.dp)) }

        IconButton(
            modifier = Modifier.align(Alignment.CenterEnd).size(60.dp),
            onClick = { viewModel.onKeyPress(22) }
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Direita", tint = Color.White, modifier = Modifier.size(40.dp)) }

        Surface(
            modifier = Modifier.size(72.dp).clickable { viewModel.onKeyPress(23) },
            shape = CircleShape,
            color = Color(0xFFBB86FC)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("OK", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CircleRemoteButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(56.dp).clickable { onClick() },
            shape = CircleShape,
            color = color
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = Color.White)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.Gray, fontSize = 10.sp)
    }
}

@Composable
fun AppButton(name: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.width(100.dp).height(40.dp)
    ) {
        Text(name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PairingDialog(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    AlertDialog(
        onDismissRequest = {
            keyboardController?.hide()
            onCancel()
        },
        title = { Text("Digite o código exibido na TV") },
        text = {
            TextField(
                value = pin,
                onValueChange = { if (it.length <= 6) pin = it.uppercase() },
                label = { Text("6 caracteres (0-9, A-F)") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = {
                keyboardController?.hide()
                onSubmit(pin)
            }) { Text("Confirmar") }
        },
        dismissButton = {
            TextButton(onClick = {
                keyboardController?.hide()
                onCancel()
            }) { Text("Cancelar") }
        }
    )
}

@Composable
fun RenameDialog(
    currentNickname: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentNickname) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renomear dispositivo") },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Apelido") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

/**
 * Teclado dinâmico estilo Android: 3 modos (números, letras, símbolos) com
 * botões pra alternar entre eles. No modo letras, um Shift troca caixa.
 * Cada tecla traduz pra um KEYCODE do Android e dispara via viewModel.onKeyPress.
 * Caracteres sem KEYCODE correspondente ficam apenas como rótulo visual e
 * não fazem nada — fica explícito que o stick não vai receber.
 */
private enum class KeyboardMode { NUMBERS, LETTERS, SYMBOLS }

@Composable
fun DynamicKeyboard(viewModel: RemoteViewModel) {
    var mode by remember { mutableStateOf(KeyboardMode.NUMBERS) }
    var shift by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (mode) {
            KeyboardMode.NUMBERS -> NumberRows(onChar = { c -> sendChar(viewModel, c) })
            KeyboardMode.LETTERS -> LetterRows(
                shift = shift,
                onChar = { c -> sendChar(viewModel, c) },
                onShift = { shift = !shift }
            )
            KeyboardMode.SYMBOLS -> SymbolRows(onChar = { c -> sendChar(viewModel, c) })
        }

        Spacer(Modifier.height(8.dp))

        // Linha utilitária: troca de modo, espaço, backspace, enter.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModeKey(
                label = when (mode) {
                    KeyboardMode.NUMBERS -> "ABC"
                    KeyboardMode.LETTERS -> "123"
                    KeyboardMode.SYMBOLS -> "ABC"
                },
                modifier = Modifier.weight(1.2f),
                onClick = {
                    mode = when (mode) {
                        KeyboardMode.NUMBERS -> KeyboardMode.LETTERS
                        KeyboardMode.LETTERS -> KeyboardMode.NUMBERS
                        KeyboardMode.SYMBOLS -> KeyboardMode.LETTERS
                    }
                }
            )
            ModeKey(
                label = if (mode == KeyboardMode.SYMBOLS) "abc" else "#+=",
                modifier = Modifier.weight(1.2f),
                onClick = {
                    mode = if (mode == KeyboardMode.SYMBOLS) KeyboardMode.LETTERS
                    else KeyboardMode.SYMBOLS
                }
            )
            KeyboardKey(
                label = "espaço",
                modifier = Modifier.weight(3f),
                onClick = { viewModel.onKeyPress(62) }
            )
            IconKey(
                icon = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Apagar",
                modifier = Modifier.weight(1.2f),
                onClick = { viewModel.onKeyPress(67) }
            )
            IconKey(
                icon = Icons.AutoMirrored.Filled.KeyboardReturn,
                contentDescription = "Enter",
                modifier = Modifier.weight(1.2f),
                accent = true,
                onClick = { viewModel.onKeyPress(66) }
            )
        }
    }
}

@Composable
private fun NumberRows(onChar: (Char) -> Unit) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf(null, '0', null),
    )
    KeyGrid(rows = rows.map { row -> row.map { it?.let { c -> KeyCell(c.toString(), c) } } }, onChar = onChar)
}

@Composable
private fun LetterRows(
    shift: Boolean,
    onChar: (Char) -> Unit,
    onShift: () -> Unit
) {
    val rowsLower = listOf(
        "qwertyuiop".toList(),
        "asdfghjkl".toList(),
        "zxcvbnm".toList()
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        rowsLower.forEachIndexed { idx, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (idx == 2) {
                    // Shift à esquerda
                    IconKey(
                        icon = Icons.Default.KeyboardCapslock,
                        contentDescription = "Shift",
                        modifier = Modifier.weight(1.4f),
                        accent = shift,
                        onClick = onShift
                    )
                }
                row.forEach { c ->
                    val ch = if (shift) c.uppercaseChar() else c
                    KeyboardKey(
                        label = ch.toString(),
                        modifier = Modifier.weight(1f),
                        onClick = { onChar(ch) }
                    )
                }
                if (idx == 2) {
                    Spacer(Modifier.weight(1.4f))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SymbolRows(onChar: (Char) -> Unit) {
    // Linhas baseadas no teclado simbólico do Gboard. Caracteres sem KEYCODE
    // no Android (^, ~, |, etc) ficam como rótulo mas não enviam nada — o usuário
    // vê que o botão "não fez nada" e percebe a limitação.
    val rows = listOf(
        "1234567890".toList(),
        "@#\$_&-+()/".toList(),
        "*\"':;!?,.".toList()
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { c ->
                    KeyboardKey(
                        label = c.toString(),
                        modifier = Modifier.weight(1f),
                        onClick = { onChar(c) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

private data class KeyCell(val label: String, val char: Char)

@Composable
private fun KeyGrid(rows: List<List<KeyCell?>>, onChar: (Char) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { cell ->
                    if (cell == null) {
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        KeyboardKey(
                            label = cell.label,
                            modifier = Modifier.weight(1f),
                            onClick = { onChar(cell.char) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun KeyboardKey(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .aspectRatio(1.6f)
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF2A2A2A)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ModeKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier
            .aspectRatio(1.6f)
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1A1A1A)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color(0xFFBB86FC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IconKey(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .aspectRatio(1.6f)
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = if (accent) Color(0xFFBB86FC) else Color(0xFF1A1A1A)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription,
                tint = if (accent) Color.Black else Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun sendChar(viewModel: RemoteViewModel, c: Char) {
    val key = when (c) {
        in 'a'..'z' -> 29 + (c - 'a')
        in 'A'..'Z' -> 29 + (c - 'A')
        in '0'..'9' -> 7 + (c - '0')
        ' ' -> 62
        '.' -> 56
        ',' -> 55
        '@' -> 77
        '/' -> 76
        '\\' -> 73
        ';' -> 74
        '\'' -> 75
        '=' -> 70
        '[' -> 71
        ']' -> 72
        '+' -> 81
        '-' -> 69
        '*' -> 17
        '#' -> 18
        else -> -1
    }
    if (key > 0) viewModel.onKeyPress(key)
}
