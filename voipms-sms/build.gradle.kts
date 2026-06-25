import com.android.build.api.dsl.ApplicationExtension
import org.gradle.internal.extensions.stdlib.capitalized

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    id("net.kourlas.oss-licenses-plugin")

    // fdroid-remove-start
    alias(libs.plugins.google.gms.googleServices)
    // fdroid-remove-end
}

configure<ApplicationExtension> {
    compileSdk = 36
    defaultConfig {
        applicationId = "net.kourlas.voipms_sms"
        minSdk = 23
        targetSdk = 36
        versionCode = 154
        versionName = "0.6.33"
    }
    flavorDimensions += "version"
    flavorDimensions += "demo"
    productFlavors {
        // fdroid-remove-start
        create("primary") {
            dimension = "version"
            buildConfigField("boolean", "IS_FDROID", "false")
        }
        // fdroid-remove-end
        create("fdroid") {
            dimension = "version"
            versionNameSuffix = "-fdroid"
            buildConfigField("boolean", "IS_FDROID", "true")
        }
        create("full") {
            dimension = "demo"
            buildConfigField("boolean", "IS_DEMO", "false")
            buildConfigField("boolean", "IS_DEMO_SENDING", "false")
        }
        create("demoNotSending") {
            dimension = "demo"
            applicationId = "net.kourlas.voipms_sms.demo"
            buildConfigField("boolean", "IS_DEMO", "true")
            buildConfigField("boolean", "IS_DEMO_SENDING", "false")
            versionNameSuffix = "-demo"
        }
        create("demoSending") {
            dimension = "demo"
            applicationId = "net.kourlas.voipms_sms.demo"
            buildConfigField("boolean", "IS_DEMO", "true")
            buildConfigField("boolean", "IS_DEMO_SENDING", "true")
            versionNameSuffix = "-demo"
        }
    }
    androidComponents.beforeVariants { variant ->
        variant.enable = run {
            val names = mutableListOf<String>()
            for (flavor in variant.productFlavors) {
                names.add(flavor.second)
            }
            val isDemo =
                names.contains("demoSending") || names.contains("demoNotSending")
            val isRelease = variant.buildType == "release"
            val isPrimary = names.contains("primary")
            !isDemo || (!isPrimary && !isRelease)
        }
    }
    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_17)
        targetCompatibility(JavaVersion.VERSION_17)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("main") {
        kotlin.directories += "src/main/kotlin"
    }
    // fdroid-remove-start
    sourceSets.getByName("primary") {
        kotlin.directories += "src/primary/kotlin"
    }
    // fdroid-remove-end
    sourceSets.getByName("fdroid") {
        kotlin.directories += "src/fdroid/kotlin"
    }
    lint {
        abortOnError = false
    }
    namespace = "net.kourlas.voipms_sms"
}

// The UnifiedPush connector (F-Droid flavor) depends on
// com.google.crypto.tink:tink, which collides with the tink-android brought in
// by androidx.security.crypto (duplicate classes). Unify the F-Droid variant on
// the connector's tink (Android-compatible since 1.9). Scoped to F-Droid
// configurations so the primary/FCM flavor is left untouched.
configurations.matching { it.name.startsWith("fdroid") }.configureEach {
    resolutionStrategy {
        // The UnifiedPush connector pulls the JVM "tink" artifact, which both
        // (a) duplicates classes from "tink-android" (via androidx.security.crypto)
        // and (b) lacks the Android integration (AndroidKeysetManager) that
        // EncryptedSharedPreferences needs at runtime. Unify everything on
        // tink-android, which contains both the core and the Android pieces.
        force("com.google.crypto.tink:tink-android:1.21.0")
        dependencySubstitution {
            substitute(module("com.google.crypto.tink:tink"))
                .using(module("com.google.crypto.tink:tink-android:1.21.0"))
                .because("Use tink-android (has AndroidKeysetManager) for the UnifiedPush connector")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // Kotlin libraries
    implementation(libs.kotlinx.coroutines.android)

    // Android support libraries
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.sharetarget)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.material)
    ksp(libs.androidx.room.compiler)

    // fdroid-remove-start

    // Google and Firebase libraries
    "primaryImplementation"(libs.google.gms.playServicesBase)
    "primaryImplementation"(platform(libs.google.firebase.bom))
    "primaryImplementation"(libs.google.firebase.messaging)

    // fdroid-remove-end

    // UnifiedPush connector (F-Droid flavor push via ntfy). Not a Google
    // dependency, so it stays in the F-Droid build.
    "fdroidImplementation"("org.unifiedpush.android:connector:3.3.3")

    // Other third-party libraries
    implementation(libs.moshi.adapters)
    implementation(libs.moshi)
    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.mukeshsolanki.markdownView)
    implementation(libs.xabaras.recyclerViewSwipeDecorator)
    implementation(libs.saket.betterLinkMovementMethod)
    ksp(libs.moshi.kotlin.codegen)
}

tasks.register<Delete>("cleanAssets") {
    delete("src/main/assets")
}

tasks.register<Copy>("copyToAssets") {
    from("../PRIVACY.md")
    from("../CHANGES.md")
    from("../NOTICE")
    from("../LICENSE.md")
    from("../HELP.md")
    into("src/main/assets")
}

tasks.getByName("preBuild") {
    dependsOn("copyToAssets")
}

tasks.getByName("copyToAssets") {
    dependsOn("cleanAssets")
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val variantName = variant.name

        val generatePackageLicenses =
            tasks.register<Exec>("generatePackageLicenses${variantName.capitalized()}") {
                commandLine(
                    "python",
                    "../licenses/packageLicenseParser.py",
                    variantName
                )
            }.get()

        tasks.matching { it.name == "${variant.name}OssLicensesTask" }
            .configureEach {
                this.dependsOn("cleanAssets")
                generatePackageLicenses.dependsOn(this)
            }

        tasks.matching { it.name == "generate${variant.name.capitalized()}Assets" }
            .configureEach { this.dependsOn(generatePackageLicenses) }
    }
}
