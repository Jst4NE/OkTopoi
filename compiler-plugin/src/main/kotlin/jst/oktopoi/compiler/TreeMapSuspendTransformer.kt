package jst.oktopoi.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Transformer that detects TreeMap instance access in suspend contexts and transforms them
 * to use the suspend view for optimal performance.
 *
 * This transformer implements instance-level transformation, where entire TreeMap variable
 * references are replaced with suspend view access in suspend contexts, rather than
 * transforming individual method calls.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class TreeMapSuspendTransformer(
    private val pluginContext: IrPluginContext
) : IrElementTransformerVoidWithContext() {

    private val treeMapFqName = FqName("jst.oktopoi.TreeMap")
    private val treeMapClassId: ClassId = ClassId.topLevel(treeMapFqName)
    private val treeMapSymbol: IrClassSymbol? = pluginContext.referenceClass(treeMapClassId)

    // Resolve view class symbols from TreeMap properties (blocking/suspend)
    private val suspendViewSymbol: IrClassSymbol? by lazy { resolveViewClassSymbol("suspend") }
    private val blockingViewSymbol: IrClassSymbol? by lazy { resolveViewClassSymbol("blocking") }


    /**
     * Transform function calls for TreeMap methods to eliminate delegation overhead.
     * - In suspend contexts: treeMap.put() → treeMap.suspend.put()
     * - In non-suspend contexts: treeMap.put() → treeMap.blocking.put()
     */
    override fun visitCall(expression: IrCall): IrExpression {
        val transformed = super.visitCall(expression)

        if (transformed is IrCall) {
            val receiver = transformed.dispatchReceiver
            if (receiver != null) {
                val recvType = receiver.type

                // Never transform calls where the receiver is already a view
                if (isTreeMapViewType(recvType)) return transformed


                if (isTreeMapType(recvType)) {
                    val inSuspend = isInSuspendContext()

                    if (inSuspend) {
                        // Special-case: size property getter → suspend.size()
                        redirectSizeGetterToSuspendIfApplicable(transformed)?.let { return it }
                        // Direct TreeMap API → suspend view
                        if (isDirectTreeMapMethodCall(transformed)) {
                            redirectToViewMethodCall(transformed, "suspend")?.let { return it }
                        }
                        // (Legacy fallback removed)
                    } else {
                        // Non-suspend: direct TreeMap API → blocking view (including size/getters)
                        if (isDirectTreeMapMethodCall(transformed)) {
                            redirectToViewMethodCall(transformed, "blocking")?.let { return it }
                        }
                    }
                }
            }
        }

        return transformed
    }


    /**
     * Check if the given type is TreeMap or a subclass.
     * This logic should be kept consistent with TreeMapDetectionUtils.isTreeMapClassId() 
     * used in the FIR phase for warning suppression.
     */
    private fun isTreeMapType(type: IrType): Boolean {
        val tm = treeMapSymbol ?: return false
        return try {
            type.isSubtypeOfClass(tm)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isTreeMapViewType(type: IrType): Boolean {
        val sv = suspendViewSymbol
        val bv = blockingViewSymbol
        val suspendMatch = sv?.let { runCatching { type.isSubtypeOfClass(it) }.getOrDefault(false) } ?: false
        val blockingMatch = bv?.let { runCatching { type.isSubtypeOfClass(it) }.getOrDefault(false) } ?: false
        return suspendMatch || blockingMatch
    }

    /**
     * Check if we're currently in a suspend function context.
     * Handles nested scopes where suspend/blocking contexts may switch.
     */
    private fun isInSuspendContext(): Boolean {
        // Walk up the scope stack to find the most recent function context
        allScopes.reversed().forEach { scope ->
            when (val owner = scope.scope.scopeOwnerSymbol.owner) {
                is IrFunction -> {
                    // Found a function - check if it's suspend
                    return owner.isSuspend
                }
                is IrClass -> {
                    // Skip class scopes, continue looking for function
                    return@forEach
                }
            }
        }

        return false
    }

    /**
     * Generate IR code to access the suspend view of a TreeMap instance.
     * Transforms: treeMapInstance → treeMapInstance.suspend
     */
    private fun generateSuspendViewAccess(originalExpression: IrExpression): IrExpression {
        // Resolve the runtime class from the expression type (works for subclasses too)
        val ownerClass = (originalExpression.type.classifierOrNull as? IrClassSymbol)?.owner
            ?: return originalExpression
        
        // Find the 'suspend' property in the class hierarchy
        val suspendProperty = findPropertyInHierarchy(ownerClass, "suspend") ?: return originalExpression
        val suspendGetter = suspendProperty.getter ?: return originalExpression
        
        // Create IR call to access the suspend property
        return pluginContext.irBuiltIns.run {
            DeclarationIrBuilder(pluginContext, suspendGetter.symbol).irCall(suspendGetter).apply {
                dispatchReceiver = originalExpression
            }
        }
    }

    /**
     * Detect if this is a property getter call for "size" on a TreeMap and redirect
     * to the suspend view's size() function.
     *
     * Returns a new IrCall on success, or null if this is not a size getter or transformation fails.
     */
    private fun redirectSizeGetterToSuspendIfApplicable(call: IrCall): IrCall? {
        // 1) Confirm this is a property getter for "size"
        val function = call.symbol.owner
        
        // Strictly match the conventional getter name
        val functionName = function.name.asString()
        val hasNoRegularParams = function.parameters.none {
            it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context
        }
        if (functionName != "getSize" || !hasNoRegularParams) return null

        // 2) Must have a dispatch receiver that is TreeMap or subclass
        val originalReceiver = call.dispatchReceiver ?: return null
        if (!isTreeMapType(originalReceiver.type)) return null

        // 3) Resolve the suspend view getter: <receiver>.suspend
        val ownerClass = (originalReceiver.type.classifierOrNull as? IrClassSymbol)?.owner ?: return null
        val suspendProperty = findPropertyInHierarchy(ownerClass, "suspend") ?: return null
        val suspendGetter = suspendProperty.getter ?: return null

        // 4) Find SuspendTreeMapView.size() (0 regular/context parameters)
        val suspendViewClassSymbol = suspendGetter.returnType.classifierOrNull as? IrClassSymbol ?: return null
        val suspendViewClass = suspendViewClassSymbol.owner
        val targetFunction = suspendViewClass.functions.firstOrNull { f ->
            f.name.asString() == "size" &&
            f.parameters.count { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context } == 0
        } ?: return null

        // 5) Build: <originalReceiver>.suspend.size()
        return pluginContext.irBuiltIns.run {
            // Build receiver.suspend
            val suspendViewExpr = DeclarationIrBuilder(pluginContext, suspendGetter.symbol)
                .irCall(suspendGetter).apply {
                    dispatchReceiver = originalReceiver
                }

            // Build suspendView.size()
            DeclarationIrBuilder(pluginContext, targetFunction.symbol)
                .irCall(targetFunction).apply {
                    dispatchReceiver = suspendViewExpr
                } as IrCall
        }
    }



    /**
     * Check if this is a direct TreeMap method call (not a view method call).
     * Direct calls are those defined on TreeMap itself that delegate to views.
     */
    private fun isDirectTreeMapMethodCall(call: IrCall): Boolean {
        val callee = call.symbol.owner as? IrSimpleFunction ?: return false
        return isDeclaredOnOrOverridesTreeMap(callee)
    }

    private fun isDeclaredOnOrOverridesTreeMap(function: IrSimpleFunction): Boolean {
        val tmOwner = treeMapSymbol?.owner ?: return false
        fun declaredOnTreeMap(f: IrSimpleFunction): Boolean = (f.parent as? IrClass)?.symbol == tmOwner.symbol
        if (declaredOnTreeMap(function)) return true
        // Traverse override chain
        val queue: ArrayDeque<IrSimpleFunction> = ArrayDeque()
        val seen = mutableSetOf<IrSimpleFunction>()
        queue.add(function)
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            if (!seen.add(f)) continue
            if (declaredOnTreeMap(f)) return true
            f.overriddenSymbols.forEach { queue.addLast(it.owner) }
        }
        return false
    }

    /**
     * Redirect a direct TreeMap method call to the specified view (blocking or suspend).
     * Example: treeMap.put(k,v) → treeMap.blocking.put(k,v) or treeMap.suspend.put(k,v)
     */
    private fun redirectToViewMethodCall(originalCall: IrCall, viewPropertyName: String): IrCall? {
        val originalFunction = originalCall.symbol.owner
        val originalDispatch = originalCall.dispatchReceiver ?: return null

        // Get the view property (blocking or suspend)
        val ownerClass = (originalDispatch.type.classifierOrNull as? IrClassSymbol)?.owner ?: return null
        val viewProperty = findPropertyInHierarchy(ownerClass, viewPropertyName) ?: return null
        val viewGetter = viewProperty.getter ?: return null
        val viewClassSymbol = viewProperty.getter?.returnType?.classifierOrNull as? IrClassSymbol ?: return null
        val viewClass = viewClassSymbol.owner

        // Find matching function by name, parameter kinds and (preferably) types in the view
        val originalName = originalFunction.name
        val targetFunction = findMatchingViewFunction(originalFunction, viewClass)
            ?: return null

        // Precompute original Regular/Context parameters for argument mapping
        val origRegularParams = originalFunction.parameters.filter { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context }

        // Build new call to the view function
        return pluginContext.irBuiltIns.run {
            val builder = DeclarationIrBuilder(pluginContext, targetFunction.symbol)
            val newCall = builder.irCall(targetFunction).apply {
                // dispatch receiver becomes `<originalDispatch>.viewPropertyName`
                dispatchReceiver = DeclarationIrBuilder(pluginContext, viewGetter.symbol).irCall(viewGetter).apply {
                    dispatchReceiver = originalDispatch
                }

                // Copy regular/context arguments by position
                val newRegularParams = targetFunction.parameters.filter { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context }
                origRegularParams.zip(newRegularParams).forEach { (origP, newP) ->
                    (this as IrMemberAccessExpression<*>).arguments[newP.indexInParameters] = (originalCall as IrMemberAccessExpression<*>).arguments[origP.indexInParameters]
                }

                // Copy type arguments if arity matches
                val origTypeParams = originalFunction.typeParameters
                val newTypeParams = targetFunction.typeParameters
                val typeParamCount = minOf(origTypeParams.size, newTypeParams.size)
                for (i in 0 until typeParamCount) {
                    typeArguments[i] = originalCall.typeArguments[i]
                }
            }
            newCall as? IrCall
        }
    }

    private fun findPropertyInHierarchy(start: IrClass, name: String): IrProperty? {
        val visited = mutableSetOf<IrClass>()
        val queue: ArrayDeque<IrClass> = ArrayDeque()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!visited.add(c)) continue
            c.properties.firstOrNull { it.name.asString() == name }?.let { return it }
            c.superTypes.forEach { st ->
                val sup = (st.classifierOrNull as? IrClassSymbol)?.owner
                if (sup != null) queue.addLast(sup)
            }
        }
        return null
    }

    private fun findMatchingViewFunction(originalFunction: IrFunction, viewClass: IrClass): IrSimpleFunction? {
        val origName = originalFunction.name
        val origParams = originalFunction.parameters.filter { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context }
        val candidates = viewClass.functions.filter { f ->
            f.name == origName &&
            f.parameters.count { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context } == origParams.size
        }.toList()
        if (candidates.isEmpty()) return null

        // Prefer exact type matches (by IrType structural equality) on Regular/Context params
        fun typesMatch(target: IrSimpleFunction): Boolean {
            val targetParams = target.parameters.filter { it.kind == IrParameterKind.Regular || it.kind == IrParameterKind.Context }
            return origParams.zip(targetParams).all { (o, t) -> o.type == t.type }
        }

        return candidates.firstOrNull { typesMatch(it) } ?: candidates.firstOrNull()
    }

    private fun resolveViewClassSymbol(viewPropertyName: String): IrClassSymbol? {
        val tmOwner = treeMapSymbol?.owner ?: return null
        val prop = tmOwner.properties.firstOrNull { it.name.asString() == viewPropertyName } ?: return null
        return prop.getter?.returnType?.classifierOrNull as? IrClassSymbol
    }
}
