plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nubind.app"
    compileSdk = 36

    // Gradle usa por defecto "~/.android/debug.keystore", que se
    // autogenera con una clave AL AZAR la primera vez que se necesita en
    // cada máquina. En GitHub Actions eso significa una clave nueva en
    // CADA build, así que cada APK queda firmado distinto y Android
    // rechaza instalar la actualización sobre la anterior a menos que se
    // desinstale primero ("no me deja instalar sobre la anterior sin
    // desinstalar" = INSTALL_FAILED_UPDATE_INCOMPATIBLE / conflicto de
    // firma). Usando este keystore versionado en el repo, todos los
    // builds (locales o en CI) firman siempre con la misma clave.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.nubind.app"
        minSdk = 26
        targetSdk = 34
        // El CI pasa APP_VERSION_CODE (run_number + 100) para que cada build suba el
        // versionCode y el actualizador de la app la detecte como nueva. Local: 51.
        versionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: 51
        versionName = "2.5.96"

        // Cliente OAuth de Google Drive que trae la app: el usuario solo da su
        // consentimiento, sin pegar credenciales. Se inyecta en el build desde
        // variables de entorno (secrets de GitHub Actions) o desde gradle.properties
        // local (gdriveClientId / gdriveClientSecret); no se versiona en el repo.
        // Vacío = se usa el cliente compartido de rclone (con cuota limitada).
        fun oauthValue(env: String, prop: String): String =
            (System.getenv(env)?.takeIf { it.isNotBlank() } ?: (project.findProperty(prop) as String?) ?: "")
                .trim()
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '~' }
        buildConfigField("String", "GDRIVE_CLIENT_ID", "\"${oauthValue("GDRIVE_CLIENT_ID", "gdriveClientId")}\"")
        buildConfigField("String", "GDRIVE_CLIENT_SECRET", "\"${oauthValue("GDRIVE_CLIENT_SECRET", "gdriveClientSecret")}\"")
    }

    // La app se distribuye SIEMPRE como build "release" (R8 + recursos reducidos, no debuggable),
    // firmada con la misma debug.keystore para que las actualizaciones sigan instalándose sobre
    // la versión anterior. Un APK debug corre Compose sin optimizar (sin R8, sin perfil de
    // arranque, con comprobaciones de depuración): arranque pesado y animaciones/scroll
    // trabados hasta que el JIT calienta, y vuelve a pasar en cada apertura en frío.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // El CI no necesita el lint de "release" (puede abortar el build por avisos fatales ajenos
    // al código); lo que importa aquí es que compile y se optimice.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // Material 3 Expressive vive en la línea 1.5.0-alpha (la 1.4.0 estable no lo trae).
    // alpha18 es la que sigue alineada con Compose 1.11.x (BOM 2026.04.01), que
    // compila con compileSdk 36 y AGP 8.x. Desde alpha23 material3 arrastra
    // Compose 1.12 alpha, que exige compileSdk 37 y AGP 9.1.
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.material:material-icons-core")
    // Variantes "outlined" de los íconos de la píldora inferior (estilo
    // Material Expressive: trazo sin seleccionar, relleno al seleccionar).
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    // Desenfoque del fondo (backdrop blur) de la barra inferior tipo píldora
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    // Instala el perfil de arranque que traen Compose y sus librerías (baseline profile) para
    // que ART compile ya lo caliente al instalar la app, en vez de interpretar/JIT en cada inicio.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // libsu: ejecutar comandos root de forma segura
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("com.github.topjohnwu.libsu:io:5.2.2")
}
