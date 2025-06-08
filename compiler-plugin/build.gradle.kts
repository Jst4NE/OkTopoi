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
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "oktopoi-compiler-plugin"
            from(components["java"])
        }
    }
}