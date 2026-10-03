package com.nubind.app

import android.content.res.Configuration
import android.os.Bundle
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
import androidx.compose.animation.expandHorizontally
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            NubindTheme {
                AppScaffold(vm)
            }
        }

        // Pedimos root en segundo plano. Si se niega o no hay root
        // disponible, la app sigue abierta y solo lo mostramos en la UI
        // en vez de lanzar una excepción no controlada que la cierra.
        Shell.getShell { shell ->
            vm.setRootGranted(shell.isRoot)
        }
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
    val items = listOf(Screen.Home, Screen.Servers, Screen.Logs, Screen.About)
    val pagerState = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
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

    // Atrás desde otra pestaña vuelve a Inicio antes de cerrar la app.
    BackHandler(enabled = pagerState.currentPage != 0) { goTo(0) }

    LaunchedEffect(vm.message) {
        // Se consume DESPUÉS de mostrarlo: cambiar vm.message reinicia este
        // efecto y cancelaría el snackbar antes de que se vea.
        vm.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.padding(bottom = if (isLandscape) 0.dp else PillSpace))
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
                        Screen.About -> AboutScreen(vm)
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
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    // Rectángulo de cada ítem en las coordenadas de la Row/Column que los
    // contiene (el mismo sistema en el que dibuja el indicador).
    val bounds = remember(items.size) {
        mutableStateListOf<Rect>().apply { repeat(items.size) { add(Rect.Zero) } }
    }
    var pressed by remember { mutableStateOf(false) }
    // Posición del dedo sobre el eje de la barra (null = no hay arrastre).
    var dragPos by remember { mutableStateOf<Float?>(null) }

    // targetPage cambia en cuanto se pide el salto (currentPage espera a
    // cruzar la mitad), así la barra reacciona al instante.
    val selected = pagerState.targetPage.coerceIn(0, items.lastIndex)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentVertical by rememberUpdatedState(vertical)

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
    val target = bounds[selected]
    val drag = dragPos
    var tx = target.left
    var ty = target.top
    if (drag != null && !target.isEmpty) {
        val real = bounds.filter { !it.isEmpty }
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

    fun itemModifier(index: Int) = Modifier.onGloballyPositioned {
        val r = Rect(it.positionInParent(), it.size.toSize())
        if (bounds[index] != r) bounds[index] = r
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
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
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
                                bounds.forEachIndexed { i, r ->
                                    if (!r.isEmpty) {
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
                        PillItem(
                            screen = screen,
                            selected = selected == index,
                            vertical = true,
                            onClick = { onSelect(index) },
                            modifier = itemModifier(index)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(8.dp).then(drawIndicator),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, screen ->
                        PillItem(
                            screen = screen,
                            selected = selected == index,
                            vertical = false,
                            showLabel = labelVisible,
                            onClick = { onSelect(index) },
                            modifier = itemModifier(index)
                        )
                    }
                }
            }
        }
    }
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
