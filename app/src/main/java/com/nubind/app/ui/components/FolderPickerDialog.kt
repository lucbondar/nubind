package com.nubind.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nubind.app.root.RootShell
import com.nubind.app.root.STORAGE_ROOT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nubind.app.R
import com.nubind.app.Strings

/**
 * Explorador de carpetas del almacenamiento interno para elegir el destino
 * del bind (reemplaza la entrada de texto). Permite navegar y crear carpetas.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FolderPickerDialog(
    initialPath: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember {
        mutableStateOf(
            if (initialPath.startsWith("$STORAGE_ROOT/")) initialPath.substringBeforeLast('/') else STORAGE_ROOT
        )
    }
    var dirs by remember { mutableStateOf<List<String>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(current, reload) {
        dirs = null
        dirs = withContext(Dispatchers.IO) { RootShell.listDirs(current) }
    }

    val atRoot = current == STORAGE_ROOT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.get(R.string.elegir_carpeta)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    current,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it; error = null },
                        label = { Text(Strings.get(R.string.nombre_de_la_carpeta)) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { creating = false; newName = ""; error = null }) { Text(Strings.get(R.string.cancelar)) }
                        TextButton(onClick = {
                            val n = newName.trim()
                            if (n.isEmpty() || n.contains('/') || n == "." || n.contains("..")) {
                                error = Strings.get(R.string.nombre_no_valido)
                            } else {
                                val target = "$current/$n"
                                creating = false
                                newName = ""
                                scope.launch {
                                    withContext(Dispatchers.IO) { RootShell.makeDir(target) }
                                    current = target
                                    reload++
                                }
                            }
                        }) { Text(Strings.get(R.string.crear)) }
                    }
                } else {
                    TextButton(onClick = { creating = true }) { Text(Strings.get(R.string.nueva_carpeta_aqui)) }
                }

                HorizontalDivider()

                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    if (!atRoot) {
                        item {
                            Text(
                                Strings.get(R.string.subir),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { current = current.substringBeforeLast('/').ifEmpty { STORAGE_ROOT } }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                    val list = dirs
                    if (list == null) {
                        item { LoadingIndicator(Modifier.padding(vertical = 12.dp).size(48.dp)) }
                    } else if (list.isEmpty()) {
                        item { Text(Strings.get(R.string.sin_subcarpetas), modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        items(list) { name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { current = "$current/$name" }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Text(
                    if (atRoot) Strings.get(R.string.entra_en_una_carpeta_para_poder)
                    else Strings.get(R.string.el_contenido_que_ya_tenga_esta),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !atRoot, onClick = { onPick(current) }) { Text(Strings.get(R.string.usar_esta_carpeta)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.get(R.string.cancelar)) } }
    )
}
