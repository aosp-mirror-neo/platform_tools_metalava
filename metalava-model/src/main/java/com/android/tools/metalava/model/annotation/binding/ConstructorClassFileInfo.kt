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

import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * Parameter names and nullability flags extracted for a [Constructor] using standard Java
 * reflection metadata.
 */
internal class ConstructorClassFileInfo(
    /** Parameter names by index, or `null` if a parameter is synthetic/implicit or unnamed. */
    val parameterNames: Array<String?>,

    /** Whether each parameter allows `null`. */
    val parameterNullability: BooleanArray,
) {
    companion object {
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

            return ConstructorClassFileInfo(names, nullable)
        }
    }
}
