/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.tools.metalava.testing

import java.io.File

/**
 * The default API level used in tests.
 *
 * Set to 34 as that was the first version in which the `java.lang.Record` class was added.
 */
private const val API_LEVEL = 34

/** Name of the environment variable pointing to `android.jar` for tests. */
private const val ENV_VAR_ANDROID_JAR = "METALAVA_ANDROID_JAR"

/** Name of the environment variable pointing to the `prebuilts-sdk` directory for tests. */
private const val ENV_VAR_PREBUILTS_SDK_DIR = "METALAVA_PREBUILTS_SDK_DIR"

/** Get a [File] for the `android.jar` specified by [ENV_VAR_ANDROID_JAR]. */
fun getAndroidJar(): File {
    val envValue =
        System.getenv(ENV_VAR_ANDROID_JAR)
            ?: error("Environment variable $ENV_VAR_ANDROID_JAR was not set")
    require(envValue.isNotBlank()) {
        "Invalid environment variable $ENV_VAR_ANDROID_JAR: '$envValue'"
    }
    val localFile = File(envValue).absoluteFile
    require(localFile.isFile) { "android.jar not found: ${localFile.path}" }
    return localFile
}

/** Get a [File] for the [apiSurface] `android.txt` of the specified [apiLevel]. */
fun getAndroidTxt(apiLevel: Int = API_LEVEL, apiSurface: String = "public"): File {
    val localFile = getPrebuiltsSdkDir().resolve("$apiLevel/$apiSurface/api/android.txt")
    if (!localFile.exists()) {
        error("Missing ${localFile.absolutePath} file in the SDK")
    }

    return localFile
}

/** The `prebuilts/sdk` directory used for testing, specified by [ENV_VAR_PREBUILTS_SDK_DIR]. */
fun getPrebuiltsSdkDir(): File {
    val envValue =
        System.getenv(ENV_VAR_PREBUILTS_SDK_DIR)
            ?: error("Environment variable $ENV_VAR_PREBUILTS_SDK_DIR was not set")
    require(envValue.isNotBlank()) {
        "Invalid environment variable $ENV_VAR_PREBUILTS_SDK_DIR: '$envValue'"
    }
    val dir = File(envValue).absoluteFile
    require(dir.isDirectory) { "prebuilts-sdk directory not found: ${dir.path}" }
    return dir
}
