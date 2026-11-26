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

import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.ClassResolver
import com.android.tools.metalava.model.ClassTypeItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.annotation.AnnotationDefaults
import com.android.tools.metalava.model.value.AnnotationValue
import com.android.tools.metalava.model.value.ArrayElementValue
import com.android.tools.metalava.model.value.ClassObjectValue
import com.android.tools.metalava.model.value.Value
import com.android.tools.metalava.model.value.asBoolean
import com.android.tools.metalava.model.value.asInt
import com.android.tools.metalava.model.value.asLong
import com.android.tools.metalava.model.value.asString
import com.android.tools.metalava.reporter.Issues
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.reflect.KClass

/**
 * Binder that can create an instance of [kClass] from an [AnnotationItem] that uses the
 * [annotationDefaults].
 */
internal class AnnotationBinding<T : Any>(
    kClass: KClass<T>,
    private val annotationDefaults: AnnotationDefaults?,
) : AnnotationBindingFactory<T> {
    /** Reflected constructor metadata and instantiation helper for [kClass]. */
    private val reflectedConstructor: ReflectedConstructor<T> = ReflectedConstructor.of(kClass.java)

    /** Number of parameters on [reflectedConstructor]. */
    private val parameterCount: Int = reflectedConstructor.parameterCount

    /**
     * Binders indexed by constructor parameter position (`null` for parameters with unsupported
     * types).
     */
    private val bindersByIndex: Array<ParameterBinder?>

    /**
     * Map from attribute name to the [ParameterBinder] that will bind that attribute's values to a
     * parameter in [reflectedConstructor].
     */
    private val bindersByName: Map<String, ParameterBinder>

    init {
        val parameterNames = reflectedConstructor.classFileInfo.parameterNames
        val parameterNullability = reflectedConstructor.classFileInfo.parameterNullability
        val parameters = reflectedConstructor.parameters

        val indexed = arrayOfNulls<ParameterBinder>(parameterCount)
        val byName = HashMap<String, ParameterBinder>(parameterCount * 2)

        for (index in 0 until parameterCount) {
            val name =
                parameterNames[index]
                    ?: error(
                        "internal error: ${reflectedConstructor.qualifiedDisplayName}: Cannot get name for parameter $index"
                    )
            val type = parameters[index].parameterizedType
            val nullableConverter = converterForType(type) ?: continue
            val rawClass = type.rawClass ?: error("internal error: Cannot support $type")
            val nullable = parameterNullability[index]

            // Get the default value specified in the annotation class declaration, if any.
            val default = annotationDefaults?.get(name)
            val defaultProvider: DefaultProvider? =
                if (default != null) {
                    // Wrap the default [Value] in a lambda that will convert it into an
                    // appropriate value for the parameter.
                    {
                        val any = nullableConverter(default)
                        if (any == null) {
                            reportInvalidAttributeValue(
                                name,
                                default,
                                type,
                                label = "default ",
                            )
                        }
                        any
                    }
                } else if (rawClass == Item::class.java) {
                    // A parameter of type Item must be bound to the annotated item. This makes
                    // the annotated item the default value of the parameter.
                    { item }
                } else {
                    null
                }

            // Get a zero value to use as a last resort when a parameter is required but no
            // value has been provided.
            val zero =
                if (nullable) {
                    // If the parameter is nullable then always use a `null` as the `zero` value.
                    null
                } else {
                    // Otherwise, compute one for the type.
                    zeroValueForNonNullableType(rawClass)
                }

            val binder =
                ParameterBinder(
                    index = index,
                    name = name,
                    type = type,
                    nullable = nullable,
                    nullableConverter = nullableConverter,
                    defaultProvider = defaultProvider,
                    zero = zero,
                )
            indexed[index] = binder
            byName[name] = binder
        }

        bindersByIndex = indexed
        bindersByName = byName
    }

    override fun createInstanceFrom(
        annotationItem: AnnotationItem,
        item: Item,
    ): T? {
        val context = ConverterContext(annotationItem, item)
        val args = Array<Any?>(parameterCount) { UNSET }
        var defaultMasks: IntArray? = null

        // Iterate over the annotation attributes, converting their values to the appropriate
        // type for the parameter.
        for (attribute in annotationItem.attributes) {
            val binder = bindersByName[attribute.name] ?: continue
            binder.bindToValue(context, args, attribute.value)
        }

        // Now iterate over all the parameter binders adding any additional defaults needed.
        val hasDefaultConstructor = reflectedConstructor.hasDefaultConstructor
        for (i in 0 until parameterCount) {
            val binder = bindersByIndex[i]
            val useKotlinDefault =
                if (binder != null) {
                    binder.bindToDefault(context, args, hasDefaultConstructor)
                } else if (hasDefaultConstructor) {
                    // Ignored parameter types cannot be bound from annotation attributes, so
                    // request their Kotlin default value (if any) via the default-arguments mask.
                    args[i] = defaultZeroForIgnoredType(reflectedConstructor.parameters[i].type)
                    true
                } else {
                    false
                }
            if (useKotlinDefault) {
                val masks =
                    defaultMasks
                        ?: IntArray(reflectedConstructor.maskCount).also { defaultMasks = it }
                masks[i ushr 5] = masks[i ushr 5] or (1 shl (i and 31))
            }
        }

        // If an error was encountered during binding then return `null`.
        if (context.doNotInstantiate) {
            return null
        }

        // Construct an instance passing in the bound arguments. While great care is taken to avoid
        // it, this could throw an exception if there is some inconsistency between the annotation
        // definition/use and the binding constructor parameters. Catching and reporting prevents
        // invalid source files from aborting processing.
        return try {
            reflectedConstructor.newInstance(args, defaultMasks, UNSET)
        } catch (e: Exception) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e

            // If a missing required non-nullable parameter had to be passed as a `null` placeholder
            // to the synthetic default constructor and `<init>` failed with a
            // `NullPointerException`
            // (either in Kotlin's non-null parameter check or while evaluating a cross-parameter
            // default expression), return `null` without reporting a duplicate internal error.
            if (
                context.hasNullPlaceholderForMissingParameter &&
                    reflectedConstructor.isMissingParameterInvocationFailure(
                        cause,
                        usedDefaultMasks = defaultMasks != null,
                    )
            ) {
                return null
            }
            item.codebase.reporter.report(
                Issues.INVALID_ANNOTATION_BINDING,
                item,
                "internal error: could not bind ${reflectedConstructor.qualifiedDisplayName} to ${annotationItem.qualifiedName}: ${cause.message}",
                location = annotationItem.fileLocation,
            )
            null
        }
    }

    /** Binds the constructor parameter at [index] to the value of attribute [name]. */
    private class ParameterBinder(
        /** Positional index of the parameter in the binding constructor. */
        val index: Int,

        /** The parameter name. */
        val name: String,

        /** Generic [Type] of the parameter, used in diagnostic messages. */
        private val type: Type,

        /** Indicates whether the parameter accepts `null`. */
        private val nullable: Boolean,

        /** The [ValueConverter] that may produce a `null` value. */
        private val nullableConverter: ValueConverter<*>,

        /** The default [Value] as provided by the annotation definition in the sources. */
        private val defaultProvider: DefaultProvider?,

        /**
         * The zero value, used as a last resort for non-nullable parameters that have no
         * [defaultProvider] and are not optional.
         *
         * If this is used then it indicates a problem in the sources somewhere.
         */
        private val zero: Any?,
    ) {
        /** Convert this [Value] within [context] to a value appropriate for this parameter. */
        private fun Value.convert(context: ConverterContext) = context.nullableConverter(this)

        /**
         * Bind this parameter in [args] to the result of converting [value] to the parameter's type
         * using [context].
         */
        fun bindToValue(
            context: ConverterContext,
            args: Array<Any?>,
            value: Value,
        ) {
            val any = value.convert(context)
            args[index] = any
            if (any == null) {
                context.reportInvalidAttributeValue(name, value, type)
            }
        }

        /**
         * If this parameter is not already bound in [args] then bind it to the result of
         * [defaultProvider], falling back to a Kotlin constructor default (returning `true` so its
         * default bitmask bit is set) or [zero] if necessary.
         */
        fun bindToDefault(
            context: ConverterContext,
            args: Array<Any?>,
            hasDefaultConstructor: Boolean,
        ): Boolean {
            val current = args[index]

            // If the parameter has already been set to a non-null value then there is no need to
            // provide a default.
            if (current !== UNSET && current != null) return false

            // If the parameter is nullable and not yet been set then set it to `null`.
            if (nullable) {
                args[index] = null
                return false
            }

            // If the annotation definition provides a default then use it.
            defaultProvider?.let { provider ->
                context.provider()?.let {
                    args[index] = it
                    return false
                }
            }

            // At this point if no parameter was set in args there is a required attribute that
            // has not been provided so report it missing.
            if (current === UNSET) {
                context.reportMissingAttribute(name)
            }

            // If the class has a synthetic Kotlin default constructor, pass the zero/null
            // placeholder and mark this parameter's bitmask bit so any Kotlin default value on the
            // parameter is substituted; parameters without a Kotlin default receive `zero`.
            if (hasDefaultConstructor) {
                args[index] = zero
                if (zero == null) {
                    context.markNullPlaceholderForMissingParameter()
                }
                return true
            }

            // Otherwise, fallback to the zero value, if possible.
            if (zero == null) {
                // Null is not a valid value for the parameter. If it was then this function would
                // have returned above. That means that an instance cannot be instantiated.
                context.doNotInstantiate()
            } else {
                args[index] = zero
            }
            return false
        }
    }

    /** Factory for creating [ValueConverter]s from [Type]. */
    private interface ConverterFactory {
        /** Create a [ValueConverter] from [type]. */
        fun createConverter(type: Type): ValueConverter<*>
    }

    /**
     * Creates [ValueConverter] that will convert a [Value] to a [List] of some element type `E`.
     *
     * The values of element type `E` will be converted from [Value]s by a [ValueConverter]
     * retrieved by [converterForType].
     */
    private class ListConverterFactory : ConverterFactory {
        override fun createConverter(type: Type): ValueConverter<List<*>> {
            val paramType =
                type as? ParameterizedType ?: error("Raw List type is not supported: $type")
            val rawArg = paramType.actualTypeArguments[0]

            // Kotlin's covariant `List<out E>` compiles to `List<? extends E>` (`WildcardType`)
            // when `E` is an interface or open class (e.g. `ArrayElementValue`). Unwrap the upper
            // bound while rejecting unbounded `?` star projections.
            val elementType =
                when (rawArg) {
                    is WildcardType -> {
                        val upper = rawArg.upperBounds.singleOrNull()
                        if (
                            upper == null ||
                                (upper == Any::class.java && rawArg.lowerBounds.isEmpty())
                        ) {
                            error("Star projections are not supported")
                        }
                        upper
                    }
                    else -> rawArg
                }
            val elementConverter =
                converterForType(elementType) ?: error("unsupported element type: $elementType")
            return { value ->
                val context = this
                value.asFlatList().mapNotNull { context.elementConverter(it) }
            }
        }
    }

    /** Creates [ValueConverter] that will bind a nested [AnnotationItem] to a class. */
    private class NestedBindingConverterFactory<T : Any> : ConverterFactory {
        override fun createConverter(type: Type): ValueConverter<T> {
            @Suppress("UNCHECKED_CAST") val bindingClass = (type.rawClass as Class<T>).kotlin
            return { value ->
                val annotationItem = annotationItemConverter(value)
                annotationItem?.bindTo(bindingClass, item)
            }
        }
    }

    companion object {
        /** Sentinel marking an unbound slot in the constructor argument array. */
        private val UNSET = Any()

        private val Type.rawClass: Class<*>?
            get() =
                when (this) {
                    is Class<*> -> this
                    is ParameterizedType -> rawType as? Class<*>
                    else -> null
                }

        /** Map from [Class] to the [ValueConverter]. */
        private val valueConvertersByClass: Map<Class<*>, ValueConverter<*>> = buildMap {
            val register = { cls: Class<*>, converter: ValueConverter<*> -> put(cls, converter) }

            // Convert from a [Value] to an [AnnotationItem].
            register(AnnotationItem::class.java) { value ->
                (value as? AnnotationValue)?.annotationItem
            }

            // Converter from a [Value] to an [ArrayElementValue].
            register(ArrayElementValue::class.java) { value -> value as? ArrayElementValue }

            // Converter from a [Value] to a [Boolean].
            val booleanConverter: ValueConverter<Boolean> = { value -> value.asBoolean() }
            register(Boolean::class.javaPrimitiveType!!, booleanConverter)
            register(Boolean::class.javaObjectType, booleanConverter)

            // Converter from a [Value] to a [TypeItem].
            val typeItemConverter: ValueConverter<TypeItem> = { value ->
                (value as? ClassObjectValue)?.typeItem
            }
            register(TypeItem::class.java, typeItemConverter)

            // Converter from a [Value] to a [ClassItem].
            register(ClassItem::class.java) { value ->
                typeItemConverter(this, value)?.let { typeItem ->
                    (typeItem as? ClassTypeItem)?.let { classTypeItem ->
                        val qualifiedName = classTypeItem.qualifiedName
                        classResolver.resolveClass(qualifiedName)
                    }
                }
            }

            // Converter from a [Value] to an [Int].
            val intConverter: ValueConverter<Int> = { value -> value.asInt() }
            register(Int::class.javaPrimitiveType!!, intConverter)
            register(Int::class.javaObjectType, intConverter)

            // Converter from a [Value] to a [Long].
            val longConverter: ValueConverter<Long> = { value -> value.asLong() }
            register(Long::class.javaPrimitiveType!!, longConverter)
            register(Long::class.javaObjectType, longConverter)

            // Converter from a [Value] to a [String].
            register(String::class.java) { value -> value.asString() }
        }

        /** Convert a [Value] to an [AnnotationItem]. */
        @Suppress("UNCHECKED_CAST")
        private val annotationItemConverter =
            valueConvertersByClass[AnnotationItem::class.java] as ValueConverter<AnnotationItem?>

        /** Map from [Class] to the [ConverterFactory]. */
        private val valueConverterFactoryByClass =
            mapOf<Class<*>, ConverterFactory>(
                List::class.java to ListConverterFactory(),
            )

        /** Create [ValueConverter] that will bind a nested [AnnotationItem] to a nested class. */
        private val nestedBindingConverterFactory = NestedBindingConverterFactory<Any>()

        /** Get the [ValueConverter] to use for converting [Value]s to [type]. */
        private fun converterForType(type: Type): (ValueConverter<*>)? {
            val rawClass = type.rawClass ?: return null

            // If binding to the annotated item then there is no value to convert. This returns a
            // non-null converter to prevent an error being reported. The converter returns null to
            // ensure that if a value was provided for a parameter of that type that it would
            // report an error.
            if (rawClass == Item::class.java) {
                return { null }
            }

            // Try one of the built-in conversions first.
            valueConvertersByClass[rawClass]?.let {
                return it
            }

            // Get the factory for the raw class.
            val factory =
                converterFactoryForClass(rawClass)
                    // No factory was found so return `null` to indicate that the type is not
                    // supported.
                    ?: return null

            // Create the converter from the type.
            return factory.createConverter(type)
        }

        /** Get the [ConverterFactory], if any, for [rawClass]. */
        private fun converterFactoryForClass(rawClass: Class<*>): ConverterFactory? {
            valueConverterFactoryByClass[rawClass]?.let {
                return it
            }

            // Ignore classes that cannot be created from an annotation.
            val isInner = rawClass.isMemberClass && !Modifier.isStatic(rawClass.modifiers)
            val isValue = rawClass.isAnnotationPresent(JvmInline::class.java)
            if (
                Modifier.isAbstract(rawClass.modifiers) ||
                    isValue ||
                    isInner ||
                    rawClass.isEnum ||
                    rawClass.isPrimitive
            ) {
                return null
            }

            // Use the nested binding factory.
            return nestedBindingConverterFactory
        }

        /** Get a zero value for a non-nullable [rawClass], or `null` if none could be found. */
        private fun zeroValueForNonNullableType(rawClass: Class<*>): Any? =
            when (rawClass) {
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaObjectType -> false
                Int::class.javaPrimitiveType,
                Int::class.javaObjectType -> 0
                Long::class.javaPrimitiveType,
                Long::class.javaObjectType -> 0L
                String::class.java -> ""
                List::class.java -> emptyList<Any>()
                else ->
                    if (rawClass.isPrimitive) {
                        error("Must provide a zero value for primitive type $rawClass")
                    } else {
                        null
                    }
            }

        /**
         * Get a JVM zero/null placeholder value for an ignored parameter of [type] when invoking a
         * synthetic Kotlin default-arguments constructor.
         */
        private fun defaultZeroForIgnoredType(type: Class<*>): Any? =
            when (type) {
                Boolean::class.javaPrimitiveType -> false
                Byte::class.javaPrimitiveType -> 0.toByte()
                Short::class.javaPrimitiveType -> 0.toShort()
                Char::class.javaPrimitiveType -> '\u0000'
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
    }
}

/** Lambda for converting [Value] to a value of type [T] using the [ConverterContext]. */
internal typealias ValueConverter<T> = ConverterContext.(Value) -> T?

/**
 * Lambda for providing a default value, which may involve converting from a [Value] using the
 * [ConverterContext].
 */
internal typealias DefaultProvider = ConverterContext.() -> Any?

/** Contextual information provided to [ValueConverter]s. */
internal class ConverterContext(
    /** The [AnnotationItem] being converted, may be nested. */
    val annotation: AnnotationItem,

    /** The [Item] to which the [annotation] belongs. */
    val item: Item,
) {
    /** Tracks whether an error was encountered which would prevent instantiation. */
    var doNotInstantiate = false
        private set

    /**
     * Tracks whether a `null` placeholder was supplied to a synthetic Kotlin default constructor
     * for a missing required non-nullable parameter.
     */
    var hasNullPlaceholderForMissingParameter = false
        private set

    /** Report that attribute [name]'s [value] cannot be converted to [type]. */
    fun reportInvalidAttributeValue(name: String, value: Value, type: Type, label: String = "") {
        item.codebase.reporter.report(
            Issues.INVALID_ANNOTATION_BINDING,
            item,
            "Attribute '$name' is invalid: $label`${value.toValueString()}` cannot be converted to ${type.typeName}",
            location = annotation.fileLocation,
        )

        // There was some inconsistency encountered between the source annotation instance and the
        // bound class so do not instantiate an instance.
        doNotInstantiate = true
    }

    /** Report that no value was provided for the required attribute [name]. */
    fun reportMissingAttribute(name: String) {
        item.codebase.reporter.report(
            Issues.MISSING_REQUIRED_ATTRIBUTE,
            item,
            "Required attribute '$name' is missing on @${annotation.originalName}",
            location = annotation.fileLocation,
        )
    }

    /**
     * Record that a `null` placeholder was passed for a missing required non-nullable parameter on
     * a constructor with Kotlin default values.
     */
    fun markNullPlaceholderForMissingParameter() {
        hasNullPlaceholderForMissingParameter = true
    }

    /**
     * An error that could not be compensated for and which will cause instantiation to fail was
     * found.
     */
    fun doNotInstantiate() {
        doNotInstantiate = true
    }

    /** The [ClassResolver] that can be used to resolve [ClassTypeItem]s to [ClassItem]. */
    val classResolver: ClassResolver = annotation.annotationContext
}
