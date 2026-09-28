
plugins {
    kotlin("jvm")
    id("maven-publish")
}

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.allWarningsAsErrors.set(false)
    compilerOptions.freeCompilerArgs.addAll(listOf(
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