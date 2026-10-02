package com.nubind.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val FIRST_DELAY_MS = 1500L
private const val INTERVAL_MS = 6000L
private val CloudBlue = Color(0xFF2A8DE0)
private val Ice = Color(0xFFBCD1E6)
private val Ink = Color(0xFF134F87)
private val Gold = Color(0xFFFFCE74)
private val Mint = Color(0xFF91E4CD)

// Original bounce + ten new scenes. A shuffle bag visits all eleven before
// reshuffling, and never repeats the scene that was just shown.
internal enum class CloudScene(val durationMs: Int) {
    Bounce(1100), Wave(1400), Spin(1600), Breathe(1800), Float(2000),
    Bind(2100), S3(2000), Server(2000), Download(2100), Sync(2000), Mount(2100)
}

internal class CloudSceneDeck(private val random: Random = Random.Default) {
    private val remaining = mutableListOf<CloudScene>()
    private var previous: CloudScene? = null

    fun next(): CloudScene {
        if (remaining.isEmpty()) {
            remaining.addAll(CloudScene.entries.shuffled(random))
            if (remaining.last() == previous) {
                val last = remaining.lastIndex
                val swap = remaining[0]
                remaining[0] = remaining[last]
                remaining[last] = swap
            }
        }
        return remaining.removeAt(remaining.lastIndex).also { previous = it }
    }
}

private data class CloudPose(
    val x: Float = 0f, val y: Float = 0f,
    val sx: Float = 1f, val sy: Float = 1f, val tilt: Float = 0f
)

// Times are normalized. Interpolating poses rather than launching a coroutine
// for every frame keeps all props synchronized with a single animation clock.
private fun poseAt(t: Float, vararg frames: Pair<Float, CloudPose>): CloudPose {
    val segment = frames.indexOfFirst { it.first >= t }.coerceAtLeast(0)
    if (segment == 0) return frames[0].second
    val (aTime, a) = frames[segment - 1]
    val (bTime, b) = frames[segment]
    val f = FastOutSlowInEasing.transform(((t - aTime) / (bTime - aTime)).coerceIn(0f, 1f))
    fun mix(a: Float, b: Float) = a + (b - a) * f
    return CloudPose(mix(a.x, b.x), mix(a.y, b.y), mix(a.sx, b.sx), mix(a.sy, b.sy), mix(a.tilt, b.tilt))
}

private fun scenePose(scene: CloudScene, t: Float): CloudPose {
    val rest = CloudPose()
    val wave = sin(t * PI * 2).toFloat()
    val envelope = sin(t * PI).toFloat()
    return when (scene) {
        CloudScene.Bounce -> rest // The original spring choreography is played separately.
        CloudScene.Wave -> CloudPose(tilt = sin(t * PI * 6).toFloat() * 12f * envelope, y = -2f * envelope)
        CloudScene.Spin -> poseAt(t, 0f to rest, .16f to CloudPose(sx = .91f, sy = 1.07f),
            .82f to CloudPose(y = -4f, sx = .94f, sy = .94f, tilt = 360f), 1f to CloudPose(tilt = 360f))
        CloudScene.Breathe -> CloudPose(sx = 1f + .09f * envelope, sy = 1f + .07f * envelope, y = -2f * envelope)
        CloudScene.Float -> CloudPose(x = 6f * wave * envelope, y = -6f * envelope,
            tilt = -8f * wave * envelope)
        CloudScene.Bind -> poseAt(t, 0f to rest, .24f to CloudPose(y = -6f),
            .5f to CloudPose(x = -5f, y = -8f, tilt = -9f),
            .7f to CloudPose(x = 3f, y = -5f, tilt = 6f), 1f to rest)
        CloudScene.S3 -> poseAt(t, 0f to rest, .2f to CloudPose(x = -5f, tilt = -10f),
            .45f to CloudPose(y = -7f, sx = .96f, sy = 1.06f, tilt = 9f),
            .72f to CloudPose(x = 4f, tilt = 5f), 1f to rest)
        CloudScene.Server -> poseAt(t, 0f to rest, .25f to CloudPose(x = -4f, y = -5f, tilt = 8f),
            .5f to CloudPose(x = -4f, y = -8f, sy = .95f, tilt = -4f),
            .75f to CloudPose(x = -2f, y = -5f, tilt = 7f), 1f to rest)
        CloudScene.Download -> CloudPose(y = -7f * envelope, sx = 1f + .03f * wave * envelope,
            sy = 1f - .04f * wave * envelope)
        CloudScene.Sync -> CloudPose(y = -3f * envelope, tilt = 6f * wave * envelope)
        CloudScene.Mount -> poseAt(t, 0f to rest, .25f to CloudPose(y = -11f, sx = .97f, sy = 1.06f),
            .6f to CloudPose(y = -3f, sx = 1.07f, sy = .92f),
            .78f to CloudPose(y = -6f), 1f to rest)
    }
}

/** Self-contained decorative logo. Touch and accessibility clicks replay it. */
@Composable
fun AnimatedAboutLogo() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val deck = remember { CloudSceneDeck() }
    val progress = remember { Animatable(0f) }
    // Separate channels preserve the old bounce's physical spring settling.
    val hop = remember { Animatable(0f) }
    val squashX = remember { Animatable(1f) }
    val squashY = remember { Animatable(1f) }
    val tilt = remember { Animatable(0f) }
    var scene by remember { mutableStateOf(CloudScene.Bounce) }
    var replay by remember { mutableIntStateOf(0) }
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(replay, resumed) {
        // Cancellation on touch or on leaving the screen cannot leave a pose
        // or an object behind; the next effect always starts from a clean state.
        progress.snapTo(0f)
        hop.snapTo(0f); squashX.snapTo(1f); squashY.snapTo(1f); tilt.snapTo(0f)
        if (!resumed) return@LaunchedEffect
        delay(if (replay == 0) FIRST_DELAY_MS else 0L)
        while (isActive) {
            // Honor Android's animation switch, including changes between scenes.
            val enabled = Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) > 0f
            if (enabled) {
                scene = deck.next()
                progress.snapTo(0f)
                if (scene == CloudScene.Bounce) {
                    coroutineScope {
                        launch { squashY.animateTo(.9f, tween(110)) }
                        launch { squashX.animateTo(1.07f, tween(110)) }
                    }
                    coroutineScope {
                        // 8 dp at the original 96 dp logo size.
                        launch { hop.animateTo(-9f, tween(200, easing = FastOutSlowInEasing)) }
                        launch { squashY.animateTo(1.1f, tween(200)) }
                        launch { squashX.animateTo(.94f, tween(200)) }
                        launch { tilt.animateTo(-6f, tween(200)) }
                    }
                    coroutineScope {
                        launch { hop.animateTo(0f, spring(dampingRatio = .4f, stiffness = 450f)) }
                        launch { squashY.animateTo(1f, spring(dampingRatio = .4f, stiffness = 450f)) }
                        launch { squashX.animateTo(1f, spring(dampingRatio = .4f, stiffness = 450f)) }
                        launch { tilt.animateTo(0f, spring(dampingRatio = .5f, stiffness = 400f)) }
                    }
                } else {
                    progress.animateTo(1f, tween(scene.durationMs, easing = LinearEasing))
                }
                progress.snapTo(0f)
            }
            // Same cadence as the original: six seconds of rest after a scene.
            delay(INTERVAL_MS)
        }
    }

    Box(
        Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)).background(CloudBlue)
            .clickable(role = Role.Button, onClickLabel = "Animar la nube") { replay++ },
        contentAlignment = Alignment.Center
    ) {
        Image(
            AppIcons.Logo, contentDescription = "Logo de Nubind: nube interactiva",
            modifier = Modifier.size(96.dp).graphicsLayer {
                val pose = scenePose(scene, progress.value)
                val unit = with(density) { 96.dp.toPx() } / 108f
                translationX = pose.x * unit
                translationY = (pose.y + hop.value) * unit
                scaleX = pose.sx * squashX.value
                scaleY = pose.sy * squashY.value
                rotationZ = pose.tilt + tilt.value
                transformOrigin = TransformOrigin(.5f, .72f)
            }
        )
        // All decorations use vector primitives, clipped to the original tile.
        // No bitmaps, network requests, assets or additional dependencies.
        Canvas(Modifier.matchParentSize()) {
            val t = progress.value
            if (t > 0f && t < 1f) {
                scale(size.width / 108f, size.height / 108f, pivot = Offset.Zero) {
                    drawSceneProps(scene, t)
                }
            }
        }
    }
}

private fun DrawScope.line(a: Offset, b: Offset, color: Color, width: Float = 2f) {
    drawLine(color, a, b, strokeWidth = width, cap = StrokeCap.Round)
}

private fun DrawScope.folder(x: Float, y: Float, alpha: Float, amount: Float = 1f) {
    val gold = Gold.copy(alpha = alpha)
    drawRoundRect(gold, Offset(x + 1, y), Size(9f, 5f), CornerRadius(1.5f))
    drawRoundRect(gold, Offset(x, y + 3), Size(24f, 15f), CornerRadius(2.5f))
    drawRoundRect(Ink.copy(alpha = alpha * .22f), Offset(x + 3, y + 8), Size(18f * amount, 2f), CornerRadius(1f))
}

private fun DrawScope.bucket(x: Float, y: Float, alpha: Float) {
    val path = Path().apply {
        moveTo(x - 8, y - 7); lineTo(x + 8, y - 7)
        lineTo(x + 6, y + 8); lineTo(x - 6, y + 8); close()
    }
    drawPath(path, Gold.copy(alpha = alpha))
    drawOval(Ice.copy(alpha = alpha), Offset(x - 8, y - 10), Size(16f, 6f))
    // Three stacked objects symbolize S3 without tiny unreadable lettering.
    repeat(3) { i -> line(Offset(x - 3, y - 3 + i * 4), Offset(x + 3, y - 3 + i * 4), Ink.copy(alpha = alpha), 1.4f) }
}

private fun DrawScope.server(x: Float, y: Float, alpha: Float, activity: Float) {
    repeat(3) { i ->
        drawRoundRect(Ink.copy(alpha = alpha), Offset(x, y + i * 7), Size(17f, 6f), CornerRadius(1.5f))
        drawCircle(Mint.copy(alpha = alpha * (.5f + .5f * activity)), 1.3f, Offset(x + 4, y + i * 7 + 3))
        line(Offset(x + 8, y + i * 7 + 3), Offset(x + 13, y + i * 7 + 3), Ice.copy(alpha = alpha), 1f)
    }
}

private fun DrawScope.file(x: Float, y: Float, alpha: Float) {
    drawRoundRect(Ice.copy(alpha = alpha), Offset(x, y), Size(8f, 11f), CornerRadius(1.3f))
    line(Offset(x + 2, y + 4), Offset(x + 6, y + 4), Ink.copy(alpha = alpha), 1f)
    line(Offset(x + 2, y + 7), Offset(x + 5, y + 7), Ink.copy(alpha = alpha), 1f)
}

private fun DrawScope.check(x: Float, y: Float, alpha: Float) {
    drawCircle(Mint.copy(alpha = alpha), 6f, Offset(x, y))
    line(Offset(x - 3, y), Offset(x - 1, y + 2), Ink.copy(alpha = alpha), 1.4f)
    line(Offset(x - 1, y + 2), Offset(x + 3, y - 2), Ink.copy(alpha = alpha), 1.4f)
}

private fun DrawScope.spark(x: Float, y: Float, alpha: Float) {
    line(Offset(x - 2, y), Offset(x + 2, y), Gold.copy(alpha = alpha), 1.4f)
    line(Offset(x, y - 2), Offset(x, y + 2), Gold.copy(alpha = alpha), 1.4f)
}

private fun DrawScope.drawSceneProps(scene: CloudScene, t: Float) {
    val alpha = (minOf(t / .15f, (1f - t) / .18f)).coerceIn(0f, 1f)
    val pulse = (.5f + .5f * sin(t * PI * 10).toFloat())
    when (scene) {
        CloudScene.Bounce -> Unit
        CloudScene.Wave -> {
            spark(18f, 29f, alpha * pulse)
            spark(91f, 35f, alpha * (1f - pulse))
        }
        CloudScene.Spin -> {
            repeat(3) { i ->
                val angle = t * PI * 4 + i * PI * 2 / 3
                spark(54f + cos(angle).toFloat() * 34f, 51f + sin(angle).toFloat() * 29f, alpha)
            }
        }
        CloudScene.Breathe -> {
            drawOval(Ice.copy(alpha = alpha * .3f), Offset(25f, 83f), Size(58f, 6f), style = Stroke(1.3f))
        }
        CloudScene.Float -> {
            repeat(3) { i ->
                val x = 19f + i * 28f + sin(t * PI * 2 + i).toFloat() * 4f
                drawCircle(Ice.copy(alpha = alpha * .45f), 1.5f + i * .5f, Offset(x, 89f - i * 4f))
            }
        }
        CloudScene.Bind -> {
            folder(73f, 81f, alpha)
            val pose = scenePose(scene, t)
            val a = Offset(55f + pose.x, 75f + pose.y)
            val b = Offset(77f, 88f)
            repeat(5) { i ->
                val f = i / 4f
                val center = a + (b - a) * f
                rotate(35f + pose.tilt, center) {
                    drawRoundRect(Gold.copy(alpha = alpha), center - Offset(4f, 2.6f),
                        Size(8f, 5.2f), CornerRadius(2.6f), style = Stroke(1.6f))
                }
            }
        }
        CloudScene.S3 -> {
            val flight = ((t - .18f) / .64f).coerceIn(0f, 1f)
            val x = 20f + 67f * flight
            val y = 86f - sin(flight * PI).toFloat() * 67f
            rotate(-15f + flight * 30f, Offset(x, y)) { bucket(x, y, alpha) }
            if (t > .76f) check(91f, 71f, alpha)
        }
        CloudScene.Server -> {
            server(79f, 78f, alpha, pulse)
            val a = Offset(58f, 75f)
            val b = Offset(83f, 82f)
            line(a, b, Ice.copy(alpha = alpha * .5f), 1f)
            repeat(3) { i ->
                val f = (t * 3f + i / 3f) % 1f
                drawCircle(Mint.copy(alpha = alpha), 2f, a + (b - a) * f)
            }
            if (t > .72f) check(74f, 91f, alpha)
        }
        CloudScene.Download -> {
            folder(42f, 85f, alpha)
            repeat(3) { i ->
                val f = ((t - .12f - i * .16f) / .35f).coerceIn(0f, 1f)
                if (f > 0f && f < 1f) file(47f + i * 4f, 65f + f * 22f, alpha * sin(f * PI).toFloat())
            }
            line(Offset(76f, 72f), Offset(76f, 84f), Mint.copy(alpha = alpha))
            line(Offset(72f, 80f), Offset(76f, 84f), Mint.copy(alpha = alpha))
            line(Offset(80f, 80f), Offset(76f, 84f), Mint.copy(alpha = alpha))
            if (t > .75f) check(70f, 89f, alpha)
        }
        CloudScene.Sync -> {
            val center = Offset(54f, 54f)
            rotate(t * 360f, center) {
                repeat(2) { i ->
                    val start = 30f + i * 180f
                    drawArc(Mint.copy(alpha = alpha), start, 95f, false,
                        Offset(18f, 20f), Size(72f, 68f), style = Stroke(2f, cap = StrokeCap.Round))
                    val angle = (start + 95f) * PI / 180
                    val tip = Offset(54f + 36f * cos(angle).toFloat(), 54f + 34f * sin(angle).toFloat())
                    val tangent = Offset(-sin(angle).toFloat(), cos(angle).toFloat())
                    val radial = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    line(tip, tip - tangent * 5f + radial * 3f, Mint.copy(alpha = alpha))
                    line(tip, tip - tangent * 5f - radial * 3f, Mint.copy(alpha = alpha))
                }
            }
        }
        CloudScene.Mount -> {
            val slide = FastOutSlowInEasing.transform((t / .35f).coerceIn(0f, 1f))
            folder(42f, 107f - 23f * slide, alpha, slide)
            line(Offset(54f, 74f), Offset(54f, 85f), Mint.copy(alpha = alpha), 2.5f)
            drawCircle(Mint.copy(alpha = alpha), 2.5f, Offset(54f, 76f))
            if (t > .58f) check(71f, 83f, alpha)
        }
    }
}
