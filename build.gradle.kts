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
    version = findProperty("oktopoi.version") as String? ?: "1.0.0-SNAPSHOT"

    // GitHub credentials from environment (used by GitHub Actions)
    extra["github.actor"] = System.getenv("GITHUB_ACTOR") ?: ""
    extra["github.token"] = System.getenv("GITHUB_TOKEN") ?: ""
}

// Task to create and push a git release tag matching oktopoi.version
tasks.register("createReleaseTag") {
    group = "release"
    description = "Create and push a git tag matching the oktopoi.version in gradle.properties"

    // Capture project property during configuration phase
    val oktopoiVersion = project.property("oktopoi.version") as String

    doLast {
        val tag = "v$oktopoiVersion"

        println("Current version: $oktopoiVersion")
        println("Git tag to create: $tag")
        println()

        // Check if tag already exists
        val checkTag = ProcessBuilder("git", "rev-parse", tag)
            .redirectErrorStream(true)
            .start()
        checkTag.waitFor()

        if (checkTag.exitValue() == 0) {
            throw GradleException("""
                Tag $tag already exists!
                To create a new release:
                  1. Update oktopoi.version in gradle.properties
                  2. Run this task again
            """.trimIndent())
        }

        println("Creating tag $tag...")

        // Create annotated tag
        val createTag = ProcessBuilder("git", "tag", "-a", tag, "-m", "Release $oktopoiVersion")
            .inheritIO()
            .start()

        if (createTag.waitFor() != 0) {
            throw GradleException("Failed to create git tag")
        }

        println("Pushing tag $tag to origin...")

        // Push tag
        val pushTag = ProcessBuilder("git", "push", "origin", tag)
            .inheritIO()
            .start()

        if (pushTag.waitFor() != 0) {
            throw GradleException("Failed to push git tag")
        }

        println()
        println("✓ Tag $tag created and pushed!")
        println("✓ GitHub Actions will now publish OkTopoi packages to GitHub Packages")

        // Try to get the repository URL
        val getUrl = ProcessBuilder("git", "remote", "get-url", "origin")
            .redirectErrorStream(true)
            .start()
        val repoUrl = getUrl.inputStream.bufferedReader().readText().trim()

        if (repoUrl.contains("github.com")) {
            val repoPath = repoUrl
                .replace("git@github.com:", "")
                .replace("https://github.com/", "")
                .removeSuffix(".git")
            println("✓ Check progress at: https://github.com/$repoPath/actions")
        }
    }
}

// Task to automatically bump version, commit, push, and create release tag
tasks.register("release") {
    group = "release"
    description = "Bump patch version, commit, push, and create release tag (all-in-one)"

    // Capture references during configuration phase
    val gradlePropertiesFile = project.file("gradle.properties")
    val projectRootDir = project.rootDir

    doLast {

        // Read current version
        val propertiesContent = gradlePropertiesFile.readText()
        val currentVersion = Regex("oktopoi\\.version=(.+)").find(propertiesContent)?.groupValues?.get(1)?.trim()
            ?: throw GradleException("oktopoi.version not found in gradle.properties")

        // Parse version (assumes semantic versioning: major.minor.patch)
        val versionParts = currentVersion.split(".")
        if (versionParts.size != 3) {
            throw GradleException("Version must be in format major.minor.patch (e.g., 1.0.0)")
        }

        val major = versionParts[0].toInt()
        val minor = versionParts[1].toInt()
        val patch = versionParts[2].toInt()

        // Increment patch version
        val newVersion = "$major.$minor.${patch + 1}"
        val tag = "v$newVersion"

        println("Current version: $currentVersion")
        println("New version: $newVersion")
        println()

        // Check if tag already exists
        val checkTag = ProcessBuilder("git", "rev-parse", tag)
            .redirectErrorStream(true)
            .start()
        checkTag.waitFor()

        if (checkTag.exitValue() == 0) {
            throw GradleException("Tag $tag already exists! Cannot create release.")
        }

        // Check for uncommitted changes (excluding gradle.properties)
        val statusCheck = ProcessBuilder("git", "status", "--porcelain")
            .redirectErrorStream(true)
            .start()
        val statusOutput = statusCheck.inputStream.bufferedReader().readText()
        statusCheck.waitFor()

        val uncommittedFiles = statusOutput.lines()
            .filter { it.isNotBlank() && !it.contains("gradle.properties") }

        if (uncommittedFiles.isNotEmpty()) {
            throw GradleException("""
                Uncommitted changes detected:
                ${uncommittedFiles.joinToString("\n")}

                Please commit or stash your changes before creating a release.
            """.trimIndent())
        }

        // Update version in gradle.properties
        println("Updating gradle.properties to version $newVersion...")
        val content = gradlePropertiesFile.readText()
        val updatedContent = content.replace(
            Regex("oktopoi\\.version=.*"),
            "oktopoi.version=$newVersion"
        )
        gradlePropertiesFile.writeText(updatedContent)

        // Git add gradle.properties
        println("Adding gradle.properties to git...")
        val gitAdd = ProcessBuilder("git", "add", "gradle.properties")
            .inheritIO()
            .start()
        if (gitAdd.waitFor() != 0) {
            throw GradleException("Failed to git add gradle.properties")
        }

        // Git commit
        println("Committing version bump...")
        val gitCommit = ProcessBuilder("git", "commit", "-m", "Bump version to $newVersion")
            .inheritIO()
            .start()
        if (gitCommit.waitFor() != 0) {
            throw GradleException("Failed to git commit")
        }

        // Git push
        println("Pushing to origin...")
        val gitPush = ProcessBuilder("git", "push")
            .inheritIO()
            .start()
        if (gitPush.waitFor() != 0) {
            throw GradleException("Failed to git push")
        }

        // Create annotated tag
        println("Creating tag $tag...")
        val createTag = ProcessBuilder("git", "tag", "-a", tag, "-m", "Release $newVersion")
            .inheritIO()
            .start()
        if (createTag.waitFor() != 0) {
            throw GradleException("Failed to create git tag")
        }

        // Push tag
        println("Pushing tag $tag to origin...")
        val pushTag = ProcessBuilder("git", "push", "origin", tag)
            .inheritIO()
            .start()
        if (pushTag.waitFor() != 0) {
            throw GradleException("Failed to push git tag")
        }

        // Publish to mavenLocal
        println()
        println("Publishing to mavenLocal...")
        val publishLocal = ProcessBuilder("./gradlew", "publishToMavenLocal")
            .directory(projectRootDir)
            .inheritIO()
            .start()
        if (publishLocal.waitFor() != 0) {
            throw GradleException("Failed to publish to mavenLocal")
        }

        println()
        println("✓ Version bumped from $currentVersion to $newVersion")
        println("✓ Changes committed and pushed")
        println("✓ Tag $tag created and pushed")
        println("✓ Published to mavenLocal")
        println("✓ GitHub Actions will now publish OkTopoi packages to GitHub Packages")

        // Try to get the repository URL
        val getUrl = ProcessBuilder("git", "remote", "get-url", "origin")
            .redirectErrorStream(true)
            .start()
        val repoUrl = getUrl.inputStream.bufferedReader().readText().trim()

        if (repoUrl.contains("github.com")) {
            val repoPath = repoUrl
                .replace("git@github.com:", "")
                .replace("https://github.com/", "")
                .removeSuffix(".git")
            println("✓ Check progress at: https://github.com/$repoPath/actions")
        }
    }
}
