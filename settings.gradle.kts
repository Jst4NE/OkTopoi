pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Load GitHub credentials from local.properties
val localProperties = java.util.Properties()
val localPropertiesFile = file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

// Set properties from local.properties or environment variables
val githubActor = localProperties.getProperty("github.actor") ?: System.getenv("GITHUB_ACTOR")
val githubToken = localProperties.getProperty("github.token") ?: System.getenv("GITHUB_TOKEN")

// Make available to all projects
extra["github.actor"] = githubActor
extra["github.token"] = githubToken

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "OkTopoi"
include(":okTopoi")
include(":compiler-plugin")
include(":gradle-plugin")

