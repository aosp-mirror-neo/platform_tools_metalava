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

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * Parameter names and nullability flags extracted for a [Constructor], combining standard Java
 * reflection metadata with JVM `.class` attributes when needed.
 *
 * Kotlin `T?` parameter nullability is read from `RuntimeInvisibleParameterAnnotations`, which
 * `kotlinc` emits for non-private constructors of non-local, non-anonymous classes. Local classes,
 * anonymous classes, and `private` constructors do not receive invisible nullability annotations
 * from `kotlinc` and only reflect runtime-retained `@Nullable` annotations.
 */
internal class ConstructorClassFileInfo(
    /** Parameter names by index, or `null` if a parameter is synthetic/implicit or unnamed. */
    val parameterNames: Array<String?>,

    /** Whether each parameter allows `null` (`T?` in Kotlin or `@Nullable` in Java). */
    val parameterNullability: BooleanArray,
) {
    companion object {
        private const val CLASS_FILE_MAGIC = 0xCAFEBABE.toInt()
        private const val KOTLIN_NULLABLE_DESCRIPTOR = "Lorg/jetbrains/annotations/Nullable;"

        // JVMS §4.4 Table 4.4-A constant pool tags.
        private const val CONSTANT_UTF8 = 1
        private const val CONSTANT_INTEGER = 3
        private const val CONSTANT_FLOAT = 4
        private const val CONSTANT_LONG = 5
        private const val CONSTANT_DOUBLE = 6
        private const val CONSTANT_CLASS = 7
        private const val CONSTANT_STRING = 8
        private const val CONSTANT_FIELDREF = 9
        private const val CONSTANT_METHODREF = 10
        private const val CONSTANT_INTERFACE_METHODREF = 11
        private const val CONSTANT_NAME_AND_TYPE = 12
        private const val CONSTANT_METHOD_HANDLE = 15
        private const val CONSTANT_METHOD_TYPE = 16
        private const val CONSTANT_DYNAMIC = 17
        private const val CONSTANT_INVOKE_DYNAMIC = 18
        private const val CONSTANT_MODULE = 19
        private const val CONSTANT_PACKAGE = 20

        // JVMS §4.7.16.1 Table 4.7.16.1-A annotation element_value tags.
        private const val ELEMENT_VALUE_BYTE = 'B'
        private const val ELEMENT_VALUE_CHAR = 'C'
        private const val ELEMENT_VALUE_DOUBLE = 'D'
        private const val ELEMENT_VALUE_FLOAT = 'F'
        private const val ELEMENT_VALUE_INT = 'I'
        private const val ELEMENT_VALUE_LONG = 'J'
        private const val ELEMENT_VALUE_SHORT = 'S'
        private const val ELEMENT_VALUE_BOOLEAN = 'Z'
        private const val ELEMENT_VALUE_STRING = 's'
        private const val ELEMENT_VALUE_ENUM = 'e'
        private const val ELEMENT_VALUE_CLASS = 'c'
        private const val ELEMENT_VALUE_ANNOTATION = '@'
        private const val ELEMENT_VALUE_ARRAY = '['

        /** Compute the JVM method descriptor (`(paramDescriptors...)V`) of this [Constructor]. */
        internal fun Constructor<*>.jvmDescriptor(): String = buildString {
            append('(')
            for (parameterType in parameterTypes) {
                append(parameterType.descriptorString())
            }
            append(")V")
        }

        /** Extract [ConstructorClassFileInfo] for [constructor]. */
        fun of(
            constructor: Constructor<*>,
        ): ConstructorClassFileInfo {
            val declaringClass = constructor.declaringClass
            val paramTypes = constructor.parameterTypes
            val parameters = constructor.parameters
            val count = paramTypes.size

            val names = arrayOfNulls<String>(count)
            val nullable = BooleanArray(count)
            if (count == 0) {
                return ConstructorClassFileInfo(names, nullable)
            }

            // Parameter 0 of a non-static inner class constructor is the enclosing instance
            // (`this$0`) and does not correspond to a user-declared parameter.
            val isInnerClass =
                declaringClass.isMemberClass && !Modifier.isStatic(declaringClass.modifiers)

            // Hoist `parameterAnnotations` once to avoid reparsing per parameter and to safely
            // align annotations on local/anonymous classes whose synthetic parameters are omitted
            // from `RuntimeVisibleParameterAnnotations`.
            val parameterAnnotations = constructor.parameterAnnotations
            var declaredIndex = 0
            for (i in 0 until count) {
                val p = parameters[i]
                if ((isInnerClass && i == 0) || p.isImplicit || p.isSynthetic) {
                    continue
                }
                val currentDeclaredIndex = declaredIndex++
                if (p.isNamePresent) {
                    names[i] = p.name
                }

                if (!paramTypes[i].isPrimitive) {
                    val paramAnnos =
                        if (parameterAnnotations.size == count) {
                            parameterAnnotations[i]
                        } else {
                            parameterAnnotations.getOrNull(currentDeclaredIndex) ?: emptyArray()
                        }
                    if (
                        paramAnnos.any { it.annotationClass.java.simpleName == "Nullable" } ||
                            p.annotatedType.annotations.any {
                                it.annotationClass.java.simpleName == "Nullable"
                            }
                    ) {
                        nullable[i] = true
                    }
                }
            }

            // Kotlin emits `@org.jetbrains.annotations.Nullable` into
            // `RuntimeInvisibleParameterAnnotations` (`RetentionPolicy.CLASS`) for non-private,
            // non-synthetic constructors of non-local, non-anonymous classes.
            val needInvisibleNullability =
                declaringClass.isAnnotationPresent(Metadata::class.java) &&
                    !Modifier.isPrivate(constructor.modifiers) &&
                    !constructor.isSynthetic &&
                    !declaringClass.isLocalClass &&
                    !declaringClass.isAnonymousClass &&
                    paramTypes.any { !it.isPrimitive }

            if (needInvisibleNullability) {
                readInvisibleNullabilityFromClassFile(constructor, nullable)
            }

            return ConstructorClassFileInfo(names, nullable)
        }

        /**
         * Scan all `<init>` methods in [declaringClass]'s `.class` resource (JVMS §4.1–4.7),
         * invoking [onConstructorAttribute] for each attribute of every `<init>` method. Returning
         * `true` from [onConstructorComplete] stops scanning further methods.
         */
        internal fun scanClassFileConstructors(
            declaringClass: Class<*>,
            onConstructorAttribute:
                (
                    accessFlags: Int,
                    descriptor: String,
                    attrName: String?,
                    attrLen: Int,
                    input: DataInputStream,
                    utf8: Array<String?>,
                ) -> Boolean,
            onConstructorComplete: (accessFlags: Int, descriptor: String) -> Boolean = { _, _ ->
                false
            },
        ) {
            val resourcePath = declaringClass.name.replace('.', '/') + ".class"
            val stream =
                declaringClass.classLoader?.getResourceAsStream(resourcePath)
                    ?: ClassLoader.getSystemResourceAsStream(resourcePath)
                    ?: return

            stream.use { raw ->
                val input = DataInputStream(raw.buffered())
                if (input.readInt() != CLASS_FILE_MAGIC) return
                input.skipBytesFully(4) // minor_version + major_version

                // Parse constant pool, retaining UTF-8 strings for descriptor/attribute matching.
                val cpCount = input.readUnsignedShort()
                val utf8 = arrayOfNulls<String>(cpCount)
                var cpIndex = 1
                while (cpIndex < cpCount) {
                    when (val tag = input.readUnsignedByte()) {
                        CONSTANT_UTF8 -> utf8[cpIndex] = input.readUTF()
                        CONSTANT_INTEGER,
                        CONSTANT_FLOAT,
                        CONSTANT_FIELDREF,
                        CONSTANT_METHODREF,
                        CONSTANT_INTERFACE_METHODREF,
                        CONSTANT_NAME_AND_TYPE,
                        CONSTANT_DYNAMIC,
                        CONSTANT_INVOKE_DYNAMIC -> input.skipBytesFully(4)
                        CONSTANT_LONG,
                        CONSTANT_DOUBLE -> {
                            // CONSTANT_Long and CONSTANT_Double occupy two constant pool entries.
                            input.skipBytesFully(8)
                            cpIndex++
                        }
                        CONSTANT_CLASS,
                        CONSTANT_STRING,
                        CONSTANT_METHOD_TYPE,
                        CONSTANT_MODULE,
                        CONSTANT_PACKAGE -> input.skipBytesFully(2)
                        CONSTANT_METHOD_HANDLE -> input.skipBytesFully(3)
                        else -> error("Unknown constant pool tag $tag in ${declaringClass.name}")
                    }
                    cpIndex++
                }

                input.skipBytesFully(6) // access_flags, this_class, super_class
                val interfacesCount = input.readUnsignedShort()
                input.skipBytesFully(interfacesCount * 2)

                val fieldsCount = input.readUnsignedShort()
                repeat(fieldsCount) {
                    input.skipBytesFully(6)
                    val fieldAttrs = input.readUnsignedShort()
                    repeat(fieldAttrs) {
                        input.skipBytesFully(2)
                        input.skipBytesFully(input.readInt())
                    }
                }

                val methodsCount = input.readUnsignedShort()
                for (m in 0 until methodsCount) {
                    val accessFlags = input.readUnsignedShort()
                    val nameIdx = input.readUnsignedShort()
                    val descIdx = input.readUnsignedShort()
                    val attrsCount = input.readUnsignedShort()
                    val isInit = utf8[nameIdx] == "<init>"
                    val descriptor = utf8[descIdx]

                    repeat(attrsCount) {
                        val attrName = utf8[input.readUnsignedShort()]
                        val attrLen = input.readInt()
                        val consumed =
                            isInit &&
                                descriptor != null &&
                                onConstructorAttribute(
                                    accessFlags,
                                    descriptor,
                                    attrName,
                                    attrLen,
                                    input,
                                    utf8,
                                )
                        if (!consumed) {
                            input.skipBytesFully(attrLen)
                        }
                    }

                    if (
                        isInit &&
                            descriptor != null &&
                            onConstructorComplete(accessFlags, descriptor)
                    ) {
                        return
                    }
                }
            }
        }

        /**
         * Read `RuntimeInvisibleParameterAnnotations` for [constructor] from its `.class` resource
         * stream (JVMS §4.1–4.7).
         */
        private fun readInvisibleNullabilityFromClassFile(
            constructor: Constructor<*>,
            nullable: BooleanArray,
        ) {
            val targetDescriptor = constructor.jvmDescriptor()
            val count = constructor.parameterCount

            scanClassFileConstructors(
                declaringClass = constructor.declaringClass,
                onConstructorAttribute = { _, descriptor, attrName, attrLen, input, utf8 ->
                    if (
                        descriptor == targetDescriptor &&
                            attrName == "RuntimeInvisibleParameterAnnotations"
                    ) {
                        val bytes = ByteArray(attrLen)
                        input.readFully(bytes)
                        parseInvisibleParameterAnnotations(bytes, utf8, count, nullable)
                        true
                    } else {
                        false
                    }
                },
                onConstructorComplete = { _, descriptor -> descriptor == targetDescriptor },
            )
        }

        /** Parse `RuntimeInvisibleParameterAnnotations` attribute bytes (JVMS §4.7.19). */
        private fun parseInvisibleParameterAnnotations(
            bytes: ByteArray,
            utf8: Array<String?>,
            paramCount: Int,
            nullable: BooleanArray,
        ) {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val numParams = input.readUnsignedByte()
            val offset = (paramCount - numParams).coerceAtLeast(0)
            for (p in 0 until numParams) {
                val numAnnos = input.readUnsignedShort()
                repeat(numAnnos) {
                    val typeIdx = input.readUnsignedShort()
                    if (utf8[typeIdx] == KOTLIN_NULLABLE_DESCRIPTOR && offset + p < paramCount) {
                        nullable[offset + p] = true
                    }
                    val numPairs = input.readUnsignedShort()
                    repeat(numPairs) {
                        input.skipBytesFully(2)
                        input.skipElementValue()
                    }
                }
            }
        }

        internal fun DataInputStream.skipBytesFully(n: Int) {
            var remaining = n
            while (remaining > 0) {
                val skipped = skipBytes(remaining)
                if (skipped <= 0) {
                    readByte()
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }

        private fun DataInputStream.skipElementValue() {
            when (val tag = readUnsignedByte().toChar()) {
                ELEMENT_VALUE_BYTE,
                ELEMENT_VALUE_CHAR,
                ELEMENT_VALUE_DOUBLE,
                ELEMENT_VALUE_FLOAT,
                ELEMENT_VALUE_INT,
                ELEMENT_VALUE_LONG,
                ELEMENT_VALUE_SHORT,
                ELEMENT_VALUE_BOOLEAN,
                ELEMENT_VALUE_STRING,
                ELEMENT_VALUE_CLASS -> skipBytesFully(2)
                ELEMENT_VALUE_ENUM -> skipBytesFully(4)
                ELEMENT_VALUE_ANNOTATION -> {
                    skipBytesFully(2)
                    val numPairs = readUnsignedShort()
                    repeat(numPairs) {
                        skipBytesFully(2)
                        skipElementValue()
                    }
                }
                ELEMENT_VALUE_ARRAY -> {
                    val numValues = readUnsignedShort()
                    repeat(numValues) { skipElementValue() }
                }
                else -> error("Unknown annotation element_value tag '$tag'")
            }
        }
    }
}
