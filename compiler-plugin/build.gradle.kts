import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("maven-publish")
}

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    compilerOptions.allWarningsAsErrors.set(false)
    compilerOptions.freeCompilerArgs.addAll(listOf(
        "-Xsuppress-deprecated-jvm-target-warning",
        "-Xallow-unstable-dependencies"
    ))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "oktopoi-compiler-plugin"
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