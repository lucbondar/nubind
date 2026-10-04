package com.nubind.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.toSize
import kotlin.math.max
import kotlin.math.min
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.AccountBox
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import kotlinx.coroutines.withTimeoutOrNull
import com.nubind.app.ui.components.NoticeKind
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nubind.app.ui.components.ExpressiveNoticeHost
import com.nubind.app.ui.components.LocalContentBottomInset
import com.nubind.app.ui.components.LocalContentEndInset
import com.nubind.app.ui.screens.AboutScreen
import com.nubind.app.ui.screens.HomeScreen
import com.nubind.app.ui.screens.LogsScreen
import com.nubind.app.ui.screens.ServersScreen
import com.nubind.app.ui.theme.NubindTheme
import com.topjohnwu.superuser.Shell
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: BindViewModel by viewModels()

    // Android 13+: sin este permiso la notificación de precarga no se ve en la barra.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            NubindTheme {
                AppScaffold(vm)
            }
        }

        // Busca una versión nueva de la app (solo red; el aviso de desfase con el
        // módulo se calcula en cuanto se confirma root, ver setRootGranted).
        vm.checkForUpdates()

        // Pedimos root en segundo plano. Si se niega o no hay root
        // disponible, la app sigue abierta y solo lo mostramos en la UI
        // en vez de lanzar una excepción no controlada que la cierra.
        Shell.getShell { shell ->
            vm.setRootGranted(shell.isRoot)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Búsqueda periódica de actualizaciones con la app a la vista (independiente del estado del módulo).
    private val updatePoll = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            vm.refreshUpdates(force = true)
            updatePoll.postDelayed(this, UPDATE_POLL_MS)
        }
    }

    override fun onPause() {
        updatePoll.removeCallbacks(pollRunnable)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        updatePoll.removeCallbacks(pollRunnable)
        updatePoll.postDelayed(pollRunnable, UPDATE_POLL_MS)
        // El montaje pudo cambiar desde el quick toggle mientras la app estaba en segundo plano.
        if (vm.rootGranted == true) vm.refreshAll()
        // Si se publicó una actualización con la app en segundo plano, que aparezca al volver.
        vm.refreshUpdates()
    }

    private companion object {
        const val UPDATE_POLL_MS = 5 * 60_000L
    }
}

/**
 * Cada pestaña trae un ícono en trazo (sin seleccionar) y uno relleno
 * (seleccionada) — el intercambio outlined/filled es el lenguaje que
 * Material Expressive usa en sus barras de navegación en vez de solo
 * cambiar de color. El relleno usa el set Rounded (esquinas suaves) en vez
 * del set Filled por defecto, más anguloso, para que la píldora se vea más
 * armónica con sus propias formas circulares.
 */
private sealed class Screen(@StringRes val labelRes: Int, val filledIcon: ImageVector, val outlinedIcon: ImageVector) {
    /** Etiqueta en el idioma actual (se resuelve al leerla, no al cargar la clase). */
    val label: String get() = Strings.get(labelRes)

    object Home : Screen(R.string.inicio, Icons.Rounded.Home, Icons.Outlined.Home)
    object Servers : Screen(R.string.servidores, Icons.Rounded.AccountBox, Icons.Outlined.AccountBox)
    object Logs : Screen(R.string.logs, Icons.AutoMirrored.Rounded.List, Icons.AutoMirrored.Outlined.List)
    object About : Screen(R.string.acerca_de, Icons.Rounded.Info, Icons.Outlined.Info)
}

/** Alto de la píldora (52 + 2×8 de relleno) + separación por arriba y abajo. */
private val PillSpace = 88.dp

/** Cuánto tiempo se ve la etiqueta de la pestaña activa antes de esconderse (retrato). */
private const val LabelHideDelayMs = 2000L

/** Cuánto hay que mantener presionado el botón Logs de la píldora para ofrecer ocultarlo. */
private const val LogsHideHoldMs = 3000L

/** Lo que tarda en salir el botón Logs de la píldora (encoger + desvanecer) antes de quitarlo. */
private const val LogsExitMs = 480L

/** Alto del degradado que funde el contenido con la barra del sistema (retrato). */
private val FadeHeight = 104.dp

/**
 * En apaisado la pantalla es mucho más baja: el mismo alto de degradado que
 * en retrato ocupa ahí una porción bastante mayor de la vista y tapa más
 * tarjetas de las necesarias. Se reduce solo para esa orientación, y además
 * con menos intensidad ([FadeStrengthLandscape]): ahí el difuminado solo
 * tiene que suavizar el corte del contenido contra la barra de gestos.
 */
private val FadeHeightLandscape = 20.dp

/** Multiplicador de la opacidad del difuminado inferior en apaisado (1 = igual que en retrato). */
private const val FadeStrengthLandscape = 0.6f

@Composable
private fun AppScaffold(vm: BindViewModel) {
    // Logs viene oculta por defecto: se activa en Acerca de (Mostrar Logs) y se oculta de nuevo
    // ahí mismo o manteniendo presionado su botón 3 s.
    val items = remember(vm.logsHidden) {
        if (vm.logsHidden) listOf(Screen.Home, Screen.Servers, Screen.About)
        else listOf(Screen.Home, Screen.Servers, Screen.Logs, Screen.About)
    }
    val pagerState = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()
    val hazeState = rememberHazeState()

    // No es un ancho de pantalla (eso ya lo maneja cada screen con
    // BoxWithConstraints para su propio layout de dos columnas): es la
    // orientación real del teléfono, la que gira la píldora de la barra
    // inferior a la derecha. android:configChanges en el manifiesto evita
    // que Android recree la Activity al girar, así esta lectura cambia en
    // caliente y hay una composición ya en pantalla sobre la que animar en
    // vez de aparecer ya en su sitio final sin transición.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun goTo(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    // Al mostrar Logs desde Acerca de: el botón entra animado en la píldora y, tras asentarse
    // el pager, la app viaja a esa pestaña (el indicador de la píldora se desliza hasta ella).
    var goToLogs by remember { mutableStateOf(false) }
    var logsEntering by remember { mutableStateOf(false) }
    // Al ocultar Logs: el botón sale animado de la píldora antes de quitarlo de la lista.
    var logsLeaving by remember { mutableStateOf(false) }

    // Al mostrar/ocultar Logs los índices del pager se corren: se vuelve a la misma pestaña
    // (o a Inicio si era la propia Logs).
    var pendingScreen by remember { mutableStateOf<Screen?>(null) }
    LaunchedEffect(items) {
        pendingScreen?.let { s ->
            pagerState.scrollToPage(items.indexOf(s).coerceAtLeast(0))
            pendingScreen = null
        }
        if (goToLogs) {
            goToLogs = false
            val logsPage = items.indexOf(Screen.Logs)
            if (logsPage >= 0) {
                delay(350L) // deja ver cómo entra el botón antes de viajar
                pagerState.animateScrollToPage(logsPage)
            }
        }
    }
    LaunchedEffect(logsEntering) {
        if (logsEntering) {
            delay(1500L)
            logsEntering = false
        }
    }

    // Mostrar/ocultar Logs: los índices del pager se corren, así que se recuerda la pestaña
    // actual para quedarse en ella (si no, Acerca de saltaría a Logs sin querer).
    // Ocultar (interruptor de Acerca de o mantener 3 s el botón): primero el botón Logs se
    // encoge y se desvanece en la píldora (si estás en Logs, la app viaja a Inicio a la vez) y
    // solo entonces se quita de la lista.
    fun setLogsVisible(visible: Boolean) {
        if (visible) {
            pendingScreen = items.getOrNull(pagerState.currentPage)
            goToLogs = true
            logsEntering = true
            vm.updateLogsHidden(false)
        } else {
            if (logsLeaving || !items.contains(Screen.Logs)) return
            scope.launch {
                logsLeaving = true
                val travel = if (items.getOrNull(pagerState.targetPage) == Screen.Logs) {
                    launch { pagerState.animateScrollToPage(0) }
                } else null
                delay(LogsExitMs)
                travel?.join()
                pendingScreen = items.getOrNull(pagerState.currentPage)
                vm.updateLogsHidden(true)
                logsLeaving = false
            }
        }
    }

    // Atrás desde otra pestaña vuelve a Inicio antes de cerrar la app.
    BackHandler(enabled = pagerState.currentPage != 0) { goTo(0) }

    Scaffold(
        // Avisos expressive (reemplazan al snackbar): el propio host se cierra solo.
        snackbarHost = {
            ExpressiveNoticeHost(
                notice = vm.notice,
                onDismiss = { id -> vm.dismissNotice(id) },
                modifier = Modifier.padding(bottom = if (isLandscape) 0.dp else PillSpace)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Deslizar horizontalmente cambia de pestaña. Las 4 páginas se
            // mantienen compuestas para conservar scroll y estado. El pager
            // ocupa todo el alto (es la fuente del desenfoque): en modo
            // retrato cada pantalla suma PillSpace a su relleno inferior vía
            // LocalContentBottomInset para no quedar tapada por la píldora
            // flotante; en apaisado la píldora se corre al lateral derecho
            // y ese relleno extra ya no hace falta.
            CompositionLocalProvider(
                LocalContentBottomInset provides if (isLandscape) 0.dp else PillSpace,
                LocalContentEndInset provides if (isLandscape) PillSpace else 0.dp
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                    beyondViewportPageCount = items.size
                ) { page ->
                    when (items[page]) {
                        Screen.Home -> HomeScreen(vm, onOpenServers = { goTo(1) })
                        Screen.Servers -> ServersScreen(vm)
                        Screen.Logs -> LogsScreen(vm)
                        Screen.About -> AboutScreen(vm, onLogsVisibleChange = ::setLogsVisible)
                    }
                }
            }

            // Difuminado inferior: desde la barra de gestos del sistema hacia
            // arriba el contenido se funde con el fondo. Va sobre el pager y
            // bajo la píldora; no intercepta toques. Más bajo en apaisado
            // (ver FadeHeightLandscape): la pantalla tiene mucha menos altura
            // ahí y el mismo alto que en retrato tapaba de más.
            run {
                val fade = MaterialTheme.colorScheme.background
                val strength = if (isLandscape) FadeStrengthLandscape else 1f
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(if (isLandscape) FadeHeightLandscape else FadeHeight)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.35f to fade.copy(alpha = 0.25f * strength),
                                0.7f to fade.copy(alpha = 0.7f * strength),
                                1f to fade.copy(alpha = 0.96f * strength)
                            )
                        )
                )
            }

            // Rebote elástico al mudarse de esquina en vez de un salto seco:
            // dos springs (uno por eje) hacia el sesgo de la posición
            // destino. BiasAlignment(0, 1) es abajo centrado (retrato);
            // BiasAlignment(1, 0) es centrado a la derecha (apaisado). Al
            // recomponerse con la nueva orientación cambia el objetivo y
            // ambos springs recorren la distancia con rebote (stiffness
            // baja) hasta asentarse ahí.
            val hBias by animateFloatAsState(
                if (isLandscape) 1f else 0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                label = "pillNavHBias"
            )
            val vBias by animateFloatAsState(
                if (isLandscape) 0f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                label = "pillNavVBias"
            )

            FloatingPillNav(
                items = items,
                pagerState = pagerState,
                hazeState = hazeState,
                onSelect = ::goTo,
                vertical = isLandscape,
                logsEntering = logsEntering,
                logsLeaving = logsLeaving,
                onLogsLongPress = {
                    setLogsVisible(false)
                    vm.showNotice(Strings.get(R.string.logs_ocultos_aviso), NoticeKind.Info)
                },
                modifier = Modifier
                    .align(BiasAlignment(hBias, vBias))
                    .padding(12.dp)
            )
        }
    }
}

/**
 * Barra flotante en forma de píldora con fondo desenfocado. Colores del
 * esquema dinámico: fondo primaryContainer (translúcido), indicador
 * primary, contenido onPrimary / onPrimaryContainer. En retrato es
 * horizontal y la pestaña activa muestra icono + etiqueta; en apaisado
 * ([vertical]) se apila a la derecha y va solo con iconos, sin etiquetas.
 *
 * Hay UN solo indicador (dibujado detrás de los ítems) que se desliza
 * entre pestañas con un spring. Al poner el dedo sobre la píldora el
 * indicador se "infla" y, si arrastras, sigue al dedo; la pestaña bajo el
 * dedo se selecciona sobre la marcha (con háptico y cambio de página).
 * Al soltar se desinfla y se asienta en la pestaña elegida.
 */
@Composable
private fun FloatingPillNav(
    items: List<Screen>,
    pagerState: PagerState,
    hazeState: HazeState,
    onSelect: (Int) -> Unit,
    vertical: Boolean,
    logsEntering: Boolean,
    logsLeaving: Boolean,
    onLogsLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    // Rectángulo de cada ítem en las coordenadas de la Row/Column que los
    // contiene (el mismo sistema en el que dibuja el indicador).
    // Por pantalla (no por índice): al quitar o añadir Logs los índices se corren, pero cada
    // botón conserva su medida y el indicador no parpadea a tamaño cero.
    val bounds = remember { mutableStateMapOf<Screen, Rect>() }
    val currentItems by rememberUpdatedState(items)
    val currentLogsLeaving by rememberUpdatedState(logsLeaving)
    val lastTarget = remember { arrayOf(Rect.Zero) }
    var pressed by remember { mutableStateOf(false) }
    // Posición del dedo sobre el eje de la barra (null = no hay arrastre).
    var dragPos by remember { mutableStateOf<Float?>(null) }

    // targetPage cambia en cuanto se pide el salto (currentPage espera a
    // cruzar la mitad), así la barra reacciona al instante.
    val selected = pagerState.targetPage.coerceIn(0, items.lastIndex)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentVertical by rememberUpdatedState(vertical)
    val currentLogsLongPress by rememberUpdatedState(onLogsLongPress)

    // La etiqueta de la pestaña activa se ve al cambiar de pestaña, al
    // tocar la píldora y mientras el dedo siga puesto; pasado un momento se
    // esconde y la píldora se encoge a solo iconos. Reaparece con la
    // siguiente interacción.
    var labelVisible by remember { mutableStateOf(true) }
    LaunchedEffect(selected, pressed, vertical) {
        labelVisible = true
        if (!pressed) {
            delay(LabelHideDelayMs)
            labelVisible = false
        }
    }

    // ---- Geometría objetivo del indicador --------------------------------
    val rawTarget = bounds[items[selected]] ?: Rect.Zero
    if (!rawTarget.isEmpty) lastTarget[0] = rawTarget
    // Mientras un botón se está midiendo de nuevo, el indicador se queda donde estaba.
    val target = if (rawTarget.isEmpty) lastTarget[0] else rawTarget
    val drag = dragPos
    var tx = target.left
    var ty = target.top
    if (drag != null && !target.isEmpty) {
        val real = items.mapNotNull { bounds[it] }.filter { !it.isEmpty }
        if (currentVertical) {
            val lo = real.minOf { it.top }
            val hi = real.maxOf { it.bottom }
            ty = (drag - target.height / 2f).coerceIn(lo, max(lo, hi - target.height))
        } else {
            val lo = real.minOf { it.left }
            val hi = real.maxOf { it.right }
            tx = (drag - target.width / 2f).coerceIn(lo, max(lo, hi - target.width))
        }
    }

    // La primera vez que hay medidas el indicador aparece directo en su
    // sitio (snap) en vez de viajar desde (0,0).
    var placed by remember { mutableStateOf(false) }
    SideEffect { if (!target.isEmpty) placed = true }

    // Mientras arrastras la posición sigue al dedo casi sin retraso; al
    // soltar (o al tocar) asienta con rebote. El tamaño usa otro spring más
    // rápido: la diferencia de velocidades da un pequeño efecto "elástico".
    val posSpec: FiniteAnimationSpec<Float> = when {
        !placed -> snap()
        drag != null -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
        else -> spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    }
    val sizeSpec: FiniteAnimationSpec<Float> =
        if (!placed) snap() else spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)

    val ix by animateFloatAsState(tx, posSpec, label = "pillIndX")
    val iy by animateFloatAsState(ty, posSpec, label = "pillIndY")
    val iw by animateFloatAsState(target.width, sizeSpec, label = "pillIndW")
    val ih by animateFloatAsState(target.height, sizeSpec, label = "pillIndH")

    // El "inflado": crece con el dedo puesto y vuelve con rebote al soltar.
    val inflate by animateFloatAsState(
        if (pressed) 1.18f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pillInflate"
    )

    val indicatorColor = colors.primary
    val drawIndicator = Modifier.drawBehind {
        if (iw > 0f && ih > 0f) {
            withTransform({ scale(inflate, inflate, pivot = Offset(ix + iw / 2f, iy + ih / 2f)) }) {
                drawRoundRect(
                    color = indicatorColor,
                    topLeft = Offset(ix, iy),
                    size = Size(iw, ih),
                    cornerRadius = CornerRadius(min(iw, ih) / 2f)
                )
            }
        }
    }

    fun itemModifier(screen: Screen) = Modifier.onGloballyPositioned {
        val r = Rect(it.positionInParent(), it.size.toSize())
        if (bounds[screen] != r) bounds[screen] = r
    }

    // Vidrio esmerilado: desenfoca lo que pasa por debajo y lo tiñe con
    // primaryContainer semitransparente (sigue el color dinámico). Sin
    // Android 12+ Haze cae a un tinte plano.
    Surface(
        modifier = modifier
            .clip(CircleShape)
            .hazeEffect(state = hazeState) {
                backgroundColor = colors.surface
                blurRadius = 24.dp
                noiseFactor = 0f
                tints = listOf(HazeTint(colors.primaryContainer.copy(alpha = 0.55f)))
            },
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, colors.onPrimaryContainer.copy(alpha = 0.12f))
    ) {
        // Capa de gestos sobre toda la píldora. No consume nada hasta pasar
        // el umbral de arrastre, así un toque simple sigue llegando al
        // selectable de cada ítem; al empezar a arrastrar consume y cancela
        // ese clic.
        Box(
            Modifier.pointerInput(Unit) {
                val padPx = 8.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    var dragging = false
                    // Mantener 3 s sobre el botón Logs (sin arrastrar) oculta la pestaña.
                    val t0 = System.nanoTime()
                    var fired = false
                    val onLogsItem = currentItems.contains(Screen.Logs) &&
                        bounds[Screen.Logs]?.contains(Offset(down.position.x - padPx, down.position.y - padPx)) == true
                    try {
                        while (true) {
                            val armed = onLogsItem && !dragging && !fired
                            val event = if (armed) {
                                val left = LogsHideHoldMs - (System.nanoTime() - t0) / 1_000_000L
                                withTimeoutOrNull(left.coerceAtLeast(1L)) { awaitPointerEvent() }
                            } else awaitPointerEvent()
                            if (event == null) {
                                fired = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentLogsLongPress()
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // Tras el aviso se consume el resto del gesto: soltar no abre la pestaña Logs.
                            if (fired) change.consume()
                            if (!change.pressed) break
                            if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                            }
                            if (dragging) {
                                change.consume()
                                val c = (if (currentVertical) change.position.y else change.position.x) - padPx
                                dragPos = c
                                // Ítem bajo el dedo (o el más cercano si cae en un hueco).
                                var best = currentSelected
                                var bestDist = Float.MAX_VALUE
                                currentItems.forEachIndexed { i, sc ->
                                    val r = bounds[sc] ?: Rect.Zero
                                    // El botón Logs que está saliendo no se puede elegir.
                                    if (!r.isEmpty && !(currentLogsLeaving && sc == Screen.Logs)) {
                                        val start = if (currentVertical) r.top else r.left
                                        val end = if (currentVertical) r.bottom else r.right
                                        val d = max(max(start - c, c - end), 0f)
                                        if (d < bestDist) { bestDist = d; best = i }
                                    }
                                }
                                if (best != currentSelected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    currentOnSelect(best)
                                }
                            }
                        }
                    } finally {
                        pressed = false
                        dragPos = null
                    }
                }
            }
        ) {
            if (vertical) {
                Column(
                    modifier = Modifier.padding(8.dp).then(drawIndicator),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items.forEachIndexed { index, screen ->
                        key(screen) {
                            PillEntrance(
                                animateIn = logsEntering && screen == Screen.Logs,
                                visible = !(logsLeaving && screen == Screen.Logs),
                                vertical = true,
                                modifier = itemModifier(screen)
                            ) {
                                PillItem(
                                    screen = screen,
                                    selected = selected == index,
                                    vertical = true,
                                    onClick = { onSelect(index) }
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(8.dp).then(drawIndicator),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, screen ->
                        key(screen) {
                            PillEntrance(
                                animateIn = logsEntering && screen == Screen.Logs,
                                visible = !(logsLeaving && screen == Screen.Logs),
                                vertical = false,
                                modifier = itemModifier(screen)
                            ) {
                                PillItem(
                                    screen = screen,
                                    selected = selected == index,
                                    vertical = false,
                                    showLabel = labelVisible,
                                    onClick = { onSelect(index) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Envoltorio de cada botón de la píldora. Con [animateIn] (solo el botón Logs al activarlo)
 * el botón entra con resorte: crece desde el centro, se ensancha y se desvanece hacia dentro.
 * Con [visible] en false (Logs al ocultarse) sale al revés: se desvanece, se encoge y se pliega
 * hasta desaparecer, y la píldora se cierra con resorte. Sin [animateIn] aparece ya en su sitio.
 * El [modifier] (medición de límites) va en el contenedor de la animación para que las
 * coordenadas sigan siendo las de la fila/columna.
 */
@Composable
private fun PillEntrance(
    animateIn: Boolean,
    visible: Boolean,
    vertical: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val state = remember { MutableTransitionState(!animateIn).apply { targetState = true } }
    // Se actualiza en un efecto (no durante la composición): así solo cambia al ocultar Logs.
    LaunchedEffect(visible) { state.targetState = visible }
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), initialScale = 0.3f) +
            (if (vertical) expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
            else expandHorizontally(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))),
        exit = fadeOut(tween(200)) +
            scaleOut(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), targetScale = 0.3f) +
            (if (vertical) shrinkVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            else shrinkHorizontally(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)))
    ) { content() }
}

@Composable
private fun PillItem(
    screen: Screen,
    selected: Boolean,
    vertical: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    // El fondo de la pestaña activa ya no lo pinta cada ítem: lo dibuja el
    // indicador único de FloatingPillNav, que se desliza entre pestañas.
    val content by animateColorAsState(
        if (selected) colors.onPrimary else colors.onPrimaryContainer, label = "pillContent"
    )
    // Rebote elástico al seleccionar, en vez de un simple fundido: es el
    // toque de motion que distingue a Expressive de un cambio de color liso.
    val iconScale by animateFloatAsState(
        if (selected) 1f else 0.86f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pillIconScale"
    )

    val itemModifier = modifier
        .let { if (vertical) it.size(52.dp) else it.height(52.dp).defaultMinSize(minWidth = 52.dp) }
        .clip(CircleShape)
        .selectable(
            selected = selected,
            role = Role.Tab,
            onClick = {
                // Solo vibra si de verdad cambia de pestaña; volver a
                // tocar la ya activa no dispara nada porque no pasa nada.
                // SegmentTick es el patrón corto que usa Android para
                // saltar entre segmentos/pestañas (distinto del de
                // encender/apagar un switch o mantener presionado).
                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            }
        )

    // En apaisado va solo el icono, sin etiqueta ni aunque esté
    // seleccionada: no hay ancho sobrante en una barra vertical angosta
    // para desplegar texto al lado sin desbordar la pantalla.
    if (vertical) {
        Box(itemModifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (selected) screen.filledIcon else screen.outlinedIcon,
                contentDescription = screen.label,
                tint = content,
                modifier = Modifier.size(26.dp).scale(iconScale)
            )
        }
        return
    }

    Row(
        modifier = itemModifier.padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (selected) screen.filledIcon else screen.outlinedIcon,
            contentDescription = screen.label,
            tint = content,
            // Sin tamaño explícito quedan en 24dp (el default de Icon). 28dp
            // es "un poco más grande" sin desbalancear la altura de 52dp de
            // la píldora ni el texto labelLarge de al lado.
            modifier = Modifier.size(28.dp).scale(iconScale)
        )
        AnimatedVisibility(
            visible = selected && showLabel,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally()
        ) {
            Text(
                text = screen.label,
                color = content,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp)
            )
        }
    }
}
