package com.sidsaxena.notes.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    lockEnabled: Boolean,
    onLockToggle: (Boolean) -> Unit,
    canUseDeviceLock: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Require Face / Fingerprint / PIN")
                    Text(
                        "Locks Notes each time you open the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = lockEnabled, onCheckedChange = onLockToggle, enabled = canUseDeviceLock)
            }
            if (!canUseDeviceLock) {
                Text(
                    "Set up a screen lock (fingerprint, face, or PIN) on this phone to enable this.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                Text("Export Notes as a Backup File")
            }
            TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text("Import Notes from a Backup File")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Everything is stored only on this phone. Export creates a plain " +
                    "JSON file you choose the location for -- nothing is uploaded anywhere.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
