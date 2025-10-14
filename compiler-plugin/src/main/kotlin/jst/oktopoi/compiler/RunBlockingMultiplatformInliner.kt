@file:Suppress("DEPRECATION")

package jst.oktopoi.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.name.CallableId
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

    // Cache platform decision once per compilation
    private val isWasmJs: Boolean by lazy { computeIsWasmJs() }

    // Resolve runBlocking top-level function by FQ name; prefer 1-arg overload, else 2-arg
    private val runBlockingFnOrNull: IrSimpleFunctionSymbol? by lazy {
        try {
            val callableId = CallableId(FqName("kotlinx.coroutines"), Name.identifier("runBlocking"))
            val symbols = pluginContext.referenceFunctions(callableId).toList()
            val candidates = symbols.map { it.owner }
                .filter { fn -> fn.name.asString() == "runBlocking" && fn.parameters.count { it.kind == IrParameterKind.Regular } in setOf(1, 2) }
            val selected: IrSimpleFunction? = candidates.minByOrNull { fn -> fn.parameters.count { it.kind == IrParameterKind.Regular } }
            selected?.symbol
        } catch (_: Throwable) { null }
    }

    // Resolve EmptyCoroutineContext for 2-arg overload fallback
    private val emptyContextClassOrNull: IrClassSymbol? by lazy {
        pluginContext.referenceClass(
            ClassId(
                FqName("kotlin.coroutines"),
                Name.identifier("EmptyCoroutineContext")
            )
        )
    }

    override fun visitCall(expression: IrCall): IrExpression {
        // Skip inlining on WASM-JS platform - keep existing implementation
        if (isWasmJsPlatform()) {
            return super.visitCall(expression)
        }

        // Check if this is a runBlockingMultiplatform call
        if (isRunBlockingMultiplatformCall(expression)) {
            val target = runBlockingFnOrNull
            if (target != null) {
                // Get the lambda parameter (suspend CoroutineScope.() -> T)
                val lambdaArg = expression.arguments[0]
                if (lambdaArg != null) {
                    // Replace with direct runBlocking call
                    return createRunBlockingCall(lambdaArg, expression, target)
                }
            }
        }

        return super.visitCall(expression)
    }

    private fun isWasmJsPlatform(): Boolean {
        return isWasmJs
    }

    private fun computeIsWasmJs(): Boolean {
        // Use proper Kotlin compiler platform type checking - no string matching!
        val platform = pluginContext.platform ?: return false
        return try {
            platform.componentPlatforms.any { component ->
                when {
                    component is WasmPlatform -> true
                    JsPlatforms.allJsPlatforms.any { it == component } -> true
                    else -> false
                }
            }
        } catch (_: Exception) {
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

    private fun createRunBlockingCall(
        lambdaArg: IrExpression,
        originalCall: IrCall,
        targetSymbol: IrSimpleFunctionSymbol
    ): IrExpression {
        val builder = DeclarationIrBuilder(pluginContext, targetSymbol)
        val call = builder.irCall(targetSymbol).apply {
            // Copy type argument (T)
            typeArguments[0] = originalCall.typeArguments[0]
        }

        val arity = targetSymbol.owner.parameters.count { it.kind == IrParameterKind.Regular }
        return if (arity == 1) {
            call.apply { arguments[0] = lambdaArg }
        } else {
            val emptyCtx = emptyContextClassOrNull ?: return originalCall
            call.apply {
                arguments[0] = createEmptyCoroutineContext(emptyCtx)
                arguments[1] = lambdaArg
            }
        }
    }

    private fun createEmptyCoroutineContext(emptyContextClass: IrClassSymbol): IrExpression {
        val builder = DeclarationIrBuilder(pluginContext, emptyContextClass)
        return builder.irGetObject(emptyContextClass)
    }
}
