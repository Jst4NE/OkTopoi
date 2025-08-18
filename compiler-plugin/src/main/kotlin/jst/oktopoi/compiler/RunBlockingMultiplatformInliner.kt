@file:Suppress("DEPRECATION")

package jst.oktopoi.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
// Try importing platform classes for proper type checking
import org.jetbrains.kotlin.platform.wasm.WasmPlatform
import org.jetbrains.kotlin.platform.js.JsPlatforms

/**
 * Transformer that inlines runBlockingMultiplatform calls on non-JS platforms
 * by replacing them with direct kotlinx.coroutines.runBlocking calls.
 * 
 * WASM-JS platform is skipped to preserve the custom implementation that handles
 * single-threaded execution correctly.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class RunBlockingMultiplatformInliner(
    private val pluginContext: IrPluginContext
) : IrElementTransformerVoidWithContext() {
    
    override fun visitCall(expression: IrCall): IrExpression {
        // Skip inlining on WASM-JS platform - keep existing implementation  
        if (isWasmJsPlatform()) {
            return super.visitCall(expression)
        }
        
        // Check if this is a runBlockingMultiplatform call
        if (isRunBlockingMultiplatformCall(expression)) {
            // Get the lambda parameter (suspend CoroutineScope.() -> T)
            val lambdaArg = expression.arguments[0]
            if (lambdaArg != null) {
                // Replace with direct runBlocking call
                return createRunBlockingCall(lambdaArg, expression)
            }
        }
        
        return super.visitCall(expression)
    }
    
    private fun isWasmJsPlatform(): Boolean {
        // Use proper Kotlin compiler platform type checking - no string matching!
        val platform = pluginContext.platform ?: return false
        
        return try {
            // Check if any component platform is JS or WASM using actual type checking
            platform.componentPlatforms.any { component ->
                when {
                    component is WasmPlatform -> true
                    JsPlatforms.allJsPlatforms.any { it == component } -> true
                    else -> false
                }
            }
        } catch (_: Exception) {
            // Conservative fallback: assume not JS/WASM if type checking fails
            false
        }
    }
    
    private fun isRunBlockingMultiplatformCall(expression: IrCall): Boolean {
        val function = expression.symbol.owner  
        val functionName = function.name.asString()
        
        if (functionName != "runBlockingMultiplatform") return false
        
        val packageFragment = function.parent as? IrPackageFragment ?: return false
        return packageFragment.packageFqName.asString() == "jst.oktopoi"
    }
    
    private fun createRunBlockingCall(lambdaArg: IrExpression, originalCall: IrCall): IrExpression {
        // Use K2-compatible API to resolve runBlocking from kotlinx.coroutines
        val buildersKtClass = pluginContext.referenceClass(
            ClassId(
                FqName("kotlinx.coroutines"),
                Name.identifier("BuildersKt")
            )
        ) ?: error("kotlinx.coroutines.BuildersKt not found")
        
        val runBlockingSymbol = buildersKtClass.owner.declarations
            .filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrSimpleFunction>()
            .single { function ->
                function.name.asString() == "runBlocking" &&
                function.parameters.size == 2 // context + block
            }.symbol
        
        // Use DeclarationIrBuilder for safer IR construction
        val builder = DeclarationIrBuilder(pluginContext, runBlockingSymbol)
        
        return builder.irCall(runBlockingSymbol).apply {
            // Copy type argument (T)
            typeArguments[0] = originalCall.typeArguments[0]
            
            // Default context (EmptyCoroutineContext)
            arguments[0] = createEmptyCoroutineContext()
            
            // Copy the lambda
            arguments[1] = lambdaArg
        }
    }
    
    private fun createEmptyCoroutineContext(): IrExpression {
        val emptyContextClass = pluginContext.referenceClass(
            ClassId(
                FqName("kotlin.coroutines"),
                Name.identifier("EmptyCoroutineContext")
            )
        ) ?: error("EmptyCoroutineContext not found")
        
        val builder = DeclarationIrBuilder(pluginContext, emptyContextClass)
        return builder.irGetObject(emptyContextClass)
    }
}