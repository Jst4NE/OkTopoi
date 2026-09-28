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
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
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
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
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
 * A local delegated property (`var query by remember { mutableStateOf("") }`) is read through
 * its getter, not a variable reference, so it is keyed on its **current value**: the getter is
 * called at the call site. That read also subscribes the composition to the state, so a change
 * recomposes the caller, invalidates the cache and hands the callee a new argument instance.
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
 *
 * ## Composition-time code nested inside arguments (hoisting)
 *
 * The wrap moves the argument into a calculation lambda that only runs on cache miss. That is
 * unsound when the argument *contains* code that must execute in normal composition flow every
 * time, because slot consumption would differ between the miss and hit paths and the slot
 * cursor misaligns on recomposition (observed in the wild as
 * `ClassCastException: ...$$Lambda cannot be cast to java.util.Comparator` when the previous
 * slot's lambda was read back where the comparator was cached). Two sources of such code:
 *
 *  - **Post-Compose:** Compose's lambda memoization has already lowered nested lambda
 *    expressions into `$composer`-touching cache sequences — e.g. the selector lambdas of the
 *    non-inline `compareBy({ a }, { b })` inside an `entryComparator` argument.
 *  - **Pre-Compose:** nested lambda expressions (except those passed to inlinable parameters
 *    of inline functions) and nested `@Composable` calls will be lowered into exactly that
 *    shape after Compose runs over the plugin's `remember { arg }` output.
 *
 * Such nested units are **hoisted**: each becomes a temporary evaluated unconditionally before
 * the wrap, the argument is rewritten to reference the temporaries, and the temporaries then
 * participate as cache keys of the outer wrap (already-lowered composer blocks and composable
 * calls self-cache and are evaluated as-is; raw lambdas get their own keyed `remember`).
 * Arguments that are themselves composition-bound at the top level (composable calls, direct
 * `$composer` use) are passed through untouched. If a nested unit captures a local declared
 * inside the same argument expression it cannot be hoisted — the build fails with guidance.
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
     * [decl] is either a value declaration (variable or parameter), keyed on its value, or the
     * getter of a local delegated property declared outside the argument, keyed on what the
     * getter returns (see [readCapture]).
     *
     * [isVarargArray] is set when the captured declaration is an enclosing composable's `vararg`
     * parameter, whose Array identity is re-synthesized every recomposition. Such captures need
     * element-wise keying rather than identity keying (see [buildCacheBlock]).
     */
    private data class Capture(
        val decl: IrDeclarationWithName,
        val isVarargArray: Boolean,
    )

    /** The key expression for [cap]: a read of the captured value, or a call of the getter. */
    private fun readCapture(builder: DeclarationIrBuilder, cap: Capture): IrExpression =
        when (val decl = cap.decl) {
            is IrValueDeclaration -> builder.irGet(decl)
            is IrSimpleFunction -> builder.irCall(decl.symbol)
            else -> error("[OktopoiPlugin] Unexpected capture ${decl.name}")
        }

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

            transformed.arguments[idx] = wrapArgument(
                enclosing = enclosing,
                composerParam = composerParam,
                arg = arg,
                callSiteStartOffset = transformed.startOffset,
            )
        }
        return transformed
    }

    /**
     * Memoizes a single argument expression, hoisting any nested composition-time units first
     * (see the class doc). Returns the argument unchanged when it is already memoized or is
     * itself composition-bound at the top level.
     */
    private fun wrapArgument(
        enclosing: IrSimpleFunction,
        composerParam: IrValueParameter?,
        arg: IrExpression,
        callSiteStartOffset: Int,
    ): IrExpression {
        // If the arg is already a memoized form, skip. Wrapping a Compose-lowered
        // cache block inside another calculation lambda corrupts the slot table:
        // the inner `$composer.startReplaceGroup` / `cache` / `endReplaceGroup` would
        // execute from a deferred context instead of the normal composition flow.
        //
        // Two shapes qualify as "already memoized":
        //  - IrBlock     — Compose has lowered a `remember(...)` into its cache-block form.
        //  - IrCall to `androidx.compose.runtime.remember` — pre-Compose user-written memoization.
        if (isAlreadyMemoized(arg)) {
            if (arg is IrBlock && composerParam != null) {
                keyCacheOnDelegatedReads(enclosing, composerParam, arg)
            }
            return arg
        }

        // Pre-Compose: a composable call as the whole argument manages its own slots and must
        // stay in normal composition flow — never defer it into a calculation lambda.
        if (composerParam == null && arg is IrCall &&
            arg.symbol.owner.hasAnnotation(composableFqName)
        ) return arg

        val hoistables = collectHoistables(arg, composerParam)

        // A bare `$composer` read can only surface as a hoist candidate when the argument
        // itself is composition-bound at the top level (e.g. a lowered composable call taking
        // `$composer` directly) — leave such arguments untouched.
        if (hoistables.any { it.expr is IrGetValue }) return arg

        if (hoistables.isNotEmpty()) {
            validateHoistScopes(enclosing, arg, hoistables)
            return buildHoistedWrap(enclosing, composerParam, arg, hoistables, callSiteStartOffset)
        }

        val captures = collectCaptures(arg)
        return if (composerParam != null) {
            buildCacheBlock(
                enclosing = enclosing,
                composerParam = composerParam,
                arg = arg,
                captures = captures,
                symbols = composerSymbols,
                callSiteStartOffset = callSiteStartOffset,
            )
        } else {
            buildRememberCall(enclosing, arg, captures)
        }
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
                is IrExpression -> wrapArgument(
                    enclosing = enclosing,
                    composerParam = composerParam,
                    arg = element,
                    callSiteStartOffset = element.startOffset,
                )
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

    /**
     * Post-Compose, a lambda argument arrives already memoized by Compose as
     * `cache($composer, <invalid>) { <lambda> }`. Compose leaves a read of a local delegated
     * property (`var query by mutableStateOf("")`) out of `<invalid>`: the lambda reads the
     * current value when invoked, so for Compose it never needs recreating. A callee that
     * restarts on the argument's identity (e.g. `LaunchedEffect(filter)`) then never sees the
     * value change. Such reads are ORed into `<invalid>` as `$composer.changed(<value>)` — the
     * same slot-consuming shape Compose emits for its own captures, evaluated on every
     * composition.
     *
     * Reads that no recognisable `cache` call can be keyed on are reported as a warning.
     */
    private fun keyCacheOnDelegatedReads(
        enclosing: IrSimpleFunction,
        composerParam: IrValueParameter,
        block: IrBlock,
    ) {
        val cacheCalls = mutableListOf<IrCall>()
        block.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                if (expression.symbol == composerSymbols.cache) cacheCalls += expression
                else super.visitCall(expression)
            }

            override fun visitFunctionExpression(expression: IrFunctionExpression) {
                // Invoke-time code — not part of the memoization shape.
            }
        })

        val symbols = composerSymbols
        val regulars = symbols.cache.owner.parameters.filter { it.kind == IrParameterKind.Regular }
        val invalidParam = regulars[0]
        val blockParam = regulars[1]
        val keyed = mutableSetOf<IrDeclarationWithName>()
        for (call in cacheCalls) {
            val calculation = call.arguments[blockParam.indexInParameters] ?: continue
            val getters = collectCaptures(calculation).filter { it.decl is IrSimpleFunction }
            if (getters.isEmpty()) continue
            val builder = DeclarationIrBuilder(pluginContext, enclosing.symbol)
            val invalid = call.arguments[invalidParam.indexInParameters] ?: continue
            call.arguments[invalidParam.indexInParameters] = getters.fold(invalid) { acc, cap ->
                builder.irCall(booleanOrSymbol).apply {
                    dispatchReceiver = acc
                    val regular = booleanOrSymbol.owner.parameters
                        .first { it.kind == IrParameterKind.Regular }
                    arguments[regular.indexInParameters] =
                        buildChangedCall(symbols.changed, composerParam, readCapture(builder, cap), builder)
                }
            }
            getters.mapTo(keyed) { it.decl }
        }

        val unkeyed = collectCaptures(block).map { it.decl }
            .filter { it is IrSimpleFunction && it !in keyed }
        if (unkeyed.isNotEmpty()) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "[OktopoiPlugin] @WrapInRemember argument in ${enclosing.kotlinFqName} reads " +
                    "${unkeyed.joinToString { it.name.asString() }} but is already memoized in a " +
                    "shape the plugin cannot key; the callee will not see changes to it. Wrap the " +
                    "argument in remember(<those values>) { ... } manually.",
            )
        }
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
     * A nested composition-time unit found inside a wrapped argument.
     *
     * [selfCaching] units (Compose-lowered `$composer`-touching subtrees, pre-Compose
     * composable calls) already manage their own slot caching — they are hoisted into a plain
     * temporary and evaluated as-is. Non-self-caching units (raw lambda allocations Compose
     * would memoize after us) get their own keyed `remember` around the temporary initializer
     * so their identity stays stable across recompositions.
     */
    private data class Hoistable(
        val expr: IrExpression,
        val selfCaching: Boolean,
    )

    /** True when any `IrGetValue` in [element]'s subtree targets [decl]. */
    private fun referencesValue(element: IrElement, decl: IrValueDeclaration): Boolean {
        var found = false
        element.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (!found) element.acceptChildrenVoid(this)
            }

            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol.owner == decl) found = true
            }
        })
        return found
    }

    /**
     * Finds the maximal nested composition-time units inside [arg] (excluding [arg] itself):
     *
     *  - **Post-Compose** ([composerParam] non-null): any subtree that reads `$composer` —
     *    Compose-lowered memoization blocks, composable calls, etc. Maximal: once a subtree is
     *    selected, its children are not inspected.
     *  - **Pre-Compose**: calls to `@Composable` functions (they will lower to composer slot
     *    traffic), and lambda expressions not passed to an inlinable parameter of an inline
     *    function (Compose will memoize exactly those after the plugin runs).
     *
     * Lambda bodies are never descended into: allocations there happen at invoke time, not
     * composition time, and must not be hoisted out of their scope.
     */
    private fun collectHoistables(
        arg: IrExpression,
        composerParam: IrValueParameter?,
    ): List<Hoistable> {
        val result = mutableListOf<Hoistable>()
        val exemptInlineArgs = mutableSetOf<IrFunctionExpression>()

        arg.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitExpression(expression: IrExpression) {
                if (expression !== arg && composerParam != null &&
                    referencesValue(expression, composerParam)
                ) {
                    result += Hoistable(expression, selfCaching = true)
                    return
                }
                super.visitExpression(expression)
            }

            override fun visitVararg(expression: IrVararg) {
                // A vararg construction allocates a fresh array each evaluation — hoisting
                // it whole would make the outer cache key identity-unstable (invalidating
                // the cache every recomposition). Stay transparent; the individual elements
                // are considered instead.
                expression.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                if (expression !== arg && composerParam == null &&
                    expression.symbol.owner.hasAnnotation(composableFqName)
                ) {
                    result += Hoistable(expression, selfCaching = true)
                    return
                }
                val callee = expression.symbol.owner
                if (callee.isInline) {
                    for (p in callee.parameters) {
                        if (p.kind != IrParameterKind.Regular || p.isNoinline) continue
                        (expression.arguments.getOrNull(p.indexInParameters) as? IrFunctionExpression)
                            ?.let { exemptInlineArgs += it }
                    }
                }
                super.visitCall(expression)
            }

            override fun visitFunctionExpression(expression: IrFunctionExpression) {
                if (expression !== arg && composerParam == null &&
                    expression !in exemptInlineArgs
                ) {
                    result += Hoistable(expression, selfCaching = false)
                }
                // Never descend into lambda bodies (invoke-time code).
            }
        })
        return result
    }

    /**
     * All value declarations (variables, function/lambda parameters) within [element], plus the
     * getters of local delegated properties declared there.
     */
    private fun collectValueDeclarations(element: IrElement): Set<IrDeclarationWithName> {
        val decls = mutableSetOf<IrDeclarationWithName>()
        element.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) {
                declaration.parameters.forEach { decls += it }
                super.visitFunction(declaration)
            }

            override fun visitVariable(declaration: IrVariable) {
                decls += declaration
                super.visitVariable(declaration)
            }

            override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty) {
                decls += declaration.getter
                super.visitLocalDelegatedProperty(declaration)
            }
        })
        return decls
    }

    /**
     * A hoistable that captures a value declared inside the same argument expression (but
     * outside itself) cannot be moved above the argument — fail the build with guidance
     * instead of silently emitting slot-corrupting code.
     */
    private fun validateHoistScopes(
        enclosing: IrSimpleFunction,
        arg: IrExpression,
        hoistables: List<Hoistable>,
    ) {
        val declsInArg = collectValueDeclarations(arg)
        for (h in hoistables) {
            val broken = collectCaptures(h.expr).firstOrNull { it.decl in declsInArg }
            if (broken != null) {
                failHard(
                    "@WrapInRemember argument in ${enclosing.kotlinFqName} contains " +
                        "composition-time code capturing a local ('${broken.decl.name}') " +
                        "declared inside the same argument expression — it cannot be hoisted. " +
                        "Wrap the argument in remember(...) manually.",
                )
            }
        }
    }

    /**
     * Emits:
     *   { val t1 = <hoistable 1>; val t2 = ...; <wrap of arg with hoistables replaced by t_i> }
     *
     * Self-caching hoistables are evaluated as-is (unconditionally, preserving their slot
     * traffic every composition); raw lambdas are wrapped in their own keyed `remember`. The
     * temporaries are picked up by [collectCaptures] on the rewritten argument and become
     * cache keys of the outer wrap.
     */
    private fun buildHoistedWrap(
        enclosing: IrSimpleFunction,
        composerParam: IrValueParameter?,
        arg: IrExpression,
        hoistables: List<Hoistable>,
        callSiteStartOffset: Int,
    ): IrExpression {
        val builder = DeclarationIrBuilder(pluginContext, enclosing.symbol)
        return builder.irBlock(resultType = arg.type) {
            val replacements = HashMap<IrExpression, IrValueDeclaration>()
            for (h in hoistables) {
                val initializer = if (h.selfCaching) h.expr
                else buildRememberCall(enclosing, h.expr, collectCaptures(h.expr))
                replacements[h.expr] = irTemporary(initializer, nameHint = "oktopoiHoisted")
            }

            val rewritten = arg.transform(object : IrElementTransformerVoid() {
                override fun visitExpression(expression: IrExpression): IrExpression {
                    replacements[expression]?.let { return irGet(it) }
                    return super.visitExpression(expression)
                }

                override fun visitFunctionExpression(expression: IrFunctionExpression): IrExpression {
                    replacements[expression]?.let { return irGet(it) }
                    return expression // don't rewrite inside lambda bodies
                }
            }, null) as IrExpression

            // Safety net: every composer-touching subtree must have been hoisted, otherwise
            // deferring the rest would still corrupt the slot table.
            if (composerParam != null && referencesValue(rewritten, composerParam)) {
                failHard(
                    "@WrapInRemember argument in ${enclosing.kotlinFqName} still references " +
                        "\$composer after hoisting — unsupported shape. Wrap the argument in " +
                        "remember(...) manually.",
                )
            }

            val captures = collectCaptures(rewritten)
            +(if (composerParam != null) {
                buildCacheBlock(
                    enclosing = enclosing,
                    composerParam = composerParam,
                    arg = rewritten,
                    captures = captures,
                    symbols = composerSymbols,
                    callSiteStartOffset = callSiteStartOffset,
                )
            } else {
                buildRememberCall(enclosing, rewritten, captures)
            })
        }
    }

    /**
     * Walks the argument subtree to find value references (`IrGetValue`) whose target is
     * declared *outside* the subtree — these are the captures that must become cache keys.
     *
     * Synthetic Compose-injected parameters (names starting with `$`, e.g. `$composer`,
     * `$changed`, `$dirty`, `$default`) are excluded: they're compiler plumbing, not real
     * captures.
     *
     * Reads of a local delegated property declared outside the subtree (`IrCall` of its getter,
     * origin `GET_LOCAL_PROPERTY`) are captures too, keyed on the getter's result. Without them
     * `{ query.isEmpty() || ... }` over `var query by mutableStateOf("")` would have no key and
     * be cached forever, so a callee keyed on the argument's identity never sees the change.
     *
     * Captures are tagged as [Capture.isVarargArray] when they reference an enclosing
     * composable's `vararg` parameter — those need element-wise keying because the Array
     * identity is re-synthesized every recomposition.
     */
    private fun collectCaptures(arg: IrExpression): List<Capture> {
        val localDecls = mutableSetOf<IrDeclarationWithName>()
        val captures = linkedSetOf<IrDeclarationWithName>()

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

            override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty) {
                localDecls += declaration.getter
                super.visitLocalDelegatedProperty(declaration)
            }

            override fun visitGetValue(expression: IrGetValue) {
                val target = expression.symbol.owner
                if (target !in localDecls && !target.name.asString().startsWith("\$")) {
                    captures += target
                }
                super.visitGetValue(expression)
            }

            override fun visitCall(expression: IrCall) {
                val getter = expression.symbol.owner
                if (expression.origin == IrStatementOrigin.GET_LOCAL_PROPERTY &&
                    getter.parameters.isEmpty() && getter !in localDecls
                ) {
                    captures += getter
                }
                super.visitCall(expression)
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
                        expression = readCapture(builder, cap),
                    )
                } else {
                    readCapture(builder, cap)
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
                        arguments[params[1].indexInParameters] = readCapture(builder, cap)
                        arguments[params[2].indexInParameters] = acc
                    }
                } else {
                    // acc or $composer.changed(cap)
                    val chg = buildChangedCall(symbols.changed, composerParam, readCapture(builder, cap), builder)
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
        key: IrExpression,
        builder: DeclarationIrBuilder,
    ): IrExpression {
        return builder.irCall(changedSym).apply {
            dispatchReceiver = builder.irGet(composerParam)
            val regular = changedSym.owner.parameters
                .first { it.kind == IrParameterKind.Regular }
            arguments[regular.indexInParameters] = key
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
