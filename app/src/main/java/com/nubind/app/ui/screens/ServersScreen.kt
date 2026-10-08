package com.nubind.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nubind.app.BindViewModel
import com.nubind.app.root.RemoteProfile
import com.nubind.app.root.RemoteType
import com.nubind.app.ui.components.AppIcons
import com.nubind.app.ui.components.BackupSheet
import com.nubind.app.ui.components.CookieBadge
import com.nubind.app.ui.components.DualPaneContentWidth
import com.nubind.app.ui.components.Entrance
import com.nubind.app.ui.components.ScreenContainer
import com.nubind.app.ui.components.ServerCardStack
import com.nubind.app.ui.components.ServerSheet
import com.nubind.app.ui.components.rememberIsDualPane
import com.nubind.app.R
import com.nubind.app.Strings

@Composable
fun ServersScreen(vm: BindViewModel, onOpenHome: () -> Unit = {}) {
    LaunchedEffect(Unit) { vm.refreshAll() }

    // Primer toque: elige el servidor. Segundo toque sobre el ya elegido: va a Inicio, al botón Montar.
    fun tapServer(name: String) {
        if (name == vm.activeName) onOpenHome() else vm.selectProfile(name)
    }

    var showSheet by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<RemoteProfile?>(null) }
    var newProfileType by remember { mutableStateOf(RemoteType.FTP) }
    var deleteTarget by remember { mutableStateOf<RemoteProfile?>(null) }
    var showBackup by remember { mutableStateOf(false) }
    var backupImport by remember { mutableStateOf(false) }

    fun openBackup(import: Boolean = false) {
        backupImport = import
        showBackup = true
    }

    fun openNew(type: RemoteType = RemoteType.FTP) {
        editTarget = null
        newProfileType = type
        showSheet = true
    }

    val dualPane = rememberIsDualPane()

    // Caché por servidor: se puede borrar salvo el que está montado.
    val cacheKbOf: (RemoteProfile) -> Long = { vm.serverCacheKb[it.name] ?: 0L }
    val canClearCache: (RemoteProfile) -> Boolean = {
        !vm.busy && !(vm.isMounted && (vm.mountedRemote == null || vm.mountedRemote == it.name))
    }

    Box(Modifier.fillMaxSize()) {
        ScreenContainer(
            title = Strings.get(R.string.servidores),
            refreshing = vm.refreshing,
            onRefresh = { vm.pullRefresh() },
            maxContentWidth = if (dualPane) DualPaneContentWidth else null,
            actions = {
                // Respaldo a la izquierda del "+", ambos como botones tonales (Expressive).
                FilledTonalIconButton(onClick = { openBackup() }) {
                    Icon(Icons.Default.Backup, contentDescription = Strings.get(R.string.respaldo_titulo))
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = { openNew() }) {
                    Icon(Icons.Default.Add, contentDescription = Strings.get(R.string.agregar_servidor))
                }
            }
        ) {
            if (vm.profiles.isNotEmpty()) {
                Entrance(0) {
                    ServersSummaryCard(
                        count = vm.profiles.size,
                        mounted = vm.isMounted,
                        hint = if (dualPane) null else Strings.get(R.string.toca_una_tarjeta_para_elegir_el)
                    )
                }
            }
            if (dualPane) {
                val ftp = vm.profiles.filter { it.type == RemoteType.FTP }
                val drive = vm.profiles.filter { it.type == RemoteType.DRIVE }
                val s3 = vm.profiles.filter { it.type == RemoteType.S3 }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    ServerPanel(
                        modifier = Modifier.weight(1f),
                        type = RemoteType.FTP,
                        emptyHint = Strings.get(R.string.agrega_un_servidor_ftp_para_montarlo),
                        profiles = ftp,
                        selected = vm.activeName,
                        onSelect = { tapServer(it) },
                        onEdit = { p -> editTarget = p; showSheet = true },
                        onDelete = { deleteTarget = it },
                        cacheKbOf = cacheKbOf,
                        canClearCache = canClearCache,
                        onClearCache = { vm.clearServerCache(it.name) }
                    )
                    ServerPanel(
                        modifier = Modifier.weight(1f),
                        type = RemoteType.DRIVE,
                        emptyHint = Strings.get(R.string.conecta_tu_cuenta_de_google_drive),
                        profiles = drive,
                        selected = vm.activeName,
                        onSelect = { tapServer(it) },
                        onEdit = { p -> editTarget = p; showSheet = true },
                        onDelete = { deleteTarget = it },
                        cacheKbOf = cacheKbOf,
                        canClearCache = canClearCache,
                        onClearCache = { vm.clearServerCache(it.name) }
                    )
                    ServerPanel(
                        modifier = Modifier.weight(1f),
                        type = RemoteType.S3,
                        emptyHint = Strings.get(R.string.conecta_un_bucket_s3_oracle_cloud),
                        profiles = s3,
                        selected = vm.activeName,
                        onSelect = { tapServer(it) },
                        onEdit = { p -> editTarget = p; showSheet = true },
                        onDelete = { deleteTarget = it },
                        cacheKbOf = cacheKbOf,
                        canClearCache = canClearCache,
                        onClearCache = { vm.clearServerCache(it.name) }
                    )
                }
            } else if (vm.profiles.isEmpty()) {
                Entrance(0) { ServersEmptyState(onAdd = { openNew() }, onImport = { openBackup(import = true) }) }
            } else {
                ServerCardStack(
                    profiles = vm.profiles,
                    selected = vm.activeName,
                    onSelect = { tapServer(it) },
                    onEdit = { p ->
                        editTarget = p
                        showSheet = true
                    },
                    onDelete = { deleteTarget = it },
                    cacheKbOf = cacheKbOf,
                    canClearCache = canClearCache,
                    onClearCache = { vm.clearServerCache(it.name) }
                )
            }
        }
    }

    if (showBackup) {
        BackupSheet(vm = vm, startOnImport = backupImport, onDismiss = { showBackup = false })
    }

    if (showSheet) {
        ServerSheet(
            initial = editTarget,
            initialType = newProfileType,
            existingNames = vm.profiles.map { it.name },
            driveAuth = vm.driveAuth,
            onDriveLogin = { clientId, clientSecret -> vm.startDriveLogin(clientId, clientSecret) },
            onDriveCancel = { vm.cancelDriveLogin() },
            onSaveFtp = { name, host, port, user, pass, folder ->
                vm.saveProfile(editTarget?.name, name, host, port, user, pass, folder)
                vm.cancelDriveLogin()
                showSheet = false
            },
            onSaveDrive = { name, token, options ->
                vm.saveDriveProfile(editTarget?.name, name, token, options)
                vm.cancelDriveLogin()
                showSheet = false
            },
            onSaveS3 = { name, options, secret ->
                vm.saveS3Profile(editTarget?.name, name, options, secret)
                vm.cancelDriveLogin()
                showSheet = false
            },
            onDismiss = {
                // Cerrar el formulario a mitad del login corta rclone y borra el token temporal.
                vm.cancelDriveLogin()
                showSheet = false
            }
        )
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(Strings.get(R.string.eliminar_servidor)) },
            text = { Text(Strings.get(R.string.se_borran_los_datos_guardados_de, profile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProfile(profile.name)
                    deleteTarget = null
                }) { Text(Strings.get(R.string.eliminar)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(Strings.get(R.string.cancelar)) }
            }
        )
    }
}

/**
 * Una columna del doble panel: título del tipo de remoto con su propio
 * botón de agregar, y debajo su stack (o un aviso si todavía no tiene
 * ningún servidor guardado). Solo se usa en pantallas anchas; en vertical
 * los servidores de ambos tipos se ven mezclados en un único stack.
 */
@Composable
private fun ServerPanel(
    type: RemoteType,
    emptyHint: String,
    profiles: List<RemoteProfile>,
    selected: String?,
    onSelect: (String) -> Unit,
    onEdit: (RemoteProfile) -> Unit,
    onDelete: (RemoteProfile) -> Unit,
    cacheKbOf: (RemoteProfile) -> Long,
    canClearCache: (RemoteProfile) -> Boolean,
    onClearCache: (RemoteProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(type.label, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        if (profiles.isEmpty()) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ServerCardStack(
                profiles = profiles,
                selected = selected,
                onSelect = onSelect,
                onEdit = onEdit,
                onDelete = onDelete,
                cacheKbOf = cacheKbOf,
                canClearCache = canClearCache,
                onClearCache = onClearCache
            )
        }
    }
}

/**
 * Resumen en la parte de arriba: insignia de forma (gira mientras hay algo montado) con cuántos
 * servidores hay guardados y, en una sola columna, la pista de que se toca una tarjeta para elegir.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServersSummaryCard(count: Int, mounted: Boolean, hint: String?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CookieBadge(
                icon = AppIcons.Cloud,
                shape = MaterialShapes.Cookie12Sided.toShape(),
                background = scheme.secondary,
                glyph = scheme.onSecondary,
                spinning = mounted
            )
            Column(Modifier.weight(1f)) {
                Text(Strings.get(R.string.servidores_guardados, count), style = MaterialTheme.typography.titleLarge)
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/** Sin servidores: insignia grande, el texto de siempre, botón pleno para agregar y atajo a importar un respaldo. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServersEmptyState(onAdd: () -> Unit, onImport: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CookieBadge(
            icon = AppIcons.Cloud,
            shape = MaterialShapes.Cookie12Sided.toShape(),
            background = scheme.secondaryContainer,
            glyph = scheme.onSecondaryContainer,
            spinning = true,
            size = 112.dp
        )
        Text(
            Strings.get(R.string.todavia_no_hay_servidores_guardados_agrega),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onAdd, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(Strings.get(R.string.agregar_servidor))
        }
        TextButton(onClick = onImport) { Text(Strings.get(R.string.respaldo_importar_existente)) }
    }
}
