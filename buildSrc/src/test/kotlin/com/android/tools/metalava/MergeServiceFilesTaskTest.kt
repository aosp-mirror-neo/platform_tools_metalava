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

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unit tests for [MergeServiceFilesTask]. */
class MergeServiceFilesTaskTest {

    /** Temporary directory for test input and output files. */
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun `parseServiceProviders strips comments, blank lines, and whitespace`() {
        val content =
            """
                # Leading full-line comment
                
                  com.example.ImplB   # trailing comment
                com.example.ImplA
                    # indented comment
                com.example.ImplC#comment-without-space
            """
                .trimIndent()
                .replace("\n", "\r\n")

        assertThat(MergeServiceFilesTask.parseServiceProviders(content))
            .containsExactly("com.example.ImplB", "com.example.ImplA", "com.example.ImplC")
            .inOrder()
    }

    @Test
    fun `merge combines, deduplicates, and sorts providers across multiple input roots`() {
        val root1 = tempFolder.newFolder("root1")
        val root2 = tempFolder.newFolder("root2")
        val outputDir = tempFolder.newFolder("output")

        writeServiceFile(
            root1,
            "com.example.SharedService",
            """
                # From root1
                com.example.ZImpl
                com.example.SharedImpl
            """
                .trimIndent(),
        )
        writeServiceFile(root1, "com.example.OnlyRoot1", "com.example.Root1Impl\n")
        writeServiceFile(
            root2,
            "com.example.SharedService",
            """
                com.example.AImpl # inline comment
                com.example.SharedImpl
            """
                .trimIndent(),
        )
        writeServiceFile(root2, "com.example.OnlyRoot2", "com.example.Root2Impl\n")
        writeServiceFile(root2, "com.example.CommentOnly", "# just a comment\n\n")

        // Non-service file in root1 must be ignored by MergeServiceFilesTask itself (root1 is
        // passed unfiltered below to exercise the task's own path-prefix check).
        File(root1, "META-INF/MANIFEST.MF").apply {
            parentFile.mkdirs()
            writeText("Manifest-Version: 1.0\n")
        }

        val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder("proj")).build()
        val task =
            project.tasks
                .register("mergeServices", MergeServiceFilesTask::class.java) {
                    it.serviceFiles.from(
                        project.fileTree(root1),
                        project.fileTree(root2) { spec -> spec.include("META-INF/services/**") },
                    )
                    it.outputDirectory.set(outputDir)
                }
                .get()

        task.merge()

        val servicesDir = File(outputDir, "META-INF/services")
        assertThat(servicesDir.list()?.sorted())
            .containsExactly(
                "com.example.OnlyRoot1",
                "com.example.OnlyRoot2",
                "com.example.SharedService",
            )
        assertThat(File(servicesDir, "com.example.SharedService").readText())
            .isEqualTo(
                """
                    com.example.AImpl
                    com.example.SharedImpl
                    com.example.ZImpl
                """
                    .trimIndent() + "\n"
            )
        assertThat(File(servicesDir, "com.example.OnlyRoot1").readText())
            .isEqualTo("com.example.Root1Impl\n")
        assertThat(File(servicesDir, "com.example.OnlyRoot2").readText())
            .isEqualTo("com.example.Root2Impl\n")
        assertThat(File(outputDir, "META-INF/MANIFEST.MF").exists()).isFalse()
    }

    @Test
    fun `merge clears stale outputs when a service file is removed`() {
        val root1 = tempFolder.newFolder("root1")
        val outputDir = tempFolder.newFolder("output")
        val staleFile = File(outputDir, "META-INF/services/com.example.RemovedService")
        staleFile.parentFile.mkdirs()
        staleFile.writeText("com.example.StaleImpl\n")

        val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder("proj")).build()
        val task =
            project.tasks
                .register("mergeServices", MergeServiceFilesTask::class.java) {
                    it.serviceFiles.from(
                        project.fileTree(root1) { spec -> spec.include("META-INF/services/**") }
                    )
                    it.outputDirectory.set(outputDir)
                }
                .get()

        task.merge()

        assertThat(staleFile.exists()).isFalse()
        assertThat(outputDir.list()).isEmpty()
    }

    @Test
    fun `merge fails fast on archive inputs`() {
        val outputDir = tempFolder.newFolder("output")
        val jarFile = tempFolder.newFile("embedded.jar")

        val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder("proj")).build()
        val task =
            project.tasks
                .register("mergeServices", MergeServiceFilesTask::class.java) {
                    it.serviceFiles.from(jarFile)
                    it.outputDirectory.set(outputDir)
                }
                .get()

        val ex = assertThrows(GradleException::class.java) { task.merge() }
        assertThat(ex.message).contains("not archive")
        assertThat(ex.message).contains("embedded.jar")
    }

    @Test
    fun `merge fails fast on standalone or mis-rooted service files`() {
        val root = tempFolder.newFolder("root")
        writeServiceFile(root, "com.example.Svc", "com.example.Impl\n")
        val standaloneFile = File(root, "META-INF/services/com.example.Svc")
        val outputDir = tempFolder.newFolder("output")

        val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder("proj")).build()

        // Passing a standalone File strips the META-INF/services/ relative prefix.
        val standaloneTask =
            project.tasks
                .register("mergeStandalone", MergeServiceFilesTask::class.java) {
                    it.serviceFiles.from(standaloneFile)
                    it.outputDirectory.set(outputDir)
                }
                .get()
        val standaloneEx = assertThrows(GradleException::class.java) { standaloneTask.merge() }
        assertThat(standaloneEx.message).contains("instead of 'META-INF/services/<serviceName>'")

        // Passing a FileTree rooted at META-INF/services/ also strips the required prefix.
        val misRootedTask =
            project.tasks
                .register("mergeMisRooted", MergeServiceFilesTask::class.java) {
                    it.serviceFiles.from(project.fileTree(File(root, "META-INF/services")))
                    it.outputDirectory.set(outputDir)
                }
                .get()
        val misRootedEx = assertThrows(GradleException::class.java) { misRootedTask.merge() }
        assertThat(misRootedEx.message).contains("instead of 'META-INF/services/<serviceName>'")

        // Passing a FileTree rooted at META-INF/ produces "services/<name>" instead of full prefix.
        val metaInfTask =
            project.tasks
                .register("mergeMetaInfRoot", MergeServiceFilesTask::class.java) {
                    it.serviceFiles.from(project.fileTree(File(root, "META-INF")))
                    it.outputDirectory.set(outputDir)
                }
                .get()
        val metaInfEx = assertThrows(GradleException::class.java) { metaInfTask.merge() }
        assertThat(metaInfEx.message).contains("instead of 'META-INF/services/<serviceName>'")
    }

    @Test
    fun `merge fails fast on nested subdirectories under META-INF services`() {
        val root = tempFolder.newFolder("root")
        writeServiceFile(root, "nested/com.example.Svc", "com.example.Impl\n")
        val outputDir = tempFolder.newFolder("output")

        val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder("proj")).build()
        val task =
            project.tasks
                .register("mergeNested", MergeServiceFilesTask::class.java) {
                    it.serviceFiles.from(project.fileTree(root))
                    it.outputDirectory.set(outputDir)
                }
                .get()

        val ex = assertThrows(GradleException::class.java) { task.merge() }
        assertThat(ex.message).contains("Invalid service path")
        assertThat(ex.message).contains("META-INF/services/nested/com.example.Svc")
    }

    /** Writes a `META-INF/services/<serviceName>` file under [root]. */
    private fun writeServiceFile(root: File, serviceName: String, content: String) {
        val file = File(root, "META-INF/services/$serviceName")
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
