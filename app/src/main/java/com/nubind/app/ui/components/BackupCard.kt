package com.nubind.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nubind.app.BindViewModel
import com.nubind.app.R
import com.nubind.app.Strings
import com.nubind.app.root.ConfBackup

/**
 * Respaldo cifrado de los servidores: "Exportar" pide una contraseña y guarda un archivo .nubind
 * donde el usuario quiera (selector de archivos de Android: Drive, Dropbox, etc.); "Importar" lo
 * lee, pide la contraseña y mezcla los servidores. El cifrado está en [ConfBackup]; los secretos
 * nunca se muestran ni salen en claro de la app.
 */
@Composable
fun BackupCard(vm: BindViewModel, modifier: Modifier = Modifier) {
    var exportDialog by remember { mutableStateOf(false) }
    // La contraseña espera aquí mientras el usuario elige dónde guardar el archivo.
    var exportPass by remember { mutableStateOf<CharArray?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pass = exportPass
        exportPass = null
        if (uri != null && pass != null) vm.exportBackup(uri, pass) else pass?.fill('\u0000')
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri = uri
    }

    val hasRoot = vm.rootGranted == true
    val enabled = hasRoot && !vm.backupBusy

    SectionCard(
        title = Strings.get(R.string.respaldo_titulo),
        icon = Icons.Default.Lock,
        subtitle = Strings.get(R.string.respaldo_subtitulo),
        modifier = modifier
    ) {
        Text(
            Strings.get(if (hasRoot) R.string.respaldo_descripcion else R.string.respaldo_requiere_root),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = {
                    if (vm.profiles.isEmpty()) {
                        vm.showNotice(Strings.get(R.string.respaldo_sin_servidores), NoticeKind.Warning)
                    } else {
                        exportDialog = true
                    }
                },
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) { Text(Strings.get(R.string.respaldo_exportar)) }
            FilledTonalButton(
                onClick = { openLauncher.launch(arrayOf("*/*")) },
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) { Text(Strings.get(R.string.respaldo_importar)) }
        }
    }

    if (exportDialog) {
        BackupPasswordDialog(
            title = Strings.get(R.string.respaldo_exportar_titulo),
            message = Strings.get(R.string.respaldo_exportar_aviso),
            confirmRepeat = true,
            confirmLabel = Strings.get(R.string.respaldo_exportar),
            onConfirm = { pass ->
                exportDialog = false
                exportPass = pass
                createLauncher.launch("nubind-servidores.nubind")
            },
            onDismiss = { exportDialog = false }
        )
    }

    importUri?.let { uri ->
        BackupPasswordDialog(
            title = Strings.get(R.string.respaldo_importar_titulo),
            message = Strings.get(R.string.respaldo_importar_aviso),
            confirmRepeat = false,
            confirmLabel = Strings.get(R.string.respaldo_importar),
            onConfirm = { pass ->
                importUri = null
                vm.importBackup(uri, pass)
            },
            onDismiss = { importUri = null }
        )
    }
}

/**
 * Diálogo de contraseña. Con [confirmRepeat] (exportar) pide repetirla y exige un mínimo de
 * [ConfBackup.MIN_PASSWORD] caracteres; sin él (importar) basta con que no esté vacía.
 */
@Composable
private fun BackupPasswordDialog(
    title: String,
    message: String,
    confirmRepeat: Boolean,
    confirmLabel: String,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val tooShort = confirmRepeat && pass.isNotEmpty() && pass.length < ConfBackup.MIN_PASSWORD
    val mismatch = confirmRepeat && repeat.isNotEmpty() && repeat != pass
    val valid = if (confirmRepeat) pass.length >= ConfBackup.MIN_PASSWORD && pass == repeat else pass.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(Strings.get(R.string.respaldo_contrasena)) },
                    singleLine = true,
                    isError = tooShort,
                    supportingText = if (tooShort) {
                        { Text(Strings.get(R.string.respaldo_corta, ConfBackup.MIN_PASSWORD)) }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
                if (confirmRepeat) {
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = { Text(Strings.get(R.string.respaldo_repetir)) },
                        singleLine = true,
                        isError = mismatch,
                        supportingText = if (mismatch) {
                            { Text(Strings.get(R.string.respaldo_no_coinciden)) }
                        } else null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pass.toCharArray()) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Strings.get(R.string.cancelar)) }
        }
    )
}
