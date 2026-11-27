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
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Functional tests for [VerifyPublishedArtifactsTask] and [setUpVerifyPublishedArtifactsTask],
 * covering valid published artifacts with embedded subprojects, Configuration Cache reuse, and
 * counterfactual metadata, exclusion, checksum, and smoke-check failures.
 */
class VerifyPublishedArtifactsTaskTest {

    /**
     * Per-test temporary folder for isolated tests that verify cold Configuration Cache storage.
     */
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun `verifyPublishedArtifacts succeeds with embedded projects and reuses configuration cache`() {
        val workspace = createEmbeddedMetalavaWorkspace(tempFolder)

        val firstRun = workspace.run("verifyPublishedArtifacts", "--configuration-cache")
        assertThat(firstRun.output).contains("Configuration cache entry stored.")

        val secondRun = workspace.run("verifyPublishedArtifacts", "--configuration-cache")
        assertThat(secondRun.output).contains("Configuration cache entry reused.")
    }

    @Test
    fun `verifyPublishedArtifacts fails if an unpublished project exists in m2repository`() {
        val leakedDir =
            File(
                sharedWorkspace.m2RepositoryDir,
                "com/android/tools/metalava/metalava-model-api/$TEST_VERSION",
            )
        leakedDir.mkdirs()
        try {
            val result =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(result.output)
                .contains(
                    "Unpublished project 'com.android.tools.metalava:metalava-model-api' must " +
                        "not be present in m2repository"
                )
        } finally {
            leakedDir.deleteRecursively()
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if an unexpected stale artifact exists in m2repository`() {
        val staleDir =
            File(
                sharedWorkspace.m2RepositoryDir,
                "com/android/tools/metalava/stale-module/$TEST_VERSION",
            )
        staleDir.mkdirs()
        try {
            val result =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(result.output)
                .contains(
                    "Unexpected Metalava artifact " +
                        "'com.android.tools.metalava:stale-module:$TEST_VERSION' found in " +
                        "m2repository"
                )
        } finally {
            staleDir.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if a published POM references an unpublished project`() {
        val pomFile =
            File(
                sharedWorkspace.publishedArtifactDir("metalava-model"),
                "metalava-model-$TEST_VERSION.pom",
            )
        withRestoredFile(pomFile) { original ->
            val injectedDep =
                """
                    <dependency>
                      <groupId>com.android.tools.metalava</groupId>
                      <artifactId>metalava-model-api</artifactId>
                      <version>$TEST_VERSION</version>
                      <scope>compile</scope>
                    </dependency>
                """
                    .trimIndent()
            // Target the top-level <project>/<dependencies> block (immediately before </project>)
            // rather than <dependencyManagement>/<dependencies>.
            val updated =
                original.replaceFirst(
                    Regex("""(</dependencies>\s*</project>)"""),
                    "$injectedDep\n  $1",
                )
            check(updated != original) {
                "Expected top-level </dependencies></project> in POM:\n$original"
            }
            pomFile.writeText(updated)

            val result =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(result.output)
                .contains(
                    "Published POM for 'metalava-model' references unpublished Metalava coordinate"
                )
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if a published POM has wrong scope for external API dependency`() {
        val pomFile =
            File(
                sharedWorkspace.publishedArtifactDir("metalava-model"),
                "metalava-model-$TEST_VERSION.pom",
            )
        withRestoredFile(pomFile) { original ->
            // Change ext-api scope in .pom from compile to runtime.
            val tampered =
                original.replace(
                    Regex(
                        """(<artifactId>ext-api</artifactId>\s*<version>1\.0\.0</version>\s*<scope>)compile(</scope>)"""
                    ),
                    "$1runtime$2",
                )
            check(tampered != original) { "Expected ext-api compile dependency in POM:\n$original" }
            pomFile.writeText(tampered)

            val result =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(result.output)
                .contains(
                    "Published POM for 'metalava-model' declares API dependency " +
                        "'com.example:ext-api' with scope 'runtime' instead of 'compile'"
                )
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if a published POM promotes a runtime-only dependency to compile scope`() {
        val pomFile =
            File(sharedWorkspace.publishedArtifactDir("metalava"), "metalava-$TEST_VERSION.pom")
        withRestoredFile(pomFile) { original ->
            val tampered =
                original.replace(
                    Regex(
                        """(<artifactId>ext-parent</artifactId>\s*<version>1\.0\.0</version>\s*<scope>)runtime(</scope>)"""
                    ),
                    "$1compile$2",
                )
            check(tampered != original) {
                "Expected ext-parent runtime dependency in POM:\n$original"
            }
            pomFile.writeText(tampered)

            val result =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(result.output)
                .contains(
                    "Published POM for 'metalava' declares runtime-only dependency " +
                        "'com.example:ext-parent' with scope 'compile' instead of 'runtime'"
                )
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if dependencyManagement references an unpublished Metalava project`() {
        val pomFile =
            File(
                sharedWorkspace.publishedArtifactDir("metalava-model"),
                "metalava-model-$TEST_VERSION.pom",
            )
        withRestoredFile(pomFile) { original ->
            val injectedManagedDep =
                """
                    <dependency>
                      <groupId>com.android.tools.metalava</groupId>
                      <artifactId>metalava-model-api</artifactId>
                      <version>$TEST_VERSION</version>
                      <type>pom</type>
                      <scope>import</scope>
                    </dependency>
                """
                    .trimIndent()
            val updated =
                original.replaceFirst(
                    Regex("""(<dependencyManagement>\s*<dependencies>)"""),
                    "$1\n$injectedManagedDep",
                )
            check(updated != original) {
                "Expected <dependencyManagement><dependencies> in POM:\n$original"
            }
            pomFile.writeText(updated)

            val result =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(result.output)
                .contains(
                    "Published POM <dependencyManagement> for 'metalava-model' references " +
                        "unpublished Metalava coordinate " +
                        "'com.android.tools.metalava:metalava-model-api'"
                )
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if a published POM omits a dependencyManagement BOM import`() {
        val pomFile =
            File(
                sharedWorkspace.publishedArtifactDir("metalava-model"),
                "metalava-model-$TEST_VERSION.pom",
            )
        withRestoredFile(pomFile) { original ->
            val updated =
                original.replace(
                    Regex(
                        """<dependencyManagement>.*?</dependencyManagement>""",
                        RegexOption.DOT_MATCHES_ALL,
                    ),
                    "",
                )
            check(updated != original) {
                "Expected <dependencyManagement> BOM import in POM:\n$original"
            }
            pomFile.writeText(updated)

            val result =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(result.output)
                .contains(
                    "Published POM for 'metalava-model' is missing <dependencyManagement> BOM " +
                        "import for platform dependency 'com.example:ext-bom:1.0.0' declared in " +
                        ".module"
                )
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if a published POM mismatches external version or exclusions`() {
        val modelPom =
            File(
                sharedWorkspace.publishedArtifactDir("metalava-model"),
                "metalava-model-$TEST_VERSION.pom",
            )
        withRestoredFile(modelPom) { original ->
            val tamperedVersion =
                original.replace(
                    Regex("""(<artifactId>ext-api</artifactId>\s*<version>)1\.0\.0(</version>)"""),
                    "$19.9.9$2",
                )
            check(tamperedVersion != original) {
                "Expected ext-api 1.0.0 dependency in POM:\n$original"
            }
            modelPom.writeText(tamperedVersion)

            val versionResult =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(versionResult.output)
                .contains(
                    "Published POM for 'metalava-model' declares dependency " +
                        "'com.example:ext-api' with version '9.9.9' instead of '1.0.0'"
                )
        }

        val cliPom =
            File(sharedWorkspace.publishedArtifactDir("metalava"), "metalava-$TEST_VERSION.pom")
        withRestoredFile(cliPom) { original ->
            val strippedExclusions =
                original.replace(
                    Regex(
                        """(<artifactId>ext-parent</artifactId>.*?)(<exclusions>.*?</exclusions>)""",
                        RegexOption.DOT_MATCHES_ALL,
                    ),
                    "$1",
                )
            check(strippedExclusions != original) {
                "Expected ext-parent <exclusions> in POM:\n$original"
            }
            cliPom.writeText(strippedExclusions)

            val exclusionResult =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(exclusionResult.output)
                .contains(
                    "Published POM for 'metalava' declares exclusions [] for " +
                        "'com.example:ext-parent' instead of .module exclusions"
                )
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if a published module omits a required variant, JAR, or escapes dir`() {
        val moduleFile =
            File(
                sharedWorkspace.publishedArtifactDir("metalava-model"),
                "metalava-model-$TEST_VERSION.module",
            )
        withRestoredFile(moduleFile) { original ->
            // Remove apiElements variant.
            val missingVariantJson = JsonParser.parseString(original).asJsonObject
            val filteredVariants = JsonArray()
            for (elem in missingVariantJson.getAsJsonArray("variants")) {
                if (elem.asJsonObject.get("name").asString != "apiElements") {
                    filteredVariants.add(elem)
                }
            }
            missingVariantJson.add("variants", filteredVariants)
            moduleFile.writeText(
                GsonBuilder().setPrettyPrinting().create().toJson(missingVariantJson)
            )
            val missingVariantResult =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(missingVariantResult.output)
                .contains(
                    "Published .module for 'metalava-model' is missing required variant " +
                        "'apiElements'"
                )

            // Clear files arrays on every variant so the main JAR is never checksum-verified.
            val emptyFilesJson = JsonParser.parseString(original).asJsonObject
            for (elem in emptyFilesJson.getAsJsonArray("variants")) {
                elem.asJsonObject.add("files", JsonArray())
            }
            moduleFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(emptyFilesJson))
            val emptyFilesResult =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(emptyFilesResult.output)
                .contains("did not list 'metalava-model-$TEST_VERSION.jar' in its variant files")

            // Point a variant file URL outside the artifact directory via path traversal.
            val traversalJson = JsonParser.parseString(original).asJsonObject
            val firstFile =
                traversalJson
                    .getAsJsonArray("variants")[0]
                    .asJsonObject
                    .getAsJsonArray("files")[0]
                    .asJsonObject
            firstFile.addProperty("url", "../metalava-model-$TEST_VERSION.jar")
            moduleFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(traversalJson))
            val traversalResult =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(traversalResult.output).contains("resolves outside artifact directory")
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if a published artifact omits an expected external runtime dependency`() {
        val modelDir = sharedWorkspace.publishedArtifactDir("metalava-model")
        val pomFile = File(modelDir, "metalava-model-$TEST_VERSION.pom")
        val moduleFile = File(modelDir, "metalava-model-$TEST_VERSION.module")
        withRestoredFile(pomFile) { originalPom ->
            withRestoredFile(moduleFile) { originalModule ->
                // Remove ext-api consistently from both .pom and .module so only the
                // expectedRuntimeExternalCoordinates parity check detects the omission.
                val updatedPom =
                    originalPom.replace(
                        Regex(
                            """<dependency>\s*<groupId>com\.example</groupId>\s*<artifactId>ext-api</artifactId>.*?</dependency>""",
                            RegexOption.DOT_MATCHES_ALL,
                        ),
                        "",
                    )
                check(updatedPom != originalPom) {
                    "Expected ext-api dependency in POM:\n$originalPom"
                }
                pomFile.writeText(updatedPom)

                val json = JsonParser.parseString(originalModule).asJsonObject
                for (variantElem in json.getAsJsonArray("variants")) {
                    val deps = variantElem.asJsonObject.getAsJsonArray("dependencies") ?: continue
                    val filtered = JsonArray()
                    for (depElem in deps) {
                        if (depElem.asJsonObject.get("module").asString != "ext-api") {
                            filtered.add(depElem)
                        }
                    }
                    variantElem.asJsonObject.add("dependencies", filtered)
                }
                moduleFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(json))

                val result =
                    sharedWorkspace.runAndFail(
                        "verifyPublishedArtifacts",
                        "-x",
                        "publishMetalavaPublicationToMavenRepository",
                    )

                // 'metalava' sorts before 'metalava-model' and transitively depends on it, so the
                // runtime parity check catches the missing ext-api on 'metalava' first.
                assertThat(result.output)
                    .contains("Published runtime external dependencies for 'metalava' do not match")
                assertThat(result.output).contains("Missing: [com.example:ext-api]")
            }
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if a published module file references an unpublished project`() {
        val moduleFile =
            File(
                sharedWorkspace.publishedArtifactDir("metalava-model"),
                "metalava-model-$TEST_VERSION.module",
            )
        withRestoredFile(moduleFile) { original ->
            val json = JsonParser.parseString(original).asJsonObject
            val runtimeVariant =
                json
                    .getAsJsonArray("variants")
                    .map { it.asJsonObject }
                    .first { it.get("name").asString == "runtimeElements" }
            val deps =
                runtimeVariant.getAsJsonArray("dependencies")
                    ?: JsonArray().also { runtimeVariant.add("dependencies", it) }
            deps.add(
                JsonObject().apply {
                    addProperty("group", "com.android.tools.metalava")
                    addProperty("module", "metalava-model-api")
                    add(
                        "version",
                        JsonObject().apply { addProperty("requires", TEST_VERSION) },
                    )
                }
            )
            moduleFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(json))

            val result =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(result.output)
                .contains(
                    "references unpublished Metalava coordinate " +
                        "'com.android.tools.metalava:metalava-model-api'"
                )
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if a published module omits an external exclude rule`() {
        val cliDir = sharedWorkspace.publishedArtifactDir("metalava")
        val pomFile = File(cliDir, "metalava-$TEST_VERSION.pom")
        val moduleFile = File(cliDir, "metalava-$TEST_VERSION.module")
        withRestoredFile(pomFile) { originalPom ->
            withRestoredFile(moduleFile) { originalModule ->
                // Strip the exclude consistently from both .pom and .module so POM/module parity
                // passes and resolvePublishedRuntimeClasspath catches the leaked ext-excluded.
                val updatedPom =
                    originalPom.replace(
                        Regex(
                            """(<artifactId>ext-parent</artifactId>.*?)(<exclusions>.*?</exclusions>)""",
                            RegexOption.DOT_MATCHES_ALL,
                        ),
                        "$1",
                    )
                check(updatedPom != originalPom) {
                    "Expected ext-parent <exclusions> in metalava.pom:\n$originalPom"
                }
                pomFile.writeText(updatedPom)

                val json = JsonParser.parseString(originalModule).asJsonObject
                val runtimeVariant =
                    json
                        .getAsJsonArray("variants")
                        .map { it.asJsonObject }
                        .first { it.get("name").asString == "runtimeElements" }
                val extParentDep =
                    runtimeVariant
                        .getAsJsonArray("dependencies")
                        .map { it.asJsonObject }
                        .first { it.get("module").asString == "ext-parent" }
                check(extParentDep.remove("excludes") != null) {
                    "Expected ext-parent in metalava.module to carry excludes"
                }
                moduleFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(json))

                val result =
                    sharedWorkspace.runAndFail(
                        "verifyPublishedArtifacts",
                        "-x",
                        "publishMetalavaPublicationToMavenRepository",
                    )
                assertThat(result.output)
                    .contains("Published runtime external dependencies for 'metalava' do not match")
                assertThat(result.output).contains("com.example:ext-excluded")
            }
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if a published module omits an exclude on a Metalava dependency`() {
        val cliDir = sharedWorkspace.publishedArtifactDir("metalava")
        val pomFile = File(cliDir, "metalava-$TEST_VERSION.pom")
        val moduleFile = File(cliDir, "metalava-$TEST_VERSION.module")
        withRestoredFile(pomFile) { originalPom ->
            withRestoredFile(moduleFile) { originalModule ->
                // Strip the exclude on metalava-model from both .pom and .module so
                // resolvePublishedRuntimeClasspath catches the leaked transitive ext-trans-child.
                val updatedPom =
                    originalPom.replace(
                        Regex(
                            """(<artifactId>metalava-model</artifactId>.*?)(<exclusions>.*?</exclusions>)""",
                            RegexOption.DOT_MATCHES_ALL,
                        ),
                        "$1",
                    )
                check(updatedPom != originalPom) {
                    "Expected metalava-model <exclusions> in metalava.pom:\n$originalPom"
                }
                pomFile.writeText(updatedPom)

                val json = JsonParser.parseString(originalModule).asJsonObject
                val runtimeVariant =
                    json
                        .getAsJsonArray("variants")
                        .map { it.asJsonObject }
                        .first { it.get("name").asString == "runtimeElements" }
                val modelDep =
                    runtimeVariant
                        .getAsJsonArray("dependencies")
                        .map { it.asJsonObject }
                        .first { it.get("module").asString == "metalava-model" }
                check(modelDep.remove("excludes") != null) {
                    "Expected metalava-model in metalava.module to carry excludes"
                }
                moduleFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(json))

                val result =
                    sharedWorkspace.runAndFail(
                        "verifyPublishedArtifacts",
                        "-x",
                        "publishMetalavaPublicationToMavenRepository",
                    )
                assertThat(result.output)
                    .contains("Published runtime external dependencies for 'metalava' do not match")
                assertThat(result.output).contains("com.example:ext-trans-child")
            }
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if aggregate build info references an unpublished project`() {
        val aggregateFile = File(sharedWorkspace.distDir, "androidx_aggregate_build_info.txt")
        withRestoredFile(aggregateFile) { original ->
            aggregateFile.writeText(
                original.replaceFirst(
                    "\"artifactId\": \"metalava-model\"",
                    "\"artifactId\": \"metalava-model-api\"",
                )
            )

            val result =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                    "-x",
                    "createAggregateBuildInfoFiles",
                )
            assertThat(result.output).contains("Aggregate build info")
            assertThat(result.output).contains("metalava-model-api")
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails if aggregate build info sets isTipOfTree to false`() {
        val aggregateFile = File(sharedWorkspace.distDir, "androidx_aggregate_build_info.txt")
        withRestoredFile(aggregateFile) { original ->
            val tampered = original.replaceFirst("\"isTipOfTree\": true", "\"isTipOfTree\": false")
            check(tampered != original) { "Expected isTipOfTree: true in aggregate build info" }
            aggregateFile.writeText(tampered)

            val result =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                    "-x",
                    "createAggregateBuildInfoFiles",
                )
            assertThat(result.output).contains("has isTipOfTree=false")
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails on JAR SHA-256 mismatch`() {
        val jarFile =
            File(
                sharedWorkspace.publishedArtifactDir("metalava-model"),
                "metalava-model-$TEST_VERSION.jar",
            )
        val originalBytes = jarFile.readBytes()
        try {
            jarFile.appendBytes(byteArrayOf(0x00))

            val result =
                sharedWorkspace.runAndFail(
                    "verifyPublishedArtifacts",
                    "-x",
                    "publishMetalavaPublicationToMavenRepository",
                )
            assertThat(result.output).contains("SHA-256 mismatch")
        } finally {
            jarFile.writeBytes(originalBytes)
        }
    }

    @Test
    fun `verifyPublishedArtifacts fails when a model smoke-check class is missing`() {
        val result =
            sharedWorkspace.runAndFail(
                "verifyPublishedArtifacts",
                "-PtestModelClasses=com.example.DoesNotExist",
                "-x",
                "publishMetalavaPublicationToMavenRepository",
            )
        assertThat(result.output)
            .contains("Failed to load smoke-check class 'com.example.DoesNotExist'")
    }

    @Test
    fun `verifyPublishedArtifacts fails when expected ToolProvider is missing`() {
        val result =
            sharedWorkspace.runAndFail(
                "verifyPublishedArtifacts",
                "-PtestToolProvider=missing-provider",
                "-x",
                "publishMetalavaPublicationToMavenRepository",
            )
        assertThat(result.output).contains("did not provide ToolProvider named 'missing-provider'")
    }

    @Test
    fun `verifyPublishedArtifacts fails when CLI output does not contain expected text`() {
        val result =
            sharedWorkspace.runAndFail(
                "verifyPublishedArtifacts",
                "-PtestExpectedCliOutput=unexpected-version-banner",
                "-x",
                "publishMetalavaPublicationToMavenRepository",
            )
        assertThat(result.output)
            .contains(
                "Published CLI smoke check output did not contain 'unexpected-version-banner'"
            )
    }

    @Test
    fun `verifyPublishedArtifacts fails when run under IDE sync`() {
        val result =
            sharedWorkspace.runAndFail(
                "verifyPublishedArtifacts",
                "-Didea.sync.active=true",
                "-x",
                "publishMetalavaPublicationToMavenRepository",
                "-x",
                "createAggregateBuildInfoFiles",
            )
        assertThat(result.output)
            .contains("Refusing to run publishing verification task ':verifyPublishedArtifacts'")
    }

    @Test
    fun `setUpVerifyPublishedArtifactsTask supports disabling smoke checks and guards cliArtifactId`() {
        val rootBuildScript =
            """
                plugins {
                    id("metalava-build-plugin") apply false
                }

                import com.android.tools.metalava.buildinfo.setUpAggregateBuildInfoFileTask
                import com.android.tools.metalava.setUpVerifyPublishedArtifactsTask

                setUpAggregateBuildInfoFileTask()
                if (providers.gradleProperty("testCliWithoutChecks").orNull == "true") {
                    setUpVerifyPublishedArtifactsTask(
                        modelArtifactId = null,
                        cliArtifactId = "simple-lib",
                        cliMainClass = null,
                        expectedToolProviderName = null,
                    )
                } else {
                    // Omitting modelArtifactId and cliArtifactId must cleanly disable both smoke
                    // checks without triggering partial-configuration errors.
                    setUpVerifyPublishedArtifactsTask(
                        modelArtifactId = null,
                        cliArtifactId = null,
                    )
                }
            """
                .trimIndent()

        val workspace = TestMetalavaWorkspace.create(tempFolder, rootBuildScript = rootBuildScript)
        workspace.addSubproject(
            path = ":simple-lib",
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
                    "src/main/java/com/example/simple/Simple.java" to
                        "package com.example.simple; public class Simple {}"
                ),
        )

        workspace.run("verifyPublishedArtifacts")

        val badCliResult =
            workspace.runAndFail(
                "verifyPublishedArtifacts",
                "-PtestCliWithoutChecks=true",
                "-x",
                "publishMetalavaPublicationToMavenRepository",
            )
        assertThat(badCliResult.output)
            .contains(
                "cliArtifactId 'simple-lib' was configured without cliMainClass or " +
                    "expectedToolProviderName."
            )
    }

    /** Executes [block] with [file]'s original UTF-8 text and restores [file] in `finally`. */
    private fun withRestoredFile(file: File, block: (original: String) -> Unit) {
        val original = file.readText(Charsets.UTF_8)
        try {
            block(original)
        } finally {
            file.writeText(original, Charsets.UTF_8)
        }
    }

    companion object {
        /**
         * Shared temporary directory holding one pre-published workspace for counterfactual runs.
         */
        @ClassRule @JvmField val sharedTempFolder = TemporaryFolder()

        /** Pre-published workspace reused across counterfactual tests in this class. */
        private lateinit var sharedWorkspace: TestMetalavaWorkspace

        /**
         * Compiles and publishes the shared baseline workspace once for all counterfactual tests.
         */
        @BeforeClass
        @JvmStatic
        fun setUpSharedWorkspace() {
            sharedWorkspace = createEmbeddedMetalavaWorkspace(sharedTempFolder)
            sharedWorkspace.run("publish", "createAggregateBuildInfoFiles")
        }

        /**
         * Creates a multi-project workspace mirroring Metalava's published `:metalava-model`
         * (embedding `:metalava-model-api` with an external API dependency), `:metalava` (embedding
         * `:metalava-feature`, providing `ToolProvider`, and declaring an excluded transitive
         * external dependency), and `:extra-consumer` (pulling in the excluded external dependency
         * so `externalTransitiveGraph` knows the edge `ext-parent -> ext-excluded`).
         */
        private fun createEmbeddedMetalavaWorkspace(
            folder: TemporaryFolder
        ): TestMetalavaWorkspace {
            val rootBuildScript =
                """
                    plugins {
                        id("metalava-build-plugin") apply false
                    }

                    import com.android.tools.metalava.VERIFY_PUBLISHED_ARTIFACTS_TASK
                    import com.android.tools.metalava.VerifyPublishedArtifactsTask
                    import com.android.tools.metalava.buildinfo.setUpAggregateBuildInfoFileTask
                    import com.android.tools.metalava.setUpVerifyPublishedArtifactsTask

                    setUpAggregateBuildInfoFileTask()
                    val classesOverride =
                        providers.gradleProperty("testModelClasses").orNull
                            ?.split(",")
                            ?.filter { it.isNotBlank() }
                    val toolProviderOverride =
                        providers.gradleProperty("testToolProvider").orNull
                    val expectedOutputOverride =
                        providers.gradleProperty("testExpectedCliOutput").orNull

                    setUpVerifyPublishedArtifactsTask(
                        modelArtifactId = "metalava-model",
                        modelSmokeCheckClasses =
                            classesOverride
                                ?: listOf(
                                    "com.android.tools.metalava.api.model.ApiItem",
                                    "com.android.tools.metalava.model.ClassItem",
                                ),
                        cliArtifactId = "metalava",
                        cliMainClass = "com.android.tools.metalava.Driver",
                        cliArgs = listOf("--version"),
                        expectedToolProviderName = toolProviderOverride ?: "metalava",
                    )
                    if (expectedOutputOverride != null) {
                        tasks.named(
                            VERIFY_PUBLISHED_ARTIFACTS_TASK,
                            VerifyPublishedArtifactsTask::class.java,
                        ) {
                            expectedCliOutputSubstring.set(expectedOutputOverride)
                        }
                    }
                """
                    .trimIndent()

            val workspace = TestMetalavaWorkspace.create(folder, rootBuildScript = rootBuildScript)
            workspace.publishExternalBom(
                group = "com.example",
                artifact = "ext-bom",
                version = "1.0.0",
                constraints = listOf("com.example:ext-api:1.0.0"),
            )
            workspace.publishExternalLibrary(
                group = "com.example",
                artifact = "ext-api",
                version = "1.0.0",
                packageName = "com.example.extapi",
                className = "ExtApiMarker",
                source = "package com.example.extapi; public class ExtApiMarker {}",
            )
            workspace.publishExternalLibrary(
                group = "com.example",
                artifact = "ext-excluded",
                version = "1.0.0",
                packageName = "com.example.excluded",
                className = "ExtExcluded",
                source = "package com.example.excluded; public class ExtExcluded {}",
            )

            // Reference ExtExcluded directly in ExtParent so publishExternalLibrary's javac
            // -classpath wiring is exercised by compilation.
            workspace.publishExternalLibrary(
                group = "com.example",
                artifact = "ext-parent",
                version = "1.0.0",
                packageName = "com.example.parent",
                className = "ExtParent",
                source =
                    """
                        package com.example.parent;
                        import com.example.excluded.ExtExcluded;
                        public class ExtParent {
                            public static ExtExcluded unused() { return new ExtExcluded(); }
                        }
                    """
                        .trimIndent(),
                dependencies = listOf("com.example:ext-excluded:1.0.0"),
            )
            workspace.publishExternalLibrary(
                group = "com.example",
                artifact = "ext-trans-child",
                version = "1.0.0",
                packageName = "com.example.transchild",
                className = "ExtTransChild",
                source = "package com.example.transchild; public class ExtTransChild {}",
            )
            workspace.publishExternalLibrary(
                group = "com.example",
                artifact = "ext-trans-parent",
                version = "1.0.0",
                packageName = "com.example.transparent",
                className = "ExtTransParent",
                source = "package com.example.transparent; public class ExtTransParent {}",
                dependencies = listOf("com.example:ext-trans-child:1.0.0"),
            )

            workspace.addSubproject(
                path = ":metalava-model-api",
                buildScript =
                    """
                        plugins {
                            `java-library`
                            id("org.jetbrains.kotlin.jvm")
                            id("metalava-build-plugin")
                        }
                        dependencies {
                            api(platform("com.example:ext-bom:1.0.0"))
                            api("com.example:ext-api")
                        }
                    """
                        .trimIndent(),
                sources =
                    mapOf(
                        "src/main/kotlin/com/android/tools/metalava/api/model/ApiItem.kt" to
                            """
                                package com.android.tools.metalava.api.model

                                import com.example.extapi.ExtApiMarker

                                interface ApiItem {
                                    fun describe(): String
                                    fun marker(): ExtApiMarker? = null
                                }
                            """
                                .trimIndent()
                    ),
            )

            workspace.addSubproject(
                path = ":metalava-model",
                buildScript =
                    """
                        plugins {
                            `java-library`
                            id("org.jetbrains.kotlin.jvm")
                            id("metalava-build-plugin")
                            id("maven-publish")
                        }
                        dependencies {
                            embedApi(project(":metalava-model-api"))
                            implementation("com.example:ext-trans-parent:1.0.0")
                        }
                    """
                        .trimIndent(),
                sources =
                    mapOf(
                        "src/main/kotlin/com/android/tools/metalava/model/ClassItem.kt" to
                            """
                                package com.android.tools.metalava.model

                                import com.android.tools.metalava.api.model.ApiItem

                                interface ClassItem : ApiItem
                            """
                                .trimIndent()
                    ),
            )

            workspace.addSubproject(
                path = ":metalava-feature",
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
                        "src/main/kotlin/com/android/tools/metalava/feature/VersionFormatter.kt" to
                            """
                                package com.android.tools.metalava.feature

                                object VersionFormatter {
                                    fun format(version: String): String =
                                        "metalava version: ${'$'}version"
                                }
                            """
                                .trimIndent()
                    ),
            )

            workspace.addSubproject(
                path = ":metalava",
                buildScript =
                    """
                        plugins {
                            id("application")
                            id("org.jetbrains.kotlin.jvm")
                            id("maven-publish")
                            id("metalava-build-plugin")
                        }
                        application {
                            mainClass.set("com.android.tools.metalava.Driver")
                        }
                        dependencies {
                            implementation(project(":metalava-model")) {
                                exclude(group = "com.example", module = "ext-trans-child")
                            }
                            embedImplementation(project(":metalava-feature"))
                            implementation("com.example:ext-parent:1.0.0") {
                                exclude(group = "com.example", module = "ext-excluded")
                            }
                        }
                    """
                        .trimIndent(),
                sources =
                    mapOf(
                        "src/main/kotlin/com/android/tools/metalava/Driver.kt" to
                            """
                                package com.android.tools.metalava

                                import com.android.tools.metalava.feature.VersionFormatter
                                import com.android.tools.metalava.model.ClassItem
                                import java.io.PrintWriter
                                import java.util.spi.ToolProvider

                                object Driver {
                                    @JvmStatic
                                    fun main(args: Array<String>) {
                                        val item = object : ClassItem {
                                            override fun describe(): String = "$TEST_VERSION"
                                        }
                                        println(VersionFormatter.format(item.describe()))
                                    }
                                }

                                class FakeToolProvider : ToolProvider {
                                    override fun name(): String = "metalava"
                                    override fun run(out: PrintWriter, err: PrintWriter, vararg args: String): Int {
                                        out.println(VersionFormatter.format("$TEST_VERSION"))
                                        return 0
                                    }
                                }
                            """
                                .trimIndent(),
                        "src/main/resources/META-INF/services/java.util.spi.ToolProvider" to
                            "com.android.tools.metalava.FakeToolProvider\n",
                    ),
            )

            // Secondary published artifact referencing ext-parent WITHOUT exclude so the root
            // externalTransitiveGraph contains the ext-parent -> ext-excluded edge, allowing the
            // counterfactual test to verify that removing metalava.module's exclude fails.
            workspace.addSubproject(
                path = ":metalava-aux",
                buildScript =
                    """
                        plugins {
                            `java-library`
                            id("metalava-build-plugin")
                            id("maven-publish")
                        }
                        dependencies {
                            implementation("com.example:ext-parent:1.0.0")
                        }
                    """
                        .trimIndent(),
                sources =
                    mapOf(
                        "src/main/java/com/android/tools/metalava/aux/AuxMarker.java" to
                            "package com.android.tools.metalava.aux; public class AuxMarker {}"
                    ),
            )

            return workspace
        }
    }
}
