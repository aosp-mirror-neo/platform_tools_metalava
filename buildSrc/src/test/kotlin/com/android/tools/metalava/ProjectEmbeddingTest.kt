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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Functional Gradle TestKit tests for Metalava project embedding and nested subproject groups. */
class ProjectEmbeddingTest {

    /** Isolated temporary directory for each TestKit workspace. */
    @get:Rule val tempFolder = TemporaryFolder()

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
}
