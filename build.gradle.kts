plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply  false
    kotlin("jvm") version "2.1.21" apply false
    alias(libs.plugins.kotlin.serialize) apply false

    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false

    alias(libs.plugins.vanniktech.mavenPublish) apply false

    id("maven-publish")
}

allprojects {
    group = "jst.oktopoi"
    version = "1.0.0"
}
