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

import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ReflectedConstructorTest {
    data class SimplePair(
        val label: String,
        val count: Int,
    )

    data class WithDefaults(
        val label: String = "default",
        val count: Int = 42,
    )

    data class AllDefaultedWithUnitGetter(
        val first: Int = 1,
        val second: String = "a",
    ) {
        // Puts `()V` into `@Metadata.data2`, colliding with the generated no-arg constructor.
        val unit: Unit
            get() = Unit
    }

    data class JvmOverloadsWithMutableInt
    @JvmOverloads
    constructor(
        val first: Int = 1,
        val second: String = "a",
    ) {
        // Setter `setMutableInt` puts `(I)V` into `@Metadata.data2`, colliding with
        // `@JvmOverloads`.
        var mutableInt: Int = 0
    }

    @Suppress("unused") class PrivateConstructor private constructor(val value: String)

    @Suppress("unused")
    class PrivateWithCompanion private constructor(val value: String) {
        companion object {
            fun create(v: String) = PrivateWithCompanion(v)
        }
    }

    data class AnnotatedJvmOverloads
    @BindingConstructor
    @JvmOverloads
    constructor(
        val first: Int = 1,
        val second: String = "a",
    )

    data class AnnotatedAllDefaultedWithSecondary
    @BindingConstructor
    constructor(
        val first: Int = 1,
        val second: String = "a",
    ) {
        @Suppress("unused") constructor(both: String) : this(both.length, both)
    }

    @JvmInline value class InlineInt(val value: Int)

    @JvmInline value class InlineString(val value: String)

    data class PrimaryWithSecondaryValueClass @BindingConstructor constructor(val a: String) {
        @Suppress("unused") constructor(a: String, v: InlineInt) : this("$a:${v.value}")
    }

    @Suppress("unused") data class UnboxedValueClassParam(val inlineInt: InlineInt)

    @Suppress("unused")
    data class BoxedValueClassParam(
        val inlineInt: InlineInt? = InlineInt(8),
        val value: String,
    )

    @Suppress("unused") data class UnboxedNullableStringValueClassParam(val s: InlineString?)

    @Suppress("unused")
    data class ValueClassWithDefaults(
        val inlineInt: InlineInt = InlineInt(0),
        val label: String = "a",
    )

    data class CrossParamDefault(
        val required: String,
        val derived: Int = required.length,
    )

    data class CrossParamExtensionDefault(
        val required: String,
        val padded: String = required.padStart(4, '0'),
    )

    @Suppress("unused")
    class BrokenInit(
        val s: String? = null,
        val count: Int = 1,
    ) {
        val length: Int = s!!.length
    }

    @Test
    fun `Test direct instantiation without defaults`() {
        val reflected = ReflectedConstructor.of(SimplePair::class.java)
        assertFalse(reflected.hasDefaultConstructor)
        val unset = Any()
        val instance = reflected.newInstance(arrayOf("hello", 3), null, unset)
        assertEquals(SimplePair("hello", 3), instance)
    }

    @Test
    fun `Test instantiation with Kotlin default parameter bitmask`() {
        val reflected = ReflectedConstructor.of(WithDefaults::class.java)
        assertTrue(reflected.hasDefaultConstructor)
        val unset = Any()
        val instance = reflected.newInstance(arrayOf("explicit", 0), intArrayOf(1 shl 1), unset)
        assertEquals(WithDefaults("explicit", 42), instance)
    }

    @Test
    fun `Test unbound parameter sentinel throws IllegalArgumentException`() {
        val reflected = ReflectedConstructor.of(SimplePair::class.java)
        val unset = Any()
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                reflected.newInstance(arrayOf(unset, 1), null, unset)
            }
        assertTrue(
            exception.message!!.startsWith(
                "No argument provided for a required parameter: parameter #0 label of "
            )
        )
    }

    @Test
    fun `Test newInstance validates argument count, default constructor availability, and mask length`() {
        val unset = Any()

        val simple = ReflectedConstructor.of(SimplePair::class.java)
        val wrongArgCount =
            assertThrows(IllegalArgumentException::class.java) {
                simple.newInstance(arrayOf("onlyOne"), null, unset)
            }
        assertTrue(wrongArgCount.message!!.startsWith("Expected 2 arguments for "))

        val tooManyArgs =
            assertThrows(IllegalArgumentException::class.java) {
                simple.newInstance(arrayOf("a", 1, "extra"), null, unset)
            }
        assertTrue(tooManyArgs.message!!.startsWith("Expected 2 arguments for "))

        val noDefaultCtor =
            assertThrows(IllegalArgumentException::class.java) {
                simple.newInstance(arrayOf("a", 1), intArrayOf(0), unset)
            }
        assertTrue(noDefaultCtor.message!!.startsWith("No default constructor available for "))

        val withDefaults = ReflectedConstructor.of(WithDefaults::class.java)
        val wrongMaskLen =
            assertThrows(IllegalArgumentException::class.java) {
                withDefaults.newInstance(arrayOf("a", 1), intArrayOf(), unset)
            }
        assertTrue(wrongMaskLen.message!!.startsWith("Expected 1 default mask word(s) for "))
    }

    @Test
    fun `Test all-defaulted primary constructor ignores generated no-arg forwarding constructor`() {
        val reflected = ReflectedConstructor.of(AllDefaultedWithUnitGetter::class.java)
        assertEquals(2, reflected.parameterCount)
        assertTrue(reflected.hasDefaultConstructor)
        val unset = Any()
        assertEquals(
            AllDefaultedWithUnitGetter(42, "b"),
            reflected.newInstance(arrayOf(42, "b"), null, unset),
        )
        assertEquals(
            AllDefaultedWithUnitGetter(1, "a"),
            reflected.newInstance(arrayOf(0, null), intArrayOf(0b11), unset),
        )
    }

    @Test
    fun `Test JvmOverloads constructor ignores generated telescopic overloads`() {
        val reflected = ReflectedConstructor.of(JvmOverloadsWithMutableInt::class.java)
        assertEquals(2, reflected.parameterCount)
        assertTrue(reflected.hasDefaultConstructor)
        val unset = Any()
        assertEquals(
            JvmOverloadsWithMutableInt(42, "b"),
            reflected.newInstance(arrayOf(42, "b"), null, unset),
        )
        assertEquals(
            JvmOverloadsWithMutableInt(1, "a"),
            reflected.newInstance(arrayOf(0, null), intArrayOf(0b11), unset),
        )
    }

    @Test
    fun `Test BindingConstructor on JvmOverloads and all-defaulted primary ignores generated overloads`() {
        val unset = Any()

        val jvmOverloads = ReflectedConstructor.of(AnnotatedJvmOverloads::class.java)
        assertEquals(2, jvmOverloads.parameterCount)
        assertTrue(jvmOverloads.hasDefaultConstructor)
        assertEquals(
            AnnotatedJvmOverloads(1, "a"),
            jvmOverloads.newInstance(arrayOf(0, null), intArrayOf(0b11), unset),
        )

        val allDefaulted = ReflectedConstructor.of(AnnotatedAllDefaultedWithSecondary::class.java)
        assertEquals(2, allDefaulted.parameterCount)
        assertTrue(allDefaulted.hasDefaultConstructor)
        assertEquals(
            AnnotatedAllDefaultedWithSecondary(1, "a"),
            allDefaulted.newInstance(arrayOf(0, null), intArrayOf(0b11), unset),
        )
    }

    @Test
    fun `Test value class target is rejected`() {
        val exception =
            assertThrows(IllegalStateException::class.java) {
                ReflectedConstructor.of(InlineInt::class.java)
            }
        assertEquals(
            "Cannot create an instance of value class com.android.tools.metalava.model.annotation.binding.ReflectedConstructorTest.InlineInt",
            exception.message,
        )
    }

    @Test
    fun `Test private binding constructor is rejected`() {
        for (cls in listOf(PrivateConstructor::class.java, PrivateWithCompanion::class.java)) {
            val exception =
                assertThrows(IllegalStateException::class.java) { ReflectedConstructor.of(cls) }
            assertEquals(
                "Cannot create an instance of ${cls.canonicalName} as its binding constructor is private",
                exception.message,
            )
        }
    }

    @Test
    fun `Test secondary value class constructor is not mistaken for default constructor`() {
        val reflected = ReflectedConstructor.of(PrimaryWithSecondaryValueClass::class.java)
        assertFalse(reflected.hasDefaultConstructor)
        val unset = Any()
        assertEquals(
            PrimaryWithSecondaryValueClass("hello"),
            reflected.newInstance(arrayOf("hello"), null, unset),
        )
    }

    @Test
    fun `Test constructors with value class parameters are rejected`() {
        for (cls in
            listOf(
                UnboxedValueClassParam::class.java,
                BoxedValueClassParam::class.java,
                UnboxedNullableStringValueClassParam::class.java,
                ValueClassWithDefaults::class.java,
            )) {
            val exception =
                assertThrows(IllegalStateException::class.java) { ReflectedConstructor.of(cls) }
            assertEquals(
                "Cannot create an instance of ${cls.canonicalName} as its binding constructor has value class parameters",
                exception.message,
            )
        }
    }

    @Test
    fun `Test missing parameter invocation failure matches Intrinsics and cross-param defaults`() {
        val unset = Any()

        val reflected = ReflectedConstructor.of(CrossParamDefault::class.java)

        // Null placeholder dereferenced directly inside the synthetic default constructor.
        val crossParamException =
            assertThrows(InvocationTargetException::class.java) {
                reflected.newInstance(arrayOf(null, 0), intArrayOf(0b11), unset)
            }
        assertTrue(
            reflected.isMissingParameterInvocationFailure(
                crossParamException.targetException,
                usedDefaultMasks = true,
            )
        )

        // Null placeholder rejected by Intrinsics.checkNotNullParameter in `<init>`.
        val directException =
            assertThrows(InvocationTargetException::class.java) {
                reflected.newInstance(arrayOf(null, 5), null, unset)
            }
        assertTrue(
            reflected.isMissingParameterInvocationFailure(
                directException.targetException,
                usedDefaultMasks = false,
            )
        )

        // Null placeholder passed to a non-inline stdlib extension inside a default expression.
        val extReflected = ReflectedConstructor.of(CrossParamExtensionDefault::class.java)
        val extException =
            assertThrows(InvocationTargetException::class.java) {
                extReflected.newInstance(arrayOf(null, null), intArrayOf(0b11), unset)
            }
        assertTrue(
            extReflected.isMissingParameterInvocationFailure(
                extException.targetException,
                usedDefaultMasks = true,
            )
        )

        // Genuine NPE inside a property initializer / init block must not be suppressed.
        val brokenInit = ReflectedConstructor.of(BrokenInit::class.java)
        val brokenViaDefaults =
            assertThrows(InvocationTargetException::class.java) {
                brokenInit.newInstance(arrayOf(null, 0), intArrayOf(0b11), unset)
            }
        assertFalse(
            brokenInit.isMissingParameterInvocationFailure(
                brokenViaDefaults.targetException,
                usedDefaultMasks = true,
            )
        )

        val brokenDirect =
            assertThrows(InvocationTargetException::class.java) {
                brokenInit.newInstance(arrayOf(null, 1), null, unset)
            }
        assertFalse(
            brokenInit.isMissingParameterInvocationFailure(
                brokenDirect.targetException,
                usedDefaultMasks = false,
            )
        )
    }

    @Test
    fun `Test ReflectedConstructor is cached per Class`() {
        val first = ReflectedConstructor.of(SimplePair::class.java)
        val second = ReflectedConstructor.of(SimplePair::class.java)
        assertSame(first, second)
    }
}
