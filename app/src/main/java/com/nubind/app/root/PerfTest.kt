package com.nubind.app.root

import com.nubind.app.R
import com.nubind.app.Strings

/** Estado de un paso de la prueba de rendimiento. */
enum class PerfStepState { PENDING, RUNNING, OK, WARN, FAIL, SKIP }

data class PerfStep(
    val id: String,
    val title: String,
    val state: PerfStepState,
    val detail: String = ""
)

data class PerfTestProgress(
    val steps: List<PerfStep>,
    /** Resultado global (OK, WARN o FAIL); null mientras la prueba sigue corriendo. */
    val verdict: PerfStepState? = null,
    val summary: String = ""
)

/** Lo que muestra la hoja de la prueba. */
data class PerfTestState(
    val started: Boolean = false,
    val progress: PerfTestProgress = PerfTestParser.parse(""),
    val error: String? = null
) {
    val running: Boolean get() = started && error == null && progress.verdict == null
}

/**
 * Lee perf_test.out, que escribe scripts/perf_test.sh una línea por evento:
 *   STEP|<id>|<RUN|OK|WARN|FAIL|SKIP>|<texto>
 *   DONE|<OK|WARN|FAIL>|<texto>|
 * Los pasos que el script todavía no alcanzó quedan en PENDING.
 */
object PerfTestParser {
    /** Orden y nombre visible de cada paso; los ids son los que emite el script. */
    private val STEPS get() = listOf(
        "mount" to Strings.get(R.string.montaje_activo),
        "opts" to Strings.get(R.string.configuracion_aplicada),
        "space" to Strings.get(R.string.espacio_para_la_cache),
        "list" to Strings.get(R.string.listado_de_carpetas),
        "write" to Strings.get(R.string.escritura),
        "read" to Strings.get(R.string.lectura_y_cache)
    )

    fun parse(output: String): PerfTestProgress {
        val seen = LinkedHashMap<String, Pair<PerfStepState, String>>()
        var verdict: PerfStepState? = null
        var summary = ""

        for (raw in output.lineSequence()) {
            val parts = raw.trim().split('|', limit = 4)
            when (parts.firstOrNull()) {
                "STEP" -> if (parts.size >= 4) {
                    val state = when (parts[2]) {
                        "RUN" -> PerfStepState.RUNNING
                        "OK" -> PerfStepState.OK
                        "WARN" -> PerfStepState.WARN
                        "FAIL" -> PerfStepState.FAIL
                        "SKIP" -> PerfStepState.SKIP
                        else -> null
                    }
                    if (state != null) seen[parts[1]] = state to parts[3]
                }
                "DONE" -> if (parts.size >= 3) {
                    verdict = when (parts[1]) {
                        "OK" -> PerfStepState.OK
                        "WARN" -> PerfStepState.WARN
                        else -> PerfStepState.FAIL
                    }
                    summary = parts[2]
                }
            }
        }

        val steps = STEPS.map { (id, title) ->
            val found = seen[id]
            PerfStep(id, title, found?.first ?: PerfStepState.PENDING, found?.second.orEmpty())
        }
        // Al terminar, los pasos que no llegaron a correr (p. ej. porque no había
        // nada montado) se ocultan en vez de quedar como pendientes para siempre.
        val visible = if (verdict != null) steps.filter { it.state != PerfStepState.PENDING } else steps
        return PerfTestProgress(visible, verdict, summary)
    }
}
