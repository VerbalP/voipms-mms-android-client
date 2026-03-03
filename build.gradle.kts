buildscript {
    repositories {
        maven(url = "https://jitpack.io")
    }
    dependencies {
        classpath("com.github.michaelkourlas:oss-licenses-plugin:0.0.5")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
