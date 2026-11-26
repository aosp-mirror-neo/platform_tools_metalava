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

import kotlin.reflect.KClass
import kotlin.test.assertEquals
import org.junit.Test

class ConstructorClassFileInfoTest {
    @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.TYPE)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Nullable

    enum class SampleEnum {
        VALUE,
    }

    @Retention(AnnotationRetention.BINARY)
    annotation class NestedBinaryAnno(
        val text: String,
    )

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.BINARY)
    annotation class AllElementTypesBinaryAnno(
        val b: Byte = 1,
        val c: Char = 'a',
        val d: Double = 1.0,
        val f: Float = 1.0f,
        val i: Int = 1,
        val j: Long = 1L,
        val s: Short = 1,
        val z: Boolean = true,
        val str: String = "x",
        val cls: KClass<*> = String::class,
        val enumVal: SampleEnum = SampleEnum.VALUE,
        val nested: NestedBinaryAnno = NestedBinaryAnno("nested"),
        val arr: IntArray = [1, 2],
    )

    class NoArgs

    @Test
    fun `Test zero-parameter constructor`() {
        val ctor = NoArgs::class.java.declaredConstructors.single()
        val info = ConstructorClassFileInfo.of(ctor)
        assertEquals(emptyList(), info.parameterNames.toList())
        assertEquals(emptyList(), info.parameterNullability.toList())
    }

    @Suppress("unused")
    class RuntimeNullableParams(
        val required: String,
        @Nullable val optional: String,
        val count: Int,
    )

    @Test
    fun `Test parameter names from MethodParameters and runtime Nullable`() {
        val ctor = RuntimeNullableParams::class.java.declaredConstructors.single()
        val info = ConstructorClassFileInfo.of(ctor)
        assertEquals(listOf("required", "optional", "count"), info.parameterNames.toList())
        assertEquals(listOf(false, true, false), info.parameterNullability.toList())
    }

    @Test
    fun `Test local class with captured outer variable and runtime Nullable`() {
        val captured = "outer"
        @Suppress("unused")
        class LocalWithCapture(
            val first: String,
            @Nullable val second: String,
        ) {
            fun useCaptured() = captured
        }

        val ctor = LocalWithCapture::class.java.declaredConstructors.single()
        val info = ConstructorClassFileInfo.of(ctor)
        // The synthetic captured variable parameter is skipped; declared parameters align properly.
        val declaredNames = info.parameterNames.filterNotNull()
        val declaredNullability =
            info.parameterNames.indices
                .filter { info.parameterNames[it] != null }
                .map { info.parameterNullability[it] }
        assertEquals(listOf("first", "second"), declaredNames)
        assertEquals(listOf(false, true), declaredNullability)
    }

    @Suppress("unused")
    class MixedSlotTypes(
        val count: Long,
        val label: String?,
        val flag: Boolean,
    )

    @Test
    fun `Test Kotlin parameter nullability from classfile`() {
        val ctor = MixedSlotTypes::class.java.declaredConstructors.single()
        val info = ConstructorClassFileInfo.of(ctor)
        assertEquals(listOf("count", "label", "flag"), info.parameterNames.toList())
        assertEquals(listOf(false, true, false), info.parameterNullability.toList())
    }

    @Suppress("unused")
    class ComplexBinaryAnnotationParams(
        @param:AllElementTypesBinaryAnno(
            b = 2,
            c = 'z',
            d = 2.5,
            f = 3.5f,
            i = 42,
            j = 99L,
            s = 7,
            z = false,
            str = "hello",
            cls = Int::class,
            enumVal = SampleEnum.VALUE,
            nested = NestedBinaryAnno("inner"),
            arr = [10, 20],
        )
        val annotated: String,
        val nullableAfterAnnotated: String?,
    )

    @Test
    fun `Test skipping complex binary parameter annotations before Kotlin Nullable`() {
        val ctor = ComplexBinaryAnnotationParams::class.java.declaredConstructors.single()
        val info = ConstructorClassFileInfo.of(ctor)
        assertEquals(listOf("annotated", "nullableAfterAnnotated"), info.parameterNames.toList())
        assertEquals(listOf(false, true), info.parameterNullability.toList())
    }

    @Suppress("unused") inner class InnerWithParam(val name: String?)

    @Test
    fun `Test inner class outer receiver parameter has null name`() {
        val ctor = InnerWithParam::class.java.declaredConstructors.single()
        val info = ConstructorClassFileInfo.of(ctor)
        assertEquals(listOf(null, "name"), info.parameterNames.toList())
        assertEquals(listOf(false, true), info.parameterNullability.toList())
    }
}
