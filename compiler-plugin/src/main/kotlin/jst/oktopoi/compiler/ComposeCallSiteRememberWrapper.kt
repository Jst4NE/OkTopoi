@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package jst.oktopoi.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrVarargElement
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSpreadElementImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Wraps arguments passed to `@WrapInRemember`-annotated parameters of `@Composable` functions
 * in a memoizing call at the caller's call site, **keyed on variables the argument captures
 * from its enclosing scope**. Zero-capture args fall back to unconditional "first-composition"
 * caching (previous plugin behaviour).
 *
 * Two emission paths depending on plugin ordering:
 *  - **Pre-Compose** (Compose hasn't run yet): emit `remember(vararg keys, calculation)` — Compose
 *    will later lower this to a Composer cache call.
 *  - **Post-Compose** (Compose already injected `$composer`): emit directly to the Composer
 *    slot-table API:
 *
 *        $composer.startReplaceGroup(<stable-int>)
 *        val v = $composer.cache(<or-of-per-capture-changed>) { <arg> }
 *        $composer.endReplaceGroup()
 *        v
 *
 *    `Composer.changed(Any?)` is a slot-consuming call — all N keys must be evaluated (no
 *    short-circuiting), so we combine them with non-short-circuit `Boolean.or`.
 *
 * Fail-fast: if any required Compose Runtime symbol is missing (API drift across Compose
 * versions), the plugin errors out the build with a clear diagnostic. Silent fallback is
 * intentionally NOT provided — a broken compile-time contract must surface loudly.
 */
class ComposeCallSiteRememberWrapper(
    private val pluginContext: IrPluginContext,
    private val messageCollector: MessageCollector,
) : IrElementTransformerVoidWithContext() {

    private val wrapInRememberFqName = FqName("jst.oktopoi.WrapInRemember")
    private val composableFqName = FqName("androidx.compose.runtime.Composable")
    private val composerClassId = ClassId.topLevel(FqName("androidx.compose.runtime.Composer"))

    // Lazily resolved (per compilation) — first lookup fails the build if missing.
    private val composerSymbols: ComposerSymbols by lazy { resolveComposerSymbols() }

    private data class ComposerSymbols(
        val composerClass: IrClassSymbol,
        val startReplaceGroup: IrSimpleFunctionSymbol,
        val endReplaceGroup: IrSimpleFunctionSymbol,
        val cache: IrSimpleFunctionSymbol,
        val changed: IrSimpleFunctionSymbol,
    )

    /**
     * A captured value used as a cache key.
     *
     * [isVarargArray] is set when the captured declaration is an enclosing composable's `vararg`
     * parameter, whose Array identity is re-synthesized every recomposition. Such captures need
     * element-wise keying rather than identity keying (see [buildCacheBlock]).
     */
    private data class Capture(
        val decl: IrValueDeclaration,
        val isVarargArray: Boolean,
    )

    private fun resolveComposerSymbols(): ComposerSymbols {
        val composerClass = pluginContext.referenceClass(composerClassId)
            ?: failHard("Could not resolve class androidx.compose.runtime.Composer. " +
                "Is the Compose Runtime dependency on the classpath?")

        // startReplaceGroup(Int) — fall back to legacy name startReplaceableGroup
        val startFn = composerClass.functions.firstOrNull { fn ->
            val n = fn.owner.name.asString()
            (n == "startReplaceGroup" || n == "startReplaceableGroup") &&
                fn.owner.parameters.count { it.kind == IrParameterKind.Regular } == 1
        } ?: failHard("Composer.startReplaceGroup(Int) / startReplaceableGroup(Int) not found. " +
            "Compose Runtime API may have changed; update OkTopoi plugin.")

        val endFn = composerClass.functions.firstOrNull { fn ->
            val n = fn.owner.name.asString()
            (n == "endReplaceGroup" || n == "endReplaceableGroup") &&
                fn.owner.parameters.none { it.kind == IrParameterKind.Regular }
        } ?: failHard("Composer.endReplaceGroup() / endReplaceableGroup() not found. " +
            "Compose Runtime API may have changed; update OkTopoi plugin.")

        // androidx.compose.runtime.cache<T>(Composer.(Boolean, () -> T) -> T)
        val cacheId = CallableId(FqName("androidx.compose.runtime"), Name.identifier("cache"))
        val cacheFn = pluginContext.referenceFunctions(cacheId).firstOrNull { sym ->
            val fn = sym.owner
            val regulars = fn.parameters.count { it.kind == IrParameterKind.Regular }
            val hasExtReceiver = fn.parameters.any { it.kind == IrParameterKind.ExtensionReceiver }
            regulars == 2 && hasExtReceiver
        } ?: failHard("androidx.compose.runtime.cache<T>(Composer, Boolean, () -> T) not found. " +
            "Compose Runtime API may have changed; update OkTopoi plugin.")

        // Composer.changed(Any?) — pick the Any?-typed overload (not the primitive specializations).
        val anyClass = pluginContext.irBuiltIns.anyClass
        val changedFn = composerClass.functions.firstOrNull { fn ->
            val owner = fn.owner
            if (owner.name.asString() != "changed") return@firstOrNull false
            val regulars = owner.parameters.filter { it.kind == IrParameterKind.Regular }
            if (regulars.size != 1) return@firstOrNull false
            val t = regulars[0].type
            t.classifierOrNull == anyClass && t.isMarkedNullable()
        } ?: failHard("Composer.changed(Any?) not found. " +
            "Compose Runtime API may have changed; update OkTopoi plugin.")

        return ComposerSymbols(composerClass, startFn, endFn, cacheFn, changedFn)
    }

    private fun failHard(msg: String): Nothing {
        messageCollector.report(CompilerMessageSeverity.ERROR, "[OktopoiPlugin] $msg")
        error("[OktopoiPlugin] $msg")
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val transformed = super.visitCall(expression) as? IrCall ?: return expression
        val callee = transformed.symbol.owner

        val wrapped = callee.parameters.filter {
            it.kind == IrParameterKind.Regular && it.hasAnnotation(wrapInRememberFqName)
        }
        if (wrapped.isEmpty()) return transformed

        val enclosing = currentFunction?.irElement as? IrSimpleFunction ?: return transformed
        if (!enclosing.hasAnnotation(composableFqName)) return transformed

        // Plugin ordering is not guaranteed across modules:
        //   - If Compose ran first, `$composer` is already injected → emit cache-block directly.
        //   - If we run first, `$composer` is absent → emit `remember { arg }` so Compose lowers it.
        val composerParam = enclosing.parameters.firstOrNull { it.name.asString() == "\$composer" }

        for (param in wrapped) {
            val idx = param.indexInParameters
            val arg = transformed.arguments.getOrNull(idx) ?: continue

            // Annotated vararg param: memoize each element individually. The call-site IR
            // shape is `IrVararg(elements)`; wrapping the whole IrVararg as a lambda return
            // value is not well-formed IR, and per-element memoization is strictly more
            // effective anyway (each element's captures get their own keyed remember slot,
            // so one churning source doesn't invalidate the others).
            if (param.varargElementType != null && arg is IrVararg) {
                transformed.arguments[idx] = wrapVarargElements(
                    enclosing = enclosing,
                    composerParam = composerParam,
                    vararg = arg,
                )
                continue
            }

            // If the arg is already a memoized form, skip. Wrapping a Compose-lowered
            // cache block inside another calculation lambda corrupts the slot table:
            // the inner `$composer.startReplaceGroup` / `cache` / `endReplaceGroup` would
            // execute from a deferred context instead of the normal composition flow.
            //
            // Two shapes qualify as "already memoized":
            //  - IrBlock     — Compose has lowered a `remember(...)` into its cache-block form.
            //  - IrCall to `androidx.compose.runtime.remember` — pre-Compose user-written memoization.
            if (isAlreadyMemoized(arg)) continue

            val captures = collectCaptures(arg)
            transformed.arguments[idx] = if (composerParam != null) {
                buildCacheBlock(
                    enclosing = enclosing,
                    composerParam = composerParam,
                    arg = arg,
                    captures = captures,
                    symbols = composerSymbols,
                    callSiteStartOffset = transformed.startOffset,
                )
            } else {
                buildRememberCall(enclosing, arg, captures)
            }
        }
        return transformed
    }

    /**
     * Rebuild the `IrVararg` with each element individually memoized.
     *
     * - `IrSpreadElement` children are left alone: the user has already produced (and
     *   presumably stabilized) the whole sub-array — e.g. `*remember(key) { arrayOf(...) }`.
     * - Regular expression children already in a memoized form ([isAlreadyMemoized])
     *   are left alone.
     * - Other expressions get wrapped via the same cache-block / remember emission the
     *   scalar path uses, keyed on whatever they capture from the enclosing scope.
     */
    private fun wrapVarargElements(
        enclosing: IrSimpleFunction,
        composerParam: IrValueParameter?,
        vararg: IrVararg,
    ): IrVararg {
        val rewritten = vararg.elements.map { element: IrVarargElement ->
            when (element) {
                is IrSpreadElement -> element
                is IrExpression -> {
                    if (isAlreadyMemoized(element)) {
                        element
                    } else {
                        val captures = collectCaptures(element)
                        if (composerParam != null) {
                            buildCacheBlock(
                                enclosing = enclosing,
                                composerParam = composerParam,
                                arg = element,
                                captures = captures,
                                symbols = composerSymbols,
                                callSiteStartOffset = element.startOffset,
                            )
                        } else {
                            buildRememberCall(enclosing, element, captures)
                        }
                    }
                }
                else -> element
            }
        }
        return IrVarargImpl(
            startOffset = vararg.startOffset,
            endOffset = vararg.endOffset,
            type = vararg.type,
            varargElementType = vararg.varargElementType,
            elements = rewritten,
        )
    }

    private fun isAlreadyMemoized(arg: IrExpression): Boolean {
        if (arg is IrBlock) return true
        if (arg is IrCall) {
            val fq = arg.symbol.owner.kotlinFqName.asString()
            if (fq == "androidx.compose.runtime.remember") return true
        }
        return false
    }

    /**
     * Walks the argument subtree to find value references (`IrGetValue`) whose target is
     * declared *outside* the subtree — these are the captures that must become cache keys.
     *
     * Synthetic Compose-injected parameters (names starting with `$`, e.g. `$composer`,
     * `$changed`, `$dirty`, `$default`) are excluded: they're compiler plumbing, not real
     * captures.
     *
     * Captures are tagged as [Capture.isVarargArray] when they reference an enclosing
     * composable's `vararg` parameter — those need element-wise keying because the Array
     * identity is re-synthesized every recomposition.
     */
    private fun collectCaptures(arg: IrExpression): List<Capture> {
        val localDecls = mutableSetOf<IrValueDeclaration>()
        val captures = linkedSetOf<IrValueDeclaration>()

        arg.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) {
                // Params of any lambda/local function inside arg are declared inside.
                declaration.parameters.forEach { localDecls += it }
                super.visitFunction(declaration)
            }

            override fun visitVariable(declaration: IrVariable) {
                localDecls += declaration
                super.visitVariable(declaration)
            }

            override fun visitGetValue(expression: IrGetValue) {
                val target = expression.symbol.owner
                if (target !in localDecls && !target.name.asString().startsWith("\$")) {
                    captures += target
                }
                super.visitGetValue(expression)
            }
        })

        return captures.map { decl ->
            val isVararg = decl is IrValueParameter && decl.varargElementType != null
            Capture(decl, isVarargArray = isVararg)
        }
    }

    // Pre-Compose paths.
    // `remember(calculation)` — zero-key fallback, matches the original plugin behaviour.
    private val rememberNoKeysSymbol: IrSimpleFunctionSymbol by lazy {
        val callableId = CallableId(FqName("androidx.compose.runtime"), Name.identifier("remember"))
        pluginContext.referenceFunctions(callableId).firstOrNull { sym ->
            val regulars = sym.owner.parameters.filter { it.kind == IrParameterKind.Regular }
            regulars.size == 1 && regulars[0].varargElementType == null
        } ?: failHard("androidx.compose.runtime.remember(calculation) overload not found. " +
            "Compose Runtime API may have changed; update OkTopoi plugin.")
    }

    // `remember(vararg keys: Any?, calculation: () -> T)` — keyed variant for N captures.
    private val rememberVarargSymbol: IrSimpleFunctionSymbol by lazy {
        val callableId = CallableId(FqName("androidx.compose.runtime"), Name.identifier("remember"))
        pluginContext.referenceFunctions(callableId).firstOrNull { sym ->
            val regulars = sym.owner.parameters.filter { it.kind == IrParameterKind.Regular }
            regulars.size == 2 &&
                regulars[0].varargElementType != null &&
                regulars[1].varargElementType == null
        } ?: failHard("androidx.compose.runtime.remember(vararg keys, calculation) overload not found. " +
            "Compose Runtime API may have changed; update OkTopoi plugin.")
    }

    // Boolean.or — non-short-circuit OR. Needed because each Composer.changed(...) must be
    // evaluated (it consumes a slot position).
    private val booleanOrSymbol: IrSimpleFunctionSymbol by lazy {
        pluginContext.irBuiltIns.booleanClass.functions.firstOrNull { fn ->
            fn.owner.name.asString() == "or" &&
                fn.owner.parameters.count { it.kind == IrParameterKind.Regular } == 1
        } ?: failHard("kotlin.Boolean.or(Boolean) not found. Stdlib API drift?")
    }

    // jst.oktopoi._oktopoiArrayChangedOr(composer, keys, seed) — runtime helper that
    // walks `keys` calling `composer.changed()` on each element and ORs with `seed`.
    // Used for captures that reference an enclosing composable's `vararg` parameter.
    private val arrayChangedOrSymbol: IrSimpleFunctionSymbol by lazy {
        val callableId = CallableId(
            FqName("jst.oktopoi"),
            Name.identifier("_oktopoiArrayChangedOr"),
        )
        pluginContext.referenceFunctions(callableId).firstOrNull { sym ->
            val regulars = sym.owner.parameters.filter { it.kind == IrParameterKind.Regular }
            regulars.size == 3
        } ?: failHard("jst.oktopoi._oktopoiArrayChangedOr(composer, keys, seed) not found. " +
            "Ensure the okTopoi runtime on the classpath matches this plugin version.")
    }

    private fun buildRememberCall(
        enclosing: IrSimpleFunction,
        arg: IrExpression,
        captures: List<Capture>,
    ): IrExpression {
        val argType = arg.type
        val builder = DeclarationIrBuilder(pluginContext, enclosing.symbol)
        val lambdaExpr = buildCalculationLambda(enclosing, arg)

        return if (captures.isEmpty()) {
            builder.irCall(rememberNoKeysSymbol).apply {
                typeArguments[0] = argType
                val calcParam = rememberNoKeysSymbol.owner.parameters
                    .first { it.kind == IrParameterKind.Regular }
                arguments[calcParam.indexInParameters] = lambdaExpr
            }
        } else {
            val anyNType = pluginContext.irBuiltIns.anyNType
            // Vararg-array captures spread into the keys vararg so `remember(vararg)`
            // keys on each element; scalar captures become single elements.
            val keyElements = captures.map { cap ->
                if (cap.isVarargArray) {
                    IrSpreadElementImpl(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        expression = builder.irGet(cap.decl),
                    )
                } else {
                    builder.irGet(cap.decl)
                }
            }
            val keysVararg = IrVarargImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = pluginContext.irBuiltIns.arrayClass.typeWith(anyNType),
                varargElementType = anyNType,
                elements = keyElements,
            )
            builder.irCall(rememberVarargSymbol).apply {
                typeArguments[0] = argType
                val regulars = rememberVarargSymbol.owner.parameters
                    .filter { it.kind == IrParameterKind.Regular }
                val keysParam = regulars[0]
                val calcParam = regulars[1]
                arguments[keysParam.indexInParameters] = keysVararg
                arguments[calcParam.indexInParameters] = lambdaExpr
            }
        }
    }

    /**
     * Emits:
     *   { $composer.startReplaceGroup(<key>)
     *     val tmp = cache($composer, <invalid>) { <arg> }
     *     $composer.endReplaceGroup()
     *     tmp }
     *
     * Where `<invalid>` is:
     *   - `false` when captures is empty (original behaviour; cache once, never invalidate)
     *   - a chain combining scalar captures and vararg-array captures, built left-to-right
     *     so `changed` calls consume slots deterministically:
     *       * scalar cap: `acc = acc or $composer.changed(cap)`
     *       * vararg cap: `acc = _oktopoiArrayChangedOr($composer, cap, acc)` — the helper
     *         walks the array, calling `changed()` on each element (matches Compose's own
     *         `remember(vararg keys)` pattern).
     */
    private fun buildCacheBlock(
        enclosing: IrSimpleFunction,
        composerParam: IrValueParameter,
        arg: IrExpression,
        captures: List<Capture>,
        symbols: ComposerSymbols,
        callSiteStartOffset: Int,
    ): IrExpression {
        val argType = arg.type
        val builder = DeclarationIrBuilder(pluginContext, enclosing.symbol)

        // Stable Int key: hash of enclosing function name + call site offset.
        val groupKey = (enclosing.name.asString().hashCode() * 31) xor callSiteStartOffset

        val lambdaExpr = buildCalculationLambda(enclosing, arg)

        return builder.irBlock(resultType = argType) {
            // $composer.startReplaceGroup(<key>)
            +irCall(symbols.startReplaceGroup).apply {
                dispatchReceiver = irGet(composerParam)
                val keyParam = symbols.startReplaceGroup.owner.parameters
                    .first { it.kind == IrParameterKind.Regular }
                arguments[keyParam.indexInParameters] = irInt(groupKey)
            }

            // Build `invalid` expression: fold captures left-to-right into an accumulating
            // boolean expression. Initial seed `false` folds away trivially; non-vararg and
            // vararg captures compose the same way so the resulting slot order is stable.
            val invalidExpr: IrExpression = captures.fold(irBoolean(false) as IrExpression) { acc, cap ->
                if (cap.isVarargArray) {
                    // _oktopoiArrayChangedOr($composer, cap, acc)
                    builder.irCall(arrayChangedOrSymbol).apply {
                        val params = arrayChangedOrSymbol.owner.parameters
                            .filter { it.kind == IrParameterKind.Regular }
                        arguments[params[0].indexInParameters] = builder.irGet(composerParam)
                        arguments[params[1].indexInParameters] = builder.irGet(cap.decl)
                        arguments[params[2].indexInParameters] = acc
                    }
                } else {
                    // acc or $composer.changed(cap)
                    val chg = buildChangedCall(symbols.changed, composerParam, cap.decl, builder)
                    builder.irCall(booleanOrSymbol).apply {
                        dispatchReceiver = acc
                        val regular = booleanOrSymbol.owner.parameters
                            .first { it.kind == IrParameterKind.Regular }
                        arguments[regular.indexInParameters] = chg
                    }
                }
            }

            // val tmp = cache<T>($composer, <invalid>) { arg }
            val cacheCall = irCall(symbols.cache).apply {
                typeArguments[0] = argType
                val cacheParams = symbols.cache.owner.parameters
                val extRecv = cacheParams.first { it.kind == IrParameterKind.ExtensionReceiver }
                val regulars = cacheParams.filter { it.kind == IrParameterKind.Regular }
                val invalidParam = regulars[0]
                val blockParam = regulars[1]
                arguments[extRecv.indexInParameters] = irGet(composerParam)
                arguments[invalidParam.indexInParameters] = invalidExpr
                arguments[blockParam.indexInParameters] = lambdaExpr
            }
            val tmp = irTemporary(cacheCall, nameHint = "oktopoi_cached")

            // $composer.endReplaceGroup()
            +irCall(symbols.endReplaceGroup).apply {
                dispatchReceiver = irGet(composerParam)
            }

            // Yield tmp
            +irGet(tmp)
        }
    }

    private fun buildChangedCall(
        changedSym: IrSimpleFunctionSymbol,
        composerParam: IrValueParameter,
        capture: IrValueDeclaration,
        builder: DeclarationIrBuilder,
    ): IrExpression {
        return builder.irCall(changedSym).apply {
            dispatchReceiver = builder.irGet(composerParam)
            val regular = changedSym.owner.parameters
                .first { it.kind == IrParameterKind.Regular }
            arguments[regular.indexInParameters] = builder.irGet(capture)
        }
    }

    /** Factor out the `() -> argType { return arg }` lambda — both emission paths need it. */
    private fun buildCalculationLambda(
        enclosing: IrSimpleFunction,
        arg: IrExpression,
    ): IrFunctionExpression {
        val argType = arg.type
        val lambdaFn = pluginContext.irFactory.buildFun {
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = argType
            modality = Modality.FINAL
            isSuspend = false
            startOffset = UNDEFINED_OFFSET
            endOffset = UNDEFINED_OFFSET
        }.also { fn ->
            fn.parent = enclosing
            fn.body = DeclarationIrBuilder(pluginContext, fn.symbol).irBlockBody {
                +irReturn(arg)
            }
        }
        val lambdaType = pluginContext.irBuiltIns.functionN(0).typeWith(argType)
        return IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = lambdaType,
            function = lambdaFn,
            origin = IrStatementOrigin.LAMBDA,
        )
    }
}
