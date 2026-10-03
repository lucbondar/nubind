package com.nubind.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import com.nubind.app.root.DEFAULT_TARGET_PATH
import com.nubind.app.root.DriveAuthParser
import com.nubind.app.root.DriveAuthState
import com.nubind.app.root.DriveOptions
import com.nubind.app.root.ModulePaths
import com.nubind.app.root.S3Options
import com.nubind.app.root.S3PerfSettings
import com.nubind.app.root.PerfMode
import com.nubind.app.root.PerfTestParser
import com.nubind.app.root.PerfTestState
import com.nubind.app.root.PreloadStatus
import com.nubind.app.root.PreloadStatusParser
import com.nubind.app.root.RemoteProfile
import com.nubind.app.root.RootShell
import com.nubind.app.root.cleanHost
import com.nubind.app.root.cleanTargetPath
import com.nubind.app.root.formatCacheKb
import com.nubind.app.root.validateTargetPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Snapshot(
    val profiles: List<RemoteProfile>,
    val active: String?,
    val status: String,
    val autostart: Boolean,
    val targetPath: String,
    val perfMode: PerfMode,
    val cacheGb: Int?,
    val cacheKb: Long,
    val serverCacheKb: Map<String, Long>,
    val ramCache: Boolean,
    val s3Perf: S3PerfSettings,
    val preloadRaw: String
)

class BindViewModel : ViewModel() {

    private val perfSaveMutex = Mutex()

    var isMounted by mutableStateOf(false)
        private set
    /** Servidor que está montado ahora mismo (null si no hay o no se sabe). */
    var mountedRemote by mutableStateOf<String?>(null)
        private set
    var autostart by mutableStateOf(false)
        private set
    var logs by mutableStateOf("")
        private set
    var rootGranted by mutableStateOf<Boolean?>(null)
        private set
    var profiles by mutableStateOf<List<RemoteProfile>>(emptyList())
        private set
    /** Ruta donde queda visible el bind (editable desde Inicio). */
    var targetPath by mutableStateOf(DEFAULT_TARGET_PATH)
        private set
    /** Perfil de rendimiento del montaje (Equilibrado o Máximo). */
    var perfMode by mutableStateOf(PerfMode.BALANCED)
        private set
    /** Tamaño de caché elegido en GB; null = el que trae el perfil. */
    var cacheGb by mutableStateOf<Int?>(null)
        private set
    /** Tamaño actual de la caché en disco (KB), para mostrarlo junto al botón de borrarla. */
    var cacheKb by mutableStateOf(0L)
    /** Caché en disco de cada servidor, en KB (por nombre). La suma de todos es [cacheKb]. */
    var serverCacheKb by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
        private set
    /** Servidor seleccionado: el que usa el botón Montar. */
    var activeName by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** true mientras dura un "deslizar para actualizar" (lo muestra el indicador). */
    var refreshing by mutableStateOf(false)
        private set
    /** Progreso del inicio de sesión con Google (lo muestra el formulario de servidor). */
    var driveAuth by mutableStateOf<DriveAuthState>(DriveAuthState.Idle)
        private set
    /** Prueba de rendimiento: la muestra PerfTestSheet. */
    /** Caché en RAM del perfil Máximo (config/ram_cache); mount.sh decide si hay memoria para cumplirlo. */
    var ramCache by mutableStateOf(false)
        private set
    /** Ajustes de rendimiento de S3; null en cada campo = automático. */
    var s3Perf by mutableStateOf(S3PerfSettings())
        private set
    var perfTest by mutableStateOf(PerfTestState())
        private set
    /** Progreso de la precarga de archivos a la caché (SectionCard de Inicio en perfil Máximo). Null = no aplica. */
    var preloadStatus by mutableStateOf<PreloadStatus?>(null)
        private set
    /** Mensaje de una sola vez; la UI lo muestra en un snackbar y lo consume. */
    var message by mutableStateOf<String?>(null)
        private set

    fun consumeMessage() {
        message = null
    }

    /**
     * Acción en espera de confirmación cuando la red activa es de datos
     * móviles (o con límite) y la acción dispara la precarga completa del
     * perfil Máximo. Mientras no sea null, la UI muestra el aviso.
     */
    var pendingMeteredAction by mutableStateOf<(() -> Unit)?>(null)
        private set

    private fun isOnMeteredNetwork(): Boolean =
        Strings.context().getSystemService(ConnectivityManager::class.java)
            ?.isActiveNetworkMetered ?: false

    /** Ejecuta [action] ya, o pide confirmación antes si se está en datos móviles. */
    private fun guardMetered(action: () -> Unit) {
        val skip = prefs().getBoolean(KEY_SKIP_METERED_WARNING, false)
        if (!skip && isOnMeteredNetwork()) pendingMeteredAction = action else action()
    }

    private fun prefs() = Strings.context().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** [dontShowAgain]: el usuario marcó "No volver a mostrar" al continuar. */
    fun confirmMetered(dontShowAgain: Boolean = false) {
        if (dontShowAgain) prefs().edit().putBoolean(KEY_SKIP_METERED_WARNING, true).apply()
        val action = pendingMeteredAction
        pendingMeteredAction = null
        action?.invoke()
    }

    fun dismissMetered() {
        pendingMeteredAction = null
    }

    fun setRootGranted(granted: Boolean) {
        rootGranted = granted
    }

    fun refreshAll() = viewModelScope.launch { reload() }

    /**
     * Actualiza estado, servidores y log de una vez (gesto de deslizar hacia
     * abajo). El indicador se mantiene un mínimo para que no parpadee cuando
     * la lectura es instantánea.
     */
    fun pullRefresh() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try {
                val started = SystemClock.elapsedRealtime()
                reload()
                logs = withContext(Dispatchers.IO) { RootShell.tailLog() }.output
                val remaining = MIN_REFRESH_MS - (SystemClock.elapsedRealtime() - started)
                if (remaining > 0) delay(remaining)
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun reload() {
        val snap = withContext(Dispatchers.IO) {
            val activeNow = RootShell.readActive()
            RootShell.migrateLegacyCacheGb(activeNow)
            val loaded = RootShell.loadProfiles()
            Snapshot(
                profiles = loaded,
                active = activeNow,
                status = RootShell.status().output,
                autostart = RootShell.readAutostart(),
                targetPath = RootShell.readTargetPath(),
                perfMode = RootShell.readPerfMode(),
                cacheGb = RootShell.readCacheGb(activeNow),
                cacheKb = RootShell.cacheSizeKb(),
                serverCacheKb = loaded.associate { it.name to RootShell.serverCacheKb(it.name) },
                ramCache = RootShell.readRamCache(),
                s3Perf = RootShell.readS3Perf(),
                preloadRaw = RootShell.preloadStatus()
            )
        }

        // Si el seleccionado no existe (borrado, o primera vez), se elige el primero.
        var active = snap.active
        if (snap.profiles.isEmpty()) {
            active = null
        } else if (snap.profiles.none { it.name == active }) {
            val first = snap.profiles.first().name
            active = first
            withContext(Dispatchers.IO) { RootShell.setActive(first) }
        }

        profiles = snap.profiles
        activeName = active
        autostart = snap.autostart
        targetPath = snap.targetPath
        perfMode = snap.perfMode
        // Si el servidor activo cambió porque el anterior ya no existe, el tamaño es el del nuevo.
        cacheGb = if (active == snap.active) snap.cacheGb
        else withContext(Dispatchers.IO) { RootShell.readCacheGb(active) }
        cacheKb = snap.cacheKb
        serverCacheKb = snap.serverCacheKb
        ramCache = snap.ramCache
        s3Perf = snap.s3Perf
        isMounted = snap.status.contains("\"mounted\":true")
        mountedRemote = if (isMounted) {
            Regex("\"remote\":\"([^\"]*)\"").find(snap.status)?.groupValues?.get(1)
        } else null
        preloadStatus = PreloadStatusParser.parse(snap.preloadRaw)
        // mount.sh lanza la precarga sola justo después de montar: si sigue
        // corriendo (recién montado, o se reabrió la app a mitad de una
        // precarga larga) y nada la está siguiendo todavía, se retoma el
        // sondeo para que la barra de progreso avance sola.
        if (preloadStatus?.running == true && preloadJob?.isActive != true) {
            preloadJob = viewModelScope.launch { watchPreload() }
        }
    }

    fun selectProfile(name: String) = viewModelScope.launch {
        activeName = name
        cacheGb = withContext(Dispatchers.IO) {
            RootShell.setActive(name)
            RootShell.readCacheGb(name)
        }
    }

    fun saveProfile(
        original: String?,
        name: String,
        host: String,
        port: String,
        user: String,
        pass: String
    ) = viewModelScope.launch {
        val cleanName = name.trim()
        val wasActive = original != null && original == activeName
        val result = withContext(Dispatchers.IO) {
            val r = RootShell.saveProfile(original, cleanName, cleanHost(host), port.trim(), user.trim(), pass)
            // Un servidor nuevo queda seleccionado; uno renombrado conserva la selección.
            if (r.success && (original == null || wasActive)) RootShell.setActive(cleanName)
            r
        }
        message = if (result.success) Strings.get(R.string.servidor_guardado) else Strings.get(R.string.error_al_guardar, result.output.take(200))
        reload()
    }

    fun saveDriveProfile(
        original: String?,
        name: String,
        token: String?,
        options: DriveOptions
    ) = viewModelScope.launch {
        val cleanName = name.trim()
        val wasActive = original != null && original == activeName
        val result = withContext(Dispatchers.IO) {
            val r = RootShell.saveDriveProfile(original, cleanName, token, options)
            if (r.success && (original == null || wasActive)) RootShell.setActive(cleanName)
            r
        }
        if (!result.success) {
            message = Strings.get(R.string.error_al_guardar, result.output.take(200))
            reload()
            return@launch
        }
        message = Strings.get(R.string.servidor_guardado)
        reload()

        // Comprobación real contra Google (sesión, red, DNS, certificados):
        // así un fallo se ve ahora y no recién al intentar montar.
        val check = withContext(Dispatchers.IO) { RootShell.checkRemote(cleanName) }
        message = if (check.success) {
            Strings.get(R.string.google_drive_conectado)
        } else {
            val detail = check.output.lines().lastOrNull { it.isNotBlank() }?.take(200)
                ?: Strings.get(R.string.sin_respuesta_sin_red_o_tiempo)
            Strings.get(R.string.guardado_pero_no_se_pudo_conectar, detail)
        }
    }

    /**
     * Guarda un servidor S3 y comprueba que responde (lista el bucket, o los
     * buckets si no se puso uno): así una clave, región o endpoint mal
     * escritos se ven ahora y no recién al intentar montar.
     */
    fun saveS3Profile(
        original: String?,
        name: String,
        options: S3Options,
        secret: String
    ) = viewModelScope.launch {
        val cleanName = name.trim()
        val wasActive = original != null && original == activeName
        val result = withContext(Dispatchers.IO) {
            val r = RootShell.saveS3Profile(original, cleanName, options, secret.trim())
            if (r.success && (original == null || wasActive)) RootShell.setActive(cleanName)
            r
        }
        if (!result.success) {
            message = Strings.get(R.string.error_al_guardar, result.output.take(200))
            reload()
            return@launch
        }
        message = Strings.get(R.string.servidor_guardado)
        reload()

        val check = withContext(Dispatchers.IO) { RootShell.checkRemote(cleanName) }
        message = if (check.success) {
            Strings.get(R.string.s3_conectado)
        } else {
            Strings.get(R.string.guardado_pero_no_se_pudo_conectar, describeS3Error(check.output))
        }
    }

    /** Traduce los errores típicos de S3 a algo que el usuario pueda corregir. */
    private fun describeS3Error(output: String): String {
        val last = output.lines().lastOrNull { it.isNotBlank() }?.take(200)
            ?: return Strings.get(R.string.sin_respuesta_sin_red_o_tiempo)
        val hint = when {
            "SignatureDoesNotMatch" in output ->
                Strings.get(R.string.la_clave_secreta_o_la_region)
            "InvalidAccessKeyId" in output || "InvalidClientTokenId" in output ->
                Strings.get(R.string.la_clave_de_acceso_no_existe)
            "AccessDenied" in output || "403" in output ->
                Strings.get(R.string.sin_permiso_revisa_las_politicas_de)
            "PermanentRedirect" in output || "AuthorizationHeaderMalformed" in output ->
                Strings.get(R.string.la_region_no_es_la_del)
            "NoSuchBucket" in output || "directory not found" in output ->
                Strings.get(R.string.el_bucket_no_existe_revisa_su)
            "no such host" in output || "lookup" in output ->
                Strings.get(R.string.no_se_resuelve_el_endpoint_revisa)
            else -> null
        }
        return if (hint != null) "$hint ($last)" else last
    }

    private var authJob: Job? = null

    /**
     * Inicia el login con Google: lanza `rclone authorize` con root, espera la
     * URL (el formulario la abre en el navegador) y luego el token. Si el
     * usuario cancela o cierra el formulario, [cancelDriveLogin] detiene todo.
     */
    fun startDriveLogin(clientId: String, clientSecret: String) {
        authJob?.cancel()
        authJob = viewModelScope.launch {
            driveAuth = DriveAuthState.Starting
            try {
                val started = withContext(Dispatchers.IO) { RootShell.driveAuthStart(clientId, clientSecret) }
                if (!started.success) {
                    driveAuth = DriveAuthState.Failed(Strings.get(R.string.no_se_pudo_iniciar_rclone, started.output.takeLast(200)))
                    return@launch
                }
                val deadline = SystemClock.elapsedRealtime() + AUTH_TIMEOUT_MS
                while (isActive) {
                    delay(AUTH_POLL_MS)
                    val output = withContext(Dispatchers.IO) { RootShell.driveAuthOutput() }
                    val progress = DriveAuthParser.parse(output)

                    val token = progress.token
                    if (token != null) {
                        driveAuth = DriveAuthState.Success(token)
                        return@launch
                    }
                    if (progress.exitCode != null) {
                        driveAuth = DriveAuthState.Failed(progress.error ?: Strings.get(R.string.el_inicio_de_sesion_termino_sin))
                        return@launch
                    }
                    val url = progress.url
                    if (url != null && driveAuth !is DriveAuthState.WaitingBrowser) {
                        driveAuth = DriveAuthState.WaitingBrowser(url)
                    }
                    if (SystemClock.elapsedRealtime() > deadline) {
                        driveAuth = DriveAuthState.Failed(Strings.get(R.string.tiempo_agotado_esperando_la_autorizacion))
                        return@launch
                    }
                }
            } finally {
                // Siempre: mata rclone si sigue vivo y borra auth.out (lleva el token).
                withContext(NonCancellable + Dispatchers.IO) { RootShell.driveAuthStop() }
            }
        }
    }

    fun cancelDriveLogin() {
        authJob?.cancel()
        authJob = null
        driveAuth = DriveAuthState.Idle
    }

    private var perfJob: Job? = null

    /**
     * Lanza la prueba de rendimiento y va leyendo su progreso. Al terminar, o si
     * se cancela, mata el script por si sigue vivo (y así se borra el archivo
     * temporal que haya dejado en la carpeta montada).
     */
    fun startPerfTest() {
        perfJob?.cancel()
        perfJob = viewModelScope.launch {
            perfTest = PerfTestState(started = true)
            try {
                val started = withContext(Dispatchers.IO) { RootShell.perfTestStart() }
                if (!started.success) {
                    perfTest = PerfTestState(
                        started = true,
                        error = Strings.get(R.string.no_se_pudo_iniciar_la_prueba, started.output.takeLast(200))
                    )
                    return@launch
                }
                val deadline = SystemClock.elapsedRealtime() + PERF_TIMEOUT_MS
                while (isActive) {
                    delay(PERF_POLL_MS)
                    val output = withContext(Dispatchers.IO) { RootShell.perfTestOutput() }
                    val progress = PerfTestParser.parse(output)
                    perfTest = PerfTestState(started = true, progress = progress)
                    if (progress.verdict != null) return@launch
                    if (SystemClock.elapsedRealtime() > deadline) {
                        perfTest = PerfTestState(
                            started = true,
                            progress = progress,
                            error = Strings.get(R.string.la_prueba_tardo_demasiado_y_se)
                        )
                        return@launch
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { RootShell.perfTestStop() }
            }
        }
    }

    fun cancelPerfTest() {
        perfJob?.cancel()
        perfJob = null
        perfTest = PerfTestState()
    }

    private var preloadJob: Job? = null

    /**
     * Relanza la precarga (scripts/preload.sh) a mano: sirve tanto para
     * reintentar como para volver a comprobar el remoto después de agregar
     * archivos nuevos (ya montado, sin tener que desmontar y montar de
     * nuevo). Mientras corre queda visible en la tarjeta de Inicio; al
     * terminar en 100% el bind queda tan rápido como el almacenamiento
     * local para lo que ya se precargó.
     */
    fun preloadNow() = guardMetered { performPreloadNow() }

    private fun performPreloadNow() = viewModelScope.launch {
        if (!isMounted) {
            message = Strings.get(R.string.monta_el_servidor_primero)
            return@launch
        }
        val started = withContext(Dispatchers.IO) { RootShell.preloadStart() }
        if (!started.success) {
            message = Strings.get(R.string.no_se_pudo_iniciar_la_precarga, started.output.takeLast(200))
            return@launch
        }
        preloadJob?.cancel()
        preloadJob = launch { watchPreload(announceIfNeverStarted = true) }
    }

    /**
     * Sondea preload_status.json. Antes de esta corrección se rendía en la
     * primera lectura que no mostrara "running":true — y esa primera
     * lectura, 1.5s después de lanzar el script, casi siempre llegaba ANTES
     * de que preload.sh terminara de recorrer el remoto con find (puede
     * tardar bastante más que eso en un FTP grande o lento) y escribiera su
     * primer estado. Resultado: tanto el botón manual como el arranque
     * automático tras montar parecían "no hacer nada", aunque la precarga sí
     * corriera de verdad en segundo plano.
     *
     * Ahora se distingue "todavía no arrancó" (se sigue esperando, hasta
     * [PRELOAD_START_GRACE_MS]) de "arrancó y ya terminó" (recién ahí se
     * corta el sondeo). [PRELOAD_TIMEOUT_MS] sigue como red de seguridad por
     * si algo se queda corriendo para siempre.
     */
    private suspend fun CoroutineScope.watchPreload(announceIfNeverStarted: Boolean = false) {
        val startDeadline = SystemClock.elapsedRealtime() + PRELOAD_START_GRACE_MS
        val hardDeadline = SystemClock.elapsedRealtime() + PRELOAD_TIMEOUT_MS
        var everRunning = false
        while (isActive) {
            delay(PRELOAD_POLL_MS)
            val output = withContext(Dispatchers.IO) { RootShell.preloadStatus() }
            val status = PreloadStatusParser.parse(output)
            preloadStatus = status
            // La caché en disco crece a medida que la precarga baja archivos:
            // se relee el tamaño en cada sondeo (incluido el último, cuando
            // termina) para que la tarjeta de Inicio avance sola.
            cacheKb = withContext(Dispatchers.IO) { RootShell.cacheSizeKb() }
            mountedRemote?.let { name ->
                val kb = withContext(Dispatchers.IO) { RootShell.serverCacheKb(name) }
                serverCacheKb = serverCacheKb + (name to kb)
            }
            val now = SystemClock.elapsedRealtime()
            if (status?.running == true) {
                // Notificación persistente (sigue el progreso con la app cerrada). Solo se
                // arranca cuando la precarga realmente corre: en Equilibrado preload.sh sale
                // sin hacer nada y no hay nada que notificar.
                if (!everRunning) PreloadService.start(Strings.context())
                everRunning = true
            } else if (everRunning) {
                return // Corría y ya terminó (o falló a medias): se corta acá.
            } else if (now > startDeadline) {
                // Nunca llegó a arrancar: perfil/remoto que no cachea lecturas
                // completas (comportamiento normal), o el script falló antes
                // de escribir nada.
                if (announceIfNeverStarted) {
                    message = Strings.get(R.string.la_precarga_no_llego_a_iniciar)
                }
                return
            }
            if (now > hardDeadline) return
        }
    }

    override fun onCleared() {
        authJob?.cancel()
        perfJob?.cancel()
        preloadJob?.cancel()
        super.onCleared()
    }

    fun deleteProfile(name: String) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { RootShell.deleteProfile(name) }
        message = if (result.success) Strings.get(R.string.servidor_eliminado) else Strings.get(R.string.error_al_eliminar, result.output.take(200))
        reload()
    }

    fun toggleMount() {
        val onlyUnmounting = isMounted && (mountedRemote == null || mountedRemote == activeName)
        // Montar en Máximo lanza la precarga completa: se avisa si hay datos móviles.
        if (!onlyUnmounting && perfMode == PerfMode.MAX) guardMetered { performToggleMount() }
        else performToggleMount()
    }

    private fun performToggleMount() = viewModelScope.launch {
        if (busy) return@launch
        val target = activeName
        if (target == null && !isMounted) {
            message = Strings.get(R.string.agrega_un_servidor_primero)
            return@launch
        }
        val mounted = isMounted
        val current = mountedRemote
        val onlyUnmounting = mounted && (current == null || current == target)
        busy = true
        val result = withContext(Dispatchers.IO) {
            when {
                onlyUnmounting -> RootShell.unmount()
                mounted -> {
                    // Hay otro servidor montado: se desmonta y se monta el seleccionado.
                    RootShell.unmount()
                    RootShell.mount()
                }
                else -> RootShell.mount()
            }
        }
        message = if (result.success) null else Strings.get(R.string.error, result.output.takeLast(200))
        reload()
        busy = false

        // mount.sh ya lanzó la precarga sola en segundo plano (ver
        // preload.sh). Se la sigue desde ya en vez de esperar a que algo
        // más (un "deslizar para actualizar") la note, porque puede tardar
        // en arrancar (recorre el remoto con find) y terminar sin que nadie
        // haya vuelto a leer el estado mientras tanto.
        if (result.success && !onlyUnmounting) {
            preloadJob?.cancel()
            preloadJob = launch { watchPreload() }
        }
    }

    fun setTargetPath(path: String) = viewModelScope.launch {
        val clean = cleanTargetPath(path)
        val error = validateTargetPath(clean)
        if (error != null) {
            message = error
            return@launch
        }
        val result = withContext(Dispatchers.IO) { RootShell.setTargetPath(clean) }
        if (result.success) {
            targetPath = clean
            message = if (isMounted) Strings.get(R.string.ruta_guardada_vuelve_a_montar_para) else Strings.get(R.string.ruta_guardada)
        } else {
            message = Strings.get(R.string.error_al_guardar_la_ruta, result.output.take(200))
        }
    }

    fun changePerfMode(mode: PerfMode) {
        val save = { savePerf({ RootShell.setPerfMode(mode) }); Unit }
        if (mode == PerfMode.MAX && perfMode != PerfMode.MAX) guardMetered(save) else save()
    }

    /** null restablece el tamaño del perfil. */
    fun changeCacheGb(gb: Int?) {
        // El tamaño es por servidor: se guarda para el seleccionado.
        val server = activeName ?: return
        savePerf({ RootShell.setCacheGb(server, gb) })
    }

    /** mount.sh comprueba la memoria disponible antes de activar tmpfs. */
    fun setRamCache(enabled: Boolean) = savePerf({ RootShell.setRamCache(enabled) })

    fun setS3Streams(value: Int?) = saveS3Perf(ModulePaths.S3_STREAMS, value)
    fun setS3UploadConcurrency(value: Int?) = saveS3Perf(ModulePaths.S3_UPLOAD_CONC, value)
    fun setS3ChunkMb(value: Int?) = saveS3Perf(ModulePaths.S3_CHUNK_MB, value)
    fun setS3FewerRequests(value: Boolean?) =
        saveS3Perf(ModulePaths.S3_FEWER_REQ, value?.let { if (it) 1 else 0 })
    fun setS3DirCacheMin(value: Int?) = saveS3Perf(ModulePaths.S3_DIR_CACHE_MIN, value)
    fun resetS3Perf() = savePerf({ RootShell.resetS3Perf() })

    private fun saveS3Perf(file: String, value: Int?) =
        savePerf({ RootShell.setS3PerfValue(file, value) })

    private fun savePerf(write: () -> RootShell.Result) = viewModelScope.launch {
        perfSaveMutex.withLock {
            val result = withContext(Dispatchers.IO) { write() }
            val server = activeName
            val saved = withContext(Dispatchers.IO) {
                SnapshotPerf(RootShell.readPerfMode(), RootShell.readCacheGb(server),
                    RootShell.readRamCache(), RootShell.readS3Perf())
            }
            perfMode = saved.mode
            cacheGb = saved.cacheGb
            ramCache = saved.ramCache
            s3Perf = saved.s3
            message = perfSaveMessage(result)
        }
    }

    private data class SnapshotPerf(
        val mode: PerfMode, val cacheGb: Int?, val ramCache: Boolean, val s3: S3PerfSettings
    )

    private fun perfSaveMessage(result: RootShell.Result): String? = when {
        !result.success -> Strings.get(R.string.error_al_guardar, result.output.take(200))
        isMounted -> Strings.get(R.string.guardado_vuelve_a_montar_para_aplicarlo)
        else -> null
    }

    fun setAutostart(enabled: Boolean) = viewModelScope.launch {
        autostart = enabled
        withContext(Dispatchers.IO) { RootShell.setAutostart(enabled) }
    }

    fun refreshLogs() = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { RootShell.tailLog() }
        logs = result.output
    }

    /** Borra el log: la pantalla se vacía al instante y el archivo se trunca en segundo plano. */
    fun clearLogs() {
        logs = ""
        viewModelScope.launch { withContext(Dispatchers.IO) { RootShell.clearLog() } }
    }

    /** Borra la caché en disco de rclone. Requiere tener el bind desmontado (ver clear_cache.sh). */
    fun clearCache() = viewModelScope.launch {
        if (isMounted) {
            message = Strings.get(R.string.desmonta_primero_para_borrar_la_cache)
            return@launch
        }
        if (busy) return@launch
        busy = true
        val result = withContext(Dispatchers.IO) { RootShell.clearCache() }
        busy = false
        message = if (result.success) {
            val kb = result.output.trim().removePrefix("OK").trim().toLongOrNull() ?: 0L
            cacheKb = 0L
            if (kb > 0) Strings.get(R.string.cache_borrada_liberados, formatCacheKb(kb)) else Strings.get(R.string.no_habia_nada_en_cache)
        } else {
            Strings.get(R.string.no_se_pudo_borrar_la_cache, result.output.take(200))
        }
    }

    /** Borra la caché de un solo servidor. Solo se rechaza si ese servidor es el que está montado. */
    fun clearServerCache(name: String) = viewModelScope.launch {
        if (isMounted && (mountedRemote == null || mountedRemote == name)) {
            message = Strings.get(R.string.desmonta_primero_para_borrar_la_cache)
            return@launch
        }
        if (busy) return@launch
        busy = true
        val result = withContext(Dispatchers.IO) { RootShell.clearServerCache(name) }
        message = if (result.success) {
            val kb = result.output.trim().removePrefix("OK").trim().toLongOrNull() ?: 0L
            if (kb > 0) Strings.get(R.string.cache_servidor_borrada, name, formatCacheKb(kb)) else Strings.get(R.string.no_habia_nada_en_cache)
        } else {
            Strings.get(R.string.no_se_pudo_borrar_la_cache, result.output.take(200))
        }
        // Relee todo: totales por servidor y el estado de la precarga, que el script pudo borrar.
        reload()
        busy = false
    }

    private companion object {
        const val PREFS_NAME = "nubind_prefs"
        const val KEY_SKIP_METERED_WARNING = "skip_metered_warning"
        const val AUTH_POLL_MS = 600L
        const val MIN_REFRESH_MS = 500L
        const val PERF_POLL_MS = 500L
        // La prueba entera tarda menos de 3 minutos aun con un enlace lento.
        const val PERF_TIMEOUT_MS = 300_000L
        // El script corta a los 300 s; esto es solo la red de seguridad de la app.
        const val AUTH_TIMEOUT_MS = 330_000L
        // La precarga puede bajar varios GB de archivos: se sondea
        // sin apuro y con un margen amplio antes de dar por perdido el seguimiento
        // (el script en sí no tiene límite de tiempo, sigue en segundo plano).
        const val PRELOAD_POLL_MS = 1500L
        const val PRELOAD_TIMEOUT_MS = 30 * 60_000L
        // Cuánto se espera a que preload.sh escriba su primer "running":true.
        // Recorrer el remoto con find (antes de poder escribir nada) puede
        // tardar bastante en un FTP grande o lento.
        const val PRELOAD_START_GRACE_MS = 60_000L
    }
}
