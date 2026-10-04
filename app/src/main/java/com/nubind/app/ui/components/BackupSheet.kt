package com.nubind.app.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nubind.app.BindViewModel
import com.nubind.app.R
import com.nubind.app.Strings
import com.nubind.app.root.ConfBackup

/**
 * Hoja de respaldo cifrado de los servidores, con el mismo formato que la de agregar servidor
 * (ModalBottomSheet). Dos modos: Exportar (contraseña dos veces -> el selector de archivos de
 * Android pide dónde guardar el .nubind) e Importar (se elige el archivo y se escribe su
 * contraseña). El cifrado está en [ConfBackup]; los secretos nunca se muestran. Mientras trabaja
 * (PBKDF2 tarda un momento) la hoja no se puede cerrar; si algo falla muestra el error dentro y
 * se queda abierta para reintentar, y si sale bien se cierra y deja el aviso de éxito.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackupSheet(vm: BindViewModel, startOnImport: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !(vm.backupBusy && it == SheetValue.Hidden) }
    )

    var importing by rememberSaveable { mutableStateOf(startOnImport) }
    var pass by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // La contraseña espera aquí mientras el usuario elige dónde guardar el archivo.
    var exportPass by remember { mutableStateOf<CharArray?>(null) }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val chars = exportPass
        exportPass = null
        if (uri != null && chars != null) {
            vm.exportBackup(uri, chars) { err -> if (err == null) onDismiss() else error = err }
        } else {
            chars?.fill('\u0000')
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickedName = displayName(context, uri)
            error = null
        }
    }

    val hasRoot = vm.rootGranted == true
    val busy = vm.backupBusy
    val tooShort = pass.isNotEmpty() && pass.length < ConfBackup.MIN_PASSWORD
    val mismatch = repeat.isNotEmpty() && repeat != pass
    val canExport = hasRoot && !busy && pass.length >= ConfBackup.MIN_PASSWORD && pass == repeat
    val canImport = hasRoot && !busy && pickedUri != null && pass.isNotEmpty()

    fun switchMode(toImport: Boolean) {
        if (busy || importing == toImport) return
        importing = toImport
        pass = ""
        repeat = ""
        error = null
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CookieBadge(
                    icon = Icons.Default.Backup,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    background = scheme.primary,
                    glyph = scheme.onPrimary,
                    spinning = busy
                )
                Column(Modifier.weight(1f)) {
                    Text(Strings.get(R.string.respaldo_titulo), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        Strings.get(R.string.respaldo_subtitulo),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            if (!hasRoot) {
                Surface(color = scheme.tertiaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        Strings.get(R.string.respaldo_requiere_root),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onTertiaryContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ModeChoice(
                    selected = !importing,
                    icon = Icons.Default.FileUpload,
                    label = Strings.get(R.string.respaldo_exportar),
                    onClick = { switchMode(false) },
                    modifier = Modifier.weight(1f)
                )
                ModeChoice(
                    selected = importing,
                    icon = Icons.Default.FileDownload,
                    label = Strings.get(R.string.respaldo_importar),
                    onClick = { switchMode(true) },
                    modifier = Modifier.weight(1f)
                )
            }

            AnimatedContent(targetState = importing, label = "backupMode") { isImport ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (isImport) {
                        Text(
                            Strings.get(R.string.respaldo_importar_aviso),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant
                        )
                        Surface(
                            onClick = { openLauncher.launch(arrayOf("*/*")) },
                            enabled = !busy,
                            color = scheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = null, tint = scheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    pickedName ?: Strings.get(R.string.respaldo_elegir_archivo),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it; error = null },
                            label = { Text(Strings.get(R.string.respaldo_contrasena)) },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            Strings.get(R.string.respaldo_descripcion),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it; error = null },
                            label = { Text(Strings.get(R.string.respaldo_contrasena)) },
                            singleLine = true,
                            enabled = !busy,
                            isError = tooShort,
                            supportingText = if (tooShort) {
                                { Text(Strings.get(R.string.respaldo_corta, ConfBackup.MIN_PASSWORD)) }
                            } else null,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = repeat,
                            onValueChange = { repeat = it; error = null },
                            label = { Text(Strings.get(R.string.respaldo_repetir)) },
                            singleLine = true,
                            enabled = !busy,
                            isError = mismatch,
                            supportingText = if (mismatch) {
                                { Text(Strings.get(R.string.respaldo_no_coinciden)) }
                            } else null,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Surface(color = scheme.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                Strings.get(R.string.respaldo_exportar_aviso),
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSecondaryContainer,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }

            error?.let {
                Surface(color = scheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())

            Button(
                onClick = {
                    if (importing) {
                        val uri = pickedUri
                        if (uri != null) {
                            error = null
                            vm.importBackup(uri, pass.toCharArray()) { err -> if (err == null) onDismiss() else error = err }
                        }
                    } else if (vm.profiles.isEmpty()) {
                        error = Strings.get(R.string.respaldo_sin_servidores)
                    } else {
                        error = null
                        exportPass = pass.toCharArray()
                        createLauncher.launch("nubind-servidores.nubind")
                    }
                },
                enabled = if (importing) canImport else canExport,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(
                    if (importing) Icons.Default.FileDownload else Icons.Default.FileUpload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(Strings.get(if (importing) R.string.respaldo_importar else R.string.respaldo_guardar_como))
            }
        }
    }
}

/**
 * Opción del selector de modo: la elegida se llena de color y se redondea del todo, la otra queda
 * tonal y más cuadrada; el cambio de color y de forma va con resorte (el estilo Expressive).
 */
@Composable
private fun ModeChoice(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
        label = "modeContainer"
    )
    val content by animateColorAsState(
        if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        label = "modeContent"
    )
    val corner by animateDpAsState(
        if (selected) 28.dp else 14.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "modeCorner"
    )
    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(corner),
        modifier = modifier.height(56.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Nombre visible del archivo elegido en el selector (o el último tramo de la ruta si no lo da). */
private fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
