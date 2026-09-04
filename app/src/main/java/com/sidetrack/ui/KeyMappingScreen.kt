package com.sidetrack.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sidetrack.settings.KeyMappingManager
import com.sidetrack.settings.RemappableAction

@Composable
fun KeyMappingScreen(
    keyMappingManager: KeyMappingManager,
    onBack: () -> Unit,
) {
    val overrides by keyMappingManager.overrides.collectAsState()
    val recordingAction by keyMappingManager.recordingAction.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (recordingAction != null) keyMappingManager.cancelRecording()
                    onBack()
                },
                modifier = Modifier.focusCircle(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Key Mapping",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Different phones send different codes for the same physical soft key. " +
                "If a function below isn't reachable on your device, press Record and then " +
                "press the physical key you want to use for it — it's added on top of the " +
                "built-in default, so nothing already working can break.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        RemappableAction.entries.forEachIndexed { index, action ->
            val override = overrides[action]
            val isRecording = recordingAction == action

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Default: " + action.defaultKeyCodes.joinToString(", ") { prettyKeyName(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (override != null) {
                    Text(
                        text = "Custom: " + prettyKeyName(override),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isRecording) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Press the key now…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { keyMappingManager.cancelRecording() }) {
                            Text("Cancel")
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { keyMappingManager.startRecording(action) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text("Record")
                        }
                        if (override != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { keyMappingManager.clearOverride(action) },
                            ) {
                                Text("Reset to default")
                            }
                        }
                    }
                }
            }

            if (index != RemappableAction.entries.lastIndex) {
                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** "KEYCODE_SOFT_LEFT" -> "Soft Left". Falls back to the raw code if unknown. */
private fun prettyKeyName(keyCode: Int): String {
    val raw = KeyEvent.keyCodeToString(keyCode)
    val withoutPrefix = raw.removePrefix("KEYCODE_")
    if (withoutPrefix == raw && !raw.startsWith("KEYCODE_")) return "Code $keyCode"
    return withoutPrefix
        .split("_")
        .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
}
