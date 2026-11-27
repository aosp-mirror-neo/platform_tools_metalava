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

import java.io.File
import java.util.TreeMap
import java.util.TreeSet
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Deterministically merges `META-INF/services/` provider configuration files across a host project
 * and its embedded subprojects.
 *
 * Comment lines (`# ...`) and blank lines are stripped according to the [java.util.ServiceLoader]
 * format, provider class names are deduplicated and sorted lexicographically, and merged files are
 * written under [outputDirectory]`/META-INF/services/` with Unix (`\n`) line endings so the
 * resulting JAR is reproducible across runs and platforms.
 */
@CacheableTask
abstract class MergeServiceFilesTask : DefaultTask() {

    /**
     * Input roots or [org.gradle.api.file.FileTree]s rooted above `META-INF/` so Gradle preserves
     * relative paths of the form `META-INF/services/<serviceName>`.
     *
     * Passing `.jar`/`.zip` archives, standalone service files, trees rooted inside `META-INF/`, or
     * nested subdirectories under `META-INF/services/` fails fast during [merge].
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val serviceFiles: ConfigurableFileCollection

    /** Output root directory into which merged `META-INF/services/` files are written. */
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    /** Reads all input service files, merges providers per service name, and writes the outputs. */
    @TaskAction
    fun merge() {
        // Clear previous outputs first so service files removed from all inputs do not linger
        // across incremental runs.
        val outDir = outputDirectory.get().asFile
        if (outDir.exists()) {
            outDir.deleteRecursively()
        }
        outDir.mkdirs()

        // Use sorted maps/sets so merged service files and provider lines are written in a
        // deterministic order for reproducible JAR archives.
        val providersByService = TreeMap<String, TreeSet<String>>()
        serviceFiles.asFileTree.visit { details ->
            if (details.isDirectory) {
                return@visit
            }

            // Fail fast on misconfigured inputs (archives or mis-rooted/standalone service files)
            // that would otherwise be silently skipped by the META-INF/services/ prefix check.
            val relativePath = details.relativePath.pathString
            if (
                !relativePath.contains('/') &&
                    (details.name.endsWith(".jar") || details.name.endsWith(".zip"))
            ) {
                throw GradleException(
                    "MergeServiceFilesTask requires unpacked directories or FileTrees rooted " +
                        "above 'META-INF/', not archive '${details.file}'."
                )
            }
            if (!relativePath.startsWith(SERVICES_PATH_PREFIX)) {
                val parent = details.file.parentFile
                val grandParent = parent?.parentFile
                if (parent?.name == "services" && grandParent?.name == "META-INF") {
                    throw GradleException(
                        "Service file '${details.file}' had relative path '$relativePath' " +
                            "instead of '${SERVICES_PATH_PREFIX}<serviceName>'. Pass unpacked " +
                            "root directories or FileTrees rooted above 'META-INF/'."
                    )
                }
                return@visit
            }

            // ServiceLoader only loads direct children of META-INF/services/; reject nested paths.
            val serviceName = relativePath.removePrefix(SERVICES_PATH_PREFIX)
            if (serviceName.isEmpty() || '/' in serviceName) {
                throw GradleException(
                    "Invalid service path '$relativePath' (${details.file}): ServiceLoader only " +
                        "loads direct children of '$SERVICES_PATH_PREFIX'."
                )
            }

            // Skip comment-only or blank service files so empty entries are not emitted into the
            // merged JAR.
            val providers = parseServiceProviders(details.file.readText(Charsets.UTF_8))
            if (providers.isNotEmpty()) {
                providersByService.getOrPut(serviceName) { TreeSet() }.addAll(providers)
            }
        }

        for ((serviceName, providers) in providersByService) {
            val targetFile = File(outDir, "$SERVICES_PATH_PREFIX$serviceName")
            targetFile.parentFile.mkdirs()
            targetFile.writeText(
                providers.joinToString(separator = "\n", postfix = "\n"),
                Charsets.UTF_8,
            )
        }
    }

    companion object {
        /** Relative path prefix for Java `ServiceLoader` provider files inside a JAR. */
        internal const val SERVICES_PATH_PREFIX = "META-INF/services/"

        /**
         * Parses non-empty provider class names from a [java.util.ServiceLoader] resource file,
         * stripping `#` comments and surrounding whitespace.
         */
        internal fun parseServiceProviders(content: String): List<String> =
            content
                .lineSequence()
                .map { line -> line.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toList()
    }
}
