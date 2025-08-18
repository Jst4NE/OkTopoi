package jst.oktopoi.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class OktopoiIrGenerationExtension : IrGenerationExtension {
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext
    ) {
        // Apply all transformers: existing functionality + TreeMap suspend transformations + runBlockingMultiplatform inlining
        moduleFragment.transform(OktopoiTransformer(pluginContext), null)
        moduleFragment.transform(TreeMapSuspendTransformer(pluginContext), null)
        moduleFragment.transform(RunBlockingMultiplatformInliner(pluginContext), null)
    }
}

class OktopoiTransformer(
    private val pluginContext: IrPluginContext
) : IrElementTransformerVoidWithContext() {
    
    private val oktopoiPackage = FqName("jst.oktopoi")
    private val targetFunctions = setOf("e", "ep", "es", "eps", "esps")
    
    override fun visitPropertyNew(declaration: IrProperty): IrStatement {
        val backingField = declaration.backingField ?: return super.visitPropertyNew(declaration)
        val initializer = backingField.initializer?.expression ?: return super.visitPropertyNew(declaration)
        
        val transformedInitializer = when (initializer) {
            is IrCall -> transformCall(initializer, declaration)
            is IrTypeOperatorCall -> {
                val argument = initializer.argument
                if (argument is IrCall) transformCall(argument, declaration) else null
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
        val functionName = function.name.asString()

        if (functionName !in targetFunctions) return null

        val functionParent = function.parent as? IrPackageFragment ?: return null
        if (!functionParent.packageFqName.asString().startsWith("jst.oktopoi")) return null

        val containingClass = property.parent as? IrClass
        val className = containingClass?.name?.asString() ?: "Unknown"
        val propertyName = property.name.asString()

        return pluginContext.irBuiltIns.run {
            irBuilder(call.symbol).irBlock {
                val tempVar = createTmpVariable(call, nameHint = "${propertyName}_init")

                // Get the class of the created object
                val resultClass = call.type.classOrNull?.owner
                    ?: error("Cannot find class for type ${call.type}")

                // Find the fields in the actual class

                val callingClassNameProperty: IrProperty = resultClass.properties.firstOrNull {
                    it.name.asString() == "callingClassName"
                } ?: error("Property callingClassName not found in ${resultClass.name}")
                val callingClassNameField = callingClassNameProperty.backingField ?: error("Backing field not found for property ${callingClassNameProperty.name}")

                val propertyNameProperty: IrProperty = resultClass.properties.firstOrNull {
                    it.name.asString() == "propertyName"
                } ?: error("Property propertyName not found in ${resultClass.name}")
                val propertyNameField = propertyNameProperty.backingField ?: error("Backing field not found for property ${propertyNameProperty.name}")

                // Generate: tempVar.callingClassName = "ClassName"
                +irSetField(
                    irGet(tempVar),
                    callingClassNameField,
                    irString(className)
                )

                // Generate: tempVar.propertyName = "propertyName"
                +irSetField(
                    irGet(tempVar),
                    propertyNameField,
                    irString(propertyName)
                )

                // Find setup() function
                val setupFunction = resultClass.functions.firstOrNull {
                    it.name.asString() == "setup" && it.parameters.isEmpty()
                } ?: error("Function setup() not found in ${resultClass.name}")

                // Generate: tempVar.setup()
                +irCall(setupFunction).apply {
                    dispatchReceiver = irGet(tempVar)
                }

                // Return the initialized object
                +irGet(tempVar)
            }
        }
    }
    
    private fun irBuilder(symbol: IrSymbol) = 
        DeclarationIrBuilder(pluginContext, symbol)
}

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
    
    /**
     * Transform variable access expressions (IrGetValue) for TreeMap instances
     * in suspend contexts to use the suspend view.
     */
    override fun visitGetValue(expression: IrGetValue): IrExpression {
        val transformed = super.visitGetValue(expression)
        
        if (transformed is IrGetValue &&
            isInSuspendContext() && 
            isTreeMapVariable(transformed)) {
            return generateSuspendViewAccess(transformed)
        }
        
        return transformed
    }
    
    /**
     * Transform property access expressions (IrGetField) for TreeMap properties
     * in suspend contexts to use the suspend view.
     */
    override fun visitGetField(expression: IrGetField): IrExpression {
        val transformed = super.visitGetField(expression)
        
        if (transformed is IrGetField &&
            isInSuspendContext() && 
            isTreeMapProperty(transformed)) {
            return generateSuspendViewAccess(transformed)
        }
        
        return transformed
    }
    
    /**
     * Transform function calls (property getters) for TreeMap properties
     * in suspend contexts to use the suspend view.
     * Handles cases like: obj.treeMapProperty
     */
    override fun visitCall(expression: IrCall): IrExpression {
        val transformed = super.visitCall(expression)
        
        if (transformed is IrCall &&
            isInSuspendContext() &&
            isTreeMapPropertyGetter(transformed)) {
            return generateSuspendViewAccess(transformed)
        }
        
        return transformed
    }

    /**
     * Check if the variable/value is a TreeMap instance
     */
    private fun isTreeMapVariable(getValue: IrGetValue): Boolean {
        return isTreeMapType(getValue.type)
    }
    
    /**
     * Check if the property/field is a TreeMap instance
     */
    private fun isTreeMapProperty(getField: IrGetField): Boolean {
        return isTreeMapType(getField.type)
    }
    
    /**
     * Check if the function call is a getter for a TreeMap property
     */
    private fun isTreeMapPropertyGetter(call: IrCall): Boolean {
        val function = call.symbol.owner
        
        // Check if this is a property getter (starts with "get" or has no parameters with return type)
        val isGetter = function.name.asString().startsWith("get") || 
                      (function.parameters.isEmpty() && !function.returnType.isUnit())
        
        if (!isGetter) return false
        
        // Check if the return type is TreeMap
        return isTreeMapType(function.returnType)
    }

    /**
     * Check if the given type is TreeMap or a subclass
     */
    private fun isTreeMapType(type: IrType): Boolean {
        val classifier = type.classifierOrNull as? IrClassSymbol ?: return false
        val irClass = classifier.owner
        
        // Check if it's TreeMap directly
        if (irClass.fqNameWhenAvailable == treeMapFqName) return true
        
        // Check superclasses (for TreeMap subclasses)
        return irClass.superTypes.any { superType ->
            val superClassifier = superType.classifierOrNull as? IrClassSymbol
            superClassifier?.owner?.fqNameWhenAvailable == treeMapFqName
        }
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
        // Get the TreeMap class from the expression type
        val treeMapClass = getTreeMapClassFromType(originalExpression.type) ?: return originalExpression
        
        // Find the suspend property in TreeMap
        val suspendProperty = treeMapClass.properties.find { property ->
            property.name.asString() == "suspend"
        } ?: return originalExpression
        
        // Get the getter function for the suspend property
        val suspendGetter = suspendProperty.getter ?: return originalExpression
        
        // Create IR call to access the suspend property
        return pluginContext.irBuiltIns.run {
            DeclarationIrBuilder(pluginContext, suspendGetter.symbol).irCall(suspendGetter).apply {
                dispatchReceiver = originalExpression
            }
        }
    }
    
    /**
     * Get the TreeMap class from an IrType
     */
    private fun getTreeMapClassFromType(type: IrType): IrClass? {
        val classifier = type.classifierOrNull as? IrClassSymbol ?: return null
        val irClass = classifier.owner
        
        // Return the class if it's TreeMap
        return if (irClass.fqNameWhenAvailable == treeMapFqName) irClass else null
    }
}