import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    id("maven-publish")

    alias(libs.plugins.kotlin.serialize)

    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "jst.oktopoi"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
//    iosSimulatorArm64()
    linuxX64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    // Serve sources to debug inside browser
                    static(rootDirPath)
                    static(projectDirPath)
                }
            }
        }
//        binaries.library()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                // JSON serialization library, works with the Kotlin serialization plugin.
                implementation(libs.kotlinx.serialization.json)

                implementation(libs.atomicfu)

                api(libs.kotlinx.io.core)

                api(libs.compose.runtime)
                api(libs.lifecycle.runtime.compose)

                implementation(libs.kermit)


                //put your multiplatform dependencies here
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

dependencies {
    // Make the IR compiler plugin available when compiling the okTopoi module itself
    add("kotlinCompilerPluginClasspath", project(":compiler-plugin"))
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Jst4NE/OkTopoi")
            credentials {
                username = rootProject.extra["github.actor"] as String?
                password = rootProject.extra["github.token"] as String?
            }
        }
    }
}