package com.procutstudio.editor.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.procutstudio.editor.ui.theme.ProCutError
import com.procutstudio.editor.ui.theme.ProCutOnSurface
import com.procutstudio.editor.ui.theme.ProCutOnSurfaceMuted
import com.procutstudio.editor.ui.theme.ProCutPrimary
import com.procutstudio.editor.ui.theme.ProCutSurfaceElevated

/** Destructive confirmation (delete project, delete clip, discard edits). */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ProCutSurfaceElevated,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = ProCutOnSurface) },
        text = { Text(message, color = ProCutOnSurfaceMuted) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) ProCutError else ProCutPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel, color = ProCutOnSurfaceMuted) }
        },
    )
}

/** Single-line text prompt used for project and text-entity names. */
@Composable
fun TextPromptDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String = "Name",
    allowEmpty: Boolean = false,
    singleLine: Boolean = true,
    maxLength: Int = 80,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ProCutSurfaceElevated,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = ProCutOnSurface) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= maxLength) value = it },
                    label = { Text(label) },
                    singleLine = singleLine,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                )
                if (!singleLine) Spacer(Modifier.height(8.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = allowEmpty || value.isNotBlank(),
            ) { Text(confirmLabel, color = ProCutPrimary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ProCutOnSurfaceMuted) } },
    )
}
