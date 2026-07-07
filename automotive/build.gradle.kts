import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Upload-key credentials for the Play release build. keystore.properties and the .jks it points
// at are gitignored and never committed; a checkout without them still builds debug (and an
// unsigned release). See CLAUDE.md "Release signing".
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "be.bendardenne.jellyfin.aaos"
    compileSdk = 36

    defaultConfig {
        // Diverges from the namespace deliberately: upstream owns be.bendardenne.jellyfin.aaos
        // on Google Play, and this fork needs its own id to reach a real car through a Play
        // closed-testing track (and to coexist with the upstream app). The ContentProvider
        // authority and account type follow it via BuildConfig.APPLICATION_ID and the
        // account_type resValue below.
        applicationId = "elizardbeth.finale"
        minSdk = 29
        targetSdk = 35
        versionCode = 41
        versionName = "1.4"

        // authenticator.xml can't use manifest placeholders, so the account type is injected
        // as a string resource instead.
        resValue("string", "account_type", applicationId!!)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        resValues = true
        dataBinding = true
        viewBinding = true
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the signing config when the credentials are present, so a checkout
            // without keystore.properties still configures (producing an unsigned release).
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.car:car:1.0.0-alpha7")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.databinding:databinding-runtime:9.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    // HLS is used for transcoded audio streams: unlike plain HTTP transcodes, HLS is seekable.
    implementation("androidx.media3:media3-exoplayer-hls:1.9.2")
    implementation("androidx.media3:media3-session:1.9.2")
    implementation("androidx.media3:media3-ui:1.9.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jellyfin.sdk:jellyfin-core:1.8.6")
    // On the runtime classpath transitively via the Jellyfin SDK, but not visible at compile time.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.google.dagger:hilt-android:2.59.2")
    // REQUIRED AT RUNTIME, do not remove: the Jellyfin SDK's okhttp engine logs through
    // io.github.oshai:kotlin-logging, whose JVM binding calls org.slf4j.LoggerFactory. Without
    // slf4j-api on the classpath the very first SDK HTTP call (e.g. the sign-in ping) dies with
    // NoClassDefFoundError. slf4j-android is only a (NO-OP under 2.x api) provider; the app's own
    // logging goes through android.util.Log, so the NO-OP binding is harmless — presence is what
    // matters, not that it routes anywhere.
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.slf4j:slf4j-android:1.7.36")
    ksp("com.google.dagger:hilt-compiler:2.59.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}