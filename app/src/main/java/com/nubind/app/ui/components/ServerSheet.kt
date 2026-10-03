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
import com.nubind.app.R
import com.nubind.app.Strings

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
                    driveError = Strings.get(R.string.no_se_encontro_un_navegador_copia)
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
                scanMessage = if (found.isEmpty()) Strings.get(R.string.no_se_encontro_ningun_servidor_ftp) else null
            } catch (e: CancellationException) {
                throw e // el usuario tocó "Cancelar búsqueda"; no es un error
            } catch (e: Exception) {
                // Antes, cualquier excepción acá dejaba el botón trabado en
                // "Cancelar búsqueda…" para siempre sin ningún aviso.
                scanMessage = Strings.get(R.string.no_se_pudo_completar_la_busqueda, e.message ?: e::class.simpleName)
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
                text = if (initial == null) Strings.get(R.string.nuevo_servidor) else Strings.get(R.string.editar_servidor),
                style = MaterialTheme.typography.headlineMedium
            )

            if (initial == null) {
                DropdownSelector(
                    label = Strings.get(R.string.tipo_de_servidor),
                    options = listOf(
                        SelectorOption(
                            RemoteType.FTP, "FTP", Strings.get(R.string.servidor_en_tu_red_o_en),
                            AppIcons.Dns
                        ),
                        SelectorOption(
                            RemoteType.DRIVE, "Google Drive", Strings.get(R.string.tu_cuenta_de_google),
                            AppIcons.DriveLogo, branded = true
                        ),
                        SelectorOption(
                            RemoteType.S3, "S3", Strings.get(R.string.oracle_amazon_cloudflare_r2_y_mas),
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
                label = { Text(Strings.get(R.string.nombre)) },
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
                    Text(if (scanning) Strings.get(R.string.cancelar_busqueda, scanChecked, scanTotal) else Strings.get(R.string.buscar_servidores_ftp_en_mi_red))
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
                    Text(Strings.get(R.string.toca_uno_para_usarlo), style = MaterialTheme.typography.labelLarge)
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
                    label = { Text(Strings.get(R.string.host_o_ip)) },
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
                    label = { Text(Strings.get(R.string.puerto_2)) },
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
                    label = { Text(Strings.get(R.string.usuario)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(Strings.get(R.string.contrasena)) },
                    singleLine = true,
                    supportingText = if (initial != null) {
                        { Text(Strings.get(R.string.dejala_vacia_para_conservar_la_actual)) }
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
                            Strings.get(R.string.oracle_cloud_usa_una_customer_secret)
                        S3Provider.AWS ->
                            Strings.get(R.string.amazon_s3_usa_una_clave_de)
                        S3Provider.CLOUDFLARE ->
                            Strings.get(R.string.cloudflare_r2_crea_un_token_de)
                        S3Provider.OTHER ->
                            Strings.get(R.string.cualquier_servicio_compatible_con_s3_minio)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DropdownSelector(
                    label = Strings.get(R.string.proveedor),
                    options = listOf(
                        SelectorOption(
                            S3Provider.ORACLE, S3Provider.ORACLE.label, Strings.get(R.string.object_storage_api),
                            AppIcons.OracleLogo, branded = true
                        ),
                        SelectorOption(
                            S3Provider.AWS, S3Provider.AWS.label, "Amazon Web Services",
                            AppIcons.AwsLogo, branded = true
                        ),
                        SelectorOption(
                            S3Provider.CLOUDFLARE, S3Provider.CLOUDFLARE.label, Strings.get(R.string.sin_cargos_por_salida_de_datos),
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
                        label = { Text(Strings.get(R.string.region)) },
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
                        label = { Text(Strings.get(R.string.region_del_bucket)) },
                        placeholder = { Text("us-east-1") },
                        singleLine = true,
                        isError = s3RegionError != null,
                        supportingText = {
                            Text(s3RegionError ?: Strings.get(R.string.es_la_region_donde_se_creo))
                        },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (s3Prov == S3Provider.CLOUDFLARE) {
                    OutlinedTextField(
                        value = s3Account,
                        onValueChange = { s3Account = it.trim(); s3AccountError = null },
                        label = { Text("Account ID") },
                        placeholder = { Text(Strings.get(R.string.s_32_caracteres)) },
                        singleLine = true,
                        isError = s3AccountError != null,
                        supportingText = {
                            Text(
                                s3AccountError
                                    ?: Strings.get(R.string.esta_en_el_panel_de_cloudflare)
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
                        label = { Text(Strings.get(R.string.region_opcional)) },
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
                    ?: if (initialS3?.hasSecret == true) Strings.get(R.string.dejala_vacia_para_conservar_la_actual) else null
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
                    label = { Text(Strings.get(R.string.bucket_opcional)) },
                    isError = s3BucketError != null,
                    supportingText = {
                        Text(
                            s3BucketError
                                ?: Strings.get(R.string.se_monta_ese_bucket_o_bucket)
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
                        newToken != null -> Strings.get(R.string.cuenta_de_google_conectada_guarda_para)
                        hasSession -> Strings.get(R.string.ya_hay_una_sesion_de_google)
                        clientChanged -> Strings.get(R.string.cambiaste_el_cliente_oauth_vuelve_a)
                        else -> Strings.get(R.string.inicia_sesion_con_tu_cuenta_de)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        if (effectiveClientId.isEmpty() != effectiveClientSecret.isEmpty()) {
                            driveError = Strings.get(R.string.client_id_y_client_secret_van)
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
                    Text(if (hasSession) Strings.get(R.string.volver_a_iniciar_sesion) else Strings.get(R.string.iniciar_sesion_con_google))
                }

                when (driveAuth) {
                    is DriveAuthState.Starting -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(Strings.get(R.string.iniciando), style = MaterialTheme.typography.bodyMedium)
                    }
                    is DriveAuthState.WaitingBrowser -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            Strings.get(R.string.autoriza_el_acceso_en_el_navegador),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                if (!openInBrowser(appContext, driveAuth.url)) {
                                    driveError = Strings.get(R.string.no_se_encontro_un_navegador_copia)
                                }
                            }) { Text(Strings.get(R.string.abrir_de_nuevo)) }
                            TextButton(onClick = { copyToClipboard(appContext, driveAuth.url) }) { Text(Strings.get(R.string.copiar_enlace)) }
                            TextButton(onClick = onDriveCancel) { Text(Strings.get(R.string.cancelar)) }
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
                        Text(Strings.get(R.string.solo_lectura), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            Strings.get(R.string.impide_modificar_o_borrar_archivos_de),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) Strings.get(R.string.ocultar_opciones_avanzadas) else Strings.get(R.string.opciones_avanzadas))
                }
                if (showAdvanced) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(Strings.get(R.string.permitir_archivos_marcados_como_malware), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                Strings.get(R.string.descarga_archivos_que_google_drive_bloquea),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = acknowledgeAbuse, onCheckedChange = { acknowledgeAbuse = it })
                    }
                    OutlinedTextField(
                        value = rootFolder,
                        onValueChange = { rootFolder = extractDriveFolderId(it) },
                        label = { Text(Strings.get(R.string.id_de_carpeta_raiz_opcional)) },
                        supportingText = { Text(Strings.get(R.string.monta_solo_esa_carpeta_en_vez)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = teamDrive,
                        onValueChange = { teamDrive = it },
                        label = { Text(Strings.get(R.string.id_de_unidad_compartida_opcional)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it; driveError = null },
                        label = { Text(Strings.get(R.string.client_id_propio_opcional)) },
                        supportingText = {
                            Text(
                                if (BuildConfig.GDRIVE_CLIENT_ID.isNotEmpty()) Strings.get(R.string.vacio_el_que_trae_la_app)
                                else Strings.get(R.string.sin_esto_se_usa_el_compartido)
                            )
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it; driveError = null },
                        label = { Text(Strings.get(R.string.client_secret_propio_opcional)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { showManual = !showManual }) {
                        Text(if (showManual) Strings.get(R.string.ocultar_token_manual) else Strings.get(R.string.pegar_token_manualmente))
                    }
                    if (showManual) {
                        Text(
                            Strings.get(R.string.en_un_pc_ejecuta_rclone_authorize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = manualToken,
                            onValueChange = { manualToken = it },
                            label = { Text(Strings.get(R.string.token_json)) },
                            minLines = 3,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                val normalized = normalizeToken(manualToken)
                                if (normalized == null) {
                                    driveError = Strings.get(R.string.token_invalido_debe_traer_access_token)
                                } else {
                                    newToken = normalized
                                    driveError = null
                                    manualToken = ""
                                    showManual = false
                                }
                            },
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(Strings.get(R.string.usar_este_token)) }
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
                        hostError = if (cleanHost(host).isEmpty()) Strings.get(R.string.escribe_la_direccion_del_servidor) else null
                        val portNumber = port.trim().toIntOrNull()
                        portError = if (portNumber == null || portNumber !in 1..65535) Strings.get(R.string.usa_un_puerto_entre_1_y) else null
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
                        s3AccessKeyError = if (s3AccessKey.isBlank()) Strings.get(R.string.escribe_la_clave_de_acceso) else null
                        s3SecretError = if (s3Secret.isEmpty() && initialS3?.hasSecret != true) {
                            Strings.get(R.string.escribe_la_clave_secreta)
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
                                Strings.get(R.string.client_id_y_client_secret_van)
                            !hasSession -> Strings.get(R.string.inicia_sesion_con_google_antes_de)
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
                Text(if (initial == null) Strings.get(R.string.guardar_servidor) else Strings.get(R.string.guardar_cambios))
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
    clipboard.setPrimaryClip(ClipData.newPlainText(Strings.get(R.string.enlace_de_google), text))
}
