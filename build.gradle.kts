buildscript {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.0.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
        classpath("com.github.michaelkourlas:oss-licenses-plugin:0.0.2")

        // fdroid-remove-start
        classpath("com.google.gms:google-services:4.4.2")
        // fdroid-remove-end
    }
}

plugins {
    id("com.google.devtools.ksp") version "2.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
