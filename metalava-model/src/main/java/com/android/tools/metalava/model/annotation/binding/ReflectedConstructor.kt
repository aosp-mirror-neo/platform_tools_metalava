/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.metalava.model.annotation.binding

import com.android.tools.metalava.model.annotation.binding.ConstructorClassFileInfo.Companion.jvmDescriptor
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.lang.reflect.Parameter
import kotlin.jvm.internal.DefaultConstructorMarker

/**
 * Encapsulates Java reflection metadata and instantiation logic for the binding constructor of
 * [jClass], including optional invocation of Kotlin's synthetic [DefaultConstructorMarker]
 * constructor when default parameter values are used.
 */
internal class ReflectedConstructor<T : Any>
private constructor(
    /** The bound Java [Class]. */
    val jClass: Class<T>,
) {
    /** Human-readable qualified name of [jClass] for diagnostic messages. */
    val qualifiedDisplayName: String = jClass.qualifiedDisplayName

    /** The [Constructor] used to instantiate [jClass] when all arguments are supplied directly. */
    private val bindingConstructor: Constructor<T> = jClass.selectBindingConstructor()

    /**
     * The synthetic Kotlin default-arguments constructor (`(..., int mask...,
     * DefaultConstructorMarker)`), if [bindingConstructor] declares any default parameter values.
     */
    private val defaultConstructor: Constructor<T>? =
        jClass.findDefaultConstructor(bindingConstructor)

    /** Whether [jClass] provides a synthetic Kotlin default-arguments constructor. */
    val hasDefaultConstructor: Boolean = defaultConstructor != null

    /** Formal parameters of [bindingConstructor]. */
    val parameters: Array<Parameter> = bindingConstructor.parameters

    /** Number of formal parameters on [bindingConstructor]. */
    val parameterCount: Int = parameters.size

    /** Number of 32-bit default-value bitmask `int` arguments expected by [defaultConstructor]. */
    val maskCount: Int = (parameterCount + 31) ushr 5

    /** Parameter names and nullability flags for [bindingConstructor]. */
    val classFileInfo: ConstructorClassFileInfo = ConstructorClassFileInfo.of(bindingConstructor)

    init {
        bindingConstructor.isAccessible = true
        defaultConstructor?.isAccessible = true
    }

    /**
     * Instantiate [jClass] with [args], invoking [defaultConstructor] with [defaultMasks] when
     * non-null or [bindingConstructor] otherwise.
     *
     * Throws [IllegalArgumentException] if [args] does not have length [parameterCount], any slot
     * in [args] still holds [unsetSentinel], or [defaultMasks] is non-null when
     * [hasDefaultConstructor] is `false` or has length different from [maskCount].
     */
    fun newInstance(
        args: Array<Any?>,
        defaultMasks: IntArray?,
        unsetSentinel: Any,
    ): T {
        require(args.size == parameterCount) {
            "Expected $parameterCount arguments for $bindingConstructor, got ${args.size}"
        }
        for (i in 0 until parameterCount) {
            if (args[i] === unsetSentinel) {
                val paramName = classFileInfo.parameterNames[i] ?: "arg$i"
                throw IllegalArgumentException(
                    "No argument provided for a required parameter: parameter #$i $paramName of $bindingConstructor"
                )
            }
        }

        if (defaultMasks != null) {
            val ctor =
                requireNotNull(defaultConstructor) {
                    "No default constructor available for $bindingConstructor"
                }
            require(defaultMasks.size == maskCount) {
                "Expected $maskCount default mask word(s) for $bindingConstructor, got ${defaultMasks.size}"
            }
            val syntheticArgs = arrayOfNulls<Any>(parameterCount + maskCount + 1)
            System.arraycopy(args, 0, syntheticArgs, 0, parameterCount)
            for (m in 0 until maskCount) {
                syntheticArgs[parameterCount + m] = defaultMasks[m]
            }
            syntheticArgs[parameterCount + maskCount] = null
            return ctor.newInstance(*syntheticArgs)
        }

        return bindingConstructor.newInstance(*args)
    }

    /**
     * Return `true` if [cause] is a [NullPointerException] caused by passing a `null` placeholder
     * for a missing required non-nullable parameter to [jClass]'s `<init>` constructor (either
     * Kotlin's `Intrinsics` non-null parameter check or a failure while evaluating a default
     * parameter expression inside [defaultConstructor] before delegating to [bindingConstructor]).
     */
    fun isMissingParameterInvocationFailure(
        cause: Throwable,
        usedDefaultMasks: Boolean = hasDefaultConstructor,
    ): Boolean {
        if (cause !is NullPointerException) return false
        if (
            cause.message?.startsWith(
                "Parameter specified as non-null is null: method ${jClass.name}.<init>, parameter "
            ) == true
        ) {
            return true
        }

        // `defaultConstructor` evaluates default parameter expressions before delegating to
        // `bindingConstructor`, so an NPE during default evaluation has only one `jClass.<init>`
        // frame on the stack whereas an NPE inside `bindingConstructor`'s body has two.
        return usedDefaultMasks &&
            hasDefaultConstructor &&
            cause.stackTrace.count { it.className == jClass.name && it.methodName == "<init>" } == 1
    }

    companion object {
        private val Class<*>.qualifiedDisplayName: String
            get() = canonicalName ?: name

        /** Get a [ReflectedConstructor] for [jClass]. */
        fun <T : Any> of(jClass: Class<T>): ReflectedConstructor<T> = ReflectedConstructor(jClass)

        /**
         * Select the binding constructor to use on this [Class].
         *
         * If [Class] only has a single non-synthetic Kotlin constructor that is returned.
         * Otherwise, the constructor that is annotated with [BindingConstructor] is returned.
         *
         * Rejects `@JvmInline value class` targets, `private` constructors (for which `kotlinc`
         * omits parameter nullability metadata and null checks), and constructors lowered to
         * `private` due to `@JvmInline value class` parameters.
         */
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> Class<T>.selectBindingConstructor(): Constructor<T> {
            if (isAnnotationPresent(JvmInline::class.java)) {
                error("Cannot create an instance of value class $qualifiedDisplayName")
            }

            val nonSynthetic = declaredConstructors.filter { !it.isSynthetic }

            // When all primary constructor parameters in Kotlin have default values (or when
            // `@JvmOverloads` is used), `kotlinc` emits additional non-synthetic convenience
            // forwarding constructors without a `LineNumberTable` (copying any
            // `@BindingConstructor` annotation onto them as well). Filter those generated overloads
            // out.
            val constructors =
                if (
                    isAnnotationPresent(Metadata::class.java) &&
                        nonSynthetic.size > 1 &&
                        declaredConstructors.any { it.isSynthetic }
                ) {
                    val forwarding =
                        ConstructorClassFileInfo.findGeneratedForwardingConstructors(this)
                    nonSynthetic
                        .filter { it.jvmDescriptor() !in forwarding }
                        .ifEmpty { nonSynthetic }
                } else {
                    nonSynthetic
                }

            val selected =
                when (constructors.size) {
                    0 ->
                        error(
                            "Cannot create an instance of $qualifiedDisplayName as it has no constructors"
                        )
                    1 -> constructors[0] as Constructor<T>
                    else -> {
                        val annotated =
                            constructors.filter { constructor ->
                                constructor.isAnnotationPresent(BindingConstructor::class.java)
                            }
                        when (annotated.size) {
                            0 ->
                                error(
                                    "Found multiple constructors in $qualifiedDisplayName; please annotate one with @BindingConstructor"
                                )
                            1 -> annotated[0] as Constructor<T>
                            else ->
                                error(
                                    "Found multiple constructors in $qualifiedDisplayName that are annotated with @BindingConstructor, please annotate only one"
                                )
                        }
                    }
                }

            if (Modifier.isPrivate(selected.modifiers)) {
                if (selected.parameterCount > 0 && hasValueClassBridge(selected)) {
                    error(
                        "Cannot create an instance of $qualifiedDisplayName as its binding constructor has value class parameters"
                    )
                }
                error(
                    "Cannot create an instance of $qualifiedDisplayName as its binding constructor is private"
                )
            }

            return selected
        }

        /**
         * Return `true` if [constructor] is a synthetic `(*paramTypes, DefaultConstructorMarker)`
         * bridge constructor emitted by `kotlinc` for a constructor with `@JvmInline value class`
         * parameters.
         *
         * `kotlinc` records value-class bridge descriptors in `@Metadata.data2`, distinguishing
         * them from synthetic `private`-constructor companion/outer accessors and synthetic
         * default-argument constructors (neither of which appears in `@Metadata.data2`).
         */
        private fun Class<*>.isValueClassBridge(constructor: Constructor<*>): Boolean =
            constructor.isSynthetic &&
                getAnnotation(Metadata::class.java)?.data2?.contains(constructor.jvmDescriptor()) ==
                    true

        /**
         * Return `true` if this [Class] declares a synthetic `(*paramTypes,
         * DefaultConstructorMarker)` bridge constructor emitted by `kotlinc` when [constructor] has
         * one or more `@JvmInline value class` parameters.
         */
        private fun Class<*>.hasValueClassBridge(constructor: Constructor<*>): Boolean {
            val paramTypes = constructor.parameterTypes
            val bridgeTypes =
                Array<Class<*>>(paramTypes.size + 1) { index ->
                    if (index < paramTypes.size) {
                        paramTypes[index]
                    } else {
                        DefaultConstructorMarker::class.java
                    }
                }
            return try {
                isValueClassBridge(getDeclaredConstructor(*bridgeTypes))
            } catch (_: NoSuchMethodException) {
                false
            }
        }

        /**
         * Find the synthetic Kotlin default-arguments constructor matching [primary], or `null` if
         * [primary] has no default parameter values.
         */
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> Class<T>.findDefaultConstructor(
            primary: Constructor<T>,
        ): Constructor<T>? {
            val paramTypes = primary.parameterTypes
            if (paramTypes.isEmpty()) return null
            val maskCount = (paramTypes.size + 31) ushr 5
            val syntheticTypes =
                Array<Class<*>>(paramTypes.size + maskCount + 1) { index ->
                    when {
                        index < paramTypes.size -> paramTypes[index]
                        index < paramTypes.size + maskCount -> Int::class.javaPrimitiveType!!
                        else -> DefaultConstructorMarker::class.java
                    }
                }
            return try {
                getDeclaredConstructor(*syntheticTypes).takeIf {
                    it.isSynthetic && !isValueClassBridge(it)
                }
            } catch (_: NoSuchMethodException) {
                null
            }
        }
    }
}
