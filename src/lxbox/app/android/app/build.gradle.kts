plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun hasReleaseKeystore(): Boolean =
    keystorePropertiesFile.exists() &&
        !keystoreProperties.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "com.leadaxe.lxbox"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // §380 — AGP по умолчанию вшивает в APK блок `DEPENDENCY METADATA`: список
    // зависимостей, зашифрованный публичным ключом Google. Читать его умеет
    // только Play Console, поэтому сканер F-Droid считает его непрозрачными
    // данными и отвергает APK целиком («Found extra signing block»).
    //
    // Лежит он в APK Signing Block — ВНЕ zip-структуры, поэтому пофайловое
    // сравнение архивов его не видит: 455 файлов совпадали, а верификация
    // всё равно падала (7185 байт, id 0x504b4453 в v2.20.4).
    //
    // Для AAB оставлено включённым — Google Play собирается из бандла, и там
    // эти данные дают предупреждения об уязвимых библиотеках.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    defaultConfig {
        // §DeltaRay — уникальный applicationId, чтобы приложение ставилось
        // РЯДОМ с оригинальным L×Box (`com.leadaxe.lxbox`), а не как его
        // замена (иначе Android видит тот же package и падает на
        // INSTALL_FAILED_ALREADY_EXISTS / signature mismatch).
        //
        // `namespace` выше остаётся `com.leadaxe.lxbox`: это лишь пакет
        // сгенерированного R-класса и FQN Kotlin-компонентов, он НЕ является
        // install-идентичностью и не мешает сосуществованию.
        applicationId = "com.deltakronecker.deltaray"
        // Android 7.0 (API 24) minimum — §233. Это абсолютный пол: Flutter
        // 3.41.x поддерживает минимум API 24, libXray.aar требует 21.
        // Приоритет тестирования и поддержки — 11+ (primary target window).
        //
        // Tiers:
        //   - Primary (11+, API 30+)  — все фичи, тестируется.
        //   - Best-effort (7.0-10, API 24-29) — compile/install OK, фичи
        //     новых API деградируют за SDK_INT-гейтами. Например, silent-kill
        //     detection (getHistoricalProcessExitReasons, API 30+) — no-op.
        //   - Unsupported (<7.0, API <24) — install blocked (пол Flutter).
        //
        // Known limitation 7.x: старый системный trust store (на 7.0 нет
        // ISRG Root X1 → Let's Encrypt-подписки не валидируются).
        // См. ARCHITECTURE.md → Supported platforms и docs/spec/tasks/233.
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // ABI filter (build-size optimization). Flutter `--target-platform`
        // влияет только на свой engine + Dart AOT; нативные .so из Maven
        // (libXray — 50-67 MB per ABI) gradle подтягивает для всех
        // ABI, и APK раздувается до ~76MB.
        //
        // Сужаем через переменную окружения `LXBOX_ABI_FILTER` (выставляется
        // в scripts/build-local-apk.sh). `-P` props из flutter build не
        // пробрасываются стабильно, env-var универсально срабатывает.
        // Если var не задан — поведение не меняется (CI-сборка по
        // умолчанию остаётся универсальной, как раньше).
    }

    // ABI filter (build-size optimization). Flutter gradle plugin по
    // умолчанию выставляет `ndk.abiFilters` для всех 3 ABI
    // (armeabi-v7a, arm64-v8a, x86_64) — даже если передан
    // `--target-platform android-arm64` это влияет только на flutter engine
    // и Dart AOT, native libs из Maven AAR (libXray 50-67 MB / ABI)
    // подтягиваются под все 3.
    //
    // Очищаем `ndk.abiFilters` и задаём только нужный ABI через env-var
    // `LXBOX_ABI_FILTER` (выставляется в scripts/build-local-apk.sh).
    // Если var не задан — поведение не меняется (CI-сборка остаётся
    // универсальной для всех 3 ABI).
    val abiFilterEnv: String? = System.getenv("LXBOX_ABI_FILTER")
    if (!abiFilterEnv.isNullOrBlank()) {
        val keepAbis = abiFilterEnv.split(",").map { it.trim() }.toSet()
        defaultConfig.ndk.abiFilters.clear()
        defaultConfig.ndk.abiFilters.addAll(keepAbis)
        // Дополнительно: исключаем JNI-libs других ABI из AAR (libXray).
        // `ndk.abiFilters` контролирует только локально-собранные .so;
        // AAR-вложенные .so отфильтровываются именно `packaging.jniLibs.excludes`.
        val allAbis = setOf("armeabi-v7a", "arm64-v8a", "x86_64", "x86")
        val excludeAbis = allAbis - keepAbis
        packaging {
            for (abi in excludeAbis) {
                jniLibs.excludes += "lib/$abi/**"
            }
        }
    }

    signingConfigs {
        if (hasReleaseKeystore()) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")!!
                keyPassword = keystoreProperties.getProperty("keyPassword")!!
                storePassword = keystoreProperties.getProperty("storePassword")!!
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile")!!)
                // §DeltaRay — современное хранилище (keystore от JDK 9+ по
                // умолчанию PKCS12; JKS deprecated). CI кладёт storeType в
                // key.properties; дефолт — PKCS12.
                storeType = keystoreProperties.getProperty("storeType") ?: "PKCS12"
                // Явно требуем все схемы подписи: v1 (JAR, API < 24 / legacy
                // verifier), v2 (APK Signing Block, API 24+) и v3 (ключевая
                // ротация, API 28+). Так APK проверяется на любом устройстве
                // и совместим с ротацией ключа.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig =
                if (hasReleaseKeystore()) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    // §104 — ядро: Xray-core через libXray (XTLS/libXray gomobile wrapper).
    // AAR не в git (~99MB, libs/ в .gitignore): его кладёт
    // scripts/fetch-xray.sh (пин версии+sha256 — app/android/xray.version),
    // вызывается из build-local-apk.sh и CI (build.yml → "Fetch Xray core").
    implementation(files("libs/libxray.aar"))
    // §Xray — gRPC-клиент к командному API Xray (commander "api"-blok).
    // Стабы пред-сгенерированы (app/src/main/java/com/xray) — плагин codegen
    // не нужен: AGP 9 + protobuf-gradle-plugin несовместимы, см. §migration.
    implementation("io.grpc:grpc-okhttp:1.68.1")
    implementation("io.grpc:grpc-stub:1.68.1")
    implementation("io.grpc:grpc-protobuf:1.68.1")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // ZeroDPI (Android library) — код + Compose UI + jniLibs/assets runtime.
    implementation(project(":zerodpi"))
}

flutter {
    source = "../.."
}
