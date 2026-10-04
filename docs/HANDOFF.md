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
| `assets/self_update.sh` | Instalador desacoplado de la app; reabre la app al terminar. Va en la app, no en el módulo, porque el módulo instalado puede ser más viejo |
| `BindViewModel.kt` | `appUpdate`, `moduleNotice`, `checkForUpdates()`, `installUpdate()`, `checkModuleSync()` (se llama desde `setRootGranted`), `dismissModuleNotice()` |
| `ui/components/UpdateBanner.kt` | `UpdateNotices()`, dentro de `HeaderCard` en `AboutScreen` |
| `AndroidManifest.xml` | `<queries>` con los paquetes de KernelSU, para poder abrirlos |
| `res/values*/strings.xml` | Textos `upd_*`; todo string nuevo va en `values`, `values-es` y `values-pt-rBR` |

## Comportamiento que no se debe romper
1. Al abrir la app busca actualización en silencio; sin red no muestra nada.
2. Con update disponible aparece "Actualizar ahora": con root instala sola, sin root abre `apkUrl` en el navegador.
3. Si el módulo KSU instalado trae un APK más viejo que la app, aparece un aviso: puede funcionar, pero para completar la actualización hay que descargar el módulo desde la app KSU. Botones "Abrir KernelSU" y "Entendido" (se descarta hasta que cambie la versión de la app o del módulo).
4. Los módulos antiguos sin `appVersionCode` se comparan por número de versión (no detectan desfase entre builds de la misma versión).

## Estado
El código del actualizador se escribió sin poder compilarlo localmente. Si el CI da errores de compilación en estos archivos, corrígelos primero.
