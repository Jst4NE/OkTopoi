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
        moduleFragment.transform(OktopoiTransformer(pluginContext), null)
    }
}

class OktopoiTransformer(
    private val pluginContext: IrPluginContext
) : IrElementTransformerVoidWithContext() {
    
    private val oktopoiPackage = FqName("jst.oktopoi")
    private val targetFunctions = setOf("e", "ep", "es", "eps")
    
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
//                callingClassNameField.visibility = DescriptorVisibilities.PUBLIC

                val propertyNameProperty: IrProperty = resultClass.properties.firstOrNull {
                    it.name.asString() == "propertyName"
                } ?: error("Property propertyName not found in ${resultClass.name}")
                val propertyNameField = propertyNameProperty.backingField ?: error("Backing field not found for property ${propertyNameProperty.name}")
//                propertyNameField.visibility = DescriptorVisibilities.PUBLIC

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
                    it.name.asString() == "setup" && it.valueParameters.isEmpty()
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