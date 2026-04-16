@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package jst.oktopoi.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.createTmpVariable
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.FqName

class OktopoiTransformer(
    private val pluginContext: IrPluginContext,
    private val messageCollector: MessageCollector,
    private val eSymbol: IrClassSymbol?,
    private val esSymbol: IrClassSymbol?
) : IrElementTransformerVoidWithContext() {

    private val newInstanceAnnotation = FqName("jst.oktopoi.OktopoiNewInstanceFactory")

    // Memoization caches for member lookups (per class symbol, per name)
    private val propertyCache = mutableMapOf<Pair<IrClassSymbol, String>, IrProperty?>()
    private val functionCache = mutableMapOf<Pair<IrClassSymbol, String>, IrSimpleFunction?>()

    override fun visitPropertyNew(declaration: IrProperty): IrStatement {
        val backingField = declaration.backingField ?: return super.visitPropertyNew(declaration)
        val initializer = backingField.initializer?.expression ?: return super.visitPropertyNew(declaration)

        val transformedInitializer = when (initializer) {
            is IrCall -> transformCall(initializer, declaration)
            is IrConstructorCall -> transformConstructor(initializer, declaration)
            is IrTypeOperatorCall -> {
                val argument = initializer.argument
                when (argument) {
                    is IrCall -> transformCall(argument, declaration)
                    is IrConstructorCall -> transformConstructor(argument, declaration)
                    else -> null
                }
            }
            else -> null
        }

        if (transformedInitializer != null) {
            backingField.initializer = pluginContext.irFactory.createExpressionBody(transformedInitializer)
        }

        return super.visitPropertyNew(declaration)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun transformCall(call: IrCall, property: IrProperty): IrExpression? {
        val function = call.symbol.owner

        // Only transform calls to functions annotated as new-instance factories
        if (!function.hasAnnotation(newInstanceAnnotation)) return null

        // Ensure the return type is E or Es (or subclass)
        if (!call.type.isSubtypeOfEorEs()) return null

        val containingClass = property.parent as? IrClass
        val className = containingClass?.name?.asString() ?: "Unknown"
        val propertyName = property.name.asString()

        return pluginContext.irBuiltIns.run {
            irBuilder(call.symbol).irBlock {
                val tempVar = createTmpVariable(call, nameHint = "${propertyName}_init")

                // Get the class of the created object
                val resultClass = call.type.classOrNull?.owner
                    ?: error("Cannot find class for type ${call.type}")

                // Find the property setters in the class hierarchy (handles base E/Es members)
                val callingClassNameProperty: IrProperty = findPropertyInHierarchyCached(resultClass, "callingClassName")
                    ?: error("Property callingClassName not found in ${resultClass.name}")
                val callingClassNameSetter = callingClassNameProperty.setter
                    ?: error("Setter not found for property ${callingClassNameProperty.name}")

                val propertyNameProperty: IrProperty = findPropertyInHierarchyCached(resultClass, "propertyName")
                    ?: error("Property propertyName not found in ${resultClass.name}")
                val propertyNameSetter = propertyNameProperty.setter
                    ?: error("Setter not found for property ${propertyNameProperty.name}")

                // Generate: tempVar.callingClassName = "ClassName"
                val callingParam = callingClassNameSetter.parameters.first { it.kind == IrParameterKind.Regular }
                +irCall(callingClassNameSetter).apply {
                    dispatchReceiver = irGet(tempVar)
                    arguments[callingParam.indexInParameters] = irString(className)
                }

                // Generate: tempVar.propertyName = "propertyName"
                val propertyParam = propertyNameSetter.parameters.first { it.kind == IrParameterKind.Regular }
                +irCall(propertyNameSetter).apply {
                    dispatchReceiver = irGet(tempVar)
                    arguments[propertyParam.indexInParameters] = irString(propertyName)
                }

                // Find setup() function in hierarchy
                val setupFunction = findFunctionInHierarchyCached(resultClass, "setup")
                    ?: error("Function setup() not found in ${resultClass.name}")

                // Generate: tempVar.setup()
                +irCall(setupFunction).apply {
                    dispatchReceiver = irGet(tempVar)
                }

                // Return the initialized object
                +irGet(tempVar)
            }
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun transformConstructor(ctor: IrConstructorCall, property: IrProperty): IrExpression? {
        // Ensure constructed type is E or Es (or subclass)
        if (!ctor.type.isSubtypeOfEorEs()) return null

        val containingClass = property.parent as? IrClass
        val className = containingClass?.name?.asString() ?: "Unknown"
        val propertyName = property.name.asString()

        return pluginContext.irBuiltIns.run {
            irBuilder(ctor.symbol).irBlock {
                val tempVar = createTmpVariable(ctor, nameHint = "${propertyName}_init")

                val resultClass = ctor.type.classOrNull?.owner
                    ?: error("Cannot find class for type ${ctor.type}")

                val callingClassNameProperty: IrProperty = findPropertyInHierarchyCached(resultClass, "callingClassName")
                    ?: error("Property callingClassName not found in ${resultClass.name}")
                val callingClassNameSetter = callingClassNameProperty.setter
                    ?: error("Setter not found for property ${callingClassNameProperty.name}")

                val propertyNameProperty: IrProperty = findPropertyInHierarchyCached(resultClass, "propertyName")
                    ?: error("Property propertyName not found in ${resultClass.name}")
                val propertyNameSetter = propertyNameProperty.setter
                    ?: error("Setter not found for property ${propertyNameProperty.name}")

                val callingParam = callingClassNameSetter.parameters.first { it.kind == IrParameterKind.Regular }
                +irCall(callingClassNameSetter).apply {
                    dispatchReceiver = irGet(tempVar)
                    arguments[callingParam.indexInParameters] = irString(className)
                }

                val propertyParam = propertyNameSetter.parameters.first { it.kind == IrParameterKind.Regular }
                +irCall(propertyNameSetter).apply {
                    dispatchReceiver = irGet(tempVar)
                    arguments[propertyParam.indexInParameters] = irString(propertyName)
                }

                val setupFunction = findFunctionInHierarchyCached(resultClass, "setup")
                    ?: error("Function setup() not found in ${resultClass.name}")

                +irCall(setupFunction).apply {
                    dispatchReceiver = irGet(tempVar)
                }

                +irGet(tempVar)
            }
        }
    }

    private fun IrType.isSubtypeOfEorEs(): Boolean {
        val e = eSymbol
        val es = esSymbol
        if (e != null && this.isSubtypeOfClass(e)) return true
        if (es != null && this.isSubtypeOfClass(es)) return true
        return false
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

    private fun findPropertyInHierarchyCached(start: IrClass, name: String): IrProperty? {
        val key = start.symbol to name
        return propertyCache.getOrPut(key) { findPropertyInHierarchy(start, name) }
    }

    private fun findFunctionInHierarchy(start: IrClass, name: String): IrSimpleFunction? {
        val visited = mutableSetOf<IrClass>()
        val queue: ArrayDeque<IrClass> = ArrayDeque()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!visited.add(c)) continue

            c.functions.firstOrNull { func ->
                func.name.asString() == name && func.parameters.none { it.kind == IrParameterKind.Regular }
            }?.let { return it }

            c.superTypes.forEach { st ->
                val sup = (st.classifierOrNull as? IrClassSymbol)?.owner
                if (sup != null) queue.addLast(sup)
            }
        }
        return null
    }

    private fun findFunctionInHierarchyCached(start: IrClass, name: String): IrSimpleFunction? {
        val key = start.symbol to name
        return functionCache.getOrPut(key) { findFunctionInHierarchy(start, name) }
    }

    private fun irBuilder(symbol: IrSymbol) =
        DeclarationIrBuilder(pluginContext, symbol)

    override fun visitCall(expression: IrCall): IrExpression {
        val transformedAny = super.visitCall(expression)
        val call = transformedAny as? IrCall ?: return transformedAny

        val function = call.symbol.owner
        val property = function.correspondingPropertySymbol?.owner
        val isSetter = property?.setter == function
        if (!isSetter) return call

        // Only consider setters for properties whose type is E/Es subtype
        val propType = property.backingField?.type ?: return call
        if (!propType.isSubtypeOfEorEs()) return call

        // Find the single regular parameter for the setter
        val valueParam = function.parameters.firstOrNull { it.kind == IrParameterKind.Regular } ?: return call
        val originalArg = call.arguments.getOrNull(valueParam.indexInParameters) ?: return call

        fun unwrapCandidate(e: IrExpression): IrExpression {
            return when (e) {
                is IrTypeOperatorCall -> unwrapCandidate(e.argument)
                is IrBlock -> e.statements.lastOrNull() as? IrExpression ?: e
                else -> e
            }
        }

        val candidate = unwrapCandidate(originalArg)
        val initExpr: IrExpression? = when (candidate) {
            is IrCall -> if (candidate.type.isSubtypeOfEorEs() && candidate.symbol.owner.hasAnnotation(newInstanceAnnotation)) candidate else null
            is IrConstructorCall -> if (candidate.type.isSubtypeOfEorEs()) candidate else null
            else -> null
        }
        if (initExpr == null) return call

        val parentClass = property.parent as? IrClass
        val className = parentClass?.name?.asString() ?: "Unknown"
        val propertyName = property.name.asString()

        // Build: { val tmp = <init>; tmp.meta=..; tmp.setup(); <setter>(..., tmp) }
        return pluginContext.irBuiltIns.run {
            irBuilder(call.symbol).irBlock {
                val tmp = createTmpVariable(initExpr, nameHint = "${propertyName}_init")

                val resultClass = initExpr.type.classOrNull?.owner
                    ?: error("Cannot find class for type ${initExpr.type}")

                val callingProp = findPropertyInHierarchyCached(resultClass, "callingClassName")
                    ?: error("Property callingClassName not found in ${resultClass.name}")
                val callingSetter = callingProp.setter ?: error("Setter not found for property ${callingProp.name}")

                val propNameProp = findPropertyInHierarchyCached(resultClass, "propertyName")
                    ?: error("Property propertyName not found in ${resultClass.name}")
                val propNameSetter = propNameProp.setter ?: error("Setter not found for property ${propNameProp.name}")

                val callingParam = callingSetter.parameters.first { it.kind == IrParameterKind.Regular }
                +irCall(callingSetter).apply {
                    dispatchReceiver = irGet(tmp)
                    arguments[callingParam.indexInParameters] = irString(className)
                }

                val propParam = propNameSetter.parameters.first { it.kind == IrParameterKind.Regular }
                +irCall(propNameSetter).apply {
                    dispatchReceiver = irGet(tmp)
                    arguments[propParam.indexInParameters] = irString(propertyName)
                }

                val setupFun = findFunctionInHierarchyCached(resultClass, "setup")
                    ?: error("Function setup() not found in ${resultClass.name}")
                +irCall(setupFun).apply { dispatchReceiver = irGet(tmp) }

                // Recreate setter call with tmp as argument
                +irCall(function).apply {
                    dispatchReceiver = call.dispatchReceiver
                    val argCount = function.parameters.size
                    // Copy existing arguments
                    (0 until argCount).forEach { idx ->
                        this.arguments[idx] = if (idx == valueParam.indexInParameters) irGet(tmp) else call.arguments.getOrNull(idx)
                    }
                }
            }
        }
    }

    override fun visitSetField(expression: IrSetField): IrExpression {
        // First, transform children
        val transformed = super.visitSetField(expression) as IrSetField

        val rhs = transformed.value

        fun unwrapCandidate(e: IrExpression): IrExpression {
            return when (e) {
                is IrTypeOperatorCall -> unwrapCandidate(e.argument)
                is IrBlock -> e.statements.lastOrNull() as? IrExpression ?: e
                else -> e
            }
        }

        val candidate = unwrapCandidate(rhs)

        // Only handle when RHS is a new instance via annotated factory or direct constructor
        val initExpr: IrExpression? = when (candidate) {
            is IrCall -> if (candidate.type.isSubtypeOfEorEs() && candidate.symbol.owner.hasAnnotation(newInstanceAnnotation)) candidate else null
            is IrConstructorCall -> if (candidate.type.isSubtypeOfEorEs()) candidate else null
            else -> null
        }
        if (initExpr == null) return transformed

        // We need the property and its containing class to set metadata
        val field = transformed.symbol.owner
        val prop = field.correspondingPropertySymbol?.owner ?: return transformed
        val parentClass = prop.parent as? IrClass ?: return transformed
        val className = parentClass.name.asString()
        val propertyName = prop.name.asString()

        // Build: { val tmp = <initExpr>; tmp.callingClassName = "Class"; tmp.propertyName = "prop"; tmp.setup(); <setfield> = tmp }
        return pluginContext.irBuiltIns.run {
            DeclarationIrBuilder(pluginContext, transformed.symbol).irBlock {
                val tmp = createTmpVariable(initExpr, nameHint = "${propertyName}_init")

                val resultClass = initExpr.type.classOrNull?.owner
                    ?: error("Cannot find class for type ${initExpr.type}")

                val callingProp = findPropertyInHierarchyCached(resultClass, "callingClassName")
                    ?: error("Property callingClassName not found in ${resultClass.name}")
                val callingSetter = callingProp.setter ?: error("Setter not found for property ${callingProp.name}")

                val propNameProp = findPropertyInHierarchyCached(resultClass, "propertyName")
                    ?: error("Property propertyName not found in ${resultClass.name}")
                val propNameSetter = propNameProp.setter ?: error("Setter not found for property ${propNameProp.name}")

                val callingParam = callingSetter.parameters.first { it.kind == IrParameterKind.Regular }
                +irCall(callingSetter).apply {
                    dispatchReceiver = irGet(tmp)
                    arguments[callingParam.indexInParameters] = irString(className)
                }

                val propParam = propNameSetter.parameters.first { it.kind == IrParameterKind.Regular }
                +irCall(propNameSetter).apply {
                    dispatchReceiver = irGet(tmp)
                    arguments[propParam.indexInParameters] = irString(propertyName)
                }

                val setupFun = findFunctionInHierarchyCached(resultClass, "setup")
                    ?: error("Function setup() not found in ${resultClass.name}")
                +irCall(setupFun).apply { dispatchReceiver = irGet(tmp) }

                // Set the field to tmp
                +irSetField(
                    transformed.receiver,
                    transformed.symbol.owner,
                    irGet(tmp)
                )
            }
        }
    }
}