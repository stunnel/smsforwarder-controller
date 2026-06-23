package com.example.smsforwarder.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smsforwarder.R
import com.example.smsforwarder.data.storage.PreferencesManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    preferencesManager: PreferencesManager,
    isBotRunning: Boolean,
    onStartBot: () -> Unit,
    onStopBot: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Load saved values
    val savedBotToken by preferencesManager.botToken.collectAsState(initial = "")
    val savedPort by preferencesManager.port.collectAsState(initial = PreferencesManager.DEFAULT_PORT)
    val savedSecurityMode by preferencesManager.securityMode.collectAsState(initial = "none")
    val savedSignSecret by preferencesManager.signSecret.collectAsState(initial = "")
    val savedRsaPrivateKey by preferencesManager.rsaPrivateKey.collectAsState(initial = "")
    val savedRsaPublicKey by preferencesManager.rsaPublicKey.collectAsState(initial = "")
    val savedAllowedUsers by preferencesManager.allowedUsers.collectAsState(initial = emptyList())
    val savedLanguage by preferencesManager.language.collectAsState(initial = "zh")

    // Editable state
    var botToken by remember(savedBotToken) { mutableStateOf(savedBotToken) }
    var port by remember(savedPort) { mutableStateOf(savedPort.toString()) }
    var securityMode by remember(savedSecurityMode) { mutableStateOf(savedSecurityMode) }
    var signSecret by remember(savedSignSecret) { mutableStateOf(savedSignSecret) }
    var rsaPrivateKey by remember(savedRsaPrivateKey) { mutableStateOf(savedRsaPrivateKey) }
    var rsaPublicKey by remember(savedRsaPublicKey) { mutableStateOf(savedRsaPublicKey) }
    var allowedUsers by remember(savedAllowedUsers) { mutableStateOf(savedAllowedUsers.joinToString(",")) }
    var language by remember(savedLanguage) { mutableStateOf(savedLanguage) }

    var showBotToken by remember { mutableStateOf(false) }
    var showSignSecret by remember { mutableStateOf(false) }
    var securityModeExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val saveLabel = stringResource(R.string.save_success)
    val saveErrorLabel = stringResource(R.string.save_error)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Telegram-style paper plane icon as logo
                        Icon(
                            imageVector = Icons.Filled.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Bot Status Card ──────────────────────────────────────────
            BotStatusCard(
                isRunning = isBotRunning,
                onStart = onStartBot,
                onStop = onStopBot
            )

            // ── Basic Settings ───────────────────────────────────────────
            SectionCard(title = stringResource(R.string.section_basic)) {
                // Bot Token
                OutlinedTextField(
                    value = botToken,
                    onValueChange = { botToken = it },
                    label = { Text(stringResource(R.string.label_bot_token)) },
                    placeholder = { Text(stringResource(R.string.hint_bot_token)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showBotToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showBotToken = !showBotToken }) {
                            Icon(
                                imageVector = if (showBotToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) }
                )

                Spacer(Modifier.height(8.dp))

                // Port
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.label_port)) },
                    placeholder = { Text(PreferencesManager.DEFAULT_PORT.toString()) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Icon(Icons.Filled.Router, contentDescription = null) }
                )

                Spacer(Modifier.height(8.dp))

                // Allowed Users
                OutlinedTextField(
                    value = allowedUsers,
                    onValueChange = { allowedUsers = it },
                    label = { Text(stringResource(R.string.label_allowed_users)) },
                    placeholder = { Text(stringResource(R.string.hint_allowed_users)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.People, contentDescription = null) }
                )

                Spacer(Modifier.height(8.dp))

                // Language
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it }
                ) {
                    OutlinedTextField(
                        value = if (language == "zh") stringResource(R.string.lang_zh) else stringResource(R.string.lang_en),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.label_language)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                        leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lang_zh)) },
                            onClick = { language = "zh"; languageExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lang_en)) },
                            onClick = { language = "en"; languageExpanded = false }
                        )
                    }
                }
            }

            // ── Security Settings ────────────────────────────────────────
            SectionCard(title = stringResource(R.string.section_security)) {
                // Security Mode
                ExposedDropdownMenuBox(
                    expanded = securityModeExpanded,
                    onExpandedChange = { securityModeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = securityMode,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.label_security_mode)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = securityModeExpanded) },
                        leadingIcon = { Icon(Icons.Filled.Security, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = securityModeExpanded,
                        onDismissRequest = { securityModeExpanded = false }
                    ) {
                        listOf("none", "sign", "rsa").forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode) },
                                onClick = { securityMode = mode; securityModeExpanded = false }
                            )
                        }
                    }
                }

                // Sign Secret (only for "sign" mode)
                if (securityMode == "sign") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = signSecret,
                        onValueChange = { signSecret = it },
                        label = { Text(stringResource(R.string.label_sign_secret)) },
                        placeholder = { Text(stringResource(R.string.hint_sign_secret)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showSignSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showSignSecret = !showSignSecret }) {
                                Icon(
                                    imageVector = if (showSignSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) }
                    )
                }

                // RSA Keys (only for "rsa" mode)
                if (securityMode == "rsa") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rsaPublicKey,
                        onValueChange = { rsaPublicKey = it },
                        label = { Text(stringResource(R.string.label_rsa_public_key)) },
                        placeholder = { Text(stringResource(R.string.hint_rsa_public_key)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        maxLines = 8,
                        leadingIcon = { Icon(Icons.Filled.LockOpen, contentDescription = null) }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rsaPrivateKey,
                        onValueChange = { rsaPrivateKey = it },
                        label = { Text(stringResource(R.string.label_rsa_private_key)) },
                        placeholder = { Text(stringResource(R.string.hint_rsa_private_key)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        maxLines = 8,
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) }
                    )
                }
            }

            // ── Save Button ──────────────────────────────────────────────
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        try {
                            preferencesManager.setBotToken(botToken.trim())
                            preferencesManager.setPort(port.toIntOrNull() ?: PreferencesManager.DEFAULT_PORT)
                            preferencesManager.setSecurityMode(securityMode)
                            preferencesManager.setSignSecret(signSecret.trim())
                            preferencesManager.setRsaPrivateKey(rsaPrivateKey.trim())
                            preferencesManager.setRsaPublicKey(rsaPublicKey.trim())
                            preferencesManager.setAllowedUsers(
                                allowedUsers.split(",")
                                    .mapNotNull { it.trim().toLongOrNull() }
                            )
                            preferencesManager.setLanguage(language)
                            snackbarHostState.showSnackbar(saveLabel)
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("$saveErrorLabel: ${e.message}")
                        } finally {
                            isSaving = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_save), fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BotStatusCard(
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = if (isRunning)
                                MaterialTheme.colorScheme.secondary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.bot_status_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (isRunning) stringResource(R.string.bot_running) else stringResource(R.string.bot_stopped),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isRunning)
                            MaterialTheme.colorScheme.onSecondaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isRunning) {
                OutlinedButton(
                    onClick = onStop,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_stop))
                }
            } else {
                Button(onClick = onStart) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_start))
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}
