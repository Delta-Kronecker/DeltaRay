import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Комбинированная сборка DeltaRay: ZeroDPI встраивается в APK Lbox как
// Android-библиотека. Путь к runtime-артефактам (jniLibs + assets) берётся
// из gradle-свойства zerodpiRuntimeDir или переменной окружения
// ZERODPI_RUNTIME_DIR (см. scripts/build-apk.sh и .github/workflows).
fun stringPropertyOrEnv(name: String) =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name))

val zeroDpiRuntimeDir = providers.gradleProperty("zerodpiRuntimeDir")
    .orElse(providers.environmentVariable("ZERODPI_RUNTIME_DIR"))
    .map(String::trim)
    .filter { it.isNotEmpty() }
    .map { file(it) }

android {
    namespace = "dev.zerodpi.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ZERODPI_ALLOW_FAKE_RUNNER", "true")
        }
        release {
            buildConfigField("boolean", "ZERODPI_ALLOW_FAKE_RUNNER", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        named("main") {
            zeroDpiRuntimeDir.orNull?.let { runtimeDir ->
                assets {
                    directories.add(runtimeDir.resolve("assets"))
                }
                jniLibs {
                    directories.add(runtimeDir.resolve("jniLibs"))
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.work:work-testing:2.11.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
