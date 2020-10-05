/*
 * Copyright 2010-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.common.overrides

import org.jetbrains.kotlin.backend.common.serialization.DeclarationTable
import org.jetbrains.kotlin.backend.common.serialization.GlobalDeclarationTable
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureSerializer
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.descriptors.WrappedPropertyDescriptor
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.overrides.FakeOverrideBuilderStrategy
import org.jetbrains.kotlin.ir.overrides.IrOverridingUtil
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrPropertySymbolImpl
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.render

class FakeOverrideGlobalDeclarationTable(signatureSerializer: IdSignatureSerializer)
    : GlobalDeclarationTable(signatureSerializer, signatureSerializer.mangler) {

    fun clear() = table.clear()
}
class FakeOverrideDeclarationTable(signatureSerializer: IdSignatureSerializer)
    : DeclarationTable(FakeOverrideGlobalDeclarationTable(signatureSerializer)) {

    fun clear() {
        this.table.clear()
        (globalDeclarationTable as FakeOverrideGlobalDeclarationTable).clear()
    }
}

interface FakeOverrideClassFilter {
    fun constructFakeOverrides(clazz: IrClass): Boolean
}

interface FileLocalLinker {
    fun referenceSimpleFunctionByLocalSignature(idSignature: IdSignature): IrSimpleFunctionSymbol
    fun referencePropertyByLocalSignature(idSignature: IdSignature): IrPropertySymbol
}

object DefaultFakeOverrideClassFilter : FakeOverrideClassFilter {
    override fun constructFakeOverrides(clazz: IrClass): Boolean = true
}

object FakeOverrideControl {
    // If set to true: all fake overrides go to klib serialized IR.
    // If set to false: eligible fake overrides are not serialized.
    val serializeFakeOverrides: Boolean = true
    // Non-public api class members have their signatures instead of ordinal numbers for IdSignature.
    val privateMemberSignatures: Boolean = true

    // If set to true: fake overrides are deserialized from klib serialized IR.
    // If set to false: eligible fake overrides are constructed within IR linker.
    // This is the default in the absence of -Xdeserialize-fake-overrides flag.
    val deserializeFakeOverrides: Boolean = false
}

class FileLocalFakeOverrideBuilder(
    val fileLocalLinker: FileLocalLinker,
    val globalFakeOverrideBuilder: FakeOverrideBuilder,
    val deserializeFakeOverrides: Boolean
) : AbstractFakeOverrideBuilder(globalFakeOverrideBuilder.symbolTable, globalFakeOverrideBuilder.signaturer, globalFakeOverrideBuilder.irBuiltIns, globalFakeOverrideBuilder.platformSpecificClassFilter) {

    override fun enqueueClass(clazz: IrClass) {
        if (!deserializeFakeOverrides) {
            if (clazz.symbol.isPublicApi) {
                globalFakeOverrideBuilder.enqueueClass(clazz)
            } else {
                fakeOverrideClassQueue.add(clazz)
                globalFakeOverrideBuilder.classToBuilder.put(clazz, this)
            }
        }
    }

    override fun findProperBuilder(clazz: IrClass) =
        globalFakeOverrideBuilder.classToBuilder[clazz] ?: globalFakeOverrideBuilder

    override fun composeSignature(declaration: IrDeclaration) =
        signaturer.composeFileLocalIdSignature(declaration)

    override fun declareFunctionFakeOverride(declaration: IrFakeOverrideFunction, signature: IdSignature) {
        val symbol = fileLocalLinker.referenceSimpleFunctionByLocalSignature(signature)
        symbolTable.declareSimpleFunctionFromLinker(symbol.descriptor, signature) {
            assert(it == symbol)
            declaration.acquireSymbol(it)
        }
    }

    override fun declarePropertyFakeOverride(declaration: IrFakeOverrideProperty, signature: IdSignature) {
        val symbol = fileLocalLinker.referencePropertyByLocalSignature(signature)
        symbolTable.declarePropertyFromLinker(symbol.descriptor, signature) {
            declaration.acquireSymbol(symbol)
        }
    }
}

class FakeOverrideBuilder(
    symbolTable: SymbolTable,
    signaturer: IdSignatureSerializer,
    irBuiltIns: IrBuiltIns,
    platformSpecificClassFilter: FakeOverrideClassFilter = DefaultFakeOverrideClassFilter
) : AbstractFakeOverrideBuilder(symbolTable, signaturer, irBuiltIns, platformSpecificClassFilter) {

    val classToBuilder = mutableMapOf<IrClass, FileLocalFakeOverrideBuilder>()
    val fakeOverrideDeclarationTable = FakeOverrideDeclarationTable(signaturer)

    override fun findProperBuilder(clazz: IrClass) =
        classToBuilder[clazz] ?: this

    override fun composeSignature(declaration: IrDeclaration) =
        signaturer.composePublicIdSignature(declaration)

    override fun declareFunctionFakeOverride(declaration: IrFakeOverrideFunction, signature: IdSignature) {
        symbolTable.declareSimpleFunctionFromLinker(WrappedSimpleFunctionDescriptor(), signature) {
            declaration.acquireSymbol(it)
        }
    }

    override fun declarePropertyFakeOverride(declaration: IrFakeOverrideProperty, signature: IdSignature) {
        symbolTable.declarePropertyFromLinker(WrappedPropertyDescriptor(), signature) {
            declaration.acquireSymbol(it)
        }
    }
}

abstract class AbstractFakeOverrideBuilder(
    val symbolTable: SymbolTable,
    val signaturer: IdSignatureSerializer,
    val irBuiltIns: IrBuiltIns,
    val platformSpecificClassFilter: FakeOverrideClassFilter = DefaultFakeOverrideClassFilter
) : FakeOverrideBuilderStrategy() {
    private val haveFakeOverrides = mutableSetOf<IrClass>()

    private val irOverridingUtil = IrOverridingUtil(irBuiltIns, this)

    protected val fakeOverrideClassQueue = mutableListOf<IrClass>()
    open fun enqueueClass(clazz: IrClass) {
        fakeOverrideClassQueue.add(clazz)
    }

    abstract fun findProperBuilder(clazz: IrClass): AbstractFakeOverrideBuilder

    fun buildFakeOverrideChainsForClass(clazz: IrClass) {
        if (haveFakeOverrides.contains(clazz)) return
        if (!platformSpecificClassFilter.constructFakeOverrides(clazz)/* || !clazz.symbol.isPublicApi*/) return

        val superTypes = clazz.superTypes

        val superClasses = superTypes.map {
            it.getClass() ?: error("Unexpected super type: $it")
        }

        superClasses.forEach {
            // We need to find the proper builder for a class here. Consider the setup:
            //
            // File1.kt
            //   private interface A
            //   public open class B : A
            //
            // File2.kt
            //   private class C: B
            //
            // Note that A and C are both file local,
            // but their signatures are local to different files.
            findProperBuilder(it).buildFakeOverrideChainsForClass(it)
            haveFakeOverrides.add(it)
        }

        irOverridingUtil.buildFakeOverridesForClass(clazz)
    }

    override fun linkFakeOverride(fakeOverride: IrOverridableMember) {
        when (fakeOverride) {
            is IrFakeOverrideFunction -> linkFunctionFakeOverride(fakeOverride)
            is IrFakeOverrideProperty -> linkPropertyFakeOverride(fakeOverride)
            else -> error("Unexpected fake override: $fakeOverride")
        }
    }

    abstract fun composeSignature(declaration: IrDeclaration): IdSignature
    abstract fun declareFunctionFakeOverride(declaration: IrFakeOverrideFunction, signature: IdSignature)
    abstract fun declarePropertyFakeOverride(declaration: IrFakeOverrideProperty, signature: IdSignature)

    protected fun linkFunctionFakeOverride(declaration: IrFakeOverrideFunction) {
        val signature = composeSignature(declaration)
        declareFunctionFakeOverride(declaration, signature)
    }

    protected fun linkPropertyFakeOverride(declaration: IrFakeOverrideProperty) {
        // To compute a signature for a property with type parameters,
        // we must have its accessor's correspondingProperty pointing to the property's symbol.
        // See IrMangleComputer.mangleTypeParameterReference() for details.
        // But to create and link that symbol we should already have the signature computed.
        // To break this loop we use temp symbol in correspondingProperty.

        val tempSymbol = IrPropertySymbolImpl(WrappedPropertyDescriptor()).also {
            it.bind(declaration as IrProperty)
        }
        declaration.getter?.let {
            it.correspondingPropertySymbol = tempSymbol
        }
        declaration.setter?.let {
            it.correspondingPropertySymbol = tempSymbol
        }

        val signature = composeSignature(declaration)
        declarePropertyFakeOverride(declaration, signature)

        declaration.getter?.let {
            it.correspondingPropertySymbol = declaration.symbol
            linkFunctionFakeOverride(it as? IrFakeOverrideFunction ?: error("Unexpected fake override getter: $it"))
        }
        declaration.setter?.let {
            it.correspondingPropertySymbol = declaration.symbol
            linkFunctionFakeOverride(it as? IrFakeOverrideFunction ?: error("Unexpected fake override setter: $it"))
        }
    }

    fun provideFakeOverrides(klass: IrClass) {
        buildFakeOverrideChainsForClass(klass)
        propertyOverriddenSymbols.clear()
        irOverridingUtil.clear()
        haveFakeOverrides.add(klass)
    }

    fun provideFakeOverrides() {
        while (fakeOverrideClassQueue.isNotEmpty()) {
            val klass = fakeOverrideClassQueue.removeLast()
            provideFakeOverrides(klass)
        }
    }
}
