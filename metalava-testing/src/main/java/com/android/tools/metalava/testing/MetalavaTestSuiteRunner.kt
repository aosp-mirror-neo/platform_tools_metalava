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

import java.lang.reflect.Modifier
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runner.Runner
import org.junit.runner.manipulation.Filter
import org.junit.runner.manipulation.NoTestsRemainException
import org.junit.runners.Suite
import org.junit.runners.model.InitializationError
import org.junit.runners.model.RunnerBuilder

/**
 * Specifies the classpath resource containing newline-separated fully-qualified candidate test
 * class names to be discovered and run by [MetalavaTestSuiteRunner].
 *
 * Blank lines and lines starting with `#` are ignored.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class TestClassListResource(
    /** Classpath-relative path to the test class list resource. */
    val value: String,
)

/**
 * A JUnit 4 [Suite] runner that loads candidate test class names from the classpath resource named
 * by [TestClassListResource], filters out non-runnable classes, prunes empty `Filterable` subtrees,
 * and delegates the remaining runners to [Suite].
 *
 * Candidate entries that do not correspond to a loadable public concrete JUnit 4 test class (such
 * as Kotlin files containing only top-level helpers, interfaces, abstract base classes, non-public
 * classes, or helper classes without [RunWith] or [Test] methods) are skipped. Child runners whose
 * tests were all provider-filtered out (leaving zero leaf tests) are also pruned so sharding
 * filters do not mistake childless `ParentRunner` descriptions for leaf tests.
 */
class MetalavaTestSuiteRunner(
    suiteClass: Class<*>,
    builder: RunnerBuilder,
) : Suite(suiteClass, buildRunners(suiteClass, builder)) {
    companion object {
        /**
         * Pass-through [Filter] whose [shouldRun] always returns `true`.
         *
         * Unlike [Filter.ALL] (whose [Filter.apply] is a no-op), applying this filter invokes
         * `Filterable.filter` recursively so empty `ParentRunner` subtrees are pruned and throw
         * [NoTestsRemainException], while non-`Filterable` runners (such as `IgnoredClassRunner`
         * and `ErrorReportingRunner`) are preserved.
         */
        private val PRUNE_EMPTY_FILTER =
            object : Filter() {
                override fun shouldRun(description: Description) = true

                override fun describe() = "prune empty runners"
            }

        /**
         * Builds JUnit 4 runners for the runnable test classes declared for [suiteClass] and prunes
         * any `Filterable` runners (and nested child runners) with no remaining leaf tests.
         */
        private fun buildRunners(
            suiteClass: Class<*>,
            builder: RunnerBuilder,
        ): List<Runner> {
            val testClasses = loadTestClasses(suiteClass)
            val runners =
                builder.runners(suiteClass, testClasses).filter { runner ->
                    try {
                        PRUNE_EMPTY_FILTER.apply(runner)
                        true
                    } catch (_: NoTestsRemainException) {
                        false
                    }
                }
            if (runners.isEmpty()) {
                throw InitializationError(
                    "No runnable tests remained after pruning empty runners for ${suiteClass.name}"
                )
            }
            return runners
        }

        /**
         * Reads candidate class names from the resource named by [TestClassListResource] on
         * [suiteClass] and returns the concrete runnable JUnit 4 test classes.
         */
        private fun loadTestClasses(suiteClass: Class<*>): Array<Class<*>> {
            val annotation =
                suiteClass.getAnnotation(TestClassListResource::class.java)
                    ?: throw InitializationError(
                        "${suiteClass.name} must be annotated with " +
                            "@${TestClassListResource::class.java.simpleName}"
                    )
            val resourcePath = annotation.value.trim()
            if (resourcePath.isEmpty()) {
                throw InitializationError(
                    "@${TestClassListResource::class.java.simpleName} on ${suiteClass.name} " +
                        "must specify a non-empty resource path"
                )
            }

            val classLoader = suiteClass.classLoader ?: ClassLoader.getSystemClassLoader()
            val normalizedPath = resourcePath.removePrefix("/")
            val stream =
                classLoader.getResourceAsStream(normalizedPath)
                    ?: throw InitializationError(
                        "Could not find test class list resource '$resourcePath' for " +
                            suiteClass.name
                    )

            val candidateNames =
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    reader
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .distinct()
                        .toList()
                }

            val testClasses =
                candidateNames.mapNotNull { className ->
                    val cls =
                        try {
                            // Avoid running static initializers for candidate classes that may be
                            // non-test helpers or abstract base classes.
                            Class.forName(className, false, classLoader)
                        } catch (_: ClassNotFoundException) {
                            return@mapNotNull null
                        }

                    // Guard against infinite recursion if the suite class appears in its own list.
                    if (cls == suiteClass) {
                        return@mapNotNull null
                    }
                    cls.takeIf { isRunnableTestClass(it) }
                }

            if (testClasses.isEmpty()) {
                throw InitializationError(
                    "No runnable test classes found in resource '$resourcePath' for " +
                        suiteClass.name
                )
            }

            return testClasses.toTypedArray()
        }

        /**
         * Returns `true` if [cls] is a public, concrete JUnit 4 test class (annotated with
         * [RunWith] or declaring/inheriting at least one public non-static [Test] method).
         */
        internal fun isRunnableTestClass(cls: Class<*>): Boolean {
            val modifiers = cls.modifiers
            if (
                cls.isInterface || Modifier.isAbstract(modifiers) || !Modifier.isPublic(modifiers)
            ) {
                return false
            }
            if (cls.isLocalClass || cls.isAnonymousClass) {
                return false
            }
            if (cls.isMemberClass && !Modifier.isStatic(modifiers)) {
                return false
            }
            if (cls.isAnnotationPresent(RunWith::class.java)) {
                return true
            }
            return cls.methods.any { method ->
                !Modifier.isStatic(method.modifiers) && method.isAnnotationPresent(Test::class.java)
            }
        }
    }
}
