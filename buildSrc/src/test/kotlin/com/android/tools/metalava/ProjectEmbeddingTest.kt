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
import com.android.tools.metalava.TestMetalavaWorkspace.Companion.createRunner
import com.android.tools.metalava.TestMetalavaWorkspace.Companion.jarEntries
import com.android.tools.metalava.TestMetalavaWorkspace.Companion.moduleVariantDependencies
import com.android.tools.metalava.TestMetalavaWorkspace.Companion.parsePomDependencies
import com.android.tools.metalava.TestMetalavaWorkspace.Companion.repositoriesBlock
import com.android.tools.metalava.TestMetalavaWorkspace.Companion.writeFile
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.jar.JarFile
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end Gradle TestKit functional tests for Gradle project embedding (`embedApi` and
 * `embedImplementation`) configured by [MetalavaBuildPlugin].
 */
class ProjectEmbeddingTest {

    /** Temporary root directory isolating each test workspace, local Maven repo, and cache. */
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun `embedApi on java-library merges artifacts, promotes deps, and works for published consumers`() {
        val workspace = TestMetalavaWorkspace.create(tempFolder)
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-api",
            version = "1.0.0",
            packageName = "com.example.extapi",
            className = "ExtApiValue",
            source =
                """
                    package com.example.extapi;
                    public class ExtApiValue {
                        public final String text;
                        public ExtApiValue(String text) { this.text = text; }
                    }
                """
                    .trimIndent(),
        )
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-impl",
            version = "1.0.0",
            packageName = "com.example.extimpl",
            className = "ExtImplHelper",
            source =
                """
                    package com.example.extimpl;
                    public class ExtImplHelper {
                        public static String tag(String value) { return "impl:" + value; }
                    }
                """
                    .trimIndent(),
        )

        workspace.addSubproject(
            path = ":published-dep",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/pub/PublishedDep.kt" to
                        """
                            package com.example.pub

                            class PublishedDep(val label: String)
                        """
                            .trimIndent()
                ),
        )

        workspace.addSubproject(
            path = ":embedded-api",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        api("com.example:ext-api:1.0.0")
                        api(project(":published-dep"))
                        implementation("com.example:ext-impl:1.0.0")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/api/ApiModel.kt" to
                        """
                            package com.example.api

                            import com.example.extapi.ExtApiValue
                            import com.example.extimpl.ExtImplHelper
                            import com.example.pub.PublishedDep

                            interface ApiThing {
                                fun value(): ExtApiValue
                                fun published(): PublishedDep
                            }

                            fun apiTopLevelHelper(thing: ApiThing): String =
                                ExtImplHelper.tag(thing.value().text + ":" + thing.published().label)

                            fun ApiThing.apiExtension(): String = "ext:" + value().text

                            internal fun embeddedInternalOnly(): String = "secret"
                        """
                            .trimIndent()
                ),
        )

        workspace.addSubproject(
            path = ":host-lib",
            buildScript =
                """
                    plugins {
                        `java-library`
                        `java-test-fixtures`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                    dependencies {
                        embedApi(project(":embedded-api"))
                        testImplementation("junit:junit:4.13.2")
                    }
                    tasks.register("inspectOutgoingVariants") {
                        val apiNames =
                            configurations.named("apiElements").map {
                                it.outgoing.variants.names.sorted()
                            }
                        val runtimeNames =
                            configurations.named("runtimeElements").map {
                                it.outgoing.variants.names.sorted()
                            }
                        doLast {
                            println("API_VARIANTS=" + apiNames.get().joinToString(","))
                            println("RT_VARIANTS=" + runtimeNames.get().joinToString(","))
                        }
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/host/HostThing.kt" to
                        """
                            package com.example.host

                            import com.example.api.ApiThing
                            import com.example.extapi.ExtApiValue
                            import com.example.pub.PublishedDep

                            class HostThing(private val name: String) : ApiThing {
                                override fun value(): ExtApiValue = ExtApiValue(name)
                                override fun published(): PublishedDep = PublishedDep("pub-" + name)
                            }
                        """
                            .trimIndent(),
                    "src/test/kotlin/com/example/host/HostThingTest.kt" to
                        """
                            package com.example.host

                            import com.example.api.apiExtension
                            import com.example.api.apiTopLevelHelper
                            import org.junit.Assert.assertEquals
                            import org.junit.Test

                            class HostThingTest {
                                @Test
                                fun `host unit tests can call embedded api`() {
                                    val thing = HostThing("ok")
                                    assertEquals("impl:ok:pub-ok", apiTopLevelHelper(thing))
                                    assertEquals("ext:ok", thing.apiExtension())
                                }
                            }
                        """
                            .trimIndent(),
                    "src/testFixtures/kotlin/com/example/host/HostFixtures.kt" to
                        """
                            package com.example.host

                            import com.example.api.ApiThing

                            object HostFixtures {
                                fun create(name: String): ApiThing = HostThing(name)
                            }
                        """
                            .trimIndent(),
                ),
        )

        workspace.addSubproject(
            path = ":downstream",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        implementation(project(":host-lib"))
                        testImplementation(testFixtures(project(":host-lib")))
                        testImplementation("junit:junit:4.13.2")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/downstream/DownstreamClient.kt" to
                        """
                            package com.example.downstream

                            import com.example.api.ApiThing
                            import com.example.api.apiExtension
                            import com.example.api.apiTopLevelHelper
                            import com.example.host.HostThing

                            object DownstreamClient {
                                fun describe(name: String): String {
                                    val thing: ApiThing = HostThing(name)
                                    return apiTopLevelHelper(thing) + "|" + thing.apiExtension()
                                }
                            }
                        """
                            .trimIndent(),
                    "src/test/kotlin/com/example/downstream/DownstreamClientTest.kt" to
                        """
                            package com.example.downstream

                            import com.example.api.ApiThing
                            import com.example.host.HostFixtures
                            import org.junit.Assert.assertEquals
                            import org.junit.Assert.assertTrue
                            import org.junit.Test

                            class DownstreamClientTest {
                                @Test
                                fun `downstream tests run against merged jar and testFixtures`() {
                                    assertEquals(
                                        "impl:hello:pub-hello|ext:hello",
                                        DownstreamClient.describe("hello"),
                                    )
                                    assertEquals("fix", HostFixtures.create("fix").value().text)
                                    val apiCodeSource =
                                        ApiThing::class.java.protectionDomain.codeSource.location.path
                                    assertTrue(
                                        "Expected ApiThing to be loaded from merged host-lib JAR, was: ${'$'}apiCodeSource",
                                        apiCodeSource.endsWith("host-lib-$TEST_VERSION.jar"),
                                    )
                                }
                            }
                        """
                            .trimIndent(),
                ),
        )

        val buildResult =
            workspace.run(
                ":host-lib:test",
                ":host-lib:inspectOutgoingVariants",
                ":downstream:test",
                "publish",
                "createBuildInfo",
                "createAggregateBuildInfoFiles",
            )

        // Verify unmerged secondary variants are stripped from both apiElements and
        // runtimeElements so downstream consumers resolve the merged JAR.
        assertThat(extractPrefixedValues(buildResult.output, "API_VARIANTS="))
            .doesNotContain("classes")
        val runtimeVariants = extractPrefixedValues(buildResult.output, "RT_VARIANTS=")
        assertThat(runtimeVariants).doesNotContain("classes")
        assertThat(runtimeVariants).doesNotContain("resources")

        // Verify merged JAR contents.
        val publishedDir = workspace.publishedArtifactDir("host-lib")
        val publishedJar = File(publishedDir, "host-lib-$TEST_VERSION.jar")
        assertThat(publishedJar.isFile).isTrue()
        val entries = jarEntries(publishedJar)
        assertThat(entries)
            .containsAtLeast(
                "com/example/host/HostThing.class",
                "com/example/api/ApiThing.class",
                "META-INF/host-lib.kotlin_module",
                "META-INF/embedded-api.kotlin_module",
            )

        // Verify .pom promotes ext-api & published-dep as compile, ext-impl as runtime, and omits
        // embedded-api.
        val pomFile = File(publishedDir, "host-lib-$TEST_VERSION.pom")
        assertThat(pomFile.readText()).doesNotContain("embedded-api")
        val pomDeps = parsePomDependencies(pomFile)
        assertThat(pomDeps["com.example:ext-api:1.0.0"]).isEqualTo("compile")
        assertThat(pomDeps["com.android.tools.metalava:published-dep:$TEST_VERSION"])
            .isEqualTo("compile")
        assertThat(pomDeps["com.example:ext-impl:1.0.0"]).isEqualTo("runtime")

        // Verify .module apiElements vs runtimeElements scopes.
        val moduleJson =
            JsonParser.parseString(File(publishedDir, "host-lib-$TEST_VERSION.module").readText())
                .asJsonObject
        val apiDeps = moduleVariantDependencies(moduleJson, "apiElements")
        val runtimeDeps = moduleVariantDependencies(moduleJson, "runtimeElements")
        assertThat(apiDeps)
            .containsAtLeast(
                "com.example:ext-api:1.0.0",
                "com.android.tools.metalava:published-dep:$TEST_VERSION",
            )
        assertThat(apiDeps).doesNotContain("com.example:ext-impl:1.0.0")
        assertThat(apiDeps).doesNotContain("com.android.tools.metalava:embedded-api:$TEST_VERSION")
        assertThat(runtimeDeps)
            .containsAtLeast(
                "com.example:ext-api:1.0.0",
                "com.android.tools.metalava:published-dep:$TEST_VERSION",
                "com.example:ext-impl:1.0.0",
            )
        assertThat(runtimeDeps)
            .doesNotContain("com.android.tools.metalava:embedded-api:$TEST_VERSION")

        // Verify build_info.txt and aggregate build info record isTipOfTree = true for
        // published-dep and omit embedded-api.
        val buildInfoFile =
            File(workspace.distDir, "build-info/com.android.tools.metalava_host-lib_build_info.txt")
        val buildInfoText = buildInfoFile.readText()
        assertThat(buildInfoText).doesNotContain("embedded-api")
        val buildInfoJson = JsonParser.parseString(buildInfoText).asJsonObject
        val buildInfoDeps = buildInfoJson.getAsJsonArray("dependencies")
        assertThat(buildInfoDeps.size()).isEqualTo(1)
        val promotedBuildInfoDep = buildInfoDeps[0].asJsonObject
        assertThat(promotedBuildInfoDep.get("artifactId").asString).isEqualTo("published-dep")
        assertThat(promotedBuildInfoDep.get("isTipOfTree").asBoolean).isTrue()

        val aggregateText = File(workspace.distDir, "androidx_aggregate_build_info.txt").readText()
        assertThat(aggregateText).doesNotContain("embedded-api")
        assertThat(aggregateText).contains("\"artifactId\": \"host-lib\"")

        // Verify a completely independent Gradle consumer build (no project substitutions) can
        // compile Kotlin against the published Maven artifact, execute it at runtime, and remains
        // blocked from calling embedded `internal` declarations.
        val consumerDir = tempFolder.newFolder("independent-consumer")
        File(consumerDir, "settings.gradle.kts")
            .writeText(
                """
                    rootProject.name = "consumer"
                    dependencyResolutionManagement {
                        repositories {
                            maven { url = uri("${workspace.m2RepositoryDir.toURI()}") }
                            maven { url = uri("${workspace.externalRepoDir.toURI()}") }
                            ${repositoriesBlock()}
                        }
                    }
                """
                    .trimIndent()
            )
        File(consumerDir, "build.gradle.kts")
            .writeText(
                """
                    import org.jetbrains.kotlin.gradle.dsl.JvmTarget

                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                    }
                    java {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                    kotlin {
                        compilerOptions {
                            jvmTarget.set(JvmTarget.JVM_17)
                        }
                    }
                    dependencies {
                        implementation("com.android.tools.metalava:host-lib:$TEST_VERSION")
                        testImplementation("junit:junit:4.13.2")
                    }
                """
                    .trimIndent()
            )
        writeFile(
            consumerDir,
            "src/test/kotlin/com/example/consumer/PublishedConsumerTest.kt",
            """
                package com.example.consumer

                import com.example.api.ApiThing
                import com.example.api.apiExtension
                import com.example.api.apiTopLevelHelper
                import com.example.host.HostThing
                import org.junit.Assert.assertEquals
                import org.junit.Test

                class PublishedConsumerTest {
                    @Test
                    fun `published artifact exposes embedded Kotlin APIs and runtime deps`() {
                        val thing: ApiThing = HostThing("maven")
                        assertEquals("impl:maven:pub-maven", apiTopLevelHelper(thing))
                        assertEquals("ext:maven", thing.apiExtension())
                    }
                }
            """
                .trimIndent(),
        )
        createRunner(consumerDir, "test").build()

        // Confirm Kotlin module encapsulation: `internal` declarations inside the embedded module
        // remain inaccessible to consumers of the merged JAR.
        writeFile(
            consumerDir,
            "src/main/kotlin/com/example/consumer/IllegalInternalCall.kt",
            """
                package com.example.consumer

                import com.example.api.embeddedInternalOnly

                fun leak(): String = embeddedInternalOnly()
            """
                .trimIndent(),
        )
        val internalFailure = createRunner(consumerDir, "compileKotlin").buildAndFail()
        assertThat(internalFailure.output).contains("Cannot access 'fun embeddedInternalOnly()")
    }

    @Test
    fun `nested embedApi promotes transitive API and runtime deps with exact scopes`() {
        val workspace = TestMetalavaWorkspace.create(tempFolder)
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-leaf-api",
            version = "1.0.0",
            packageName = "com.example.leafapi",
            className = "LeafApiType",
            source = "package com.example.leafapi; public class LeafApiType {}",
        )
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-leaf-impl",
            version = "1.0.0",
            packageName = "com.example.leafimpl",
            className = "LeafImplType",
            source = "package com.example.leafimpl; public class LeafImplType {}",
        )

        workspace.addSubproject(
            path = ":leaf-api",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        api("com.example:ext-leaf-api:1.0.0")
                        implementation("com.example:ext-leaf-impl:1.0.0")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/leaf/LeafInterface.java" to
                        """
                            package com.example.leaf;
                            import com.example.leafapi.LeafApiType;
                            public interface LeafInterface {
                                LeafApiType leaf();
                            }
                        """
                            .trimIndent()
                ),
        )

        workspace.addSubproject(
            path = ":mid-api",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        embedApi(project(":leaf-api"))
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/mid/MidInterface.java" to
                        """
                            package com.example.mid;
                            import com.example.leaf.LeafInterface;
                            public interface MidInterface extends LeafInterface {}
                        """
                            .trimIndent()
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
                        embedApi(project(":mid-api"))
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/host/HostApi.java" to
                        """
                            package com.example.host;
                            import com.example.mid.MidInterface;
                            public interface HostApi extends MidInterface {}
                        """
                            .trimIndent()
                ),
        )

        workspace.run(":host:publish")

        val publishedDir = workspace.publishedArtifactDir("host")
        val entries = jarEntries(File(publishedDir, "host-$TEST_VERSION.jar"))
        assertThat(entries)
            .containsAtLeast(
                "com/example/host/HostApi.class",
                "com/example/mid/MidInterface.class",
                "com/example/leaf/LeafInterface.class",
            )

        val pomDeps = parsePomDependencies(File(publishedDir, "host-$TEST_VERSION.pom"))
        assertThat(pomDeps)
            .containsExactly(
                "com.example:ext-leaf-api:1.0.0",
                "compile",
                "com.example:ext-leaf-impl:1.0.0",
                "runtime",
            )

        val moduleJson =
            JsonParser.parseString(File(publishedDir, "host-$TEST_VERSION.module").readText())
                .asJsonObject
        assertThat(moduleVariantDependencies(moduleJson, "apiElements"))
            .contains("com.example:ext-leaf-api:1.0.0")
        assertThat(moduleVariantDependencies(moduleJson, "apiElements"))
            .doesNotContain("com.example:ext-leaf-impl:1.0.0")
        assertThat(moduleVariantDependencies(moduleJson, "runtimeElements"))
            .containsAtLeast("com.example:ext-leaf-api:1.0.0", "com.example:ext-leaf-impl:1.0.0")
    }

    @Test
    fun `nested and diamond embedding unpacks once, preserves host excludes, and keeps java-library API clean`() {
        val workspace = TestMetalavaWorkspace.create(tempFolder)
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-excluded",
            version = "1.0.0",
            packageName = "com.example.excluded",
            className = "ExtExcluded",
            source = "package com.example.excluded; public class ExtExcluded {}",
        )
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-core",
            version = "1.0.0",
            packageName = "com.example.extcore",
            className = "ExtCore",
            source = "package com.example.extcore; public class ExtCore {}",
            dependencies = listOf("com.example:ext-excluded:1.0.0"),
        )
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-lint",
            version = "1.0.0",
            packageName = "com.example.extlint",
            className = "ExtLint",
            source = "package com.example.extlint; public class ExtLint {}",
        )
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-leaf",
            version = "1.0.0",
            packageName = "com.example.extleaf",
            className = "ExtLeaf",
            source = "package com.example.extleaf; public class ExtLeaf {}",
        )

        workspace.addSubproject(
            path = ":pub-sibling",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/pub/PubSibling.kt" to
                        "package com.example.pub\nclass PubSibling\n"
                ),
        )

        workspace.addSubproject(
            path = ":nested:core",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        api(project(":pub-sibling"))
                        api("com.example:ext-core:1.0.0")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/core/CoreClass.kt" to
                        "package com.example.core\nclass CoreClass\n"
                ),
        )

        workspace.addSubproject(
            path = ":nested:lint",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        implementation(project(":nested:core"))
                        implementation("com.example:ext-lint:1.0.0")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/lint/LintClass.kt" to
                        "package com.example.lint\nclass LintClass\n"
                ),
        )

        // Two nested subprojects sharing the same leaf name ("leaf") verify that project-path
        // Kotlin moduleName and nested group prevent collisions.
        workspace.addSubproject(
            path = ":nested:leaf",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        implementation("com.example:ext-leaf:1.0.0")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/leaf1/LeafOne.kt" to
                        "package com.example.leaf1\nfun leafOne() = 1\n"
                ),
        )

        workspace.addSubproject(
            path = ":nested:wrapper",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        embedImplementation(project(":nested:leaf"))
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/wrapper/WrapperClass.kt" to
                        "package com.example.wrapper\nclass WrapperClass\n"
                ),
        )

        workspace.addSubproject(
            path = ":other:leaf",
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
                    "src/main/kotlin/com/example/leaf2/LeafTwo.kt" to
                        "package com.example.leaf2\nfun leafTwo() = 2\n"
                ),
        )

        workspace.addSubproject(
            path = ":host",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                        `java-test-fixtures`
                        id("maven-publish")
                    }
                    dependencies {
                        // Explicit host declaration with exclude must be preserved without
                        // duplication when :nested:core also depends on ext-core.
                        implementation("com.example:ext-core:1.0.0") {
                            exclude(group = "com.example", module = "ext-excluded")
                        }
                        embedImplementation(project(":nested:core"))
                        embedImplementation(project(":nested:lint"))
                        embedImplementation(project(":nested:wrapper"))
                        embedImplementation(project(":other:leaf"))
                    }
                    tasks.register("inspectApiVariants") {
                        val apiNames =
                            configurations.named("apiElements").map {
                                it.outgoing.variants.names.sorted()
                            }
                        val runtimeNames =
                            configurations.named("runtimeElements").map {
                                it.outgoing.variants.names.sorted()
                            }
                        doLast {
                            println("VARIANTS=" + apiNames.get().joinToString(","))
                            println("RT_VARIANTS=" + runtimeNames.get().joinToString(","))
                        }
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/host/HostClass.kt" to
                        "package com.example.host\nimport com.example.core.CoreClass\nclass HostClass { internal fun core(): CoreClass = CoreClass() }\n",
                    "src/testFixtures/kotlin/com/example/host/HostFixture.kt" to
                        "package com.example.host\nimport com.example.core.CoreClass\nobject HostFixture { fun core(): CoreClass = HostClass().core() }\n",
                ),
        )

        val runResult =
            workspace.run(
                ":host:publish",
                ":host:createBuildInfo",
                ":host:compileTestFixturesKotlin",
                ":host:inspectApiVariants",
            )

        // embedImplementation-only java-library retains its classes variant on apiElements, while
        // runtimeElements strips unmerged classes/resources variants.
        assertThat(extractPrefixedValues(runResult.output, "VARIANTS=")).contains("classes")
        val runtimeVariants = extractPrefixedValues(runResult.output, "RT_VARIANTS=")
        assertThat(runtimeVariants).doesNotContain("classes")
        assertThat(runtimeVariants).doesNotContain("resources")

        val publishedDir = workspace.publishedArtifactDir("host")
        val entries = jarEntries(File(publishedDir, "host-$TEST_VERSION.jar"))
        assertThat(entries)
            .containsAtLeast(
                "com/example/host/HostClass.class",
                "com/example/core/CoreClass.class",
                "com/example/lint/LintClass.class",
                "com/example/wrapper/WrapperClass.class",
                "com/example/leaf1/LeafOneKt.class",
                "com/example/leaf2/LeafTwoKt.class",
                "META-INF/host.kotlin_module",
                "META-INF/nested.core.kotlin_module",
                "META-INF/nested.lint.kotlin_module",
                "META-INF/nested.wrapper.kotlin_module",
                "META-INF/nested.leaf.kotlin_module",
                "META-INF/other.leaf.kotlin_module",
            )

        val pomFile = File(publishedDir, "host-$TEST_VERSION.pom")
        val pomText = pomFile.readText()
        for (unpublished in listOf("core", "lint", "wrapper", "leaf")) {
            assertThat(pomText).doesNotContain("<artifactId>$unpublished</artifactId>")
        }
        val pomDeps = parsePomDependencies(pomFile)
        assertThat(pomDeps["com.example:ext-core:1.0.0"]).isEqualTo("runtime")
        assertThat(pomDeps["com.example:ext-lint:1.0.0"]).isEqualTo("runtime")
        assertThat(pomDeps["com.example:ext-leaf:1.0.0"]).isEqualTo("runtime")
        assertThat(pomDeps["com.android.tools.metalava:pub-sibling:$TEST_VERSION"])
            .isEqualTo("runtime")

        // Verify apiElements omits embedded api() deps (pub-sibling, ext-core), and
        // runtimeElements preserves the host's explicit exclude on ext-core without duplicating it.
        val moduleJson =
            JsonParser.parseString(File(publishedDir, "host-$TEST_VERSION.module").readText())
                .asJsonObject
        val apiDeps = moduleVariantDependencies(moduleJson, "apiElements")
        assertThat(apiDeps).doesNotContain("com.example:ext-core:1.0.0")
        assertThat(apiDeps).doesNotContain("com.android.tools.metalava:pub-sibling:$TEST_VERSION")

        val runtimeVariant =
            moduleJson
                .getAsJsonArray("variants")
                .map { it.asJsonObject }
                .first { it.get("name").asString == "runtimeElements" }
        val extCoreDeps =
            runtimeVariant
                .getAsJsonArray("dependencies")
                .map { it.asJsonObject }
                .filter {
                    it.get("group").asString == "com.example" &&
                        it.get("module").asString == "ext-core"
                }
        assertThat(extCoreDeps).hasSize(1)
        val excludes = extCoreDeps.single().getAsJsonArray("excludes")
        assertThat(excludes).isNotNull()
        assertThat(excludes.size()).isEqualTo(1)
        assertThat(excludes[0].asJsonObject.get("module").asString).isEqualTo("ext-excluded")

        val buildInfoJson =
            JsonParser.parseString(
                    File(
                            workspace.distDir,
                            "build-info/com.android.tools.metalava_host_build_info.txt",
                        )
                        .readText()
                )
                .asJsonObject
        val buildInfoDeps = buildInfoJson.getAsJsonArray("dependencies")
        assertThat(buildInfoDeps.size()).isEqualTo(1)
        assertThat(buildInfoDeps[0].asJsonObject.get("artifactId").asString)
            .isEqualTo("pub-sibling")
        assertThat(buildInfoDeps[0].asJsonObject.get("isTipOfTree").asBoolean).isTrue()
    }

    @Test
    fun `boundary promotion propagates host implementation excludes, version constraints, and platform BOMs`() {
        val workspace = TestMetalavaWorkspace.create(tempFolder)
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-excluded",
            version = "1.0.0",
            packageName = "com.example.excluded",
            className = "ExtExcluded",
            source = "package com.example.excluded; public class ExtExcluded {}",
        )
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-core",
            version = "1.0.0",
            packageName = "com.example.extcore",
            className = "ExtCore",
            source =
                """
                    package com.example.extcore;
                    public class ExtCore {
                        public static String version() { return "1.0.0"; }
                    }
                """
                    .trimIndent(),
            dependencies = listOf("com.example:ext-excluded:1.0.0"),
        )
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-core",
            version = "2.0.0",
            packageName = "com.example.extcore",
            className = "ExtCore",
            source =
                """
                    package com.example.extcore;
                    public class ExtCore {
                        public static String version() { return "2.0.0"; }
                    }
                """
                    .trimIndent(),
            dependencies = listOf("com.example:ext-excluded:1.0.0"),
        )
        workspace.publishExternalLibrary(
            group = "com.example",
            artifact = "ext-managed",
            version = "1.0.0",
            packageName = "com.example.extmanaged",
            className = "ExtManaged",
            source =
                """
                    package com.example.extmanaged;
                    public class ExtManaged {
                        public static String tag() { return "managed"; }
                    }
                """
                    .trimIndent(),
        )
        workspace.publishExternalBom(
            group = "com.example",
            artifact = "ext-bom",
            version = "1.0.0",
            constraints = listOf("com.example:ext-managed:1.0.0"),
        )

        workspace.addSubproject(
            path = ":embedded-api",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        api("com.example:ext-core:2.0.0")
                        api(platform("com.example:ext-bom:1.0.0"))
                        api("com.example:ext-managed")
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/api/EmbeddedApi.kt" to
                        """
                            package com.example.api

                            import com.example.extcore.ExtCore
                            import com.example.extmanaged.ExtManaged

                            object EmbeddedApi {
                                fun info(): String = ExtCore.version() + ":" + ExtManaged.tag()
                            }
                        """
                            .trimIndent()
                ),
        )

        workspace.addSubproject(
            path = ":host",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                        id("metalava-build-plugin")
                        id("maven-publish")
                    }
                    dependencies {
                        // Host declares an older ext-core version with an exclude rule while
                        // :embedded-api exposes ext-core:2.0.0 in its API and imports a BOM.
                        implementation("com.example:ext-core:1.0.0") {
                            exclude(group = "com.example", module = "ext-excluded")
                        }
                        embedApi(project(":embedded-api"))
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/kotlin/com/example/host/HostEntry.kt" to
                        """
                            package com.example.host

                            import com.example.api.EmbeddedApi

                            object HostEntry {
                                fun describe(): String = EmbeddedApi.info()
                            }
                        """
                            .trimIndent()
                ),
        )

        workspace.run(":host:publish")

        val publishedDir = workspace.publishedArtifactDir("host")
        val moduleJson =
            JsonParser.parseString(File(publishedDir, "host-$TEST_VERSION.module").readText())
                .asJsonObject
        val variantsByName =
            moduleJson
                .getAsJsonArray("variants")
                .map { it.asJsonObject }
                .associateBy { it.get("name").asString }

        // apiElements must carry the host's implementation exclude rule on promoted ext-core:2.0.0
        // and preserve org.gradle.category=platform on promoted ext-bom:1.0.0.
        val apiDeps = variantsByName.getValue("apiElements").getAsJsonArray("dependencies")
        val apiExtCore =
            apiDeps.map { it.asJsonObject }.single { it.get("module").asString == "ext-core" }
        assertThat(apiExtCore.getAsJsonObject("version").get("requires").asString)
            .isEqualTo("2.0.0")
        val apiExcludes = apiExtCore.getAsJsonArray("excludes")
        assertThat(apiExcludes).isNotNull()
        assertThat(apiExcludes.map { it.asJsonObject.get("module").asString })
            .containsExactly("ext-excluded")

        val apiExtBom =
            apiDeps.map { it.asJsonObject }.single { it.get("module").asString == "ext-bom" }
        assertThat(apiExtBom.getAsJsonObject("attributes").get("org.gradle.category").asString)
            .isEqualTo("platform")

        // runtimeElements must keep a single host ext-core entry with its exclude rule while
        // emitting a dependencyConstraint upgrading it to 2.0.0, plus platform ext-bom:1.0.0.
        val runtimeVariant = variantsByName.getValue("runtimeElements")
        val runtimeDeps = runtimeVariant.getAsJsonArray("dependencies").map { it.asJsonObject }
        val runtimeExtCore = runtimeDeps.filter { it.get("module").asString == "ext-core" }
        assertThat(runtimeExtCore).hasSize(1)
        assertThat(
                runtimeExtCore.single().getAsJsonArray("excludes").map {
                    it.asJsonObject.get("module").asString
                }
            )
            .containsExactly("ext-excluded")

        val runtimeConstraints =
            runtimeVariant.getAsJsonArray("dependencyConstraints").map { it.asJsonObject }
        val extCoreConstraint =
            runtimeConstraints.single { it.get("module").asString == "ext-core" }
        assertThat(extCoreConstraint.getAsJsonObject("version").get("requires").asString)
            .isEqualTo("2.0.0")

        val runtimeExtBom = runtimeDeps.single { it.get("module").asString == "ext-bom" }
        assertThat(runtimeExtBom.getAsJsonObject("attributes").get("org.gradle.category").asString)
            .isEqualTo("platform")

        // Published .pom must carry the <exclusion> on compile-scoped ext-core and emit ext-bom
        // under <dependencyManagement> with <type>pom</type> and <scope>import</scope>.
        val pomFile = File(publishedDir, "host-$TEST_VERSION.pom")
        val pomText = pomFile.readText()
        assertThat(pomText).contains("<artifactId>ext-excluded</artifactId>")
        assertThat(pomText).contains("<dependencyManagement>")
        assertThat(pomText).contains("<type>pom</type>")
        assertThat(pomText).contains("<scope>import</scope>")
        val directPomDeps = parsePomDependencies(pomFile)
        assertThat(directPomDeps.keys.none { it.contains("ext-bom") }).isTrue()
        assertThat(directPomDeps["com.example:ext-core:2.0.0"]).isEqualTo("compile")
        assertThat(directPomDeps["com.example:ext-managed:1.0.0"]).isEqualTo("compile")

        // Independent Gradle consumer must resolve ext-core:2.0.0 and ext-managed:1.0.0 cleanly
        // without fetching a nonexistent ext-bom JAR or pulling in ext-excluded.
        val consumerDir = tempFolder.newFolder("bom-and-exclude-consumer")
        File(consumerDir, "settings.gradle.kts")
            .writeText(
                """
                    rootProject.name = "bom-consumer"
                    dependencyResolutionManagement {
                        repositories {
                            maven { url = uri("${workspace.m2RepositoryDir.toURI()}") }
                            maven { url = uri("${workspace.externalRepoDir.toURI()}") }
                            ${repositoriesBlock()}
                        }
                    }
                """
                    .trimIndent()
            )
        File(consumerDir, "build.gradle.kts")
            .writeText(
                """
                    import org.jetbrains.kotlin.gradle.dsl.JvmTarget

                    plugins {
                        `java-library`
                        id("org.jetbrains.kotlin.jvm")
                    }
                    java {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                    kotlin {
                        compilerOptions {
                            jvmTarget.set(JvmTarget.JVM_17)
                        }
                    }
                    dependencies {
                        implementation("com.android.tools.metalava:host:$TEST_VERSION")
                        testImplementation("junit:junit:4.13.2")
                    }
                    tasks.register("printClasspaths") {
                        val compileFiles =
                            configurations.named("compileClasspath").map { cfg ->
                                cfg.files.map { it.name }.sorted()
                            }
                        val runtimeFiles =
                            configurations.named("runtimeClasspath").map { cfg ->
                                cfg.files.map { it.name }.sorted()
                            }
                        doLast {
                            println("COMPILE=" + compileFiles.get().joinToString(","))
                            println("RUNTIME=" + runtimeFiles.get().joinToString(","))
                        }
                    }
                """
                    .trimIndent()
            )
        writeFile(
            consumerDir,
            "src/test/kotlin/com/example/consumer/BomConsumerTest.kt",
            """
                package com.example.consumer

                import com.example.host.HostEntry
                import org.junit.Assert.assertEquals
                import org.junit.Test

                class BomConsumerTest {
                    @Test
                    fun `consumer resolves upgraded ext-core and BOM-managed dep`() {
                        assertEquals("2.0.0:managed", HostEntry.describe())
                    }
                }
            """
                .trimIndent(),
        )
        val consumerResult = createRunner(consumerDir, "test", "printClasspaths").build()
        val compileJars = extractPrefixedValues(consumerResult.output, "COMPILE=")
        val runtimeJars = extractPrefixedValues(consumerResult.output, "RUNTIME=")
        assertThat(compileJars).containsAtLeast("ext-core-2.0.0.jar", "ext-managed-1.0.0.jar")
        assertThat(compileJars).doesNotContain("ext-excluded-1.0.0.jar")
        assertThat(runtimeJars).containsAtLeast("ext-core-2.0.0.jar", "ext-managed-1.0.0.jar")
        assertThat(runtimeJars).doesNotContain("ext-core-1.0.0.jar")
        assertThat(runtimeJars).doesNotContain("ext-excluded-1.0.0.jar")
    }

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
    fun `fail-fast guard rejects embedding an already-published project on jar and publish`() {
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

        // Both :jar and :publish must report the actionable custom error message.
        val jarResult = workspace.runAndFail(":host:jar")
        assertThat(jarResult.output)
            .contains("Cannot embed project ':published-sibling' in ':host'")
        assertThat(jarResult.output).contains("maven-publish")

        val publishResult = workspace.runAndFail(":host:publish")
        assertThat(publishResult.output)
            .contains("Cannot embed project ':published-sibling' in ':host'")
        assertThat(publishResult.output).contains("maven-publish")
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
    fun `fail-fast guard rejects embedded project depending on an un-embedded unpublished sibling`() {
        val workspace = TestMetalavaWorkspace.create(tempFolder)
        workspace.addSubproject(
            path = ":forgotten-helper",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                    }
                    // Register an attribute-less consumable configuration (like modelTestSuite) to
                    // verify isPublishedMetalavaProject does not suffer a lenient-matching false
                    // positive.
                    configurations.register("modelTestSuite") {
                        isCanBeResolved = false
                        isCanBeConsumed = true
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/helper/Helper.java" to
                        "package com.example.helper; public class Helper {}"
                ),
        )
        workspace.addSubproject(
            path = ":embed-lint",
            buildScript =
                """
                    plugins {
                        `java-library`
                        id("metalava-build-plugin")
                    }
                    dependencies {
                        implementation(project(":forgotten-helper"))
                    }
                """
                    .trimIndent(),
            sources =
                mapOf(
                    "src/main/java/com/example/lint/Lint.java" to
                        "package com.example.lint; public class Lint {}"
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
                        embedImplementation(project(":embed-lint"))
                    }
                """
                    .trimIndent(),
        )

        val result = workspace.runAndFail(":host:publish")
        assertThat(result.output)
            .contains(
                "Embedded project ':embed-lint' depends on unpublished project " +
                    "':forgotten-helper', which is not embedded by ':host'"
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
        val firstRun =
            workspace.run(
                ":host:jar",
                ":host:publish",
                ":host:createBuildInfo",
                "--configuration-cache",
                "--build-cache",
            )
        assertThat(firstRun.output).contains("Configuration cache entry stored.")

        // Second run must reuse the configuration cache entry cleanly.
        val secondRun =
            workspace.run(
                ":host:jar",
                ":host:publish",
                ":host:createBuildInfo",
                "--configuration-cache",
                "--build-cache",
            )
        assertThat(secondRun.output).contains("Configuration cache entry reused.")

        // Delete build outputs and re-run to verify MergeServiceFilesTask is cacheable.
        workspace.outDir.deleteRecursively()
        val cachedRun = workspace.run(":host:jar", "--configuration-cache", "--build-cache")
        assertThat(cachedRun.task(":host:mergeServiceFiles")?.outcome)
            .isEqualTo(TaskOutcome.FROM_CACHE)
    }

    /** Extracts comma-separated values from a line starting with [prefix] in [output]. */
    private fun extractPrefixedValues(output: String, prefix: String): List<String> {
        val line =
            output.lineSequence().firstOrNull { it.startsWith(prefix) }
                ?: error("Output did not contain line starting with '$prefix':\n$output")
        return line.removePrefix(prefix).split(",").filter { it.isNotEmpty() }
    }
}
