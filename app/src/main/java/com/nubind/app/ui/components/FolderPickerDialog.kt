package com.nubind.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nubind.app.R
import com.nubind.app.Strings
import com.nubind.app.root.RootShell
import com.nubind.app.root.STORAGE_ROOT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Carpeta en la que abre el selector: la que contiene el destino actual, o la raíz. */
fun folderPickerStartDir(initialPath: String): String =
    if (initialPath.startsWith("$STORAGE_ROOT/")) initialPath.substringBeforeLast('/') else STORAGE_ROOT

/**
 * Memoria de las carpetas ya listadas (listar con root tarda). El selector muestra lo que haya
 * aquí al instante y refresca por detrás; Inicio precarga la carpeta de apertura con [prefetch].
 */
object FolderListCache {
    private val map = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    fun peek(path: String): List<String>? = map[path]

    fun invalidate(path: String) {
        map.remove(path)
    }

    suspend fun load(path: String): List<String> =
        withContext(Dispatchers.IO) { RootShell.listDirs(path) }.also { map[path] = it }

    suspend fun prefetch(path: String) {
        if (map[path] == null) load(path)
    }
}

/**
 * Explorador de carpetas del almacenamiento interno para elegir el destino
 * del bind (reemplaza la entrada de texto). Permite navegar y crear carpetas.
 *
 * Estilo Material 3 Expressive: diálogo muy redondeado, insignia de forma en la
 * cabecera, ruta en una píldora, carpetas como filas-tarjeta que se aplastan al
 * tocarlas y botones en píldora. Abre al instante (lista en caché, sin animar el tamaño).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FolderPickerDialog(
    initialPath: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf(folderPickerStartDir(initialPath)) }
    // Si la carpeta ya se listó antes (o se precargó al abrir Inicio), la lista aparece al instante.
    var dirs by remember { mutableStateOf(FolderListCache.peek(current)) }
    var reload by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(current, reload) {
        // Lo ya conocido se muestra de inmediato y se refresca en silencio: sin parpadeo
        // de "cargando" ni cambios de tamaño del diálogo.
        dirs = FolderListCache.peek(current)
        dirs = FolderListCache.load(current)
    }

    val atRoot = current == STORAGE_ROOT
    val scheme = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(36.dp),
        containerColor = scheme.surfaceContainerHigh,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(scheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                }
                Text(
                    Strings.get(R.string.elegir_carpeta),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Ruta actual en una píldora.
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        current,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }

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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = { creating = false; newName = ""; error = null }) { Text(Strings.get(R.string.cancelar)) }
                        FilledTonalButton(onClick = {
                            val n = newName.trim()
                            if (n.isEmpty() || n.contains('/') || n == "." || n.contains("..")) {
                                error = Strings.get(R.string.nombre_no_valido)
                            } else {
                                val target = "$current/$n"
                                creating = false
                                newName = ""
                                scope.launch {
                                    withContext(Dispatchers.IO) { RootShell.makeDir(target) }
                                    FolderListCache.invalidate(current)
                                    current = target
                                    reload++
                                }
                            }
                        }) { Text(Strings.get(R.string.crear)) }
                    }
                } else {
                    FilledTonalButton(
                        onClick = { creating = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(Strings.get(R.string.nueva_carpeta_aqui), modifier = Modifier.padding(start = 8.dp))
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    if (!atRoot) {
                        item {
                            PickerRow(onClick = { current = current.substringBeforeLast('/').ifEmpty { STORAGE_ROOT } }) {
                                IconTile(Icons.Default.ArrowUpward, scheme.secondaryContainer, scheme.onSecondaryContainer)
                                Text(
                                    Strings.get(R.string.subir),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    val list = dirs
                    if (list == null) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                LoadingIndicator(Modifier.size(48.dp))
                            }
                        }
                    } else if (list.isEmpty()) {
                        item {
                            Text(
                                Strings.get(R.string.sin_subcarpetas),
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp)
                            )
                        }
                    } else {
                        items(list, key = { it }) { name ->
                            PickerRow(onClick = { current = "$current/$name" }) {
                                IconTile(Icons.Default.Folder, scheme.primaryContainer, scheme.onPrimaryContainer)
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Nota informativa.
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp).size(18.dp)
                    )
                    Text(
                        if (atRoot) Strings.get(R.string.entra_en_una_carpeta_para_poder)
                        else Strings.get(R.string.el_contenido_que_ya_tenga_esta),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = !atRoot, onClick = { onPick(current) }) { Text(Strings.get(R.string.usar_esta_carpeta)) }
        },
        dismissButton = {
            FilledTonalButton(onClick = onDismiss) { Text(Strings.get(R.string.cancelar)) }
        }
    )
}

/** Fila-tarjeta de la lista: se aplasta con resorte al tocarla. */
@Composable
private fun PickerRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "pickerRowPress"
    )
    Surface(
        onClick = onClick,
        interactionSource = source,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/** Icono sobre un cuadrado redondeado de color. */
@Composable
private fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, container: androidx.compose.ui.graphics.Color, glyph: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(container, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(22.dp))
    }
}
