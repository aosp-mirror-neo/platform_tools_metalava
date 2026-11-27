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

import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

/**
 * Reusable workspace harness built on Gradle TestKit ([GradleRunner]), Gradle's functional testing
 * library for running real end-to-end Gradle builds in isolated temporary directories against the
 * `buildSrc` plugins under test.
 *
 * Mirrors `tools/metalava`'s multi-project layout (`version.properties`, `:stub-annotations`,
 * `out/dist`, offline prebuilts or `DOWNLOAD_DEPENDENCIES=true`, and local synthetic external Maven
 * artifacts) so `buildSrc` tests can verify multi-project wiring, Maven publishing, Configuration
 * Cache, and Build Cache behavior realistically.
 *
 * @property projectDir Root directory of the synthetic `tools/metalava` Gradle build.
 * @property outDir Root build output directory (`<workspace>/out`).
 * @property distDir Distribution output directory (`<outDir>/dist`).
 * @property externalRepoDir Local Maven repository holding synthetic external test libraries.
 * @property buildCacheDir Isolated local Gradle build cache directory for this workspace.
 */
internal class TestMetalavaWorkspace
private constructor(
    private val tempFolder: TemporaryFolder,
    val projectDir: File,
    val outDir: File,
    val distDir: File,
    val externalRepoDir: File,
    val buildCacheDir: File,
    private val useRelativeOutDir: Boolean,
) {
    /** Published local Maven repository (`<distDir>/repo/m2repository`). */
    val m2RepositoryDir: File = File(distDir, "repo/m2repository")

    /** Registered Gradle subproject paths included in `settings.gradle.kts`. */
    private val subprojectPaths = mutableListOf<String>()

    /** Returns the published Maven directory for [artifactId] under [m2RepositoryDir]. */
    fun publishedArtifactDir(artifactId: String): File =
        File(m2RepositoryDir, "com/android/tools/metalava/$artifactId/$TEST_VERSION")

    /** Adds a subproject at Gradle [path] with the given [buildScript] and [sources]. */
    fun addSubproject(
        path: String,
        buildScript: String,
        sources: Map<String, String> = emptyMap(),
    ) {
        subprojectPaths.add(path)
        val relativeDir = path.removePrefix(":").replace(":", "/")
        val subDir = File(projectDir, relativeDir).apply { mkdirs() }
        File(subDir, "build.gradle.kts").writeText(buildScript + "\n")
        for ((relPath, content) in sources) {
            writeFile(subDir, relPath, content)
        }
        writeSettings()
    }

    /**
     * Compiles a single Java source file and publishes it as a valid Maven module (`.jar` + `.pom`)
     * into [externalRepoDir], with optional transitive Maven [dependencies]
     * (`"group:artifact:ver"`).
     */
    fun publishExternalLibrary(
        group: String,
        artifact: String,
        version: String,
        packageName: String,
        className: String,
        source: String,
        dependencies: List<String> = emptyList(),
    ) {
        val workDir = tempFolder.newFolder("ext-$artifact-${System.nanoTime()}")
        val srcDir = File(workDir, "src")
        val classesDir = File(workDir, "classes").apply { mkdirs() }
        val javaFile =
            File(srcDir, "${packageName.replace('.', '/')}/$className.java").apply {
                parentFile.mkdirs()
                writeText(source)
            }

        // Parse declared Maven dependencies upfront so both javac (via -classpath against already
        // published jars in externalRepoDir) and POM generation share the parsed triples.
        val parsedDeps =
            dependencies.map { coord ->
                val parts = coord.split(":")
                check(parts.size == 3) { "Expected group:artifact:version, got '$coord'" }
                Triple(parts[0], parts[1], parts[2])
            }
        val classpathJars =
            parsedDeps
                .map { (g, a, v) ->
                    File(externalRepoDir, "${g.replace('.', '/')}/$a/$v/$a-$v.jar")
                }
                .filter { it.isFile }

        val compiler =
            ToolProvider.getSystemJavaCompiler()
                ?: error("System Java compiler is required to build external test libraries")
        val errOut = ByteArrayOutputStream()
        val compilerArgs = buildList {
            add("--release")
            add("17")
            if (classpathJars.isNotEmpty()) {
                add("-classpath")
                add(classpathJars.joinToString(File.pathSeparator) { it.absolutePath })
            }
            add("-d")
            add(classesDir.absolutePath)
            add(javaFile.absolutePath)
        }
        val exitCode = compiler.run(null, null, errOut, *compilerArgs.toTypedArray())
        check(exitCode == 0) {
            "Failed to compile synthetic external library $group:$artifact:$version:\n" +
                errOut.toString(Charsets.UTF_8)
        }

        val moduleDir =
            File(externalRepoDir, "${group.replace('.', '/')}/$artifact/$version").apply {
                mkdirs()
            }
        val jarFile = File(moduleDir, "$artifact-$version.jar")
        JarOutputStream(FileOutputStream(jarFile)).use { jos ->
            classesDir
                .walkTopDown()
                .filter { it.isFile }
                .forEach { classFile ->
                    val entryName = classFile.relativeTo(classesDir).invariantSeparatorsPath
                    jos.putNextEntry(JarEntry(entryName))
                    jos.write(classFile.readBytes())
                    jos.closeEntry()
                }
        }

        // Build the POM line-by-line rather than using trimIndent() with multiline interpolation so
        // leading whitespace can never precede the <?xml ...?> prolog.
        val pomContent = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<project xmlns="http://maven.apache.org/POM/4.0.0">""")
            appendLine("  <modelVersion>4.0.0</modelVersion>")
            appendLine("  <groupId>$group</groupId>")
            appendLine("  <artifactId>$artifact</artifactId>")
            appendLine("  <version>$version</version>")
            if (parsedDeps.isNotEmpty()) {
                appendLine("  <dependencies>")
                for ((depGroup, depArtifact, depVersion) in parsedDeps) {
                    appendLine("    <dependency>")
                    appendLine("      <groupId>$depGroup</groupId>")
                    appendLine("      <artifactId>$depArtifact</artifactId>")
                    appendLine("      <version>$depVersion</version>")
                    appendLine("      <scope>compile</scope>")
                    appendLine("    </dependency>")
                }
                appendLine("  </dependencies>")
            }
            appendLine("</project>")
        }
        File(moduleDir, "$artifact-$version.pom").writeText(pomContent)
    }

    /**
     * Publishes a synthetic POM-only BOM (`<packaging>pom</packaging>`) with
     * `<dependencyManagement>` [constraints] (`"group:artifact:version"`) into [externalRepoDir].
     */
    fun publishExternalBom(
        group: String,
        artifact: String,
        version: String,
        constraints: List<String>,
    ) {
        val parsedConstraints =
            constraints.map { coord ->
                val parts = coord.split(":")
                check(parts.size == 3) { "Expected group:artifact:version, got '$coord'" }
                Triple(parts[0], parts[1], parts[2])
            }
        val moduleDir =
            File(externalRepoDir, "${group.replace('.', '/')}/$artifact/$version").apply {
                mkdirs()
            }
        val pomContent = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<project xmlns="http://maven.apache.org/POM/4.0.0">""")
            appendLine("  <modelVersion>4.0.0</modelVersion>")
            appendLine("  <groupId>$group</groupId>")
            appendLine("  <artifactId>$artifact</artifactId>")
            appendLine("  <version>$version</version>")
            appendLine("  <packaging>pom</packaging>")
            if (parsedConstraints.isNotEmpty()) {
                appendLine("  <dependencyManagement>")
                appendLine("    <dependencies>")
                for ((depGroup, depArtifact, depVersion) in parsedConstraints) {
                    appendLine("      <dependency>")
                    appendLine("        <groupId>$depGroup</groupId>")
                    appendLine("        <artifactId>$depArtifact</artifactId>")
                    appendLine("        <version>$depVersion</version>")
                    appendLine("      </dependency>")
                }
                appendLine("    </dependencies>")
                appendLine("  </dependencyManagement>")
            }
            appendLine("</project>")
        }
        File(moduleDir, "$artifact-$version.pom").writeText(pomContent)
    }

    /** Runs Gradle with [args] and expects success. */
    fun run(vararg args: String): BuildResult = runner(*args).build()

    /** Runs Gradle with [args] and expects failure. */
    fun runAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()

    /** Configures a [GradleRunner] targeting [projectDir] with the `buildSrc` plugin classpath. */
    private fun runner(vararg args: String): GradleRunner = createRunner(projectDir, *args)

    /** Writes `settings.gradle.kts` including all registered subprojects and configured repos. */
    private fun writeSettings() {
        val includes = subprojectPaths.joinToString("\n") { "include(\"$it\")" }
        val buildDirExpr =
            if (useRelativeOutDir) {
                // Mirror settings.gradle.kts when OUT_DIR is unset (contains literal ".."
                // segments).
                """File(rootDir, "../../out/metalava${'$'}suffix")"""
            } else {
                """file("${outDir.absolutePath.replace("\\", "\\\\")}/metalava${'$'}suffix")"""
            }

        File(projectDir, "settings.gradle.kts")
            .writeText(
                """
                    rootProject.name = "metalava"

                    buildCache {
                        local {
                            directory = file("${buildCacheDir.absolutePath.replace("\\", "\\\\")}")
                        }
                    }

                    dependencyResolutionManagement {
                        repositories {
                            maven { url = uri("${externalRepoDir.toURI()}") }
                            ${repositoriesBlock()}
                        }
                    }

                    settings.gradle.beforeProject {
                        val suffix = "${'$'}{path.replace(":", "/")}/build"
                        layout.buildDirectory.set($buildDirExpr)
                    }

                    $includes
                """
                    .trimIndent() + "\n"
            )
    }

    companion object {
        /** Metalava version written into every synthetic workspace's `version.properties`. */
        const val TEST_VERSION = "1.0.0-test"

        /**
         * Creates an isolated multi-project Metalava TestKit workspace under [tempFolder] with the
         * given [rootBuildScript].
         */
        fun create(
            tempFolder: TemporaryFolder,
            rootBuildScript: String =
                """
                    plugins {
                        id("metalava-build-plugin") apply false
                    }

                    import com.android.tools.metalava.buildinfo.setUpAggregateBuildInfoFileTask

                    setUpAggregateBuildInfoFileTask()
                """
                    .trimIndent(),
            useRelativeOutDir: Boolean = false,
        ): TestMetalavaWorkspace {
            val root = tempFolder.newFolder("ws-${System.nanoTime()}")
            val projectDir = File(root, "tools/metalava").apply { mkdirs() }
            val outDir = File(root, "out").apply { mkdirs() }
            val distDir = File(outDir, "dist")
            val externalRepoDir = File(root, "external-repo").apply { mkdirs() }
            val buildCacheDir = File(root, "build-cache").apply { mkdirs() }

            File(projectDir, "version.properties").writeText("metalavaVersion=$TEST_VERSION\n")
            File(projectDir, "test-data/prebuilts-sdk").mkdirs()

            // Disable parallel execution inside nested TestKit builds so parallel buildSrc:test
            // forks do not oversubscribe threads and test logs stay deterministic.
            File(projectDir, "gradle.properties")
                .writeText(
                    """
                        org.gradle.caching=true
                        org.gradle.configuration-cache=true
                        org.gradle.parallel=false
                    """
                        .trimIndent() + "\n"
                )
            File(projectDir, "build.gradle.kts").writeText(rootBuildScript + "\n")

            val workspace =
                TestMetalavaWorkspace(
                    tempFolder = tempFolder,
                    projectDir = projectDir,
                    outDir = outDir,
                    distDir = distDir,
                    externalRepoDir = externalRepoDir,
                    buildCacheDir = buildCacheDir,
                    useRelativeOutDir = useRelativeOutDir,
                )

            // MetalavaBuildPlugin.configureTestTasks always resolves :stub-annotations.
            workspace.addSubproject(
                path = ":stub-annotations",
                buildScript = "plugins { `java-library` }\n",
                sources =
                    mapOf(
                        "src/main/java/stub/StubMarker.java" to
                            "package stub; public @interface StubMarker {}"
                    ),
            )

            return workspace
        }

        /**
         * Configures a [GradleRunner] targeting [dir] with the `buildSrc` plugin classpath and
         * stripped `OUT_DIR`/`DIST_DIR` environment variables so CI server variables never leak
         * into TestKit runs.
         */
        fun createRunner(dir: File, vararg args: String): GradleRunner {
            val isolatedEnv = System.getenv().filterKeys { it != "DIST_DIR" && it != "OUT_DIR" }
            return GradleRunner.create()
                .withProjectDir(dir)
                .withPluginClasspath()
                .withEnvironment(isolatedEnv)
                .withArguments(*args, "--stacktrace")
        }

        /**
         * Formats repository declarations matching `settings.gradle.kts`: `google();
         * mavenCentral()` when `DOWNLOAD_DEPENDENCIES=true`, or local `maven { ... }` entries from
         * `metalava.prebuilts.repos` otherwise.
         */
        fun repositoriesBlock(): String {
            if (System.getProperty("metalava.download.dependencies") == "true") {
                return "google()\nmavenCentral()"
            }
            val prebuiltsProp =
                System.getProperty("metalava.prebuilts.repos")
                    ?: error("System property metalava.prebuilts.repos is not set")
            return prebuiltsProp
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n") { repoPath ->
                    "maven { url = uri(\"${File(repoPath).toURI()}\") }"
                }
        }

        /** Writes [content] to [relativePath] under [rootDir], creating parent directories. */
        fun writeFile(rootDir: File, relativePath: String, content: String) {
            File(rootDir, relativePath).apply {
                parentFile.mkdirs()
                writeText(content)
            }
        }

        /** Returns all non-directory entry names inside [archiveFile]. */
        fun jarEntries(archiveFile: File): Set<String> =
            JarFile(archiveFile).use { jar ->
                jar.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toSet()
            }

        /**
         * Parses direct `<project>/<dependencies>/<dependency>` entries from [pomFile] into a map
         * of `"group:artifact:version"` to scope.
         */
        fun parsePomDependencies(pomFile: File): Map<String, String> {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile)
            val result = linkedMapOf<String, String>()
            val rootChildren = document.documentElement.childNodes
            for (i in 0 until rootChildren.length) {
                val depsNode = rootChildren.item(i) as? Element ?: continue
                if (depsNode.tagName != "dependencies") continue
                val depChildren = depsNode.childNodes
                for (j in 0 until depChildren.length) {
                    val depEl = depChildren.item(j) as? Element ?: continue
                    if (depEl.tagName != "dependency") continue
                    var group = ""
                    var artifact = ""
                    var version = ""
                    var scope = "compile"
                    val fields = depEl.childNodes
                    for (k in 0 until fields.length) {
                        val field = fields.item(k) as? Element ?: continue
                        when (field.tagName) {
                            "groupId" -> group = field.textContent.trim()
                            "artifactId" -> artifact = field.textContent.trim()
                            "version" -> version = field.textContent.trim()
                            "scope" -> scope = field.textContent.trim()
                        }
                    }
                    if (group.isNotEmpty() && artifact.isNotEmpty()) {
                        result["$group:$artifact:$version"] = scope
                    }
                }
            }
            return result
        }

        /** Extracts `group:module:version` coordinates from a named variant in `.module` JSON. */
        fun moduleVariantDependencies(moduleJson: JsonObject, variantName: String): List<String> {
            val variant =
                moduleJson
                    .getAsJsonArray("variants")
                    .map { it.asJsonObject }
                    .first { it.get("name").asString == variantName }
            val deps = variant.getAsJsonArray("dependencies") ?: return emptyList()
            return deps.map { elem ->
                val obj = elem.asJsonObject
                val group = obj.get("group").asString
                val module = obj.get("module").asString
                val version = obj.getAsJsonObject("version")?.get("requires")?.asString ?: ""
                "$group:$module:$version"
            }
        }
    }
}
