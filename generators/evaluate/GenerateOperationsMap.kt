/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.generators.evaluate

import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.generators.util.GeneratorsFileUtil
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.utils.Printer
import java.io.File

val DEST_FILE: File = File("compiler/frontend/src/org/jetbrains/kotlin/resolve/constants/evaluate/OperationsMapGenerated.kt")
private val EXCLUDED_FUNCTIONS = listOf("rangeTo", "hashCode", "inc", "dec", "subSequence")

fun main(args: Array<String>) {
    GeneratorsFileUtil.writeFileIfContentChanged(DEST_FILE, generate())
}

fun generate(): String {
    val sb = StringBuilder()
    val p = Printer(sb)
    p.println(File("license/COPYRIGHT.txt").readText())
    p.println("@file:Suppress(\"DEPRECATION\", \"DEPRECATION_ERROR\")")

    p.println()
    p.println("package org.jetbrains.kotlin.resolve.constants.evaluate")
    p.println()
    p.println("import java.math.BigInteger")
    p.println("import java.util.HashMap")
    p.println()
    p.println("/** This file is generated by org.jetbrains.kotlin.generators.evaluate:generate(). DO NOT MODIFY MANUALLY */")
    p.println()

    val unaryOperationsMap = arrayListOf<Triple<String, List<KotlinType>, Boolean>>()
    val binaryOperationsMap = arrayListOf<Pair<String, List<KotlinType>>>()

    val builtIns = DefaultBuiltIns.Instance
    @Suppress("UNCHECKED_CAST")
    val allPrimitiveTypes = builtIns.builtInsPackageScope.getContributedDescriptors()
            .filter { it is ClassDescriptor && KotlinBuiltIns.isPrimitiveType(it.defaultType) } as List<ClassDescriptor>

    for (descriptor in allPrimitiveTypes + builtIns.string) {
        @Suppress("UNCHECKED_CAST")
        val functions = descriptor.getMemberScope(listOf()).getContributedDescriptors()
                .filter { it is CallableDescriptor && !EXCLUDED_FUNCTIONS.contains(it.getName().asString()) } as List<CallableDescriptor>

        for (function in functions) {
            val parametersTypes = function.getParametersTypes()

            when (parametersTypes.size) {
                1 -> unaryOperationsMap.add(Triple(function.name.asString(), parametersTypes, function is FunctionDescriptor))
                2 -> binaryOperationsMap.add(function.name.asString() to parametersTypes)
                else -> throw IllegalStateException("Couldn't add following method from builtins to operations map: ${function.name} in class ${descriptor.name}")
            }
        }
    }

    p.println("internal val emptyBinaryFun: Function2<BigInteger, BigInteger, BigInteger> = { a, b -> BigInteger(\"0\") }")
    p.println("internal val emptyUnaryFun: Function1<Long, Long> = { a -> 1.toLong() }")
    p.println()
    p.println("internal val unaryOperations: HashMap<UnaryOperationKey<*>, Pair<Function1<Any?, Any>, Function1<Long, Long>>>")
    p.println("            = hashMapOf<UnaryOperationKey<*>, Pair<Function1<Any?, Any>, Function1<Long, Long>>>(")
    p.pushIndent()

    val unaryOperationsMapIterator = unaryOperationsMap.iterator()
    while (unaryOperationsMapIterator.hasNext()) {
        val (funcName, parameters, isFunction) = unaryOperationsMapIterator.next()
        val parenthesesOrBlank = if (isFunction) "()" else ""
        p.println(
                "unaryOperation(",
                parameters.map { it.asString() }.joinToString(", "),
                ", ",
                "\"$funcName\"",
                ", { a -> a.$funcName$parenthesesOrBlank }, ",
                renderCheckUnaryOperation(funcName, parameters),
                ")",
                if (unaryOperationsMapIterator.hasNext()) "," else ""
        )
    }
    p.popIndent()
    p.println(")")

    p.println()

    p.println("internal val binaryOperations: HashMap<BinaryOperationKey<*, *>, Pair<Function2<Any?, Any?, Any>, Function2<BigInteger, BigInteger, BigInteger>>>")
    p.println("            = hashMapOf<BinaryOperationKey<*, *>, Pair<Function2<Any?, Any?, Any>, Function2<BigInteger, BigInteger, BigInteger>>>(")
    p.pushIndent()

    val binaryOperationsMapIterator = binaryOperationsMap.iterator()
    while (binaryOperationsMapIterator.hasNext()) {
        val (funcName, parameters) = binaryOperationsMapIterator.next()
        p.println(
                "binaryOperation(",
                parameters.map { it.asString() }.joinToString(", "),
                ", ",
                "\"$funcName\"",
                ", { a, b -> a.$funcName(b) }, ",
                renderCheckBinaryOperation(funcName, parameters),
                ")",
                if (binaryOperationsMapIterator.hasNext()) "," else ""
        )
    }
    p.popIndent()
    p.println(")")

    return sb.toString()
}

fun renderCheckUnaryOperation(name: String, params: List<KotlinType>): String {
    val isAllParamsIntegers = params.fold(true) { a, b -> a && b.isIntegerType() }
    if (!isAllParamsIntegers) {
        return "emptyUnaryFun"
    }

    return when(name) {
        "unaryMinus", "minus" -> "{ a -> a.$name() }"
        else -> "emptyUnaryFun"
    }
}

fun renderCheckBinaryOperation(name: String, params: List<KotlinType>): String {
    val isAllParamsIntegers = params.fold(true) { a, b -> a && b.isIntegerType() }
    if (!isAllParamsIntegers) {
        return "emptyBinaryFun"
    }

    return when(name) {
        "plus" -> "{ a, b -> a.add(b) }"
        "minus" -> "{ a, b -> a.subtract(b) }"
        "div" -> "{ a, b -> a.divide(b) }"
        "times" -> "{ a, b -> a.multiply(b) }"
        "mod",
        "rem",
        "xor",
        "or",
        "and" -> "{ a, b -> a.$name(b) }"
        else -> "emptyBinaryFun"
    }
}

private fun KotlinType.isIntegerType(): Boolean {
    return KotlinBuiltIns.isInt(this) ||
           KotlinBuiltIns.isShort(this) ||
           KotlinBuiltIns.isByte(this) ||
           KotlinBuiltIns.isLong(this)
}


private fun CallableDescriptor.getParametersTypes(): List<KotlinType> {
    val list = arrayListOf<KotlinType>((containingDeclaration as ClassDescriptor).defaultType)
    valueParameters.map { it.type }.forEach {
        list.add(TypeUtils.makeNotNullable(it))
    }
    return list
}

private fun KotlinType.asString(): String = constructor.declarationDescriptor!!.name.asString().toUpperCase()
