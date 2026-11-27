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

import java.nio.file.Path
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.named

/** Declarable configuration for embedding a local subproject's API into a `java-library` host. */
internal const val EMBED_API_CONFIGURATION = "embedApi"

/** Declarable configuration for embedding a local subproject as an implementation detail. */
internal const val EMBED_IMPLEMENTATION_CONFIGURATION = "embedImplementation"

/** Consumable configuration on unpublished projects exposing raw outputs for embedding. */
internal const val EMBED_ELEMENTS_CONFIGURATION = "embedElements"

/** Resolvable configuration traversing only the transitive embedded project cluster. */
private const val EMBED_CLUSTER_CONFIGURATION = "embedCluster"

/** Gradle [Category] attribute value identifying raw embeddable project outputs. */
private const val METALAVA_EMBED_CATEGORY = "metalava-embed"

/** Name of the task that merges `META-INF/services/` files into a host project's JAR. */
private const val MERGE_SERVICE_FILES_TASK = "mergeServiceFiles"

/**
 * Local resolvable classpath configurations that receive embedded project dependencies (including
 * `java-test-fixtures` classpaths when `JavaTestFixturesPlugin` is applied in any order).
 */
private val LOCAL_EMBED_CLASSPATH_CONFIGS =
    setOf(
        "compileClasspath",
        "runtimeClasspath",
        "testCompileClasspath",
        "testRuntimeClasspath",
        "testFixturesCompileClasspath",
        "testFixturesRuntimeClasspath",
    )

/**
 * Configures core Gradle project embedding support (`embedApi` and `embedImplementation`) on
 * [project].
 *
 * Unpublished subprojects (those without `maven-publish`) expose an `embedElements` variant
 * containing only their own compiled classes and processed resources. Host projects declare
 * `embedApi(project(...))` (on `java-library` projects) or `embedImplementation(project(...))` to
 * merge the transitive embedded cluster's classes, Kotlin `.kotlin_module` metadata, resources, and
 * deterministically merged `META-INF/services/` entries into the host's `jar`.
 */
internal fun configureEmbedding(project: Project) {
    project.plugins.withType(JavaPlugin::class.java) {
        val embedImplementation =
            project.configurations.dependencyScope(EMBED_IMPLEMENTATION_CONFIGURATION)

        // Use a dedicated Category attribute so embedCluster matches only unpublished subprojects'
        // embedElements variants and stops cleanly at regular api/implementation boundaries.
        val embedCluster =
            project.configurations.resolvable(EMBED_CLUSTER_CONFIGURATION) { config ->
                config.extendsFrom(embedImplementation.get())
                config.attributes { attrs ->
                    attrs.attribute(
                        Category.CATEGORY_ATTRIBUTE,
                        project.objects.named(METALAVA_EMBED_CATEGORY),
                    )
                }
            }

        // Make embedded implementation projects visible to the host's own compilation, runtime,
        // unit tests, and (when java-test-fixtures is applied, in any order) testFixtures without
        // leaking them into outgoing published variants. Using configurations.matching avoids
        // nesting plugins.withType callbacks inside plugins.withType (forbidden in Gradle 9).
        project.configurations
            .matching { it.name in LOCAL_EMBED_CLASSPATH_CONFIGS }
            .configureEach { config -> config.extendsFrom(embedImplementation.get()) }

        // Wait until the subproject's build script finishes evaluating so maven-publish and any
        // embedApi/embedImplementation dependencies have been declared before wiring variants and
        // merged packaging tasks.
        project.afterEvaluate {
            val embedImpl = embedImplementation.get()
            val embedApi = project.configurations.findByName(EMBED_API_CONFIGURATION)
            val isPublished = project.plugins.hasPlugin(MavenPublishPlugin::class.java)

            // Expose raw compiled outputs via embedElements only on unpublished subprojects so
            // attempting to embed an already-published artifact fails fast.
            if (!isPublished) {
                registerEmbedElementsVariant(project, embedApi, embedImpl)
            }

            val hasEmbedApi = embedApi != null && embedApi.dependencies.isNotEmpty()
            val hasEmbedImpl = embedImpl.dependencies.isNotEmpty()
            if (!hasEmbedApi && !hasEmbedImpl) {
                return@afterEvaluate
            }

            validateEmbedDeclarations(project, embedApi, embedImpl)

            // Remove unmerged secondary variants so downstream compile and runtime consumers
            // (including ArtifactView consumers such as com.android.lint) resolve the merged JAR
            // rather than unmerged host output directories.
            if (hasEmbedApi) {
                project.configurations.named("apiElements").configure { apiElements ->
                    apiElements.outgoing.variants.removeIf { it.name == "classes" }
                }
            }
            project.configurations.named("runtimeElements").configure { runtimeElements ->
                runtimeElements.outgoing.variants.removeIf {
                    it.name == "classes" || it.name == "resources"
                }
            }

            configureMergedJar(project, embedCluster.get())
        }
    }

    // JavaLibraryPlugin always applies JavaPlugin first, so embedImplementation and embedCluster
    // are already registered when this callback runs.
    project.plugins.withType(JavaLibraryPlugin::class.java) {
        val embedApi = project.configurations.dependencyScope(EMBED_API_CONFIGURATION)
        val embedCluster = project.configurations.named(EMBED_CLUSTER_CONFIGURATION)

        embedCluster.configure { it.extendsFrom(embedApi.get()) }

        project.configurations
            .matching { it.name in LOCAL_EMBED_CLASSPATH_CONFIGS }
            .configureEach { config -> config.extendsFrom(embedApi.get()) }
    }
}

/**
 * Registers the consumable `embedElements` variant on an unpublished subproject, exposing only its
 * own `main` source set outputs (never merged JARs) and extending only `embedApi` /
 * `embedImplementation` so transitive embedding stops at normal `api` / `implementation` edges.
 */
private fun registerEmbedElementsVariant(
    project: Project,
    embedApi: Configuration?,
    embedImplementation: Configuration,
) {
    // Extend only embedImplementation and embedApi (never api/implementation) so transitive
    // embedCluster resolution traverses nested embedded subprojects and stops at regular edges.
    val embedElements =
        project.configurations.consumable(EMBED_ELEMENTS_CONFIGURATION) { config ->
            config.extendsFrom(embedImplementation)
            if (embedApi != null) {
                config.extendsFrom(embedApi)
            }
            config.attributes { attrs ->
                attrs.attribute(
                    Category.CATEGORY_ATTRIBUTE,
                    project.objects.named(METALAVA_EMBED_CATEGORY),
                )
            }
        }

    // Expose raw compiled class/resource output directories rather than the jar task to avoid
    // zip/unzip overhead and circular JAR dependencies; builtBy preserves compile task wiring.
    val mainOutput =
        project.extensions.getByType(SourceSetContainer::class.java).named("main").get().output
    embedElements.configure { config ->
        for (outputDir in mainOutput.files) {
            config.outgoing.artifact(outputDir) { artifact -> artifact.builtBy(mainOutput) }
        }
    }
}

/**
 * Fails fast if any dependency declared in [embedApi] or [embedImplementation] is not a local
 * [ProjectDependency].
 */
private fun validateEmbedDeclarations(
    project: Project,
    embedApi: Configuration?,
    embedImplementation: Configuration,
) {
    val allEmbedded = buildList {
        if (embedApi != null) {
            addAll(embedApi.dependencies)
        }
        addAll(embedImplementation.dependencies)
    }
    for (dep in allEmbedded) {
        if (dep !is ProjectDependency) {
            val coords = listOfNotNull(dep.group, dep.name, dep.version).joinToString(":")
            throw GradleException(
                "Cannot embed external dependency '$coords' in '${project.path}'. Only " +
                    "unpublished local Metalava projects may be added to " +
                    "'$EMBED_API_CONFIGURATION' or '$EMBED_IMPLEMENTATION_CONFIGURATION'."
            )
        }
    }
}

/**
 * Resolves [embedCluster] and returns the set of Gradle project paths in the transitive embedded
 * cluster, failing fast with an actionable error if any target lacks `embedElements`.
 */
private fun resolveEmbeddedClusterPaths(
    project: Project,
    embedCluster: Configuration,
): Set<String> {
    // Inspect resolutionResult.allDependencies explicitly because Gradle's resolutionResult is
    // lenient by default; this lets us surface actionable errors distinguishing published projects
    // from external modules.
    val resolutionResult = embedCluster.incoming.resolutionResult
    for (dep in resolutionResult.allDependencies) {
        if (dep is UnresolvedDependencyResult) {
            val requested = dep.requested
            if (requested is ProjectComponentSelector) {
                throw GradleException(
                    "Cannot embed project '${requested.projectPath}' in '${project.path}': it " +
                        "does not expose an '$EMBED_ELEMENTS_CONFIGURATION' variant (it is " +
                        "likely already published with 'maven-publish' or does not apply " +
                        "'metalava-build-plugin'). Use 'api' or 'implementation' instead.",
                    dep.failure,
                )
            } else {
                throw GradleException(
                    "Cannot embed '${requested.displayName}' in '${project.path}': only " +
                        "unpublished local Metalava projects may be embedded.",
                    dep.failure,
                )
            }
        }
    }
    return collectEmbeddedProjectPaths(resolutionResult.rootComponent.get())
}

/**
 * Collects all Gradle project paths reachable from [root] in an `embedCluster` resolution result,
 * excluding [root] itself.
 */
private fun collectEmbeddedProjectPaths(root: ResolvedComponentResult): Set<String> {
    val paths = linkedSetOf<String>()
    val visited = hashSetOf<ComponentIdentifier>()
    val queue = ArrayDeque<ResolvedComponentResult>()

    // Mark root visited upfront so diamond graphs or cycles never re-visit or include the host
    // project itself in the returned embedded set.
    visited.add(root.id)
    queue.add(root)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (edge in current.dependencies) {
            // Skip dependency constraints (e.g. BOM alignments) which are not real embedded edges.
            if (edge is ResolvedDependencyResult && !edge.isConstraint) {
                val target = edge.selected
                if (visited.add(target.id)) {
                    val targetId = target.id
                    if (targetId is ProjectComponentIdentifier) {
                        paths.add(targetId.projectPath)
                    }
                    queue.add(target)
                }
            }
        }
    }
    return paths
}

/**
 * Registers [MergeServiceFilesTask] and updates the host `jar` task to merge all embedded project
 * outputs and deterministically merged `META-INF/services/` entries while failing on any other
 * duplicate entry.
 */
private fun configureMergedJar(project: Project, embedCluster: Configuration) {
    val mainOutput =
        project.extensions.getByType(SourceSetContainer::class.java).named("main").get().output

    // Validate embedCluster before Gradle attempts default artifact resolution so :jar and
    // :mergeServiceFiles emit an actionable error message when an embedded project lacks
    // embedElements (e.g. applies maven-publish).
    val validatedEmbedFiles =
        project.provider {
            resolveEmbeddedClusterPaths(project, embedCluster)
            embedCluster.incoming.files
        }
    val validatedEmbedServiceFiles =
        project.provider {
            resolveEmbeddedClusterPaths(project, embedCluster)
            embedCluster.incoming.files.asFileTree.matching { it.include("META-INF/services/**") }
        }

    val mergeServiceFilesTask =
        project.tasks.register(MERGE_SERVICE_FILES_TASK, MergeServiceFilesTask::class.java) { task
            ->
            task.description = "Merges META-INF/services files from host and embedded projects."
            task.serviceFiles.from(
                mainOutput.asFileTree.matching { it.include("META-INF/services/**") }
            )
            task.serviceFiles.from(validatedEmbedServiceFiles)
            task.outputDirectory.set(project.layout.buildDirectory.dir("generated/merged-services"))
        }

    val mergedServicesNormalizedPath =
        mergeServiceFilesTask.flatMap { task ->
            task.outputDirectory.map { it.asFile.toPath().toAbsolutePath().normalize().toString() }
        }

    project.tasks.named("jar", Jar::class.java).configure { jar ->
        // Produce byte-for-byte reproducible archives and fail immediately on any accidental class
        // or resource collision between the host and embedded subprojects (META-INF/services/ and
        // META-INF/MANIFEST.MF are explicitly handled below).
        jar.isPreserveFileTimestamps = false
        jar.isReproducibleFileOrder = true
        jar.duplicatesStrategy = DuplicatesStrategy.FAIL

        // Exclude raw host META-INF/services/** files so only the merged outputs from
        // mergeServiceFilesTask are packaged, without affecting any other files. Normalize both
        // paths so relative segments such as "../../out" (when OUT_DIR is unset) never cause
        // Path.startsWith to falsely exclude the merged service files.
        jar.eachFile { details ->
            if (
                details.path.startsWith(MergeServiceFilesTask.SERVICES_PATH_PREFIX) &&
                    !details.file
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                        .startsWith(Path.of(mergedServicesNormalizedPath.get()))
            ) {
                details.exclude()
            }
        }

        // Include raw compiled classes and resources from all embedded projects, excluding
        // embedded manifests and raw service files.
        jar.from(validatedEmbedFiles) { spec ->
            spec.exclude("META-INF/MANIFEST.MF")
            spec.exclude("META-INF/services/**")
        }

        // Include deterministically merged META-INF/services/** files.
        jar.from(mergeServiceFilesTask)
    }
}
