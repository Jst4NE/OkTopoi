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
        // Apply the compiler plugin
        target.plugins.apply(OktopoiCompilerSubplugin::class.java)

        // Auto-configure dependencies for multiplatform projects
        target.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            target.extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.named("commonMain") {
                    dependencies {
                        implementation("jst.oktopoi:okTopoi:1.0.0")
//                        implementation("jst.oktopoi:okTopoi:${target.version}")
                    }
                }
            }
        }

        // Auto-configure dependencies for JVM-only projects
        target.plugins.withType<org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper> {
            target.dependencies {
                add("implementation", "jst.oktopoi:okTopoi:1.0.0")
//                add("implementation", "jst.oktopoi:okTopoi:${target.version}")
            }
        }
    }
}

internal class OktopoiCompilerSubplugin : KotlinCompilerPluginSupportPlugin {
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = "jst.oktopoi.compiler-plugin"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "jst.oktopoi",
        artifactId = "oktopoi-compiler-plugin",
        version = "1.0.0"
    )

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        return kotlinCompilation.target.project.provider { emptyList() }
    }
}