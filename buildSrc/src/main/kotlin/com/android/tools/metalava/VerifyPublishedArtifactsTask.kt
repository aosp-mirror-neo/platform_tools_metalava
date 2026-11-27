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

import com.android.tools.metalava.buildinfo.CreateAggregateLibraryBuildInfoFileTask.Companion.CREATE_AGGREGATE_BUILD_INFO_FILES_TASK
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ServiceLoader
import java.util.spi.ToolProvider
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JvmEcosystemPlugin
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element

/** Name of the root verification task that validates published Maven artifacts and metadata. */
const val VERIFY_PUBLISHED_ARTIFACTS_TASK = "verifyPublishedArtifacts"

/**
 * Verifies the published Metalava Maven repository (`out/dist/repo/m2repository`) and aggregate
 * build-info file (`androidx_aggregate_build_info.txt`), loading all Metalava JARs strictly from
 * [m2RepositoryDirectory] without Gradle project substitutions while resolving external JARs from
 * Gradle's external-only runtime view.
 *
 * Specifically, this task checks that:
 * 1. Every published subproject exists in `m2repository` at [metalavaVersion] with valid `.jar`,
 *    `.pom`, `.module`, and matching SHA-256 checksums, while no unpublished subproject is
 *    published at [metalavaVersion].
 * 2. No unpublished (including embedded) Metalava coordinate appears in any `.pom`, `.module`, or
 *    `androidx_aggregate_build_info.txt`, and `.pom`, `.module`, and build-info metadata agree on
 *    all Metalava and external dependencies, scopes, and `isTipOfTree` flags.
 * 3. Every published artifact's `.module`-resolved external runtime dependency closure matches its
 *    expected non-embedded `runtimeClasspath` external coordinates (catching both omitted promoted
 *    dependencies and missing `exclude` rules such as `lint-checks`).
 * 4. Loading [modelSmokeCheckClasses] from the published [modelArtifactId] runtime classpath in an
 *    isolated [URLClassLoader] succeeds with code sources strictly inside [m2RepositoryDirectory].
 * 5. Executing [cliMainClass] with [cliArgs] on the published [cliArtifactId] runtime classpath
 *    succeeds with output containing [expectedCliOutputSubstring], and `ServiceLoader` discovers
 *    [expectedToolProviderName] when configured.
 */
@DisableCachingByDefault(because = "Verifies published artifacts in the shared dist directory")
abstract class VerifyPublishedArtifactsTask : DefaultTask() {

    /** Process execution service used to launch the published CLI smoke check in a fresh JVM. */
    @get:Inject abstract val execOperations: ExecOperations

    /**
     * Object factory for creating execution-time file collections without referencing `project`.
     */
    @get:Inject abstract val objects: ObjectFactory

    /** Root directory of the local published Maven repository (`.../repo/m2repository`). */
    @get:Internal abstract val m2RepositoryDirectory: DirectoryProperty

    /** Path to the generated `androidx_aggregate_build_info.txt` file. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aggregateBuildInfoFile: RegularFileProperty

    /** Expected Metalava version read from `version.properties`. */
    @get:Input abstract val metalavaVersion: Property<String>

    /** Expected Maven artifact IDs of all published (`maven-publish`) Metalava subprojects. */
    @get:Input abstract val publishedArtifacts: SetProperty<String>

    /** Coordinates (`group:name`) of unpublished subprojects that must never be published. */
    @get:Input abstract val unpublishedCoordinates: SetProperty<String>

    /** Resolved external (non-project) runtime JAR files available to published artifacts. */
    @get:InputFiles @get:Classpath abstract val externalArtifactFiles: ConfigurableFileCollection

    /**
     * Maps each external `"group:module"` coordinate to its resolved `"filename:byteLength"` keys
     * in [externalArtifactFiles].
     */
    @get:Input abstract val externalCoordinateToFileKeys: MapProperty<String, Set<String>>

    /** Maps each external `"group:module"` coordinate to its direct external dependency coords. */
    @get:Input abstract val externalTransitiveGraph: MapProperty<String, Set<String>>

    /**
     * Maps each external `"group:module"` coordinate to its resolved version (used to validate
     * KMP-redirected `.pom` artifact versions such as `tracing-wire` -> `tracing-wire-desktop`).
     */
    @get:Input abstract val externalCoordinateToVersion: MapProperty<String, String>

    /**
     * Maps each published artifact ID to the set of external `"group:module"` coordinates resolved
     * by its local `runtimeClasspath` (the ground-truth runtime graph including embedded projects'
     * transitive dependencies and `exclude` rules).
     */
    @get:Input abstract val expectedRuntimeExternalCoordinates: MapProperty<String, Set<String>>

    /** Published model artifact ID to verify via isolated classloading (optional). */
    @get:Input @get:Optional abstract val modelArtifactId: Property<String>

    /** Fully qualified class names to load and inspect from the published [modelArtifactId] JAR. */
    @get:Input abstract val modelSmokeCheckClasses: ListProperty<String>

    /** Published CLI artifact ID to verify via `javaexec` and `ToolProvider` (optional). */
    @get:Input @get:Optional abstract val cliArtifactId: Property<String>

    /** Main class to execute for the published CLI smoke check. */
    @get:Input @get:Optional abstract val cliMainClass: Property<String>

    /** Command-line arguments passed to [cliMainClass]. */
    @get:Input abstract val cliArgs: ListProperty<String>

    /** Expected substring in stdout from running [cliMainClass] with [cliArgs]. */
    @get:Input @get:Optional abstract val expectedCliOutputSubstring: Property<String>

    /** Expected `ToolProvider.name()` discoverable from the published [cliArtifactId] classpath. */
    @get:Input @get:Optional abstract val expectedToolProviderName: Property<String>

    /** Whether IntelliJ Gradle sync (`-Didea.sync.active=true`) is active for this build. */
    @get:Input abstract val ideaSyncActive: Property<Boolean>

    /** Runs all metadata, dependency-resolution, classloading, and CLI smoke checks. */
    @TaskAction
    fun verify() {
        if (ideaSyncActive.get()) {
            throw GradleException(
                "Refusing to run publishing verification task '$path' while " +
                    "'idea.sync.active=true' because IDE sync alters embedded dependency metadata."
            )
        }

        val m2Repo = m2RepositoryDirectory.get().asFile
        val version = metalavaVersion.get()
        val published = publishedArtifacts.get()
        val unpublished = unpublishedCoordinates.get()
        val coordToFileKeys = externalCoordinateToFileKeys.get()
        val externalGraph = externalTransitiveGraph.get()
        val externalCoordToVersion = externalCoordinateToVersion.get()
        val expectedExternalByArtifact = expectedRuntimeExternalCoordinates.get()

        // Validate optional smoke-check property pairs upfront so partial configuration (for
        // example, modelSmokeCheckClasses without modelArtifactId) fails fast instead of silently
        // skipping verification.
        val modelId = modelArtifactId.orNull?.takeIf { it.isNotEmpty() }
        val classesToLoad = modelSmokeCheckClasses.get()
        if (classesToLoad.isNotEmpty() && modelId == null) {
            throw GradleException("modelSmokeCheckClasses was configured without modelArtifactId.")
        }
        if (modelId != null && classesToLoad.isEmpty()) {
            throw GradleException(
                "modelArtifactId '$modelId' was configured without modelSmokeCheckClasses."
            )
        }

        val cliId = cliArtifactId.orNull?.takeIf { it.isNotEmpty() }
        val toolProviderName = expectedToolProviderName.orNull?.takeIf { it.isNotEmpty() }
        val mainClass = cliMainClass.orNull?.takeIf { it.isNotEmpty() }
        val expectedOutput = expectedCliOutputSubstring.orNull?.takeIf { it.isNotEmpty() }
        if ((toolProviderName != null || mainClass != null) && cliId == null) {
            throw GradleException(
                "CLI smoke check (cliMainClass / expectedToolProviderName) was configured " +
                    "without cliArtifactId."
            )
        }
        if (cliId != null && toolProviderName == null && mainClass == null) {
            throw GradleException(
                "cliArtifactId '$cliId' was configured without cliMainClass or " +
                    "expectedToolProviderName."
            )
        }
        if (expectedOutput != null && mainClass == null) {
            throw GradleException("expectedCliOutputSubstring was configured without cliMainClass.")
        }

        if (!m2Repo.isDirectory) {
            throw GradleException(
                "Published Maven repository directory does not exist: ${m2Repo.absolutePath}"
            )
        }
        if (published.isEmpty()) {
            throw GradleException(
                "No published Metalava artifacts were registered for verification."
            )
        }

        // Index external JAR files by "filename:length" so lookups are independent of absolute
        // checkout or cache paths.
        val externalFilesByKey = hashMapOf<String, File>()
        for (file in externalArtifactFiles.files) {
            if (file.isFile) {
                externalFilesByKey["${file.name}:${file.length()}"] = file
            }
        }

        // 1. Ensure no unpublished or stale artifact was published at the current version.
        for (coord in unpublished) {
            val group = coord.substringBefore(':')
            val artifactId = coord.substringAfter(':')
            val groupPath = group.replace('.', '/')
            val unexpectedDir = File(m2Repo, "$groupPath/$artifactId/$version")
            if (unexpectedDir.exists()) {
                throw GradleException(
                    "Unpublished project '$coord' must not be present in m2repository at " +
                        "${unexpectedDir.absolutePath}"
                )
            }
        }

        // Also scan m2repository/com/android/tools/metalava/ directly so non-metalava-build-plugin
        // subprojects (which keep Gradle's default group) and stale/conditionally-excluded
        // directories left in the shared dist repo are caught at the current version.
        val metalavaGroupDir = File(m2Repo, METALAVA_GROUP_PATH)
        if (metalavaGroupDir.isDirectory) {
            for (child in metalavaGroupDir.listFiles().orEmpty().sortedBy { it.name }) {
                if (
                    child.isDirectory && child.name !in published && File(child, version).exists()
                ) {
                    throw GradleException(
                        "Unexpected Metalava artifact '$METALAVA_GROUP:${child.name}:$version' " +
                            "found in m2repository at ${File(child, version).absolutePath} " +
                            "(not in published artifacts ${published.sorted()})."
                    )
                }
            }
        }

        // 2. Parse and verify every published artifact's .jar, .pom, and .module metadata.
        // Union both maps because BOM platforms and KMP umbrella modules appear in externalGraph
        // even though they produce no JAR artifacts in coordToFileKeys.
        val knownExternalCoords = coordToFileKeys.keys + externalGraph.keys
        val parsedModules = linkedMapOf<String, ParsedModuleMetadata>()
        for (artifactId in published.sorted()) {
            val artifactDir = File(m2Repo, "$METALAVA_GROUP_PATH/$artifactId/$version")
            val jarFile = File(artifactDir, "$artifactId-$version.jar")
            val pomFile = File(artifactDir, "$artifactId-$version.pom")
            val moduleFile = File(artifactDir, "$artifactId-$version.module")

            requireExistingFile(jarFile, "published JAR for $artifactId")
            requireExistingFile(pomFile, "published POM for $artifactId")
            requireExistingFile(moduleFile, "published Gradle Module Metadata for $artifactId")

            val moduleMeta = parseModuleMetadata(moduleFile)
            verifyRequiredModuleVariants(artifactId, moduleMeta)
            verifyModuleChecksums(artifactId, version, artifactDir, moduleMeta, moduleFile)
            verifyModuleDependencies(
                artifactId = artifactId,
                moduleMeta = moduleMeta,
                expectedVersion = version,
                published = published,
                unpublished = unpublished,
                knownExternalCoords = knownExternalCoords,
            )

            val pomMeta = parsePomMetadata(pomFile)
            verifyPomDependencies(
                artifactId = artifactId,
                pomMeta = pomMeta,
                moduleMeta = moduleMeta,
                expectedVersion = version,
                published = published,
                unpublished = unpublished,
                coordToFileKeys = coordToFileKeys,
                externalGraph = externalGraph,
                externalCoordToVersion = externalCoordToVersion,
            )

            parsedModules[artifactId] = moduleMeta
        }

        // 3. Verify androidx_aggregate_build_info.txt matches published metadata.
        verifyAggregateBuildInfo(
            file = aggregateBuildInfoFile.get().asFile,
            expectedVersion = version,
            published = published,
            unpublished = unpublished,
            parsedModules = parsedModules,
        )

        // 4. Resolve each published artifact's runtime classpath strictly from m2repository
        // .module files and verify its external dependency closure matches its expected
        // non-embedded runtimeClasspath.
        val resolvedClasspaths = linkedMapOf<String, List<File>>()
        for (artifactId in published.sorted()) {
            val resolved =
                resolvePublishedRuntimeClasspath(
                    rootArtifactId = artifactId,
                    m2Repo = m2Repo,
                    version = version,
                    parsedModules = parsedModules,
                    externalGraph = externalGraph,
                    coordToFileKeys = coordToFileKeys,
                    externalFilesByKey = externalFilesByKey,
                )
            resolvedClasspaths[artifactId] = resolved.classpath

            val expectedCoords =
                expectedExternalByArtifact[artifactId]
                    ?: throw GradleException(
                        "Missing expectedRuntimeExternalCoordinates for published artifact " +
                            "'$artifactId'."
                    )
            val missing = (expectedCoords - resolved.externalCoords).sorted()
            val unexpected = (resolved.externalCoords - expectedCoords).sorted()
            if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
                throw GradleException(
                    "Published runtime external dependencies for '$artifactId' do not match " +
                        "its expected runtimeClasspath. Missing: $missing, unexpected: $unexpected."
                )
            }
        }

        // 5. Smoke-check the published model artifact in an isolated URLClassLoader.
        if (modelId != null && classesToLoad.isNotEmpty()) {
            val modelClasspath =
                resolvedClasspaths[modelId]
                    ?: throw GradleException(
                        "Configured modelArtifactId '$modelId' was not found in published " +
                            "artifacts (${published.sorted()})"
                    )
            verifyModelClasses(m2Repo, modelId, modelClasspath, classesToLoad)
        }

        // 6. Smoke-check the published CLI artifact via ToolProvider and javaexec.
        if (cliId != null) {
            val cliClasspath =
                resolvedClasspaths[cliId]
                    ?: throw GradleException(
                        "Configured cliArtifactId '$cliId' was not found in published artifacts " +
                            "(${published.sorted()})"
                    )

            if (toolProviderName != null) {
                verifyToolProvider(m2Repo, cliId, cliClasspath, toolProviderName)
            }

            if (mainClass != null) {
                verifyCliExecution(
                    cliClasspath = cliClasspath,
                    mainClass = mainClass,
                    args = cliArgs.get(),
                    expectedOutput = expectedOutput,
                )
            }
        }
    }

    /** Throws a [GradleException] if [file] does not exist as a regular file. */
    private fun requireExistingFile(file: File, description: String) {
        if (!file.isFile) {
            throw GradleException("Missing $description: ${file.absolutePath}")
        }
    }

    /**
     * Validates that a Metalava coordinate `(group, module, version)` belongs to [published] at
     * [expectedVersion] and never references [unpublished] or a nested sub-group.
     */
    private fun validateMetalavaCoordinate(
        context: String,
        group: String,
        module: String,
        version: String,
        expectedVersion: String,
        published: Set<String>,
        unpublished: Set<String>,
    ) {
        val coord = "$group:$module"
        if (group != METALAVA_GROUP || coord in unpublished) {
            throw GradleException("$context references unpublished Metalava coordinate '$coord'")
        }
        if (module !in published) {
            throw GradleException("$context references unknown Metalava artifact '$coord'")
        }
        if (version != expectedVersion) {
            throw GradleException(
                "$context references '$coord:$version' instead of expected version " +
                    "'$expectedVersion'"
            )
        }
    }

    /**
     * Verifies [moduleMeta] declares both required JVM variants (`apiElements`, `runtimeElements`).
     */
    private fun verifyRequiredModuleVariants(
        artifactId: String,
        moduleMeta: ParsedModuleMetadata,
    ) {
        for (requiredVariant in listOf("apiElements", "runtimeElements")) {
            if (requiredVariant !in moduleMeta.variants) {
                throw GradleException(
                    "Published .module for '$artifactId' is missing required variant " +
                        "'$requiredVariant'"
                )
            }
        }
    }

    /**
     * Verifies every artifact file listed in [moduleMeta] lies inside [artifactDir], exists, and
     * matches its SHA-256 hash, and confirms `$artifactId-$version.jar` itself was verified.
     */
    private fun verifyModuleChecksums(
        artifactId: String,
        version: String,
        artifactDir: File,
        moduleMeta: ParsedModuleMetadata,
        moduleFile: File,
    ) {
        val artifactDirCanonical = artifactDir.canonicalFile.toPath()

        // Deduplicate file URLs across variants (apiElements and runtimeElements reference the
        // same JAR) so each artifact is hashed only once.
        val checkedFiles = hashSetOf<String>()
        for (variant in moduleMeta.variants.values) {
            for (fileSpec in variant.files) {
                if (!checkedFiles.add(fileSpec.url)) {
                    continue
                }
                val file = File(artifactDir, fileSpec.url)
                if (!file.canonicalFile.toPath().startsWith(artifactDirCanonical)) {
                    throw GradleException(
                        "Artifact '${fileSpec.url}' in ${moduleFile.name} resolves outside " +
                            "artifact directory '$artifactDirCanonical'"
                    )
                }
                requireExistingFile(
                    file,
                    "artifact '${fileSpec.url}' referenced by ${moduleFile.name}",
                )
                val expectedSha256 =
                    fileSpec.sha256
                        ?: throw GradleException(
                            "Missing sha256 checksum for '${fileSpec.url}' in ${moduleFile.name}"
                        )
                val actualSha256 = sha256Hex(file)
                if (actualSha256 != expectedSha256) {
                    throw GradleException(
                        "SHA-256 mismatch for ${file.name} in ${moduleFile.name}: expected " +
                            "$expectedSha256, actual $actualSha256"
                    )
                }
            }
        }

        val expectedJarName = "$artifactId-$version.jar"
        if (expectedJarName !in checkedFiles) {
            throw GradleException(
                "Published .module for '$artifactId' (${moduleFile.name}) did not list " +
                    "'$expectedJarName' in its variant files"
            )
        }
    }

    /**
     * Verifies all dependencies in [moduleMeta] have non-empty versions and reference only
     * published Metalava or known external coordinates.
     */
    private fun verifyModuleDependencies(
        artifactId: String,
        moduleMeta: ParsedModuleMetadata,
        expectedVersion: String,
        published: Set<String>,
        unpublished: Set<String>,
        knownExternalCoords: Set<String>,
    ) {
        for ((variantName, variant) in moduleMeta.variants) {
            for (dep in variant.dependencies) {
                if (isMetalavaGroup(dep.group)) {
                    validateMetalavaCoordinate(
                        context = "Published module '$artifactId' ($variantName)",
                        group = dep.group,
                        module = dep.module,
                        version = dep.version,
                        expectedVersion = expectedVersion,
                        published = published,
                        unpublished = unpublished,
                    )
                } else {
                    val coord = "${dep.group}:${dep.module}"
                    if (dep.version.isEmpty()) {
                        throw GradleException(
                            "Published module '$artifactId' ($variantName) declares external " +
                                "dependency '$coord' without a version."
                        )
                    }
                    if (coord !in knownExternalCoords) {
                        throw GradleException(
                            "Published module '$artifactId' ($variantName) declares external " +
                                "dependency '$coord:${dep.version}' which was not found in the " +
                                "resolved external runtime graph."
                        )
                    }
                }
            }
        }
    }

    /**
     * Verifies `.pom` `<dependencies>` and `<dependencyManagement>` omit unpublished Metalava
     * modules and match `.module`'s `apiElements` (`compile`), `runtimeElements` (`runtime`),
     * `platform`/`enforced-platform` BOM imports, versions, and `<exclusions>`.
     */
    private fun verifyPomDependencies(
        artifactId: String,
        pomMeta: ParsedPomMetadata,
        moduleMeta: ParsedModuleMetadata,
        expectedVersion: String,
        published: Set<String>,
        unpublished: Set<String>,
        coordToFileKeys: Map<String, Set<String>>,
        externalGraph: Map<String, Set<String>>,
        externalCoordToVersion: Map<String, String>,
    ) {
        // Always validate Metalava coordinates in <dependencyManagement> too so an unpublished
        // Metalava BOM import or constraint cannot slip through undetected.
        for (dep in pomMeta.managedDependencies) {
            if (isMetalavaGroup(dep.group)) {
                validateMetalavaCoordinate(
                    context = "Published POM <dependencyManagement> for '$artifactId'",
                    group = dep.group,
                    module = dep.module,
                    version = dep.version,
                    expectedVersion = expectedVersion,
                    published = published,
                    unpublished = unpublished,
                )
            }
        }
        val managedByCoord = pomMeta.managedDependencies.groupBy { "${it.group}:${it.module}" }

        val pomByCoord = linkedMapOf<String, PomDependency>()
        for (dep in pomMeta.dependencies) {
            if (isMetalavaGroup(dep.group)) {
                validateMetalavaCoordinate(
                    context = "Published POM for '$artifactId'",
                    group = dep.group,
                    module = dep.module,
                    version = dep.version,
                    expectedVersion = expectedVersion,
                    published = published,
                    unpublished = unpublished,
                )
            } else if (dep.version.isEmpty()) {
                throw GradleException(
                    "Published POM for '$artifactId' declares external dependency " +
                        "'${dep.group}:${dep.module}' without a <version>"
                )
            }
            val coord = "${dep.group}:${dep.module}"
            if (pomByCoord.put(coord, dep) != null) {
                throw GradleException(
                    "Published POM for '$artifactId' declares duplicate dependency '$coord'"
                )
            }
        }

        val apiDeps = moduleMeta.variants.getValue("apiElements").dependencies
        val runtimeDeps = moduleMeta.variants.getValue("runtimeElements").dependencies
        val (apiPlatforms, apiRegularDeps) = apiDeps.partition { it.isPlatform }
        val (runtimePlatforms, runtimeRegularDeps) = runtimeDeps.partition { it.isPlatform }

        // Platform/BOM dependencies in .module (Category = platform / enforced-platform) are
        // emitted by Gradle into <dependencyManagement><dependencies> with <scope>import</scope>
        // rather than <project>/<dependencies>.
        for (platformDep in
            (apiPlatforms + runtimePlatforms).distinctBy {
                "${it.group}:${it.module}:${it.version}"
            }) {
            val coord = "${platformDep.group}:${platformDep.module}"
            val managedMatches = managedByCoord[coord].orEmpty()
            val matchingImport =
                managedMatches.firstOrNull {
                    it.scope == "import" && it.type == "pom" && it.version == platformDep.version
                }
            if (matchingImport == null) {
                throw GradleException(
                    "Published POM for '$artifactId' is missing <dependencyManagement> BOM " +
                        "import for platform dependency '$coord:${platformDep.version}' declared " +
                        "in .module"
                )
            }
        }

        // Track apiElements matches separately from runtimeElements matches so we enforce both
        // directions of Gradle's POM scope mapping: apiElements -> "compile" and runtimeElements
        // minus apiElements -> "runtime" (allowing KMP umbrella modules such as
        // androidx.tracing:tracing-wire to map to their JVM target artifact in .pom).
        val apiMatchedPomCoords = hashSetOf<String>()
        val matchedPomCoords = hashSetOf<String>()
        for (dep in apiRegularDeps) {
            val matchedCoord =
                findMatchingPomCoord(dep, pomByCoord, coordToFileKeys, externalGraph)
                    ?: throw GradleException(
                        "Published POM for '$artifactId' is missing API dependency " +
                            "'${dep.group}:${dep.module}' declared in .module"
                    )
            val pomDep = pomByCoord.getValue(matchedCoord)
            if (pomDep.scope != "compile") {
                throw GradleException(
                    "Published POM for '$artifactId' declares API dependency '$matchedCoord' " +
                        "with scope '${pomDep.scope}' instead of 'compile'"
                )
            }
            verifyPomDependencyVersionAndExcludes(
                artifactId = artifactId,
                moduleDep = dep,
                matchedCoord = matchedCoord,
                pomDep = pomDep,
                externalCoordToVersion = externalCoordToVersion,
            )
            apiMatchedPomCoords.add(matchedCoord)
            matchedPomCoords.add(matchedCoord)
        }

        for (dep in runtimeRegularDeps) {
            val matchedCoord =
                findMatchingPomCoord(dep, pomByCoord, coordToFileKeys, externalGraph)
                    ?: throw GradleException(
                        "Published POM for '$artifactId' is missing runtime dependency " +
                            "'${dep.group}:${dep.module}' declared in .module"
                    )
            val pomDep = pomByCoord.getValue(matchedCoord)
            if (matchedCoord !in apiMatchedPomCoords) {
                if (pomDep.scope != "runtime") {
                    throw GradleException(
                        "Published POM for '$artifactId' declares runtime-only dependency " +
                            "'$matchedCoord' with scope '${pomDep.scope}' instead of 'runtime'"
                    )
                }
                verifyPomDependencyVersionAndExcludes(
                    artifactId = artifactId,
                    moduleDep = dep,
                    matchedCoord = matchedCoord,
                    pomDep = pomDep,
                    externalCoordToVersion = externalCoordToVersion,
                )
            }
            matchedPomCoords.add(matchedCoord)
        }

        val extraPomCoords = (pomByCoord.keys - matchedPomCoords).sorted()
        if (extraPomCoords.isNotEmpty()) {
            throw GradleException(
                "Published POM for '$artifactId' declares dependencies not present in .module: " +
                    "$extraPomCoords"
            )
        }
    }

    /**
     * Verifies [pomDep]'s `<version>` and `<exclusions>` match [moduleDep] (using
     * [externalCoordToVersion] for KMP-redirected coordinates whose artifact ID differs from
     * [moduleDep]).
     */
    private fun verifyPomDependencyVersionAndExcludes(
        artifactId: String,
        moduleDep: ModuleDependency,
        matchedCoord: String,
        pomDep: PomDependency,
        externalCoordToVersion: Map<String, String>,
    ) {
        val exact = "${moduleDep.group}:${moduleDep.module}"
        val expectedVersion =
            if (matchedCoord == exact) {
                moduleDep.version
            } else {
                externalCoordToVersion[matchedCoord] ?: moduleDep.version
            }
        if (pomDep.version != expectedVersion) {
            throw GradleException(
                "Published POM for '$artifactId' declares dependency '$matchedCoord' with " +
                    "version '${pomDep.version}' instead of '$expectedVersion'"
            )
        }
        val expectedExcludes = moduleDep.excludes.toSet()
        if (pomDep.exclusions != expectedExcludes) {
            throw GradleException(
                "Published POM for '$artifactId' declares exclusions ${pomDep.exclusions} for " +
                    "'$matchedCoord' instead of .module exclusions $expectedExcludes"
            )
        }
    }

    /**
     * Resolves the `.pom` coordinate corresponding to [moduleDep]: either exact `group:module`, or
     * for KMP umbrella modules without their own JAR (e.g. `androidx.tracing:tracing-wire`), the
     * redirected JVM child coordinate emitted into `.pom` by Gradle.
     */
    private fun findMatchingPomCoord(
        moduleDep: ModuleDependency,
        pomByCoord: Map<String, PomDependency>,
        coordToFileKeys: Map<String, Set<String>>,
        externalGraph: Map<String, Set<String>>,
    ): String? {
        val exact = "${moduleDep.group}:${moduleDep.module}"
        if (exact in pomByCoord) {
            return exact
        }

        // Restrict fallback to JAR-less coordinates (e.g. KMP umbrella modules whose .pom entry is
        // redirected to a JVM target child) so missing normal JAR dependencies are not masked.
        if (coordToFileKeys[exact].isNullOrEmpty()) {
            return externalGraph[exact]?.firstOrNull { it in pomByCoord }
        }
        return null
    }

    /** Verifies `androidx_aggregate_build_info.txt` lists exactly [published] and valid deps. */
    private fun verifyAggregateBuildInfo(
        file: File,
        expectedVersion: String,
        published: Set<String>,
        unpublished: Set<String>,
        parsedModules: Map<String, ParsedModuleMetadata>,
    ) {
        requireExistingFile(file, "aggregate build info file")
        val root = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        val artifactsArray =
            root.getAsJsonArray("artifacts")
                ?: throw GradleException(
                    "Aggregate build info file is missing 'artifacts' array: ${file.absolutePath}"
                )

        val seenArtifacts = linkedSetOf<String>()
        for (element in artifactsArray) {
            val artifactObj = element.asJsonObject
            val groupId = artifactObj.get("groupId")?.asString.orEmpty()
            val artifactId = artifactObj.get("artifactId")?.asString.orEmpty()
            val version = artifactObj.get("version")?.asString.orEmpty()

            validateMetalavaCoordinate(
                context = "Aggregate build info artifact entry",
                group = groupId,
                module = artifactId,
                version = version,
                expectedVersion = expectedVersion,
                published = published,
                unpublished = unpublished,
            )
            seenArtifacts.add(artifactId)

            val buildInfoDeps = linkedSetOf<String>()
            val depsArray = artifactObj.getAsJsonArray("dependencies")
            if (depsArray != null) {
                for (depElem in depsArray) {
                    val depObj = depElem.asJsonObject
                    val depGroup = depObj.get("groupId")?.asString.orEmpty()
                    val depArtifact = depObj.get("artifactId")?.asString.orEmpty()
                    val depVersion = depObj.get("version")?.asString.orEmpty()
                    val isTipOfTree = depObj.get("isTipOfTree")?.asBoolean ?: false

                    validateMetalavaCoordinate(
                        context = "Aggregate build info for '$artifactId'",
                        group = depGroup,
                        module = depArtifact,
                        version = depVersion,
                        expectedVersion = expectedVersion,
                        published = published,
                        unpublished = unpublished,
                    )
                    if (!isTipOfTree) {
                        throw GradleException(
                            "Aggregate build info for '$artifactId' has isTipOfTree=false for " +
                                "local Metalava dependency '$depArtifact'"
                        )
                    }
                    buildInfoDeps.add(depArtifact)
                }
            }

            // Filter .module dependencies to METALAVA_GROUP because CreateLibraryBuildInfoTask
            // only records tip-of-tree local Metalava dependencies, not third-party libraries.
            val expectedMetalavaDeps =
                parsedModules[artifactId]
                    ?.variants
                    ?.values
                    .orEmpty()
                    .flatMap { it.dependencies }
                    .filter { it.group == METALAVA_GROUP }
                    .map { it.module }
                    .toSet()
            if (buildInfoDeps != expectedMetalavaDeps) {
                throw GradleException(
                    "Aggregate build info dependencies for '$artifactId' " +
                        "(${buildInfoDeps.sorted()}) do not match published .module " +
                        "(${expectedMetalavaDeps.sorted()})"
                )
            }
        }

        if (seenArtifacts != published) {
            throw GradleException(
                "Aggregate build info artifacts (${seenArtifacts.sorted()}) do not match " +
                    "expected published artifacts (${published.sorted()})"
            )
        }
    }

    /**
     * Resolves the runtime classpath and external coordinate set for [rootArtifactId] strictly by
     * traversing published `.module` files inside [m2Repo] for Metalava artifacts and then
     * traversing [externalGraph] from the declared external coordinates (honoring `.module`
     * `excludes`).
     */
    private fun resolvePublishedRuntimeClasspath(
        rootArtifactId: String,
        m2Repo: File,
        version: String,
        parsedModules: Map<String, ParsedModuleMetadata>,
        externalGraph: Map<String, Set<String>>,
        coordToFileKeys: Map<String, Set<String>>,
        externalFilesByKey: Map<String, File>,
    ): ResolvedPublishedRuntime {
        val classpath = linkedSetOf<File>()
        val visitedMetalava = hashSetOf<Pair<String, Set<ModuleExclude>>>()
        val metalavaQueue = ArrayDeque<Pair<String, Set<ModuleExclude>>>()
        val externalRoots = mutableListOf<ModuleDependency>()

        // Resolve local Metalava artifacts strictly from m2repository .module files (never from
        // Gradle's project graph) so project substitutions cannot mask missing or mispublished
        // JARs or metadata. Track active excludes per visited Metalava node so excludes declared on
        // local project dependencies or configurations propagate transitively without letting an
        // excluded diamond path shadow an unexcluded path.
        metalavaQueue.add(rootArtifactId to emptySet())
        while (metalavaQueue.isNotEmpty()) {
            val state = metalavaQueue.removeFirst()
            if (!visitedMetalava.add(state)) {
                continue
            }
            val (currentId, activeExcludes) = state
            val moduleMeta =
                parsedModules[currentId]
                    ?: throw GradleException(
                        "Cannot resolve published Metalava artifact '$currentId' from m2repository"
                    )
            val runtimeVariant =
                moduleMeta.variants["runtimeElements"]
                    ?: throw GradleException(
                        "Published .module for '$currentId' is missing 'runtimeElements' variant"
                    )

            val artifactDir = File(m2Repo, "$METALAVA_GROUP_PATH/$currentId/$version")
            for (fileSpec in runtimeVariant.files) {
                val jarFile = File(artifactDir, fileSpec.url)
                requireExistingFile(jarFile, "published runtime JAR for '$currentId'")
                classpath.add(jarFile)
            }

            for (dep in runtimeVariant.dependencies) {
                val mergedExcludes = activeExcludes + dep.excludes
                if (isExcluded(dep.group, dep.module, mergedExcludes.toList())) {
                    continue
                }
                if (dep.group == METALAVA_GROUP) {
                    metalavaQueue.add(dep.module to mergedExcludes)
                } else {
                    externalRoots.add(dep.copy(excludes = mergedExcludes.toList()))
                }
            }
        }

        // Traverse external dependencies per declared root so root-specific excludes (such as
        // lint -> exclude lint-checks) are enforced accurately.
        val resolvedExternalCoords = linkedSetOf<String>()
        for (rootDep in externalRoots) {
            val rootCoord = "${rootDep.group}:${rootDep.module}"
            val visitedForRoot = hashSetOf<String>()
            val queue = ArrayDeque<String>()
            if (!isExcluded(rootDep.group, rootDep.module, rootDep.excludes)) {
                queue.add(rootCoord)
            }
            while (queue.isNotEmpty()) {
                val coord = queue.removeFirst()
                if (!visitedForRoot.add(coord)) {
                    continue
                }
                resolvedExternalCoords.add(coord)
                for (childCoord in externalGraph[coord].orEmpty()) {
                    val childGroup = childCoord.substringBefore(':')
                    val childModule = childCoord.substringAfter(':')
                    if (!isExcluded(childGroup, childModule, rootDep.excludes)) {
                        queue.add(childCoord)
                    }
                }
            }
        }

        for (coord in resolvedExternalCoords) {
            val fileKeys = coordToFileKeys[coord]
            if (fileKeys != null) {
                for (key in fileKeys) {
                    val file =
                        externalFilesByKey[key]
                            ?: throw GradleException(
                                "External artifact file missing for '$coord' (key '$key')"
                            )
                    classpath.add(file)
                }
            } else if (coord !in externalGraph) {
                // Coordinates present in externalGraph with no JAR files are valid non-JAR
                // components such as BOM platforms (e.g. kotlinx-coroutines-bom) or KMP umbrella
                // modules (e.g. androidx.tracing:tracing) whose child targets supply the JARs.
                throw GradleException(
                    "External coordinate '$coord' declared in published metadata for " +
                        "'$rootArtifactId' could not be resolved."
                )
            }
        }

        return ResolvedPublishedRuntime(
            classpath = classpath.toList(),
            externalCoords = resolvedExternalCoords,
        )
    }

    /** Loads [classesToLoad] from [classpath] and asserts every class comes from [m2Repo]. */
    private fun verifyModelClasses(
        m2Repo: File,
        modelId: String,
        classpath: List<File>,
        classesToLoad: List<String>,
    ) {
        val m2RepoCanonical = m2Repo.canonicalFile.toPath()
        val urls = classpath.map { it.toURI().toURL() }.toTypedArray()

        // Use the platform classloader as parent so Gradle's build classpath and workspace classes
        // cannot leak into the isolated check; every Metalava and third-party class must resolve
        // strictly from the published classpath.
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            for (className in classesToLoad) {
                // Load with initialize = false to avoid running static initializers while still
                // forcing class loading and member/supertype resolution below.
                val loadedClass =
                    try {
                        Class.forName(className, false, loader)
                    } catch (e: Throwable) {
                        throw GradleException(
                            "Failed to load smoke-check class '$className' from published " +
                                "'$modelId' classpath",
                            e,
                        )
                    }

                // Force resolution of supertype/method signatures to catch missing embedded APIs
                // or missing promoted compile/runtime dependencies.
                try {
                    loadedClass.declaredMethods
                    loadedClass.interfaces
                    loadedClass.superclass
                } catch (e: Throwable) {
                    throw GradleException(
                        "Failed to resolve members/supertypes of '$className' from published " +
                            "'$modelId' classpath",
                        e,
                    )
                }

                verifyLoadedClassFromM2Repo(
                    loadedClass = loadedClass,
                    expectedLoader = loader,
                    m2RepoCanonical = m2RepoCanonical,
                    description = "Smoke-check class '$className'",
                )
            }
        }
    }

    /** Verifies `ServiceLoader` finds [expectedToolProviderName] from the published CLI JARs. */
    private fun verifyToolProvider(
        m2Repo: File,
        cliId: String,
        classpath: List<File>,
        expectedToolProviderName: String,
    ) {
        val m2RepoCanonical = m2Repo.canonicalFile.toPath()
        val urls = classpath.map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val provider =
                ServiceLoader.load(ToolProvider::class.java, loader).firstOrNull {
                    it.name() == expectedToolProviderName
                }
                    ?: throw GradleException(
                        "Published '$cliId' classpath did not provide ToolProvider named " +
                            "'$expectedToolProviderName' via META-INF/services"
                    )
            verifyLoadedClassFromM2Repo(
                loadedClass = provider.javaClass,
                expectedLoader = loader,
                m2RepoCanonical = m2RepoCanonical,
                description = "ToolProvider '${provider.javaClass.name}'",
            )
        }
    }

    /**
     * Asserts that [loadedClass] was defined directly by [expectedLoader] (not delegated to a
     * bootstrap/platform module) and that its code source lies inside [m2RepoCanonical].
     */
    private fun verifyLoadedClassFromM2Repo(
        loadedClass: Class<*>,
        expectedLoader: ClassLoader,
        m2RepoCanonical: Path,
        description: String,
    ) {
        if (loadedClass.classLoader !== expectedLoader) {
            throw GradleException(
                "$description was loaded by '${loadedClass.classLoader}' instead of the " +
                    "isolated published m2repository classloader"
            )
        }
        val location =
            loadedClass.protectionDomain?.codeSource?.location
                ?: throw GradleException(
                    "$description has no code source location (expected JAR inside " +
                        "'$m2RepoCanonical')"
                )
        val codeSourceFile = File(location.toURI()).canonicalFile
        if (!codeSourceFile.toPath().startsWith(m2RepoCanonical)) {
            throw GradleException(
                "$description was loaded from '${codeSourceFile.absolutePath}' instead of " +
                    "published m2repository '$m2RepoCanonical'"
            )
        }
    }

    /** Executes [mainClass] with [args] in a fresh JVM using only [cliClasspath]. */
    private fun verifyCliExecution(
        cliClasspath: List<File>,
        mainClass: String,
        args: List<String>,
        expectedOutput: String?,
    ) {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result =
            execOperations.javaexec { spec ->
                spec.classpath = objects.fileCollection().from(cliClasspath)
                spec.mainClass.set(mainClass)
                spec.args = args
                spec.standardOutput = stdout
                spec.errorOutput = stderr

                // Capture non-zero exits ourselves so both stdout and stderr are included in the
                // reported GradleException.
                spec.isIgnoreExitValue = true
            }
        val stdoutText = stdout.toString(Charsets.UTF_8)
        val stderrText = stderr.toString(Charsets.UTF_8)
        if (result.exitValue != 0) {
            throw GradleException(
                "Published CLI smoke check ($mainClass ${args.joinToString(" ")}) failed with " +
                    "exit code ${result.exitValue}.\nstdout:\n$stdoutText\nstderr:\n$stderrText"
            )
        }
        if (expectedOutput != null && !stdoutText.contains(expectedOutput)) {
            throw GradleException(
                "Published CLI smoke check output did not contain '$expectedOutput'.\n" +
                    "stdout:\n$stdoutText\nstderr:\n$stderrText"
            )
        }
    }

    /** Returns true if [group] belongs to the Metalava group hierarchy. */
    private fun isMetalavaGroup(group: String): Boolean =
        group == METALAVA_GROUP || group.startsWith("$METALAVA_GROUP.")

    /** Returns true if `(group, module)` matches any rule in [excludes]. */
    private fun isExcluded(group: String, module: String, excludes: List<ModuleExclude>): Boolean =
        excludes.any { rule ->
            (rule.group == "*" || rule.group == group) &&
                (rule.module == "*" || rule.module == module)
        }

    /** Computes the lowercase hex SHA-256 digest of [file]. */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Parses a Gradle Module Metadata `.module` JSON file. */
    private fun parseModuleMetadata(moduleFile: File): ParsedModuleMetadata {
        val root = JsonParser.parseString(moduleFile.readText(Charsets.UTF_8)).asJsonObject
        val variants = linkedMapOf<String, ParsedVariant>()
        val variantsArray = root.getAsJsonArray("variants")
        if (variantsArray != null) {
            for (elem in variantsArray) {
                val variantObj = elem.asJsonObject
                val name = variantObj.get("name").asString
                val deps = parseVariantDependencies(variantObj)
                val files = parseVariantFiles(variantObj)
                variants[name] = ParsedVariant(name = name, dependencies = deps, files = files)
            }
        }
        return ParsedModuleMetadata(variants = variants)
    }

    /**
     * Extracts dependency entries, `org.gradle.category` attributes, and exclude rules from a
     * `.module` variant JSON object.
     */
    private fun parseVariantDependencies(variantObj: JsonObject): List<ModuleDependency> {
        val depsArray = variantObj.getAsJsonArray("dependencies") ?: return emptyList()
        return depsArray.map { depElem ->
            val obj = depElem.asJsonObject
            val group = obj.get("group").asString
            val module = obj.get("module").asString
            val version =
                obj.getAsJsonObject("version")
                    ?.let { verObj ->
                        verObj.get("requires")?.asString ?: verObj.get("strictly")?.asString
                    }
                    .orEmpty()
            val category =
                obj.getAsJsonObject("attributes")?.get(Category.CATEGORY_ATTRIBUTE.name)?.asString
            val excludes =
                obj.getAsJsonArray("excludes")?.map { exElem ->
                    val exObj = exElem.asJsonObject
                    ModuleExclude(
                        group = exObj.get("group")?.asString ?: "*",
                        module = exObj.get("module")?.asString ?: "*",
                    )
                } ?: emptyList()
            ModuleDependency(
                group = group,
                module = module,
                version = version,
                category = category,
                excludes = excludes,
            )
        }
    }

    /** Extracts artifact file specs and SHA-256 digests from a `.module` variant JSON object. */
    private fun parseVariantFiles(variantObj: JsonObject): List<ModuleFileSpec> {
        val filesArray = variantObj.getAsJsonArray("files") ?: return emptyList()
        return filesArray.map { fileElem ->
            val obj = fileElem.asJsonObject
            ModuleFileSpec(
                url = obj.get("url").asString,
                sha256 = obj.get("sha256")?.asString,
            )
        }
    }

    /**
     * Parses direct `<project>/<dependencies>` and
     * `<project>/<dependencyManagement>/<dependencies>` entries (including `<type>`, `<scope>`, and
     * `<exclusions>`) from a Maven `.pom` XML file.
     */
    private fun parsePomMetadata(pomFile: File): ParsedPomMetadata {
        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(pomFile)
        val projectElement = document.documentElement
        val directDeps = mutableListOf<PomDependency>()
        val managedDeps = mutableListOf<PomDependency>()

        // Walk direct child nodes level-by-level rather than calling getElementsByTagName so BOM
        // imports and constraints inside <dependencyManagement> stay separated from direct
        // <dependencies> while still being validated.
        val rootChildren = projectElement.childNodes
        for (i in 0 until rootChildren.length) {
            val child = rootChildren.item(i) as? Element ?: continue
            when (child.tagName) {
                "dependencies" -> directDeps.addAll(parseDependencyElements(child))
                "dependencyManagement" -> {
                    val mgmtChildren = child.childNodes
                    for (j in 0 until mgmtChildren.length) {
                        val depsNode = mgmtChildren.item(j) as? Element ?: continue
                        if (depsNode.tagName == "dependencies") {
                            managedDeps.addAll(parseDependencyElements(depsNode))
                        }
                    }
                }
            }
        }
        return ParsedPomMetadata(dependencies = directDeps, managedDependencies = managedDeps)
    }

    /** Extracts `<dependency>` child entries from a `<dependencies>` XML element. */
    private fun parseDependencyElements(dependenciesNode: Element): List<PomDependency> {
        val result = mutableListOf<PomDependency>()
        val depChildren = dependenciesNode.childNodes
        for (j in 0 until depChildren.length) {
            val depElement = depChildren.item(j) as? Element ?: continue
            if (depElement.tagName != "dependency") {
                continue
            }
            var group = ""
            var artifact = ""
            var version = ""

            // Per Maven POM semantics, omitted <scope> defaults to "compile" and <type> to "jar".
            var scope = "compile"
            var type = "jar"
            val exclusions = linkedSetOf<ModuleExclude>()
            val fields = depElement.childNodes
            for (k in 0 until fields.length) {
                val field = fields.item(k) as? Element ?: continue
                when (field.tagName) {
                    "groupId" -> group = field.textContent.trim()
                    "artifactId" -> artifact = field.textContent.trim()
                    "version" -> version = field.textContent.trim()
                    "scope" -> scope = field.textContent.trim()
                    "type" -> type = field.textContent.trim()
                    "exclusions" -> {
                        val exChildren = field.childNodes
                        for (m in 0 until exChildren.length) {
                            val exEl = exChildren.item(m) as? Element ?: continue
                            if (exEl.tagName != "exclusion") {
                                continue
                            }
                            var exGroup = "*"
                            var exModule = "*"
                            val exFields = exEl.childNodes
                            for (n in 0 until exFields.length) {
                                val exField = exFields.item(n) as? Element ?: continue
                                when (exField.tagName) {
                                    "groupId" -> exGroup = exField.textContent.trim()
                                    "artifactId" -> exModule = exField.textContent.trim()
                                }
                            }
                            exclusions.add(ModuleExclude(group = exGroup, module = exModule))
                        }
                    }
                }
            }
            if (group.isNotEmpty() && artifact.isNotEmpty()) {
                result.add(PomDependency(group, artifact, version, scope, type, exclusions))
            }
        }
        return result
    }

    /** Resolved published runtime classpath and transitive external coordinates for an artifact. */
    private data class ResolvedPublishedRuntime(
        val classpath: List<File>,
        val externalCoords: Set<String>,
    )

    /** Parsed view of a `.module` file's variants. */
    private data class ParsedModuleMetadata(val variants: Map<String, ParsedVariant>)

    /** Parsed view of a single variant inside a `.module` file. */
    private data class ParsedVariant(
        val name: String,
        val dependencies: List<ModuleDependency>,
        val files: List<ModuleFileSpec>,
    )

    /** Dependency entry inside a `.module` variant. */
    private data class ModuleDependency(
        val group: String,
        val module: String,
        val version: String,
        val category: String?,
        val excludes: List<ModuleExclude>,
    ) {
        /** Whether this dependency targets a Maven BOM (`platform` or `enforced-platform`). */
        val isPlatform: Boolean
            get() = category == Category.REGULAR_PLATFORM || category == Category.ENFORCED_PLATFORM
    }

    /** Exclusion rule attached to a `.module` or `.pom` dependency. */
    private data class ModuleExclude(val group: String, val module: String)

    /** Published file entry inside a `.module` variant. */
    private data class ModuleFileSpec(val url: String, val sha256: String?)

    /** Parsed direct and `<dependencyManagement>` dependency entries from a `.pom` file. */
    private data class ParsedPomMetadata(
        val dependencies: List<PomDependency>,
        val managedDependencies: List<PomDependency>,
    )

    /** Dependency entry inside a `.pom` file. */
    private data class PomDependency(
        val group: String,
        val module: String,
        val version: String,
        val scope: String,
        val type: String,
        val exclusions: Set<ModuleExclude>,
    )

    companion object {
        /** Maven group ID required for all published Metalava artifacts. */
        private const val METALAVA_GROUP = "com.android.tools.metalava"

        /** Directory path corresponding to [METALAVA_GROUP] inside an `m2repository`. */
        private const val METALAVA_GROUP_PATH = "com/android/tools/metalava"
    }
}

/**
 * Registers the root [VERIFY_PUBLISHED_ARTIFACTS_TASK] task to verify published artifacts and
 * metadata in `out/dist/repo/m2repository` and `androidx_aggregate_build_info.txt`.
 */
fun Project.setUpVerifyPublishedArtifactsTask(
    modelArtifactId: String? = "metalava-model",
    modelSmokeCheckClasses: List<String> =
        if (modelArtifactId != null) {
            listOf(
                "com.android.tools.metalava.model.Codebase",
                "com.android.tools.metalava.model.ClassItem",
                "com.android.tools.metalava.model.Item",
            )
        } else {
            emptyList()
        },
    cliArtifactId: String? = "metalava",
    cliMainClass: String? =
        if (cliArtifactId != null) "com.android.tools.metalava.Driver" else null,
    cliArgs: List<String> = listOf("--version"),
    expectedToolProviderName: String? = if (cliArtifactId != null) "metalava" else null,
) {
    // Register standard JVM attribute compatibility/disambiguation rules (e.g. TargetJvmVersion
    // 8 <= 17, TargetJvmEnvironment) on the root project without adding Java source sets or tasks.
    plugins.apply(JvmEcosystemPlugin::class.java)

    val publishedExternalRuntimeScope =
        configurations.dependencyScope("publishedExternalRuntimeScope")
    val publishedExternalRuntime =
        configurations.resolvable("publishedExternalRuntime") { config ->
            config.extendsFrom(publishedExternalRuntimeScope.get())
            configureJvmResolutionAttributes(
                project = this,
                config = config,
                usageName = Usage.JAVA_RUNTIME,
                targetJvmVersion = 17,
            )
        }

    val versionProvider = readMetalavaVersion()

    // Filter to external ModuleComponentIdentifiers only (local Metalava JARs must come strictly
    // from m2repository) and key files by "filename:length" so Configuration Cache inputs stay
    // relocatable without storing checkout-specific absolute paths.
    val externalView =
        publishedExternalRuntime.map { config ->
            config.incoming.artifactView { view ->
                view.componentFilter { it is ModuleComponentIdentifier }
            }
        }

    val externalCoordToFileKeys =
        externalView.flatMap { view ->
            view.artifacts.resolvedArtifacts.map { artifacts ->
                val map = linkedMapOf<String, MutableSet<String>>()
                for (artifact in artifacts) {
                    val id = artifact.id.componentIdentifier
                    if (id is ModuleComponentIdentifier) {
                        val coord = "${id.group}:${id.module}"
                        val fileKey = "${artifact.file.name}:${artifact.file.length()}"
                        map.getOrPut(coord) { linkedSetOf() }.add(fileKey)
                    }
                }
                map.mapValues { (_, keys) -> keys.toSet() }
            }
        }

    val externalResolutionRoot =
        publishedExternalRuntime.flatMap { config ->
            config.incoming.resolutionResult.rootComponent
        }
    val externalTransitiveGraph =
        externalResolutionRoot.map { root -> buildExternalTransitiveGraph(root) }
    val externalCoordinateToVersion =
        externalResolutionRoot.map { root -> buildExternalCoordinateVersions(root) }

    val verifyTask =
        tasks.register(VERIFY_PUBLISHED_ARTIFACTS_TASK, VerifyPublishedArtifactsTask::class.java) {
            task ->
            task.group = "verification"
            task.description =
                "Verifies published Metalava Maven artifacts, metadata, and runtime smoke checks."
            task.m2RepositoryDirectory.set(
                File(getDistributionDirectory(project), "repo/m2repository")
            )
            task.aggregateBuildInfoFile.set(
                File(getDistributionDirectory(project), "androidx_aggregate_build_info.txt")
            )
            task.metalavaVersion.set(versionProvider)
            task.externalArtifactFiles.from(externalView.map { it.files })
            task.externalCoordinateToFileKeys.set(externalCoordToFileKeys)
            task.externalTransitiveGraph.set(externalTransitiveGraph)
            task.externalCoordinateToVersion.set(externalCoordinateToVersion)

            if (modelArtifactId != null) {
                task.modelArtifactId.set(modelArtifactId)
            }
            task.modelSmokeCheckClasses.set(modelSmokeCheckClasses)

            if (cliArtifactId != null) {
                task.cliArtifactId.set(cliArtifactId)
            }
            if (cliMainClass != null) {
                task.cliMainClass.set(cliMainClass)
                task.expectedCliOutputSubstring.set(versionProvider)
            }
            task.cliArgs.set(cliArgs)
            if (expectedToolProviderName != null) {
                task.expectedToolProviderName.set(expectedToolProviderName)
            }
            task.ideaSyncActive.set(
                providers.systemProperty("idea.sync.active").map { it.toBoolean() }.orElse(false)
            )

            task.dependsOn(CREATE_AGGREGATE_BUILD_INFO_FILES_TASK)
        }

    subprojects { subproject ->
        // Register published artifacts reactively as soon as maven-publish is applied.
        subproject.plugins.withType(MavenPublishPlugin::class.java) {
            publishedExternalRuntimeScope.configure { config ->
                config.dependencies.add(dependencies.project(mapOf("path" to subproject.path)))
            }
            verifyTask.configure { task ->
                task.publishedArtifacts.add(subproject.name)
                task.dependsOn(
                    subproject.tasks.matching {
                        it.name == "publishMetalavaPublicationToMavenRepository"
                    }
                )
            }
        }

        // Wait until subproject evaluation completes before deciding whether a project remained
        // unpublished (and reading its final group and runtimeClasspath).
        subproject.afterEvaluate {
            if (subproject.plugins.hasPlugin(MavenPublishPlugin::class.java)) {
                val expectedExternalCoords =
                    subproject.configurations
                        .named("runtimeClasspath")
                        .flatMap { config -> config.incoming.resolutionResult.rootComponent }
                        .map { root -> collectExternalCoordinates(root) }
                verifyTask.configure { task ->
                    task.expectedRuntimeExternalCoordinates.put(
                        subproject.name,
                        expectedExternalCoords,
                    )
                }
            } else {
                val coord = "${subproject.group}:${subproject.name}"
                verifyTask.configure { task -> task.unpublishedCoordinates.add(coord) }
            }
        }
    }
}

/**
 * Traverses [root] from a `ResolutionResult` and builds a map of every external `"group:module"`
 * coordinate to its direct external `"group:module"` dependencies.
 */
private fun buildExternalTransitiveGraph(root: ResolvedComponentResult): Map<String, Set<String>> {
    val graph = linkedMapOf<String, MutableSet<String>>()
    val visited = hashSetOf<ComponentIdentifier>()
    val queue = ArrayDeque<ResolvedComponentResult>()
    visited.add(root.id)
    queue.add(root)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val currentId = current.id

        // Record every visited external module in graph even if it has no outgoing edges so
        // JAR-less BOM platforms remain distinguishable from unknown coordinates, while still
        // traversing non-external project nodes below to reach each subproject's external roots.
        val currentExternalCoord =
            if (currentId is ModuleComponentIdentifier) {
                "${currentId.group}:${currentId.module}"
                    .also { graph.getOrPut(it) { linkedSetOf() } }
            } else {
                null
            }

        for (edge in current.dependencies) {
            if (edge is ResolvedDependencyResult && !edge.isConstraint) {
                val target = edge.selected
                val targetId = target.id
                if (currentExternalCoord != null && targetId is ModuleComponentIdentifier) {
                    graph
                        .getOrPut(currentExternalCoord) { linkedSetOf() }
                        .add("${targetId.group}:${targetId.module}")
                }
                if (visited.add(targetId)) {
                    queue.add(target)
                }
            }
        }
    }

    return graph.mapValues { (_, deps) -> deps.toSet() }
}

/** Collects all external `"group:module"` coordinates reachable from [root]. */
private fun collectExternalCoordinates(root: ResolvedComponentResult): Set<String> =
    buildExternalTransitiveGraph(root).keys

/**
 * Traverses [root] from a `ResolutionResult` and maps every resolved external `"group:module"`
 * coordinate to its resolved version string.
 */
private fun buildExternalCoordinateVersions(root: ResolvedComponentResult): Map<String, String> {
    val versions = linkedMapOf<String, String>()
    val visited = hashSetOf<ComponentIdentifier>()
    val queue = ArrayDeque<ResolvedComponentResult>()
    visited.add(root.id)
    queue.add(root)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val currentId = current.id
        if (currentId is ModuleComponentIdentifier) {
            versions["${currentId.group}:${currentId.module}"] = currentId.version
        }
        for (edge in current.dependencies) {
            if (edge is ResolvedDependencyResult && !edge.isConstraint) {
                val target = edge.selected
                if (visited.add(target.id)) {
                    queue.add(target)
                }
            }
        }
    }
    return versions
}
