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

import kotlin.test.assertEquals
import org.junit.Test

class ConstructorClassFileInfoTest {
    @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.TYPE)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Nullable

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

    @Suppress("unused") inner class InnerWithParam(val name: String)

    @Test
    fun `Test inner class outer receiver parameter has null name`() {
        val ctor = InnerWithParam::class.java.declaredConstructors.single()
        val info = ConstructorClassFileInfo.of(ctor)
        assertEquals(listOf(null, "name"), info.parameterNames.toList())
        assertEquals(listOf(false, false), info.parameterNullability.toList())
    }
}
