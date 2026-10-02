package com.nubind.app.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nubind.app.BuildConfig
import com.nubind.app.net.FoundFtpServer
import com.nubind.app.net.scanForFtpServers
import com.nubind.app.root.DriveAuthState
import com.nubind.app.root.DriveOptions
import com.nubind.app.root.RemoteProfile
import com.nubind.app.root.RemoteType
import com.nubind.app.root.S3Options
import com.nubind.app.root.S3Provider
import com.nubind.app.root.awsEndpoint
import com.nubind.app.root.cleanS3Bucket
import com.nubind.app.root.cloudflareEndpoint
import com.nubind.app.root.cloudflareFieldValue
import com.nubind.app.root.cleanS3Endpoint
import com.nubind.app.root.oracleEndpoint
import com.nubind.app.root.parseOracleEndpoint
import com.nubind.app.root.validateAwsRegion
import com.nubind.app.root.validateCloudflareAccount
import com.nubind.app.root.validateOracleNamespace
import com.nubind.app.root.validateOracleRegion
import com.nubind.app.root.validateS3Bucket
import com.nubind.app.root.validateS3Endpoint
import com.nubind.app.root.cleanHost
import com.nubind.app.root.extractDriveFolderId
import com.nubind.app.root.normalizeToken
import com.nubind.app.root.validateProfileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Formulario para agregar un servidor (FTP, Google Drive o S3), o editar
 * [initial] si no es null. El tipo no se puede cambiar al editar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServerSheet(
    initial: RemoteProfile?,
    // Solo aplica cuando initial es null (servidor nuevo): qué chip queda
    // marcado al abrir. Por ejemplo, el panel de Google Drive en el doble
    // panel de Servidores abre el formulario ya en Drive en vez de FTP.
    initialType: RemoteType = RemoteType.FTP,
    existingNames: List<String>,
    driveAuth: DriveAuthState,
    onDriveLogin: (clientId: String, clientSecret: String) -> Unit,
    onDriveCancel: () -> Unit,
    onSaveFtp: (name: String, host: String, port: String, user: String, pass: String) -> Unit,
    onSaveDrive: (name: String, token: String?, options: DriveOptions) -> Unit,
    onSaveS3: (name: String, options: S3Options, secret: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port ?: "21") }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var pass by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var hostError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }

    var type by remember { mutableStateOf(initial?.type ?: initialType) }

    // Estado de Google Drive
    val initialDrive = initial?.drive
    // Los campos avanzados son solo para un cliente OAuth propio; vacíos = el que trae la app.
    var clientId by remember {
        mutableStateOf(initialDrive?.clientId?.takeUnless { it == BuildConfig.GDRIVE_CLIENT_ID }.orEmpty())
    }
    var clientSecret by remember {
        mutableStateOf(initialDrive?.clientSecret?.takeUnless { it == BuildConfig.GDRIVE_CLIENT_SECRET }.orEmpty())
    }
    var readOnly by remember { mutableStateOf(initialDrive?.readOnly ?: false) }
    var rootFolder by remember { mutableStateOf(initialDrive?.rootFolderId ?: "") }
    var teamDrive by remember { mutableStateOf(initialDrive?.teamDrive ?: "") }
    var acknowledgeAbuse by remember { mutableStateOf(initialDrive?.acknowledgeAbuse ?: false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var manualToken by remember { mutableStateOf("") }
    // Token nuevo (login o pegado). Null al editar = se conserva la sesión guardada.
    var newToken by remember { mutableStateOf<String?>(null) }
    var driveError by remember { mutableStateOf<String?>(null) }

    // Estado de S3. Con Oracle Cloud basta namespace + región, con Amazon S3 solo
    // la región y con Cloudflare R2 solo el Account ID (el endpoint se arma
    // solo); "Otro proveedor" deja escribir el endpoint completo.
    val initialS3 = initial?.s3
    val initialOracle = initialS3?.endpoint?.let { parseOracleEndpoint(it) }
    var s3Prov by remember {
        mutableStateOf(initialS3?.let { S3Provider.fromEndpoint(it.endpoint) } ?: S3Provider.ORACLE)
    }
    var s3Namespace by remember { mutableStateOf(initialOracle?.first.orEmpty()) }
    // Cloudflare R2: Account ID (o el host completo si el bucket es de una jurisdicción).
    var s3Account by remember {
        mutableStateOf(initialS3?.endpoint?.let { cloudflareFieldValue(it) }.orEmpty())
    }
    var s3Region by remember { mutableStateOf(initialS3?.region.orEmpty()) }
    var s3Endpoint by remember {
        mutableStateOf(if (initialOracle == null) initialS3?.endpoint.orEmpty() else "")
    }
    var s3AccessKey by remember { mutableStateOf(initialS3?.accessKeyId.orEmpty()) }
    var s3Secret by remember { mutableStateOf("") }
    var s3Bucket by remember { mutableStateOf(initialS3?.bucket.orEmpty()) }
    var s3NamespaceError by remember { mutableStateOf<String?>(null) }
    var s3AccountError by remember { mutableStateOf<String?>(null) }
    var s3RegionError by remember { mutableStateOf<String?>(null) }
    var s3EndpointError by remember { mutableStateOf<String?>(null) }
    var s3AccessKeyError by remember { mutableStateOf<String?>(null) }
    var s3SecretError by remember { mutableStateOf<String?>(null) }
    var s3BucketError by remember { mutableStateOf<String?>(null) }

    // Cliente OAuth efectivo: el propio si se escribió; si no, el de la app. Un
    // remoto viejo creado con el cliente compartido de rclone (sin client_id
    // guardado) conserva ese cliente para no invalidar su sesión.
    val useBuiltIn = !(initialDrive != null && initialDrive.clientId.isEmpty())
    val effectiveClientId = clientId.trim().ifEmpty { if (useBuiltIn) BuildConfig.GDRIVE_CLIENT_ID else "" }
    val effectiveClientSecret = clientSecret.trim().ifEmpty { if (useBuiltIn) BuildConfig.GDRIVE_CLIENT_SECRET else "" }

    // Cambiar el cliente OAuth invalida la sesión guardada: hay que volver a entrar.
    val clientChanged = initialDrive != null &&
        (effectiveClientId != initialDrive.clientId || effectiveClientSecret != initialDrive.clientSecret)
    val hasSession = newToken != null || (initialDrive?.hasToken == true && !clientChanged)
    val loginRunning = driveAuth is DriveAuthState.Starting || driveAuth is DriveAuthState.WaitingBrowser

    val appContext = LocalContext.current
    LaunchedEffect(driveAuth) {
        when (driveAuth) {
            is DriveAuthState.Success -> {
                newToken = driveAuth.token
                driveError = null
            }
            // Se abre solo una vez por inicio de sesión: la clave es el propio estado.
            is DriveAuthState.WaitingBrowser -> {
                if (!openInBrowser(appContext, driveAuth.url)) {
                    driveError = "No se encontró un navegador. Copia el enlace y ábrelo a mano."
                }
            }
            else -> Unit
        }
    }

    // Detector de servidores FTP en la red local (solo llena Host/Puerto;
    // el nombre lo sigue eligiendo el usuario).
    val scope = rememberCoroutineScope()
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanChecked by remember { mutableStateOf(0) }
    var scanTotal by remember { mutableStateOf(1) }
    val context = LocalContext.current
    var scanResults by remember { mutableStateOf<List<FoundFtpServer>>(emptyList()) }
    var scanMessage by remember { mutableStateOf<String?>(null) }

    fun startScan() {
        scanResults = emptyList()
        scanMessage = null
        scanChecked = 0
        scanTotal = 1
        scanning = true
        scanJob = scope.launch {
            try {
                val found = scanForFtpServers(context) { checked, total -> scanChecked = checked; scanTotal = total }
                scanResults = found
                scanMessage = if (found.isEmpty()) "No se encontró ningún servidor FTP. Verifica que estés en Wi-Fi y que el servidor esté encendido (puertos 21, 2121, 2221, 2222)." else null
            } catch (e: CancellationException) {
                throw e // el usuario tocó "Cancelar búsqueda"; no es un error
            } catch (e: Exception) {
                // Antes, cualquier excepción acá dejaba el botón trabado en
                // "Cancelar búsqueda…" para siempre sin ningún aviso.
                scanMessage = "No se pudo completar la búsqueda: ${e.message ?: e::class.simpleName}"
            } finally {
                scanning = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (initial == null) "Nuevo servidor" else "Editar servidor",
                style = MaterialTheme.typography.headlineMedium
            )

            if (initial == null) {
                DropdownSelector(
                    label = "Tipo de servidor",
                    options = listOf(
                        SelectorOption(
                            RemoteType.FTP, "FTP", "Servidor en tu red o en internet",
                            AppIcons.Dns
                        ),
                        SelectorOption(
                            RemoteType.DRIVE, "Google Drive", "Tu cuenta de Google",
                            AppIcons.DriveLogo, branded = true
                        ),
                        SelectorOption(
                            RemoteType.S3, "S3", "Oracle, Amazon, Cloudflare R2 y más",
                            AppIcons.Cloud
                        )
                    ),
                    selected = type,
                    onSelect = { type = it }
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it; nameError = null },
                label = { Text("Nombre") },
                singleLine = true,
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            )

            if (type == RemoteType.FTP) {
                OutlinedButton(
                    onClick = {
                        if (scanning) {
                            scanJob?.cancel()
                            scanning = false
                        } else {
                            startScan()
                        }
                    },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (scanning) "Cancelar búsqueda ($scanChecked/$scanTotal)" else "Buscar servidores FTP en mi red")
                }

                if (scanning) {
                    LinearWavyProgressIndicator(
                        progress = { scanChecked / scanTotal.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                scanMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (scanResults.isNotEmpty()) {
                    Text("Toca uno para usarlo", style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        scanResults.forEach { server ->
                            Surface(
                                onClick = {
                                    host = server.ip
                                    hostError = null
                                    port = server.port.toString()
                                    portError = null
                                    scanResults = emptyList()
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${server.ip}:${server.port}", style = MaterialTheme.typography.bodyLarge)
                                    if (!server.banner.isNullOrBlank()) {
                                        Text(
                                            server.banner,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; hostError = null },
                    label = { Text("Host o IP") },
                    singleLine = true,
                    isError = hostError != null,
                    supportingText = hostError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it; portError = null },
                    label = { Text("Puerto") },
                    singleLine = true,
                    isError = portError != null,
                    supportingText = portError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Usuario") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Contraseña") },
                    singleLine = true,
                    supportingText = if (initial != null) {
                        { Text("Déjala vacía para conservar la actual") }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (type == RemoteType.S3) {
                // ---- S3 / Oracle Cloud Object Storage ----
                Text(
                    when (s3Prov) {
                        S3Provider.ORACLE ->
                            "Oracle Cloud: usa una «Customer Secret Key» (Perfil > Mi perfil > " +
                                "Claves secretas de cliente). El namespace está en Administración del inquilino."
                        S3Provider.AWS ->
                            "Amazon S3: usa una clave de acceso de IAM (Credenciales de seguridad > " +
                                "Crear clave de acceso) de un usuario con permisos sobre el bucket. " +
                                "La región es la del bucket."
                        S3Provider.CLOUDFLARE ->
                            "Cloudflare R2: crea un token de API (R2 > Administrar tokens de API) con permiso " +
                                "de lectura y escritura de objetos; te da la Access Key ID y la Secret."
                        S3Provider.OTHER ->
                            "Cualquier servicio compatible con S3 (MinIO, Wasabi, Backblaze B2...)."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DropdownSelector(
                    label = "Proveedor",
                    options = listOf(
                        SelectorOption(
                            S3Provider.ORACLE, S3Provider.ORACLE.label, "Object Storage (API compatible con S3)",
                            AppIcons.OracleLogo, branded = true
                        ),
                        SelectorOption(
                            S3Provider.AWS, S3Provider.AWS.label, "Amazon Web Services",
                            AppIcons.AwsLogo, branded = true
                        ),
                        SelectorOption(
                            S3Provider.CLOUDFLARE, S3Provider.CLOUDFLARE.label, "Sin cargos por salida de datos",
                            AppIcons.CloudflareLogo, branded = true
                        ),
                        SelectorOption(
                            S3Provider.OTHER, S3Provider.OTHER.label, "MinIO, Wasabi, Backblaze B2...",
                            AppIcons.Cloud
                        )
                    ),
                    selected = s3Prov,
                    onSelect = { s3Prov = it }
                )
                if (s3Prov == S3Provider.ORACLE) {
                    OutlinedTextField(
                        value = s3Namespace,
                        onValueChange = { s3Namespace = it.trim(); s3NamespaceError = null },
                        label = { Text("Namespace") },
                        singleLine = true,
                        isError = s3NamespaceError != null,
                        supportingText = s3NamespaceError?.let { { Text(it) } },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = s3Region,
                        onValueChange = { s3Region = it.trim(); s3RegionError = null },
                        label = { Text("Región") },
                        placeholder = { Text("us-ashburn-1") },
                        singleLine = true,
                        isError = s3RegionError != null,
                        supportingText = s3RegionError?.let { { Text(it) } },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (s3Prov == S3Provider.AWS) {
                    OutlinedTextField(
                        value = s3Region,
                        onValueChange = { s3Region = it.trim(); s3RegionError = null },
                        label = { Text("Región del bucket") },
                        placeholder = { Text("us-east-1") },
                        singleLine = true,
                        isError = s3RegionError != null,
                        supportingText = {
                            Text(s3RegionError ?: "Es la región donde se creó el bucket; se ve en la consola de S3.")
                        },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (s3Prov == S3Provider.CLOUDFLARE) {
                    OutlinedTextField(
                        value = s3Account,
                        onValueChange = { s3Account = it.trim(); s3AccountError = null },
                        label = { Text("Account ID") },
                        placeholder = { Text("32 caracteres") },
                        singleLine = true,
                        isError = s3AccountError != null,
                        supportingText = {
                            Text(
                                s3AccountError
                                    ?: "Está en el panel de Cloudflare, en R2 > Resumen. Si tu bucket es de " +
                                        "una jurisdicción (UE, FedRAMP), pega el endpoint completo."
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = s3Endpoint,
                        onValueChange = { s3Endpoint = it; s3EndpointError = null },
                        label = { Text("Endpoint") },
                        placeholder = { Text("https://s3.ejemplo.com") },
                        singleLine = true,
                        isError = s3EndpointError != null,
                        supportingText = s3EndpointError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = s3Region,
                        onValueChange = { s3Region = it.trim() },
                        label = { Text("Región (opcional)") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = s3AccessKey,
                    onValueChange = { s3AccessKey = it.trim(); s3AccessKeyError = null },
                    label = { Text("Access Key ID") },
                    singleLine = true,
                    isError = s3AccessKeyError != null,
                    supportingText = s3AccessKeyError?.let { { Text(it) } },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
                val secretHint = s3SecretError
                    ?: if (initialS3?.hasSecret == true) "Déjala vacía para conservar la actual" else null
                OutlinedTextField(
                    value = s3Secret,
                    onValueChange = { s3Secret = it; s3SecretError = null },
                    label = { Text("Secret Access Key") },
                    singleLine = true,
                    isError = s3SecretError != null,
                    supportingText = secretHint?.let { { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = s3Bucket,
                    // Se guarda lo escrito tal cual y se limpia al guardar: limpiar
                    // en cada tecla quitaba la "/" final apenas se escribía, y solo
                    // se podía poner pegando "bucket/carpeta" de una vez.
                    onValueChange = { s3Bucket = it; s3BucketError = null },
                    // Teclado de direcciones: trae la "/" en la fila principal.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    label = { Text("Bucket (opcional)") },
                    isError = s3BucketError != null,
                    supportingText = {
                        Text(
                            s3BucketError
                                ?: "Se monta ese bucket (o «bucket/carpeta»). Vacío muestra todos los buckets; " +
                                    "si tu clave no puede listarlos, escribe el bucket."
                        )
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // ---- Google Drive ----
                Text(
                    when {
                        newToken != null -> "Cuenta de Google conectada. Guarda para aplicarla."
                        hasSession -> "Ya hay una sesión de Google guardada."
                        clientChanged -> "Cambiaste el cliente OAuth: vuelve a iniciar sesión."
                        else -> "Inicia sesión con tu cuenta de Google para dar acceso a Drive."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        if (effectiveClientId.isEmpty() != effectiveClientSecret.isEmpty()) {
                            driveError = "Client ID y Client Secret van juntos: llena los dos o ninguno."
                            showAdvanced = true
                        } else {
                            driveError = null
                            onDriveLogin(effectiveClientId, effectiveClientSecret)
                        }
                    },
                    enabled = !loginRunning,
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4285F4), // Azul Google
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF4285F4).copy(alpha = 0.38f),
                        disabledContentColor = Color.White.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(if (hasSession) "Volver a iniciar sesión" else "Iniciar sesión con Google")
                }

                when (driveAuth) {
                    is DriveAuthState.Starting -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Iniciando…", style = MaterialTheme.typography.bodyMedium)
                    }
                    is DriveAuthState.WaitingBrowser -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Autoriza el acceso en el navegador y vuelve a esta app. " +
                                "Verás «Success!» cuando termine.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                if (!openInBrowser(appContext, driveAuth.url)) {
                                    driveError = "No se encontró un navegador. Copia el enlace y ábrelo a mano."
                                }
                            }) { Text("Abrir de nuevo") }
                            TextButton(onClick = { copyToClipboard(appContext, driveAuth.url) }) { Text("Copiar enlace") }
                            TextButton(onClick = onDriveCancel) { Text("Cancelar") }
                        }
                    }
                    is DriveAuthState.Failed -> Text(
                        driveAuth.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> Unit
                }

                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Solo lectura", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Impide modificar o borrar archivos de Drive desde la carpeta montada.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Ocultar opciones avanzadas" else "Opciones avanzadas")
                }
                if (showAdvanced) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Permitir archivos marcados como malware", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Descarga archivos que Google Drive bloquea como malware o spam " +
                                    "(error 403 cannotDownloadAbusiveFile). Actívalo solo si confías en el contenido.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = acknowledgeAbuse, onCheckedChange = { acknowledgeAbuse = it })
                    }
                    OutlinedTextField(
                        value = rootFolder,
                        onValueChange = { rootFolder = extractDriveFolderId(it) },
                        label = { Text("ID de carpeta raíz (opcional)") },
                        supportingText = { Text("Monta solo esa carpeta en vez de todo Mi unidad. Puedes pegar el link para compartir: se toma solo el ID.") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = teamDrive,
                        onValueChange = { teamDrive = it },
                        label = { Text("ID de unidad compartida (opcional)") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it; driveError = null },
                        label = { Text("Client ID propio (opcional)") },
                        supportingText = {
                            Text(
                                if (BuildConfig.GDRIVE_CLIENT_ID.isNotEmpty()) "Vacío = el que trae la app."
                                else "Sin esto se usa el compartido de rclone, con cuota limitada."
                            )
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it; driveError = null },
                        label = { Text("Client Secret propio (opcional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { showManual = !showManual }) {
                        Text(if (showManual) "Ocultar token manual" else "Pegar token manualmente")
                    }
                    if (showManual) {
                        Text(
                            "En un PC ejecuta: rclone authorize \"drive\" y pega aquí el bloque JSON que imprime.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = manualToken,
                            onValueChange = { manualToken = it },
                            label = { Text("Token (JSON)") },
                            minLines = 3,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                val normalized = normalizeToken(manualToken)
                                if (normalized == null) {
                                    driveError = "Token inválido: debe traer access_token y refresh_token."
                                } else {
                                    newToken = normalized
                                    driveError = null
                                    manualToken = ""
                                    showManual = false
                                }
                            },
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Usar este token") }
                    }
                }

                driveError?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }

            Button(
                onClick = {
                    val cleanName = name.trim()
                    nameError = validateProfileName(cleanName, initial?.name, existingNames)
                    if (type == RemoteType.FTP) {
                        hostError = if (cleanHost(host).isEmpty()) "Escribe la dirección del servidor" else null
                        val portNumber = port.trim().toIntOrNull()
                        portError = if (portNumber == null || portNumber !in 1..65535) "Usa un puerto entre 1 y 65535" else null
                        if (nameError == null && hostError == null && portError == null) {
                            onSaveFtp(cleanName, host, port, user, pass)
                        }
                    } else if (type == RemoteType.S3) {
                        val endpoint: String
                        val region: String
                        s3AccountError = null
                        if (s3Prov == S3Provider.ORACLE) {
                            s3NamespaceError = validateOracleNamespace(s3Namespace.trim())
                            s3RegionError = validateOracleRegion(s3Region.trim())
                            endpoint = oracleEndpoint(s3Namespace, s3Region)
                            region = s3Region.trim()
                            s3EndpointError = null
                        } else if (s3Prov == S3Provider.AWS) {
                            region = s3Region.trim()
                            s3RegionError = validateAwsRegion(region)
                            endpoint = awsEndpoint(region)
                            s3NamespaceError = null
                            s3EndpointError = null
                        } else if (s3Prov == S3Provider.CLOUDFLARE) {
                            s3AccountError = validateCloudflareAccount(s3Account)
                            endpoint = cloudflareEndpoint(s3Account).orEmpty()
                            // R2 reparte los buckets solo; su región es siempre "auto".
                            region = "auto"
                            s3NamespaceError = null
                            s3RegionError = null
                            s3EndpointError = null
                        } else {
                            endpoint = cleanS3Endpoint(s3Endpoint)
                            region = s3Region.trim()
                            s3EndpointError = validateS3Endpoint(endpoint)
                            s3NamespaceError = null
                            s3RegionError = null
                        }
                        s3AccessKeyError = if (s3AccessKey.isBlank()) "Escribe la clave de acceso" else null
                        s3SecretError = if (s3Secret.isEmpty() && initialS3?.hasSecret != true) {
                            "Escribe la clave secreta"
                        } else null
                        val bucketClean = cleanS3Bucket(s3Bucket)
                        s3BucketError = validateS3Bucket(bucketClean)
                        if (nameError == null && s3NamespaceError == null && s3AccountError == null &&
                            s3RegionError == null && s3EndpointError == null && s3AccessKeyError == null &&
                            s3SecretError == null && s3BucketError == null
                        ) {
                            onSaveS3(
                                cleanName,
                                S3Options(
                                    endpoint = endpoint,
                                    region = region,
                                    accessKeyId = s3AccessKey.trim(),
                                    bucket = bucketClean
                                ),
                                s3Secret
                            )
                        }
                    } else {
                        driveError = when {
                            effectiveClientId.isEmpty() != effectiveClientSecret.isEmpty() ->
                                "Client ID y Client Secret van juntos: llena los dos o ninguno."
                            !hasSession -> "Inicia sesión con Google antes de guardar."
                            else -> null
                        }
                        if (nameError == null && driveError == null) {
                            onSaveDrive(
                                cleanName,
                                newToken,
                                DriveOptions(
                                    // Siempre se guarda el cliente usado en el login: el
                                    // refresh token solo vale con ese mismo cliente.
                                    clientId = effectiveClientId,
                                    clientSecret = effectiveClientSecret,
                                    readOnly = readOnly,
                                    rootFolderId = rootFolder.trim(),
                                    teamDrive = teamDrive.trim(),
                                    acknowledgeAbuse = acknowledgeAbuse
                                )
                            )
                        }
                    }
                },
                enabled = !loginRunning,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (initial == null) "Guardar servidor" else "Guardar cambios")
            }
        }
    }
}

/** Abre [url] en el navegador del sistema. Devuelve false si no hay ninguno. */
private fun openInBrowser(context: Context, url: String): Boolean =
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Enlace de Google", text))
}
