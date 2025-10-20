package jst.oktopoi.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class OktopoiGradlePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // Get version from gradle.properties
        val oktopoiVersion = target.rootProject.findProperty("oktopoi.version") as String?
            ?: target.findProperty("oktopoi.version") as String?
            ?: "1.0.10"  // Fallback version

        // Apply the compiler plugin
        target.plugins.apply(OktopoiCompilerSubplugin::class.java)

        // Auto-configure dependencies for multiplatform projects
        target.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            target.extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.named("commonMain") {
                    dependencies {
                        implementation("jst.oktopoi:oktopoi:$oktopoiVersion")
                    }
                }
                sourceSets.named("commonTest") {
                    dependencies {
                        implementation("jst.oktopoi:oktopoi:$oktopoiVersion")
                    }
                }
            }
        }

        // Auto-configure dependencies for JVM-only projects
        target.plugins.withType<org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper> {
            target.dependencies {
                add("implementation", "jst.oktopoi:oktopoi:$oktopoiVersion")
                add("testImplementation", "jst.oktopoi:oktopoi:$oktopoiVersion")
            }
        }
    }
}

internal class OktopoiCompilerSubplugin : KotlinCompilerPluginSupportPlugin {
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = "jst.oktopoi.compiler-plugin"

    override fun getPluginArtifact(): SubpluginArtifact {
        // Get version from gradle.properties
        val project = kotlinCompilation.target.project
        val oktopoiVersion = project.rootProject.findProperty("oktopoi.version") as String?
            ?: project.findProperty("oktopoi.version") as String?
            ?: "1.0.10"  // Fallback version

        return SubpluginArtifact(
            groupId = "jst.oktopoi",
            artifactId = "oktopoi-compiler-plugin",
            version = oktopoiVersion
        )
    }

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        return kotlinCompilation.target.project.provider { emptyList() }
    }
}