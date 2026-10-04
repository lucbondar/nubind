# Nubind: notas para retomar el proyecto

Documento para quien (persona o IA) retome el desarrollo sin el historial anterior. Describe el sistema de actualizaciones, que es la parte más delicada.

## Qué es
App Android (Kotlin + Jetpack Compose, Material 3 Expressive) más un módulo KernelSU que monta FTP, Google Drive y S3 con rclone como una carpeta del almacenamiento interno.

## Sistema de actualizaciones

**Canal:** `update.json`, publicado por el CI en el release fijo `updates`:
`https://github.com/lucbondar/nubind/releases/download/updates/update.json`
También lo lee KSU mediante `updateJson` en `module/module.prop`.

**Campos de update.json:**
- Módulo: `version`, `versionCode`, `zipUrl`, `changelog`
- App: `appVersion`, `appVersionCode`, `apkUrl`, `apkSha256`
- Módulo (para el flasheo desde la app): `zipUrl` (ya existía) y `zipSha256` (nuevo; el CI arma el zip antes de escribir update.json)

**Versiones (CI, `.github/workflows/build.yml`):**
- `build-app` fija `APP_VERSION_CODE = run_number + 100`.
- `package-module` usa la misma fórmula y estampa en `module.prop` las claves `appVersion` y `appVersionCode` (el APK que trae el módulo).
- Las dos fórmulas deben coincidir: si cambias una, cambia la otra.
- `app/build.gradle.kts` lee `versionCode` de `APP_VERSION_CODE` (local: 51). `versionName` se sube a mano.

**Firma:** el APK se firma con `debug.keystore` del repo. Si esa clave cambia, Android rechaza las actualizaciones sobre la versión instalada.

## Código

| Archivo | Rol |
|---|---|
| `root/AppUpdater.kt` | Consulta update.json, descarga el APK y verifica SHA-256, instala con root, lee el `module.prop` instalado y decide el desfase (`isModuleBehind`) |
| `assets/flash_module.sh` | Igual que `self_update.sh` pero para el módulo: `ksud module install <zip>` (o `magisk --install-module`), desacoplado porque `customize.sh` reinstala la app y mata el proceso; reabre la app al terminar |
| `assets/self_update.sh` | Instalador desacoplado de la app; reabre la app al terminar. Va en la app, no en el módulo, porque el módulo instalado puede ser más viejo |
| `BindViewModel.kt` | `appUpdate`, `moduleNotice`, `checkForUpdates()`, `refreshUpdates()`, `installUpdate()`, `checkModuleSync()` (se llama desde `setRootGranted`), `dismissModuleNotice()` |
| `ui/components/UpdateBanner.kt` | `UpdateNotices()`, dentro de `HeaderCard` en `AboutScreen`. También `updateHeaderColors()` (devuelve `HeaderColors`): la tarjeta de cabecera es un degradado diagonal Monet (`primaryContainer` -> `tertiaryContainer`, texto `onPrimaryContainer`); con update disponible/descargando/instalando o reinicio pendiente pasa a un degradado verde de la paleta fija `UpdateGreen`; es solo visual, no toca el flujo. Los colores de estado (verde = actualización/listo, ámbar = desfase) viven en `ui/theme/StatusColors.kt` |
| `AndroidManifest.xml` | `<queries>` con los paquetes de KernelSU, para poder abrirlos |
| `res/values*/strings.xml` | Textos `upd_*`; todo string nuevo va en `values`, `values-es` y `values-pt-rBR` |

## Comportamiento que no se debe romper
1. Al abrir la app busca actualización en silencio; sin red no muestra nada.
2. Con update disponible aparece "Actualizar ahora": con root instala sola, sin root abre `apkUrl` en el navegador.
3. Si el módulo KSU instalado trae un APK más viejo que la app, aparece un aviso: puede funcionar, pero para completar la actualización hay que descargar el módulo desde la app KSU. Botones "Descargar y flashear módulo" (`BindViewModel.flashModule()`: baja `zipUrl`, verifica `zipSha256`, flashea con root; no flashea si el módulo publicado trae una app más vieja que la instalada) y, debajo, el secundario tonal "Lo haré luego" (`dismissModuleNotice()`: se descarta hasta que cambie la versión de la app o del módulo). Ya no hay "Abrir KernelSU" ni "Entendido". Ya no existe la tarjeta "Estás al día" ni el botón de buscar de nuevo: si no hay actualización no se muestra nada (`AppUpdateState.UpToDate` se queda como estado interno). Ya no hay aviso emergente de desfase al abrir la app.
3b. Tras flashear, el módulo queda en `/data/adb/modules_update/nubind` hasta reiniciar: el aviso de desfase se reemplaza por una tarjeta verde "Módulo instalado" con botón "Reiniciar ahora" (`modulePendingReboot`) y, debajo, "Lo haré luego" (`postponeReboot()`): oculta la tarjeta y la cabecera verde y lo guarda en prefs (`KEY_REBOOT_POSTPONED`), así que persiste al salir de la app; la tarjeta no vuelve hasta tocar el chip "Reinicio pendiente" o flashear otro módulo. `checkModuleSync()` restaura el estado al abrir y lo borra cuando el reinicio ya no está pendiente. La UI usa `showRebootCard` (= pendiente y no pospuesto), no `modulePendingReboot` a secas. Mientras está pospuesto (`rebootReminder`) aparece una píldora fija "Reinicio pendiente" (verde) arriba a la derecha de la cabecera (`UpdateReminderChip`); al tocarla vuelve la tarjeta (`showRebootCardAgain()`). Igual con el desfase: si se pospone, `moduleNotice` queda en null pero `moduleBehind` sigue y la píldora ámbar "Módulo desfasado" (`moduleReminder`) vuelve a abrir el aviso (`showModuleNoticeAgain()`). Nunca hay dos píldoras a la vez.
3c. La búsqueda del arranque (`checkForUpdates`) corre una vez; después `refreshUpdates()` re-consulta en silencio (sin "Buscando…" ni errores) al volver a la app (`onResume`, máx. cada 30 s) y de inmediato al pulsar "Lo haré luego" (desfase o reinicio). Además `MainActivity` la repite cada 5 min mientras la app está a la vista (Handler en `onResume`/`onPause`). La búsqueda no depende del estado del módulo (desfasado, aviso pospuesto o reinicio pendiente) y se recupera de un fallo de red. No pisa descargas/instalaciones en curso.
4. Los módulos antiguos sin `appVersionCode` se comparan por número de versión (no detectan desfase entre builds de la misma versión).

## Pantalla Acerca de
`ui/screens/AboutScreen.kt`. La cabecera (logo animado + nombre + versión en píldora) no se anima ni se envuelve en nada: el icono no se toca. El resto de tarjetas entra escalonada con `Entrance()` (solo alfa y desplazamiento, sin cambiar de tamaño). "Qué hace" dibuja los pasos con insignias numeradas a partir del string `s_1_agrega_un_servidor_en_la` (formato `1. ...\n2. ...`: si cambias ese formato, `StepRow` quita la numeración con una regex). "Sistema" muestra los valores en píldoras, con punto de estado en root. `SectionCard` (StyleKit) es compartido con otras pantallas: no se modificó.

## Avisos (notificaciones expressive)
Ya no hay `Toast` ni `Snackbar` en la app. Todo aviso de una sola vez pasa por `NoticeKind` (`Success`/`Info`/`Warning`/`Error`, cada uno con su duración):
- **Dentro de la app:** `BindViewModel.showNotice(texto, tipo)` (también se llama desde la UI, p. ej. `UpdateBanner`) deja un `AppNotice` en `vm.notice`; `ExpressiveNoticeHost` (`ui/components/ExpressiveNotice.kt`, enganchado en el `snackbarHost` del `Scaffold` de `MainActivity`) lo dibuja: tarjeta flotante con insignia de forma Material Expressive que entra con resorte, se cierra sola, al tocarla o al deslizarla. Uno nuevo reemplaza al anterior; `dismissNotice(id)` ignora ids viejos. Colores: verde/ámbar de `StatusColors.kt`, rojo y azul del tema.
- **Fuera de la app (tile de ajustes rápidos):** `SystemNotice.show()` publica una notificación emergente breve (canal `notices`, se retira sola, al tocarla abre la app). Si las notificaciones están desactivadas cae a un `Toast`, para no perder el aviso.
- Al añadir un aviso nuevo elige el tipo con criterio: `Warning` = el usuario debe hacer/corregir algo, `Error` = falló algo.

## Notificación de precarga (expressive)
`PreloadService.progressNotification()`: en Android 16+ (API 36) usa `Notification.ProgressStyle` (`expressiveNotification()`): barra de 4 tramos, la nube como icono que viaja por la barra, azul (ámbar en pausa), y se pide como actualización en vivo (extra `android.requestPromotedOngoing` puesto a mano, porque `setRequestPromotedOngoing` no compila con el SDK 36 + `setShortCriticalText("NN%")`; permiso `POST_PROMOTED_NOTIFICATIONS` en el manifest). Con Notification.Builder de la plataforma, no NotificationCompat: lo protege `@RequiresApi(36)`. En versiones anteriores sigue la notificación estándar con barra lineal. Al terminar la precarga la notificación desaparece sola: ya no se deja aviso de "Listo" (`finish()` solo quita la de progreso y cancela `DONE_ID` por si quedó una de versiones viejas). Android no permite Compose ni formas Expressive en notificaciones; esto es lo máximo que ofrece el sistema.

## Precarga: pausa
`preload.sh` mira el archivo `preload.paused` (en el directorio del módulo) antes de cada archivo: el que se está leyendo termina y los workers esperan. La app lo crea/borra con `RootShell.preloadPause()/preloadResume()`; `preloadStatus()` añade `"paused":true` al JSON si el archivo existe (`PreloadStatus.paused`). Botón en la tarjeta de Inicio (`BindViewModel.setPreloadPaused`) y acción en la notificación (`PreloadService`, acciones `PRELOAD_PAUSE/RESUME`). El script y `unmount.sh` limpian la bandera; los topes de seguridad (app y servicio) no corren mientras está en pausa.

## Reglas de trabajo
- **Cada cambio sube la sub versión** (2.5.4 -> 2.5.5), sin esperar a que lo pidan. Va en tres sitios que deben coincidir: `versionName` en `app/build.gradle.kts`, y `version=v...` y `appVersion=...` en `module/module.prop`. El `versionCode` no se toca: lo fija el CI.
- **Lo último de cada respuesta son los comandos para Termux**, apilados en un bloque, uno por línea, sin `cd`. Siempre estos cinco y en este orden: primero el `unzip` del zip entregado (se descarga del chat a `/storage/emulated/0/Download/`) hacia `/storage/emulated/0/Download/nubind/`, luego git, y `ciwatch` al final:
  ```bash
  unzip -o /storage/emulated/0/Download/<zip entregado>.zip -d /storage/emulated/0/Download/nubind/
  git add -A
  git commit -m "<versión>: <resumen>"
  git push origin preview
  ciwatch
  ```
  La rama de trabajo es `preview`.

## Estado
El código del actualizador se escribió sin poder compilarlo localmente. Si el CI da errores de compilación en estos archivos, corrígelos primero.
