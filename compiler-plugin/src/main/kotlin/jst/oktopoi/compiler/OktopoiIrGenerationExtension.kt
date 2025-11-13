@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package jst.oktopoi.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity

class OktopoiIrGenerationExtension(
    private val messageCollector: org.jetbrains.kotlin.cli.common.messages.MessageCollector = org.jetbrains.kotlin.cli.common.messages.MessageCollector.NONE
) : IrGenerationExtension {
    
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext
    ) {
        try {
            // Only apply OktopoiTransformer for E/Es metadata injection
            // TreeMapSuspendTransformer removed - TreeMap methods are now natively suspend
            // RunBlockingMultiplatformInliner removed - no more runBlocking calls
            moduleFragment.transform(OktopoiTransformer(pluginContext, messageCollector), null)
        } catch (e: Exception) {
            messageCollector.report(CompilerMessageSeverity.ERROR, "[OktopoiPlugin] Transformation error: ${e.message}")
            throw e
        }
    }
}

