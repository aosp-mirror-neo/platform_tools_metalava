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

import com.android.tools.metalava.buildinfo.setBuildInfoAttributes
import java.nio.file.Path
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.distribution.DistributionContainer
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.application.CreateStartScripts
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.named

/** Declarable configuration for embedding a local subproject's API into a `java-library` host. */
internal const val EMBED_API_CONFIGURATION = "embedApi"

/** Declarable configuration for embedding a local subproject as an implementation detail. */
internal const val EMBED_IMPLEMENTATION_CONFIGURATION = "embedImplementation"

/** Consumable configuration on unpublished projects exposing raw outputs for embedding. */
internal const val EMBED_ELEMENTS_CONFIGURATION = "embedElements"

/** Resolvable configuration traversing only the transitive embedded project cluster. */
private const val EMBED_CLUSTER_CONFIGURATION = "embedCluster"

/** Resolvable configuration capturing `JAVA_API` dependencies of `embedApi` projects. */
private const val EMBED_API_RESOLVABLE_CONFIGURATION = "embedApiResolvable"

/** Resolvable configuration capturing `JAVA_RUNTIME` dependencies of embedded projects. */
private const val EMBED_RUNTIME_RESOLVABLE_CONFIGURATION = "embedRuntimeResolvable"

/**
 * Internal declarable configuration extended by `apiElements` to hold promoted API boundary
 * dependencies (Gradle 9 forbids declaring dependencies directly on consumable `apiElements`).
 */
private const val EMBED_PROMOTED_API_CONFIGURATION = "embedPromotedApi"

/**
 * Internal declarable configuration extended by `runtimeElements` to hold promoted runtime boundary
 * dependencies (Gradle 9 forbids declaring dependencies directly on consumable `runtimeElements`).
 */
private const val EMBED_PROMOTED_RUNTIME_CONFIGURATION = "embedPromotedRuntime"

/** Gradle [Category] attribute value identifying raw embeddable project outputs. */
private const val METALAVA_EMBED_CATEGORY = "metalava-embed"

/** Name of the task that merges `META-INF/services/` files into a host project's JAR. */
private const val MERGE_SERVICE_FILES_TASK = "mergeServiceFiles"

/**
 * Local classpath configurations on a host project that must see embedded subprojects directly
 * without leaking them into outgoing published variants (`apiElements`, `runtimeElements`,
 * `testFixturesApiElements`, `testFixturesRuntimeElements`).
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
 * Configures Gradle project embedding support (`embedApi` and `embedImplementation`) on [project].
 *
 * Unpublished subprojects (those without `maven-publish`) expose an `embedElements` variant
 * containing only their own compiled classes and processed resources. Host projects declare
 * `embedApi(project(...))` (on `java-library` projects) or `embedImplementation(project(...))` to:
 * - Merge the transitive embedded cluster's classes, Kotlin `.kotlin_module` metadata, resources,
 *   and deterministically merged `META-INF/services/` entries into the host's `jar`.
 * - Exclude embedded projects from published `.pom`, `.module`, and `build_info.txt` metadata while
 *   promoting all non-embedded boundary dependencies (including `platform(...)` BOMs) with the
 *   appropriate scope. Note: because Gradle's `ResolutionResult` does not expose per-dependency
 *   `ExcludeRule`s on transitive edges, any `exclude(...)` rule for an embedded project's external
 *   boundary dependency must be declared on the host project (where it is preserved across
 *   promotion).
 * - Filter embedded subproject JARs out of `application` distributions (`installDist`, `distZip`,
 *   `distTar`, and `startScripts`).
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

        val embedRuntimeResolvable =
            project.configurations.resolvable(EMBED_RUNTIME_RESOLVABLE_CONFIGURATION) { config ->
                config.extendsFrom(embedImplementation.get())
                configureJvmResolutionAttributes(project, config, Usage.JAVA_RUNTIME)
            }

        // Make embedded implementation projects visible to the host's own compilation, runtime,
        // unit tests, and (when java-test-fixtures is applied, in any order) testFixtures without
        // leaking them into outgoing published variants. Using configurations.matching avoids
        // nesting plugins.withType callbacks inside plugins.withType (forbidden in Gradle 9).
        project.configurations
            .matching { it.name in LOCAL_EMBED_CLASSPATH_CONFIGS }
            .configureEach { config -> config.extendsFrom(embedImplementation.get()) }

        // Promote non-embedded runtime boundary dependencies of all embedded projects into
        // runtimeElements via a declarable dependencyScope (Gradle 9 forbids adding dependencies
        // directly to consumable configurations such as runtimeElements).
        val embedPromotedRuntime =
            project.configurations.dependencyScope(EMBED_PROMOTED_RUNTIME_CONFIGURATION)
        val runtimeElements = project.configurations.named("runtimeElements")
        runtimeElements.configure { it.extendsFrom(embedPromotedRuntime.get()) }
        embedPromotedRuntime.configure { promoted ->
            promoted.withDependencies { targetDeps ->
                val embedApi = project.configurations.findByName(EMBED_API_CONFIGURATION)
                val hasEmbedApi = embedApi?.dependencies?.isNotEmpty() == true
                val hasEmbedImpl = embedImplementation.get().dependencies.isNotEmpty()
                if (!hasEmbedApi && !hasEmbedImpl) {
                    return@withDependencies
                }
                promoteBoundaryDependencies(
                    project = project,
                    embedApi = embedApi,
                    embedImplementation = embedImplementation.get(),
                    embedCluster = embedCluster.get(),
                    resolvableConfig = embedRuntimeResolvable.get(),
                    targetOutgoingConfig = runtimeElements.get(),
                    promotedScope = promoted,
                    targetDeps = targetDeps,
                )
            }
        }

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
            runtimeElements.configure { runtime ->
                runtime.outgoing.variants.removeIf {
                    it.name == "classes" || it.name == "resources"
                }
            }

            configureMergedJar(project, embedCluster.get())

            if (project.plugins.hasPlugin(ApplicationPlugin::class.java)) {
                configureApplicationDistribution(project, embedCluster.get())
            }
        }
    }

    // JavaLibraryPlugin always applies JavaPlugin first, so embedImplementation, embedCluster,
    // and embedRuntimeResolvable are already registered when this callback runs.
    project.plugins.withType(JavaLibraryPlugin::class.java) {
        val embedApi = project.configurations.dependencyScope(EMBED_API_CONFIGURATION)
        val embedImplementation = project.configurations.named(EMBED_IMPLEMENTATION_CONFIGURATION)
        val embedCluster = project.configurations.named(EMBED_CLUSTER_CONFIGURATION)
        val embedRuntimeResolvable =
            project.configurations.named(EMBED_RUNTIME_RESOLVABLE_CONFIGURATION)

        val embedApiResolvable =
            project.configurations.resolvable(EMBED_API_RESOLVABLE_CONFIGURATION) { config ->
                config.extendsFrom(embedApi.get())
                configureJvmResolutionAttributes(project, config, Usage.JAVA_API)
            }

        embedCluster.configure { it.extendsFrom(embedApi.get()) }
        embedRuntimeResolvable.configure { it.extendsFrom(embedApi.get()) }

        project.configurations
            .matching { it.name in LOCAL_EMBED_CLASSPATH_CONFIGS }
            .configureEach { config -> config.extendsFrom(embedApi.get()) }

        // Promote non-embedded API boundary dependencies of embedApi projects into apiElements
        // via a declarable dependencyScope.
        val embedPromotedApi =
            project.configurations.dependencyScope(EMBED_PROMOTED_API_CONFIGURATION)
        val apiElements = project.configurations.named("apiElements")
        apiElements.configure { it.extendsFrom(embedPromotedApi.get()) }
        embedPromotedApi.configure { promoted ->
            promoted.withDependencies { targetDeps ->
                if (embedApi.get().dependencies.isEmpty()) {
                    return@withDependencies
                }
                promoteBoundaryDependencies(
                    project = project,
                    embedApi = embedApi.get(),
                    embedImplementation = embedImplementation.get(),
                    embedCluster = embedCluster.get(),
                    resolvableConfig = embedApiResolvable.get(),
                    targetOutgoingConfig = apiElements.get(),
                    promotedScope = promoted,
                    targetDeps = targetDeps,
                )
            }
        }
    }
}

/**
 * Configures standard JVM library resolution attributes on [config] for [usageName] (`JAVA_API` or
 * `JAVA_RUNTIME`) so Kotlin Multiplatform and Guava dependencies disambiguate cleanly.
 */
internal fun configureJvmResolutionAttributes(
    project: Project,
    config: Configuration,
    usageName: String,
    targetJvmVersion: Int =
        project.extensions
            .getByType(JavaPluginExtension::class.java)
            .targetCompatibility
            .majorVersion
            .toInt(),
) {
    config.attributes { attrs ->
        attrs.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(usageName))
        attrs.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.LIBRARY))
        attrs.attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling.EXTERNAL))
        attrs.attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            project.objects.named(LibraryElements.JAR),
        )
        attrs.attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            project.objects.named(TargetJvmEnvironment.STANDARD_JVM),
        )
        attrs.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, targetJvmVersion)
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
 * Promotes non-embedded boundary dependencies of the embedded cluster from [resolvableConfig] into
 * [targetDeps] of [targetOutgoingConfig].
 *
 * Dependencies inside the embedded cluster are traversed recursively and never promoted; external
 * modules and published sibling projects at the boundary are added once (unless already declared on
 * [targetOutgoingConfig] or its parent configurations, preserving explicit host exclusions). On
 * published hosts, any boundary project that does not apply `maven-publish` fails the build fast.
 */
private fun promoteBoundaryDependencies(
    project: Project,
    embedApi: Configuration?,
    embedImplementation: Configuration,
    embedCluster: Configuration,
    resolvableConfig: Configuration,
    targetOutgoingConfig: Configuration,
    promotedScope: Configuration,
    targetDeps: DependencySet,
) {
    validateEmbedDeclarations(project, embedApi, embedImplementation)

    val embeddedPaths = resolveEmbeddedClusterPaths(project, embedCluster)
    if (embeddedPaths.isEmpty()) {
        return
    }

    // Resolve the JVM API/runtime graph separately from embedCluster so traversal crosses normal
    // api/implementation edges out of the embedded cluster to discover external modules and
    // published sibling projects at the boundary.
    val resolutionResult = resolvableConfig.incoming.resolutionResult
    for (dep in resolutionResult.allDependencies) {
        if (dep is UnresolvedDependencyResult) {
            throw GradleException(
                "Failed to resolve embedded dependencies for '${project.path}' in " +
                    "'${resolvableConfig.name}': ${dep.requested.displayName}",
                dep.failure,
            )
        }
    }

    // Gather existing project and module coordinates declared on sibling parent configurations of
    // targetOutgoingConfig (such as api, implementation, runtimeOnly) and targetDeps, excluding
    // promotedScope itself to avoid re-entering its withDependencies callback. Track declared
    // versions so we can emit a dependency constraint when an embedded project requires a different
    // version without duplicating the host dependency or stripping host excludes.
    val existingProjectPaths = hashSetOf<String>()
    val existingModuleVersions = hashMapOf<Pair<String?, String>, MutableSet<String?>>()
    val inheritedConfigs =
        collectInheritedConfigurations(targetOutgoingConfig, exclude = promotedScope)
    for (dep in inheritedConfigs.flatMap { it.allDependencies } + targetDeps) {
        if (dep is ProjectDependency) {
            existingProjectPaths.add(dep.path)
        } else {
            existingModuleVersions
                .getOrPut(dep.group to dep.name) { linkedSetOf() }
                .add(dep.version)
        }
    }

    // When promoting into apiElements, collect per-module exclude rules from the host's
    // implementation configuration so a host declaring implementation("g:m:v") { exclude(...) }
    // alongside embedApi(project(...)) does not leak excluded transitives into apiElements or
    // drop <exclusions> from the merged compile-scoped POM entry.
    val hostImplExcludesByModule = linkedMapOf<Pair<String?, String>, MutableSet<ExcludeRule>>()
    if (targetOutgoingConfig.name == "apiElements") {
        val hostImpl = project.configurations.findByName("implementation")
        if (hostImpl != null) {
            for (dep in hostImpl.allDependencies) {
                if (dep is ModuleDependency && dep.excludeRules.isNotEmpty()) {
                    hostImplExcludesByModule
                        .getOrPut(dep.group to dep.name) { linkedSetOf() }
                        .addAll(dep.excludeRules)
                }
            }
        }
    }

    val isPublishedHost = project.plugins.hasPlugin(MavenPublishPlugin::class.java)
    val publishedCheckCache = hashMapOf<String, Boolean>()

    val root = resolutionResult.rootComponent.get()
    val visited = hashSetOf<ComponentIdentifier>()
    val promotedIds = hashSetOf<ComponentIdentifier>()
    val queue = ArrayDeque<Pair<ResolvedComponentResult, String>>()

    // Seed the walk exclusively with direct edges entering embeddedPaths so we only inspect
    // dependencies originating inside the embedded cluster, never the host's own direct deps.
    for (edge in root.dependencies) {
        if (edge is ResolvedDependencyResult && !edge.isConstraint) {
            val target = edge.selected
            val targetId = target.id
            if (targetId is ProjectComponentIdentifier && targetId.projectPath in embeddedPaths) {
                if (visited.add(targetId)) {
                    queue.add(target to targetId.projectPath)
                }
            }
        }
    }

    while (queue.isNotEmpty()) {
        val (node, currentEmbeddedPath) = queue.removeFirst()
        for (edge in node.dependencies) {
            if (edge !is ResolvedDependencyResult || edge.isConstraint) {
                continue
            }
            val target = edge.selected
            val targetId = target.id
            if (targetId == root.id) {
                continue
            }

            // Stay inside the embedded cluster without promoting the project itself, since its
            // compiled outputs are merged into the host JAR while its outgoing edges may cross the
            // boundary.
            if (targetId is ProjectComponentIdentifier && targetId.projectPath in embeddedPaths) {
                if (visited.add(targetId)) {
                    queue.add(target to targetId.projectPath)
                }
                continue
            }

            // Boundary dependency: promote once and stop traversal because the boundary artifact's
            // own metadata already declares its transitive dependencies.
            if (!promotedIds.add(targetId)) {
                continue
            }

            when (targetId) {
                is ProjectComponentIdentifier -> {
                    // Only enforce the unpublished-sibling guard on published hosts; an unpublished
                    // intermediate project may legitimately depend on a sibling that an outer
                    // published host embeds.
                    if (isPublishedHost) {
                        val isTargetPublished =
                            publishedCheckCache.getOrPut(targetId.projectPath) {
                                isPublishedMetalavaProject(project, targetId.projectPath)
                            }
                        if (!isTargetPublished) {
                            throw GradleException(
                                "Embedded project '$currentEmbeddedPath' depends on unpublished " +
                                    "project '${targetId.projectPath}', which is not embedded by " +
                                    "'${project.path}'. Add " +
                                    "$EMBED_IMPLEMENTATION_CONFIGURATION(project(\"${targetId.projectPath}\")) " +
                                    "(or $EMBED_API_CONFIGURATION) to '${project.path}' or apply " +
                                    "'maven-publish' to '${targetId.projectPath}'."
                            )
                        }
                    }

                    // Skip coordinates already declared directly on the host to preserve any
                    // explicit host dependency configuration or exclude rules.
                    if (targetId.projectPath !in existingProjectPaths) {
                        targetDeps.add(
                            project.dependencies.project(mapOf("path" to targetId.projectPath))
                        )
                        existingProjectPaths.add(targetId.projectPath)
                    }
                }
                is ModuleComponentIdentifier -> {
                    // Preserve explicit host module declarations (including exclude(...) rules)
                    // rather than adding an unexcluded duplicate that would re-introduce excluded
                    // transitive artifacts. Preserve platform/enforced-platform categories for BOMs
                    // so downstream consumers do not attempt to resolve a JAR for a POM-only BOM.
                    val key = targetId.group to targetId.module
                    val coord = "${targetId.group}:${targetId.module}:${targetId.version}"
                    val category = edge.resolvedCategory()
                    val isPlatform =
                        category == Category.REGULAR_PLATFORM ||
                            category == Category.ENFORCED_PLATFORM
                    val declaredVersions = existingModuleVersions[key]
                    if (declaredVersions == null) {
                        val promotedDep =
                            when (category) {
                                Category.REGULAR_PLATFORM -> project.dependencies.platform(coord)
                                Category.ENFORCED_PLATFORM ->
                                    project.dependencies.enforcedPlatform(coord)
                                else -> project.dependencies.create(coord)
                            }
                        if (!isPlatform && promotedDep is ModuleDependency) {
                            for (rule in hostImplExcludesByModule[key].orEmpty()) {
                                promotedDep.exclude(group = rule.group, module = rule.module)
                            }
                        }
                        targetDeps.add(promotedDep)
                        existingModuleVersions[key] = linkedSetOf(targetId.version)
                    } else if (!isPlatform && declaredVersions != setOf(targetId.version)) {
                        // When the host already declares this module at a different version, add a
                        // dependency constraint on promotedScope so outgoing metadata reflects the
                        // embedded module's required version without duplicating the dependency or
                        // stripping host excludes.
                        promotedScope.dependencyConstraints.add(
                            project.dependencies.constraints.create(coord)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Returns all ancestor configurations extended by [configuration] without including [configuration]
 * itself or [exclude].
 */
private fun collectInheritedConfigurations(
    configuration: Configuration,
    exclude: Configuration,
): Set<Configuration> {
    val result = linkedSetOf<Configuration>()
    val queue = ArrayDeque(configuration.extendsFrom)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (current != exclude && result.add(current)) {
            queue.addAll(current.extendsFrom)
        }
    }
    return result
}

/**
 * Checks whether [projectPath] is a published Metalava project in a Project-Isolation-safe way by
 * resolving its `buildInfoProvider` (`Category = build-info`) variant, which is registered only
 * when `maven-publish` is applied.
 */
private fun isPublishedMetalavaProject(project: Project, projectPath: String): Boolean {
    // Probe the target project's build-info variant via variant-aware resolution rather than
    // looking up Project instances on rootProject so this stays Project-Isolation-safe. Verify the
    // selected variant explicitly carries org.gradle.category = "build-info" so an attribute-less
    // legacy consumable configuration (such as modelTestSuite) cannot cause a false positive.
    val checkConfig =
        project.configurations.detachedConfiguration(
            project.dependencies.project(mapOf("path" to projectPath))
        )
    checkConfig.isTransitive = false
    checkConfig.attributes.setBuildInfoAttributes(project)
    val deps = checkConfig.incoming.resolutionResult.allDependencies
    return deps.isNotEmpty() &&
        deps.all { dep ->
            dep is ResolvedDependencyResult && dep.resolvedCategory() == "build-info"
        }
}

/**
 * Returns the string value of `org.gradle.category` on this resolved dependency's selected variant,
 * or `null` if the selected variant omits the category attribute.
 */
private fun ResolvedDependencyResult.resolvedCategory(): String? {
    val attrs = resolvedVariant.attributes
    val categoryAttr = attrs.keySet().firstOrNull { it.name == Category.CATEGORY_ATTRIBUTE.name }
    return categoryAttr?.let { attrs.getAttribute(it)?.toString() }
}

/**
 * Registers [MergeServiceFilesTask] and updates the host `jar` task to merge all embedded project
 * outputs and deterministically merged `META-INF/services/` entries while failing on any other
 * duplicate entry.
 */
private fun configureMergedJar(project: Project, embedCluster: Configuration) {
    val mainOutput =
        project.extensions.getByType(SourceSetContainer::class.java).named("main").get().output

    // Validate embedCluster before Gradle attempts default artifact resolution so :jar,
    // :mergeServiceFiles, and :installDist emit the same actionable error message as :publish
    // when an embedded project lacks embedElements (e.g. applies maven-publish).
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

/**
 * Updates `application` plugin distribution tasks (`startScripts`, `installDist`, `distZip`,
 * `distTar`) to exclude embedded subproject JARs from `lib/` and the launcher classpath, because
 * their classes and resources are already merged into the host JAR.
 */
private fun configureApplicationDistribution(project: Project, embedCluster: Configuration) {
    val runtimeClasspath = project.configurations.named("runtimeClasspath").get()

    // Route through resolveEmbeddedClusterPaths so :startScripts and :installDist emit the same
    // actionable error message as :jar and :publish when an embedded target lacks embedElements.
    val embeddedPathsProvider =
        project.provider { resolveEmbeddedClusterPaths(project, embedCluster) }

    // Partition runtimeClasspath into two complementary views: startScripts takes an explicit
    // non-embedded classpath (jarTask + distRuntimeFiles), whereas Gradle's application
    // distribution CopySpec includes runtimeClasspath implicitly and needs the exact embedded JAR
    // paths to exclude them via eachFile.
    val distRuntimeFiles =
        runtimeClasspath.incoming
            .artifactView { view ->
                view.componentFilter { id ->
                    !isEmbeddedProjectComponent(id, embeddedPathsProvider)
                }
            }
            .files

    val embeddedRuntimeJarFiles =
        runtimeClasspath.incoming
            .artifactView { view ->
                view.componentFilter { id -> isEmbeddedProjectComponent(id, embeddedPathsProvider) }
            }
            .files

    // Store normalized absolute path strings (Configuration-Cache-serializable) so relative build
    // directories such as "../../out" (when OUT_DIR is unset) match details.file accurately.
    val embeddedJarNormalizedPaths =
        embeddedRuntimeJarFiles.elements.map { locations ->
            locations.map { it.asFile.toPath().toAbsolutePath().normalize().toString() }.toSet()
        }

    val jarTask = project.tasks.named("jar")
    project.tasks.named("startScripts", CreateStartScripts::class.java).configure { scripts ->
        scripts.classpath = project.objects.fileCollection().from(jarTask, distRuntimeFiles)
    }

    project.extensions.getByType(DistributionContainer::class.java).named("main").configure {
        distribution ->
        distribution.contents { spec ->
            spec.eachFile { details ->
                if (
                    details.file.toPath().toAbsolutePath().normalize().toString() in
                        embeddedJarNormalizedPaths.get()
                ) {
                    details.exclude()
                }
            }
        }
    }
}

/**
 * Returns true if [id] is a local project component belonging to the embedded project paths in
 * [embeddedPathsProvider].
 */
private fun isEmbeddedProjectComponent(
    id: ComponentIdentifier,
    embeddedPathsProvider: Provider<Set<String>>,
): Boolean = id is ProjectComponentIdentifier && id.projectPath in embeddedPathsProvider.get()
