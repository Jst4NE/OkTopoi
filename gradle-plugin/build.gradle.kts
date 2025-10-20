plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    id("maven-publish")
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
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
