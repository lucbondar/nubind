package com.nubind.app

import android.content.Context
import androidx.annotation.StringRes

/**
 * Acceso a los textos de los strings.xml de res/values y sus variantes desde cualquier parte, sin
 * tener que pasar un Context hasta funciones puras (validadores, mensajes del
 * ViewModel, parsers). Se inicializa en [NubindApp.onCreate].
 *
 * Idiomas: inglés (por defecto, values/), español (values-es/) y portugués de
 * Brasil (values-pt-rBR/). Android elige el del sistema; no hay selector.
 *
 * Los textos se resuelven en el momento de la llamada, así que no hay que
 * guardarlos en propiedades que se inicialicen una sola vez (enums, objects):
 * ahí se guarda el @StringRes y se llama a get() al leerlos.
 */
object Strings {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(@StringRes id: Int, vararg args: Any?): String {
        val c = checkNotNull(appContext) { "Strings.init() no se llamó en Application.onCreate()" }
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }
}
