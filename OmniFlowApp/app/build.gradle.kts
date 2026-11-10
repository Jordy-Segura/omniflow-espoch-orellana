import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Credenciales del servicio OASIS y URL del backend: se leen de local.properties (no se suben a git).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String = ""): String =
    (localProps.getProperty(key) ?: default).replace("\\", "\\\\").replace("\"", "\\\"")
fun rawProp(key: String): String = localProps.getProperty(key)?.trim().orEmpty()

// Version: las compilaciones publicadas (-Pomniflow.release) usan los minutos desde 1970 como versionCode,
// asi cada publicacion es mayor que la anterior sin editar nada a mano. "Run" desde Android Studio = 1.
val isPublishBuild = providers.gradleProperty("omniflow.release").isPresent
val buildStamp = Date()
val autoVersionCode = (buildStamp.time / 60_000L).toInt()
val appVersionCode = if (isPublishBuild) autoVersionCode else 1
val appVersionName = if (isPublishBuild) "0.2." + SimpleDateFormat("yyMMdd.HHmm").format(buildStamp) else "0.2-dev"
val keystoreFile = rootProject.file("keystore/omniflow.jks")
val keystorePassword = rawProp("OMNIFLOW_KEYSTORE_PASSWORD")

android {
    namespace = "ec.edu.espoch.omniflow"
    compileSdk = 37

    defaultConfig {
        applicationId = "ec.edu.espoch.omniflow"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "OASIS_BASE_URL", "\"${localProp("OASIS_BASE_URL", "http://swoasis.espoch.edu.ec/OASis/OAS_Interop/")}\"")
        buildConfigField("String", "OASIS_WS_USER", "\"${localProp("OASIS_WS_USER")}\"")
        buildConfigField("String", "OASIS_WS_PASSWORD", "\"${localProp("OASIS_WS_PASSWORD")}\"")
        buildConfigField("String", "OMNIFLOW_API_URL", "\"${localProp("OMNIFLOW_API_URL")}\"")

        if (isPublishBuild) {
            // Actualizaciones mas livianas: telefonos (ARM) + emulador x86_64; se omite x86 de 32 bits.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
    }

    signingConfigs {
        // Misma firma en todas las compilaciones: requisito para que las actualizaciones se instalen encima.
        if (keystoreFile.exists() && keystorePassword.isNotEmpty()) {
            create("omniflow") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "omniflow"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("omniflow")?.let { signingConfig = it }
        }
        release {
            signingConfigs.findByName("omniflow")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Las versiones publicadas comprimen las librerias nativas (MediaPipe) para que la descarga pese menos.
        jniLibs { useLegacyPackaging = isPublishBuild }
    }
    androidResources {
        // El modelo .tflite debe quedar sin comprimir para poder mapearse en memoria.
        noCompress += "tflite"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Persistencia local
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // Inyeccion de dependencias
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Camara del telefono (fuente temporal hasta tener camaras IP)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    // Machine Learning on-device: deteccion de personas (EfficientDet-Lite0, CNN cuantizable)
    implementation(libs.mediapipe.tasks.vision)

    // Red: API OmniFlow (REST) y OASIS (SOAP)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------------------------
// Publicar una actualizacion en el backend:   ./gradlew publishApk -Pomniflow.release -Pomniflow.notes="Cambios"
// (o ejecuta publicar-actualizacion.bat). Los telefonos la descargan e instalan solos.
// ---------------------------------------------------------------------------------------------
val publishUrl = rawProp("OMNIFLOW_PUBLISH_URL").ifEmpty { rawProp("OMNIFLOW_API_URL") }.trimEnd('/')
val releaseKey = rawProp("RELEASE_UPLOAD_KEY")
val releaseNotes = providers.gradleProperty("omniflow.notes").orElse("").get()
val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")

tasks.register("publishApk") {
    group = "omniflow"
    description = "Compila la app y la publica en el backend para la actualizacion automatica."
    dependsOn("assembleDebug")
    doLast {
        check(isPublishBuild) { "Ejecuta con -Pomniflow.release para generar un versionCode nuevo" }
        check(publishUrl.isNotEmpty()) { "Configura OMNIFLOW_PUBLISH_URL en local.properties" }
        check(releaseKey.isNotEmpty()) { "Configura RELEASE_UPLOAD_KEY en local.properties (igual que en el servidor)" }
        val apk = apkFile.get().asFile
        val enc = { v: String -> URLEncoder.encode(v, Charsets.UTF_8) }
        val uri = URI.create("$publishUrl/api/v1/app/releases?versionCode=$appVersionCode" +
            "&versionName=${enc(appVersionName)}&notes=${enc(releaseNotes)}")
        val request = HttpRequest.newBuilder(uri)
            .header("X-Release-Key", releaseKey)
            .header("Content-Type", "application/vnd.android.package-archive")
            .PUT(HttpRequest.BodyPublishers.ofFile(apk.toPath()))
            .build()
        // HTTP/1.1: uvicorn no acepta el "upgrade" a HTTP/2 en texto plano y el cuerpo se perderia.
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "El backend rechazó la publicación (${response.statusCode()}): ${response.body()}" }
        println("Publicada OmniFlow $appVersionName (versionCode $appVersionCode, ${apk.length() / 1024 / 1024} MB) en $publishUrl")
    }
}
