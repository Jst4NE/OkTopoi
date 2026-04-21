@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package jst.oktopoi.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

class OktopoiIrGenerationExtension(
    private val messageCollector: org.jetbrains.kotlin.cli.common.messages.MessageCollector = org.jetbrains.kotlin.cli.common.messages.MessageCollector.NONE
) : IrGenerationExtension {
    
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext
    ) {
        try {
            // Resolve E/Es class symbols for the transformer
            val firstFile = moduleFragment.files.firstOrNull()
            val finder = firstFile?.let { pluginContext.finderForSource(it) }
            val eClassId = ClassId.topLevel(FqName("jst.oktopoi.E"))
            val esClassId = ClassId.topLevel(FqName("jst.oktopoi.Es"))
            val eSymbol = finder?.let { try { it.findClass(eClassId) } catch (_: Exception) { null } }
            val esSymbol = finder?.let { try { it.findClass(esClassId) } catch (_: Exception) { null } }

            moduleFragment
                .transform(OktopoiTransformer(pluginContext, messageCollector, eSymbol, esSymbol), null)
                .transform(ComposeCallSiteRememberWrapper(pluginContext, messageCollector), null)
                .transform(RunBlockingMultiplatformInliner(pluginContext), null)
        } catch (e: Exception) {
            messageCollector.report(CompilerMessageSeverity.ERROR, "[OktopoiPlugin] Transformation error: ${e.message}")
            throw e
        }
    }
}

