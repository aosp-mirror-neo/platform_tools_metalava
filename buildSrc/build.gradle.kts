/*
 * Copyright (C) 2020 The Android Open Source Project
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

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    `java-gradle-plugin`
}

dependencies {
    implementation(gradleApi())
    implementation(gradleKotlinDsl())
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.androidGradlePlugin)
    implementation(libs.gson)

    testImplementation(gradleTestKit())
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}

gradlePlugin {
    plugins {
        create("metalava-build-plugin") {
            id = "metalava-build-plugin"
            implementationClass = "com.android.tools.metalava.MetalavaBuildPlugin"
        }
        create("metalava-model-provider-plugin") {
            id = "metalava-model-provider-plugin"
            implementationClass = "com.android.tools.metalava.MetalavaModelProviderPlugin"
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

tasks.withType<Test>().configureEach {
    // Normalize relative METALAVA_PREBUILTS_REPOS entries against tools/metalava (rather than
    // buildSrc/) because TestKit writes them into generated settings.gradle.kts files inside
    // temporary directories.
    val rootProjectDir = layout.projectDirectory.dir("..").asFile
    val defaultPrebuiltsDir =
        layout.projectDirectory.dir("../../../prebuilts/metalava").asFile.canonicalPath
    val prebuiltsReposProvider =
        providers
            .environmentVariable("METALAVA_PREBUILTS_REPOS")
            .map { raw ->
                raw.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(",") { path ->
                        val file = File(path)
                        (if (file.isAbsolute) file else File(rootProjectDir, path)).canonicalPath
                    }
            }
            .orElse(defaultPrebuiltsDir)
    val downloadDependenciesProvider =
        providers.environmentVariable("DOWNLOAD_DEPENDENCIES").orElse("false")
    val kotlinVersion = libs.versions.kotlin.get()

    // Cap parallel forks to at most 3 because each TestKit worker starts/reuses a Gradle daemon;
    // this runs the 3 buildSrc test classes concurrently without excessive daemon memory pressure.
    maxHeapSize = "1024m"
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)

    // Track behavioral properties as task inputs, but populate machine-specific repository paths
    // inside doFirst so the buildSrc:test cache key remains relocatable across checkouts.
    inputs.property("kotlinVersion", kotlinVersion)
    inputs.property("downloadDependencies", downloadDependenciesProvider)
    doFirst {
        systemProperty("metalava.prebuilts.repos", prebuiltsReposProvider.get())
        systemProperty("metalava.download.dependencies", downloadDependenciesProvider.get())
    }
    testLogging {
        events = hashSetOf(TestLogEvent.FAILED, TestLogEvent.STANDARD_ERROR)
        exceptionFormat = TestExceptionFormat.FULL
    }
}
