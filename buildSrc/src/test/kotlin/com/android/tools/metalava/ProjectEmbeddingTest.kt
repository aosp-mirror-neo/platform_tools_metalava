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

package com.android.tools.metalava

import com.android.tools.metalava.TestMetalavaWorkspace.Companion.TEST_VERSION
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.jar.JarFile
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Functional Gradle TestKit tests for Metalava project embedding (`embedApi` and
 * `embedImplementation`), `META-INF/services/` merging, fail-fast guards, and caching.
 */
class ProjectEmbeddingTest {

    /** Isolated temporary directory for each TestKit workspace. */
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun `META-INF services files are merged even with relative out directory and loadable via ServiceLoader`() {
        // Use relative "../../out" build directory (matching settings.gradle.kts when OUT_DIR is
        // unset) to verify jar.eachFile path normalization never drops merged service files.
        val workspace = TestMetalavaWorkspace.create(tempFolder, useRelativeOutDir = true)

        workspace.addSubproject(
            path = ":spi",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/spi/Greeter.java" to
                        """
                            package com.example.spi;
                            public interface Greeter {
                                String id();
                            }
                        """
                            .trimIndent()
                ),
        )

        workspace.addSubproject(
            path = ":provider-a",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        implementation(project(":spi"))
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/a/GreeterA.java" to
                        """
                            package com.example.a;
                            import com.example.spi.Greeter;
                            public class GreeterA implements Greeter {
                                @Override public String id() { return "A"; }
                            }
                        """
                            .trimIndent(),
                    "src/main/resources/META-INF/services/com.example.spi.Greeter" to
                        "# Provider A\ncom.example.a.GreeterA\n",
                ),
        )

        workspace.addSubproject(
            path = ":provider-b",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        implementation(project(":spi"))
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/b/GreeterB.java" to
                        """
                            package com.example.b;
                            import com.example.spi.Greeter;
                            public class GreeterB implements Greeter {
                                @Override public String id() { return "B"; }
                            }
                        """
                            .trimIndent(),
                    "src/main/resources/META-INF/services/com.example.spi.Greeter" to
                        "com.example.b.GreeterB # comment\ncom.example.a.GreeterA\n",
                ),
        )

        workspace.addSubproject(
            path = ":host",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                    dependencies {
                        embedApi(project(":spi"))
                        embedImplementation(project(":provider-a"))
                        embedImplementation(project(":provider-b"))
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/host/HostGreeter.java" to
                        """
                            package com.example.host;
                            import com.example.spi.Greeter;
                            public class HostGreeter implements Greeter {
                                @Override public String id() { return "HOST"; }
                            }
                        """
                            .trimIndent(),
                    "src/main/resources/META-INF/services/com.example.spi.Greeter" to
                        "com.example.host.HostGreeter\n",
                ),
        )

        workspace.run(":host:publish")

        val publishedJar = File(workspace.publishedArtifactDir("host"), "host-$TEST_VERSION.jar")
        JarFile(publishedJar).use { jar ->
            val serviceEntry = jar.getJarEntry("META-INF/services/com.example.spi.Greeter")
            assertThat(serviceEntry).isNotNull()
            val mergedText = jar.getInputStream(serviceEntry).bufferedReader().readText()
            assertThat(mergedText)
                .isEqualTo(
                    """
                        com.example.a.GreeterA
                        com.example.b.GreeterB
                        com.example.host.HostGreeter
                    """
                        .trimIndent() + "\n"
                )
        }

        // Load all three ServiceLoader implementations from the published JAR in an isolated
        // URLClassLoader.
        URLClassLoader(arrayOf(publishedJar.toURI().toURL()), ClassLoader.getPlatformClassLoader())
            .use { loader ->
                val spiClass = loader.loadClass("com.example.spi.Greeter")
                val idMethod = spiClass.getMethod("id")
                val ids = ServiceLoader.load(spiClass, loader).map { idMethod.invoke(it) as String }
                assertThat(ids).containsExactly("A", "B", "HOST").inOrder()
            }
    }

    @Test
    fun `fail-fast guard rejects duplicate resource across embedded projects`() {
        val workspace = TestMetalavaWorkspace.create(tempFolder)
        workspace.addSubproject(
            path = ":embed-a",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                    }
                """
                    .trimIndent(),
            sources = mapOf("src/main/resources/shared.txt" to "from-a\n"),
        )
        workspace.addSubproject(
            path = ":embed-b",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                    }
                """
                    .trimIndent(),
            sources = mapOf("src/main/resources/shared.txt" to "from-b\n"),
        )
        workspace.addSubproject(
            path = ":host",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                    dependencies {
                        embedImplementation(project(":embed-a"))
                        embedImplementation(project(":embed-b"))
                    }
                """
                    .trimIndent(),
        )

        val result = workspace.runAndFail(":host:jar")
        assertThat(result.output).contains("shared.txt")
    }

    @Test
    fun `fail-fast guard rejects duplicate class across embedded projects`() {
        val workspace = TestMetalavaWorkspace.create(tempFolder)
        workspace.addSubproject(
            path = ":embed-a",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/dup/Colliding.java" to
                        "package com.example.dup; public class Colliding {}"
                ),
        )
        workspace.addSubproject(
            path = ":embed-b",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/dup/Colliding.java" to
                        "package com.example.dup; public class Colliding {}"
                ),
        )
        workspace.addSubproject(
            path = ":host",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                    dependencies {
                        embedImplementation(project(":embed-a"))
                        embedImplementation(project(":embed-b"))
                    }
                """
                    .trimIndent(),
        )

        val result = workspace.runAndFail(":host:jar")
        assertThat(result.output).contains("Colliding.class")
    }

    @Test
    fun `fail-fast guard rejects embedding external Maven coordinates`() {
        val workspace = TestMetalavaWorkspace.create(tempFolder)
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-lib",
            version = "1.0.0",
            packageName = "com.example.ext",
            className = "ExtLib",
            source = "package com.example.ext; public class ExtLib {}",
        )
        workspace.addSubproject(
            path = ":host",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                    dependencies {
                        embedImplementation("com.example:ext-lib:1.0.0")
                    }
                """
                    .trimIndent(),
        )

        val result = workspace.runAndFail(":host:jar")
        assertThat(result.output)
            .contains("Cannot embed external dependency 'com.example:ext-lib:1.0.0'")
    }

    @Test
    fun `fail-fast guard rejects embedding an already-published project on jar`() {
        val workspace = TestMetalavaWorkspace.create(tempFolder)
        workspace.addSubproject(
            path = ":published-sibling",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/pub/Pub.java" to
                        "package com.example.pub; public class Pub {}"
                ),
        )
        workspace.addSubproject(
            path = ":host",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                    dependencies {
                        embedImplementation(project(":published-sibling"))
                    }
                """
                    .trimIndent(),
        )

        val jarResult = workspace.runAndFail(":host:jar")
        assertThat(jarResult.output)
            .contains("Cannot embed project ':published-sibling' in ':host'")
        assertThat(jarResult.output).contains("maven-publish")
    }

    @Test
    fun `fail-fast guard rejects publishing a nested subproject`() {
        val workspace = TestMetalavaWorkspace.create(tempFolder)
        workspace.addSubproject(
            path = ":nested:published-child",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                    // Overriding project.group back to the flat group must NOT bypass the
                    // nested-path publishing restriction.
                    group = "com.android.tools.metalava"
                """
                    .trimIndent(),
        )

        val nestedResult = workspace.runAndFail(":nested:published-child:tasks")
        assertThat(nestedResult.output)
            .contains(
                "Nested Metalava subproject ':nested:published-child' cannot be published; only " +
                    "top-level projects may apply 'maven-publish'."
            )

        // A top-level published project overriding project.group must also fail fast.
        val groupWorkspace = TestMetalavaWorkspace.create(tempFolder)
        groupWorkspace.addSubproject(
            path = ":bad-group",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                    group = "com.example.wrong"
                """
                    .trimIndent(),
        )

        val groupResult = groupWorkspace.runAndFail(":bad-group:tasks")
        assertThat(groupResult.output)
            .contains(
                "Published Metalava project ':bad-group' must use group " +
                    "'com.android.tools.metalava', but has 'com.example.wrong'."
            )
    }

    @Test
    fun `configuration cache is reused and MergeServiceFilesTask is served from build cache`() {
        val workspace = TestMetalavaWorkspace.create(tempFolder)
        workspace.addSubproject(
            path = ":embedded",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/embed/Embedded.kt" to
                        "package com.example.embed\nclass Embedded\n",
                    "src/main/resources/META-INF/services/com.example.Svc" to
                        "com.example.embed.Embedded\n",
                ),
        )
        workspace.addSubproject(
            path = ":host",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("maven-publish")
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        embedImplementation(project(":embedded"))
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/host/Main.kt" to
                        "package com.example.host\nobject Main { @JvmStatic fun main(args: Array<String>) {} }\n"
                ),
        )

        // First run populates both configuration cache and build cache.
        val firstRun = workspace.run(":host:jar", "--configuration-cache", "--build-cache")
        assertThat(firstRun.output).contains("Configuration cache entry stored.")

        // Second run must reuse the configuration cache entry cleanly.
        val secondRun = workspace.run(":host:jar", "--configuration-cache", "--build-cache")
        assertThat(secondRun.output).contains("Configuration cache entry reused.")

        // Delete build outputs and re-run to verify MergeServiceFilesTask is cacheable.
        workspace.outDir.deleteRecursively()
        val cachedRun = workspace.run(":host:jar", "--configuration-cache", "--build-cache")
        assertThat(cachedRun.task(":host:mergeServiceFiles")?.outcome)
            .isEqualTo(TaskOutcome.FROM_CACHE)
    }
}
