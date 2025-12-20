package jst.oktopoi.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.config.CommonConfigurationKeys

@OptIn(ExperimentalCompilerApi::class)
class OktopoiCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "jst.oktopoi.compiler-plugin"
    override val supportsK2: Boolean = true
    
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY) ?: MessageCollector.NONE
        
        try {
            // Register IR generation extension (core functionality)
            val irExtension = OktopoiIrGenerationExtension(messageCollector)
            IrGenerationExtension.registerExtension(irExtension)
            
            messageCollector.report(CompilerMessageSeverity.INFO, "[OktopoiPlugin] IR extension registered successfully")
        } catch (e: Exception) {
            messageCollector.report(CompilerMessageSeverity.ERROR, "[OktopoiPlugin] Failed to register extensions: ${e.message}")
            throw e
        }
    }
}
