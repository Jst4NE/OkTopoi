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
            val transformed1 = moduleFragment.transform(OktopoiTransformer(pluginContext, messageCollector), null)
            val transformed2 = transformed1.transform(TreeMapSuspendTransformer(pluginContext), null)
            val transformed3 = transformed2.transform(RunBlockingMultiplatformInliner(pluginContext), null)
        } catch (e: Exception) {
            messageCollector.report(CompilerMessageSeverity.ERROR, "[OktopoiPlugin] Transformation error: ${e.message}")
            throw e
        }
    }
}

