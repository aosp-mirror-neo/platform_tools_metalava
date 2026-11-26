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

package com.android.tools.metalava.testing

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.internal.builders.AllDefaultPossibilitiesBuilder
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.junit.runner.Runner
import org.junit.runner.manipulation.Filter
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.JUnit4
import org.junit.runners.ParentRunner
import org.junit.runners.model.InitializationError

/** Unit tests for [MetalavaTestSuiteRunner]. */
class MetalavaTestSuiteRunnerTest {
    /** Plain concrete JUnit 4 test class. */
    class ConcreteSample {
        @Test fun sampleTest() {}
    }

    /** Abstract base class with a [Test] method; must be skipped directly, run via subclasses. */
    abstract class AbstractSample {
        @Test fun inheritedTest() {}
    }

    /** Concrete subclass inheriting its [Test] method from [AbstractSample]. */
    class InheritedTestSample : AbstractSample()

    /** Concrete test class explicitly annotated with [RunWith]. */
    @RunWith(JUnit4::class)
    class RunWithSample {
        @Test fun runWithTest() {}
    }

    /** Interface that must be skipped. */
    interface InterfaceSample

    /** Non-static inner class that must be skipped. */
    inner class InnerSample {
        @Test fun shouldNotRun() {}
    }

    /** Public concrete helper class without [RunWith] or [Test] methods that must be skipped. */
    class HelperSample

    /** [ParentRunner] with zero children, simulating a fully provider-filtered runner. */
    class EmptyParentRunner(
        testClass: Class<*>,
        private val runnerName: String = testClass.name,
    ) : ParentRunner<Runner>(testClass) {
        /** Single-argument constructor invoked by JUnit [RunWith] reflection. */
        constructor(testClass: Class<*>) : this(testClass, testClass.name)

        override fun getName(): String = runnerName

        override fun getChildren(): List<Runner> = emptyList()

        override fun describeChild(child: Runner): Description = child.description

        override fun runChild(child: Runner, notifier: RunNotifier) = child.run(notifier)
    }

    /** [BlockJUnit4ClassRunner] with a custom group name, mirroring parameterized child runners. */
    private class NamedBlockJUnit4ClassRunner(
        testClass: Class<*>,
        private val runnerName: String,
    ) : BlockJUnit4ClassRunner(testClass) {
        override fun getName(): String = runnerName
    }

    /**
     * Nested [ParentRunner] containing a single empty child [ParentRunner], simulating
     * `ModelTestSuiteRunner` with an empty `InstanceRunner`.
     */
    class NestedEmptyParentRunner(testClass: Class<*>) : ParentRunner<Runner>(testClass) {
        /** Child runners created once so `filter` mutates the same instances used during runs. */
        private val childRunners = listOf<Runner>(EmptyParentRunner(testClass, "[]"))

        override fun getChildren(): List<Runner> = childRunners

        override fun describeChild(child: Runner): Description = child.description

        override fun runChild(child: Runner, notifier: RunNotifier) = child.run(notifier)
    }

    /**
     * Nested [ParentRunner] containing both an empty child [ParentRunner] and a runnable child
     * runner, verifying only the empty child subtree is pruned.
     */
    class PartiallyEmptyParentRunner(testClass: Class<*>) : ParentRunner<Runner>(testClass) {
        /** Child runners created once so `filter` mutates the same instances used during runs. */
        private val childRunners =
            listOf<Runner>(
                EmptyParentRunner(testClass, "[]"),
                NamedBlockJUnit4ClassRunner(testClass, "[runnable]"),
            )

        override fun getChildren(): List<Runner> = childRunners

        override fun describeChild(child: Runner): Description = child.description

        override fun runChild(child: Runner, notifier: RunNotifier) = child.run(notifier)
    }

    /** Sample class whose custom [ParentRunner] has zero children. */
    @RunWith(EmptyParentRunner::class) class EmptyParentRunnerSample

    /** Sample class whose nested [ParentRunner] contains only an empty child [ParentRunner]. */
    @RunWith(NestedEmptyParentRunner::class) class NestedEmptyParentRunnerSample

    /** Sample class whose nested [ParentRunner] has one empty child and one runnable child. */
    @RunWith(PartiallyEmptyParentRunner::class)
    class PartiallyEmptyParentRunnerSample {
        @Test fun partialTest() {}
    }

    /** Ignored class backed by a non-`Filterable` runner that must be preserved. */
    @Ignore("intentionally ignored")
    class IgnoredSample {
        @Test fun ignoredTest() {}
    }

    /** Suite fixture annotated with [RunWith] and a relative resource path. */
    @RunWith(MetalavaTestSuiteRunner::class)
    @TestClassListResource("metalava-test-suite-runner-classes.txt")
    class AnnotatedSuite

    /** Suite fixture whose resource list includes itself (must not recurse infinitely). */
    @RunWith(MetalavaTestSuiteRunner::class)
    @TestClassListResource("metalava-test-suite-runner-self.txt")
    class SelfReferencingSuite

    /** Suite fixture with a leading slash on the resource path. */
    @TestClassListResource("/metalava-test-suite-runner-classes.txt") class LeadingSlashSuite

    /** Suite fixture mixing runnable, empty, partially empty, and ignored runners. */
    @TestClassListResource("metalava-test-suite-runner-pruned.txt") class PrunedSuite

    /** Fixture whose candidate runners are all empty and pruned. */
    @TestClassListResource("metalava-test-suite-runner-all-pruned.txt") class AllPrunedSuite

    /** Fixture missing [TestClassListResource]. */
    class MissingAnnotationSuite

    /** Fixture with a blank [TestClassListResource] path. */
    @TestClassListResource("   ") class BlankResourceSuite

    /** Fixture pointing to a non-existent resource. */
    @TestClassListResource("does-not-exist.txt") class MissingResourceSuite

    /** Fixture pointing to a resource with no runnable test classes. */
    @TestClassListResource("metalava-test-suite-runner-empty.txt") class EmptySuite

    /** Runs [runner] via [JUnitCore] and returns the executed leaf test names. */
    private fun runAndCollectTests(runner: MetalavaTestSuiteRunner): List<String> {
        val executed = mutableListOf<String>()
        val core = JUnitCore()
        core.addListener(
            object : RunListener() {
                override fun testFinished(description: Description) {
                    executed.add("${description.testClass.simpleName}.${description.methodName}")
                }
            }
        )
        val result = core.run(runner)
        assertTrue(
            result.wasSuccessful(),
            "Expected suite to succeed, failures: ${result.failures}",
        )
        return executed
    }

    @Test
    fun `Test discovers concrete test classes and skips non-runnable entries`() {
        val executed = mutableListOf<String>()
        val core = JUnitCore()
        core.addListener(
            object : RunListener() {
                override fun testFinished(description: Description) {
                    executed.add("${description.testClass.simpleName}.${description.methodName}")
                }
            }
        )
        val result = core.run(AnnotatedSuite::class.java)
        assertTrue(
            result.wasSuccessful(),
            "Expected suite to succeed, failures: ${result.failures}",
        )
        assertEquals(
            listOf(
                "ConcreteSample.sampleTest",
                "InheritedTestSample.inheritedTest",
                "RunWithSample.runWithTest",
            ),
            executed,
        )
    }

    @Test
    fun `Test rejects local and anonymous classes even with Test methods`() {
        class LocalSample {
            @Test fun shouldNotRun() {}
        }
        val anonymousSample =
            object {
                @Test fun shouldNotRun() {}
            }
        assertFalse(MetalavaTestSuiteRunner.isRunnableTestClass(LocalSample::class.java))
        assertFalse(MetalavaTestSuiteRunner.isRunnableTestClass(anonymousSample.javaClass))
    }

    @Test
    fun `Test skips self reference to prevent infinite recursion`() {
        val runner =
            MetalavaTestSuiteRunner(
                SelfReferencingSuite::class.java,
                AllDefaultPossibilitiesBuilder(),
            )
        val executed = runAndCollectTests(runner)
        assertEquals(listOf("ConcreteSample.sampleTest"), executed)
    }

    @Test
    fun `Test supports leading slash on resource path`() {
        val runner =
            MetalavaTestSuiteRunner(
                LeadingSlashSuite::class.java,
                AllDefaultPossibilitiesBuilder(),
            )
        val executed = runAndCollectTests(runner)
        assertEquals(
            listOf(
                "ConcreteSample.sampleTest",
                "InheritedTestSample.inheritedTest",
                "RunWithSample.runWithTest",
            ),
            executed,
        )
    }

    @Test
    fun `Test fails when TestClassListResource annotation is missing`() {
        val error =
            assertFailsWith<InitializationError> {
                MetalavaTestSuiteRunner(
                    MissingAnnotationSuite::class.java,
                    AllDefaultPossibilitiesBuilder(),
                )
            }
        assertTrue(
            error.causes.single().message!!.contains("TestClassListResource"),
            "Unexpected error: ${error.causes}",
        )
    }

    @Test
    fun `Test fails when resource path is blank`() {
        val error =
            assertFailsWith<InitializationError> {
                MetalavaTestSuiteRunner(
                    BlankResourceSuite::class.java,
                    AllDefaultPossibilitiesBuilder(),
                )
            }
        assertTrue(
            error.causes.single().message!!.contains("non-empty resource path"),
            "Unexpected error: ${error.causes}",
        )
    }

    @Test
    fun `Test fails when resource does not exist`() {
        val error =
            assertFailsWith<InitializationError> {
                MetalavaTestSuiteRunner(
                    MissingResourceSuite::class.java,
                    AllDefaultPossibilitiesBuilder(),
                )
            }
        assertTrue(
            error.causes.single().message!!.contains("does-not-exist.txt"),
            "Unexpected error: ${error.causes}",
        )
    }

    @Test
    fun `Test fails when resource contains no runnable test classes`() {
        val error =
            assertFailsWith<InitializationError> {
                MetalavaTestSuiteRunner(
                    EmptySuite::class.java,
                    AllDefaultPossibilitiesBuilder(),
                )
            }
        assertTrue(
            error.causes.single().message!!.contains("No runnable test classes found"),
            "Unexpected error: ${error.causes}",
        )
    }

    /** Collects all leaf descriptions (`isTest == true`) from [description]. */
    private fun collectLeafDescriptions(description: Description): List<Description> {
        if (description.isTest) return listOf(description)
        return description.children.flatMap { collectLeafDescriptions(it) }
    }

    @Test
    fun `Test prunes empty ParentRunners while preserving runnable and ignored runners`() {
        val runner =
            MetalavaTestSuiteRunner(
                PrunedSuite::class.java,
                AllDefaultPossibilitiesBuilder(),
            )

        // Neither the empty ParentRunner nor the nested empty ParentRunner should appear as
        // childless leaf descriptions; the non-Filterable IgnoredClassRunner is preserved.
        val leafDisplayNames = collectLeafDescriptions(runner.description).map { it.displayName }
        assertEquals(
            listOf(
                "sampleTest(${ConcreteSample::class.java.name})",
                "partialTest(${PartiallyEmptyParentRunnerSample::class.java.name})",
                IgnoredSample::class.java.name,
            ),
            leafDisplayNames,
        )

        val executed = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        val core = JUnitCore()
        core.addListener(
            object : RunListener() {
                override fun testFinished(description: Description) {
                    executed.add("${description.testClass.simpleName}.${description.methodName}")
                }

                override fun testIgnored(description: Description) {
                    ignored.add(description.displayName)
                }
            }
        )
        val result = core.run(runner)
        assertTrue(
            result.wasSuccessful(),
            "Expected suite to succeed, failures: ${result.failures}",
        )
        assertEquals(
            listOf(
                "ConcreteSample.sampleTest",
                "PartiallyEmptyParentRunnerSample.partialTest",
            ),
            executed,
        )
        assertEquals(listOf(IgnoredSample::class.java.name), ignored)
    }

    @Test
    fun `Test sharding filter succeeds for each leaf test after pruning empty runners`() {
        val initialRunner =
            MetalavaTestSuiteRunner(
                PrunedSuite::class.java,
                AllDefaultPossibilitiesBuilder(),
            )
        val runnableLeaves =
            collectLeafDescriptions(initialRunner.description).filter { it.methodName != null }
        assertEquals(2, runnableLeaves.size)

        // Simulate Blaze sharding: construct a fresh runner and filter it down to each leaf
        // description individually, verifying Filter.apply succeeds without NoTestsRemainException.
        for (leaf in runnableLeaves) {
            val shardedRunner =
                MetalavaTestSuiteRunner(
                    PrunedSuite::class.java,
                    AllDefaultPossibilitiesBuilder(),
                )
            shardedRunner.filter(Filter.matchMethodDescription(leaf))
            val executed = runAndCollectTests(shardedRunner)
            assertEquals(listOf("${leaf.testClass.simpleName}.${leaf.methodName}"), executed)
        }
    }

    @Test
    fun `Test fails when all runners are pruned as empty`() {
        val error =
            assertFailsWith<InitializationError> {
                MetalavaTestSuiteRunner(
                    AllPrunedSuite::class.java,
                    AllDefaultPossibilitiesBuilder(),
                )
            }
        assertTrue(
            error.causes
                .single()
                .message!!
                .contains("No runnable tests remained after pruning empty runners"),
            "Unexpected error: ${error.causes}",
        )
    }
}
