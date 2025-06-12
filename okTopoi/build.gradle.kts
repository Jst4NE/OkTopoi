import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
//    alias(libs.plugins.vanniktech.mavenPublish)
    id("maven-publish")

    alias(libs.plugins.kotlin.serialize)

    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

group = "jst.oktopoi"
version = "1.0.0"

kotlin {
    jvm()
    androidTarget {
        publishLibraryVariants("release")
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
//        binaries.library()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                // JSON serialization library, works with the Kotlin serialization plugin.
                api(libs.kotlinx.serialization.json)

//                implementation(libs.atomicfu)

                api(libs.kotlinx.io.core)

                api(compose.runtime)

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

android {
    namespace = "jst.oktopoi"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

publishing {
    repositories {
        maven {
        }
    }
}
//publishing {
//    publications.withType<MavenPublication> {
//        groupId = "jst"
//        artifactId = "oktopoi"
//        version = project.version.toString()
//
//        pom {
//            name.set("OkTopoi")
//            description.set("OkTopoi")
//            url.set("https://github.com/yourorg/oktopoi")
//
//            licenses {
//                license {
//                    name.set("MIT License")
//                    url.set("https://opensource.org/licenses/MIT")
//                }
//            }
//
//            developers {
//                developer {
//                    id.set("yourname")
//                    name.set("Your Name")
//                    email.set("your.email@example.com")
//                }
//            }
//        }
//    }
//}