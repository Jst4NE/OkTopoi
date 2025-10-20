plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    id("maven-publish")
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
}

// Generate version constant at build time
val generateVersionFile = tasks.register("generateVersionFile") {
    val outputDir = layout.buildDirectory.dir("generated/source/version")
    val versionFile = outputDir.map { it.file("jst/oktopoi/gradle/OktopoiVersion.kt") }

    // Capture version during configuration phase
    val version = project.version.toString()

    inputs.property("version", version)
    outputs.dir(outputDir)

    doLast {
        versionFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""
                package jst.oktopoi.gradle

                internal object OktopoiVersion {
                    const val VERSION = "$version"
                }

            """.trimIndent())
        }
    }
}

// Add generated source to compilation
sourceSets {
    main {
        kotlin {
            srcDir(generateVersionFile.map { it.outputs.files.singleFile })
        }
    }
}

// Ensure version file is generated before compilation
tasks.named("compileKotlin") {
    dependsOn(generateVersionFile)
}

gradlePlugin {
    plugins {
        create("oktopoi") {
            id = "jst.oktopoi"
            implementationClass = "jst.oktopoi.gradle.OktopoiGradlePlugin"
            displayName = "Oktopoi Kotlin Multiplatform Plugin"
            description = "Compiler plugin for Oktopoi state management library"
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "oktopoi-gradle-plugin"
            from(components["java"])
        }
    }

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
