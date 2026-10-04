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
| `BindViewModel.kt` | `appUpdate`, `moduleNotice`, `checkForUpdates()`, `installUpdate()`, `checkModuleSync()` (se llama desde `setRootGranted`), `dismissModuleNotice()` |
| `ui/components/UpdateBanner.kt` | `UpdateNotices()`, dentro de `HeaderCard` en `AboutScreen`. También `updateHeaderColors()`: con update disponible/descargando/instalando la tarjeta de cabecera entera pasa a verde (paleta fija `UpdateGreen`); es solo visual, no toca el flujo. Los colores de estado (verde = actualización/listo, ámbar = desfase) viven en `ui/theme/StatusColors.kt` |
| `AndroidManifest.xml` | `<queries>` con los paquetes de KernelSU, para poder abrirlos |
| `res/values*/strings.xml` | Textos `upd_*`; todo string nuevo va en `values`, `values-es` y `values-pt-rBR` |

## Comportamiento que no se debe romper
1. Al abrir la app busca actualización en silencio; sin red no muestra nada.
2. Con update disponible aparece "Actualizar ahora": con root instala sola, sin root abre `apkUrl` en el navegador.
3. Si el módulo KSU instalado trae un APK más viejo que la app, aparece un aviso: puede funcionar, pero para completar la actualización hay que descargar el módulo desde la app KSU. Botones "Descargar y flashear módulo" (`BindViewModel.flashModule()`: baja `zipUrl`, verifica `zipSha256`, flashea con root; no flashea si el módulo publicado trae una app más vieja que la instalada), "Abrir KernelSU" y "Entendido" (se descarta hasta que cambie la versión de la app o del módulo). Ya no hay snackbar/toast de desfase al abrir la app.
3b. Tras flashear, el módulo queda en `/data/adb/modules_update/nubind` hasta reiniciar: el aviso de desfase se reemplaza por una tarjeta verde "Módulo instalado" con botón "Reiniciar ahora" (`modulePendingReboot`).
4. Los módulos antiguos sin `appVersionCode` se comparan por número de versión (no detectan desfase entre builds de la misma versión).

## Precarga: pausa
`preload.sh` mira el archivo `preload.paused` (en el directorio del módulo) antes de cada archivo: el que se está leyendo termina y los workers esperan. La app lo crea/borra con `RootShell.preloadPause()/preloadResume()`; `preloadStatus()` añade `"paused":true` al JSON si el archivo existe (`PreloadStatus.paused`). Botón en la tarjeta de Inicio (`BindViewModel.setPreloadPaused`) y acción en la notificación (`PreloadService`, acciones `PRELOAD_PAUSE/RESUME`). El script y `unmount.sh` limpian la bandera; los topes de seguridad (app y servicio) no corren mientras está en pausa.

## Reglas de trabajo
- **Cada cambio sube la sub versión** (2.5.4 -> 2.5.5), sin esperar a que lo pidan. Va en tres sitios que deben coincidir: `versionName` en `app/build.gradle.kts`, y `version=v...` y `appVersion=...` en `module/module.prop`. El `versionCode` no se toca: lo fija el CI.
- **Lo último de cada respuesta es el comando completo para Termux**: `unzip` del zip entregado, `git add -A`, `git commit -m "<versión>: <resumen>"` y `git push origin preview`, en un solo bloque. La rama de trabajo es `preview`.

## Estado
El código del actualizador se escribió sin poder compilarlo localmente. Si el CI da errores de compilación en estos archivos, corrígelos primero.
