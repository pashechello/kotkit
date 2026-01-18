package com.autoposter.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedType by remember { mutableStateOf(if (uiState.hasStoredPassword) 1 else 0) }
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showSnackbar by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screen Unlock") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = {
            showSnackbar?.let { message ->
                Snackbar {
                    Text(message)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Info card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Your PIN or password is stored securely using Android Keystore encryption. It's only used to unlock the screen for scheduled posts.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Lock type selection
            Text(
                text = "Lock Type",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterChip(
                    selected = selectedType == 0,
                    onClick = { selectedType = 0 },
                    label = { Text("PIN") },
                    leadingIcon = if (selectedType == 0) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedType == 1,
                    onClick = { selectedType = 1 },
                    label = { Text("Password") },
                    leadingIcon = if (selectedType == 1) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
            }

            // Input field
            if (selectedType == 0) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pin = it },
                    label = { Text("PIN") },
                    placeholder = { Text("Enter your device PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    placeholder = { Text("Enter your device password") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showPassword)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = "Toggle password visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Save button
            Button(
                onClick = {
                    val success = if (selectedType == 0) {
                        viewModel.savePin(pin)
                    } else {
                        viewModel.savePassword(password)
                    }
                    if (success) {
                        showSnackbar = "Saved successfully"
                        onNavigateBack()
                    } else {
                        showSnackbar = if (selectedType == 0)
                            "PIN must be at least 4 digits"
                        else
                            "Password cannot be empty"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save")
            }

            // Clear button if credentials exist
            if (uiState.hasStoredPin || uiState.hasStoredPassword) {
                OutlinedButton(
                    onClick = {
                        viewModel.clearUnlockCredentials()
                        pin = ""
                        password = ""
                        showSnackbar = "Credentials cleared"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Saved Credentials")
                }
            }
        }
    }
}
