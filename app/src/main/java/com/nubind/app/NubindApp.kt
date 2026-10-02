package com.nubind.app

import android.app.Application
import com.topjohnwu.superuser.Shell

/**
 * libsu exige que Shell.setDefaultBuilder() se llame antes de que se cree el
 * shell principal (la primera vez que se usa Shell.getShell()/Shell.cmd() en
 * todo el proceso); si se llama después, lanza IllegalStateException.
 *
 * Antes esta configuración vivía en el init{} del object RootShell, pero ese
 * object solo se inicializa la primera vez que algo lo referencia — y eso
 * pasaba en HomeScreen (LaunchedEffect -> vm.refreshStatus()), es decir
 * DESPUÉS de que MainActivity.onCreate() ya hubiera llamado a
 * Shell.getShell() para pedir root. Resultado: el shell principal ya existía
 * cuando RootShell intentaba fijar el builder, y la app crasheaba en cada
 * arranque en cuanto se montaba HomeScreen.
 *
 * El companion object con bloque init se ejecuta al cargar la clase, que
 * Android garantiza que ocurre antes que Application.onCreate() y, por
 * tanto, antes que cualquier Activity — es el punto más temprano posible.
 */
class NubindApp : Application() {
    companion object {
        init {
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(
                Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR)
            )
        }
    }
}
